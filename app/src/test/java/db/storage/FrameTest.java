package db.storage;

import org.junit.jupiter.api.Test;

import static db.storage.DiskManager.PAGE_SIZE;
import static org.junit.jupiter.api.Assertions.*;

class FrameTest {

    @Test
    void newFrameIsEmpty() {
        Frame frame = new Frame();

        assertEquals(Frame.NO_PAGE, frame.getPageId());
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
        frame.reset(3);

        frame.reset(7);

        assertEquals(7, frame.getPageId());
        assertEquals(0, frame.getPinCount());
        assertFalse(frame.isDirty());
    }

    @Test
    void resetPinnedFrameThrows() {
        Frame frame = new Frame();
        frame.reset(3);
        frame.pin();

        assertThrows(IllegalStateException.class, () -> frame.reset(7));
        assertEquals(3, frame.getPageId());
        assertEquals(1, frame.getPinCount());
    }

    @Test
    void resetDirtyFrameThrows() {
        Frame frame = new Frame();
        frame.reset(3);
        frame.markDirty();

        assertThrows(IllegalStateException.class, () -> frame.reset(7));
        assertEquals(3, frame.getPageId());
        assertTrue(frame.isDirty());
    }

    @Test
    void resetAfterWriteBackSequenceSucceeds() {
        // The order the buffer pool must follow when evicting: unpin, write back, clear dirty, reset.
        Frame frame = new Frame();
        frame.reset(3);
        frame.pin();
        frame.markDirty();

        frame.unpin();
        frame.clearDirty();
        frame.reset(7);

        assertEquals(7, frame.getPageId());
    }

    @Test
    void dataArrayIsSameObjectForFrameLifetime() {
        Frame frame = new Frame();
        byte[] before = frame.getData();
        before[0] = 42;

        frame.reset(5);

        assertSame(before, frame.getData());
        assertEquals(42, frame.getData()[0]);
    }
}
