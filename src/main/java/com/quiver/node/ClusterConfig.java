package com.quiver.node;

import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The validated cluster membership, keyed by node id. */
public final class ClusterConfig {

    private final Map<String, NodeConfig> nodesById = new LinkedHashMap<>();

    public ClusterConfig(List<NodeConfig> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("config contains no nodes");
        }
        for (NodeConfig node : nodes) {
            node.validate();
            if (nodesById.putIfAbsent(node.nodeId, node) != null) {
                throw new IllegalArgumentException("duplicate nodeId in config: " + node.nodeId);
            }
        }
    }

    public NodeConfig require(String nodeId) {
        NodeConfig node = nodesById.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException(
                    "node '" + nodeId + "' is not in the config; known nodes: " + nodesById.keySet());
        }
        return node;
    }

    public List<NodeConfig> peersOf(String nodeId) {
        return nodesById.values().stream().filter(n -> !n.nodeId.equals(nodeId)).toList();
    }

    /** Every node's public key, decoded, keyed by node id — what a {@code MessageSigner} verifies against. */
    public Map<String, PublicKey> publicKeysByNodeId() {
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        nodesById.forEach((id, node) -> keys.put(id, NodeKeyStore.decodePublic(node.publicKey)));
        return keys;
    }

    public java.util.Set<String> nodeIds() {
        return nodesById.keySet();
    }
}
