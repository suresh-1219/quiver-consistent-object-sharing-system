package com.quiver.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.quiver.node.ClusterConfig;
import com.quiver.node.JournalStore;
import com.quiver.node.NodeConfig;
import com.quiver.node.NodeKeyStore;
import com.quiver.node.ObjectStore;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deliberately worse-than-healthy conditions: nodes killed without warning, garbage on
 * the wire, repeated restart churn. {@link NodeSyncIntegrationTest} proves the happy
 * path works; this class exists to find out what happens when it doesn't.
 *
 * <p>A real SIGKILL and a real network partition are things a single JVM test cannot
 * fully fake — that's what the Docker-based runbook in {@code CHAOS.md} is for, against
 * the actual deployed containers. What a test here <em>can</em> do faithfully is the
 * part that matters most for the persistence and convergence claims: stop using a node's
 * {@link NodeServer} without ever calling {@link JournalStore#close()} on it, which is
 * exactly what a SIGKILL does to a real process — nothing gets a chance to run a
 * shutdown hook. Because {@link JournalStore#append} fsyncs before returning, this is a
 * faithful test of "what survives a kill", not a weaker approximation of it.
 */
class ChaosIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final List<NodeServer> started = new ArrayList<>();
    private final List<JournalStore> openJournals = new ArrayList<>();
    private final Map<String, JournalStore> journalByNodeId = new HashMap<>();
    private final Map<String, KeyPair> keysByNodeId = new HashMap<>();

    @AfterEach
    void stopEverything() {
        started.forEach(NodeServer::close);
        started.clear();
        // Deliberately NOT closing openJournals here in most tests — a kill doesn't
        // close anything either. Individual tests close what they need to for cleanup.
        openJournals.forEach(JournalStore::close);
        openJournals.clear();
    }

    /**
     * The core persistence promise, proven at the level a user actually experiences it:
     * not "JournalStore.replayInto returns the right value" (already covered in
     * {@code JournalStoreTest}) but "a node that gets killed mid-session and comes back
     * has what it already told its peers it had" — the whole assembled system, not one
     * class in isolation.
     */
    @Test
    void killedNodeRecoversEverythingItHadBeforeTheKill(@TempDir Path dataDir) throws Exception {
        ClusterConfig cluster = clusterOf("A", "B");
        Path journalPathB = dataDir.resolve("B.jsonl");

        ObjectStore storeA = new ObjectStore("A");
        NodeServer serverA = start("A", cluster, storeA);

        ObjectStore storeB = new ObjectStore("B");
        NodeServer serverB = startWithPersistence("B", cluster, storeB, journalPathB);

        storeA.createObject("doc1");
        serverA.broadcast(SyncMessage.create("doc1", "A"));
        storeA.updateObject("doc1", "before-the-kill");
        serverA.broadcast(SyncMessage.update("doc1", "before-the-kill", storeA.getObjectTimestamp("doc1"), "A"));
        await(() -> "before-the-kill".equals(storeB.getObjectValue("doc1")));

        // The kill: close the server (so nothing more can reach it) and simply stop
        // using the journal, never calling close() on it. A real SIGKILL gives a
        // process exactly this: no shutdown hook runs, no final flush happens — and
        // none is needed, because every append already fsynced on its own.
        serverA.close();
        serverB.close();
        started.clear();
        reclaimJournalHandleFor("B");

        // The restart: brand-new ObjectStore (a new process has no memory of the old
        // one), replay the same journal file, re-attach, start again. This is exactly
        // what Main does on startup.
        ObjectStore restartedStoreB = new ObjectStore("B");
        NodeServer restartedServerB = startWithPersistence("B", cluster, restartedStoreB, journalPathB);

        assertEquals("before-the-kill", restartedStoreB.getObjectValue("doc1"),
                "everything acknowledged before the kill must survive it, with no peer needed");
        assertEquals("A", restartedStoreB.getOwner("doc1"));

        restartedServerB.close();
    }

    /**
     * Churn: kill and restart the same node several times while its peer keeps writing
     * in between. Each restart must pick up exactly where the journal left off, and by
     * the end both replicas must agree on every single write that happened along the way
     * — not just the most recent one.
     */
    @Test
    void repeatedKillRestartCyclesStillConvergeOnEveryWrite(@TempDir Path dataDir) throws Exception {
        ClusterConfig cluster = clusterOf("A", "B");
        Path journalPathB = dataDir.resolve("B.jsonl");

        ObjectStore storeA = new ObjectStore("A");
        NodeServer serverA = start("A", cluster, storeA);
        storeA.createObject("doc1");

        ObjectStore currentStoreB = new ObjectStore("B");
        NodeServer currentServerB = startWithPersistence("B", cluster, currentStoreB, journalPathB);
        serverA.broadcast(SyncMessage.create("doc1", "A"));
        final ObjectStore storeBAtStart = currentStoreB; 
        // currentStoreB is reassigned 
        await(() -> storeBAtStart.objectExists("doc1")); 
        int cycles = 5;
        for (int i = 0; i < cycles; i++) {
            String value = "write-" + i;
            storeA.updateObject("doc1", value);
            serverA.broadcast(SyncMessage.update("doc1", value, storeA.getObjectTimestamp("doc1"), "A"));
            final ObjectStore storeBThisCycle = currentStoreB; 
            // same reassignment issue, same fix, each cycle 
            await(() -> value.equals(storeBThisCycle.getObjectValue("doc1")));
            // Kill B mid-churn: no graceful close of anything the application controls.
            currentServerB.close();
            // Stand in for the OS reclaiming the dead process's file descriptors before
            // the next one starts — see reclaimJournalHandleFor's javadoc for why this
            // doesn't weaken the kill simulation.
            reclaimJournalHandleFor("B");

            // Restart it, fresh store, same journal file.
            currentStoreB = new ObjectStore("B");
            currentServerB = startWithPersistence("B", cluster, currentStoreB, journalPathB);
            final ObjectStore storeToCheck = currentStoreB;
            // Must have exactly the last write it saw before dying, from the journal
            // alone — A hasn't been asked to resend anything yet at this point.
            assertEquals(value, storeToCheck.getObjectValue("doc1"),
                    "cycle " + i + ": restart must recover the last acknowledged write from its own journal");
        }

        assertEquals("write-" + (cycles - 1), currentStoreB.getObjectValue("doc1"));
        currentServerB.close();
    }

    /**
     * A steady stream of malformed, truncated, and outright garbage input on real
     * sockets, interleaved with legitimate messages from a real peer. Nothing here
     * should be new in kind — {@code NodeSyncIntegrationTest.unauthenticatedPeerIsIgnored}
     * already covers one malformed line — but a chaos suite's job is breadth: throw
     * enough different kinds of garbage that a gap in the parsing/validation chain has
     * somewhere to show itself.
     */
    @Test
    void nodeSurvivesAFloodOfGarbageAndStillAcceptsRealMessagesAfterward() throws Exception {
        ClusterConfig cluster = clusterOf("A", "B");
        ObjectStore storeA = new ObjectStore("A");
        ObjectStore storeB = new ObjectStore("B");
        NodeServer serverA = start("A", cluster, storeA);
        NodeServer serverB = start("B", cluster, storeB);

        // 1. A connection that sends a line with no trailing newline, then closes
        //    abruptly mid-"message" — simulates a peer dying mid-write.
        try (Socket socket = new Socket("127.0.0.1", serverA.boundPort());
             OutputStream out = socket.getOutputStream()) {
            out.write("{\"senderNodeId\":\"Z\",\"payloadJson\":\"{incomplete".getBytes(StandardCharsets.UTF_8));
            out.flush();
            // socket closes here via try-with-resources, no newline ever sent
        }

        // 2. Raw binary garbage, not text at all.
        try (Socket socket = new Socket("127.0.0.1", serverA.boundPort());
             OutputStream out = socket.getOutputStream()) {
            byte[] junk = new byte[256];
            new java.util.Random(42).nextBytes(junk);
            out.write(junk);
            out.write('\n');
        }

        // 3. Syntactically valid JSON envelope, nonsense signature.
        try (Socket socket = new Socket("127.0.0.1", serverA.boundPort());
             OutputStream out = socket.getOutputStream()) {
            String line = """
                    {"senderNodeId":"B","sentAtMillis":%d,"nonce":"x","payloadJson":"{}","signature":"not-a-real-signature"}
                    """.formatted(System.currentTimeMillis());
            out.write(line.getBytes(StandardCharsets.UTF_8));
        }

        // 4. An empty connection: open, send nothing, close.
        try (Socket socket = new Socket("127.0.0.1", serverA.boundPort())) {
            // nothing
        }

        // The real test: a legitimate peer, sending a legitimate message, right after
        // all of the above. If any of the garbage above left a connection handler
        // thread dead, the accept loop stuck, or the pool exhausted, this is where it
        // would show up — not as an exception, just as this never arriving.
        storeB.createObject("doc1");
        serverB.broadcast(SyncMessage.create("doc1", "B"));
        storeB.updateObject("doc1", "still-works-after-garbage");
        serverB.broadcast(SyncMessage.update("doc1", "still-works-after-garbage",
                storeB.getObjectTimestamp("doc1"), "B"));

        await(() -> "still-works-after-garbage".equals(storeA.getObjectValue("doc1")));
        assertEquals("B", storeA.getOwner("doc1"));
    }

    /**
     * {@code JournalStoreTest} already proves a torn last line is skipped by {@code
     * JournalStore} in isolation. This proves the same thing through the whole stack a
     * real restart actually goes through: {@code replayInto} feeding a fresh {@link
     * ObjectStore}, exactly as {@code Main} wires it — because an integration-level
     * regression here (say, a future change that lets a parse exception propagate out of
     * {@code Main} instead of being caught) would not be caught by the unit test alone.
     */
    @Test
    void crashDuringAJournalWriteDoesNotLoseEarlierStateOnNextStartup(@TempDir Path dataDir) throws Exception {
        Path journalPath = dataDir.resolve("A.jsonl");

        ObjectStore store = new ObjectStore("A");
        try (JournalStore journal = JournalStore.open(journalPath)) {
            store.setChangeListener(journal.asChangeListener());
            store.createObject("doc1");
            store.updateObject("doc1", "safely-written");
        }

        // Simulate a crash mid-append: a torn, unterminated final line appended directly
        // to the file, bypassing JournalStore entirely (which never produces this on its
        // own — this is standing in for the OS flushing a partial write right as the
        // process died).
        Files.writeString(journalPath, "{\"name\":\"doc1\",\"value\":\"neve",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        ObjectStore restarted = new ObjectStore("A");
        JournalStore.replayInto(journalPath, restarted);

        assertEquals("safely-written", restarted.getObjectValue("doc1"),
                "the last cleanly-written state must survive a torn line after it");
    }

    // ------------------------------------------------------------------------- helpers

    private NodeServer start(String nodeId, ClusterConfig cluster, ObjectStore store) throws IOException {
        KeyPair selfKeyPair = keysByNodeId.get(nodeId);
        NodeServer server = new NodeServer(nodeId, selfKeyPair, cluster.require(nodeId).port, store, cluster);
        server.start();
        started.add(server);
        return server;
    }

    /**
     * Starts a node the way {@code Main} actually does: replay the journal into a store
     * with no listener yet, then open the journal for writing and attach it. Returns the
     * {@link NodeServer}; the {@link JournalStore} it opens is tracked in {@link
     * #openJournals} for cleanup, but deliberately not returned — tests that want to
     * simulate a kill just stop using the returned server and never touch the journal
     * again, which is the whole point.
     */
    private NodeServer startWithPersistence(String nodeId, ClusterConfig cluster, ObjectStore store,
                                            Path journalPath) throws IOException {
        JournalStore.replayInto(journalPath, store);
        JournalStore journal = JournalStore.open(journalPath);
        openJournals.add(journal);
        journalByNodeId.put(nodeId, journal);
        store.setChangeListener(journal.asChangeListener());

        KeyPair selfKeyPair = keysByNodeId.get(nodeId);
        NodeServer server = new NodeServer(nodeId, selfKeyPair, cluster.require(nodeId).port, store, cluster);
        server.start();
        started.add(server);
        return server;
    }

    /**
     * Closes the file handle a previous {@link #startWithPersistence} call opened for
     * {@code nodeId}, if any. Call this only <em>after</em> that node's {@link
     * NodeServer} has already been closed — at that point the node is fully "dead" and
     * nothing can write through its journal anyway, so this just stands in for the OS
     * reclaiming the old process's file descriptors before the next restart, the same
     * way it would between two real process lifetimes. It is not standing in for a
     * graceful shutdown: nothing here calls any flush-on-shutdown logic the application
     * itself doesn't already have, and closing it is not what makes the data durable —
     * every write was already fsynced to disk the moment it was appended, which is the
     * entire point of {@link #killedNodeRecoversEverythingItHadBeforeTheKill} below.
     * Without this, repeated kill/restart cycles in one JVM would quietly accumulate
     * open file handles to the same path for the lifetime of the test — harmless at this
     * scale, but sloppy, and not a faithful model of what happens between two real
     * process lifetimes either.
     */
    private void reclaimJournalHandleFor(String nodeId) {
        JournalStore old = journalByNodeId.remove(nodeId);
        if (old != null) {
            old.close();
            openJournals.remove(old);
        }
    }

    private ClusterConfig clusterOf(String... nodeIds) throws IOException {
        List<NodeConfig> nodes = new ArrayList<>();
        for (String id : nodeIds) {
            KeyPair keyPair = NodeKeyStore.generate();
            keysByNodeId.put(id, keyPair);
            String publicKey = NodeKeyStore.encodePublic(keyPair.getPublic());
            nodes.add(new NodeConfig(id, "127.0.0.1", freePort(), publicKey));
        }
        return new ClusterConfig(nodes);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("condition not met within " + TIMEOUT);
    }
}
