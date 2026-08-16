package com.quiver.crdt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LWWRegisterTest {

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

        String xValue = nodeX.getValue();
        VectorClock xTimestamp = nodeX.getTimestamp();
        String yValue = nodeY.getValue();
        VectorClock yTimestamp = nodeY.getTimestamp();

        nodeX.merge(yValue, yTimestamp, "Y");
        nodeY.merge(xValue, xTimestamp, "X");

        assertEquals(nodeX.getValue(), nodeY.getValue());
    }

    @Test
    void concurrentConflictBreaksTieByHigherNodeId() {
       
        LWWRegister<String> nodeX = new LWWRegister<>();
        LWWRegister<String> nodeY = new LWWRegister<>();

        nodeX.update("Value from X", "X");
        nodeY.update("Value from Y", "Y");

        nodeX.merge(nodeY.getValue(), nodeY.getTimestamp(), "Y");
        nodeY.merge(nodeX.getValue(), nodeX.getTimestamp(), "X");

        assertEquals("Value from Y", nodeX.getValue());
        assertEquals("Value from Y", nodeY.getValue());
    }

    @Test
    void staleUpdateIsIgnored() {
        LWWRegister<String> register = new LWWRegister<>();

        register.update("first", "A");
        VectorClock staleTimestamp = new VectorClock(); // empty/older clock

        register.merge("stale value", staleTimestamp, "B");

        assertEquals("first", register.getValue());
    }
}
