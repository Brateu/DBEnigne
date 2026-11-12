package db.storage;

public class ReadPageGuard implements AutoCloseable {

    private final BufferPool pool;
    private final Frame frame;
    private final PageId pageId;
    private boolean closed;

    ReadPageGuard(BufferPool pool, Frame frame) {
        this.pool = pool;
        this.frame = frame;
        this.pageId = frame.getPageId();
    }

    public PageId getPageId() {
        checkOpen();
        return this.pageId;
    }

    public byte[] getData() {
        checkOpen();
        return this.frame.getData();
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("guard for page " + this.pageId + " is already closed");
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            pool.unpinPage(this.pageId, false);
        }
    }
}
