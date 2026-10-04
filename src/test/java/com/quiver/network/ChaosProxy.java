package com.quiver.network;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A TCP proxy for tests that need to simulate a network fault between two Quiver nodes
 * without touching any production code.
 *
 * <p>A node finds a peer via a plain host:port entry in its {@link
 * com.quiver.node.ClusterConfig}. Point that entry at this proxy's port instead of the
 * peer's real one, and every byte is transparently forwarded in both directions — until
 * {@link #cut()} is called, after which new connection attempts are accepted and
 * immediately dropped (indistinguishable, from {@link NodeServer#send}'s point of view,
 * from any other connection failure it already has to handle). {@link #restore()} undoes
 * that. {@link #setJitterMillis} adds a random per-connection delay, for tests about
 * message reordering rather than outright loss.
 *
 * <p>This is deliberately the same technique a tool like Toxiproxy uses — a controllable
 * proxy sitting on the wire — just small enough to have no dependency of its own and to
 * live entirely in the test tree. Because it works purely at the socket level, every
 * fault it introduces is caught by whatever error handling {@link NodeServer} already
 * has for a real unreachable peer; chaos tests exercise that existing code path under
 * worse conditions, they don't add a new one.
 */
final class ChaosProxy implements AutoCloseable {

    private final ServerSocket listener;
    private final String targetHost;
    private final int targetPort;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "chaos-proxy");
        t.setDaemon(true);
        return t;
    });
    private final List<Socket> openSockets = new CopyOnWriteArrayList<>();
    private final AtomicBoolean up = new AtomicBoolean(true);
    private final AtomicInteger jitterMillis = new AtomicInteger(0);
    private final Random random = new Random();

    private ChaosProxy(ServerSocket listener, String targetHost, int targetPort) {
        this.listener = listener;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    /** Binds synchronously, so {@link #port()} is valid the instant this returns. */
    static ChaosProxy between(String targetHost, int targetPort) throws IOException {
        ServerSocket listener = new ServerSocket(0);
        ChaosProxy proxy = new ChaosProxy(listener, targetHost, targetPort);
        Thread acceptThread = new Thread(proxy::acceptLoop, "chaos-proxy-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return proxy;
    }

    int port() {
        return listener.getLocalPort();
    }

    /** Simulates a partition: kills every open connection now, and refuses new ones. */
    void cut() {
        up.set(false);
        for (Socket s : openSockets) {
            closeQuietly(s);
        }
        openSockets.clear();
    }

    /** Heals the partition: connections are accepted and forwarded again. */
    void restore() {
        up.set(true);
    }

    /** Each new connection is delayed by a random amount in {@code [0, maxMillis]}. */
    void setJitterMillis(int maxMillis) {
        jitterMillis.set(maxMillis);
    }

    private void acceptLoop() {
        while (!listener.isClosed()) {
            Socket client;
            try {
                client = listener.accept();
            } catch (IOException e) {
                return; // listener closed; normal shutdown
            }
            if (!up.get()) {
                closeQuietly(client); // TCP handshake succeeded, then an immediate hang-up
                continue;
            }
            openSockets.add(client);
            pool.submit(() -> relay(client));
        }
    }

    private void relay(Socket client) {
        int delay = jitterMillis.get() > 0 ? random.nextInt(jitterMillis.get() + 1) : 0;
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                closeQuietly(client);
                return;
            }
        }
        try {
            Socket target = new Socket();
            target.connect(new InetSocketAddress(targetHost, targetPort), 2000);
            openSockets.add(target);
            Thread reverse = new Thread(() -> pipe(target, client), "chaos-proxy-return");
            reverse.setDaemon(true);
            reverse.start();
            pipe(client, target);
        } catch (IOException e) {
            closeQuietly(client);
        }
    }

    private void pipe(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // the other end closing mid-message is normal, not a bug in the proxy
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }

    @Override
    public void close() {
        cut();
        try {
            listener.close();
        } catch (IOException ignored) {
            // best-effort
        }
        pool.shutdownNow();
    }
}
