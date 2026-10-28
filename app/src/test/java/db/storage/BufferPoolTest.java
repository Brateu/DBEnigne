package db.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.ClosedChannelException;
import java.nio.file.Path;

import static db.storage.DiskManager.PAGE_SIZE;
import static org.junit.jupiter.api.Assertions.*;

class BufferPoolTest {

    @TempDir
    Path tempDir;

    private DiskManager dm;

    @BeforeEach
    void openDisk() throws Exception {
        dm = new DiskManager(tempDir.resolve("test.db"));
    }

    @AfterEach
    void closeDisk() throws Exception {
        dm.close();
    }

    private BufferPool pool(int size) {
        return new BufferPool(dm, size);
    }

    private static byte[] patternPage(int seed) {
        byte[] page = new byte[PAGE_SIZE];
        for (int i = 0; i < PAGE_SIZE; i++) {
            page[i] = (byte) (i + seed);
        }
        return page;
    }

    /** Simulates a caller modifying a page it has pinned. */
    private static void write(Frame frame, byte[] bytes) {
        System.arraycopy(bytes, 0, frame.getData(), 0, PAGE_SIZE);
    }

    // ---- constructor ----

    @Test
    void constructorRejectsNullDiskManager() {
        assertThrows(NullPointerException.class, () -> new BufferPool(null, 2));
    }

    @Test
    void constructorRejectsNonPositiveSize() {
        assertThrows(IllegalArgumentException.class, () -> pool(0));
        assertThrows(IllegalArgumentException.class, () -> pool(-1));
    }

    // ---- newPage ----

    @Test
    void newPageReturnsPinnedFramesWithSequentialIds() throws Exception {
        BufferPool bp = pool(3);
        Frame f0 = bp.newPage();
        Frame f1 = bp.newPage();

        assertEquals(0, f0.getPageId());
        assertEquals(1, f1.getPageId());
        assertEquals(1, f0.getPinCount());
        assertEquals(2, dm.getNumPages());
    }

    @Test
    void newPageZeroesReusedFrame() {
        // Pool of 1: the second newPage must reuse the frame that held page 0's bytes.
        BufferPool bp = pool(1);
        Frame f0 = bp.newPage();
        write(f0, patternPage(7));
        bp.unpinPage(0, true);

        Frame f1 = bp.newPage();

        assertSame(f0, f1);
        assertArrayEquals(new byte[PAGE_SIZE], f1.getData());
    }

    @Test
    void newPageOnExhaustedPoolThrowsAndDoesNotAllocateOnDisk() throws Exception {
        // Guards the "frame before allocatePage" ordering: a full pool must not waste a disk page.
        BufferPool bp = pool(2);
        bp.newPage();
        bp.newPage();

        assertThrows(IllegalStateException.class, bp::newPage);
        assertEquals(2, dm.getNumPages());
    }

    // ---- fetchPage ----

    @Test
    void fetchPageMissReadsBytesFromDisk() throws Exception {
        int pageId = dm.allocatePage();
        dm.write(pageId, patternPage(3));
        BufferPool bp = pool(2);

        Frame frame = bp.fetchPage(pageId);

        assertEquals(pageId, frame.getPageId());
        assertArrayEquals(patternPage(3), frame.getData());
        assertEquals(1, frame.getPinCount());
    }

    @Test
    void fetchPageHitReturnsSameFrameAndPinsAgain() {
        BufferPool bp = pool(2);
        Frame first = bp.newPage();

        Frame second = bp.fetchPage(0);

        assertSame(first, second);
        assertEquals(2, second.getPinCount());
    }

    @Test
    void fetchPageRejectsNegativeId() {
        assertThrows(IllegalArgumentException.class, () -> pool(2).fetchPage(-1));
    }

    @Test
    void failedFetchDoesNotLeakFrame() {
        // Pool of 1: if a failed read had already taken the only frame, the newPage below would throw.
        BufferPool bp = pool(1);

        assertThrows(IllegalArgumentException.class, () -> bp.fetchPage(99));   // page doesn't exist on disk

        assertDoesNotThrow(bp::newPage);
    }

