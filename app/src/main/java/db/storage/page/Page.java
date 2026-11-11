package db.storage.page;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.zip.CRC32C;

import static db.storage.DiskManager.PAGE_SIZE;
import static java.util.Objects.requireNonNull;

public class Page {

    public static final int HEADER_SIZE = 24;
    public static final int LINE_POINTER_SIZE = 4;
    public static final int LAYOUT_VERSION = 1;
    private static final int LSN_OFFSET = 0;
    private static final int CHECKSUM_OFFSET = 8;
    private static final int CHECKSUM_SIZE = 4;
    private static final int FLAGS_OFFSET = 12;
    private static final int LOWER_OFFSET = 14;
    private static final int UPPER_OFFSET = 16;
    private static final int SPECIAL_OFFSET = 18;
    private static final int VERSION_OFFSET = 20;
    private static final int RESERVED_OFFSET = 22;

    private static final int LP_OFFSET_MASK = 0x7FFF;
    private static final int LP_STATE_SHIFT = 15;
    private static final int LP_STATE_MASK = 0x3;
    private static final int LP_LENGTH_SHIFT = 17;
    private static final int LP_LENGTH_MASK = 0x7FFF;

    private final ByteBuffer buffer;

    private record LinePointer(int offset, int length, LinePointerState state) {
    }

    public Page(byte[] data) {
        requireNonNull(data, "data must not be null");
        if (data.length != PAGE_SIZE) {
            throw new IllegalArgumentException("data length " + data.length + " must be " + PAGE_SIZE);
        }
        buffer = ByteBuffer.wrap(data);
        if (!isNew()) {
            validateHeader();
        }
    }

    private void validateHeader() {
        if (!(HEADER_SIZE <= getLower() && getLower() <= getUpper() && getUpper() <= getSpecial() && getSpecial() <= PAGE_SIZE)) {
            throw new CorruptPageException("corrupt page header: expected 24 <= lower <= upper <= special <= 4096, got lower = " + getLower() + " upper = " + getUpper() + " special = " + getSpecial());
        }
        if (!((getLower() - HEADER_SIZE) % LINE_POINTER_SIZE == 0)) {
            throw new CorruptPageException("corrupt page header: line pointer array size " + (getLower() - HEADER_SIZE) + " is not a multiple of " + LINE_POINTER_SIZE);
        }
        if (!(getVersion() == LAYOUT_VERSION)) {
            throw new CorruptPageException("corrupt page header: unsupported layout version " + getVersion() + ", expected " + LAYOUT_VERSION);
        }
    }

    protected void init(int specialSize) {
        if (specialSize < 0 || specialSize > PAGE_SIZE - HEADER_SIZE - LINE_POINTER_SIZE) {
            throw new IllegalArgumentException("specialSize must be between 0 and " + (PAGE_SIZE - HEADER_SIZE - LINE_POINTER_SIZE) + ", got " + specialSize);
        }
        Arrays.fill(buffer.array(), (byte) 0);
        setLower(HEADER_SIZE);
        setSpecial(PAGE_SIZE - specialSize);
        setUpper(PAGE_SIZE - specialSize);
        setVersion(LAYOUT_VERSION);
    }

    public int getUpper() {
        return buffer.getShort(UPPER_OFFSET) & 0xFFFF;
    }

    private void setUpper(int upper) {
        buffer.putShort(UPPER_OFFSET, (short) upper);
    }

    public int getLower() {
        return buffer.getShort(LOWER_OFFSET) & 0xFFFF;
    }

    private void setLower(int lower) {
        buffer.putShort(LOWER_OFFSET, (short) lower);
    }

    public long getLsn() {
        return buffer.getLong(LSN_OFFSET);
    }

    public void setLsn(long lsn) {
        if (lsn < 0) {
            throw new IllegalArgumentException("lsn must be non-negative, got " + lsn);
        }
        buffer.putLong(LSN_OFFSET, lsn);
    }

    protected int getFlags() {
        return buffer.getShort(FLAGS_OFFSET) & 0xFFFF;
    }

