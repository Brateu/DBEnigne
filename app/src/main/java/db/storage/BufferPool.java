package db.storage;

import java.io.IOException;
import java.nio.Buffer;
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

    private int frameIdOf(int pageId) {
        int frameId = pageIdToFrameId.getOrDefault(pageId, -1);
        if (frameId == -1) {
            throw new IllegalArgumentException("page with id " + pageId + " is not in the cache");
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
        if (oldFrame.isDirty()) {
            try {
                diskManager.write(oldPageId, oldFrame.getData());
            } catch (IOException e) {
                throw new RuntimeException("error while writing page " + oldPageId + " to disk", e);
            }
            oldFrame.clearDirty();
        }
        pageIdToFrameId.remove(oldPageId);
        return victimFrameId;
    }

    public Frame fetchPage(int pageId) {
        if (pageId < 0) {
            throw new IllegalArgumentException("pageId out of range, got " + pageId);
        }
        if (pageIdToFrameId.containsKey(pageId)) {
            int frameId = pageIdToFrameId.get(pageId);
            frames[frameId].pin();
            replacer.markNonEvictable(frameId);
            return frames[frameId];
        }
        byte[] localRead;
        try {
            localRead = diskManager.read(pageId);
        } catch (IOException e) {
            throw new RuntimeException("could not read the page with page id " + pageId, e);
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
            throw new RuntimeException("could not allocate page", e);
        }
        Frame newFrame = frames[newFrameId];
        newFrame.zeroData();
        newFrame.reset(pageId);
        pageIdToFrameId.put(pageId, newFrameId);
        newFrame.pin();
        return newFrame;
    }

    public void unpinPage(int pageId, boolean dirty) {
        if (pageId < 0) {
            throw new IllegalArgumentException("page id out of bounds, got " + pageId);
        }
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
        if (pageId < 0) {
            throw new IllegalArgumentException("page id out of bounds, got " + pageId);
        }
        int frameId = frameIdOf(pageId);
        Frame frame = frames[frameId];
        if (frame.isDirty()) {
            try {
                diskManager.write(pageId, frame.getData());
            } catch (IOException e) {
                throw new RuntimeException("error while writing page " + pageId + " to disk", e);
            }
        }
        frame.clearDirty();
    }

    public void flushAll() {
        final Set<Integer> keySet = pageIdToFrameId.keySet();
        for (int key : keySet) {
            flushPage(key);
        }
    }
}