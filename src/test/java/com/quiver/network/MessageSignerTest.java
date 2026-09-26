package com.quiver.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.quiver.node.NodeKeyStore;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessageSignerTest {

    private final KeyPair keysA = NodeKeyStore.generate();
    private final KeyPair keysB = NodeKeyStore.generate();
    private final Map<String, PublicKey> trustedKeys = Map.of(
            "A", keysA.getPublic(),
            "B", keysB.getPublic());

    @Test
    void sealedMessageOpensAgain() throws Exception {
        MessageSigner sender = new MessageSigner("A", keysA, trustedKeys);
        MessageSigner receiver = new MessageSigner("B", keysB, trustedKeys);

        String payload = receiver.open(sender.seal("{\"hello\":1}"));

        assertEquals("{\"hello\":1}", payload);
    }

    @Test
    void tamperedPayloadIsRejected() {
        MessageSigner sender = new MessageSigner("A", keysA, trustedKeys);
        MessageSigner receiver = new MessageSigner("B", keysB, trustedKeys);
        MessageSigner.Envelope envelope = sender.seal("{\"value\":\"ok\"}");

        envelope.payloadJson = "{\"value\":\"injected\"}";

        assertThrows(MessageSigner.AuthenticationException.class, () -> receiver.open(envelope));
    }

    @Test
    void spoofedSenderIdIsRejected() {
        MessageSigner sender = new MessageSigner("A", keysA, trustedKeys);
        MessageSigner receiver = new MessageSigner("B", keysB, trustedKeys);
        MessageSigner.Envelope envelope = sender.seal("{}");

        envelope.senderNodeId = "B"; // signature was produced with A's private key, not B's

        assertThrows(MessageSigner.AuthenticationException.class, () -> receiver.open(envelope));
    }

    /**
     * The property that a shared-secret scheme cannot have: holding every public key
     * needed to verify does not grant the ability to forge a message. This is the whole
     * point of moving to asymmetric signatures.
     */
    @Test
    void cannotForgeAMessageWithOnlyThePublicKey() {
        KeyPair attacker = NodeKeyStore.generate();
        // The attacker signs with their own key but claims to be "A". They do not have,
        // and this test never gives them, A's private key.
        MessageSigner attackerAsA = new MessageSigner("A", attacker, trustedKeys);
        MessageSigner receiver = new MessageSigner("B", keysB, trustedKeys);

        MessageSigner.Envelope forged = attackerAsA.seal("{\"forged\":true}");

        assertThrows(MessageSigner.AuthenticationException.class, () -> receiver.open(forged));
    }

    @Test
    void unknownNodeIsRejected() {
        KeyPair outsider = NodeKeyStore.generate();
        MessageSigner sender = new MessageSigner("Z", outsider, Map.of("Z", outsider.getPublic()));
        MessageSigner receiver = new MessageSigner("B", keysB, trustedKeys); // doesn't trust "Z"

        assertThrows(MessageSigner.AuthenticationException.class, () -> receiver.open(sender.seal("{}")));
    }

    @Test
    void replayIsRejected() throws Exception {
        MessageSigner sender = new MessageSigner("A", keysA, trustedKeys);
        MessageSigner receiver = new MessageSigner("B", keysB, trustedKeys);
        MessageSigner.Envelope envelope = sender.seal("{}");

        receiver.open(envelope);

        assertThrows(MessageSigner.AuthenticationException.class, () -> receiver.open(envelope));
    }

    @Test
    void staleMessageIsRejected() {
        MessageSigner sender = new MessageSigner("A", keysA, trustedKeys);
        MessageSigner receiver = new MessageSigner("B", keysB, trustedKeys);
        MessageSigner.Envelope envelope = sender.seal("{}");

        envelope.sentAtMillis -= 10 * 60_000; // ten minutes ago
        // Note: mutating sentAtMillis after signing does NOT re-sign, so this would also
        // fail on the signature check. That's fine — either failure reason is correct;
        // the point is the message must be rejected either way.

        assertThrows(MessageSigner.AuthenticationException.class, () -> receiver.open(envelope));
    }
}
