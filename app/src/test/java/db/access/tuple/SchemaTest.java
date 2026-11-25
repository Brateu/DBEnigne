package db.access.tuple;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SchemaTest {

    private static final Column ID = Column.of("id", Type.INT, false);
    private static final Column NAME = Column.varchar("name", 20, true);
    private static final Column ACTIVE = Column.of("active", Type.BOOLEAN, true);

    private static Schema users() {
        return new Schema(List.of(ID, NAME, ACTIVE));
    }

    private static List<Column> manyColumns(int count) {
        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            columns.add(Column.of("c" + i, Type.INT, true));
        }
        return columns;
    }

    // ---- by position ----

    @Test
    void keepsColumnsInOrder() {
        Schema schema = users();

        assertEquals(3, schema.columnCount());
        assertEquals(ID, schema.column(0));
        assertEquals(NAME, schema.column(1));
        assertEquals(ACTIVE, schema.column(2));
        assertEquals(List.of(ID, NAME, ACTIVE), schema.columns());
    }

    @Test
    void singleColumnSchemaIsAllowed() {
        Schema schema = new Schema(List.of(ID));

        assertEquals(1, schema.columnCount());
        assertEquals(ID, schema.column(0));
    }

    @Test
    void columnRejectsOutOfRangeIndex() {
        Schema schema = users();

        assertThrows(IndexOutOfBoundsException.class, () -> schema.column(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> schema.column(3));
    }

    // ---- by name ----

    @Test
    void indexOfFindsEveryColumn() {
        Schema schema = users();

        assertEquals(0, schema.indexOf("id"));
        assertEquals(1, schema.indexOf("name"));
        assertEquals(2, schema.indexOf("active"));
    }

    @Test
    void indexOfAgreesWithColumnForWideSchema() {
        Schema schema = new Schema(manyColumns(200));

        for (int i = 0; i < 200; i++) {
            assertEquals(i, schema.indexOf(schema.column(i).name()));
        }
    }

    @Test
    void indexOfUnknownNameThrowsWithName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> users().indexOf("email"));
        assertTrue(e.getMessage().contains("email"), "message should name the column: " + e.getMessage());
    }

    @Test
    void indexOfIsCaseSensitive() {
        // the parser lowercases unquoted identifiers before they reach Schema
        assertThrows(IllegalArgumentException.class, () -> users().indexOf("ID"));
    }

    @Test
    void indexOfNullThrowsNpe() {
        assertThrows(NullPointerException.class, () -> users().indexOf(null));
    }

    // ---- validation ----

    @Test
    void nullListThrowsNpe() {
        assertThrows(NullPointerException.class, () -> new Schema(null));
    }

    @Test
    void nullColumnThrowsNpe() {
        List<Column> withNull = Arrays.asList(ID, null, ACTIVE);   // List.of can't hold null

        assertThrows(NullPointerException.class, () -> new Schema(withNull));
    }

    @Test
    void emptyListIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Schema(List.of()));
    }

    @Test
    void duplicateNameIsRejectedWithName() {
        List<Column> columns = List.of(ID, NAME, Column.of("id", Type.BIGINT, true));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new Schema(columns));
        assertTrue(e.getMessage().contains("id"), "message should name the duplicate: " + e.getMessage());
    }

    @Test
    void namesDifferingOnlyInCaseAreDistinct() {
        Schema schema = new Schema(List.of(Column.of("a", Type.INT, true), Column.of("A", Type.INT, true)));

        assertEquals(0, schema.indexOf("a"));
        assertEquals(1, schema.indexOf("A"));
    }

    @Test
    void maxColumnsIsAllowed() {
        assertEquals(Schema.MAX_COLUMNS, new Schema(manyColumns(Schema.MAX_COLUMNS)).columnCount());
    }

    @Test
    void moreThanMaxColumnsIsRejectedWithSize() {
        int tooMany = Schema.MAX_COLUMNS + 1;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Schema(manyColumns(tooMany)));
        assertTrue(e.getMessage().contains(String.valueOf(tooMany)), "message should include the size: " + e.getMessage());
    }

    @Test
    void maxColumnsFitsInTwoByteNatts() {
        assertTrue(Schema.MAX_COLUMNS > 0 && Schema.MAX_COLUMNS <= 0xFFFF);
    }

    // ---- immutability ----

    @Test
    void laterChangesToCallerListDoNotAffectSchema() {
        List<Column> columns = new ArrayList<>(List.of(ID, NAME));
        Schema schema = new Schema(columns);

        columns.add(ACTIVE);
        columns.set(0, Column.of("other", Type.INT, true));

        assertEquals(2, schema.columnCount());
        assertEquals(ID, schema.column(0));
        assertThrows(IllegalArgumentException.class, () -> schema.indexOf("active"));
    }

    @Test
    void columnsListCannotBeModified() {
        Schema schema = users();

        assertThrows(UnsupportedOperationException.class, () -> schema.columns().add(ID));
        assertThrows(UnsupportedOperationException.class, () -> schema.columns().remove(0));
    }

    // ---- equality ----

    @Test
    void schemasWithSameColumnsAreEqual() {
        Schema a = users();
        Schema b = new Schema(new ArrayList<>(List.of(ID, NAME, ACTIVE)));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void schemasDifferingInColumnsOrOrderAreNotEqual() {
        Schema base = users();

        assertNotEquals(base, new Schema(List.of(ID, NAME)));
        assertNotEquals(base, new Schema(List.of(NAME, ID, ACTIVE)));
        assertNotEquals(base, new Schema(List.of(ID, Column.varchar("name", 21, true), ACTIVE)));
        assertNotEquals(base, null);
        assertNotEquals(base, "not a schema");
    }
}
