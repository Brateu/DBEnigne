package db.storage;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PageIdTest {

    @Test
    void acceptsZeroAndPositiveValues() {
        PageId id = new PageId(0, 0);

        assertEquals(0, id.fileId());
        assertEquals(0, id.pageNo());
    }

    @Test
    void rejectsNegativeFileId() {
        assertThrows(IllegalArgumentException.class, () -> new PageId(-1, 0));
    }

    @Test
    void rejectsNegativePageNo() {
        assertThrows(IllegalArgumentException.class, () -> new PageId(0, -1));
    }

    @Test
    void equalValuesAreEqualAndHashTheSame() {
        // The buffer pool's map relies on this: a PageId built later must find the entry stored earlier.
        PageId a = new PageId(2, 5);
        PageId b = new PageId(2, 5);

        assertNotSame(a, b);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void samePageNoInDifferentFilesIsDifferent() {
        assertNotEquals(new PageId(1, 0), new PageId(2, 0));
        assertNotEquals(new PageId(1, 0), new PageId(0, 1));
    }

    @Test
    void worksAsHashMapKey() {
        Map<PageId, Integer> map = new HashMap<>();
        map.put(new PageId(1, 3), 42);

        assertEquals(42, map.get(new PageId(1, 3)));
        assertNull(map.get(new PageId(3, 1)));
    }
}
