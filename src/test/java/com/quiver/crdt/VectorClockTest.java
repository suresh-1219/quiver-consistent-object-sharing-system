package com.quiver.crdt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VectorClockTest {

    @Test
    void newClocksAreEqual() {
        VectorClock a = new VectorClock();
        VectorClock b = new VectorClock();

        assertEquals("EQUAL", a.compare(b));
    }

    @Test
    void incrementedClockIsAfterOriginal() {
        VectorClock a = new VectorClock();
        VectorClock b = new VectorClock();

        b.increment("A");

        assertEquals("BEFORE", a.compare(b));
        assertEquals("AFTER", b.compare(a));
    }

    @Test
    void independentIncrementsAreConcurrent() {
        VectorClock a = new VectorClock();
        VectorClock b = new VectorClock();

        a.increment("A");
        b.increment("B");

        assertEquals("CONCURRENT", a.compare(b));
        assertEquals("CONCURRENT", b.compare(a));
    }

    @Test
    void sameIncrementsAreEqual() {
        VectorClock a = new VectorClock();
        VectorClock b = new VectorClock();

        a.increment("A");
        b.increment("A");

        assertEquals("EQUAL", a.compare(b));
    }
}
