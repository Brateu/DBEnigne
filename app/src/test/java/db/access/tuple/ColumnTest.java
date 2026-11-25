package db.access.tuple;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ColumnTest {

    // ---- valid columns ----

    @Test
    void fixedLengthColumnsHaveZeroMaxLength() {
        for (Type type : new Type[]{Type.INT, Type.BIGINT, Type.BOOLEAN}) {
            Column c = new Column("c", type, true, 0);
            assertEquals("c", c.name());
            assertEquals(type, c.type());
            assertTrue(c.nullable());
            assertEquals(0, c.maxLength());
        }
    }

    @Test
    void varcharColumnKeepsItsMaxLength() {
        Column c = new Column("name", Type.VARCHAR, false, 20);

        assertEquals(Type.VARCHAR, c.type());
        assertFalse(c.nullable());
        assertEquals(20, c.maxLength());
    }

    @Test
    void varcharAcceptsBoundaryLengths() {
        assertEquals(1, new Column("c", Type.VARCHAR, true, 1).maxLength());
        assertEquals(Column.MAX_VARCHAR_LENGTH,
                new Column("c", Type.VARCHAR, true, Column.MAX_VARCHAR_LENGTH).maxLength());
    }

    @Test
    void maxVarcharLengthFitsTwoByteLengthPrefixInWorstCaseUtf8() {
        // the length prefix stores bytes, not characters; one character is at most 4 UTF-8 bytes
        assertTrue(Column.MAX_VARCHAR_LENGTH * 4 <= 0xFFFF);
    }

    // ---- factories ----

    @Test
    void ofBuildsFixedLengthColumn() {
        assertEquals(new Column("id", Type.INT, false, 0), Column.of("id", Type.INT, false));
        assertEquals(new Column("big", Type.BIGINT, true, 0), Column.of("big", Type.BIGINT, true));
    }

    @Test
    void varcharFactoryBuildsVarcharColumn() {
        assertEquals(new Column("name", Type.VARCHAR, true, 20), Column.varchar("name", 20, true));
    }

    @Test
    void ofWithVarcharIsRejected() {
        // VARCHAR needs a length, so it must go through varchar(...)
        assertThrows(IllegalArgumentException.class, () -> Column.of("name", Type.VARCHAR, true));
    }

    @Test
    void recordEqualityUsesAllFields() {
        Column base = Column.varchar("name", 20, true);

        assertEquals(base, Column.varchar("name", 20, true));
        assertEquals(base.hashCode(), Column.varchar("name", 20, true).hashCode());
        assertNotEquals(base, Column.varchar("other", 20, true));
        assertNotEquals(base, Column.varchar("name", 21, true));
        assertNotEquals(base, Column.varchar("name", 20, false));
    }

    // ---- invalid name / type ----

    @Test
    void nullNameOrTypeThrowsNpe() {
        assertThrows(NullPointerException.class, () -> new Column(null, Type.INT, true, 0));
        assertThrows(NullPointerException.class, () -> new Column("c", null, true, 0));
    }

    @Test
    void blankNameIsRejected() {
        for (String bad : new String[]{"", " ", "   ", "\t"}) {
            assertThrows(IllegalArgumentException.class, () -> new Column(bad, Type.INT, true, 0),
                    "name '" + bad + "' should be rejected");
        }
    }

    // ---- invalid maxLength ----

    @Test
    void varcharRejectsNonPositiveLength() {
        for (int bad : new int[]{0, -1, Integer.MIN_VALUE}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new Column("name", Type.VARCHAR, true, bad));
            assertMessageNames(e, "name", bad);
        }
    }

    @Test
    void varcharRejectsLengthAboveMax() {
        for (int bad : new int[]{Column.MAX_VARCHAR_LENGTH + 1, 65535, Integer.MAX_VALUE}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new Column("name", Type.VARCHAR, true, bad));
            assertMessageNames(e, "name", bad);
        }
    }

    @Test
    void fixedLengthTypesRejectNonZeroMaxLength() {
        for (Type type : new Type[]{Type.INT, Type.BIGINT, Type.BOOLEAN}) {
            for (int bad : new int[]{1, 20, -1}) {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                        () -> new Column("col", type, true, bad), type + "(" + bad + ") should be rejected");
                assertMessageNames(e, "col", bad);
            }
        }
    }

    private static void assertMessageNames(Exception e, String column, int value) {
        String msg = e.getMessage();
        assertNotNull(msg, "exception should have a message");
        assertTrue(msg.contains(column), "message should name the column: " + msg);
        assertTrue(msg.contains(String.valueOf(value)), "message should include the bad value: " + msg);
    }
}
