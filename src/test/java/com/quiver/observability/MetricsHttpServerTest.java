package com.quiver.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Exercises the endpoint over a real socket, the same way a Prometheus server would. */
class MetricsHttpServerTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private MetricsHttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void getMetricsReturnsPrometheusTextOfTheCurrentRegistry() throws Exception {
        Metrics metrics = new Metrics();
        metrics.incCounter("quiver_writes_applied_total", "Writes accepted", Map.of());
        server = MetricsHttpServer.start(0, metrics);

        HttpResponse<String> response = get("/metrics");

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
        assertTrue(response.body().contains("quiver_writes_applied_total 1"));
    }

    @Test
    void reflectsCounterChangesMadeAfterTheServerStarted() throws Exception {
        Metrics metrics = new Metrics();
        server = MetricsHttpServer.start(0, metrics);

        metrics.incCounter("quiver_writes_applied_total", "Writes accepted", Map.of());
        metrics.incCounter("quiver_writes_applied_total", "Writes accepted", Map.of());

        assertTrue(get("/metrics").body().contains("quiver_writes_applied_total 2"),
                "the endpoint must render the live registry, not a snapshot taken at startup");
    }

    @Test
    void nonGetRequestsAreRejected() throws Exception {
        Metrics metrics = new Metrics();
        server = MetricsHttpServer.start(0, metrics);

        HttpRequest request = HttpRequest.newBuilder(uri("/metrics")).POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
    }

    @Test
    void closeStopsAcceptingConnections() throws Exception {
        Metrics metrics = new Metrics();
        server = MetricsHttpServer.start(0, metrics);
        int port = server.boundPort();

        server.close();

        assertThrows(IOException.class, () -> client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/metrics")).build(),
                HttpResponse.BodyHandlers.ofString()));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + server.boundPort() + path);
    }
}
