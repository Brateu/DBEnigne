package db.storage.page;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static db.storage.DiskManager.PAGE_SIZE;
import static db.storage.page.Page.HEADER_SIZE;
import static db.storage.page.Page.LAYOUT_VERSION;
import static db.storage.page.Page.LINE_POINTER_SIZE;
import static org.junit.jupiter.api.Assertions.*;

class PageTest {

    private static final int MAX_SPECIAL_SIZE = PAGE_SIZE - HEADER_SIZE - LINE_POINTER_SIZE;

    private static Page initializedPage(int specialSize) {
        Page page = new Page(new byte[PAGE_SIZE]);
        page.init(specialSize);
        return page;
    }

    /** Reads a big-endian unsigned 16-bit value straight from the raw bytes. */
    private static int rawShort(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    // ---- constructor ----

    @Test
    void constructorRejectsNull() {
        assertThrows(NullPointerException.class, () -> new Page(null));
    }

    @Test
    void constructorRejectsWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> new Page(new byte[PAGE_SIZE - 1]));
        assertThrows(IllegalArgumentException.class, () -> new Page(new byte[PAGE_SIZE + 1]));
        assertThrows(IllegalArgumentException.class, () -> new Page(new byte[0]));
    }

    @Test
    void constructorDoesNotModifyBytes() {
        byte[] data = new byte[PAGE_SIZE];
        Page original = new Page(data);
        original.init(0);
        original.setLsn(42);
        original.setChecksum(0xCAFEBABE);
        for (int i = 2000; i < PAGE_SIZE; i++) {
            data[i] = (byte) i;
        }
        byte[] before = data.clone();

        new Page(data);

        assertArrayEquals(before, data);
    }

    // ---- header validation ----

    /** A valid heap page holding two items, as raw bytes we can then damage. */
    private static byte[] validPageBytes() {
        byte[] data = new byte[PAGE_SIZE];
        Page page = new Page(data);
        page.init(0);
        page.insert(item(100, 1));
        page.insert(item(50, 2));
        return data;
    }

    private static void putRawShort(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 8);
        data[offset + 1] = (byte) value;
    }

    private static CorruptPageException assertCorrupt(byte[] data) {
        CorruptPageException e = assertThrows(CorruptPageException.class, () -> new Page(data));
        assertNotNull(e.getMessage());
        assertFalse(e.getMessage().isBlank());
        return e;
    }

    @Test
    void validPageWithItemsIsAccepted() {
        byte[] data = validPageBytes();

        Page page = new Page(data);

        assertEquals(2, page.slotCount());
        assertArrayEquals(item(100, 1), page.get(0));
    }

    @Test
    void corruptPageExceptionIsAStorageException() {
        byte[] data = validPageBytes();
        putRawShort(data, 20, 99);

        assertInstanceOf(db.storage.StorageException.class, assertCorrupt(data));
    }

    @Test
    void lowerInsideHeaderIsCorrupt() {
        byte[] data = validPageBytes();
        putRawShort(data, 14, HEADER_SIZE - LINE_POINTER_SIZE);

        assertCorrupt(data);
    }

    @Test
    void lowerAboveUpperIsCorrupt() {
        byte[] data = validPageBytes();
        int upper = rawShort(data, 16);
        putRawShort(data, 14, upper + LINE_POINTER_SIZE);

        CorruptPageException e = assertCorrupt(data);
        assertTrue(e.getMessage().contains(String.valueOf(upper + LINE_POINTER_SIZE)), "message names the bad lower");
    }

    @Test
    void lowerEqualToUpperIsAccepted() {
        byte[] data = new byte[PAGE_SIZE];
        Page full = new Page(data);
        full.init(0);
        full.insert(item(full.maxItemSize(), 1));        // lower == upper

        assertEquals(1, new Page(data).slotCount());
    }

    @Test
    void upperAboveSpecialIsCorrupt() {
        byte[] data = new byte[PAGE_SIZE];
        new Page(data).init(16);
        putRawShort(data, 16, PAGE_SIZE - 8);            // upper inside special space

        assertCorrupt(data);
    }

    @Test
    void specialBeyondPageIsCorrupt() {
        byte[] data = validPageBytes();
        putRawShort(data, 18, PAGE_SIZE + 4);

        assertCorrupt(data);
    }

    @Test
    void misalignedLowerIsCorrupt() {
        byte[] data = validPageBytes();
        putRawShort(data, 14, HEADER_SIZE + 2 * LINE_POINTER_SIZE + 2);

        assertCorrupt(data);
    }

    @Test
    void wrongVersionIsCorrupt() {
        byte[] data = validPageBytes();
        putRawShort(data, 20, LAYOUT_VERSION + 1);

        CorruptPageException e = assertCorrupt(data);
        assertTrue(e.getMessage().contains(String.valueOf(LAYOUT_VERSION + 1)), "message names the bad version");
    }

    @Test
    void zeroVersionOnNonNewPageIsCorrupt() {
        byte[] data = validPageBytes();
        putRawShort(data, 20, 0);

        assertCorrupt(data);
    }

    @Test
    void garbageBytesAreCorrupt() {
        byte[] data = new byte[PAGE_SIZE];
        Arrays.fill(data, (byte) 0x7F);

        assertCorrupt(data);
    }

    // ---- fresh (zeroed) page ----

    @Test
    void zeroedPageIsNew() {
        Page page = new Page(new byte[PAGE_SIZE]);

        assertTrue(page.isNew());
        assertEquals(0, page.slotCount());
    }

    // ---- init ----

    @Test
    void initFormatsHeapStyleHeader() {
        Page page = initializedPage(0);

        assertFalse(page.isNew());
        assertEquals(HEADER_SIZE, page.getLower());
        assertEquals(PAGE_SIZE, page.getUpper());
        assertEquals(PAGE_SIZE, page.getSpecial());
        assertEquals(LAYOUT_VERSION, page.getVersion());
        assertEquals(0, page.getLsn());
        assertEquals(0, page.getChecksum());
        assertEquals(0, page.getFlags());
        assertEquals(0, page.slotCount());
        assertEquals(PAGE_SIZE - HEADER_SIZE, page.freeSpace());
    }

    @Test
    void initReservesSpecialSpaceAtEnd() {
        Page page = initializedPage(16);

        assertEquals(PAGE_SIZE - 16, page.getSpecial());
        assertEquals(PAGE_SIZE - 16, page.getUpper());
        assertEquals(PAGE_SIZE - HEADER_SIZE - 16, page.freeSpace());
    }

    @Test
    void initWritesHeaderAtDocumentedOffsets() {
        byte[] data = new byte[PAGE_SIZE];
        new Page(data).init(16);

        assertEquals(HEADER_SIZE, rawShort(data, 14), "lower at offset 14");
        assertEquals(PAGE_SIZE - 16, rawShort(data, 16), "upper at offset 16");
        assertEquals(PAGE_SIZE - 16, rawShort(data, 18), "special at offset 18");
        assertEquals(LAYOUT_VERSION, rawShort(data, 20), "version at offset 20");
        assertEquals(0, rawShort(data, 22), "reserved at offset 22");
    }

    @Test
    void initClearsPreviousContents() {
        byte[] data = new byte[PAGE_SIZE];
        Page page = new Page(data);                     // wrap while zeroed: garbage would be rejected as corrupt
        Arrays.fill(data, (byte) 0x7F);

        page.init(0);

        for (int i = 0; i < 14; i++) {
            assertEquals(0, data[i], "lsn/checksum/flags byte " + i);
        }
        for (int i = HEADER_SIZE; i < PAGE_SIZE; i++) {
            assertEquals(0, data[i], "body byte " + i);
        }
    }

    @Test
    void initReformatsAnInitializedPage() {
        Page page = initializedPage(16);
        page.setLsn(99);

        page.init(0);

        assertEquals(0, page.getLsn());
        assertEquals(PAGE_SIZE, page.getSpecial());
    }

    @Test
    void initAcceptsLargestSpecialSize() {
        Page page = initializedPage(MAX_SPECIAL_SIZE);

        assertEquals(HEADER_SIZE + LINE_POINTER_SIZE, page.getSpecial());
        assertEquals(LINE_POINTER_SIZE, page.freeSpace());
    }

    @Test
    void initRejectsBadSpecialSize() {
        Page page = new Page(new byte[PAGE_SIZE]);

        assertThrows(IllegalArgumentException.class, () -> page.init(-1));
        assertThrows(IllegalArgumentException.class, () -> page.init(MAX_SPECIAL_SIZE + 1));
        assertThrows(IllegalArgumentException.class, () -> page.init(PAGE_SIZE));
    }

    @Test
    void failedInitLeavesPageUntouched() {
        Page page = initializedPage(0);
        page.setLsn(7);

        assertThrows(IllegalArgumentException.class, () -> page.init(-1));

        assertEquals(7, page.getLsn());
        assertEquals(PAGE_SIZE, page.getSpecial());
    }

    // ---- lsn ----

    @Test
    void lsnRoundTrip() {
        Page page = initializedPage(0);

        page.setLsn(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, page.getLsn());

        page.setLsn(0);
        assertEquals(0, page.getLsn());
    }

    @Test
    void setLsnRejectsNegativeAndKeepsOldValue() {
        Page page = initializedPage(0);
        page.setLsn(5);

        assertThrows(IllegalArgumentException.class, () -> page.setLsn(-1));

        assertEquals(5, page.getLsn());
    }

    // ---- checksum ----

    @Test
    void checksumRoundTripUsesAllFourBytes() {
        byte[] data = new byte[PAGE_SIZE];
        Page page = new Page(data);
        page.init(0);

        page.setChecksum(0x12345678);

        assertEquals(0x12345678, page.getChecksum());
        assertEquals(0x12, data[8]);
        assertEquals(0x34, data[9]);
        assertEquals(0x56, data[10]);
        assertEquals(0x78, data[11]);
    }

    @Test
    void checksumDoesNotTouchNeighbouringFields() {
        Page page = initializedPage(0);

        page.setChecksum(-1);

        assertEquals(-1, page.getChecksum());
        assertEquals(0, page.getLsn());
        assertEquals(0, page.getFlags());
        assertEquals(HEADER_SIZE, page.getLower());
    }

    // ---- flags ----

    @Test
    void flagsAreUnsigned16Bit() {
        Page page = initializedPage(0);

        page.setFlags(0xFFFF);

        assertEquals(0xFFFF, page.getFlags());
        assertEquals(HEADER_SIZE, page.getLower());
    }

    // ---- maxItemSize ----

    @Test
    void maxItemSizeLeavesRoomForOneLinePointer() {
        assertEquals(PAGE_SIZE - HEADER_SIZE - LINE_POINTER_SIZE, initializedPage(0).maxItemSize());
        assertEquals(PAGE_SIZE - 16 - HEADER_SIZE - LINE_POINTER_SIZE, initializedPage(16).maxItemSize());
    }

    // ---- insert / get ----

    private static byte[] item(int length, int seed) {
        byte[] item = new byte[length];
        for (int i = 0; i < length; i++) {
            item[i] = (byte) (seed + i);
        }
        return item;
    }

    @Test
    void insertThenGetReturnsSameBytes() {
        Page page = initializedPage(0);
        byte[] item = item(100, 1);

        int slot = page.insert(item);

        assertEquals(0, slot);
        assertArrayEquals(item, page.get(slot));
    }

    @Test
    void slotsAreNumberedSequentially() {
        Page page = initializedPage(0);

        assertEquals(0, page.insert(item(10, 1)));
        assertEquals(1, page.insert(item(20, 2)));
        assertEquals(2, page.insert(item(30, 3)));
        assertEquals(3, page.slotCount());
    }

    @Test
    void insertMovesUpperDownAndLowerUp() {
        Page page = initializedPage(0);

        page.insert(item(100, 1));

        assertEquals(PAGE_SIZE - 100, page.getUpper());
        assertEquals(HEADER_SIZE + LINE_POINTER_SIZE, page.getLower());
        assertEquals(PAGE_SIZE - HEADER_SIZE - 100 - LINE_POINTER_SIZE, page.freeSpace());
    }

    @Test
    void linePointerHasDocumentedBitLayout() {
        byte[] data = new byte[PAGE_SIZE];
        Page page = new Page(data);
        page.init(0);
        page.insert(item(PAGE_SIZE - 3996, 1));          // 100 bytes at offset 3996
        page.insert(item(50, 2));                        // 50 bytes at offset 3946

        // slot 0: offset 3996 | NORMAL(1) << 15 | length 100 << 17 = 0x00C88F9C
        assertEquals(0x00, data[24] & 0xFF);
        assertEquals(0xC8, data[25] & 0xFF);
        assertEquals(0x8F, data[26] & 0xFF);
        assertEquals(0x9C, data[27] & 0xFF);
        // slot 1: offset 3946 | 1 << 15 | 50 << 17 = 0x00648F6A
        assertEquals(0x00, data[28] & 0xFF);
        assertEquals(0x64, data[29] & 0xFF);
        assertEquals(0x8F, data[30] & 0xFF);
        assertEquals(0x6A, data[31] & 0xFF);
    }

    @Test
    void itemsGrowDownFromSpecial() {
        byte[] data = new byte[PAGE_SIZE];
        Page page = new Page(data);
        page.init(16);
        byte[] first = item(10, 1);
        byte[] second = item(20, 50);

        page.insert(first);
        page.insert(second);

        int special = PAGE_SIZE - 16;
        assertArrayEquals(first, Arrays.copyOfRange(data, special - 10, special));
        assertArrayEquals(second, Arrays.copyOfRange(data, special - 30, special - 10));
        assertEquals(special - 30, page.getUpper());
    }

    @Test
    void insertsNeverTouchSpecialSpace() {
        byte[] data = new byte[PAGE_SIZE];
        Page page = new Page(data);
        page.init(16);
        for (int i = PAGE_SIZE - 16; i < PAGE_SIZE; i++) {
            data[i] = (byte) 0xAB;
        }

        while (page.freeSpace() >= 50 + LINE_POINTER_SIZE) {
            page.insert(item(50, page.slotCount()));
        }

        for (int i = PAGE_SIZE - 16; i < PAGE_SIZE; i++) {
            assertEquals((byte) 0xAB, data[i], "special byte " + i);
        }
    }

    @Test
    void largeItemRoundTrip() {
        Page page = initializedPage(0);
        byte[] item = item(4000, 7);

        int slot = page.insert(item);

        assertArrayEquals(item, page.get(slot));
    }

    @Test
    void itemOfExactlyMaxItemSizeFillsThePage() {
        Page page = initializedPage(0);
        byte[] item = item(page.maxItemSize(), 3);

        page.insert(item);

        assertEquals(0, page.freeSpace());
        assertEquals(page.getLower(), page.getUpper());
        assertArrayEquals(item, page.get(0));
    }

    @Test
    void insertRequiresRoomForItemPlusLinePointer() {
        Page page = initializedPage(0);
        byte[] filler = item(PAGE_SIZE - HEADER_SIZE - LINE_POINTER_SIZE - 104, 1);
        page.insert(filler);
        assertEquals(104, page.freeSpace());

        // 104 bytes of item + 4 bytes of line pointer = 108 > 104
        assertThrows(IllegalStateException.class, () -> page.insert(item(104, 2)));
        assertThrows(IllegalStateException.class, () -> page.insert(item(101, 2)));

        byte[] fits = item(100, 9);
        page.insert(fits);
        assertEquals(0, page.freeSpace());
        assertArrayEquals(filler, page.get(0));
        assertArrayEquals(fits, page.get(1));
    }

    @Test
    void failedInsertLeavesPageUntouched() {
        Page page = initializedPage(0);
        page.insert(item(page.maxItemSize() - 10, 1));
        int lower = page.getLower();
        int upper = page.getUpper();

        assertThrows(IllegalStateException.class, () -> page.insert(item(20, 2)));

        assertEquals(lower, page.getLower());
        assertEquals(upper, page.getUpper());
        assertEquals(1, page.slotCount());
    }

    @Test
    void insertRejectsBadItems() {
        Page page = initializedPage(0);

        assertThrows(NullPointerException.class, () -> page.insert(null));
        assertThrows(IllegalArgumentException.class, () -> page.insert(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> page.insert(new byte[page.maxItemSize() + 1]));
        assertEquals(0, page.slotCount());
    }

    @Test
    void insertAndGetOnUnformattedPageThrow() {
        Page page = new Page(new byte[PAGE_SIZE]);

        assertThrows(IllegalStateException.class, () -> page.insert(item(10, 1)));
        assertThrows(IllegalStateException.class, () -> page.get(0));
    }

    @Test
    void getRejectsSlotsOutOfRange() {
        Page page = initializedPage(0);
        assertThrows(IllegalArgumentException.class, () -> page.get(0));

        page.insert(item(10, 1));

        assertThrows(IllegalArgumentException.class, () -> page.get(-1));
        assertThrows(IllegalArgumentException.class, () -> page.get(1));
        assertThrows(IllegalArgumentException.class, () -> page.get(100));
    }

    @Test
    void getReturnsCopyAndInsertCopiesInput() {
        Page page = initializedPage(0);
        byte[] item = item(10, 1);
        byte[] expected = item.clone();
        int slot = page.insert(item);

        item[0] = 99;                       // caller mutates its own array after insert
        page.get(slot)[1] = 99;             // caller mutates the array get returned

        assertArrayEquals(expected, page.get(slot));
    }

    @Test
    void randomInsertsMatchModel() {
        java.util.Random random = new java.util.Random(42);
        for (int round = 0; round < 50; round++) {
            Page page = initializedPage(random.nextBoolean() ? 0 : 16);
            java.util.List<byte[]> model = new java.util.ArrayList<>();
            int expectedFree = page.freeSpace();

            while (true) {
                byte[] item = item(1 + random.nextInt(300), random.nextInt());
                if (item.length + LINE_POINTER_SIZE > page.freeSpace()) {
                    assertThrows(IllegalStateException.class, () -> page.insert(item));
                    break;
                }
                assertEquals(model.size(), page.insert(item));
                model.add(item);
                expectedFree -= item.length + LINE_POINTER_SIZE;
                assertEquals(expectedFree, page.freeSpace());
            }

            assertEquals(model.size(), page.slotCount());
            for (int slot = 0; slot < model.size(); slot++) {
                assertArrayEquals(model.get(slot), page.get(slot), "round " + round + " slot " + slot);
            }
        }
    }

    // ---- stateOf / delete ----

    @Test
    void insertedItemIsNormal() {
        Page page = initializedPage(0);
        int slot = page.insert(item(10, 1));

        assertEquals(LinePointerState.NORMAL, page.stateOf(slot));
    }

    @Test
    void deleteMarksSlotDeadAndGetThrows() {
        Page page = initializedPage(0);
        int slot = page.insert(item(10, 1));

        page.delete(slot);

        assertEquals(LinePointerState.DEAD, page.stateOf(slot));
        assertThrows(java.util.NoSuchElementException.class, () -> page.get(slot));
    }

    @Test
    void deleteTwiceThrows() {
        Page page = initializedPage(0);
        int slot = page.insert(item(10, 1));
        page.delete(slot);

        assertThrows(java.util.NoSuchElementException.class, () -> page.delete(slot));
        assertEquals(LinePointerState.DEAD, page.stateOf(slot));
    }

    @Test
    void deleteLeavesOtherSlotsIntact() {
        Page page = initializedPage(0);
        byte[] a = item(10, 1);
        byte[] b = item(20, 2);
        byte[] c = item(30, 3);
        page.insert(a);
        page.insert(b);
        page.insert(c);

        page.delete(1);

        assertArrayEquals(a, page.get(0));
        assertArrayEquals(c, page.get(2));
        assertEquals(LinePointerState.NORMAL, page.stateOf(0));
        assertEquals(LinePointerState.NORMAL, page.stateOf(2));
    }

    @Test
    void deleteDoesNotReclaimSpaceOrMoveBounds() {
        Page page = initializedPage(0);
        page.insert(item(10, 1));
        page.insert(item(20, 2));
        int lower = page.getLower();
        int upper = page.getUpper();
        int free = page.freeSpace();

        page.delete(0);

        assertEquals(lower, page.getLower());
        assertEquals(upper, page.getUpper());
        assertEquals(free, page.freeSpace());
        assertEquals(2, page.slotCount());
    }

    @Test
    void deleteChangesOnlyStateBitsOfLinePointer() {
        byte[] data = new byte[PAGE_SIZE];
        Page page = new Page(data);
        page.init(0);
        page.insert(item(100, 1));                       // offset 3996, length 100 -> 0x00C88F9C
        byte[] itemBytes = Arrays.copyOfRange(data, 3996, PAGE_SIZE);

        page.delete(0);

        // same offset and length, state 3 (DEAD) -> 0x00C98F9C
        assertEquals(0x00, data[24] & 0xFF);
        assertEquals(0xC9, data[25] & 0xFF);
        assertEquals(0x8F, data[26] & 0xFF);
        assertEquals(0x9C, data[27] & 0xFF);
        assertArrayEquals(itemBytes, Arrays.copyOfRange(data, 3996, PAGE_SIZE), "item bytes untouched");
    }

    @Test
    void insertAfterDeleteDoesNotReuseDeadSlot() {
        Page page = initializedPage(0);
        page.insert(item(10, 1));
        page.delete(0);

        int slot = page.insert(item(10, 2));

        assertEquals(1, slot);
        assertEquals(LinePointerState.DEAD, page.stateOf(0));
    }

    @Test
    void stateOfAndDeleteValidateSlot() {
        Page page = initializedPage(0);
        page.insert(item(10, 1));

        assertThrows(IllegalArgumentException.class, () -> page.stateOf(-1));
        assertThrows(IllegalArgumentException.class, () -> page.stateOf(1));
        assertThrows(IllegalArgumentException.class, () -> page.delete(-1));
        assertThrows(IllegalArgumentException.class, () -> page.delete(1));
    }

    @Test
    void stateOfAndDeleteOnUnformattedPageThrow() {
        Page page = new Page(new byte[PAGE_SIZE]);

        assertThrows(IllegalStateException.class, () -> page.stateOf(0));
        assertThrows(IllegalStateException.class, () -> page.delete(0));
    }

    @Test
    void randomInsertsAndDeletesMatchModel() {
        java.util.Random random = new java.util.Random(7);
        for (int round = 0; round < 50; round++) {
            Page page = initializedPage(0);
            java.util.Map<Integer, byte[]> live = new java.util.HashMap<>();
            java.util.Set<Integer> dead = new java.util.HashSet<>();

            for (int op = 0; op < 200; op++) {
                if (!live.isEmpty() && random.nextInt(3) == 0) {
                    Integer[] slots = live.keySet().toArray(new Integer[0]);
                    int victim = slots[random.nextInt(slots.length)];
                    page.delete(victim);
                    live.remove(victim);
                    dead.add(victim);
                } else {
                    byte[] item = item(1 + random.nextInt(60), random.nextInt());
                    if (item.length + LINE_POINTER_SIZE > page.freeSpace()) {
                        break;
                    }
                    live.put(page.insert(item), item);
                }
            }

            assertEquals(live.size() + dead.size(), page.slotCount());
            for (var entry : live.entrySet()) {
                assertEquals(LinePointerState.NORMAL, page.stateOf(entry.getKey()));
                assertArrayEquals(entry.getValue(), page.get(entry.getKey()), "round " + round);
            }
            for (int slot : dead) {
                assertEquals(LinePointerState.DEAD, page.stateOf(slot));
                assertThrows(java.util.NoSuchElementException.class, () -> page.get(slot));
            }
        }
    }

    // ---- canFit / compact ----

    /** Fills a heap page with 100-byte items until no more fit; returns the items by slot. */
    private static java.util.List<byte[]> fillWith100ByteItems(Page page) {
        java.util.List<byte[]> items = new java.util.ArrayList<>();
        while (page.freeSpace() >= 100 + LINE_POINTER_SIZE) {
            byte[] item = item(100, items.size() * 3);
            page.insert(item);
            items.add(item);
        }
        return items;
    }

    @Test
    void canFitOnEmptyPage() {
        Page page = initializedPage(0);

        assertTrue(page.canFit(1));
        assertTrue(page.canFit(page.maxItemSize()));
        assertFalse(page.canFit(page.maxItemSize() + 1));
    }

    @Test
    void canFitRejectsNegativeLength() {
        Page page = initializedPage(0);

        assertThrows(IllegalArgumentException.class, () -> page.canFit(-1));
        assertTrue(page.canFit(0));
    }

    @Test
    void canFitOnUnformattedPageIsFalse() {
        Page page = new Page(new byte[PAGE_SIZE]);

        assertFalse(page.canFit(1));
    }

    @Test
    void canFitBoundaryIncludesLinePointer() {
        Page page = initializedPage(0);
        page.insert(item(PAGE_SIZE - HEADER_SIZE - LINE_POINTER_SIZE - 104, 1));
        assertEquals(104, page.freeSpace());

        assertTrue(page.canFit(100));
        assertFalse(page.canFit(101));
    }

    @Test
    void canFitIgnoresLiveItemsAndCountsDeadOnes() {
        Page page = initializedPage(0);
        fillWith100ByteItems(page);
        assertFalse(page.canFit(100), "full page of live items");

        page.delete(3);

        assertTrue(page.canFit(100), "dead item's bytes are reclaimable");
        assertFalse(page.canFit(101 + page.freeSpace()), "only one dead item's worth");
    }

    @Test
    void canFitGivesSameAnswerBeforeAndAfterCompaction() {
        Page page = initializedPage(0);
        fillWith100ByteItems(page);
        page.delete(2);
        page.delete(5);
        boolean[] before = new boolean[400];
        for (int length = 0; length < before.length; length++) {
            before[length] = page.canFit(length);
        }

        page.compact();

        for (int length = 0; length < before.length; length++) {
            assertEquals(before[length], page.canFit(length), "length " + length);
        }
    }

    @Test
    void compactReclaimsDeadSpaceAndKeepsSlotNumbers() {
        Page page = initializedPage(0);
        byte[] a = item(100, 1);
        byte[] b = item(200, 2);
        byte[] c = item(300, 3);
        page.insert(a);
        page.insert(b);
        page.insert(c);
        page.delete(1);
        int lower = page.getLower();
        int freeBefore = page.freeSpace();

        page.compact();

        assertEquals(PAGE_SIZE - 400, page.getUpper());
        assertEquals(lower, page.getLower());
        assertEquals(freeBefore + 200, page.freeSpace());
        assertEquals(3, page.slotCount());
        assertArrayEquals(a, page.get(0));
        assertArrayEquals(c, page.get(2));
        assertEquals(LinePointerState.DEAD, page.stateOf(1));
    }

    @Test
    void compactWithNoDeadItemsKeepsEverything() {
        Page page = initializedPage(0);
        byte[] a = item(50, 1);
        byte[] b = item(70, 2);
        page.insert(a);
        page.insert(b);
        int upper = page.getUpper();

        page.compact();

        assertEquals(upper, page.getUpper());
        assertArrayEquals(a, page.get(0));
        assertArrayEquals(b, page.get(1));
    }

    @Test
    void compactWithAllItemsDeadEmptiesItemArea() {
        Page page = initializedPage(0);
        page.insert(item(50, 1));
        page.insert(item(70, 2));
        page.delete(0);
        page.delete(1);

        page.compact();

        assertEquals(page.getSpecial(), page.getUpper());
        assertEquals(2, page.slotCount());
    }

    @Test
    void compactOnEmptyPageIsNoOp() {
        Page page = initializedPage(0);

        page.compact();

        assertEquals(PAGE_SIZE, page.getUpper());
        assertEquals(HEADER_SIZE, page.getLower());
    }

    @Test
    void compactPacksAgainstSpecialAndLeavesItIntact() {
        byte[] data = new byte[PAGE_SIZE];
        Page page = new Page(data);
        page.init(16);
        for (int i = PAGE_SIZE - 16; i < PAGE_SIZE; i++) {
            data[i] = (byte) 0xAB;
        }
        byte[] a = item(40, 1);
        byte[] c = item(60, 3);
        page.insert(a);
        page.insert(item(50, 2));
        page.insert(c);
        page.delete(1);

        page.compact();

        assertEquals(PAGE_SIZE - 16 - 100, page.getUpper());
        assertArrayEquals(a, page.get(0));
        assertArrayEquals(c, page.get(2));
        for (int i = PAGE_SIZE - 16; i < PAGE_SIZE; i++) {
            assertEquals((byte) 0xAB, data[i], "special byte " + i);
        }
    }

    @Test
    void compactResetsDeadLinePointerToZeroOffsetAndLength() {
        byte[] data = new byte[PAGE_SIZE];
        Page page = new Page(data);
        page.init(0);
        page.insert(item(100, 1));
        page.delete(0);

        page.compact();

        // offset 0 | DEAD(3) << 15 | length 0 << 17 = 0x00018000
        assertEquals(0x00, data[24] & 0xFF);
        assertEquals(0x01, data[25] & 0xFF);
        assertEquals(0x80, data[26] & 0xFF);
        assertEquals(0x00, data[27] & 0xFF);
    }

    @Test
    void insertCompactsWhenOnlyFragmentedSpaceIsLeft() {
        Page page = initializedPage(0);
        java.util.List<byte[]> items = fillWith100ByteItems(page);
        page.delete(4);
        page.delete(10);
        page.delete(20);
        assertTrue(page.freeSpace() < 250 + LINE_POINTER_SIZE, "precondition: doesn't fit without compaction");

        byte[] big = item(250, 77);
        int slot = page.insert(big);

        assertEquals(items.size(), slot);
        assertArrayEquals(big, page.get(slot));
        for (int s = 0; s < items.size(); s++) {
            if (s == 4 || s == 10 || s == 20) {
                assertEquals(LinePointerState.DEAD, page.stateOf(s));
            } else {
                assertArrayEquals(items.get(s), page.get(s), "slot " + s);
            }
        }
    }

    @Test
    void insertThrowsWhenEvenCompactionWontHelp() {
        Page page = initializedPage(0);
        fillWith100ByteItems(page);
        page.delete(0);
        int lower = page.getLower();
        int upper = page.getUpper();

        assertThrows(IllegalStateException.class, () -> page.insert(item(300, 1)));

        assertEquals(lower, page.getLower());
        assertEquals(upper, page.getUpper(), "no compaction on a failed insert");
    }

    @Test
    void randomInsertsDeletesWithCompactionMatchModel() {
        java.util.Random random = new java.util.Random(2026);
        for (int round = 0; round < 50; round++) {
            Page page = initializedPage(random.nextBoolean() ? 0 : 16);
            java.util.Map<Integer, byte[]> live = new java.util.HashMap<>();
            int liveBytes = 0;

            for (int op = 0; op < 400; op++) {
                if (!live.isEmpty() && random.nextInt(3) == 0) {
                    Integer[] slots = live.keySet().toArray(new Integer[0]);
                    int victim = slots[random.nextInt(slots.length)];
                    page.delete(victim);
                    liveBytes -= live.remove(victim).length;
                } else {
                    byte[] item = item(1 + random.nextInt(200), random.nextInt());
                    int reclaimable = page.getSpecial() - page.getLower() - liveBytes;
                    boolean shouldFit = item.length + LINE_POINTER_SIZE <= reclaimable;
                    assertEquals(shouldFit, page.canFit(item.length), "round " + round + " op " + op);
                    if (shouldFit) {
                        live.put(page.insert(item), item);
                        liveBytes += item.length;
                    } else {
                        assertThrows(IllegalStateException.class, () -> page.insert(item));
                    }
                }
            }

            for (var entry : live.entrySet()) {
                assertArrayEquals(entry.getValue(), page.get(entry.getKey()), "round " + round);
            }
        }
    }

    // ---- update ----

    @Test
    void updateSameSizeOverwritesInPlace() {
        Page page = initializedPage(0);
        page.insert(item(50, 1));
        int slot = page.insert(item(100, 2));
        int upper = page.getUpper();
        int free = page.freeSpace();
        byte[] replacement = item(100, 9);

        page.update(slot, replacement);

        assertArrayEquals(replacement, page.get(slot));
        assertEquals(upper, page.getUpper());
        assertEquals(free, page.freeSpace());
    }

    @Test
    void updateShrinkReturnsOnlyNewBytes() {
        Page page = initializedPage(0);
        int slot = page.insert(item(100, 1));
        int upper = page.getUpper();
        byte[] smaller = item(60, 9);

        page.update(slot, smaller);

        assertArrayEquals(smaller, page.get(slot));
        assertEquals(upper, page.getUpper(), "shrink happens in place");
    }

    @Test
    void updateShrinkWritesNewLengthIntoLinePointer() {
        byte[] data = new byte[PAGE_SIZE];
        Page page = new Page(data);
        page.init(0);
        page.insert(item(100, 1));                       // offset 3996, length 100

        page.update(0, item(60, 2));

        // offset 3996 | NORMAL(1) << 15 | length 60 << 17 = 0x00788F9C
        assertEquals(0x00, data[24] & 0xFF);
        assertEquals(0x78, data[25] & 0xFF);
        assertEquals(0x8F, data[26] & 0xFF);
        assertEquals(0x9C, data[27] & 0xFF);
    }

    @Test
    void updateGrowKeepsSlotNumberAndLower() {
        Page page = initializedPage(0);
        byte[] a = item(50, 1);
        byte[] c = item(70, 3);
        page.insert(a);
        int slot = page.insert(item(40, 2));
        page.insert(c);
        int lower = page.getLower();
        byte[] bigger = item(200, 9);

        page.update(slot, bigger);

        assertEquals(3, page.slotCount());
        assertEquals(lower, page.getLower());
        assertEquals(LinePointerState.NORMAL, page.stateOf(slot));
        assertArrayEquals(bigger, page.get(slot));
        assertArrayEquals(a, page.get(0));
        assertArrayEquals(c, page.get(2));
    }

    @Test
    void updateGrowUsesGapWhenItFits() {
        Page page = initializedPage(0);
        int slot = page.insert(item(50, 1));
        int upper = page.getUpper();

        page.update(slot, item(200, 2));

        assertEquals(upper - 200, page.getUpper(), "placed in the gap, no compaction");
    }

    @Test
    void updateGrowCountsOldItemAsFreedAndNeedsNoNewLinePointer() {
        Page page = initializedPage(0);
        java.util.List<byte[]> items = fillWith100ByteItems(page);
        int gap = page.freeSpace();                      // < 104, and no dead items
        byte[] exact = item(100 + gap, 9);               // fits only if old 100 bytes count as free

        page.update(5, exact);

        assertArrayEquals(exact, page.get(5));
        assertEquals(0, page.freeSpace());
        assertEquals(items.size(), page.slotCount());
        for (int s = 0; s < items.size(); s++) {
            if (s != 5) {
                assertArrayEquals(items.get(s), page.get(s), "slot " + s);
            }
        }
    }

    @Test
    void updateGrowReclaimsDeadSpaceViaCompaction() {
        Page page = initializedPage(0);
        java.util.List<byte[]> items = fillWith100ByteItems(page);
        int gap = page.freeSpace();
        page.delete(2);
        byte[] big = item(100 + gap + 100, 9);           // old item + gap + dead item 2

        page.update(5, big);

        assertArrayEquals(big, page.get(5));
        assertEquals(LinePointerState.DEAD, page.stateOf(2));
        for (int s = 0; s < items.size(); s++) {
            if (s != 2 && s != 5) {
                assertArrayEquals(items.get(s), page.get(s), "slot " + s);
            }
        }
    }

    @Test
    void updateThatDoesNotFitLeavesRowUntouched() {
        Page page = initializedPage(0);
        java.util.List<byte[]> items = fillWith100ByteItems(page);
        int gap = page.freeSpace();
        int lower = page.getLower();
        int upper = page.getUpper();

        assertThrows(IllegalStateException.class, () -> page.update(5, item(100 + gap + 1, 9)));

        assertEquals(LinePointerState.NORMAL, page.stateOf(5));
        assertArrayEquals(items.get(5), page.get(5));
        assertEquals(lower, page.getLower());
        assertEquals(upper, page.getUpper());
    }

    @Test
    void updateRejectsBadItems() {
        Page page = initializedPage(0);
        int slot = page.insert(item(10, 1));

        assertThrows(NullPointerException.class, () -> page.update(slot, null));
        assertThrows(IllegalArgumentException.class, () -> page.update(slot, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> page.update(slot, new byte[page.maxItemSize() + 1]));
        assertArrayEquals(item(10, 1), page.get(slot));
    }

    @Test
    void updateValidatesSlotAndState() {
        Page page = initializedPage(0);
        int slot = page.insert(item(10, 1));
        page.delete(slot);

        assertThrows(java.util.NoSuchElementException.class, () -> page.update(slot, item(5, 2)));
        assertThrows(IllegalArgumentException.class, () -> page.update(-1, item(5, 2)));
        assertThrows(IllegalArgumentException.class, () -> page.update(1, item(5, 2)));
        assertThrows(IllegalStateException.class,
                () -> new Page(new byte[PAGE_SIZE]).update(0, item(5, 2)));
    }

    @Test
    void randomInsertsDeletesUpdatesMatchModel() {
        java.util.Random random = new java.util.Random(99);
        for (int round = 0; round < 50; round++) {
            Page page = initializedPage(random.nextBoolean() ? 0 : 16);
            java.util.Map<Integer, byte[]> live = new java.util.HashMap<>();
            int liveBytes = 0;

            for (int op = 0; op < 400; op++) {
                int choice = random.nextInt(4);
                byte[] item = item(1 + random.nextInt(200), random.nextInt());
                int reclaimable = page.getSpecial() - page.getLower() - liveBytes;
                if (!live.isEmpty() && choice == 0) {
                    Integer[] slots = live.keySet().toArray(new Integer[0]);
                    int victim = slots[random.nextInt(slots.length)];
                    page.delete(victim);
                    liveBytes -= live.remove(victim).length;
                } else if (!live.isEmpty() && choice == 1) {
                    Integer[] slots = live.keySet().toArray(new Integer[0]);
                    int target = slots[random.nextInt(slots.length)];
                    int oldLength = live.get(target).length;
                    if (item.length <= reclaimable + oldLength) {
                        page.update(target, item);
                        live.put(target, item);
                        liveBytes += item.length - oldLength;
                    } else {
                        assertThrows(IllegalStateException.class, () -> page.update(target, item));
                    }
                } else if (item.length + LINE_POINTER_SIZE <= reclaimable) {
                    live.put(page.insert(item), item);
                    liveBytes += item.length;
                }
            }

            int expectedSlots = live.isEmpty() ? 0 : java.util.Collections.max(live.keySet()) + 1;
            assertTrue(page.slotCount() >= expectedSlots, "round " + round);
            for (var entry : live.entrySet()) {
                assertArrayEquals(entry.getValue(), page.get(entry.getKey()), "round " + round + " slot " + entry.getKey());
            }
        }
    }

    // ---- stateless view ----

    @Test
    void twoPagesOverSameBytesSeeEachOthersChanges() {
        byte[] data = new byte[PAGE_SIZE];
        Page first = new Page(data);
        Page second = new Page(data);

        first.init(16);
        first.setLsn(123);

        assertFalse(second.isNew());
        assertEquals(123, second.getLsn());
        assertEquals(PAGE_SIZE - 16, second.getSpecial());
    }
}
