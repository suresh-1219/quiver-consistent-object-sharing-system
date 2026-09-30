package com.quiver.observability;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serves {@link Metrics#renderPrometheusText()} over plain HTTP on {@code GET /metrics}.
 *
 * <p>Built on {@code com.sun.net.httpserver}, part of the JDK itself since Java 6 (the
 * {@code jdk.httpserver} module) — not a new dependency, and not something a project
 * this size needs a full web framework for. A real Prometheus server's scrape config
 * just needs an HTTP GET that returns text in the exposition format; that's all this is.
 *
 * <p>Deliberately minimal: no HTTPS, no auth, no other paths. Metrics endpoints are
 * conventionally left unauthenticated and scraped from a trusted network — the same
 * assumption Prometheus itself makes by default.
 */
public final class MetricsHttpServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(MetricsHttpServer.class.getName());
    private static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private final HttpServer server;

    private MetricsHttpServer(HttpServer server) {
        this.server = server;
    }

    public static MetricsHttpServer start(int port, Metrics metrics) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/metrics", exchange -> handle(exchange, metrics));
        // A single background thread is plenty: scrapes are infrequent (typically every
        // 15-60s) and rendering the registry is fast, in-memory work.
        server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread t = new Thread(runnable, "quiver-metrics-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        LOG.info(() -> "metrics endpoint listening on http://localhost:" + port + "/metrics");
        return new MetricsHttpServer(server);
    }

    private static void handle(HttpExchange exchange, Metrics metrics) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] body = metrics.renderPrometheusText().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", CONTENT_TYPE);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (RuntimeException e) {
            // A broken metric must never take the endpoint itself down.
            LOG.log(Level.WARNING, "error rendering metrics", e);
            exchange.sendResponseHeaders(500, -1);
        } finally {
            exchange.close();
        }
    }

    public int boundPort() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
