package com.quiver.node;

import com.quiver.network.NodeServer;
import com.quiver.network.SyncMessage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Entry point: starts one node and runs its interactive command loop. */
public final class Main {

    private static final String USAGE = """
            Usage: java -jar quiver.jar --node-id=A [--port=9001] [--config=path/to/config.json]
                                         [--data-dir=path/to/data] [--key-file=path/to/identity]

              --node-id   which entry in config.json this process is (required)
              --port      override the port from the config (optional)
              --config    path to a config file; defaults to ./config.json, then the
                          copy bundled in the jar
              --data-dir  where this node's journal AND identity file live; defaults to
                          ./quiver-data. Each node's journal is <node-id>.jsonl and its
                          Ed25519 identity is <node-id>.key, so nodes sharing a machine
                          can share a --data-dir safely.
              --key-file  path to this node's identity file, overriding --data-dir's
                          default location. If no identity is found anywhere, one is
                          generated and its public key printed for you to add to
                          config.json.
            """;

    private static final String HELP = """
            Commands:
              create <name>                          create an object owned by this node
              update <name> <data>                   write a value (owner or granted writers)
              set-permission <name> write <node-id>  grant write access (owner only)
              status <name>                          show an object's value, owner and clock
              list                                   list known objects
              help                                   show this message
              exit                                   shut this node down
            """;

    private Main() {
    }

    public static void main(String[] args) {
        Map<String, String> flags = parseFlags(args);
        String nodeId = flags.get("node-id");
        if (nodeId == null || flags.containsKey("help")) {
            System.out.print(USAGE);
            System.exit(nodeId == null ? 2 : 0);
        }

        ClusterConfig cluster;
        NodeConfig self;
        try {
            cluster = ConfigLoader.load(flags.get("config"));
            self = cluster.require(nodeId);
        } catch (RuntimeException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(2);
            return;
        }

        int port = self.port;
        if (flags.containsKey("port")) {
            try {
                port = Integer.parseInt(flags.get("port"));
            } catch (NumberFormatException e) {
                System.err.println("Invalid --port value: " + flags.get("port"));
                System.exit(2);
            }
        }

        Path dataDir = Path.of(flags.getOrDefault("data-dir", "quiver-data"));
        Path journalPath = dataDir.resolve(nodeId + ".jsonl");

        java.security.KeyPair selfKeyPair;
        try {
            selfKeyPair = NodeKeyStore.resolve(nodeId, flags.get("key-file"), dataDir);
        } catch (RuntimeException e) {
            System.err.println("Identity error: " + e.getMessage());
            System.exit(1);
            return;
        }
        String declaredKey = self.publicKey;
        String actualKey = NodeKeyStore.encodePublic(selfKeyPair.getPublic());
        if (!declaredKey.equals(actualKey)) {
            System.err.printf("""
                    WARNING: the identity loaded for '%s' does not match the publicKey in config.json.
                    Peers will reject every message this node sends until one of the two is updated.
                      config.json declares: %s
                      loaded identity is:   %s
                    """, nodeId, declaredKey, actualKey);
        }

        ObjectStore store = new ObjectStore(nodeId);
        JournalStore journal;
        try {
            // Replay first, into a store with no listener attached yet, so reading the
            // journal back in does not immediately write it back out again.
            JournalStore.replayInto(journalPath, store);
            journal = JournalStore.open(journalPath);
            store.setChangeListener(journal.asChangeListener());
        } catch (IOException e) {
            System.err.println("Could not open journal at " + journalPath.toAbsolutePath()
                    + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        NodeServer server = new NodeServer(nodeId, selfKeyPair, port, store, cluster);
        try {
            server.start();
        } catch (IOException e) {
            System.err.println("Could not bind port " + port + ": " + e.getMessage());
            journal.close();
            System.exit(1);
            return;
        }
        JournalStore journalToClose = journal;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            journalToClose.close();
        }, "quiver-shutdown"));

        if (!store.listObjects().isEmpty()) {
            System.out.printf("Restored %d object(s) from %s%n",
                    store.listObjects().size(), journalPath.toAbsolutePath());
        }

        System.out.printf("Node '%s' listening on port %d; peers: %s%n",
                nodeId, server.boundPort(), cluster.peersOf(nodeId));
        server.requestFullSyncFromPeers();
        System.out.println("Requested a full sync from peers.");
        System.out.print(HELP);

