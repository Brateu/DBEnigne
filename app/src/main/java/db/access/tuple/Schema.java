package db.access.tuple;

// IMPORTS you'll need: java.util.List, java.util.Map, java.util.HashMap,
//   static java.util.Objects.requireNonNull

import java.util.List;
import java.util.Map;

/*
 * Schema = the table's columns IN ORDER + a fast name -> position lookup.
 *   position: serializer walks column(0), column(1), ... and matches values[i]
 *   name:     SQL analyzer (Phase 3) turns "WHERE name = ..." into position 1
 * Built once, validated once, never changes afterwards (immutable).
 */
public final class Schema {

    // CONSTANT — MAX_COLUMNS = 1600, public static final
    //   why: tuple header stores natts in 2 bytes (max 65535); Postgres caps at 1600, so do we.
    //   public so tests/analyzer can refer to it instead of repeating the number.

    private List<Column> columnList;
    private Map<String, Integer> mapToIndex;
    // FIELDS — fix the two above:
    //   - make both `final` (set once in the constructor, never reassigned → immutable)
    //   - give the map a name, e.g. nameToIndex
    //   - "columnList" → "columns" reads nicer (columns() accessor below returns it), your call

    // CONSTRUCTOR — public Schema(List<Column> columns)
    //
    //   STEP 1 — requireNonNull on the list itself (NPE, with a message)
    //
    //   STEP 2 — empty list → IAE
    //     why: a table with 0 columns can't hold a row; natts would be 0
    //
    //   STEP 3 — size > MAX_COLUMNS → IAE, message includes the size
    //
    //   STEP 4 — one loop over i = 0 .. size-1:
    //     a) column = columns.get(i); requireNonNull(column, message with i)
    //          why: a null column would blow up later in the serializer, far from the cause
    //     b) previous = map.put(column.name(), i)
    //        if previous != null → duplicate name → IAE, message names the column
    //          why: put() returns the OLD value for that key, or null if the key was new.
    //          one loop both fills the lookup AND catches "CREATE TABLE t (id INT, id INT)"
    //     tip: do all checks into a LOCAL HashMap first, assign the field at the end
    //          → if anything throws, no half-built Schema exists (check before mutating)
    //
    //   STEP 5 — this.columns = List.copyOf(columns)
    //     why: immutable copy. if the caller later does list.add(...) on THEIR list,
    //          our schema must not change. (List.copyOf also throws NPE on null elements,
    //          but do your own check in step 4a for a clear message.)
    //   STEP 6 — this.nameToIndex = Map.copyOf(localMap)   (or keep the HashMap, it's private anyway)

    // METHOD — public int columnCount()
    //   size of the list. serializer uses it for the loop and for natts in the header.

    // METHOD — public Column column(int index)
    //   list.get(index). bad index → List.get already throws IndexOutOfBoundsException, nothing to add.

    // METHOD — public List<Column> columns()
    //   return the list directly — safe because it's the immutable copy from step 5.

    // METHOD — public int indexOf(String name)
    //   requireNonNull(name)
    //   look up in the map; missing → IAE "no column <name>"
    //     why IAE not -1: every caller would have to check -1, and forgetting it means
    //     values[-1] → ArrayIndexOutOfBounds far away. throwing here names the real problem.
    //   careful: map.get returns Integer (null when missing) — check null BEFORE unboxing to int.
    //   case-sensitive on purpose: the parser lowercases unquoted names before they get here.

    // METHOD — equals(Object o) / hashCode()   (@Override both)
    //   equal = same class AND columns.equals(other.columns). hashCode = columns.hashCode().
    //   why only the list: the map is derived from it — same list ⇒ same map automatically.
    //   why we need them: catalog round-trip tests compare "schema written" vs "schema read back".

    // METHOD — toString()
    //   e.g. "Schema" + columns → readable in test failures. Not checked by tests.
}
