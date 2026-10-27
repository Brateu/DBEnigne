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
        replacer.unpin(2);
        replacer.unpin(0);
        replacer.unpin(3);

        assertEquals(3, replacer.size());
        assertEquals(List.of(2, 0, 3), drainVictims(replacer));
        assertEquals(0, replacer.size());
    }

    @Test
    void pinThenUnpinMovesFrameToNewest() {
        // The walkthrough from our discussion: 0 is used again, so 1 becomes the oldest.
        LruReplacer replacer = new LruReplacer(3);
        replacer.unpin(0);
        replacer.unpin(1);
        replacer.unpin(2);

        replacer.pin(0);
        replacer.unpin(0);

        assertEquals(List.of(1, 2, 0), drainVictims(replacer));
    }

    // ---- pin ----

    @Test
    void pinnedFrameIsNeverAVictim() {
        LruReplacer replacer = new LruReplacer(3);
        replacer.unpin(0);
        replacer.unpin(1);
        replacer.unpin(2);

        replacer.pin(1);

        assertEquals(2, replacer.size());
        assertEquals(List.of(0, 2), drainVictims(replacer));
    }

    @Test
    void pinOfNonCandidateDoesNothing() {
        LruReplacer replacer = new LruReplacer(3);
        replacer.unpin(0);

        replacer.pin(2);   // never unpinned
        replacer.pin(0);
        replacer.pin(0);   // second pin: already not a candidate

        assertEquals(0, replacer.size());
        assertTrue(replacer.victim().isEmpty());
    }

    // ---- unpin ----

    @Test
    void unpinTwiceDoesNotDuplicateOrReorder() {
        LruReplacer replacer = new LruReplacer(3);
        replacer.unpin(0);
        replacer.unpin(1);

        replacer.unpin(0);   // already a candidate: must not move to newest or be added twice

        assertEquals(2, replacer.size());
        assertEquals(List.of(0, 1), drainVictims(replacer));
    }

    // ---- victim ----

    @Test
    void victimRemovesFrameFromCandidates() {
        LruReplacer replacer = new LruReplacer(3);
        replacer.unpin(0);
        replacer.unpin(1);

        assertEquals(0, replacer.victim().getAsInt());

        assertEquals(1, replacer.size());
        replacer.pin(0);   // 0 is gone already; must not throw or touch frame 1
        assertEquals(List.of(1), drainVictims(replacer));
    }

    @Test
    void replacerIsReusableAfterBeingEmptied() {
        LruReplacer replacer = new LruReplacer(2);
        replacer.unpin(0);
        replacer.victim();
        assertTrue(replacer.victim().isEmpty());

        replacer.unpin(1);
        replacer.unpin(0);

        assertEquals(List.of(1, 0), drainVictims(replacer));
    }

    @Test
    void victimFrameCanBecomeCandidateAgain() {
        // After eviction the pool reuses the frame for a new page; when that is unpinned it comes back.
        LruReplacer replacer = new LruReplacer(2);
        replacer.unpin(0);
        replacer.unpin(1);
        assertEquals(0, replacer.victim().getAsInt());

        replacer.unpin(0);

        assertEquals(List.of(1, 0), drainVictims(replacer));
    }

    // ---- frame id range ----

    @Test
    void outOfRangeFrameIdsThrow() {
        LruReplacer replacer = new LruReplacer(4);

        assertThrows(IllegalArgumentException.class, () -> replacer.unpin(-1));
        assertThrows(IllegalArgumentException.class, () -> replacer.unpin(4));
        assertThrows(IllegalArgumentException.class, () -> replacer.pin(-1));
        assertThrows(IllegalArgumentException.class, () -> replacer.pin(4));
        assertEquals(0, replacer.size());
    }

    @Test
    void boundaryFrameIdsAreAccepted() {
        LruReplacer replacer = new LruReplacer(4);

        replacer.unpin(0);
        replacer.unpin(3);

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
                replacer.unpin(frameId);
                if (!model.contains(frameId)) {
                    model.addLast(frameId);
                }
            } else if (op == 1) {
                replacer.pin(frameId);
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
