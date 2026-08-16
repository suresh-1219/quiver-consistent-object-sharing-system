package com.quiver.network;

import com.quiver.crdt.VectorClock;
import java.util.Map;

public class SyncMessage {

    public String type;         
    public String objectName;
    public String value;
    public Map<String, Integer> timestampClock;  
    public String senderNodeId;

    public SyncMessage() {
    }

    public SyncMessage(String type, String objectName, String value,
                        VectorClock timestamp, String senderNodeId) {
        this.type = type;
        this.objectName = objectName;
        this.value = value;
        this.timestampClock = timestamp.getClock();
        this.senderNodeId = senderNodeId;
    }
    public int senderPort;   

    public SyncMessage(String type, String senderNodeId, int senderPort) {
        this.type = type;
        this.senderNodeId = senderNodeId;
        this.senderPort = senderPort;
    }
    public String targetUserId;   
    public String permissionType; 

    public SyncMessage(String type, String objectName, String senderNodeId,
                        String targetUserId, String permissionType) {
        this.type = type;
        this.objectName = objectName;
        this.senderNodeId = senderNodeId;
        this.targetUserId = targetUserId;
        this.permissionType = permissionType;
    }
    public VectorClock toVectorClock() {
        VectorClock vc = new VectorClock();
        if (timestampClock != null) {
            vc.getClock().putAll(timestampClock);
        }
        return vc;
    }
}