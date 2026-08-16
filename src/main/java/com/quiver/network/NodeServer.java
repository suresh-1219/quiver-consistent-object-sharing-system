package com.quiver.network;

import com.google.gson.Gson;
import com.quiver.node.ObjectStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class NodeServer {

    private int port;
    private String nodeId;
    private ObjectStore store;
    private Gson gson = new Gson();

    
    private List<PeerInfo> peers = new ArrayList<>();

    public NodeServer(String nodeId, int port, ObjectStore store) {
        this.nodeId = nodeId;
        this.port = port;
        this.store = store;
    }

        public void addPeer(String host, int peerPort) {
        peers.add(new PeerInfo(host, peerPort));
    }

    
    public void start() {
        Thread serverThread = new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                System.out.println("[" + nodeId + "] Listening on port " + port);

                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    
                    new Thread(() -> handleConnection(clientSocket)).start();
                }
            } catch (IOException e) {
                System.out.println("[" + nodeId + "] Server error: " + e.getMessage());
            }
        });
        serverThread.setDaemon(true);  
        serverThread.start();
    }

        private void handleConnection(Socket socket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String jsonLine = reader.readLine();
            if (jsonLine != null) {
                SyncMessage msg = gson.fromJson(jsonLine, SyncMessage.class);
                processIncomingMessage(msg);
            }
        } catch (IOException e) {
            System.out.println("[" + nodeId + "] Connection error: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void processIncomingMessage(SyncMessage msg) {
    	if ("CREATE".equals(msg.type)) {
            if (!store.objectExists(msg.objectName)) {
                store.createObjectWithOwner(msg.objectName, msg.senderNodeId);
            }

        
        } else if ("UPDATE".equals(msg.type)) {
            store.mergeRemoteUpdate(msg.objectName, msg.value, msg.toVectorClock(), msg.senderNodeId);
        } else if ("SYNC_REQUEST".equals(msg.type)) {
        	
            System.out.println("[" + nodeId + "] Received SYNC_REQUEST from " + msg.senderNodeId + ", sending full state...");
            sendFullStateTo("localhost", msg.senderPort);
        } else if ("GRANT_PERMISSION".equals(msg.type)) {
            store.applyRemoteGrant(msg.objectName, msg.targetUserId, msg.senderNodeId);
        }  
        
    }

    private void sendFullStateTo(String host, int targetPort) {
        for (String objName : store.listObjects()) {
            String value = store.getObjectValue(objName);
            com.quiver.crdt.VectorClock timestamp = store.getObjectTimestamp(objName);
            SyncMessage response = new SyncMessage("UPDATE", objName, value, timestamp, nodeId);
            sendToPeer(new PeerInfo(host, targetPort), gson.toJson(response));
        }
    }

    public void broadcastUpdate(SyncMessage msg) {
        String json = gson.toJson(msg);
        for (PeerInfo peer : peers) {
            sendToPeer(peer, json);
        }
    }
    public void broadcastSyncRequest() {
        SyncMessage msg = new SyncMessage("SYNC_REQUEST", nodeId, port);
        String json = gson.toJson(msg);
        for (PeerInfo peer : peers) {
            sendToPeer(peer, json);
        }
    }
    private void sendToPeer(PeerInfo peer, String json) {
        try (Socket socket = new Socket(peer.host, peer.port);
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {
            writer.println(json);
        } catch (IOException e) {
            System.out.println("[" + nodeId + "] Could not reach peer " + peer.host + ":" + peer.port
                    + " (" + e.getMessage() + ")");
        }
    }

    private static class PeerInfo {
        String host;
        int port;
        PeerInfo(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}