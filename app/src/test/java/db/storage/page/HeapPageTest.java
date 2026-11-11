package db.storage.page;

import org.junit.jupiter.api.Test;

import static db.storage.DiskManager.PAGE_SIZE;
import static db.storage.page.Page.HEADER_SIZE;
import static db.storage.page.Page.LAYOUT_VERSION;
import static db.storage.page.Page.LINE_POINTER_SIZE;
import static org.junit.jupiter.api.Assertions.*;

class HeapPageTest {

    private static byte[] item(int length, int seed) {
        byte[] item = new byte[length];
        for (int i = 0; i < length; i++) {
            item[i] = (byte) (seed + i);
        }
        return item;
    }

    private static HeapPage initializedHeapPage(byte[] data) {
        HeapPage page = new HeapPage(data);
        page.init();
        return page;
    }

    // ---- construction ----

    @Test
    void heapPageIsAPage() {
        assertInstanceOf(Page.class, new HeapPage(new byte[PAGE_SIZE]));
    }

    @Test
    void constructorKeepsPageChecks() {
        assertThrows(NullPointerException.class, () -> new HeapPage(null));
        assertThrows(IllegalArgumentException.class, () -> new HeapPage(new byte[PAGE_SIZE - 1]));
    }

    @Test
    void zeroedBytesWrapAsNewHeapPage() {
        HeapPage page = new HeapPage(new byte[PAGE_SIZE]);

        assertTrue(page.isNew());
    }

    // ---- init ----

    @Test
    void initFormatsWithoutSpecialSpace() {
        HeapPage page = initializedHeapPage(new byte[PAGE_SIZE]);

        assertFalse(page.isNew());
        assertEquals(HEADER_SIZE, page.getLower());
        assertEquals(PAGE_SIZE, page.getUpper());
        assertEquals(PAGE_SIZE, page.getSpecial());
        assertEquals(LAYOUT_VERSION, page.getVersion());
        assertEquals(PAGE_SIZE - HEADER_SIZE, page.freeSpace());
        assertEquals(PAGE_SIZE - HEADER_SIZE - LINE_POINTER_SIZE, page.maxItemSize());
    }

    @Test
    void initReformatsAPageWithItems() {
        HeapPage page = initializedHeapPage(new byte[PAGE_SIZE]);
        page.insert(item(100, 1));
        page.insert(item(50, 2));

        page.init();

        assertEquals(0, page.slotCount());
        assertEquals(PAGE_SIZE - HEADER_SIZE, page.freeSpace());
    }

    // ---- page type check ----

    @Test
    void pageWithSpecialSpaceIsNotAHeapPage() {
        byte[] data = new byte[PAGE_SIZE];
        new Page(data).init(16);                         // valid page, but e.g. a B-tree page

        CorruptPageException e = assertThrows(CorruptPageException.class, () -> new HeapPage(data));
        assertTrue(e.getMessage().contains(String.valueOf(PAGE_SIZE - 16)), "message names the bad special");
    }

    @Test
    void sameBytesAreValidAsGenericPage() {
        byte[] data = new byte[PAGE_SIZE];
        new Page(data).init(16);

        assertDoesNotThrow(() -> new Page(data));
    }

    @Test
    void corruptHeaderIsStillRejected() {
        byte[] data = new byte[PAGE_SIZE];
        initializedHeapPage(data);
        data[20] = 0;
        data[21] = (byte) (LAYOUT_VERSION + 1);          // bad version

        assertThrows(CorruptPageException.class, () -> new HeapPage(data));
    }

    // ---- inherited item operations ----

    @Test
    void itemOperationsWorkThroughHeapPage() {
        HeapPage page = initializedHeapPage(new byte[PAGE_SIZE]);
        byte[] a = item(100, 1);
        byte[] b = item(50, 2);
        byte[] bigger = item(300, 3);

        int slotA = page.insert(a);
        int slotB = page.insert(b);
        page.delete(slotA);
        page.update(slotB, bigger);

        assertEquals(LinePointerState.DEAD, page.stateOf(slotA));
        assertArrayEquals(bigger, page.get(slotB));
        assertTrue(page.canFit(page.freeSpace() - LINE_POINTER_SIZE));
    }

    @Test
    void rewrappingKeepsItems() {
        byte[] data = new byte[PAGE_SIZE];
        HeapPage writer = initializedHeapPage(data);
        byte[] a = item(100, 1);
        byte[] b = item(50, 2);
        writer.insert(a);
        writer.insert(b);
        writer.setLsn(42);

        HeapPage reader = new HeapPage(data);

        assertEquals(2, reader.slotCount());
        assertArrayEquals(a, reader.get(0));
        assertArrayEquals(b, reader.get(1));
        assertEquals(42, reader.getLsn());
    }

    @Test
    void fullHeapPageIsStillAValidHeapPage() {
        byte[] data = new byte[PAGE_SIZE];
        HeapPage page = initializedHeapPage(data);
        page.insert(item(page.maxItemSize(), 1));        // lower == upper

        assertDoesNotThrow(() -> new HeapPage(data));
    }
}
