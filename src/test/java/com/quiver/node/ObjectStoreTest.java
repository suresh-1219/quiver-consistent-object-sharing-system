package com.quiver.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ObjectStoreTest {

    @Test
    void creatingObjectSetsCreatorAsOwner() {
        ObjectStore store = new ObjectStore("A");

        boolean created = store.createObject("doc1");

        assertTrue(created);
        assertEquals("A", store.getOwner("doc1"));
    }

    @Test
    void cannotCreateSameObjectTwice() {
        ObjectStore store = new ObjectStore("A");

        store.createObject("doc1");
        boolean createdAgain = store.createObject("doc1");

        assertFalse(createdAgain);
    }

    @Test
    void ownerCanUpdateOwnObject() {
        ObjectStore store = new ObjectStore("A");
        store.createObject("doc1");

        boolean updated = store.updateObject("doc1", "hello");

        assertTrue(updated);
        assertEquals("hello", store.getObjectValue("doc1"));
    }

    @Test
    void nonOwnerWithoutPermissionCannotUpdate() {
        ObjectStore store = new ObjectStore("B");
        // "doc1" is owned by node A (as if it synced in via sync)
        store.createObjectWithOwner("doc1", "A");

        boolean updated = store.updateObject("doc1", "hello");

        assertFalse(updated);
    }

    @Test
    void grantingWritePermissionAllowsNonOwnerToUpdate() {
        ObjectStore store = new ObjectStore("A");
        store.createObject("doc1");

        store.grantPermission("doc1", "write", "B", "A");

        assertTrue(store.hasWriteAccess("doc1", "B"));
    }

    @Test
    void onlyOwnerCanGrantPermission() {
        ObjectStore store = new ObjectStore("A");
        store.createObject("doc1");

        boolean granted = store.grantPermission("doc1", "write", "C", "B");

        assertFalse(granted);
        assertFalse(store.hasWriteAccess("doc1", "C"));
    }

    @Test
    void mergeRemoteUpdateCreatesObjectIfMissing() {
        ObjectStore store = new ObjectStore("B");
        com.quiver.crdt.VectorClock timestamp = new com.quiver.crdt.VectorClock();
        timestamp.increment("A");

        store.mergeRemoteUpdate("doc1", "hello from A", timestamp, "A");

        assertTrue(store.objectExists("doc1"));
        assertEquals("hello from A", store.getObjectValue("doc1"));
    }
}
