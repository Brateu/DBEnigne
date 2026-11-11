package db.storage;

import db.storage.page.CorruptPageException;
import db.storage.page.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.ClosedChannelException;
import java.nio.file.Path;

import static db.storage.DiskManager.PAGE_SIZE;
import static org.junit.jupiter.api.Assertions.*;

class BufferPoolTest {

    /** Most tests use a single table file; the multi-file tests open a second one. */
    private static final int FILE = 0;
    private static final int OTHER_FILE = 1;

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

    /** A page as the pool would have written it: the pattern with a valid checksum for pageNo in bytes 8..11. */
    private static byte[] stampedPage(int seed, int pageNo) {
        byte[] page = patternPage(seed);
        Page.stampChecksum(page, pageNo);
        return page;
    }

    /**
     * The pool stamps the checksum into bytes 8..11 on write-back, so a page that went through the
     * pool equals what the caller wrote everywhere except those 4 bytes.
     */
    private static void assertSameIgnoringChecksum(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            if (i < 8 || i >= 12) {
                assertEquals(expected[i], actual[i], "byte " + i + " differs");
            }
        }
    }

    /** Disk bytes written by the pool: caller's content plus a checksum that verifies for this pageNo. */
    private static void assertWrittenByPool(byte[] expected, byte[] onDisk, int pageNo) {
        assertSameIgnoringChecksum(expected, onDisk);
        assertTrue(Page.verifyChecksum(onDisk, pageNo), "page " + pageNo + " on disk has no valid checksum");
    }

    /** Simulates a caller modifying a page it has pinned. */
    private static void write(Frame frame, byte[] bytes) {
        System.arraycopy(bytes, 0, frame.getData(), 0, PAGE_SIZE);
    }

    // ---- constructor ----

    @Test
    void constructorRejectsNullStorage() {
        assertThrows(NullPointerException.class, () -> new BufferPool(null, 2));
    }

    @Test
    void constructorRejectsNonPositiveSize() {
        assertThrows(IllegalArgumentException.class, () -> pool(0));
        assertThrows(IllegalArgumentException.class, () -> pool(-1));
    }

    // ---- newPage ----

    @Test
    void newPageReturnsPinnedFramesWithSequentialIds() {
        BufferPool bp = pool(3);
        Frame f0 = bp.newPage(FILE);
        Frame f1 = bp.newPage(FILE);

        assertEquals(pid(0), f0.getPageId());
        assertEquals(pid(1), f1.getPageId());
        assertEquals(1, f0.getPinCount());
        assertEquals(2, dm.getNumPages());
    }

    @Test
    void newPageZeroesReusedFrame() {
        // Pool of 1: the second newPage must reuse the frame that held page 0's bytes.
        BufferPool bp = pool(1);
        Frame f0 = bp.newPage(FILE);
        write(f0, patternPage(7));
        bp.unpinPage(pid(0), true);

        Frame f1 = bp.newPage(FILE);

        assertSame(f0, f1);
        assertArrayEquals(new byte[PAGE_SIZE], f1.getData());
    }

    @Test
    void newPageOnExhaustedPoolThrowsAndDoesNotAllocateOnDisk() {
        // Guards the "frame before allocatePage" ordering: a full pool must not waste a disk page.
        BufferPool bp = pool(2);
        bp.newPage(FILE);
        bp.newPage(FILE);

        assertThrows(IllegalStateException.class, () -> bp.newPage(FILE));
        assertEquals(2, dm.getNumPages());
    }

    @Test
    void newPageOnUnknownFileThrowsAndDoesNotLeakFrame() {
        // Guards the "look up the file before acquireFrame" ordering. Pool of 1: a leaked frame
        // would make the second newPage fail with "pool exhausted".
        BufferPool bp = pool(1);

        assertThrows(IllegalArgumentException.class, () -> bp.newPage(99));

        assertDoesNotThrow(() -> bp.newPage(FILE));
    }

    // ---- fetchPage ----

    @Test
    void fetchPageMissReadsBytesFromDisk() throws Exception {
        int pageNo = dm.allocatePage();
        dm.write(pageNo, stampedPage(3, pageNo));
        BufferPool bp = pool(2);

        Frame frame = bp.fetchPage(pid(pageNo));

        assertEquals(pid(pageNo), frame.getPageId());
        assertArrayEquals(stampedPage(3, pageNo), frame.getData());
        assertEquals(1, frame.getPinCount());
    }

    @Test
    void fetchPageHitReturnsSameFrameAndPinsAgain() {
        BufferPool bp = pool(2);
        Frame first = bp.newPage(FILE);

        Frame second = bp.fetchPage(pid(0));

        assertSame(first, second);
        assertEquals(2, second.getPinCount());
    }

    @Test
    void fetchPageHitWorksWithEqualButDifferentPageIdObject() {
        // The map key is a record: a freshly built PageId must find the entry stored by newPage.
        BufferPool bp = pool(2);
        Frame frame = bp.newPage(FILE);

        assertSame(frame, bp.fetchPage(new PageId(FILE, 0)));
    }

    @Test
    void fetchPageRejectsNull() {
        assertThrows(NullPointerException.class, () -> pool(2).fetchPage(null));
    }

    @Test
    void fetchPageFromUnknownFileThrows() {
        assertThrows(IllegalArgumentException.class, () -> pool(2).fetchPage(new PageId(99, 0)));
    }

    @Test
    void failedFetchDoesNotLeakFrame() {
        // Pool of 1: if a failed read had already taken the only frame, the newPage below would throw.
        BufferPool bp = pool(1);

        assertThrows(IllegalArgumentException.class, () -> bp.fetchPage(pid(99)));   // page doesn't exist on disk

        assertDoesNotThrow(() -> bp.newPage(FILE));
    }

    @Test
    void fetchHitOnEvictableFrameMakesItNonEvictableAgain() {
        // Regression for the hit path: refetching an unpinned page must remove it from eviction candidates.
        BufferPool bp = pool(2);
        bp.newPage(FILE);
        bp.unpinPage(pid(0), false);   // page 0 is now evictable

        bp.fetchPage(pid(0));          // pinned again: must no longer be evictable
        bp.newPage(FILE);              // takes the last free frame

        assertThrows(IllegalStateException.class, () -> bp.newPage(FILE));
    }

    // ---- multiple files ----

    @Test
    void samePageNoInTwoFilesDoesNotCollide() throws Exception {
        DiskManager other = storage.open(OTHER_FILE);
        BufferPool bp = pool(2);
        Frame a = bp.newPage(FILE);
        Frame b = bp.newPage(OTHER_FILE);

        assertEquals(new PageId(FILE, 0), a.getPageId());
        assertEquals(new PageId(OTHER_FILE, 0), b.getPageId());
        assertNotSame(a, b);

        write(a, patternPage(1));
        write(b, patternPage(2));
        bp.unpinPage(a.getPageId(), true);
        bp.unpinPage(b.getPageId(), true);
        bp.flushAll();

        assertWrittenByPool(patternPage(1), dm.read(0), 0);
        assertWrittenByPool(patternPage(2), other.read(0), 0);
    }

    @Test
    void evictionWritesBackToTheVictimsOwnFile() throws Exception {
        // Pool of 1: the dirty page of OTHER_FILE is evicted by a newPage in FILE.
        // Its bytes must land in OTHER_FILE, not in the file that caused the eviction.
        DiskManager other = storage.open(OTHER_FILE);
        BufferPool bp = pool(1);
        write(bp.newPage(OTHER_FILE), patternPage(4));
        bp.unpinPage(new PageId(OTHER_FILE, 0), true);

        bp.newPage(FILE);

        assertWrittenByPool(patternPage(4), other.read(0), 0);
        assertArrayEquals(new byte[PAGE_SIZE], dm.read(0));
    }

    // ---- eviction ----

    @Test
    void pinnedPageIsNeverEvicted() {
        BufferPool bp = pool(2);
        Frame f0 = bp.newPage(FILE);   // stays pinned
        Frame f1 = bp.newPage(FILE);
        bp.unpinPage(pid(1), false);

        Frame f2 = bp.newPage(FILE);   // must evict page 1, the only candidate

        assertSame(f1, f2);
        assertEquals(pid(0), f0.getPageId());
    }

    @Test
    void evictsLeastRecentlyUnpinnedPage() {
        BufferPool bp = pool(2);
        Frame f0 = bp.newPage(FILE);
        Frame f1 = bp.newPage(FILE);
        bp.unpinPage(pid(0), false);
        bp.unpinPage(pid(1), false);   // order so far: 0 oldest, 1 newest

        bp.fetchPage(pid(0));
        bp.unpinPage(pid(0), false);   // 0 used again: now 1 is oldest

        Frame f2 = bp.newPage(FILE);

        assertSame(f1, f2);
        assertEquals(pid(0), f0.getPageId());
    }

    @Test
    void exhaustedPoolThrowsOnFetch() throws Exception {
        dm.allocatePage();
        dm.allocatePage();
        dm.allocatePage();
        BufferPool bp = pool(2);
        bp.fetchPage(pid(0));
        bp.fetchPage(pid(1));

        assertThrows(IllegalStateException.class, () -> bp.fetchPage(pid(2)));
    }

    @Test
    void dirtyPageSurvivesEviction() throws Exception {
        BufferPool bp = pool(1);
        write(bp.newPage(FILE), patternPage(5));
        bp.unpinPage(pid(0), true);

        bp.newPage(FILE);              // evicts page 0: must write it back first
        bp.unpinPage(pid(1), false);

        assertWrittenByPool(patternPage(5), dm.read(0), 0);
        assertSameIgnoringChecksum(patternPage(5), bp.fetchPage(pid(0)).getData());   // verified on the way back in
    }

    @Test
    void cleanPageIsNotWrittenBackOnEviction() throws Exception {
        // unpin(false) promises "I didn't modify it", so eviction skips the write and the disk stays zeros.
        BufferPool bp = pool(1);
        write(bp.newPage(FILE), patternPage(5));
        bp.unpinPage(pid(0), false);

        bp.newPage(FILE);

        assertArrayEquals(new byte[PAGE_SIZE], dm.read(0));
    }

    // ---- unpinPage ----

    @Test
    void dirtyFlagIsNotClearedByLaterCleanUnpin() throws Exception {
        // Two users: A modifies and unpins dirty, B only read and unpins clean. A's change must still reach disk.
        BufferPool bp = pool(1);
        Frame frame = bp.newPage(FILE);
        bp.fetchPage(pid(0));
        write(frame, patternPage(9));
        bp.unpinPage(pid(0), true);
        bp.unpinPage(pid(0), false);

        bp.newPage(FILE);              // evicts page 0

        assertWrittenByPool(patternPage(9), dm.read(0), 0);
    }

    @Test
    void pageStaysNonEvictableUntilPinCountReachesZero() {
        BufferPool bp = pool(1);
        bp.newPage(FILE);
        bp.fetchPage(pid(0));          // pin count 2
        bp.unpinPage(pid(0), false);   // pin count 1: still in use

        assertThrows(IllegalStateException.class, () -> bp.newPage(FILE));

        bp.unpinPage(pid(0), false);   // pin count 0: now evictable
        assertDoesNotThrow(() -> bp.newPage(FILE));
    }

    @Test
    void unpinNonResidentPageThrows() {
        BufferPool bp = pool(2);
        assertThrows(IllegalArgumentException.class, () -> bp.unpinPage(pid(0), false));
    }

    @Test
    void unpinMoreTimesThanPinnedThrows() {
        BufferPool bp = pool(2);
        bp.newPage(FILE);
        bp.unpinPage(pid(0), false);

        assertThrows(IllegalStateException.class, () -> bp.unpinPage(pid(0), false));
    }

    // ---- flushPage / flushAll ----

    @Test
    void flushPageWritesDirtyPageWithoutEvicting() throws Exception {
        BufferPool bp = pool(2);
        Frame frame = bp.newPage(FILE);
        bp.fetchPage(pid(0));
        write(frame, patternPage(4));
        bp.unpinPage(pid(0), true);    // still pinned once

        bp.flushPage(pid(0));

        assertWrittenByPool(patternPage(4), dm.read(0), 0);
        assertEquals(1, frame.getPinCount());
        assertSame(frame, bp.fetchPage(pid(0)));
    }

    @Test
    void flushPageClearsDirtyFlag() throws Exception {
        // After a flush the frame is clean, so a later unmarked change must NOT be written on eviction.
        BufferPool bp = pool(1);
        Frame frame = bp.newPage(FILE);
        write(frame, patternPage(1));
        bp.unpinPage(pid(0), true);
        bp.flushPage(pid(0));

        bp.fetchPage(pid(0));
        write(frame, patternPage(2));
        bp.unpinPage(pid(0), false);
        bp.newPage(FILE);              // evicts page 0

        assertWrittenByPool(patternPage(1), dm.read(0), 0);
    }

    @Test
    void flushNonResidentPageThrows() {
        assertThrows(IllegalArgumentException.class, () -> pool(2).flushPage(pid(0)));
    }

    @Test
    void flushAllWritesEveryDirtyPageInPartiallyFilledPool() throws Exception {
        // Pool of 3 with only 2 pages: the unused frame must not trip flushAll up.
        BufferPool bp = pool(3);
        write(bp.newPage(FILE), patternPage(10));
        write(bp.newPage(FILE), patternPage(20));
        bp.unpinPage(pid(0), true);
        bp.unpinPage(pid(1), true);

        bp.flushAll();

        assertWrittenByPool(patternPage(10), dm.read(0), 0);
        assertWrittenByPool(patternPage(20), dm.read(1), 1);
    }

    @Test
    void flushAllOnEmptyPoolDoesNothing() {
        assertDoesNotThrow(() -> pool(2).flushAll());
    }

    // ---- checksums ----

    @Test
    void writeBackStampsChecksumForThePagesOwnPageNo() throws Exception {
        BufferPool bp = pool(3);
        bp.newPage(FILE);
        write(bp.newPage(FILE), patternPage(8));
        bp.unpinPage(pid(1), true);

        bp.flushPage(pid(1));

        byte[] onDisk = dm.read(1);
        assertTrue(Page.verifyChecksum(onDisk, 1));
        assertFalse(Page.verifyChecksum(onDisk, 0));
    }

    @Test
    void fetchOfNeverWrittenNewPageAcceptsAllZeros() {
        // allocatePage puts zeros on disk without a checksum; the pool must still accept that page.
        BufferPool bp = pool(1);
        bp.newPage(FILE);
        bp.unpinPage(pid(0), false);
        bp.newPage(FILE);              // evicts page 0 without writing it
        bp.unpinPage(pid(1), false);

        Frame frame = bp.fetchPage(pid(0));

        assertArrayEquals(new byte[PAGE_SIZE], frame.getData());
    }

    @Test
    void fetchOfCorruptedPageThrowsCorruptPageException() throws Exception {
        BufferPool writer = pool(1);
        write(writer.newPage(FILE), patternPage(2));
        writer.unpinPage(pid(0), true);
        writer.flushPage(pid(0));

        byte[] onDisk = dm.read(0);
        onDisk[1000] ^= 1;             // bit rot inside the tuple area
        dm.write(0, onDisk);

        CorruptPageException e = assertThrows(CorruptPageException.class, () -> pool(1).fetchPage(pid(0)));
        assertInstanceOf(StorageException.class, e);
        assertTrue(e.getMessage().contains(pid(0).toString()), "message should name the page, got: " + e.getMessage());
    }

    @Test
    void corruptedPageDoesNotTakeAFrame() throws Exception {
        // Pool of 1: verification runs before acquireFrame, so a failed fetch leaves the only frame free.
        int pageNo = dm.allocatePage();
        dm.write(pageNo, patternPage(5));   // non-zero and never stamped
        BufferPool bp = pool(1);

        assertThrows(CorruptPageException.class, () -> bp.fetchPage(pid(pageNo)));
        assertThrows(CorruptPageException.class, () -> bp.fetchPage(pid(pageNo)));   // not cached either

        assertDoesNotThrow(() -> bp.newPage(FILE));
    }

    @Test
    void corruptedPageDoesNotEvictAResidentPage() throws Exception {
        // Pool of 1 with an evictable dirty page: a corrupt fetch must fail before choosing a victim,
        // so the resident page stays cached and unwritten.
        dm.allocatePage();
        dm.write(0, patternPage(5));   // corrupt page 0 on disk
        BufferPool bp = pool(1);
        Frame resident = bp.newPage(FILE);   // page 1
        write(resident, patternPage(6));
        bp.unpinPage(pid(1), true);

        assertThrows(CorruptPageException.class, () -> bp.fetchPage(pid(0)));

        assertSame(resident, bp.fetchPage(pid(1)));
        assertTrue(resident.isDirty());
        assertArrayEquals(new byte[PAGE_SIZE], dm.read(1));
    }

    @Test
    void pageCopiedToWrongPositionIsDetected() throws Exception {
        // Misdirected write: page 0's (valid) bytes end up at position 1.
        BufferPool writer = pool(2);
        write(writer.newPage(FILE), patternPage(3));
        writer.newPage(FILE);
        writer.unpinPage(pid(0), true);
        writer.flushAll();
        dm.write(1, dm.read(0));

        BufferPool bp = pool(2);
        assertDoesNotThrow(() -> bp.fetchPage(pid(0)));
        assertThrows(CorruptPageException.class, () -> bp.fetchPage(pid(1)));
    }

    @Test
    void pageCopiedToSamePageNoInAnotherFileIsNotDetected() throws Exception {
        // Documents a design decision: the checksum mixes in pageNo but not fileId,
        // so a page moved to the same position in another file still verifies.
        DiskManager other = storage.open(OTHER_FILE);
        BufferPool writer = pool(1);
        write(writer.newPage(FILE), patternPage(3));
        writer.unpinPage(pid(0), true);
        writer.flushPage(pid(0));
        other.allocatePage();
        other.write(0, dm.read(0));

        assertDoesNotThrow(() -> pool(1).fetchPage(new PageId(OTHER_FILE, 0)));
    }

    @Test
    void stampedPageRoundTripsThroughEvictionAndFetch() {
        // Write -> evict (stamp) -> fetch (verify) -> modify -> evict (restamp) -> fetch (verify).
        BufferPool bp = pool(1);
        write(bp.newPage(FILE), patternPage(1));
        bp.unpinPage(pid(0), true);
        bp.newPage(FILE);
        bp.unpinPage(pid(1), false);

        Frame frame = bp.fetchPage(pid(0));
        assertSameIgnoringChecksum(patternPage(1), frame.getData());
        write(frame, patternPage(2));
        bp.unpinPage(pid(0), true);
        bp.fetchPage(pid(1));
        bp.unpinPage(pid(1), false);

        assertSameIgnoringChecksum(patternPage(2), bp.fetchPage(pid(0)).getData());
    }

    // ---- disk failures ----
    // Closing the DiskManager makes every later channel call throw ClosedChannelException (an IOException),
    // which is an easy way to simulate a failing disk without a fake DiskManager. The StorageManager still
    // hands out the closed instance, so the pool reaches the failing disk through its normal path.

    @Test
    void fetchMissOnFailingDiskThrowsStorageExceptionWithCause() throws Exception {
        dm.allocatePage();
        BufferPool bp = pool(2);
        dm.close();

        StorageException e = assertThrows(StorageException.class, () -> bp.fetchPage(pid(0)));
        assertInstanceOf(ClosedChannelException.class, e.getCause());
    }

    @Test
    void failedNewPageReturnsFrameToFreeList() throws Exception {
        // Pool of 1: if the failed allocation leaked the only frame, the second attempt would
        // throw "pool exhausted" (ISE) instead of failing on the disk again.
        BufferPool bp = pool(1);
        dm.close();

        assertThrows(StorageException.class, () -> bp.newPage(FILE));
        assertThrows(StorageException.class, () -> bp.newPage(FILE));
        assertEquals(0, dm.getNumPages());
    }

    @Test
    void failedWriteBackKeepsVictimResidentAndEvictable() throws Exception {
        // Pool of 1 holding dirty page 0. Eviction must write it back, and the disk is failing.
        BufferPool bp = pool(1);
        Frame frame = bp.newPage(FILE);
        write(frame, patternPage(6));
        bp.unpinPage(pid(0), true);
        dm.close();

        assertThrows(StorageException.class, () -> bp.newPage(FILE));

        // Still evictable: a second eviction attempt fails on the disk again, not with "pool exhausted".
        assertThrows(StorageException.class, () -> bp.newPage(FILE));
        // Still resident with its unsaved bytes: the hit path needs no disk.
        assertSame(frame, bp.fetchPage(pid(0)));
        assertSameIgnoringChecksum(patternPage(6), frame.getData());   // stamp happens in place before the write
        assertTrue(frame.isDirty());
    }

    @Test
    void failedFlushKeepsPageDirty() throws Exception {
        BufferPool bp = pool(2);
        Frame frame = bp.newPage(FILE);
        bp.unpinPage(pid(0), true);
        dm.close();

        assertThrows(StorageException.class, () -> bp.flushPage(pid(0)));
        assertTrue(frame.isDirty());
    }
}
