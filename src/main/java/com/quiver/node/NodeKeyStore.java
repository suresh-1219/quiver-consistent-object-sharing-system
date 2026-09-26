package com.quiver.node;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;

/**
 * Resolves a node's Ed25519 identity and encodes/decodes keys for {@code config.json}.
 *
 * <p>This is the piece that actually closes the gap the README used to name as a known
 * limitation: a shared HMAC secret proves cluster <em>membership</em>, not which specific
 * node sent a message, because anyone who can verify a message with that secret could
 * also have forged it. Ed25519 is asymmetric: verifying a signature only requires the
 * <em>public</em> key, so a peer can confirm "this came from A" without ever holding
 * anything that would let it forge a message as A.
 *
 * <p>What goes where mirrors the shape of {@link ConfigLoader}, deliberately: {@code
 * config.json} is shared, non-secret, and safe to commit — it lists everyone's public
 * key, the way an SSH {@code known_hosts} file does. A node's private key is exactly the
 * opposite: local, secret, and never appears in {@code config.json}. Resolution order for
 * the private key is explicit path, then a file in the data directory, then a bundled
 * demo identity (see the loud warning on that path), then — for a genuinely new node —
 * generate one and print the public key so it can be pasted into {@code config.json} for
 * every peer that needs to trust it.
 */
public final class NodeKeyStore {

    private static final Logger LOG = Logger.getLogger(NodeKeyStore.class.getName());
    private static final String ALGORITHM = "Ed25519";

    private NodeKeyStore() {
    }

    public static final class KeyStoreException extends RuntimeException {
        public KeyStoreException(String message, Throwable cause) {
            super(message, cause);
        }

        public KeyStoreException(String message) {
            super(message);
        }
    }

    /**
     * Resolves this node's keypair, trying each source in order and generating a fresh
     * one only as a last resort.
     *
     * @param nodeId        this process's node id, used for the bundled demo-key lookup
     *                      and the generated file name
     * @param explicitPath  value of a {@code --key-file} flag, or null
     * @param dataDir       this node's data directory (journal lives here too)
     */
    public static KeyPair resolve(String nodeId, String explicitPath, Path dataDir) {
        if (explicitPath != null && !explicitPath.isBlank()) {
            Path path = Path.of(explicitPath);
            if (!Files.isReadable(path)) {
                throw new KeyStoreException("Key file not readable: " + path.toAbsolutePath());
            }
            return parse(readLines(path), "explicit path " + path);
        }

        Path defaultFile = dataDir.resolve(nodeId + ".key");
        if (Files.isReadable(defaultFile)) {
            return parse(readLines(defaultFile), defaultFile.toString());
        }

        String demoResource = "/demo-keys/" + nodeId + ".key";
        try (var in = NodeKeyStore.class.getResourceAsStream(demoResource)) {
            if (in != null) {
                List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
                LOG.warning(() -> "Using the BUNDLED DEMO identity for node '" + nodeId + "'. "
                        + "This private key ships in the jar and is public knowledge — fine for "
                        + "trying the project out, never acceptable for anything real. Generate a "
                        + "real identity by deleting quiver-data/" + nodeId + ".key if it exists "
                        + "and passing a fresh --data-dir, or pass --key-file to point at one you "
                        + "control.");
                return parse(lines, "bundled demo resource " + demoResource);
            }
        } catch (IOException e) {
            throw new KeyStoreException("Could not read bundled demo key " + demoResource, e);
        }

        return generateAndPersist(nodeId, defaultFile);
    }

    private static KeyPair generateAndPersist(String nodeId, Path file) {
        KeyPair keyPair = generate();
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file,
                    encodePrivate(keyPair.getPrivate()) + "\n" + encodePublic(keyPair.getPublic()) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new KeyStoreException("Generated a new identity but could not save it to " + file, e);
        }
        LOG.info(() -> "Generated a new identity for node '" + nodeId + "' at " + file.toAbsolutePath()
                + ". Add this to config.json so peers trust it:\n"
                + "  \"publicKey\": \"" + encodePublic(keyPair.getPublic()) + "\"");
        return keyPair;
    }

    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            // Ed25519 is a JEP 339 requirement since Java 15; this project targets 17.
            throw new IllegalStateException("Ed25519 not available on this JVM", e);
        }
    }

    public static String encodePublic(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static String encodePrivate(PrivateKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static PublicKey decodePublic(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64.strip());
            return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(bytes));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new KeyStoreException("Malformed Ed25519 public key: " + e.getMessage(), e);
        }
    }

    private static PrivateKey decodePrivate(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64.strip());
            return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new KeyStoreException("Malformed Ed25519 private key: " + e.getMessage(), e);
        }
    }

    private static KeyPair parse(List<String> lines, String source) {
        List<String> nonBlank = lines.stream().filter(l -> !l.isBlank()).toList();
        if (nonBlank.size() < 2) {
            throw new KeyStoreException("Key file at " + source
                    + " must have two lines: the private key, then the public key");
        }
        PrivateKey priv = decodePrivate(nonBlank.get(0));
        PublicKey pub = decodePublic(nonBlank.get(1));
        return new KeyPair(pub, priv);
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new KeyStoreException("Could not read key file " + path.toAbsolutePath(), e);
        }
    }
}
