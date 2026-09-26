package com.quiver.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quiver.node.ClusterConfig;
import com.quiver.node.NodeConfig;
import com.quiver.node.NodeKeyStore;
import com.quiver.node.ObjectStore;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** End-to-end tests over real sockets. These are the tests the project never had. */
class NodeSyncIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final List<NodeServer> started = new ArrayList<>();
    // Each test's clusterOf() call populates this so start() can find that node's own
    // keypair; ClusterConfig only ever carries public keys, by design (see NodeConfig).
    private final Map<String, KeyPair> keysByNodeId = new HashMap<>();

    @AfterEach
    void stopNodes() {
        started.forEach(NodeServer::close);
        started.clear();
    }

    @Test
    void updateOnOwnerConvergesOnPeer() throws Exception {
        ClusterConfig cluster = clusterOf("A", "B");
        ObjectStore storeA = new ObjectStore("A");
        ObjectStore storeB = new ObjectStore("B");
        NodeServer serverA = start("A", cluster, storeA);
        start("B", cluster, storeB);

        storeA.createObject("doc1");
        serverA.broadcast(SyncMessage.create("doc1", "A"));
        storeA.updateObject("doc1", "hello-from-A");
        serverA.broadcast(SyncMessage.update("doc1", "hello-from-A", storeA.getObjectTimestamp("doc1"), "A"));

        await(() -> "hello-from-A".equals(storeB.getObjectValue("doc1")));
        assertEquals("A", storeB.getOwner("doc1"));
    }

    @Test
    void peerWithoutWritePermissionCannotChangeAnObject() throws Exception {
        ClusterConfig cluster = clusterOf("A", "B");
        ObjectStore storeA = new ObjectStore("A");
        ObjectStore storeB = new ObjectStore("B");
        start("A", cluster, storeA);
        NodeServer serverB = start("B", cluster, storeB);

        storeA.createObject("doc1");
        storeA.updateObject("doc1", "owned-by-A");
        storeB.applyRemoteCreate("doc1", "A");

        // B forges the write locally and pushes it anyway.
        serverB.broadcast(SyncMessage.update("doc1", "sneaky-from-B",
                com.quiver.crdt.VectorClock.of(java.util.Map.of("B", 99)), "B"));

        Thread.sleep(500);
        assertEquals("owned-by-A", storeA.getObjectValue("doc1"));
    }

    @Test
    void grantedWriterCanUpdateRemotely() throws Exception {
        ClusterConfig cluster = clusterOf("A", "B");
        ObjectStore storeA = new ObjectStore("A");
        ObjectStore storeB = new ObjectStore("B");
        NodeServer serverA = start("A", cluster, storeA);
        NodeServer serverB = start("B", cluster, storeB);

        storeA.createObject("doc1");
        serverA.broadcast(SyncMessage.create("doc1", "A"));
        await(() -> storeB.objectExists("doc1"));

        storeA.grantPermission("doc1", "write", "B", "A");
        serverA.broadcast(SyncMessage.grantPermission("doc1", "write", "B", "A"));
        await(() -> storeB.hasWriteAccess("doc1", "B"));

        storeB.updateObject("doc1", "from-B");
        serverB.broadcast(SyncMessage.update("doc1", "from-B", storeB.getObjectTimestamp("doc1"), "B"));

        await(() -> "from-B".equals(storeA.getObjectValue("doc1")));
    }

    @Test
    void unauthenticatedPeerIsIgnored() throws Exception {
        ClusterConfig cluster = clusterOf("A", "B");
        ObjectStore storeA = new ObjectStore("A");
        NodeServer serverA = start("A", cluster, storeA);
        storeA.createObject("doc1");
        storeA.updateObject("doc1", "owned-by-A");

        String forged = """
                {"senderNodeId":"A","sentAtMillis":%d,"nonce":"x","payloadJson":"{}","signature":"AA=="}
                """.formatted(System.currentTimeMillis());
        try (Socket socket = new Socket("127.0.0.1", serverA.boundPort());
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {
            writer.println(forged);
            writer.println("not-json-at-all");   // must not kill the handler
        }

        Thread.sleep(300);
        assertEquals("owned-by-A", storeA.getObjectValue("doc1"), "forged write must not land");
        assertTrue(serverA.boundPort() > 0, "server must still be running after bad input");
    }

    @Test
    void lateJoinerCatchesUpViaStateTransfer() throws Exception {
        ClusterConfig cluster = clusterOf("A", "B");
        ObjectStore storeA = new ObjectStore("A");
        start("A", cluster, storeA);

        storeA.createObject("doc1");
        storeA.grantPermission("doc1", "write", "B", "A");
        storeA.updateObject("doc1", "written-before-B-existed");

        ObjectStore storeB = new ObjectStore("B");
        NodeServer serverB = start("B", cluster, storeB);
        assertNotEquals("written-before-B-existed", storeB.getObjectValue("doc1"));

        serverB.requestFullSyncFromPeers();

        await(() -> "written-before-B-existed".equals(storeB.getObjectValue("doc1")));
        assertEquals("A", storeB.getOwner("doc1"));
        assertTrue(storeB.hasWriteAccess("doc1", "B"), "ACLs must travel with the state");
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
     * Builds a cluster of real Ed25519 identities, one per node id. The public halves go
     * into the returned {@link ClusterConfig} (exactly what ships in {@code config.json}
     * in production); the private halves are stashed in {@link #keysByNodeId} so {@link
     * #start} can hand each node its own key when constructing its {@link NodeServer} —
     * mirroring how a real deployment keeps private keys node-local and out of the
     * shared config entirely.
     */
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