    protected void setFlags(int flags) {
        buffer.putShort(FLAGS_OFFSET, (short) flags);
    }

    public static int computeChecksum(byte[] data, int pageNo) {
        requireNonNull(data);
        if (data.length != PAGE_SIZE) {
            throw new IllegalArgumentException("data length " + data.length + " must be " + PAGE_SIZE);
        }
        if (pageNo < 0) {
            throw new IllegalArgumentException("invalid page number, got " + pageNo);
        }
        CRC32C crc = new CRC32C();
        byte[] four = ByteBuffer.allocate(4).putInt(pageNo).array();
        crc.update(data, 0, CHECKSUM_OFFSET);
        crc.update(data, CHECKSUM_OFFSET + CHECKSUM_SIZE, PAGE_SIZE - (CHECKSUM_OFFSET + CHECKSUM_SIZE));
        crc.update(four);
        return (int) crc.getValue();
    }

    public static void stampChecksum(byte[] data, int pageNo) {
        int c = computeChecksum(data, pageNo);
        ByteBuffer.wrap(data).putInt(CHECKSUM_OFFSET, c);
    }

    private static boolean isAllZeros(byte[] data) {
        for (byte goat : data) {
            if (goat != 0) {
                return false;
            }
        }
        return true;
    }

    public static boolean verifyChecksum(byte[] data, int pageNo) {
        int created = computeChecksum(data, pageNo);
        int stored = ByteBuffer.wrap(data).getInt(CHECKSUM_OFFSET);
        if (created == stored) {
            return true;
        }
        return isAllZeros(data);
    }


    public int getChecksum() {
        return buffer.getInt(CHECKSUM_OFFSET);
    }

    public void setChecksum(int checksum) {
        buffer.putInt(CHECKSUM_OFFSET, checksum);
    }

    public int getVersion() {
        return buffer.getShort(VERSION_OFFSET) & 0xFFFF;
    }

    private void setVersion(int version) {
        buffer.putShort(VERSION_OFFSET, (short) version);
    }

    public int getSpecial() {
        return buffer.getShort(SPECIAL_OFFSET) & 0xFFFF;
    }

    private void setSpecial(int special) {
        buffer.putShort(SPECIAL_OFFSET, (short) special);
    }

    public boolean isNew() {
        return getUpper() == 0;
    }

    public int slotCount() {
        if (isNew()) {
            return 0;
        }
        return (getLower() - HEADER_SIZE) / LINE_POINTER_SIZE;
    }

    public int freeSpace() {
        return getUpper() - getLower();
    }

    private LinePointer readLinePointer(int slot) {
        int lp = buffer.getInt(HEADER_SIZE + (slot * LINE_POINTER_SIZE));
        int offset = lp & LP_OFFSET_MASK;
        int length = (lp >>> LP_LENGTH_SHIFT) & LP_LENGTH_MASK;
        LinePointerState state = LinePointerState.fromCode((lp >>> LP_STATE_SHIFT) & LP_STATE_MASK);
        return new LinePointer(offset, length, state);
    }

    private void writeLinePointer(int slot, int offset, int length, LinePointerState state) {
        int lp = offset | (state.getCode() << LP_STATE_SHIFT) | (length << LP_LENGTH_SHIFT);
        buffer.putInt(HEADER_SIZE + (slot * LINE_POINTER_SIZE), lp);
    }

    public int maxItemSize() {
        return getSpecial() - HEADER_SIZE - LINE_POINTER_SIZE;
    }

    public int insert(byte[] item) {
        requireNonNull(item, "item must not be null");
        if (isNew()) {
            throw new IllegalStateException("The page is not formatted");
        }
        if (item.length > maxItemSize() || item.length == 0) {
            throw new IllegalArgumentException("item length " + item.length + " must be greater than 0 and at most " + maxItemSize());
        }
        if (!canFit(item.length)) {
            throw new IllegalStateException("item length " + item.length + " is larger than available space, left " + reclaimableSpace());
        }
        if (freeSpace() < item.length + LINE_POINTER_SIZE) {
            compact();
        }
        int newUpper = getUpper() - item.length;
        System.arraycopy(item, 0, buffer.array(), newUpper, item.length);
        int slot = slotCount();
        writeLinePointer(slot, newUpper, item.length, LinePointerState.NORMAL);
        setUpper(newUpper);
        setLower(getLower() + LINE_POINTER_SIZE);
        return slot;
    }

