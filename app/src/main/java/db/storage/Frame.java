package db.storage;

import static db.storage.DiskManager.PAGE_SIZE;
import static java.util.Objects.requireNonNull;

public class Frame {

    private final byte[] data;
    private PageId pageId;
    private int pinCount;
    private boolean dirty;

    public Frame() {
        this.data = new byte[PAGE_SIZE];
        this.pinCount = 0;
        this.pageId = null;
        this.dirty = false;
    }

    public byte[] getData() {
        return data;
    }

    public PageId getPageId() {
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

    void reset(PageId pageId) {
        requireNonNull(pageId, "pageId must not be null");
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