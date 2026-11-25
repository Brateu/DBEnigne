package db.access.tuple;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TypeTest {

    // ---- codes ----

    @Test
    void everyTypeRoundTripsThroughItsCode() {
        for (Type type : Type.values()) {
            assertSame(type, Type.fromCode(type.code()), type + " did not round-trip");
        }
    }

    @Test
    void codesAreUnique() {
        Set<Integer> seen = new HashSet<>();
        for (Type type : Type.values()) {
            assertTrue(seen.add(type.code()), "duplicate code " + type.code() + " on " + type);
        }
    }

    @Test
    void noTypeUsesCodeZero() {
        // zeroed bytes on disk must never decode as a valid type
        for (Type type : Type.values()) {
            assertNotEquals(0, type.code(), type + " uses code 0");
        }
        assertThrows(IllegalArgumentException.class, () -> Type.fromCode(0));
    }

    @Test
    void codesArePinned() {
        // codes are persisted by the catalog: changing one breaks every stored table definition
        assertEquals(1, Type.INT.code());
        assertEquals(2, Type.BIGINT.code());
        assertEquals(3, Type.BOOLEAN.code());
        assertEquals(4, Type.VARCHAR.code());
    }

    @Test
    void fromCodeRejectsUnknownCodes() {
        for (int bad : new int[]{-1, 5, 99, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Type.fromCode(bad));
            assertTrue(e.getMessage().contains(String.valueOf(bad)), "message should name the code: " + e.getMessage());
        }
    }

    // ---- sizes ----

    @Test
    void fixedLengthTypesReportTheirSize() {
        assertEquals(Integer.BYTES, Type.INT.fixedSize());
        assertEquals(Long.BYTES, Type.BIGINT.fixedSize());
        assertEquals(1, Type.BOOLEAN.fixedSize());
    }

    @Test
    void onlyVarcharIsVariableLength() {
        assertTrue(Type.INT.isFixedLength());
        assertTrue(Type.BIGINT.isFixedLength());
        assertTrue(Type.BOOLEAN.isFixedLength());
        assertFalse(Type.VARCHAR.isFixedLength());
    }

    @Test
    void varcharFixedSizeThrows() {
        IllegalStateException e = assertThrows(IllegalStateException.class, Type.VARCHAR::fixedSize);
        assertTrue(e.getMessage().contains("VARCHAR"), "message should name the type: " + e.getMessage());
    }

    @Test
    void everyFixedLengthTypeHasPositiveSize() {
        for (Type type : Type.values()) {
            if (type.isFixedLength()) {
                assertTrue(type.fixedSize() > 0, type + " has size " + type.fixedSize());
            }
        }
    }

    // ---- java classes ----

    @Test
    void javaClassesMatchValueRepresentation() {
        assertEquals(Integer.class, Type.INT.getJavaClass());
        assertEquals(Long.class, Type.BIGINT.getJavaClass());
        assertEquals(Boolean.class, Type.BOOLEAN.getJavaClass());
        assertEquals(String.class, Type.VARCHAR.getJavaClass());
    }

    @Test
    void javaClassAcceptsOnlyMatchingValues() {
        // this is how the serializer will check values against a column's type
        assertTrue(Type.INT.getJavaClass().isInstance(42));
        assertFalse(Type.INT.getJavaClass().isInstance(42L));
        assertFalse(Type.BIGINT.getJavaClass().isInstance(42));
        assertFalse(Type.VARCHAR.getJavaClass().isInstance(42));
        assertFalse(Type.BOOLEAN.getJavaClass().isInstance(null));
    }
}
