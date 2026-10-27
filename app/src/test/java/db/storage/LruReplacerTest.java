package db.storage;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class LruReplacerTest {

    /** Calls victim() until empty and returns the frame ids in the order they came out. */
    private static List<Integer> drainVictims(LruReplacer replacer) {
        List<Integer> out = new ArrayList<>();
        OptionalInt v;
        while ((v = replacer.victim()).isPresent()) {
            out.add(v.getAsInt());
        }
        return out;
    }

    // ---- construction ----

    @Test
    void newReplacerHasNoCandidates() {
        LruReplacer replacer = new LruReplacer(4);

        assertEquals(0, replacer.size());
        assertTrue(replacer.victim().isEmpty());
    }

    @Test
    void nonPositiveCapacityThrows() {
        assertThrows(IllegalArgumentException.class, () -> new LruReplacer(0));
        assertThrows(IllegalArgumentException.class, () -> new LruReplacer(-3));
    }

    // ---- basic LRU order ----

    @Test
    void victimsComeOutOldestFirst() {
        LruReplacer replacer = new LruReplacer(4);
        replacer.markEvictable(2);
        replacer.markEvictable(0);
        replacer.markEvictable(3);

        assertEquals(3, replacer.size());
        assertEquals(List.of(2, 0, 3), drainVictims(replacer));
        assertEquals(0, replacer.size());
    }

    @Test
    void nonEvictableThenEvictableMovesFrameToNewest() {
        // The walkthrough from our discussion: 0 is used again, so 1 becomes the oldest.
        LruReplacer replacer = new LruReplacer(3);
        replacer.markEvictable(0);
        replacer.markEvictable(1);
        replacer.markEvictable(2);

        replacer.markNonEvictable(0);
        replacer.markEvictable(0);

        assertEquals(List.of(1, 2, 0), drainVictims(replacer));
    }

    // ---- markNonEvictable ----

    @Test
    void nonEvictableFrameIsNeverAVictim() {
        LruReplacer replacer = new LruReplacer(3);
        replacer.markEvictable(0);
        replacer.markEvictable(1);
        replacer.markEvictable(2);

        replacer.markNonEvictable(1);

        assertEquals(2, replacer.size());
        assertEquals(List.of(0, 2), drainVictims(replacer));
    }

    @Test
    void markNonEvictableOfNonCandidateDoesNothing() {
        LruReplacer replacer = new LruReplacer(3);
        replacer.markEvictable(0);

        replacer.markNonEvictable(2);   // never unpinned
        replacer.markNonEvictable(0);
        replacer.markNonEvictable(0);   // second pin: already not a candidate

        assertEquals(0, replacer.size());
        assertTrue(replacer.victim().isEmpty());
    }

    // ---- markEvictable ----

    @Test
    void markEvictableTwiceDoesNotDuplicateOrReorder() {
        LruReplacer replacer = new LruReplacer(3);
        replacer.markEvictable(0);
        replacer.markEvictable(1);

        replacer.markEvictable(0);   // already a candidate: must not move to newest or be added twice

        assertEquals(2, replacer.size());
        assertEquals(List.of(0, 1), drainVictims(replacer));
    }

    // ---- victim ----

    @Test
    void victimRemovesFrameFromCandidates() {
        LruReplacer replacer = new LruReplacer(3);
        replacer.markEvictable(0);
        replacer.markEvictable(1);

        assertEquals(0, replacer.victim().getAsInt());

        assertEquals(1, replacer.size());
        replacer.markNonEvictable(0);   // 0 is gone already; must not throw or touch frame 1
        assertEquals(List.of(1), drainVictims(replacer));
    }

    @Test
    void replacerIsReusableAfterBeingEmptied() {
        LruReplacer replacer = new LruReplacer(2);
        replacer.markEvictable(0);
        replacer.victim();
        assertTrue(replacer.victim().isEmpty());

        replacer.markEvictable(1);
        replacer.markEvictable(0);

        assertEquals(List.of(1, 0), drainVictims(replacer));
    }

    @Test
    void victimFrameCanBecomeCandidateAgain() {
        // After eviction the pool reuses the frame for a new page; when that is unpinned it comes back.
        LruReplacer replacer = new LruReplacer(2);
        replacer.markEvictable(0);
        replacer.markEvictable(1);
        assertEquals(0, replacer.victim().getAsInt());

        replacer.markEvictable(0);

        assertEquals(List.of(1, 0), drainVictims(replacer));
    }

    // ---- frame id range ----

    @Test
    void outOfRangeFrameIdsThrow() {
        LruReplacer replacer = new LruReplacer(4);

        assertThrows(IllegalArgumentException.class, () -> replacer.markEvictable(-1));
        assertThrows(IllegalArgumentException.class, () -> replacer.markEvictable(4));
        assertThrows(IllegalArgumentException.class, () -> replacer.markNonEvictable(-1));
        assertThrows(IllegalArgumentException.class, () -> replacer.markNonEvictable(4));
        assertEquals(0, replacer.size());
    }

    @Test
    void boundaryFrameIdsAreAccepted() {
        LruReplacer replacer = new LruReplacer(4);

        replacer.markEvictable(0);
        replacer.markEvictable(3);

        assertEquals(List.of(0, 3), drainVictims(replacer));
    }

    // ---- randomized check against a simple reference model ----

    @Test
    void randomOperationsMatchReferenceModel() {
        // The model is a plain ArrayDeque: obviously correct, just O(n). Both must agree after every step.
        int capacity = 16;
        LruReplacer replacer = new LruReplacer(capacity);
        Deque<Integer> model = new ArrayDeque<>();
        Random random = new Random(42);   // fixed seed: same sequence every run, so failures are reproducible

        for (int step = 0; step < 10_000; step++) {
            int frameId = random.nextInt(capacity);
            int op = random.nextInt(3);

            if (op == 0) {
                replacer.markEvictable(frameId);
                if (!model.contains(frameId)) {
                    model.addLast(frameId);
                }
            } else if (op == 1) {
                replacer.markNonEvictable(frameId);
                model.remove(frameId);   // Deque.remove(Object): removes the value, not an index
            } else {
                OptionalInt victim = replacer.victim();
                Integer expected = model.pollFirst();
                if (expected == null) {
                    assertTrue(victim.isEmpty(), "step " + step);
                } else {
                    assertEquals(expected, victim.getAsInt(), "step " + step);
                }
            }

            assertEquals(model.size(), replacer.size(), "step " + step);
        }
    }
}
