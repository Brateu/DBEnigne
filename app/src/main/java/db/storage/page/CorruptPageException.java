package db.storage.page;

import db.storage.StorageException;

public class CorruptPageException extends StorageException {
    public CorruptPageException(String message) {
        super(message);
    }
}
