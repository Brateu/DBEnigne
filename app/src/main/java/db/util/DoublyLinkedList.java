package db.util;


import java.util.NoSuchElementException;
import java.util.Objects;

public class DoublyLinkedList<T> {

    public static class Node<T> {
        private final T data;
        Node<T> next;
        Node<T> prev;

        Node(T data) {
            this.data = data;
        }

        public T getData() {
            return this.data;
        }
    }

    private Node<T> head;
    private Node<T> tail;
    private int size;

    public DoublyLinkedList() {
    }


    public boolean isEmpty() {
        return head == null;
    }

    public int size() {
        return size;
    }

    public Node<T> addFirst(T data) {
        Node<T> newNode = new Node<>(data);
        newNode.next = head;
        newNode.prev = null;

        if (head != null) {
            head.prev = newNode;
        }
        head = newNode;

        if (tail == null) {
            tail = newNode;
        }
        size++;
        return newNode;
    }

    public Node<T> addLast(T data) {
        Node<T> newNode = new Node<>(data);
        newNode.next = null;
        newNode.prev = tail;

        if (tail != null) {
            tail.next = newNode;
        }
        tail = newNode;

        if (head == null) {
            head = newNode;
        }
        size++;
        return newNode;
    }

    public T removeFirst() {
        if (head == null) {
            throw new NoSuchElementException("list is empty");
        }
        Node<T> node = head;
        remove(head);
        return node.data;
    }

    public void remove(Node<T> node) {
        Objects.requireNonNull(node, "node must not be null");
        if (node.prev == null && node != head) {
            throw new IllegalArgumentException("node is not in the list");
        }

        if (node.prev != null) {
            node.prev.next = node.next;
        } else {
            head = node.next;
        }
        if (node.next != null) {
            node.next.prev = node.prev;
        } else {
            tail = node.prev;
        }
        node.next = node.prev = null;
        size--;
    }

    public Node<T> peekFirst() {
        if (head == null) {
            throw new NoSuchElementException("list is empty");
        }
        return head;
    }

    public Node<T> peekLast() {
        if (tail == null) {
            throw new NoSuchElementException("list is empty");
        }
        return tail;
    }
}
