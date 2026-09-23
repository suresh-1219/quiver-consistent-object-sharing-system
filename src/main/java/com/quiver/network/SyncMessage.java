package com.quiver.network;

import com.quiver.crdt.VectorClock;
import com.quiver.node.ObjectStore;

import java.util.List;
import java.util.Map;

/**
 * The payload of a peer-to-peer message.
 *
 * <p>Fields are public and nullable because Gson populates them reflectively; the three
 * overlapping constructors of the previous version have been replaced by named factory
 * methods, and {@link #validate()} rejects anything a peer sends that doesn't match its
 * declared type before the handler sees it.
 */
public final class SyncMessage {

    public enum Type { CREATE, UPDATE, SYNC_REQUEST, STATE, GRANT_PERMISSION }

    public Type type;
    public String senderNodeId;

    public String objectName;
    public String value;
    public Map<String, Integer> timestampClock;
    public String ownerNodeId;

    public String targetUserId;
    public String permissionType;

    public List<ObjectStore.ObjectSnapshot> objects;

    public SyncMessage() {
    }

    public static SyncMessage create(String objectName, String senderNodeId) {
        SyncMessage m = new SyncMessage();
        m.type = Type.CREATE;
        m.objectName = objectName;
        m.senderNodeId = senderNodeId;
        m.ownerNodeId = senderNodeId;
        return m;
    }

    public static SyncMessage update(String objectName, String value,
                                     VectorClock clock, String senderNodeId) {
        SyncMessage m = new SyncMessage();
        m.type = Type.UPDATE;
        m.objectName = objectName;
        m.value = value;
        m.timestampClock = clock.asMap(); // defensive copy: never alias the live clock
        m.senderNodeId = senderNodeId;
        return m;
    }

    public static SyncMessage syncRequest(String senderNodeId) {
        SyncMessage m = new SyncMessage();
        m.type = Type.SYNC_REQUEST;
        m.senderNodeId = senderNodeId;
        return m;
    }

    public static SyncMessage state(List<ObjectStore.ObjectSnapshot> objects, String senderNodeId) {
        SyncMessage m = new SyncMessage();
        m.type = Type.STATE;
        m.objects = objects;
        m.senderNodeId = senderNodeId;
        return m;
    }

    public static SyncMessage grantPermission(String objectName, String permissionType,
                                              String targetUserId, String senderNodeId) {
        SyncMessage m = new SyncMessage();
        m.type = Type.GRANT_PERMISSION;
        m.objectName = objectName;
        m.permissionType = permissionType;
        m.targetUserId = targetUserId;
        m.senderNodeId = senderNodeId;
        return m;
    }

    /** @throws IllegalArgumentException if a peer sent something structurally invalid. */
    public void validate() {
        require(type != null, "missing type");
        require(senderNodeId != null && !senderNodeId.isBlank(), "missing senderNodeId");
        switch (type) {
            case CREATE -> {
                requireObjectName();
                require(ownerNodeId != null && !ownerNodeId.isBlank(), "CREATE without ownerNodeId");
            }
            case UPDATE -> requireObjectName();
            case GRANT_PERMISSION -> {
                requireObjectName();
                require(targetUserId != null && !targetUserId.isBlank(), "grant without targetUserId");
                require(permissionType != null && !permissionType.isBlank(), "grant without permissionType");
            }
            case STATE -> require(objects != null, "STATE without objects");
            case SYNC_REQUEST -> { /* no extra fields */ }
        }
    }

    public VectorClock toVectorClock() {
        return VectorClock.of(timestampClock);
    }

    private void requireObjectName() {
        require(objectName != null && !objectName.isBlank(), type + " without objectName");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("Invalid SyncMessage: " + message);
        }
    }
}
