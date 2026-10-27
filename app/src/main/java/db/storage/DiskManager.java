package db.storage;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Objects;

import static java.nio.file.StandardOpenOption.*;

public class DiskManager implements Closeable {

    public static final int PAGE_SIZE = 4096;

    private final FileChannel channel;

    public DiskManager(Path path) throws IOException {
        Objects.requireNonNull(path, "path must not be null");
        channel = FileChannel.open(path, READ, WRITE, CREATE);
    }

    public byte[] read(int pageId) throws IOException {
        checkPageId(pageId);
        long offset = (long) pageId * PAGE_SIZE;
        ByteBuffer buffer = ByteBuffer.allocate(PAGE_SIZE);
        while (buffer.hasRemaining()) {
            int bytesRead = channel.read(buffer, offset + buffer.position());
            if (bytesRead == -1) {
                throw new EOFException("page " + pageId + " truncated, got " + buffer.position() + " bytes, expected " + PAGE_SIZE);
            }
        }
        return buffer.array();
    }

    public void write(int pageId, byte[] data) throws IOException {
        Objects.requireNonNull(data, "data must not be null");
        if (data.length != PAGE_SIZE) {
            throw new IllegalArgumentException("data length " + data.length + " is invalid, should be " + PAGE_SIZE);
        }
        checkPageId(pageId);
        writeRaw(pageId, data);
    }

    public int allocatePage() throws IOException {
        int pageId = getNumPages();
        byte[] emptyPage = new byte[PAGE_SIZE];
        writeRaw(pageId, emptyPage);
        return pageId;
    }

    public int getNumPages() throws IOException {
        return (int) (channel.size() / PAGE_SIZE);
    }

    public void sync() throws IOException {
        channel.force(true);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    private void writeRaw(int pageId, byte[] data) throws IOException {
        long offset = (long) pageId * PAGE_SIZE;
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) {
            channel.write(buffer, offset + buffer.position());
        }
    }

    private void checkPageId(int pageId) throws IOException {
        int numPages = getNumPages();
        if (pageId < 0 || pageId >= numPages) {
            throw new IllegalArgumentException("pageId " + pageId + " is out of range, file has " + numPages + " pages");
        }
    }
}

