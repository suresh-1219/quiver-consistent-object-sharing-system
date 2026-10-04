package com.quiver.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.quiver.crdt.VectorClock;
import com.quiver.node.ClusterConfig;
import com.quiver.node.NodeConfig;
import com.quiver.node.NodeKeyStore;
import com.quiver.node.ObjectStore;
import java.io.IOException;
import java.net.ServerSocket;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Stress-tests the convergence claim under conditions worse than "everything's healthy":
 * partitions, a bridge node relaying a write neither side of a cut edge could deliver
 * directly, reordered/delayed messages, and randomised combinations of all three.
 *
 * <p>Every fault here is injected by {@link ChaosProxy} sitting on the wire between two
 * nodes — nothing in {@code NodeServer}, {@code ObjectStore} or the CRDT is touched or
 * even aware this is happening. A cut connection surfaces to Quiver exactly the way any
 * other unreachable peer does, through the same code path {@link
 * NodeSyncIntegrationTest} already exercises under normal conditions. These tests exist
 * to show that path, and the CRDT merge behind it, hold up under a genuinely adversarial
 * network, not to add a second thing that needs to be correct.
 */
class ChaosClusterTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final List<NodeServer> started = new ArrayList<>();
    private final List<ChaosProxy> proxies = new ArrayList<>();

    @AfterEach
    void cleanup() {
        started.forEach(NodeServer::close);
        started.clear();
        proxies.forEach(ChaosProxy::close);
        proxies.clear();
    }

    @Test
    void isolatedNodeCatchesUpOnceThePartitionHeals() throws Exception {
        ThreeNodeMesh mesh = buildMesh();

        mesh.cut("A", "B");
        mesh.cut("B", "A");
        mesh.cut("A", "C");
        mesh.cut("C", "A");

        mesh.stores.get("A").createObject("doc1");
        mesh.servers.get("A").broadcast(SyncMessage.create("doc1", "A"));
        mesh.stores.get("A").updateObject("doc1", "written-while-isolated");
        mesh.servers.get("A").broadcast(SyncMessage.update(
                "doc1", "written-while-isolated", mesh.stores.get("A").getObjectTimestamp("doc1"), "A"));

        Thread.sleep(300); // give the (doomed) broadcast attempts time to fail
        assertFalse(mesh.stores.get("B").objectExists("doc1"), "still isolated, must not have it yet");
        assertFalse(mesh.stores.get("C").objectExists("doc1"), "still isolated, must not have it yet");

        mesh.restoreAll();
        mesh.servers.get("B").requestFullSyncFromPeers();
        mesh.servers.get("C").requestFullSyncFromPeers();

        await(() -> "written-while-isolated".equals(mesh.stores.get("B").getObjectValue("doc1")));
        await(() -> "written-while-isolated".equals(mesh.stores.get("C").getObjectValue("doc1")));
        assertEquals("A", mesh.stores.get("B").getOwner("doc1"));
        assertEquals("A", mesh.stores.get("C").getOwner("doc1"));
    }

    /**
     * A does not need to reach B directly for B to eventually learn A's write, as long as
     * some third node both can reach. This isn't a gossip protocol — it falls out of full
     * state transfer: C's reply to a {@code SYNC_REQUEST} is C's own current state, which
     * already includes whatever C itself has received from A. A full mesh with
     * request/reply state transfer gets one hop of relay resilience for free; it just
     * doesn't get more than one hop, since nothing here re-forwards a {@code STATE}
     * message it wasn't itself the origin of.
     */
    @Test
    void writeReachesAnIsolatedPairViaAThirdNodeRelay() throws Exception {
        ThreeNodeMesh mesh = buildMesh();

        mesh.cut("A", "B");
        mesh.cut("B", "A");
        // A<->C and B<->C are deliberately left up, and never healed in this test.

        mesh.stores.get("A").createObject("doc1");
        mesh.servers.get("A").broadcast(SyncMessage.create("doc1", "A"));
        mesh.stores.get("A").updateObject("doc1", "relayed-via-C");
        mesh.servers.get("A").broadcast(SyncMessage.update(
                "doc1", "relayed-via-C", mesh.stores.get("A").getObjectTimestamp("doc1"), "A"));

        await(() -> "relayed-via-C".equals(mesh.stores.get("C").getObjectValue("doc1")));
        Thread.sleep(300);
        assertFalse(mesh.stores.get("B").objectExists("doc1"),
                "A<->B is still cut; B must not have this yet");

        mesh.servers.get("B").requestFullSyncFromPeers(); // asks both A (fails) and C (succeeds)

        await(() -> "relayed-via-C".equals(mesh.stores.get("B").getObjectValue("doc1")));
        assertEquals("A", mesh.stores.get("B").getOwner("doc1"),
                "ownership must travel with the relayed state, not just the value");
    }

    /**
     * B sends two updates to the same object back to back. With random per-connection
     * jitter on the wire, the second write can physically arrive before the first. The
     * final value must be the causally later one regardless — this is the same property
     * {@code LWWRegisterTest} proves for the register in isolation, exercised here over a
     * real, deliberately unreliable network instead of direct method calls.
     */
    @Test
    void reorderedMessagesStillConvergeOnTheCausallyLatestWrite() throws Exception {
        ThreeNodeMesh mesh = buildMesh();
        mesh.setJitter("B", "A", 150);

        ObjectStore storeB = mesh.stores.get("B");
        storeB.createObject("doc1");
        storeB.updateObject("doc1", "v1");
        VectorClock clockV1 = storeB.getObjectTimestamp("doc1");
        storeB.updateObject("doc1", "v2");
        VectorClock clockV2 = storeB.getObjectTimestamp("doc1");
        assertEquals(VectorClock.Ordering.BEFORE, clockV1.compare(clockV2),
                "the test only proves anything if v1 really did happen before v2");

        NodeServer serverB = mesh.servers.get("B");
        serverB.broadcast(SyncMessage.create("doc1", "B"));
        serverB.broadcast(SyncMessage.update("doc1", "v1", clockV1, "B"));
        serverB.broadcast(SyncMessage.update("doc1", "v2", clockV2, "B"));

        await(() -> "v2".equals(mesh.stores.get("A").getObjectValue("doc1")));
        Thread.sleep(400); // let any still-in-flight, out-of-order duplicate arrive too
        assertEquals("v2", mesh.stores.get("A").getObjectValue("doc1"),
                "a late-arriving stale write must not overwrite the causally later one");
    }

    /**
     * The property-based version: random partitions opened and healed around random
     * writes from random nodes, many times over. Every trial ends the same way — heal
     * everything, ask everyone to sync, and check all three nodes agree on the exact
     * same final value and clock. Trial count is deliberately modest (unlike {@code
     * LWWRegisterTest}'s 200 in-memory trials): each one here opens real sockets and
     * threads, not just method calls.
     */
    @Test
    void randomizedChaosTrialsAlwaysConverge() throws Exception {
        Random random = new Random(20261001L);

        for (int trial = 0; trial < 15; trial++) {
            ThreeNodeMesh mesh = buildMesh();
            String[] ids = {"A", "B", "C"};

            mesh.stores.get("A").createObject("doc1");
            mesh.stores.get("A").grantPermission("doc1", "write", "B", "A");
            mesh.stores.get("A").grantPermission("doc1", "write", "C", "A");
            mesh.servers.get("A").broadcast(SyncMessage.create("doc1", "A"));
            mesh.servers.get("A").broadcast(SyncMessage.grantPermission("doc1", "write", "B", "A"));
            mesh.servers.get("A").broadcast(SyncMessage.grantPermission("doc1", "write", "C", "A"));
            // objectExists is checked too, not just hasWriteAccess: the ACL map can
            // register an (empty) entry for a name via a GRANT alone, independently of
            // whether the matching CREATE has landed yet, since ObjectStore's write-
            // permission map and object map are populated separately. Only checking the
            // grant here would risk updateObject() below racing a CREATE still in flight.
            await(() -> mesh.stores.get("B").objectExists("doc1") && mesh.stores.get("B").hasWriteAccess("doc1", "B")
                    && mesh.stores.get("C").objectExists("doc1") && mesh.stores.get("C").hasWriteAccess("doc1", "C"));

            // Randomly sever some subset of the six directed edges.
            for (String from : ids) {
                for (String to : ids) {
                    if (!from.equals(to) && random.nextBoolean()) {
                        mesh.cut(from, to);
                    }
                }
            }

            // Each node writes once, in a random order, to whatever it can currently see.
            List<String> order = new ArrayList<>(List.of(ids));
            java.util.Collections.shuffle(order, random);
            for (String id : order) {
                ObjectStore store = mesh.stores.get(id);
                String value = id + "-trial" + trial;
                store.updateObject("doc1", value);
                mesh.servers.get(id).broadcast(SyncMessage.update(
                        "doc1", value, store.getObjectTimestamp("doc1"), id));
                Thread.sleep(20); // let this write start propagating before the next one fires
            }

            mesh.restoreAll();
            for (String id : ids) {
                mesh.servers.get(id).requestFullSyncFromPeers();
            }

            String trialLabel = "trial " + trial;
            await(() -> {
                String a = mesh.stores.get("A").getObjectValue("doc1");
                String b = mesh.stores.get("B").getObjectValue("doc1");
                String c = mesh.stores.get("C").getObjectValue("doc1");
                return a != null && a.equals(b) && b.equals(c);
            }, trialLabel);
            VectorClock clockA = mesh.stores.get("A").getObjectTimestamp("doc1");
            assertEquals(clockA, mesh.stores.get("B").getObjectTimestamp("doc1"), trialLabel);
            assertEquals(clockA, mesh.stores.get("C").getObjectTimestamp("doc1"), trialLabel);

            mesh.tearDown();
        }
    }

    // ------------------------------------------------------------------------- fixture

    private ThreeNodeMesh buildMesh() throws IOException {
        ThreeNodeMesh mesh = new ThreeNodeMesh();
        mesh.init();
        return mesh;
    }

    /**
     * Three real nodes, each with its <em>own</em> view of how to reach its two peers —
     * either that peer's real port, or a dedicated {@link ChaosProxy} standing in for it.
     * Every directed edge (A-to-B and B-to-A are independent) gets its own proxy, so any
     * single direction of any single edge can be cut without disturbing the other five.
     * A real deployment would never configure asymmetric routing like this by hand; it's
     * exactly what's needed here to make one edge of a mesh independently faulty.
     */
    private final class ThreeNodeMesh {
        final Map<String, ObjectStore> stores = new LinkedHashMap<>();
        final Map<String, NodeServer> servers = new LinkedHashMap<>();
        final Map<String, Map<String, ChaosProxy>> proxyByFromTo = new HashMap<>();

        void init() throws IOException {
            String[] ids = {"A", "B", "C"};
            Map<String, Integer> realPorts = new HashMap<>();
            Map<String, String> publicKeys = new HashMap<>();
            Map<String, KeyPair> keyPairs = new HashMap<>();
            for (String id : ids) {
                realPorts.put(id, freePort());
                KeyPair kp = NodeKeyStore.generate();
                keyPairs.put(id, kp);
                publicKeys.put(id, NodeKeyStore.encodePublic(kp.getPublic()));
            }

            for (String from : ids) {
                Map<String, ChaosProxy> outbound = new HashMap<>();
                for (String to : ids) {
                    if (!from.equals(to)) {
                        ChaosProxy proxy = ChaosProxy.between("127.0.0.1", realPorts.get(to));
                        proxies.add(proxy);
                        outbound.put(to, proxy);
                    }
                }
                proxyByFromTo.put(from, outbound);
            }

            for (String viewer : ids) {
                List<NodeConfig> view = new ArrayList<>();
                for (String id : ids) {
                    int port = id.equals(viewer) ? realPorts.get(id) : proxyByFromTo.get(viewer).get(id).port();
                    view.add(new NodeConfig(id, "127.0.0.1", port, publicKeys.get(id)));
                }
                ClusterConfig clusterViewOfThisNode = new ClusterConfig(view);

                ObjectStore store = new ObjectStore(viewer);
                NodeServer server = new NodeServer(
                        viewer, keyPairs.get(viewer), realPorts.get(viewer), store, clusterViewOfThisNode);
                server.start();
                started.add(server);
                stores.put(viewer, store);
                servers.put(viewer, server);
            }
        }

        void cut(String from, String to) {
            proxyByFromTo.get(from).get(to).cut();
        }

        void setJitter(String from, String to, int maxMillis) {
            proxyByFromTo.get(from).get(to).setJitterMillis(maxMillis);
        }

        void restoreAll() {
            proxyByFromTo.values().forEach(m -> m.values().forEach(ChaosProxy::restore));
        }

        /**
         * Used only by the multi-trial test, to tear down between iterations instead of
         * accumulating open ports across all 15 of them. The single-scenario tests rely
         * on {@code @AfterEach} instead.
         */
        void tearDown() {
            servers.values().forEach(NodeServer::close);
            proxyByFromTo.values().forEach(m -> m.values().forEach(ChaosProxy::close));
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        await(condition, null);
    }

    private static void await(BooleanSupplier condition, String label) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("condition not met within " + TIMEOUT
                + (label != null ? " (" + label + ")" : ""));
    }
}
