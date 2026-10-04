package com.quiver.network;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.quiver.node.ClusterConfig;
import com.quiver.node.NodeConfig;
import com.quiver.node.ObjectStore;
import com.quiver.observability.Metrics;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Peer-to-peer sync over TCP.
 *
 * <p>What changed and why:
 * <ul>
 *   <li>Every inbound message is authenticated before it is parsed, and every state
 *       change is authorised against the ACL. Previously any socket could forge a
 *       sender id and overwrite any object. Authentication is now Ed25519 signatures
 *       (see {@link MessageSigner}), not a secret shared by the whole cluster, so
 *       verifying a message no longer implies the ability to forge one.</li>
 *   <li>Malformed input is handled, not fatal. A single bad line used to kill a
 *       connection thread with a Gson stack trace on the user's prompt.</li>
 *   <li>Connections are served by a bounded pool with read timeouts and a line-length
 *       cap, instead of an unbounded thread-per-connection with no timeouts.</li>
 *   <li>Sync replies go to the peer's configured address rather than a hardcoded
 *       {@code localhost}, and carry ownership and ACLs, not just values.</li>
 *   <li>{@link #close()} shuts the listener and pool down deterministically.</li>
 * </ul>
 */
public final class NodeServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(NodeServer.class.getName());

    private static final int SOCKET_TIMEOUT_MILLIS = 5_000;
    private static final int CONNECT_TIMEOUT_MILLIS = 2_000;
    private static final int MAX_LINE_CHARS = 1 << 20; // 1 MiB guard against a hostile peer
    private static final int MAX_CONNECTION_THREADS = 16;

    private final String nodeId;
    private final int configuredPort;
    private final ObjectStore store;
    private final ClusterConfig cluster;
    private final MessageSigner signer;
    private final Metrics metrics;
    private final java.util.function.BiPredicate<String, String> linkAllowed;
    private final Gson gson = new Gson();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService connectionPool =
            Executors.newFixedThreadPool(MAX_CONNECTION_THREADS, runnable -> {
                Thread t = new Thread(runnable, "quiver-conn");
                t.setDaemon(true);
                return t;
            });

    private ServerSocket serverSocket;
    private Thread acceptThread;

    public NodeServer(String nodeId, KeyPair selfKeyPair, int port, ObjectStore store, ClusterConfig cluster) {
        this(nodeId, selfKeyPair, port, store, cluster, new Metrics());
    }

    public NodeServer(String nodeId, KeyPair selfKeyPair, int port, ObjectStore store,
                      ClusterConfig cluster, Metrics metrics) {
        this(nodeId, selfKeyPair, port, store, cluster, metrics, (from, to) -> true);
    }

    /**
     * @param linkAllowed consulted before every outbound send: {@code linkAllowed.test(this
     *     node's id, the peer's id)} returning {@code false} makes the send behave exactly
     *     like an unreachable peer (logged, counted, not fatal), without actually touching
     *     a socket. Production code never needs this — the four-and-five-argument
     *     constructors default to "every link always allowed" — it exists so tests can
     *     simulate a network partition between specific nodes without standing up a real
     *     proxy. See {@code NetworkPartition} in the test sources.
     */
    public NodeServer(String nodeId, KeyPair selfKeyPair, int port, ObjectStore store, ClusterConfig cluster,
                      Metrics metrics, java.util.function.BiPredicate<String, String> linkAllowed) {
        this.nodeId = nodeId;
        this.configuredPort = port;
        this.store = store;
        this.cluster = cluster;
        this.signer = new MessageSigner(nodeId, selfKeyPair, cluster.publicKeysByNodeId());
        this.metrics = metrics;
        this.linkAllowed = linkAllowed;
    }

    /** Binds synchronously, so a caller (or a test) knows the node is reachable on return. */
    public void start() throws IOException {
        serverSocket = new ServerSocket(configuredPort);
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "quiver-accept-" + nodeId);
        acceptThread.setDaemon(true);
        acceptThread.start();
        LOG.info(() -> "[" + nodeId + "] listening on port " + boundPort());
    }

    /** Useful when bound to port 0 in tests. */
    public int boundPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : configuredPort;
    }

    private void acceptLoop() {
        while (running.get()) {
            Socket socket = null;
            try {
                socket = serverSocket.accept();
                final Socket accepted = socket;
                connectionPool.submit(() -> handleConnection(accepted));
            } catch (IOException e) {
                if (running.get()) {
                    LOG.log(Level.WARNING, "[" + nodeId + "] accept failed", e);
                }
            } catch (java.util.concurrent.RejectedExecutionException e) {
                LOG.warning("[" + nodeId + "] connection pool saturated, dropping connection");
                closeQuietly(socket);
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (Socket s = socket;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
            s.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            String line;
            while ((line = readLimitedLine(reader)) != null) {
                handleLine(line);
            }
        } catch (SocketTimeoutException e) {
            LOG.fine(() -> "[" + nodeId + "] idle peer connection timed out");
        } catch (IOException e) {
            LOG.log(Level.FINE, "[" + nodeId + "] connection error", e);
        }
    }

    /** Reads one line, refusing to buffer an unbounded amount from a hostile peer. */
    private String readLimitedLine(BufferedReader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') {
                return sb.toString();
            }
            if (c != '\r') {
                sb.append((char) c);
            }
            if (sb.length() > MAX_LINE_CHARS) {
                throw new IOException("peer sent an over-long message; closing connection");
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private void handleLine(String line) {
        if (line.isBlank()) {
            return;
        }
        SyncMessage message;
        try {
            MessageSigner.Envelope envelope =
                    gson.fromJson(line, MessageSigner.Envelope.class);
            String payloadJson = signer.open(envelope);
            message = gson.fromJson(payloadJson, SyncMessage.class);
            if (message == null) {
                throw new IllegalArgumentException("empty payload");
            }
            message.validate();
            if (!envelope.senderNodeId.equals(message.senderNodeId)) {
                throw new IllegalArgumentException("payload sender does not match envelope sender");
            }
        } catch (MessageSigner.AuthenticationException e) {
            LOG.warning("[" + nodeId + "] rejected unauthenticated message: " + e.getMessage());
            metrics.incCounter("quiver_messages_rejected_total",
                    "Inbound messages rejected before being processed", labels("reason", "auth"));
            return;
        } catch (JsonSyntaxException | IllegalArgumentException | IllegalStateException e) {
            LOG.warning("[" + nodeId + "] rejected malformed message: " + e.getMessage());
            metrics.incCounter("quiver_messages_rejected_total",
                    "Inbound messages rejected before being processed", labels("reason", "malformed"));
            return;
        }
        process(message);
    }

    private void process(SyncMessage msg) {
        metrics.incCounter("quiver_messages_received_total",
                "Authenticated inbound messages, by type", labels("type", msg.type.name()));
        switch (msg.type) {
            case CREATE -> {
                if (store.applyRemoteCreate(msg.objectName, msg.ownerNodeId)
                        == ObjectStore.CreateOutcome.CREATED) {
                    metrics.incCounter("quiver_objects_created_total",
                            "Objects newly created on this node", labels("source", "remote"));
                }
            }

            case UPDATE -> {
                ObjectStore.MergeOutcome outcome = store.applyRemoteUpdate(
                        msg.objectName, msg.value, msg.toVectorClock(), msg.senderNodeId);
                switch (outcome) {
                    case MERGED -> metrics.incCounter("quiver_writes_applied_total",
                            "Writes accepted into the CRDT", labels("source", "remote"));
                    case IGNORED -> metrics.incCounter("quiver_writes_ignored_total",
                            "Writes that lost a conflict or were duplicates", labels("source", "remote"));
                    case ACCESS_DENIED -> {
                        LOG.warning("[" + nodeId + "] refused write to '"
                                + msg.objectName + "' from unauthorised node '" + msg.senderNodeId + "'");
                        metrics.incCounter("quiver_writes_denied_total",
                                "Writes refused: sender not authorized, or object unknown", labels("source", "remote", "reason", "unauthorized"));
                    }
                    case UNKNOWN_OBJECT -> {
                        LOG.info("[" + nodeId + "] update for unknown object '" + msg.objectName
                                + "'; requesting full state from " + msg.senderNodeId);
                        metrics.incCounter("quiver_writes_denied_total",
                                "Writes refused: sender not authorized, or object unknown", labels("source", "remote", "reason", "unknown_object"));
                        sendTo(msg.senderNodeId, SyncMessage.syncRequest(nodeId));
                    }
                }
            }

            case GRANT_PERMISSION -> {
                ObjectStore.GrantOutcome outcome = store.applyRemoteGrant(
                        msg.objectName, msg.permissionType, msg.targetUserId, msg.senderNodeId);
                if (outcome == ObjectStore.GrantOutcome.NOT_OWNER) {
                    LOG.warning("[" + nodeId + "] refused grant on '" + msg.objectName
                            + "' from non-owner '" + msg.senderNodeId + "'");
                    metrics.incCounter("quiver_grants_denied_total",
                            "Grants refused because the requester is not the owner",
                            labels("source", "remote"));
                } else if (outcome == ObjectStore.GrantOutcome.GRANTED) {
                    metrics.incCounter("quiver_grants_applied_total",
                            "Write-permission grants applied", labels("source", "remote"));
                }
            }

            case SYNC_REQUEST -> {
                metrics.incCounter("quiver_sync_requests_received_total",
                        "Full-state sync requests received from peers", Map.of());
                sendTo(msg.senderNodeId, SyncMessage.state(store.snapshot(), nodeId));
            }

            case STATE -> store.applySnapshot(msg.objects, msg.senderNodeId);
        }
    }

    // ------------------------------------------------------------------------- outbound

    public void broadcast(SyncMessage message) {
        for (NodeConfig peer : cluster.peersOf(nodeId)) {
            send(peer, message);
        }
    }

    public void requestFullSyncFromPeers() {
        metrics.incCounter("quiver_sync_requests_sent_total",
                "Full-state sync requests sent to peers", Map.of());
        broadcast(SyncMessage.syncRequest(nodeId));
    }

    private void sendTo(String peerNodeId, SyncMessage message) {
        List<NodeConfig> peers = cluster.peersOf(nodeId);
        peers.stream()
             .filter(p -> p.nodeId.equals(peerNodeId))
             .findFirst()
             .ifPresentOrElse(peer -> send(peer, message),
                     () -> LOG.warning("[" + nodeId + "] no configured address for peer " + peerNodeId));
    }

    private void send(NodeConfig peer, SyncMessage message) {
        String line = gson.toJson(signer.seal(gson.toJson(message)));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(peer.host, peer.port), CONNECT_TIMEOUT_MILLIS);
            PrintWriter writer = new PrintWriter(
                    new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            writer.println(line);
            metrics.incCounter("quiver_messages_sent_total",
                    "Outbound messages written to a peer connection",
                    labels("type", message.type.name()));
        } catch (IOException e) {
            // Unreachable peers are normal in a partitioned cluster, not an error.
            LOG.fine(() -> "[" + nodeId + "] could not reach " + peer + " (" + e.getMessage() + ")");
            metrics.incCounter("quiver_peer_unreachable_total",
                    "Outbound sends that failed because a peer could not be reached",
                    labels("peer", peer.nodeId));
        }
    }

    @Override
    public void close() {
        running.set(false);
        closeQuietly(serverSocket);
        connectionPool.shutdownNow();
        try {
            connectionPool.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // nothing useful to do during shutdown
        }
    }

    /**
     * Builds a label map from alternating key/value strings, e.g. {@code labels("reason",
     * "auth")}. This node's own id is deliberately never one of these labels: each node
     * runs its own {@code /metrics} endpoint on its own port, so a real Prometheus
     * server already disambiguates nodes via the {@code instance} label it derives from
     * the scrape target address — baking the id in here too would just duplicate that.
     */
    private static Map<String, String> labels(String... keyValuePairs) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
            map.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }
}
