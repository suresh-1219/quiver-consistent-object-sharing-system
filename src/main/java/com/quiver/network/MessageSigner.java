package com.quiver.network;


import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signs outgoing messages with this node's Ed25519 private key and verifies incoming
 * ones against the sender's public key.
 *
 * <p>This replaces an earlier HMAC-based scheme that used a secret shared between every
 * node. That proved cluster <em>membership</em> — "someone with a valid key sent this" —
 * but not sender identity, because verifying a message required the same secret that
 * could have forged one. Ed25519 is asymmetric: this node's own private key never
 * leaves it, and every peer verifies using only a public key, so a peer that can confirm
 * "this came from A" gains no ability to produce a message that looks like it came from
 * A. That is the actual property "authentication" is supposed to mean, and the previous
 * scheme did not have it — see the README's earlier "Security model" section for the
 * limitation this closes.
 *
 * <p>Everything else about the envelope — timestamp freshness window, nonce replay
 * cache, length-prefixed signed bytes so field boundaries can't be shifted by crafted
 * content — is unchanged from the HMAC version, because none of that was the part that
 * was weak.
 */
public final class MessageSigner {

    private static final String ALGORITHM = "Ed25519";
    private static final long MAX_CLOCK_SKEW_MILLIS = 60_000;

    /** A signed envelope. The signature covers every other field. */
    public static final class Envelope {
        public String senderNodeId;
        public long sentAtMillis;
        public String nonce;
        public String payloadJson;
        public String signature; // Base64
    }

    public static final class AuthenticationException extends Exception {
        public AuthenticationException(String message) {
            super(message);
        }
    }

    private final String selfNodeId;
    private final PrivateKey selfPrivateKey;
    private final Map<String, PublicKey> publicKeysByNodeId;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

    public MessageSigner(String selfNodeId, KeyPair selfKeyPair, Map<String, PublicKey> publicKeysByNodeId) {
        this.selfNodeId = selfNodeId;
        this.selfPrivateKey = selfKeyPair.getPrivate();
        this.publicKeysByNodeId = Map.copyOf(publicKeysByNodeId);
    }

    /** Signs {@code payloadJson} as this node. The sender is always whoever holds this signer's key. */
    public Envelope seal(String payloadJson) {
        Envelope envelope = new Envelope();
        envelope.senderNodeId = selfNodeId;
        envelope.sentAtMillis = System.currentTimeMillis();
        envelope.nonce = newNonce();
        envelope.payloadJson = payloadJson;
        envelope.signature = Base64.getEncoder().encodeToString(sign(selfPrivateKey, signedBytes(envelope)));
        return envelope;
    }

    /** @return the verified payload JSON. */
    public String open(Envelope envelope) throws AuthenticationException {
        if (envelope == null || envelope.senderNodeId == null || envelope.signature == null
                || envelope.nonce == null || envelope.payloadJson == null) {
            throw new AuthenticationException("malformed envelope");
        }
        PublicKey senderKey = publicKeysByNodeId.get(envelope.senderNodeId);
        if (senderKey == null) {
            throw new AuthenticationException("unknown node '" + envelope.senderNodeId + "'");
        }

        byte[] signatureBytes;
        try {
            signatureBytes = Base64.getDecoder().decode(envelope.signature);
        } catch (IllegalArgumentException e) {
            throw new AuthenticationException("malformed signature");
        }
        if (!verify(senderKey, signedBytes(envelope), signatureBytes)) {
            throw new AuthenticationException("bad signature from '" + envelope.senderNodeId + "'");
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

    private static byte[] sign(PrivateKey key, byte[] data) {
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(key);
            signer.update(data);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    private static boolean verify(PublicKey key, byte[] data, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key);
            verifier.update(data);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false; // a malformed signature/key fails verification, it isn't a crash
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