    @Test
    void fetchHitOnEvictableFrameMakesItNonEvictableAgain() {
        // Regression for the hit path: refetching an unpinned page must remove it from eviction candidates.
        BufferPool bp = pool(2);
        bp.newPage();
        bp.unpinPage(0, false);   // page 0 is now evictable

        bp.fetchPage(0);          // pinned again: must no longer be evictable
        bp.newPage();             // takes the last free frame

        assertThrows(IllegalStateException.class, bp::newPage);
    }

    // ---- eviction ----

    @Test
    void pinnedPageIsNeverEvicted() {
        BufferPool bp = pool(2);
        Frame f0 = bp.newPage();   // stays pinned
        Frame f1 = bp.newPage();
        bp.unpinPage(1, false);

        Frame f2 = bp.newPage();   // must evict page 1, the only candidate

        assertSame(f1, f2);
        assertEquals(0, f0.getPageId());
    }

    @Test
    void evictsLeastRecentlyUnpinnedPage() {
        BufferPool bp = pool(2);
        Frame f0 = bp.newPage();
        Frame f1 = bp.newPage();
        bp.unpinPage(0, false);
        bp.unpinPage(1, false);   // order so far: 0 oldest, 1 newest

        bp.fetchPage(0);
        bp.unpinPage(0, false);   // 0 used again: now 1 is oldest

        Frame f2 = bp.newPage();

        assertSame(f1, f2);
        assertEquals(0, f0.getPageId());
    }

    @Test
    void exhaustedPoolThrowsOnFetch() throws Exception {
        dm.allocatePage();
        dm.allocatePage();
        dm.allocatePage();
        BufferPool bp = pool(2);
        bp.fetchPage(0);
        bp.fetchPage(1);

        assertThrows(IllegalStateException.class, () -> bp.fetchPage(2));
    }

    @Test
    void dirtyPageSurvivesEviction() throws Exception {
        BufferPool bp = pool(1);
        write(bp.newPage(), patternPage(5));
        bp.unpinPage(0, true);

        bp.newPage();             // evicts page 0: must write it back first
        bp.unpinPage(1, false);

        assertArrayEquals(patternPage(5), dm.read(0));
        assertArrayEquals(patternPage(5), bp.fetchPage(0).getData());
    }

    @Test
    void cleanPageIsNotWrittenBackOnEviction() throws Exception {
        // unpin(false) promises "I didn't modify it", so eviction skips the write and the disk stays zeros.
        BufferPool bp = pool(1);
        write(bp.newPage(), patternPage(5));
        bp.unpinPage(0, false);

        bp.newPage();

        assertArrayEquals(new byte[PAGE_SIZE], dm.read(0));
    }

    // ---- unpinPage ----

    @Test
    void dirtyFlagIsNotClearedByLaterCleanUnpin() throws Exception {
        // Two users: A modifies and unpins dirty, B only read and unpins clean. A's change must still reach disk.
        BufferPool bp = pool(1);
        Frame frame = bp.newPage();
        bp.fetchPage(0);
        write(frame, patternPage(9));
        bp.unpinPage(0, true);
        bp.unpinPage(0, false);

        bp.newPage();             // evicts page 0

        assertArrayEquals(patternPage(9), dm.read(0));
    }

    @Test
    void pageStaysNonEvictableUntilPinCountReachesZero() {
        BufferPool bp = pool(1);
        bp.newPage();
        bp.fetchPage(0);          // pin count 2
        bp.unpinPage(0, false);   // pin count 1: still in use

        assertThrows(IllegalStateException.class, bp::newPage);

        bp.unpinPage(0, false);   // pin count 0: now evictable
        assertDoesNotThrow(bp::newPage);
    }

    @Test
    void unpinNonResidentPageThrows() {
        BufferPool bp = pool(2);
        assertThrows(IllegalArgumentException.class, () -> bp.unpinPage(0, false));
        assertThrows(IllegalArgumentException.class, () -> bp.unpinPage(-1, false));
    }

    @Test
    void unpinMoreTimesThanPinnedThrows() {
        BufferPool bp = pool(2);
        bp.newPage();
        bp.unpinPage(0, false);

        assertThrows(IllegalStateException.class, () -> bp.unpinPage(0, false));
    }

