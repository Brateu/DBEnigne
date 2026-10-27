package db.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static db.storage.DiskManager.PAGE_SIZE;
import static org.junit.jupiter.api.Assertions.*;

class DiskManagerTest {

    @TempDir
    Path tempDir;   // JUnit makes a fresh empty folder for each test

    private Path dbFile() {
        return tempDir.resolve("test.db");
    }

    /** A page where every byte differs from its neighbours, so shifted or partial writes show up. */
    private static byte[] patternPage(int seed) {
        byte[] page = new byte[PAGE_SIZE];
        for (int i = 0; i < PAGE_SIZE; i++) {
            page[i] = (byte) (i + seed);
        }
        return page;
    }

    private static byte[] filledPage(int value) {
        byte[] page = new byte[PAGE_SIZE];
        Arrays.fill(page, (byte) value);
        return page;
    }

    // ---- allocation and page count ----

    @Test
    void newFileHasZeroPages() throws Exception {
        try (DiskManager dm = new DiskManager(dbFile())) {
            assertEquals(0, dm.getNumPages());
        }
    }

    @Test
    void allocatePageReturnsSequentialIds() throws Exception {
        try (DiskManager dm = new DiskManager(dbFile())) {
            assertEquals(0, dm.allocatePage());
            assertEquals(1, dm.allocatePage());
            assertEquals(2, dm.allocatePage());
            assertEquals(3, dm.getNumPages());
        }
        assertEquals(3L * PAGE_SIZE, Files.size(dbFile()));
    }

    @Test
    void allocatedPageIsZeroed() throws Exception {
        try (DiskManager dm = new DiskManager(dbFile())) {
            int pageId = dm.allocatePage();
            assertArrayEquals(new byte[PAGE_SIZE], dm.read(pageId));
        }
    }

    // ---- read / write round trips ----

    @Test
    void writeThenReadReturnsSameBytes() throws Exception {
        try (DiskManager dm = new DiskManager(dbFile())) {
            dm.allocatePage();
            byte[] data = patternPage(0);

            dm.write(0, data);

            assertArrayEquals(data, dm.read(0));
        }
    }

    @Test
    void pagesDoNotOverlap() throws Exception {
        try (DiskManager dm = new DiskManager(dbFile())) {
            dm.allocatePage();
            dm.allocatePage();
            dm.allocatePage();
            byte[] ones = filledPage(1);
            byte[] twos = filledPage(2);

            dm.write(1, twos);
            dm.write(0, ones);

            assertArrayEquals(ones, dm.read(0));
            assertArrayEquals(twos, dm.read(1));
            assertArrayEquals(new byte[PAGE_SIZE], dm.read(2));
        }
    }

    @Test
    void writeOverwritesExistingPage() throws Exception {
        try (DiskManager dm = new DiskManager(dbFile())) {
            dm.allocatePage();
            dm.write(0, filledPage(7));
            byte[] second = patternPage(3);

            dm.write(0, second);

            assertArrayEquals(second, dm.read(0));
            assertEquals(1, dm.getNumPages());
        }
    }

    @Test
    void readReturnsIndependentCopy() throws Exception {
        try (DiskManager dm = new DiskManager(dbFile())) {
            dm.allocatePage();
            byte[] data = patternPage(0);
            dm.write(0, data);

            byte[] copy = dm.read(0);
            copy[0] = 99;
            data[1] = 99;

            assertArrayEquals(patternPage(0), dm.read(0));
        }
    }

    // ---- persistence ----

    @Test
    void dataSurvivesReopen() throws Exception {
        byte[] page0 = patternPage(0);
        byte[] page1 = filledPage(42);
        try (DiskManager dm = new DiskManager(dbFile())) {
            dm.allocatePage();
            dm.allocatePage();
            dm.write(0, page0);
            dm.write(1, page1);
            dm.sync();
        }

        try (DiskManager dm = new DiskManager(dbFile())) {
            assertEquals(2, dm.getNumPages());
            assertArrayEquals(page0, dm.read(0));
            assertArrayEquals(page1, dm.read(1));
            assertEquals(2, dm.allocatePage());
        }
    }

    @Test
    void partialTrailingPageIsIgnoredAndReclaimed() throws Exception {
        // Simulates a crash halfway through allocatePage: one full page plus 100 stray bytes.
        byte[] onDisk = new byte[PAGE_SIZE + 100];
        Arrays.fill(onDisk, 0, PAGE_SIZE, (byte) 5);
        Files.write(dbFile(), onDisk);

        try (DiskManager dm = new DiskManager(dbFile())) {
            assertEquals(1, dm.getNumPages());
            assertThrows(IllegalArgumentException.class, () -> dm.read(1));

            assertEquals(1, dm.allocatePage());
            assertArrayEquals(new byte[PAGE_SIZE], dm.read(1));
        }
        assertEquals(2L * PAGE_SIZE, Files.size(dbFile()));
    }

    // ---- invalid input ----

    @Test
    void writeRejectsWrongSizeData() throws Exception {
        try (DiskManager dm = new DiskManager(dbFile())) {
            dm.allocatePage();

            assertThrows(NullPointerException.class, () -> dm.write(0, null));
            assertThrows(IllegalArgumentException.class, () -> dm.write(0, new byte[100]));
            assertThrows(IllegalArgumentException.class, () -> dm.write(0, new byte[PAGE_SIZE + 1]));
            assertArrayEquals(new byte[PAGE_SIZE], dm.read(0));
        }
    }

    @Test
    void writeRejectsPageThatDoesNotExist() throws Exception {
        try (DiskManager dm = new DiskManager(dbFile())) {
            assertThrows(IllegalArgumentException.class, () -> dm.write(0, patternPage(0)));
            assertEquals(0, dm.getNumPages());

            dm.allocatePage();
            assertThrows(IllegalArgumentException.class, () -> dm.write(-1, patternPage(0)));
            assertThrows(IllegalArgumentException.class, () -> dm.write(1, patternPage(0)));
            assertEquals(1, dm.getNumPages());
        }
    }

    @Test
    @Timeout(2)
    void readRejectsOutOfRangePage() throws Exception {
        try (DiskManager dm = new DiskManager(dbFile())) {
            assertThrows(IllegalArgumentException.class, () -> dm.read(0));

            dm.allocatePage();
            assertThrows(IllegalArgumentException.class, () -> dm.read(-1));
            assertThrows(IllegalArgumentException.class, () -> dm.read(1));
            assertThrows(IllegalArgumentException.class, () -> dm.read(5));
        }
    }

    @Test
    void syncDoesNotThrow() throws Exception {
        try (DiskManager dm = new DiskManager(dbFile())) {
            dm.sync();
            dm.allocatePage();
            dm.write(0, patternPage(0));
            dm.sync();
        }
    }
}
