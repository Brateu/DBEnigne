package db.storage;

import java.util.OptionalInt;

public interface Replacer {
    public void markNonEvictable(int frameId);
    public void markEvictable(int frameId);
    public OptionalInt victim();
    public int size();
}