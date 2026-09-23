package com.quiver.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MessageAuthenticatorTest {

    private static final Map<String, String> SECRETS = Map.of("A", "secret-a", "B", "secret-b");

    @Test
    void sealedMessageOpensAgain() throws Exception {
        MessageAuthenticator sender = new MessageAuthenticator(SECRETS);
        MessageAuthenticator receiver = new MessageAuthenticator(SECRETS);

        String payload = receiver.open(sender.seal("A", "{\"hello\":1}"));

        assertEquals("{\"hello\":1}", payload);
    }

    @Test
    void tamperedPayloadIsRejected() {
        MessageAuthenticator sender = new MessageAuthenticator(SECRETS);
        MessageAuthenticator receiver = new MessageAuthenticator(SECRETS);
        MessageAuthenticator.Envelope envelope = sender.seal("A", "{\"value\":\"ok\"}");

        envelope.payloadJson = "{\"value\":\"injected\"}";

        assertThrows(MessageAuthenticator.AuthenticationException.class, () -> receiver.open(envelope));
    }

    @Test
    void spoofedSenderIdIsRejected() {
        MessageAuthenticator sender = new MessageAuthenticator(SECRETS);
        MessageAuthenticator receiver = new MessageAuthenticator(SECRETS);
        MessageAuthenticator.Envelope envelope = sender.seal("A", "{}");

        envelope.senderNodeId = "B"; // MAC was computed with A's key

        assertThrows(MessageAuthenticator.AuthenticationException.class, () -> receiver.open(envelope));
    }

    @Test
    void unknownNodeIsRejected() {
        MessageAuthenticator outsider = new MessageAuthenticator(Map.of("Z", "secret-z"));
        MessageAuthenticator receiver = new MessageAuthenticator(SECRETS);

        assertThrows(MessageAuthenticator.AuthenticationException.class,
                () -> receiver.open(outsider.seal("Z", "{}")));
    }

    @Test
    void replayIsRejected() throws Exception {
        MessageAuthenticator sender = new MessageAuthenticator(SECRETS);
        MessageAuthenticator receiver = new MessageAuthenticator(SECRETS);
        MessageAuthenticator.Envelope envelope = sender.seal("A", "{}");

        receiver.open(envelope);

        assertThrows(MessageAuthenticator.AuthenticationException.class, () -> receiver.open(envelope));
    }

    @Test
    void staleMessageIsRejected() {
        MessageAuthenticator sender = new MessageAuthenticator(SECRETS);
        MessageAuthenticator receiver = new MessageAuthenticator(SECRETS);
        MessageAuthenticator.Envelope envelope = sender.seal("A", "{}");

        envelope.sentAtMillis -= 10 * 60_000; // ten minutes ago

        assertThrows(MessageAuthenticator.AuthenticationException.class, () -> receiver.open(envelope));
    }
}
