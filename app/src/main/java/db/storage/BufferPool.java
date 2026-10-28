package db.storage;

import java.io.IOException;
import java.util.*;

import static java.util.Objects.requireNonNull;

public class BufferPool {

    private final StorageManager storage;
    private final Frame[] frames;
    private final Map<PageId, Integer> pageIdToFrameId;
    private final Deque<Integer> freeList;
    private final Replacer replacer;

    BufferPool(StorageManager storage, int poolSize) {
        requireNonNull(storage, "storageManager cannot be null");
        if (poolSize <= 0) {
            throw new IllegalArgumentException("poolSize must be greater than 0, got " + poolSize);
        }
        this.storage = storage;
        this.freeList = new ArrayDeque<>();
        this.frames = new Frame[poolSize];
        for (int i = 0; i < poolSize; i++) {
            frames[i] = new Frame();
            freeList.add(i);
        }
        this.pageIdToFrameId = new HashMap<>();
        this.replacer = new LruReplacer(poolSize);
    }

    private void writeBack(Frame frame) {
        PageId pageId = frame.getPageId();
        if (frame.isDirty()) {
            try {
                diskOf(pageId).write(pageId.pageNo(), frame.getData());
            } catch (IOException e) {
                throw new StorageException("error while writing page " + pageId + " to disk", e);
            }
            frame.clearDirty();
        }
    }

    private int frameIdOf(PageId pageId) {
        int frameId = pageIdToFrameId.getOrDefault(pageId, -1);
        if (frameId == -1) {
            throw new IllegalArgumentException("page " + pageId + " is not resident in the buffer pool");
        }
        return frameId;
    }

    private DiskManager diskOf(PageId pageId) {
        return storage.get(pageId.fileId());
    }

    private int acquireFrame() {
        if (!freeList.isEmpty()) {
            return freeList.pop();
        }
        OptionalInt victim = replacer.victim();
        if (victim.isEmpty()) {
            throw new IllegalStateException("no frame available to evict, the pool is exhausted");
        }
        int victimFrameId = victim.getAsInt();
        Frame oldFrame = frames[victimFrameId];
        PageId oldPageId = oldFrame.getPageId();
        try {
            writeBack(oldFrame);
        } catch (StorageException e) {
            replacer.markEvictable(victimFrameId);      //back to the tail on purpose: a failing write must not block evicting other frames.
            throw e;
        }
        pageIdToFrameId.remove(oldPageId);
        return victimFrameId;
    }

    public Frame fetchPage(PageId pageId) {
        requireNonNull(pageId, "pageId must not be null");
        int frameId = pageIdToFrameId.getOrDefault(pageId, -1);
        if (frameId != -1) {
            frames[frameId].pin();
            replacer.markNonEvictable(frameId);
            return frames[frameId];
        }
        byte[] localRead;
        try {
            localRead = diskOf(pageId).read(pageId.pageNo());
        } catch (IOException e) {
            throw new StorageException("error while reading page " + pageId + " from disk", e);
        }
        int newFrameId = acquireFrame();
        Frame newFrame = frames[newFrameId];
        System.arraycopy(localRead, 0, newFrame.getData(), 0, localRead.length);
        newFrame.reset(pageId);
        pageIdToFrameId.put(pageId, newFrameId);
        newFrame.pin();
        return newFrame;
    }

    public Frame newPage(int fileId) {
        DiskManager disk = storage.get(fileId);
        int newFrameId = acquireFrame();
        int pageNo;
        try {
            pageNo = disk.allocatePage();
        } catch (IOException e) {
            freeList.push(newFrameId);
            throw new StorageException("error while allocating a new page in file " + fileId + " for frame " + newFrameId, e);
        }
        PageId pageId = new PageId(fileId, pageNo);
        Frame newFrame = frames[newFrameId];
        newFrame.zeroData();
        newFrame.reset(pageId);
        pageIdToFrameId.put(pageId, newFrameId);
        newFrame.pin();
        return newFrame;
    }

    public void unpinPage(PageId pageId, boolean dirty) {
        int frameId = frameIdOf(pageId);
        Frame frame = frames[frameId];
        frame.unpin();
        if (dirty) {
            frame.markDirty();
        }
        if (frame.getPinCount() == 0) {
            replacer.markEvictable(frameId);
        }
    }

    public void flushPage(PageId pageId) {
        int frameId = frameIdOf(pageId);
        Frame frame = frames[frameId];
        writeBack(frame);
    }

    public void flushAll() {
        for (PageId key : pageIdToFrameId.keySet()) {
            flushPage(key);
        }
    }
}