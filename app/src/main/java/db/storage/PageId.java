package db.storage;

public record PageId(int fileId, int pageNo) {
    public PageId{
        if (fileId < 0) {
            throw new IllegalArgumentException("fileId must be non-negative, got " + fileId);
        }
        if (pageNo < 0) {
            throw new IllegalArgumentException("pageNo must be non-negative, got " + pageNo);
        }
    }
}