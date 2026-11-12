package db.storage.buffer;

import db.storage.PageId;
import db.storage.disk.DiskManager;
import db.storage.disk.StorageManager;
import db.storage.page.CorruptPageException;
import db.storage.page.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import static db.storage.disk.DiskManager.PAGE_SIZE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pin state is observed through behavior: in a pool of 1, a page that is still pinned makes
 * newPage throw "pool exhausted" (ISE); once every pin is released, newPage can evict it.
 */
class PageGuardTest {

    private static final int FILE = 0;

    @TempDir
    Path tempDir;

    private StorageManager storage;
    private DiskManager dm;

    @BeforeEach
    void openStorage() throws Exception {
        storage = new StorageManager(tempDir);
        dm = storage.open(FILE);
    }

    @AfterEach
    void closeStorage() throws Exception {
        storage.close();
    }

    private BufferPool pool(int size) {
        return new BufferPool(storage, size);
    }

    private static PageId pid(int pageNo) {
        return new PageId(FILE, pageNo);
    }

    private static byte[] patternPage(int seed) {
        byte[] page = new byte[PAGE_SIZE];
        for (int i = 0; i < PAGE_SIZE; i++) {
            page[i] = (byte) (i + seed);
        }
        return page;
    }

    private static void write(byte[] target, byte[] bytes) {
        System.arraycopy(bytes, 0, target, 0, PAGE_SIZE);
    }

