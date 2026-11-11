package db.storage.page;

import static db.storage.DiskManager.PAGE_SIZE;

public class HeapPage extends Page {

    public HeapPage(byte[] data) {
        super(data);
        if (!isNew() && getSpecial() != PAGE_SIZE) {
            throw new CorruptPageException("not a heap page, special = " + getSpecial() + ", expected " + PAGE_SIZE);
        }
    }

    public void init() {
        super.init(0);
    }
}
