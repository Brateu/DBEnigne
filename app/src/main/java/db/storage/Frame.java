package db.storage;

import static db.storage.DiskManager.PAGE_SIZE;

public class Frame {

    public static final int NO_PAGE = -1;
    private final byte[] data;
    private int pageId;
    private int pinCount;
    private boolean dirty;

    public Frame() {
        this.data = new byte[PAGE_SIZE];
        this.pageId = NO_PAGE;
        this.pinCount = 0;
        this.dirty = false;
    }

    public byte[] getData() {
        return data;
    }

    public int getPageId() {
        return pageId;
    }

    int getPinCount() {
        return pinCount;
    }

    boolean isDirty() {
        return dirty;
    }

    void pin() {
        pinCount++;
    }

    void unpin() {
        if (pinCount == 0) {
            throw new IllegalStateException("cannot unpin page " + getPageId() + ", pin count is 0");
        }
        pinCount--;
    }

    void reset(int pageId) {
        if (getPinCount() != 0) {
            throw new IllegalStateException("cannot reset page " + getPageId() + ", pin count is " + getPinCount());
        }
        if (isDirty()) {
            throw new IllegalStateException("cannot reset page " + getPageId() + ", page is dirty");
        }
        this.pageId = pageId;
    }

    void markDirty() {
        this.dirty = true;
    }

    void clearDirty() {
        this.dirty = false;
    }

    void zeroData() {
        for (int i = 0; i < PAGE_SIZE; i++){
            this.data[i] = 0;
        }
    }
}