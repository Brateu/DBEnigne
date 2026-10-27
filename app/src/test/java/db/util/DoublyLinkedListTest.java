package db.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

class DoublyLinkedListTest {

    /** Empties the list front to back, so tests can check the full order in one assert. */
    private static <T> List<T> drain(DoublyLinkedList<T> list) {
        List<T> out = new ArrayList<>();
        while (!list.isEmpty()) {
            out.add(list.removeFirst());
        }
        return out;
    }

    // ---- empty list ----

    @Test
    void newListIsEmpty() {
        DoublyLinkedList<Integer> list = new DoublyLinkedList<>();

        assertTrue(list.isEmpty());
        assertEquals(0, list.size());
    }

    @Test
    void removeFirstOnEmptyListThrows() {
        DoublyLinkedList<Integer> list = new DoublyLinkedList<>();

        assertThrows(NoSuchElementException.class, list::removeFirst);
        assertEquals(0, list.size());
    }

    // ---- adding ----

    @Test
    void addLastKeepsInsertionOrder() {
        DoublyLinkedList<Integer> list = new DoublyLinkedList<>();
        list.addLast(1);
        list.addLast(2);
        list.addLast(3);

        assertEquals(3, list.size());
        assertEquals(List.of(1, 2, 3), drain(list));
    }

    @Test
    void addFirstPutsElementAtFront() {
        DoublyLinkedList<Integer> list = new DoublyLinkedList<>();
        list.addLast(2);
        list.addFirst(1);
        list.addLast(3);

        assertEquals(List.of(1, 2, 3), drain(list));
    }

    // ---- removeFirst ----

    @Test
    void removeFirstReturnsTheRemovedValue() {
        DoublyLinkedList<String> list = new DoublyLinkedList<>();
        list.addLast("A");
        list.addLast("B");
        list.addLast("C");

        assertEquals("A", list.removeFirst());
        assertEquals(2, list.size());
        assertEquals("B", list.removeFirst());
        assertEquals("C", list.removeFirst());
        assertTrue(list.isEmpty());
    }

    @Test
    void removeFirstOnSingleElementEmptiesList() {
        DoublyLinkedList<String> list = new DoublyLinkedList<>();
        list.addLast("A");

        assertEquals("A", list.removeFirst());
        assertTrue(list.isEmpty());
        assertEquals(0, list.size());

        // head and tail must both be reset, so the list is usable again
        list.addLast("B");
        assertEquals(List.of("B"), drain(list));
    }

    // ---- remove(node) at every position ----

    @Test
    void removeHeadNode() {
        DoublyLinkedList<String> list = new DoublyLinkedList<>();
        DoublyLinkedList.Node<String> a = list.addLast("A");
        list.addLast("B");
        list.addLast("C");

        list.remove(a);

        assertEquals(2, list.size());
        assertEquals(List.of("B", "C"), drain(list));
    }

    @Test
    void removeMiddleNode() {
        DoublyLinkedList<String> list = new DoublyLinkedList<>();
        list.addLast("A");
        DoublyLinkedList.Node<String> b = list.addLast("B");
        list.addLast("C");

        list.remove(b);

        assertEquals(2, list.size());
        assertEquals(List.of("A", "C"), drain(list));
    }

    @Test
    void removeTailNodeThenAddLastStillWorks() {
        DoublyLinkedList<String> list = new DoublyLinkedList<>();
        list.addLast("A");
        list.addLast("B");
        DoublyLinkedList.Node<String> c = list.addLast("C");

        list.remove(c);
        list.addLast("D");   // fails if tail was not moved back to B

        assertEquals(List.of("A", "B", "D"), drain(list));
    }

    @Test
    void removeOnlyNodeEmptiesList() {
        DoublyLinkedList<String> list = new DoublyLinkedList<>();
        DoublyLinkedList.Node<String> a = list.addLast("A");

        list.remove(a);

        assertTrue(list.isEmpty());
        assertEquals(0, list.size());
        list.addFirst("B");
        assertEquals(List.of("B"), drain(list));
    }

    @Test
    void removeDetachesNode() {
        DoublyLinkedList<String> list = new DoublyLinkedList<>();
        list.addLast("A");
        DoublyLinkedList.Node<String> b = list.addLast("B");
        list.addLast("C");

        list.remove(b);

        assertNull(b.prev);
        assertNull(b.next);
    }

    // ---- the guard: removing a node that is not in the list ----

    @Test
    void removingSameNodeTwiceThrowsAndLeavesListIntact() {
        DoublyLinkedList<String> list = new DoublyLinkedList<>();
        DoublyLinkedList.Node<String> a = list.addLast("A");
        list.addLast("B");
        list.addLast("C");
        list.remove(a);

        assertThrows(IllegalArgumentException.class, () -> list.remove(a));
        assertEquals(2, list.size());
        assertEquals(List.of("B", "C"), drain(list));
    }

    @Test
    void removingOnlyNodeTwiceThrows() {
        DoublyLinkedList<String> list = new DoublyLinkedList<>();
        DoublyLinkedList.Node<String> a = list.addLast("A");
        list.remove(a);

        assertThrows(IllegalArgumentException.class, () -> list.remove(a));
        assertEquals(0, list.size());
    }

    // ---- the pattern LRU will use ----

    @Test
    void lruStyleMoveToBack() {
        // "touch" B: remove it from the middle and re-add it as newest
        DoublyLinkedList<String> list = new DoublyLinkedList<>();
        list.addLast("A");
        DoublyLinkedList.Node<String> b = list.addLast("B");
        list.addLast("C");

        list.remove(b);
        list.addLast("B");

        assertEquals(List.of("A", "C", "B"), drain(list));
    }
}
