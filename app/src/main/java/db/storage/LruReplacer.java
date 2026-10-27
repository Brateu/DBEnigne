package db.storage;

import db.util.DoublyLinkedList;
import db.util.DoublyLinkedList.Node;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

public class LruReplacer {

    private final int capacity;
    private final DoublyLinkedList<Integer> list;
    private final Map<Integer, Node<Integer>> nodes;

    LruReplacer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        this.capacity = capacity;
        list = new DoublyLinkedList<>();
        nodes = new HashMap<>();
    }

    void pin(int frameId) {
        checkFrameId(frameId);
        Node<Integer> node = nodes.remove(frameId);
        if (node != null) {
            list.remove(node);
        }

    }

    void unpin(int frameId) {
        checkFrameId(frameId);
        if (!nodes.containsKey(frameId)) {
            nodes.put(frameId, list.addLast(frameId));
        }
    }

    OptionalInt victim() {
        if (list.isEmpty()) {
            return OptionalInt.empty();
        }
        int frameId = list.removeFirst();
        nodes.remove(frameId);
        return OptionalInt.of(frameId);
    }

    int size() {
        return list.size();
    }

    private void checkFrameId(int frameId) {
        if (frameId < 0 || frameId >= capacity) {
            throw new IllegalArgumentException("frameId " + frameId + " is out of bounds, must be in [0, " + (capacity - 1) + "]");
        }
    }

}
