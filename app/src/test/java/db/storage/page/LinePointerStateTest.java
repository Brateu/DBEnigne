package db.storage.page;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LinePointerStateTest {

    @Test
    void codesMatchOnDiskFormat() {
        assertEquals(0, LinePointerState.UNUSED.getCode());
        assertEquals(1, LinePointerState.NORMAL.getCode());
        assertEquals(2, LinePointerState.REDIRECT.getCode());
        assertEquals(3, LinePointerState.DEAD.getCode());
    }

    @Test
    void fromCodeIsInverseOfGetCode() {
        for (LinePointerState state : LinePointerState.values()) {
            assertSame(state, LinePointerState.fromCode(state.getCode()));
        }
    }

    @Test
    void fromCodeRejectsUnknownCodes() {
        assertThrows(IllegalArgumentException.class, () -> LinePointerState.fromCode(4));
        assertThrows(IllegalArgumentException.class, () -> LinePointerState.fromCode(-1));
    }
}
