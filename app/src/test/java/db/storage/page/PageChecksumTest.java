package db.storage.page;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static db.storage.DiskManager.PAGE_SIZE;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PageChecksumTest {

    private static final int CHECKSUM_OFFSET = 8;

    private static byte[] item(int length, int seed) {
        byte[] item = new byte[length];
        for (int i = 0; i < length; i++) {
            item[i] = (byte) (seed + i);
        }
        return item;
    }

    // A realistic formatted page with a non-zero LSN and a couple of tuples.
    private static byte[] populatedPage() {
        byte[] data = new byte[PAGE_SIZE];
        HeapPage page = new HeapPage(data);
        page.init();
        page.setLsn(0x0102030405060708L);
        page.insert(item(100, 1));
        page.insert(item(50, 77));
        return data;
    }

    private static int storedChecksum(byte[] data) {
        return ByteBuffer.wrap(data).getInt(CHECKSUM_OFFSET);
    }

    // ---- computeChecksum ----

    @Test
    void computeIsDeterministic() {
        byte[] data = populatedPage();

        assertEquals(Page.computeChecksum(data, 3), Page.computeChecksum(data, 3));
        assertEquals(Page.computeChecksum(data, 3), Page.computeChecksum(data.clone(), 3));
    }

    @Test
    void computeDoesNotModifyData() {
        byte[] data = populatedPage();
        byte[] before = data.clone();

        Page.computeChecksum(data, 3);

        assertArrayEquals(before, data);
    }

    @Test
    void computeIgnoresChecksumField() {
        byte[] data = populatedPage();
        int expected = Page.computeChecksum(data, 3);

        for (int i = CHECKSUM_OFFSET; i < CHECKSUM_OFFSET + 4; i++) {
            byte[] copy = data.clone();
            copy[i] ^= (byte) 0xFF;
            assertEquals(expected, Page.computeChecksum(copy, 3), "byte " + i + " must not affect the checksum");
        }
    }

    @Test
    void computeCoversEveryByteOutsideChecksumField() {
        byte[] data = populatedPage();
        int original = Page.computeChecksum(data, 3);

        for (int i = 0; i < PAGE_SIZE; i++) {
            if (i >= CHECKSUM_OFFSET && i < CHECKSUM_OFFSET + 4) {
                continue;
            }
            data[i] ^= 1;
            assertNotEquals(original, Page.computeChecksum(data, 3), "flipping a bit in byte " + i + " must change the checksum");
            data[i] ^= 1;
        }
    }

    @Test
    void computeDependsOnPageNo() {
        byte[] data = populatedPage();

        assertNotEquals(Page.computeChecksum(data, 0), Page.computeChecksum(data, 1));
        // 7 and 263 share the low byte (0x07): catches feeding pageNo through update(int)
        assertNotEquals(Page.computeChecksum(data, 7), Page.computeChecksum(data, 263));
        // differ only in the high byte
        assertNotEquals(Page.computeChecksum(data, 1), Page.computeChecksum(data, 1 | (1 << 24)));
    }

    @Test
    void computeRejectsBadArguments() {
        assertThrows(NullPointerException.class, () -> Page.computeChecksum(null, 0));
        assertThrows(IllegalArgumentException.class, () -> Page.computeChecksum(new byte[PAGE_SIZE - 1], 0));
        assertThrows(IllegalArgumentException.class, () -> Page.computeChecksum(new byte[PAGE_SIZE + 1], 0));
        assertThrows(IllegalArgumentException.class, () -> Page.computeChecksum(new byte[PAGE_SIZE], -1));
    }

    // ---- stampChecksum ----

    @Test
    void stampWritesComputedValueBigEndianAtOffset8() {
        byte[] data = populatedPage();
        int expected = Page.computeChecksum(data, 5);

        Page.stampChecksum(data, 5);

        assertEquals(expected, storedChecksum(data));
        assertEquals((byte) (expected >>> 24), data[8]);
        assertEquals((byte) expected, data[11]);
    }

    @Test
    void stampChangesOnlyChecksumField() {
        byte[] data = populatedPage();
        byte[] before = data.clone();

        Page.stampChecksum(data, 5);

        for (int i = 0; i < PAGE_SIZE; i++) {
            if (i < CHECKSUM_OFFSET || i >= CHECKSUM_OFFSET + 4) {
                assertEquals(before[i], data[i], "byte " + i + " changed");
            }
        }
    }

    @Test
    void stampIsIdempotent() {
        byte[] data = populatedPage();
        Page.stampChecksum(data, 5);
        byte[] once = data.clone();

        Page.stampChecksum(data, 5);

        assertArrayEquals(once, data);
    }

    @Test
    void stampIsVisibleThroughPageGetter() {
        byte[] data = populatedPage();
        Page.stampChecksum(data, 5);

        assertEquals(storedChecksum(data), new HeapPage(data).getChecksum());
    }

    @Test
    void stampRejectsBadArgumentsWithoutWriting() {
        byte[] data = populatedPage();
        byte[] before = data.clone();

        assertThrows(NullPointerException.class, () -> Page.stampChecksum(null, 0));
        assertThrows(IllegalArgumentException.class, () -> Page.stampChecksum(new byte[PAGE_SIZE - 1], 0));
        assertThrows(IllegalArgumentException.class, () -> Page.stampChecksum(data, -1));
        assertArrayEquals(before, data);
    }

    // ---- verifyChecksum ----

    @Test
    void stampThenVerifySucceeds() {
        byte[] data = populatedPage();
        Page.stampChecksum(data, 42);

        assertTrue(Page.verifyChecksum(data, 42));
    }

    @Test
    void verifyDoesNotModifyData() {
        byte[] data = populatedPage();
        Page.stampChecksum(data, 42);
        byte[] valid = data.clone();
        Page.verifyChecksum(data, 42);
        assertArrayEquals(valid, data);

        data[100] ^= 1;
        byte[] corrupted = data.clone();
        Page.verifyChecksum(data, 42);
        assertArrayEquals(corrupted, data);
    }

    @Test
    void verifyDetectsSingleBitFlipAnywhere() {
        byte[] data = populatedPage();
        Page.stampChecksum(data, 42);

        for (int i = 0; i < PAGE_SIZE; i++) {
            for (int bit = 0; bit < 8; bit += 7) {   // lowest and highest bit of each byte
                data[i] ^= (byte) (1 << bit);
                assertFalse(Page.verifyChecksum(data, 42), "flip of bit " + bit + " in byte " + i + " not detected");
                data[i] ^= (byte) (1 << bit);
            }
        }
    }

    @Test
    void verifyDetectsTornWrite() {
        byte[] oldVersion = populatedPage();
        Page.stampChecksum(oldVersion, 42);
        byte[] newVersion = oldVersion.clone();
        HeapPage page = new HeapPage(newVersion);
        page.insert(item(200, 9));
        Page.stampChecksum(newVersion, 42);

        // first half new, second half old
        byte[] torn = oldVersion.clone();
        System.arraycopy(newVersion, 0, torn, 0, PAGE_SIZE / 2);

        assertFalse(Page.verifyChecksum(torn, 42));
    }

    @Test
    void verifyDetectsMisdirectedWrite() {
        byte[] data = populatedPage();
        Page.stampChecksum(data, 7);

        assertTrue(Page.verifyChecksum(data, 7));
        assertFalse(Page.verifyChecksum(data, 263));
        assertFalse(Page.verifyChecksum(data, 8));
    }

    @Test
    void verifyFailsOnUnstampedPopulatedPage() {
        byte[] data = populatedPage();
        // stored field is still 0; only a CRC that is exactly 0 could match it
        assumeTrue(Page.computeChecksum(data, 42) != 0);

        assertFalse(Page.verifyChecksum(data, 42));
    }

    @Test
    void verifyAcceptsAllZeroPage() {
        byte[] data = new byte[PAGE_SIZE];

        assertTrue(Page.verifyChecksum(data, 0));
        assertTrue(Page.verifyChecksum(data, 263));
        assertArrayEquals(new byte[PAGE_SIZE], data);
    }

    @Test
    void verifyRejectsNearlyZeroPage() {
        for (int i : new int[]{0, 7, CHECKSUM_OFFSET, 11, 12, PAGE_SIZE / 2, PAGE_SIZE - 1}) {
            byte[] data = new byte[PAGE_SIZE];
            data[i] = 1;
            assertFalse(Page.verifyChecksum(data, 0), "single non-zero byte at " + i + " must not pass as an all-zero page");
        }
    }

    @Test
    void verifyAcceptsStampedFreshlyInitializedPage() {
        byte[] data = new byte[PAGE_SIZE];
        new HeapPage(data).init();
        Page.stampChecksum(data, 1);

        assertTrue(Page.verifyChecksum(data, 1));
    }

    @Test
    void verifyRejectsBadArguments() {
        assertThrows(NullPointerException.class, () -> Page.verifyChecksum(null, 0));
        assertThrows(IllegalArgumentException.class, () -> Page.verifyChecksum(new byte[PAGE_SIZE - 1], 0));
        assertThrows(IllegalArgumentException.class, () -> Page.verifyChecksum(new byte[PAGE_SIZE], -1));
    }
}
