package com.quiver.node;

import com.quiver.crdt.LWWRegister;
import com.quiver.crdt.VectorClock;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ObjectStore {

    
    private final Map<String, LWWRegister<String>> objects = new ConcurrentHashMap<>();
    
    private final Map<String, String> owners = new ConcurrentHashMap<>();

    
    private final Map<String, Set<String>> writePermissions = new ConcurrentHashMap<>();
    private String nodeId;

    public ObjectStore(String nodeId) {
        this.nodeId = nodeId;
    }

    public boolean createObject(String objectName) {
        if (objects.containsKey(objectName)) {
            System.out.println("Object '" + objectName + "' already exists.");
            return false;
        }
        objects.put(objectName, new LWWRegister<>());
        owners.put(objectName, nodeId);   
        writePermissions.put(objectName, java.util.concurrent.ConcurrentHashMap.newKeySet());
        System.out.println("Object '" + objectName + "' created on node " + nodeId + " (owner: " + nodeId + ")");
        return true;
    }
    public boolean createObjectWithOwner(String objectName, String actualOwnerId) {
        if (objects.containsKey(objectName)) {
            return false;
        }
        objects.put(objectName, new LWWRegister<>());
        owners.put(objectName, actualOwnerId);   
        writePermissions.put(objectName, java.util.concurrent.ConcurrentHashMap.newKeySet());
        System.out.println("Object '" + objectName + "' created (via sync) with owner: " + actualOwnerId);
        return true;
    }
    public boolean updateObject(String objectName, String data) {
        LWWRegister<String> register = objects.get(objectName);
        if (register == null) {
            System.out.println("Object '" + objectName + "' does not exist.");
            return false;
        }

        if (!hasWriteAccess(objectName, nodeId)) {
            System.out.println("Access Denied: Node '" + nodeId + "' does not have write permission for '" + objectName + "'.");
            return false;
        }

        register.update(data, nodeId);
        System.out.println("Object '" + objectName + "' updated locally on node " + nodeId);
        return true;
    }

    public boolean hasWriteAccess(String objectName, String checkNodeId) {
        String owner = owners.get(objectName);
        if (owner != null && owner.equals(checkNodeId)) {
            return true;   
        }
        java.util.Set<String> writers = writePermissions.get(objectName);
        return writers != null && writers.contains(checkNodeId);
    }

    public boolean grantPermission(String objectName, String permissionType, String targetUserId, String requesterId) {
        String owner = owners.get(objectName);
        if (owner == null) {
            System.out.println("Object '" + objectName + "' does not exist.");
            return false;
        }
        if (!owner.equals(requesterId)) {
            System.out.println("Access Denied: Only the owner ('" + owner + "') can grant permissions for '" + objectName + "'.");
            return false;
        }
        if ("write".equalsIgnoreCase(permissionType)) {
            writePermissions.computeIfAbsent(objectName, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(targetUserId);
            System.out.println("Granted WRITE permission on '" + objectName + "' to node '" + targetUserId + "'.");
        } else if ("read".equalsIgnoreCase(permissionType)) {
            System.out.println("All nodes already have READ access to '" + objectName + "' (read is public in this implementation).");
        } else {
            System.out.println("Unknown permission type: " + permissionType);
            return false;
        }
        return true;
    }

    public void applyRemoteGrant(String objectName, String targetUserId, String ownerNodeId) {
        owners.putIfAbsent(objectName, ownerNodeId);   
        writePermissions.computeIfAbsent(objectName, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(targetUserId);
        System.out.println("Applied remote permission grant: '" + targetUserId + "' can now write to '" + objectName + "'.");
    }

    public String getOwner(String objectName) {
        return owners.get(objectName);
    }

    public void mergeRemoteUpdate(String objectName, String data, VectorClock timestamp, String remoteNodeId) {
        LWWRegister<String> register = objects.get(objectName);

        if (register == null) {
            register = new LWWRegister<>();
            objects.put(objectName, register);
        }
        register.merge(data, timestamp, remoteNodeId);
        System.out.println("Object '" + objectName + "' merged from node " + remoteNodeId + " on node " + nodeId);
    }

    public String getObjectValue(String objectName) {
        LWWRegister<String> register = objects.get(objectName);
        return (register != null) ? register.getValue() : null;
    }

    public VectorClock getObjectTimestamp(String objectName) {
        LWWRegister<String> register = objects.get(objectName);
        return (register != null) ? register.getTimestamp() : null;
    }

    public Set<String> listObjects() {
        return objects.keySet();
    }

    public boolean objectExists(String objectName) {
        return objects.containsKey(objectName);
    }
}
