package db.storage;

import java.io.IOException;
import java.nio.Buffer;
import java.util.*;

import static java.util.Objects.requireNonNull;

public class BufferPool {

    private final DiskManager diskManager;
    private final Frame[] frames;
    private final Map<Integer, Integer> pageTable;
    private final Deque<Integer> freeList;
    private final LruReplacer replacer;

    public BufferPool(DiskManager diskManager, int poolSize) {
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
        this.pageTable = new HashMap<>();
        this.replacer = new LruReplacer(poolSize);
    }

    public Frame fetchPage(int pageId) {
        if (pageId < 0) {
            throw new IllegalArgumentException("pageId " + pageId + " is out of bounds");
        }
        int frameId = pageTable.getOrDefault(pageId, 0);
        if (pageTable.containsKey(frameId)) {
            frames[frameId].pin();
            replacer.pin(frameId);
            return frames[frameId];
        } else {
            if (freeList.isEmpty()) {
                OptionalInt victimFrameId = replacer.victim();
                if (victimFrameId.isEmpty()) {
                    throw new IllegalStateException("No frame available to evict");
                }
                frameId = victimFrameId.getAsInt();
                Frame victimFrame = frames[frameId];
                if (victimFrame.isDirty()) {
                    try {
                        diskManager.write(victimFrame.getPageId(), victimFrame.getData());
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to write dirty page to disk", e);
                    }
                    pageTable.remove(frameId);
                } else {
                    frameId = findFreeFrame();
                }
                Frame frame = frames[frameId];
                try {
                    byte[] data = diskManager.read(pageId);
                    System.arraycopy(data, 0, frame.getData(), 0, data.length);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read page from disk", e);
                }
                frame.reset(pageId);
            }
            frame.pin();
            pageTable.put(pageId, frameId);
            return frame;
        }
    }

    public int findFreeFrame() {
        Object frameId = freeList.pollFirst();
        if (frameId == null) {
            throw new IllegalStateException("No frame available");
        }
        return (int) frameId;
    }

    private int frameIdOf(int pageId) {
        int frameId = pageTable.get(pageId);
        if (frameId == -1) {
            throw new IllegalArgumentException("pageId " + pageId + " is not in the buffer pool");
        }
        return frameId;
    }
}
