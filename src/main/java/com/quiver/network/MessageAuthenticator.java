package com.quiver.network;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authenticates messages between peers with HMAC-SHA256 over a pre-shared per-node key.
 *
 * <p>Without this, {@code senderNodeId} was a self-declared string: anyone who could open
 * a socket could claim to be any node and write to any object.
 *
 * <p>Scope, stated plainly: symmetric keys prove cluster membership and message
 * integrity, and replay is bounded by a timestamp window plus a nonce cache. They do not
 * give non-repudiation — a node holding peer keys in order to verify them could also
 * forge messages from those peers. Per-node key pairs (Ed25519) or mTLS is the next step;
 * see README "Security model".
 */
public final class MessageAuthenticator {

    private static final String ALGORITHM = "HmacSHA256";
    private static final long MAX_CLOCK_SKEW_MILLIS = 60_000;

    /** An authenticated envelope. The MAC covers every other field. */
    public static final class Envelope {
        public String senderNodeId;
        public long sentAtMillis;
        public String nonce;
        public String payloadJson;
        public String mac;
    }

    public static final class AuthenticationException extends Exception {
        public AuthenticationException(String message) {
            super(message);
        }
    }

    private final Map<String, byte[]> keysByNodeId;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

    public MessageAuthenticator(Map<String, String> secretsByNodeId) {
        Map<String, byte[]> keys = new ConcurrentHashMap<>();
        secretsByNodeId.forEach((node, secret) -> keys.put(node, secret.getBytes(StandardCharsets.UTF_8)));
        this.keysByNodeId = keys;
    }

    public Envelope seal(String senderNodeId, String payloadJson) {
        byte[] key = keyFor(senderNodeId);
        Envelope envelope = new Envelope();
        envelope.senderNodeId = senderNodeId;
        envelope.sentAtMillis = System.currentTimeMillis();
        envelope.nonce = newNonce();
        envelope.payloadJson = payloadJson;
        envelope.mac = HexFormat.of().formatHex(hmac(key, signedBytes(envelope)));
        return envelope;
    }

    /** @return the verified payload JSON. */
    public String open(Envelope envelope) throws AuthenticationException {
        if (envelope == null || envelope.senderNodeId == null || envelope.mac == null
                || envelope.nonce == null || envelope.payloadJson == null) {
            throw new AuthenticationException("malformed envelope");
        }
        byte[] key = keysByNodeId.get(envelope.senderNodeId);
        if (key == null) {
            throw new AuthenticationException("unknown node '" + envelope.senderNodeId + "'");
        }

        byte[] expected = hmac(key, signedBytes(envelope));
        byte[] presented;
        try {
            presented = HexFormat.of().parseHex(envelope.mac);
        } catch (IllegalArgumentException e) {
            throw new AuthenticationException("malformed MAC");
        }
        // Constant-time: a length-sensitive or short-circuiting compare leaks the MAC.
        if (!MessageDigest.isEqual(expected, presented)) {
            throw new AuthenticationException("bad MAC from '" + envelope.senderNodeId + "'");
        }

        long now = System.currentTimeMillis();
        if (Math.abs(now - envelope.sentAtMillis) > MAX_CLOCK_SKEW_MILLIS) {
            throw new AuthenticationException("stale or future-dated message");
        }
        pruneNonces(now);
        if (seenNonces.putIfAbsent(envelope.nonce, now + MAX_CLOCK_SKEW_MILLIS) != null) {
            throw new AuthenticationException("replayed nonce");
        }
        return envelope.payloadJson;
    }

    private byte[] keyFor(String nodeId) {
        byte[] key = keysByNodeId.get(nodeId);
        if (key == null) {
            throw new IllegalStateException("No shared secret configured for node '" + nodeId + "'");
        }
        return key;
    }

    private static byte[] signedBytes(Envelope envelope) {
        // Length-prefixed so that field boundaries cannot be shifted by crafted content.
        StringBuilder sb = new StringBuilder();
        appendField(sb, envelope.senderNodeId);
        appendField(sb, Long.toString(envelope.sentAtMillis));
        appendField(sb, envelope.nonce);
        appendField(sb, envelope.payloadJson);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendField(StringBuilder sb, String field) {
        sb.append(field.length()).append(':').append(field).append('|');
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private String newNonce() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void pruneNonces(long now) {
        seenNonces.entrySet().removeIf(e -> e.getValue() < now);
    }
}