    // ---- flushPage / flushAll ----

    @Test
    void flushPageWritesDirtyPageWithoutEvicting() throws Exception {
        BufferPool bp = pool(2);
        Frame frame = bp.newPage();
        bp.fetchPage(0);
        write(frame, patternPage(4));
        bp.unpinPage(0, true);    // still pinned once

        bp.flushPage(0);

        assertArrayEquals(patternPage(4), dm.read(0));
        assertEquals(1, frame.getPinCount());
        assertSame(frame, bp.fetchPage(0));
    }

    @Test
    void flushPageClearsDirtyFlag() throws Exception {
        // After a flush the frame is clean, so a later unmarked change must NOT be written on eviction.
        BufferPool bp = pool(1);
        Frame frame = bp.newPage();
        write(frame, patternPage(1));
        bp.unpinPage(0, true);
        bp.flushPage(0);

        bp.fetchPage(0);
        write(frame, patternPage(2));
        bp.unpinPage(0, false);
        bp.newPage();             // evicts page 0

        assertArrayEquals(patternPage(1), dm.read(0));
    }

    @Test
    void flushNonResidentPageThrows() {
        assertThrows(IllegalArgumentException.class, () -> pool(2).flushPage(0));
    }

    @Test
    void flushAllWritesEveryDirtyPageInPartiallyFilledPool() throws Exception {
        // Pool of 3 with only 2 pages: the unused frame must not trip flushAll up.
        BufferPool bp = pool(3);
        write(bp.newPage(), patternPage(10));
        write(bp.newPage(), patternPage(20));
        bp.unpinPage(0, true);
        bp.unpinPage(1, true);

        bp.flushAll();

        assertArrayEquals(patternPage(10), dm.read(0));
        assertArrayEquals(patternPage(20), dm.read(1));
    }

    @Test
    void flushAllOnEmptyPoolDoesNothing() {
        assertDoesNotThrow(() -> pool(2).flushAll());
    }

    // ---- disk failures ----
    // Closing the DiskManager makes every later channel call throw ClosedChannelException (an IOException),
    // which is an easy way to simulate a failing disk without a fake DiskManager.

    @Test
    void fetchMissOnFailingDiskThrowsStorageExceptionWithCause() throws Exception {
        dm.allocatePage();
        BufferPool bp = pool(2);
        dm.close();

        StorageException e = assertThrows(StorageException.class, () -> bp.fetchPage(0));
        assertInstanceOf(ClosedChannelException.class, e.getCause());
    }

    @Test
    void failedNewPageReturnsFrameToFreeList() throws Exception {
        // Pool of 1: if the failed allocation leaked the only frame, the second attempt would
        // throw "pool exhausted" (ISE) instead of failing on the disk again.
        BufferPool bp = pool(1);
        dm.close();

        assertThrows(StorageException.class, bp::newPage);
        assertThrows(StorageException.class, bp::newPage);
        assertEquals(0, dm.getNumPages());
    }

    @Test
    void failedWriteBackKeepsVictimResidentAndEvictable() throws Exception {
        // Pool of 1 holding dirty page 0. Eviction must write it back, and the disk is failing.
        BufferPool bp = pool(1);
        Frame frame = bp.newPage();
        write(frame, patternPage(6));
        bp.unpinPage(0, true);
        dm.close();

        assertThrows(StorageException.class, bp::newPage);

        // Still evictable: a second eviction attempt fails on the disk again, not with "pool exhausted".
        assertThrows(StorageException.class, bp::newPage);
        // Still resident with its unsaved bytes: the hit path needs no disk.
        assertSame(frame, bp.fetchPage(0));
        assertArrayEquals(patternPage(6), frame.getData());
        assertTrue(frame.isDirty());
    }

    @Test
    void failedFlushKeepsPageDirty() throws Exception {
        BufferPool bp = pool(2);
        Frame frame = bp.newPage();
        bp.unpinPage(0, true);
        dm.close();

        assertThrows(StorageException.class, () -> bp.flushPage(0));
        assertTrue(frame.isDirty());
    }
}
