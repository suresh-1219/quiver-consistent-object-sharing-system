package com.quiver.node;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Loads cluster membership.
 *
 * <p>The previous version read {@code "config.json"} relative to the working directory
 * only. Since the file actually lives in {@code src/main/resources} (and inside the jar),
 * following the README meant every node silently fell back to hardcoded ports and
 * config-driven discovery never ran. Resolution order is now explicit, and a missing or
 * malformed config is a hard failure rather than a silent downgrade.
 */
public final class ConfigLoader {

    private static final String DEFAULT_RESOURCE = "/config.json";

    private ConfigLoader() {
    }

    public static final class ConfigException extends RuntimeException {
        public ConfigException(String message, Throwable cause) {
            super(message, cause);
        }

        public ConfigException(String message) {
            super(message);
        }
    }

    /**
     * @param explicitPath value of {@code --config=...}, or null to search the defaults:
     *                     {@code ./config.json}, then the bundled classpath resource.
     */
    public static ClusterConfig load(String explicitPath) {
        if (explicitPath != null && !explicitPath.isBlank()) {
            Path path = Path.of(explicitPath);
            if (!Files.isReadable(path)) {
                throw new ConfigException("Config file not readable: " + path.toAbsolutePath());
            }
            return parse(path.toString(), readFile(path));
        }

        Path workingDirCopy = Path.of("config.json");
        if (Files.isReadable(workingDirCopy)) {
            return parse(workingDirCopy.toAbsolutePath().toString(), readFile(workingDirCopy));
        }

        try (InputStream in = ConfigLoader.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new ConfigException("No config.json on the classpath and none in "
                        + Path.of("").toAbsolutePath() + "; pass --config=<path>");
            }
            return parse("classpath:" + DEFAULT_RESOURCE,
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ConfigException("Could not read bundled config.json", e);
        }
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException("Could not read config file " + path.toAbsolutePath(), e);
        }
    }

    private static ClusterConfig parse(String source, String json) {
        Type mapType = new TypeToken<Map<String, List<NodeConfig>>>() { }.getType();
        Map<String, List<NodeConfig>> data;
        try {
            data = new Gson().fromJson(json, mapType);
        } catch (JsonSyntaxException e) {
            throw new ConfigException("Malformed JSON in " + source, e);
        }
        if (data == null || data.get("nodes") == null) {
            throw new ConfigException("Config " + source + " has no 'nodes' array");
        }
        try {
            return new ClusterConfig(data.get("nodes"));
        } catch (IllegalArgumentException | NodeKeyStore.KeyStoreException e) {
            throw new ConfigException("Invalid config in " + source + ": " + e.getMessage(), e);
        }
    }
}
