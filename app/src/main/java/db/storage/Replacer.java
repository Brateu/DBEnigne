package db.storage;

import java.util.OptionalInt;

public interface Replacer {
    void markNonEvictable(int frameId);
    void markEvictable(int frameId);
    OptionalInt victim();
}