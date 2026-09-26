package com.quiver.node;

/**
 * One node's entry in {@code config.json}.
 *
 * <p>{@code publicKey} is exactly that — public. It's how every peer verifies messages
 * claiming to come from this node, and it is meant to be shared and committed, the way
 * an SSH {@code known_hosts} entry is. The matching private key never appears here; see
 * {@link NodeKeyStore}.
 */
public final class NodeConfig {

    public String nodeId;
    public String host;
    public int port;
    public String publicKey;

    public NodeConfig() {
    }

    public NodeConfig(String nodeId, String host, int port, String publicKey) {
        this.nodeId = nodeId;
        this.host = host;
        this.port = port;
        this.publicKey = publicKey;
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
        if (publicKey == null || publicKey.isBlank()) {
            throw new IllegalArgumentException("node '" + nodeId + "' missing publicKey");
        }
        // Fail fast on a malformed key at config-load time rather than at the first
        // message this node ever tries to verify.
        NodeKeyStore.decodePublic(publicKey);
    }

    @Override
    public String toString() {
        return nodeId + "@" + host + ":" + port;
    }
}
