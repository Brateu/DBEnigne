package db.storage.page;

import db.exception.CorruptPageException;

import static db.storage.page.Page.PAGE_SIZE;

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
