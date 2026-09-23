package com.quiver.crdt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LWWRegisterTest {

    /** One replicated write, as it would arrive over the wire. */
    private record Write(String value, VectorClock clock, String writer) { }

    @Test
    void localUpdateSetsValue() {
        LWWRegister<String> register = new LWWRegister<>();

        register.update("Hello from A", "A");

        assertEquals("Hello from A", register.getValue());
    }

    @Test
    void remoteUpdatePropagatesToReplica() {
        LWWRegister<String> replicaA = new LWWRegister<>();
        LWWRegister<String> replicaB = new LWWRegister<>();

        replicaA.update("Hello from A", "A");
        replicaB.merge(replicaA.getValue(), replicaA.getTimestamp(), "A");

        assertEquals(replicaA.getValue(), replicaB.getValue());
    }

    @Test
    void concurrentUpdatesConvergeToSameValueOnBothNodes() {
        LWWRegister<String> nodeX = new LWWRegister<>();
        LWWRegister<String> nodeY = new LWWRegister<>();

        nodeX.update("Value from X", "X");
        nodeY.update("Value from Y", "Y");

        // Capture both writes before either replica merges, otherwise the second merge
        // just replays the winner back at itself and proves nothing.
        String xValue = nodeX.getValue();
        VectorClock xClock = nodeX.getTimestamp();
        String yValue = nodeY.getValue();
        VectorClock yClock = nodeY.getTimestamp();

        nodeX.merge(yValue, yClock, "Y");
        nodeY.merge(xValue, xClock, "X");

        assertEquals(nodeX.getValue(), nodeY.getValue());
    }

    /**
     * With equally advanced clocks the writer id decides, and every replica decides the
     * same way regardless of the order the two writes arrive in.
     */
    @Test
    void equallyAdvancedConcurrentWritesAreBrokenByWriterId() {
        LWWRegister<String> nodeX = new LWWRegister<>();
        LWWRegister<String> nodeY = new LWWRegister<>();

        nodeX.update("Value from X", "X");
        nodeY.update("Value from Y", "Y");

        String xValue = nodeX.getValue();
        VectorClock xClock = nodeX.getTimestamp();
        String yValue = nodeY.getValue();
        VectorClock yClock = nodeY.getTimestamp();

        nodeX.merge(yValue, yClock, "Y");
        nodeY.merge(xValue, xClock, "X");

        assertEquals("Value from Y", nodeX.getValue());
        assertEquals("Value from Y", nodeY.getValue());
    }

    /**
     * Causal history outranks the writer id: a write that has seen more of the world
     * wins even when it comes from a lower-id node. This is what keeps the total order
     * used for conflict resolution consistent with the causal partial order.
     */
    @Test
    void moreAdvancedClockBeatsHigherWriterId() {
        LWWRegister<String> register = new LWWRegister<>();
        register.update("a1", "A");
        register.update("a2", "A");
        register.update("a3", "A"); // clock {A=3}

        LWWRegister.MergeOutcome outcome =
                register.merge("b1", VectorClock.of(Map.of("B", 1)), "B");

        assertEquals(LWWRegister.MergeOutcome.IGNORED_LOST_TIEBREAK, outcome);
        assertEquals("a3", register.getValue());
    }

    @Test
    void staleUpdateIsIgnored() {
        LWWRegister<String> register = new LWWRegister<>();
        register.update("first", "A");

        LWWRegister.MergeOutcome outcome = register.merge("stale value", VectorClock.EMPTY, "B");

        assertEquals(LWWRegister.MergeOutcome.IGNORED_STALE, outcome);
        assertEquals("first", register.getValue());
    }

    @Test
    void duplicateDeliveryIsANoOp() {
        LWWRegister<String> register = new LWWRegister<>();
        register.update("v1", "A");
        VectorClock clock = register.getTimestamp();

        assertEquals(LWWRegister.MergeOutcome.NO_CHANGE, register.merge("v1", clock, "A"));
        assertEquals("v1", register.getValue());
    }

    /**
     * Regression: the previous implementation replaced its clock on merge, so a replica
     * that lost a conflict forgot its own counter and reissued it. Counters must only
     * ever move forward, and the losing merge must still be absorbed into the clock the
     * next local write is stamped from.
     */
    @Test
    void ownCounterNeverRegressesAfterLosingAMerge() {
        LWWRegister<String> nodeA = new LWWRegister<>();
        nodeA.update("a1", "A");
        nodeA.update("a2", "A");
        nodeA.update("a3", "A");
        assertEquals(3, nodeA.getTimestamp().counterFor("A"));

        // Equally advanced ({B=3} vs {A=3}), so the writer id decides and B wins.
        nodeA.merge("b1", VectorClock.of(Map.of("B", 3)), "B");
        assertEquals("b1", nodeA.getValue());
        assertEquals(0, nodeA.getTimestamp().counterFor("A"),
                "the winning value carries only its own clock");

        nodeA.update("a4", "A");

        assertEquals(4, nodeA.getTimestamp().counterFor("A"), "A's counter must continue from 3");
        assertEquals(3, nodeA.getTimestamp().counterFor("B"), "the merged clock must be retained");
        assertEquals("a4", nodeA.getValue());
    }

    /**
     * Convergence property: replicas that see the same writes in any order, with
     * duplicates, must end up identical.
     */
    @Test
    void replicasConvergeUnderAnyDeliveryOrder() {
        Random random = new Random(20260921L);

        for (int trial = 0; trial < 200; trial++) {
            List<Write> writes = generateWrites(random);

            String reference = null;
            for (int replica = 0; replica < 8; replica++) {
                List<Write> delivery = new ArrayList<>(writes);
                java.util.Collections.shuffle(delivery, random);
                delivery.add(delivery.get(random.nextInt(delivery.size()))); // duplicate

                LWWRegister<String> register = new LWWRegister<>();
                for (Write w : delivery) {
                    register.merge(w.value(), w.clock(), w.writer());
                }

                if (reference == null) {
                    reference = register.getValue();
                    assertNotNull(reference);
                } else {
                    assertEquals(reference, register.getValue(),
                            "replicas diverged for write set " + writes);
                }
            }
        }
    }

    /** Simulates a few nodes writing, occasionally having seen each other's clocks. */
    private static List<Write> generateWrites(Random random) {
        String[] nodes = {"A", "B", "C"};
        List<Write> writes = new ArrayList<>();
        List<VectorClock> known = new ArrayList<>(List.of(VectorClock.EMPTY));

        int count = 2 + random.nextInt(5);
        for (int i = 0; i < count; i++) {
            String node = nodes[random.nextInt(nodes.length)];
            VectorClock base = known.get(random.nextInt(known.size()));
            VectorClock clock = base.incremented(node);
            known.add(clock);
            writes.add(new Write(node + "-write-" + i, clock, node));
        }
        assertTrue(writes.size() >= 2);
        return writes;
    }
}