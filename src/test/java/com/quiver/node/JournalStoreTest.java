package com.quiver.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalStoreTest {

    @Test
    void writesThenRestoresIdenticalState(@TempDir Path dir) throws IOException {
        Path journalPath = dir.resolve("A.jsonl");

        ObjectStore original = new ObjectStore("A");
        try (JournalStore journal = JournalStore.open(journalPath)) {
            original.setChangeListener(journal.asChangeListener());

            original.createObject("doc1");
            original.updateObject("doc1", "hello");
            original.grantPermission("doc1", "write", "B", "A");
            original.updateObject("doc1", "hello-again");
        }

        ObjectStore restored = new ObjectStore("A");
        JournalStore.replayInto(journalPath, restored);

        assertEquals("hello-again", restored.getObjectValue("doc1"));
        assertEquals("A", restored.getOwner("doc1"));
        assertTrue(restored.hasWriteAccess("doc1", "B"));
        assertEquals(original.getObjectTimestamp("doc1"), restored.getObjectTimestamp("doc1"));
    }

    @Test
    void missingJournalFileIsNotAnError(@TempDir Path dir) throws IOException {
        Path journalPath = dir.resolve("does-not-exist.jsonl");
        ObjectStore store = new ObjectStore("A");

        JournalStore.replayInto(journalPath, store);

        assertTrue(store.listObjects().isEmpty());
    }

    /** A crash mid-write leaves a torn last line; replay must skip it, not fail outright. */
    @Test
    void tornLastLineIsSkippedNotFatal(@TempDir Path dir) throws IOException {
        Path journalPath = dir.resolve("A.jsonl");
        ObjectStore original = new ObjectStore("A");
        try (JournalStore journal = JournalStore.open(journalPath)) {
            original.setChangeListener(journal.asChangeListener());
            original.createObject("doc1");
            original.updateObject("doc1", "hello");
        }
        Files.writeString(journalPath, "{\"name\":\"doc1\",\"value\":\"broke", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        ObjectStore restored = new ObjectStore("A");
        JournalStore.replayInto(journalPath, restored);

        assertEquals("hello", restored.getObjectValue("doc1"), "the good lines before the torn one must still apply");
    }

    /** Replay must not re-trigger the listener that produced the file in the first place. */
    @Test
    void replayIntoAStoreWithNoListenerDoesNotGrowTheFile(@TempDir Path dir) throws IOException {
        Path journalPath = dir.resolve("A.jsonl");
        ObjectStore original = new ObjectStore("A");
        try (JournalStore journal = JournalStore.open(journalPath)) {
            original.setChangeListener(journal.asChangeListener());
            original.createObject("doc1");
            original.updateObject("doc1", "hello");
        }
        long sizeBeforeReplay = Files.size(journalPath);

        ObjectStore restored = new ObjectStore("A"); // no listener attached
        JournalStore.replayInto(journalPath, restored);

        assertEquals(sizeBeforeReplay, Files.size(journalPath));
    }

    @Test
    void replayIsIdempotentWhenRunTwice(@TempDir Path dir) throws IOException {
        Path journalPath = dir.resolve("A.jsonl");
        ObjectStore original = new ObjectStore("A");
        try (JournalStore journal = JournalStore.open(journalPath)) {
            original.setChangeListener(journal.asChangeListener());
            original.createObject("doc1");
            original.updateObject("doc1", "hello");
        }

        ObjectStore restored = new ObjectStore("A");
        JournalStore.replayInto(journalPath, restored);
        JournalStore.replayInto(journalPath, restored); // replay the same file again

        assertEquals("hello", restored.getObjectValue("doc1"));
        assertFalse(restored.getObjectTimestamp("doc1").isEmpty());
    }
}
