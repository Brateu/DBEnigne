package db.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static db.storage.DiskManager.PAGE_SIZE;
import static org.junit.jupiter.api.Assertions.*;

class StorageManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void constructorRejectsNullDir() {
        assertThrows(NullPointerException.class, () -> new StorageManager(null));
    }

    @Test
    void openCreatesFileNamedAfterFileId() throws Exception {
        try (StorageManager sm = new StorageManager(tempDir)) {
            sm.open(3);

            assertTrue(Files.exists(tempDir.resolve("3")));
        }
    }

    @Test
    void openTwiceReturnsSameInstance() throws Exception {
        // Two DiskManagers on one file would each cache numPages and hand out the same page twice.
        try (StorageManager sm = new StorageManager(tempDir)) {
            DiskManager first = sm.open(1);
            DiskManager second = sm.open(1);

            assertSame(first, second);
        }
    }

    @Test
    void getReturnsOpenedInstance() throws Exception {
        try (StorageManager sm = new StorageManager(tempDir)) {
            DiskManager opened = sm.open(1);

            assertSame(opened, sm.get(1));
        }
    }

    @Test
    void getUnknownFileThrowsAndDoesNotCreateIt() throws Exception {
        try (StorageManager sm = new StorageManager(tempDir)) {
            assertThrows(IllegalArgumentException.class, () -> sm.get(57));
            assertFalse(Files.exists(tempDir.resolve("57")));
        }
    }

    @Test
    void openRejectsNegativeFileId() throws Exception {
        try (StorageManager sm = new StorageManager(tempDir)) {
            assertThrows(IllegalArgumentException.class, () -> sm.open(-1));
        }
    }

    @Test
    void differentFileIdsAreIndependentFiles() throws Exception {
        try (StorageManager sm = new StorageManager(tempDir)) {
            DiskManager a = sm.open(1);
            DiskManager b = sm.open(2);

            assertEquals(0, a.allocatePage());
            assertEquals(1, a.allocatePage());
            assertEquals(0, b.allocatePage());   // file 2 starts at page 0 on its own

            assertEquals(2 * PAGE_SIZE, Files.size(tempDir.resolve("1")));
            assertEquals(PAGE_SIZE, Files.size(tempDir.resolve("2")));
        }
    }

    @Test
    void reopenAfterCloseSeesExistingPages() throws Exception {
        try (StorageManager sm = new StorageManager(tempDir)) {
            sm.open(1).allocatePage();
        }

        try (StorageManager sm = new StorageManager(tempDir)) {
            assertEquals(1, sm.open(1).getNumPages());
        }
    }

    @Test
    void closeClosesEveryFileAndForgetsThem() throws Exception {
        StorageManager sm = new StorageManager(tempDir);
        DiskManager a = sm.open(1);
        DiskManager b = sm.open(2);

        sm.close();

        // A closed channel makes I/O throw, which shows both DiskManagers were closed.
        assertThrows(java.nio.channels.ClosedChannelException.class, a::allocatePage);
        assertThrows(java.nio.channels.ClosedChannelException.class, b::allocatePage);
        assertThrows(IllegalArgumentException.class, () -> sm.get(1));
    }
}