    private void checkSlot(int slot) {
        if (isNew()) {
            throw new IllegalStateException("The page is not formatted");
        }
        if (slot < 0 || slot >= slotCount()) {
            throw new IllegalArgumentException("slot " + slot + " is out of bounds [0, " + slotCount() + ")");
        }
    }

    public byte[] get(int slot) {
        checkSlot(slot);
        LinePointer lp = readLinePointer(slot);
        if (lp.state() != LinePointerState.NORMAL) {
            throw new NoSuchElementException("slot " + slot + " is " + lp.state());
        }
        return Arrays.copyOfRange(buffer.array(), lp.offset(), lp.offset() + lp.length());
    }

    public LinePointerState stateOf(int slot) {
        checkSlot(slot);
        return readLinePointer(slot).state();
    }

    public void delete(int slot) {
        checkSlot(slot);
        LinePointer lp = readLinePointer(slot);
        if (lp.state() != LinePointerState.NORMAL) {
            throw new NoSuchElementException("slot " + slot + " is " + lp.state());
        }
        writeLinePointer(slot, lp.offset(), lp.length(), LinePointerState.DEAD);
    }

    private int reclaimableSpace() {
        int liveBytes = 0;
        for (int slot = 0; slot < slotCount(); slot++) {
            LinePointer lp = readLinePointer(slot);
            if (lp.state() == LinePointerState.NORMAL) {
                liveBytes += lp.length();
            }
        }
        return getSpecial() - getLower() - liveBytes;
    }

    public boolean canFit(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must be non-negative, got " + length);
        }
        if (length + LINE_POINTER_SIZE <= freeSpace()) {
            return true;
        }
        return length + LINE_POINTER_SIZE <= reclaimableSpace();
    }

    void compact() {
        byte[] temp = new byte[PAGE_SIZE];
        int newUpper = getSpecial();
        for (int slot = 0; slot < slotCount(); slot++) {
            LinePointer lp = readLinePointer(slot);
            if (lp.state() == LinePointerState.NORMAL) {
                newUpper -= lp.length();
                System.arraycopy(buffer.array(), lp.offset(), temp, newUpper, lp.length());
                writeLinePointer(slot, newUpper, lp.length(), LinePointerState.NORMAL);
            } else {
                writeLinePointer(slot, 0, 0, lp.state());
            }
        }
        System.arraycopy(temp, newUpper, buffer.array(), newUpper, getSpecial() - newUpper);
        setUpper(newUpper);
    }

    public void update(int slot, byte[] item) {
        requireNonNull(item, "item must not be null");
        checkSlot(slot);
        if (item.length > maxItemSize() || item.length == 0) {
            throw new IllegalArgumentException("item length " + item.length + " must be greater than 0 and at most " + maxItemSize());
        }
        LinePointer lp = readLinePointer(slot);
        if (lp.state() != LinePointerState.NORMAL) {
            throw new NoSuchElementException("can not update slot " + slot + " in state " + lp.state());
        }
        if (item.length <= lp.length()) {
            System.arraycopy(item, 0, buffer.array(), lp.offset(), item.length);
            writeLinePointer(slot, lp.offset(), item.length, LinePointerState.NORMAL);
        } else {
            int availableSpace = reclaimableSpace() + lp.length();
            if (item.length > availableSpace) {
                throw new IllegalStateException("item length " + item.length + " exceeds available space " + availableSpace);
            }
            writeLinePointer(slot, lp.offset(), lp.length(), LinePointerState.DEAD);
            if (freeSpace() < item.length) {
                compact();
            }
            int newUpper = getUpper() - item.length;
            System.arraycopy(item, 0, buffer.array(), newUpper, item.length);
            setUpper(newUpper);
            writeLinePointer(slot, newUpper, item.length, LinePointerState.NORMAL);
        }
    }

}
