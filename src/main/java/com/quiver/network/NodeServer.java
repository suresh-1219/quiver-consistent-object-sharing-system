package com.quiver.network;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.quiver.node.ClusterConfig;
import com.quiver.node.NodeConfig;
import com.quiver.node.ObjectStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 *       sender id and overwrite any object.</li>
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
    private final MessageAuthenticator authenticator;
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

    public NodeServer(String nodeId, int port, ObjectStore store, ClusterConfig cluster) {
        this.nodeId = nodeId;
        this.configuredPort = port;
        this.store = store;
        this.cluster = cluster;
        this.authenticator = new MessageAuthenticator(cluster.secretsByNodeId());
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
            MessageAuthenticator.Envelope envelope =
                    gson.fromJson(line, MessageAuthenticator.Envelope.class);
            String payloadJson = authenticator.open(envelope);
            message = gson.fromJson(payloadJson, SyncMessage.class);
            if (message == null) {
                throw new IllegalArgumentException("empty payload");
            }
            message.validate();
            if (!envelope.senderNodeId.equals(message.senderNodeId)) {
                throw new IllegalArgumentException("payload sender does not match envelope sender");
            }
        } catch (MessageAuthenticator.AuthenticationException e) {
            LOG.warning("[" + nodeId + "] rejected unauthenticated message: " + e.getMessage());
            return;
        } catch (JsonSyntaxException | IllegalArgumentException | IllegalStateException e) {
            LOG.warning("[" + nodeId + "] rejected malformed message: " + e.getMessage());
            return;
        }
        process(message);
    }

    private void process(SyncMessage msg) {
        switch (msg.type) {
            case CREATE -> store.applyRemoteCreate(msg.objectName, msg.ownerNodeId);

            case UPDATE -> {
                ObjectStore.MergeOutcome outcome = store.applyRemoteUpdate(
                        msg.objectName, msg.value, msg.toVectorClock(), msg.senderNodeId);
                switch (outcome) {
                    case ACCESS_DENIED -> LOG.warning("[" + nodeId + "] refused write to '"
                            + msg.objectName + "' from unauthorised node '" + msg.senderNodeId + "'");
                    case UNKNOWN_OBJECT -> {
                        LOG.info("[" + nodeId + "] update for unknown object '" + msg.objectName
                                + "'; requesting full state from " + msg.senderNodeId);
                        sendTo(msg.senderNodeId, SyncMessage.syncRequest(nodeId));
                    }
                    default -> LOG.fine(() -> "[" + nodeId + "] " + outcome + " '" + msg.objectName + "'");
                }
            }

            case GRANT_PERMISSION -> {
                ObjectStore.GrantOutcome outcome = store.applyRemoteGrant(
                        msg.objectName, msg.permissionType, msg.targetUserId, msg.senderNodeId);
                if (outcome == ObjectStore.GrantOutcome.NOT_OWNER) {
                    LOG.warning("[" + nodeId + "] refused grant on '" + msg.objectName
                            + "' from non-owner '" + msg.senderNodeId + "'");
                }
            }

            case SYNC_REQUEST -> sendTo(msg.senderNodeId, SyncMessage.state(store.snapshot(), nodeId));

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
        String line = gson.toJson(authenticator.seal(nodeId, gson.toJson(message)));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(peer.host, peer.port), CONNECT_TIMEOUT_MILLIS);
            PrintWriter writer = new PrintWriter(
                    new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            writer.println(line);
        } catch (IOException e) {
            // Unreachable peers are normal in a partitioned cluster, not an error.
            LOG.fine(() -> "[" + nodeId + "] could not reach " + peer + " (" + e.getMessage() + ")");
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
}
