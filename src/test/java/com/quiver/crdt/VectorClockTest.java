package com.quiver.crdt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quiver.crdt.VectorClock.Ordering;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VectorClockTest {

    @Test
    void newClocksAreEqual() {
        assertEquals(Ordering.EQUAL, VectorClock.EMPTY.compare(VectorClock.EMPTY));
    }

    @Test
    void incrementedClockIsAfterOriginal() {
        VectorClock a = VectorClock.EMPTY;
        VectorClock b = a.incremented("A");

        assertEquals(Ordering.BEFORE, a.compare(b));
        assertEquals(Ordering.AFTER, b.compare(a));
    }

    @Test
    void independentIncrementsAreConcurrent() {
        VectorClock a = VectorClock.EMPTY.incremented("A");
        VectorClock b = VectorClock.EMPTY.incremented("B");

        assertEquals(Ordering.CONCURRENT, a.compare(b));
        assertEquals(Ordering.CONCURRENT, b.compare(a));
    }

    @Test
    void sameIncrementsAreEqual() {
        assertEquals(Ordering.EQUAL,
                VectorClock.EMPTY.incremented("A").compare(VectorClock.EMPTY.incremented("A")));
    }

    @Test
    void incrementDoesNotMutateTheReceiver() {
        VectorClock original = VectorClock.EMPTY.incremented("A");

        VectorClock derived = original.incremented("A");

        assertEquals(1, original.counterFor("A"));
        assertEquals(2, derived.counterFor("A"));
    }

    @Test
    void joinTakesPointwiseMaximum() {
        VectorClock a = VectorClock.of(Map.of("A", 3, "B", 1));
        VectorClock b = VectorClock.of(Map.of("B", 5, "C", 2));

        VectorClock joined = a.join(b);

        assertEquals(3, joined.counterFor("A"));
        assertEquals(5, joined.counterFor("B"));
        assertEquals(2, joined.counterFor("C"));
    }

    @Test
    void joinIsIdempotentAndCommutative() {
        VectorClock a = VectorClock.of(Map.of("A", 2));
        VectorClock b = VectorClock.of(Map.of("B", 7));

        assertEquals(a.join(b), b.join(a));
        assertEquals(a.join(b), a.join(b).join(a));
    }

    @Test
    void asMapReturnsACopy() {
        VectorClock clock = VectorClock.of(Map.of("A", 1));

        clock.asMap().put("A", 99);

        assertEquals(1, clock.counterFor("A"));
    }

    /** The whole conflict-resolution argument rests on this property. */
    @Test
    void totalOrderAgreesWithCausality() {
        VectorClock earlier = VectorClock.of(Map.of("A", 1));
        VectorClock later = VectorClock.of(Map.of("A", 1, "B", 1));

        assertEquals(Ordering.BEFORE, earlier.compare(later));
        assertTrue(VectorClock.TOTAL_ORDER.compare(earlier, later) < 0);
    }

    @Test
    void totalOrderSeparatesConcurrentClocksDeterministically() {
        VectorClock x = VectorClock.of(Map.of("X", 1));
        VectorClock y = VectorClock.of(Map.of("Y", 1));

        assertEquals(Ordering.CONCURRENT, x.compare(y));
        assertTrue(VectorClock.TOTAL_ORDER.compare(x, y) < 0);
        assertTrue(VectorClock.TOTAL_ORDER.compare(y, x) > 0);
    }

    @Test
    void zeroCountersAreNormalisedAway() {
        assertSame(VectorClock.EMPTY, VectorClock.of(Map.of("A", 0)));
    }

    @Test
    void malformedClocksAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> VectorClock.of(Map.of("A", -1)));
    }
}
