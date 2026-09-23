package com.quiver.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quiver.crdt.VectorClock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ObjectStoreTest {

    @Test
    void creatingObjectSetsCreatorAsOwner() {
        ObjectStore store = new ObjectStore("A");

        assertEquals(ObjectStore.CreateOutcome.CREATED, store.createObject("doc1"));
        assertEquals("A", store.getOwner("doc1"));
    }

    @Test
    void cannotCreateSameObjectTwice() {
        ObjectStore store = new ObjectStore("A");
        store.createObject("doc1");

        assertEquals(ObjectStore.CreateOutcome.ALREADY_EXISTS, store.createObject("doc1"));
    }

    @Test
    void ownerCanUpdateOwnObject() {
        ObjectStore store = new ObjectStore("A");
        store.createObject("doc1");

        assertEquals(ObjectStore.UpdateOutcome.UPDATED, store.updateObject("doc1", "hello"));
        assertEquals("hello", store.getObjectValue("doc1"));
    }

    @Test
    void nonOwnerWithoutPermissionCannotUpdate() {
        ObjectStore store = new ObjectStore("B");
        store.applyRemoteCreate("doc1", "A");

        assertEquals(ObjectStore.UpdateOutcome.ACCESS_DENIED, store.updateObject("doc1", "hello"));
    }

    @Test
    void updatingUnknownObjectIsReportedSeparatelyFromDenial() {
        ObjectStore store = new ObjectStore("A");

        assertEquals(ObjectStore.UpdateOutcome.NOT_FOUND, store.updateObject("ghost", "hello"));
    }

    @Test
    void grantingWritePermissionAllowsNonOwnerToUpdate() {
        ObjectStore store = new ObjectStore("A");
        store.createObject("doc1");

        assertEquals(ObjectStore.GrantOutcome.GRANTED,
                store.grantPermission("doc1", "write", "B", "A"));
        assertTrue(store.hasWriteAccess("doc1", "B"));
    }

    @Test
    void onlyOwnerCanGrantPermission() {
        ObjectStore store = new ObjectStore("A");
        store.createObject("doc1");

        assertEquals(ObjectStore.GrantOutcome.NOT_OWNER,
                store.grantPermission("doc1", "write", "C", "B"));
        assertFalse(store.hasWriteAccess("doc1", "C"));
    }

    /** Regression: remote writes used to bypass the ACL entirely. */
    @Test
    void remoteUpdateFromUnauthorisedNodeIsRefused() {
        ObjectStore store = new ObjectStore("A");
        store.createObject("doc1");
        store.updateObject("doc1", "owned-by-A");

        ObjectStore.MergeOutcome outcome = store.applyRemoteUpdate(
                "doc1", "injected", VectorClock.of(Map.of("Z", 99)), "Z");

        assertEquals(ObjectStore.MergeOutcome.ACCESS_DENIED, outcome);
        assertEquals("owned-by-A", store.getObjectValue("doc1"));
    }

    @Test
    void remoteUpdateFromGrantedWriterIsMerged() {
        ObjectStore store = new ObjectStore("A");
        store.createObject("doc1");
        store.grantPermission("doc1", "write", "B", "A");

        ObjectStore.MergeOutcome outcome = store.applyRemoteUpdate(
                "doc1", "from-B", VectorClock.of(Map.of("B", 1)), "B");

        assertEquals(ObjectStore.MergeOutcome.MERGED, outcome);
        assertEquals("from-B", store.getObjectValue("doc1"));
    }

    @Test
    void remoteUpdateForUnknownObjectIsNotSilentlyAccepted() {
        ObjectStore store = new ObjectStore("A");

        assertEquals(ObjectStore.MergeOutcome.UNKNOWN_OBJECT,
                store.applyRemoteUpdate("ghost", "x", VectorClock.of(Map.of("B", 1)), "B"));
        assertFalse(store.objectExists("ghost"));
    }

    /** Regression: concurrent creates of the same name used to leave owners split. */
    @Test
    void concurrentCreatesResolveToTheSameOwnerEverywhere() {
        ObjectStore onA = new ObjectStore("A");
        ObjectStore onB = new ObjectStore("B");

        onA.createObject("doc1");   // A thinks it owns doc1
        onB.createObject("doc1");   // B thinks it owns doc1

        onA.applyRemoteCreate("doc1", "B");
        onB.applyRemoteCreate("doc1", "A");

        assertEquals(onA.getOwner("doc1"), onB.getOwner("doc1"));
        assertEquals("A", onA.getOwner("doc1"));
    }

    @Test
    void remoteGrantFromNonOwnerIsRefused() {
        ObjectStore store = new ObjectStore("A");
        store.createObject("doc1");

        assertEquals(ObjectStore.GrantOutcome.NOT_OWNER,
                store.applyRemoteGrant("doc1", "write", "Z", "Z"));
        assertFalse(store.hasWriteAccess("doc1", "Z"));
    }

    /** A late joiner must learn ownership and ACLs, not just values. */
    @Test
    void snapshotTransfersValueOwnershipAndAcl() {
        ObjectStore source = new ObjectStore("A");
        source.createObject("doc1");
        source.grantPermission("doc1", "write", "B", "A");
        source.updateObject("doc1", "hello");

        ObjectStore joiner = new ObjectStore("C");
        joiner.applySnapshot(source.snapshot(), "A");

        assertEquals("hello", joiner.getObjectValue("doc1"));
        assertEquals("A", joiner.getOwner("doc1"));
        assertTrue(joiner.hasWriteAccess("doc1", "B"));
        assertFalse(joiner.hasWriteAccess("doc1", "C"));
    }

    @Test
    void listObjectsReturnsACopy() {
        ObjectStore store = new ObjectStore("A");
        store.createObject("doc1");

        store.listObjects().clear();

        assertEquals(List.of("doc1"), List.copyOf(store.listObjects()));
    }
}
