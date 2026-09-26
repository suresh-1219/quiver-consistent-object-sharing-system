package com.quiver.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

    // Real Ed25519 public keys (X.509 SubjectPublicKeyInfo, base64) — arbitrary for these
    // parsing tests, but they must be *valid* keys so validate() doesn't reject them for
    // the wrong reason.
    private static final String PUB_A = "MCowBQYDK2VwAyEAOh+RtiIhe+ieGEaMWa1kGHOIxCyrIhWINpGt+Iky5l0=";
    private static final String PUB_B = "MCowBQYDK2VwAyEAY6phr2VMX1fW5SIAWoNBfMTRSXvPi/t7CDwGwUXvwJg=";

    private static final String VALID = """
            { "nodes": [
              { "nodeId": "A", "host": "127.0.0.1", "port": 9001, "publicKey": "%s" },
              { "nodeId": "B", "host": "127.0.0.1", "port": 9002, "publicKey": "%s" }
            ] }
            """.formatted(PUB_A, PUB_B);

    @Test
    void loadsExplicitPath(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("config.json"), VALID);

        ClusterConfig config = ConfigLoader.load(file.toString());

        assertEquals(List.of("B"), config.peersOf("A").stream().map(n -> n.nodeId).toList());
        assertEquals(9001, config.require("A").port);
    }

    /** The bundled resource must load, because the README tells users to rely on it. */
    @Test
    void fallsBackToTheBundledResource() {
        ClusterConfig config = ConfigLoader.load(null);

        assertEquals(2, config.peersOf("A").size());
    }

    @Test
    void missingFileIsAHardFailureNotASilentFallback(@TempDir Path dir) {
        Path missing = dir.resolve("nope.json");

        assertThrows(ConfigLoader.ConfigException.class, () -> ConfigLoader.load(missing.toString()));
    }

    @Test
    void malformedJsonIsRejected(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("config.json"), "{ not json ");

        assertThrows(ConfigLoader.ConfigException.class, () -> ConfigLoader.load(file.toString()));
    }

    @Test
    void nodeWithoutPublicKeyIsRejected(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("config.json"),
                """
                { "nodes": [ { "nodeId": "A", "host": "127.0.0.1", "port": 9001 } ] }
                """);

        assertThrows(ConfigLoader.ConfigException.class, () -> ConfigLoader.load(file.toString()));
    }

    /** Regression: a config-time check should catch a broken key, not the first message this node verifies. */
    @Test
    void malformedPublicKeyIsRejectedAtLoadTime(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("config.json"),
                """
                { "nodes": [
                  { "nodeId": "A", "host": "127.0.0.1", "port": 9001, "publicKey": "not-a-real-key" }
                ] }
                """);

        assertThrows(ConfigLoader.ConfigException.class, () -> ConfigLoader.load(file.toString()));
    }

    @Test
    void duplicateNodeIdIsRejected(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("config.json"),
                """
                { "nodes": [
                  { "nodeId": "A", "host": "h", "port": 1, "publicKey": "%s" },
                  { "nodeId": "A", "host": "h", "port": 2, "publicKey": "%s" }
                ] }
                """.formatted(PUB_A, PUB_A));

        assertThrows(ConfigLoader.ConfigException.class, () -> ConfigLoader.load(file.toString()));
    }

    @Test
    void unknownNodeIdIsReportedClearly(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("config.json"), VALID);
        ClusterConfig config = ConfigLoader.load(file.toString());

        assertThrows(IllegalArgumentException.class, () -> config.require("Z"));
    }
}
