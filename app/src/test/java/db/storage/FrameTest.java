package db.storage;

import org.junit.jupiter.api.Test;

import static db.storage.DiskManager.PAGE_SIZE;
import static org.junit.jupiter.api.Assertions.*;

class FrameTest {

    private static final PageId P3 = new PageId(0, 3);
    private static final PageId P7 = new PageId(0, 7);

    @Test
    void newFrameIsEmpty() {
        Frame frame = new Frame();

        assertNull(frame.getPageId());
        assertEquals(0, frame.getPinCount());
        assertFalse(frame.isDirty());
        assertEquals(PAGE_SIZE, frame.getData().length);
    }

    @Test
    void pinAndUnpinCount() {
        Frame frame = new Frame();

        frame.pin();
        frame.pin();
        assertEquals(2, frame.getPinCount());

        frame.unpin();
        assertEquals(1, frame.getPinCount());
        frame.unpin();
        assertEquals(0, frame.getPinCount());
    }

    @Test
    void unpinAtZeroThrows() {
        Frame frame = new Frame();

        assertThrows(IllegalStateException.class, frame::unpin);
        assertEquals(0, frame.getPinCount());
    }

    @Test
    void markDirtySetsFlag() {
        Frame frame = new Frame();

        frame.markDirty();

        assertTrue(frame.isDirty());
    }

    @Test
    void clearDirtyClearsFlag() {
        Frame frame = new Frame();
        frame.markDirty();

        frame.clearDirty();

        assertFalse(frame.isDirty());
    }

    @Test
    void resetSwitchesCleanUnpinnedFrameToNewPage() {
        Frame frame = new Frame();
        frame.reset(P3);

        frame.reset(P7);

        assertEquals(P7, frame.getPageId());
        assertEquals(0, frame.getPinCount());
        assertFalse(frame.isDirty());
    }

    @Test
    void resetRejectsNull() {
        // A mapped frame holding null would look empty and NPE later during eviction.
        Frame frame = new Frame();
        frame.reset(P3);

        assertThrows(NullPointerException.class, () -> frame.reset(null));
        assertEquals(P3, frame.getPageId());
    }

    @Test
    void resetPinnedFrameThrows() {
        Frame frame = new Frame();
        frame.reset(P3);
        frame.pin();

        assertThrows(IllegalStateException.class, () -> frame.reset(P7));
        assertEquals(P3, frame.getPageId());
        assertEquals(1, frame.getPinCount());
    }

    @Test
    void resetDirtyFrameThrows() {
        Frame frame = new Frame();
        frame.reset(P3);
        frame.markDirty();

        assertThrows(IllegalStateException.class, () -> frame.reset(P7));
        assertEquals(P3, frame.getPageId());
        assertTrue(frame.isDirty());
    }

    @Test
    void resetAfterWriteBackSequenceSucceeds() {
        // The order the buffer pool must follow when evicting: unpin, write back, clear dirty, reset.
        Frame frame = new Frame();
        frame.reset(P3);
        frame.pin();
        frame.markDirty();

        frame.unpin();
        frame.clearDirty();
        frame.reset(P7);

        assertEquals(P7, frame.getPageId());
    }

    @Test
    void dataArrayIsSameObjectForFrameLifetime() {
        Frame frame = new Frame();
        byte[] before = frame.getData();
        before[0] = 42;

        frame.reset(P3);

        assertSame(before, frame.getData());
        assertEquals(42, frame.getData()[0]);
    }

    @Test
    void zeroDataClearsEveryByteInPlace() {
        Frame frame = new Frame();
        byte[] data = frame.getData();
        java.util.Arrays.fill(data, (byte) 9);

        frame.zeroData();

        assertSame(data, frame.getData());
        assertArrayEquals(new byte[PAGE_SIZE], frame.getData());
    }
}
