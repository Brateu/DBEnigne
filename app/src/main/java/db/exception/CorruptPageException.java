package db.exception;


public class CorruptPageException extends StorageException {
    public CorruptPageException(String message) {
        super(message);
    }
}
