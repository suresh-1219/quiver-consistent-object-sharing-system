package com.quiver.node;

import com.quiver.network.NodeServer;
import com.quiver.network.SyncMessage;

import java.util.Scanner;

public class Main {

    public static void main(String[] args) {

        
        String nodeId = null;
        int port = -1;

        for (String arg : args) {
            if (arg.startsWith("--node-id=")) {
                nodeId = arg.substring("--node-id=".length());
            } else if (arg.startsWith("--port=")) {
                try {
                    port = Integer.parseInt(arg.substring("--port=".length()));
                } catch (NumberFormatException e) {
                    System.out.println("Invalid port value. Usage: java Main --node-id=A --port=9001");
                    return;
                }
            }
        }

        if (nodeId == null || port == -1) {
            System.out.println("Usage: java Main --node-id=A --port=9001");
            return;
        }

       
        ObjectStore store = new ObjectStore(nodeId);
        NodeServer server = new NodeServer(nodeId, port, store);

        
        java.util.List<NodeConfig> peerConfigs = ConfigLoader.loadPeers("config.json");

        if (peerConfigs != null) {
            for (NodeConfig peer : peerConfigs) {
                if (!peer.nodeId.equals(nodeId)) {   
                    server.addPeer(peer.host, peer.port);
                }
            }
            System.out.println("Loaded " + (peerConfigs.size() - 1) + " peers from config.json");
        } else {
            int[] allPorts = {9001, 9002, 9003};
            for (int p : allPorts) {
                if (p != port) {
                    server.addPeer("localhost", p);
                }
            }
        }

        server.start();
        System.out.println("Node '" + nodeId + "' started on port " + port);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {}
        server.broadcastSyncRequest();
        System.out.println("Sent sync request to peers to catch up on missed data.\n");
        System.out.println("Type commands: create <name> | update <name> <data> | status <name> | list | exit\n");

       
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("quiver> ");
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+", 3);
            String command = parts[0];

            switch (command) {
                case "create": {
                    if (parts.length < 2) { System.out.println("Usage: create <name>"); break; }
                    String objName = parts[1];
                    if (store.createObject(objName)) {
                        SyncMessage msg = new SyncMessage("CREATE", objName, null,
                                store.getObjectTimestamp(objName), nodeId);
                        server.broadcastUpdate(msg);
                    }
                    break;
                }
                case "update": {
                    if (parts.length < 3) { System.out.println("Usage: update <name> <data>"); break; }
                    String objName = parts[1];
                    String data = parts[2];
                    if (store.updateObject(objName, data)) {
                        SyncMessage msg = new SyncMessage("UPDATE", objName, data,
                                store.getObjectTimestamp(objName), nodeId);
                        server.broadcastUpdate(msg);
                    }
                    break;
                }
                case "set-permission": {
                    if (parts.length < 3) {
                        System.out.println("Usage: set-permission <name> <read/write> <user_id>");
                        break;
                    }
                    String[] permParts = parts[2].split("\\s+", 2);
                    if (permParts.length < 2) {
                        System.out.println("Usage: set-permission <name> <read/write> <user_id>");
                        break;
                    }
                    String objName = parts[1];
                    String permType = permParts[0];
                    String targetUser = permParts[1];

                    if (store.grantPermission(objName, permType, targetUser, nodeId)) {
                        SyncMessage msg = new SyncMessage("GRANT_PERMISSION", objName, nodeId, targetUser, permType);
                        server.broadcastUpdate(msg);
                    }
                    break;
                }
                case "status": {
                    if (parts.length < 2) { System.out.println("Usage: status <name>"); break; }
                    String objName = parts[1];
                    System.out.println(objName + " = " + store.getObjectValue(objName));
                    break;
                }
                case "list": {
                    System.out.println("Objects: " + store.listObjects());
                    break;
                }
                case "exit": {
                    System.out.println("Shutting down node " + nodeId);
                    scanner.close();
                    return;
                }
                default:
                    System.out.println("Unknown command: " + command);
            }
        }
    }
}