    /** Pages that went through write-back carry a checksum in bytes 8..11; compare everything else. */
    private static void assertSameIgnoringChecksum(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            if (i < 8 || i >= 12) {
                assertEquals(expected[i], actual[i], "byte " + i + " differs");
            }
        }
    }

    /** Creates page 0 on disk with the given content, through the pool so it gets a valid checksum. */
    private void createPageOnDisk(byte[] content) {
        BufferPool writer = pool(1);
        write(writer.newPage(FILE).getData(), content);
        writer.unpinPage(pid(0), true);
        writer.flushPage(pid(0));
    }

    /** Pool of 1: succeeds only if the resident page has no pins left. */
    private static void assertUnpinned(BufferPool bp) {
        assertDoesNotThrow(() -> bp.newPage(FILE), "page should have been unpinned");
    }

    /** Pool of 1: the resident page is still pinned, so nothing can be evicted. */
    private static void assertStillPinned(BufferPool bp) {
        assertThrows(IllegalStateException.class, () -> bp.newPage(FILE), "page should still be pinned");
    }

    // ---- ReadPageGuard ----

    @Test
    void readGuardExposesPageIdAndBytesFromDisk() {
        createPageOnDisk(patternPage(3));
        BufferPool bp = pool(2);

        try (ReadPageGuard g = bp.fetchPageRead(pid(0))) {
            assertEquals(pid(0), g.getPageId());
            assertSameIgnoringChecksum(patternPage(3), g.getData());
        }
    }

    @Test
    void readGuardHoldsPinUntilClosed() {
        createPageOnDisk(patternPage(1));
        BufferPool bp = pool(1);

        ReadPageGuard g = bp.fetchPageRead(pid(0));
        assertStillPinned(bp);

        g.close();
        assertUnpinned(bp);
    }

    @Test
    void readGuardUnpinsClean() {
        // A read guard promises "not modified": even if the bytes were changed, they are not written back.
        BufferPool bp = pool(1);
        bp.newPage(FILE);
        bp.unpinPage(pid(0), false);

        try (ReadPageGuard g = bp.fetchPageRead(pid(0))) {
            write(g.getData(), patternPage(7));
        }
        bp.newPage(FILE);              // evicts page 0 without write-back

        assertArrayEquals(new byte[PAGE_SIZE], readDisk(0));
    }

    @Test
    void twoReadGuardsShareTheSameFrame() {
        createPageOnDisk(patternPage(2));
        BufferPool bp = pool(2);

        try (ReadPageGuard a = bp.fetchPageRead(pid(0));
             ReadPageGuard b = bp.fetchPageRead(pid(0))) {
            assertSame(a.getData(), b.getData());
        }
    }

    @Test
    void eachReadGuardOwnsItsOwnPin() {
        createPageOnDisk(patternPage(2));
        BufferPool bp = pool(1);

        ReadPageGuard a = bp.fetchPageRead(pid(0));
        ReadPageGuard b = bp.fetchPageRead(pid(0));

        a.close();
        assertStillPinned(bp);         // b still holds its pin
        b.close();
        assertUnpinned(bp);
    }

    // ---- WritePageGuard ----

    @Test
    void writeGuardChangesReachDisk() {
        createPageOnDisk(patternPage(1));
        BufferPool bp = pool(1);

        try (WritePageGuard g = bp.fetchPageWrite(pid(0))) {
            assertEquals(pid(0), g.getPageId());
            write(g.getData(), patternPage(9));
        }
        bp.newPage(FILE);              // evicts page 0: must write it back

        assertSameIgnoringChecksum(patternPage(9), readDisk(0));
    }

    @Test
    void writeGuardMarksDirtyEvenWithoutChanges() {
        // Design choice 2A: closing a write guard always marks dirty, so the page is written on eviction.
        BufferPool bp = pool(1);
        bp.newPage(FILE);
        bp.unpinPage(pid(0), false);   // page 0 is all zeros and clean, nothing on disk but zeros

        bp.fetchPageWrite(pid(0)).close();
        bp.newPage(FILE);              // evicts page 0

        byte[] onDisk = readDisk(0);
        assertTrue(Page.verifyChecksum(onDisk, 0));
        assertNotEquals(0, Page.computeChecksum(onDisk, 0), "precondition for the next assertion");
        assertFalse(Arrays.equals(new byte[PAGE_SIZE], onDisk), "write-back should have stamped a checksum");
    }

    @Test
    void writeGuardHoldsPinUntilClosed() {
        createPageOnDisk(patternPage(1));
        BufferPool bp = pool(1);

        WritePageGuard g = bp.fetchPageWrite(pid(0));
        assertStillPinned(bp);

        g.close();
        assertUnpinned(bp);
    }

    @Test
    void writeGuardChangesAreVisibleToLaterReadGuard() {
        BufferPool bp = pool(2);
        try (WritePageGuard w = bp.newPageGuarded(FILE)) {
            write(w.getData(), patternPage(4));
        }

        try (ReadPageGuard r = bp.fetchPageRead(pid(0))) {
            assertArrayEquals(patternPage(4), r.getData());   // still resident, never written: no checksum yet
        }
    }

    @Test
    void cleanReadGuardDoesNotUndoEarlierWriteGuard() {
        // The dirty flag is OR-ed: a later clean unpin must not lose an earlier write.
        BufferPool bp = pool(1);
        WritePageGuard w = bp.newPageGuarded(FILE);
        ReadPageGuard r = bp.fetchPageRead(pid(0));
        write(w.getData(), patternPage(6));

        w.close();
        r.close();
        bp.newPage(FILE);              // evicts page 0

        assertSameIgnoringChecksum(patternPage(6), readDisk(0));
    }

    // ---- newPageGuarded ----

    @Test
    void newPageGuardedReturnsZeroedPinnedPageWithSequentialIds() {
        BufferPool bp = pool(3);

        try (WritePageGuard a = bp.newPageGuarded(FILE);
             WritePageGuard b = bp.newPageGuarded(FILE)) {
            assertEquals(pid(0), a.getPageId());
            assertEquals(pid(1), b.getPageId());
            assertArrayEquals(new byte[PAGE_SIZE], a.getData());
            assertEquals(2, dm.getNumPages());
        }
    }

    @Test
    void newPageGuardedHoldsPinUntilClosed() {
        BufferPool bp = pool(1);

        WritePageGuard g = bp.newPageGuarded(FILE);
        assertStillPinned(bp);

        g.close();
        assertUnpinned(bp);
    }

    @Test
    void newPageGuardedContentReachesDisk() {
        BufferPool bp = pool(1);
        try (WritePageGuard g = bp.newPageGuarded(FILE)) {
            write(g.getData(), patternPage(5));
        }

        bp.newPage(FILE);              // evicts page 0

        assertSameIgnoringChecksum(patternPage(5), readDisk(0));
    }

    // ---- try-with-resources and exceptions ----

    @Test
    void exceptionInsideTryStillUnpins() {
        createPageOnDisk(patternPage(1));
        BufferPool bp = pool(1);

        assertThrows(RuntimeException.class, () -> {
            try (ReadPageGuard g = bp.fetchPageRead(pid(0))) {
                throw new RuntimeException("boom");
            }
        });

        assertUnpinned(bp);
    }

    @Test
    void exceptionInsideWriteGuardStillUnpinsAndMarksDirty() {
        // Partial changes are still written: without WAL there is no rollback. Phase 4 changes this story.
        createPageOnDisk(patternPage(1));
        BufferPool bp = pool(1);

        assertThrows(RuntimeException.class, () -> {
            try (WritePageGuard g = bp.fetchPageWrite(pid(0))) {
                write(g.getData(), patternPage(8));
                throw new RuntimeException("boom");
            }
        });
        bp.newPage(FILE);              // evicts page 0

        assertSameIgnoringChecksum(patternPage(8), readDisk(0));
    }

    @Test
    void failedFetchCreatesNoGuardAndLeaksNoFrame() {
        BufferPool bp = pool(1);

        assertThrows(IllegalArgumentException.class, () -> bp.fetchPageRead(pid(99)));   // not on disk
        assertThrows(IllegalArgumentException.class, () -> bp.fetchPageWrite(pid(99)));

        assertUnpinned(bp);
    }

    @Test
    void corruptPageThrowsFromGuardedFetch() throws Exception {
        dm.allocatePage();
        dm.write(0, patternPage(5));   // non-zero and never stamped
        BufferPool bp = pool(1);

        assertThrows(CorruptPageException.class, () -> bp.fetchPageRead(pid(0)));
        assertThrows(CorruptPageException.class, () -> bp.fetchPageWrite(pid(0)));

        assertUnpinned(bp);
    }

    @Test
    void guardedFetchRejectsNull() {
        BufferPool bp = pool(1);

        assertThrows(NullPointerException.class, () -> bp.fetchPageRead(null));
        assertThrows(NullPointerException.class, () -> bp.fetchPageWrite(null));
    }

    @Test
    void newPageGuardedOnUnknownFileThrows() {
        BufferPool bp = pool(1);

        assertThrows(IllegalArgumentException.class, () -> bp.newPageGuarded(99));

        assertUnpinned(bp);
    }

    // ---- close semantics ----

    @Test
    void closeIsIdempotent() {
        createPageOnDisk(patternPage(1));
        BufferPool bp = pool(1);
        ReadPageGuard r = bp.fetchPageRead(pid(0));
        WritePageGuard w = bp.fetchPageWrite(pid(0));

        r.close();
        assertDoesNotThrow(r::close);
        w.close();
        assertDoesNotThrow(w::close);
    }

    @Test
    void secondCloseDoesNotReleaseSomeoneElsesPin() {
        // If close() unpinned again, it would silently drop b's pin and the page could be evicted under b.
        createPageOnDisk(patternPage(1));
        BufferPool bp = pool(1);
        ReadPageGuard a = bp.fetchPageRead(pid(0));
        ReadPageGuard b = bp.fetchPageRead(pid(0));

        a.close();
        a.close();

        assertStillPinned(bp);
        b.close();
        assertUnpinned(bp);
    }

    @Test
    void explicitCloseInsideTryWithResourcesIsSafe() {
        createPageOnDisk(patternPage(1));
        BufferPool bp = pool(1);

        assertDoesNotThrow(() -> {
            try (WritePageGuard g = bp.fetchPageWrite(pid(0))) {
                g.close();             // try-with-resources closes it again
            }
        });
        assertUnpinned(bp);
    }

    @Test
    void readGuardUseAfterCloseThrows() {
        createPageOnDisk(patternPage(1));
        BufferPool bp = pool(1);
        ReadPageGuard g = bp.fetchPageRead(pid(0));
        g.close();

        assertThrows(IllegalStateException.class, g::getData);
        assertThrows(IllegalStateException.class, g::getPageId);
    }

    @Test
    void writeGuardUseAfterCloseThrows() {
        BufferPool bp = pool(1);
        WritePageGuard g = bp.newPageGuarded(FILE);
        g.close();

        assertThrows(IllegalStateException.class, g::getData);
        assertThrows(IllegalStateException.class, g::getPageId);
    }

    @Test
    void guardsAreAutoCloseable() {
        assertTrue(AutoCloseable.class.isAssignableFrom(ReadPageGuard.class));
        assertTrue(AutoCloseable.class.isAssignableFrom(WritePageGuard.class));
    }

    @Test
    void guardCloseDeclaresNoCheckedException() throws Exception {
        // close() must not declare "throws Exception", or every try-with-resources would need a catch.
        assertEquals(0, ReadPageGuard.class.getMethod("close").getExceptionTypes().length);
        assertEquals(0, WritePageGuard.class.getMethod("close").getExceptionTypes().length);
    }

    private byte[] readDisk(int pageNo) {
        try {
            return dm.read(pageNo);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
