package db.storage;

import java.io.IOException;
import java.util.*;

import static java.util.Objects.requireNonNull;

public class BufferPool {

    private final DiskManager diskManager;
    private final Frame[] frames;
    private final Map<Integer, Integer> pageIdToFrameId;
    private final Deque<Integer> freeList;
    private final Replacer replacer;

    BufferPool(DiskManager diskManager, int poolSize) {
        requireNonNull(diskManager, "diskManager cannot be null");
        if (poolSize <= 0) {
            throw new IllegalArgumentException("poolSize must be greater than 0, got " + poolSize);
        }
        this.diskManager = diskManager;
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
        if (frame.isDirty()) {
            try {
                diskManager.write(frame.getPageId(), frame.getData());
            } catch (IOException e) {
                throw new StorageException("error while writing page " + frame.getPageId() + " to disk", e);
            }
            frame.clearDirty();
        }
    }

    private int frameIdOf(int pageId) {
        int frameId = pageIdToFrameId.getOrDefault(pageId, -1);
        if (frameId == -1) {
            throw new IllegalArgumentException("page " + pageId + " is not resident in the buffer pool");
        }
        return frameId;
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
        int oldPageId = oldFrame.getPageId();
        try {
            writeBack(oldFrame);
        } catch (StorageException e) {
            replacer.markEvictable(victimFrameId);      //back to the tail on purpose: a failing write must not block evicting other frames.
            throw e;
        }
        pageIdToFrameId.remove(oldPageId);
        return victimFrameId;
    }

    public Frame fetchPage(int pageId) {
        if (pageId < 0) {
            throw new IllegalArgumentException("pageId must be non-negative, got " + pageId);
        }
        int frameId = pageIdToFrameId.getOrDefault(pageId, -1);
        if (frameId != -1) {
            frames[frameId].pin();
            replacer.markNonEvictable(frameId);
            return frames[frameId];
        }
        byte[] localRead;
        try {
            localRead = diskManager.read(pageId);
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

    public Frame newPage() {
        int newFrameId = acquireFrame();
        int pageId;
        try {
            pageId = diskManager.allocatePage();
        } catch (IOException e) {
            freeList.push(newFrameId);
            throw new StorageException("error while allocating a new page on disk for frame " + newFrameId, e);
        }
        Frame newFrame = frames[newFrameId];
        newFrame.zeroData();
        newFrame.reset(pageId);
        pageIdToFrameId.put(pageId, newFrameId);
        newFrame.pin();
        return newFrame;
    }

    public void unpinPage(int pageId, boolean dirty) {
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

    public void flushPage(int pageId) {
        int frameId = frameIdOf(pageId);
        Frame frame = frames[frameId];
        writeBack(frame);
    }

    public void flushAll() {
        for (int key : pageIdToFrameId.keySet()) {
            flushPage(key);
        }
    }
}