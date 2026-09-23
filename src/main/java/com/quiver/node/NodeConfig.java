package com.quiver.node;

/** One node's entry in {@code config.json}. */
public final class NodeConfig {

    public String nodeId;
    public String host;
    public int port;
    /** Pre-shared HMAC key. See README "Security model" before using in anger. */
    public String secret;

    public NodeConfig() {
    }

    public NodeConfig(String nodeId, String host, int port, String secret) {
        this.nodeId = nodeId;
        this.host = host;
        this.port = port;
        this.secret = secret;
    }

    public void validate() {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("config entry missing nodeId");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("node '" + nodeId + "' missing host");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("node '" + nodeId + "' has invalid port " + port);
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("node '" + nodeId + "' missing secret");
        }
    }

    @Override
    public String toString() {
        return nodeId + "@" + host + ":" + port;
    }
}