        int exitCode = runCommandLoop(nodeId, store, server);
        server.close();
        System.exit(exitCode);
    }

    private static int runCommandLoop(String nodeId, ObjectStore store, NodeServer server) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            while (true) {
                System.out.print("quiver> ");
                System.out.flush();
                String line = in.readLine();
                if (line == null) { // EOF: piped input or Ctrl-D, not a crash
                    System.out.println();
                    return 0;
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (!execute(line, nodeId, store, server)) {
                    return 0;
                }
            }
        } catch (IOException e) {
            System.err.println("Input error: " + e.getMessage());
            return 1;
        }
    }

    /** @return false when the node should shut down. */
    private static boolean execute(String line, String nodeId, ObjectStore store, NodeServer server) {
        String[] parts = line.split("\\s+", 3);
        switch (parts[0]) {
            case "create" -> {
                if (parts.length < 2) {
                    System.out.println("Usage: create <name>");
                    return true;
                }
                String name = parts[1];
                if (store.createObject(name) == ObjectStore.CreateOutcome.CREATED) {
                    System.out.printf("Created '%s' (owner: %s)%n", name, nodeId);
                    server.broadcast(SyncMessage.create(name, nodeId));
                } else {
                    System.out.printf("'%s' already exists (owner: %s)%n", name, store.getOwner(name));
                }
            }
            case "update" -> {
                if (parts.length < 3) {
                    System.out.println("Usage: update <name> <data>");
                    return true;
                }
                String name = parts[1];
                switch (store.updateObject(name, parts[2])) {
                    case UPDATED -> {
                        System.out.printf("Updated '%s'%n", name);
                        server.broadcast(SyncMessage.update(
                                name, store.getObjectValue(name), store.getObjectTimestamp(name), nodeId));
                    }
                    case NOT_FOUND -> System.out.printf("No such object: '%s'%n", name);
                    case ACCESS_DENIED -> System.out.printf(
                            "Access denied: '%s' is owned by %s and has not granted you write access%n",
                            name, store.getOwner(name));
                }
            }
            case "set-permission" -> {
                String[] rest = parts.length >= 3 ? parts[2].split("\\s+", 2) : new String[0];
                if (parts.length < 3 || rest.length < 2) {
                    System.out.println("Usage: set-permission <name> <read|write> <node-id>");
                    return true;
                }
                String name = parts[1];
                String permission = rest[0];
                String target = rest[1];
                switch (store.grantPermission(name, permission, target, nodeId)) {
                    case GRANTED -> {
                        System.out.printf("Granted write on '%s' to '%s'%n", name, target);
                        server.broadcast(SyncMessage.grantPermission(name, permission, target, nodeId));
                    }
                    case READ_IS_PUBLIC -> System.out.println(
                            "Reads are public in this implementation; nothing to grant.");
                    case NOT_OWNER -> System.out.printf(
                            "Access denied: only the owner (%s) can grant permissions on '%s'%n",
                            store.getOwner(name), name);
                    case UNKNOWN_OBJECT -> System.out.printf("No such object: '%s'%n", name);
                    case UNKNOWN_PERMISSION -> System.out.printf(
                            "Unknown permission '%s' (expected read or write)%n", permission);
                }
            }
            case "status" -> {
                if (parts.length < 2) {
                    System.out.println("Usage: status <name>");
                    return true;
                }
                String name = parts[1];
                if (!store.objectExists(name)) {
                    System.out.printf("No such object: '%s'%n", name);
                    return true;
                }
                System.out.printf("%s = %s%n  owner: %s%n  writers: %s%n  clock: %s%n",
                        name,
                        store.getObjectValue(name) == null ? "<unset>" : store.getObjectValue(name),
                        store.getOwner(name),
                        store.getWriters(name).isEmpty() ? "<none>" : store.getWriters(name),
                        store.getObjectTimestamp(name));
            }
            case "list" -> {
                var objects = store.listObjects();
                System.out.println(objects.isEmpty() ? "No objects yet." : "Objects: " + objects);
            }
            case "help" -> System.out.print(HELP);
            case "exit" -> {
                System.out.println("Shutting down node " + nodeId);
                return false;
            }
            default -> System.out.printf("Unknown command: '%s' (try 'help')%n", parts[0]);
        }
        return true;
    }

    private static Map<String, String> parseFlags(String[] args) {
        Map<String, String> flags = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                continue;
            }
            String body = arg.substring(2);
            int eq = body.indexOf('=');
            if (eq < 0) {
                flags.put(body, "");
            } else {
                flags.put(body.substring(0, eq), body.substring(eq + 1));
            }
        }
        return flags;
    }
}
