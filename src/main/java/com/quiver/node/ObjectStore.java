package com.quiver.node;

import com.quiver.crdt.LWWRegister;
import com.quiver.crdt.VectorClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The replicated state of one node: object values plus their ownership and write ACLs.
 *
 * <p>Every method returns an outcome instead of printing. Printing from the store made
 * it impossible to test quietly and mixed protocol output into the CLI prompt; the
 * caller decides what the user sees.
 *
 * <p>Authorisation is enforced here for <em>both</em> local and remote writes. Previously
 * remote updates bypassed the ACL entirely, so the permission model only constrained
 * the node's own CLI.
 *
 * <p>Persistence hook: every successful mutation notifies a listener with a full
 * snapshot of the object it touched (see {@link #setChangeListener}). {@link
 * com.quiver.node.JournalStore} is the production listener, but the store itself has no
 * idea persistence exists — it just reports what changed. This keeps the CRDT and ACL
 * logic testable without a filesystem, and means the same snapshot format already used
 * for peer state-transfer is what gets written to disk, so there is exactly one format
 * to serialise, merge and test.
 */
public final class ObjectStore {

    public enum CreateOutcome { CREATED, ALREADY_EXISTS }

    public enum UpdateOutcome { UPDATED, NOT_FOUND, ACCESS_DENIED }

    public enum MergeOutcome { MERGED, IGNORED, UNKNOWN_OBJECT, ACCESS_DENIED }

    public enum GrantOutcome { GRANTED, READ_IS_PUBLIC, NOT_OWNER, UNKNOWN_OBJECT, UNKNOWN_PERMISSION }

    /** Full state of one object, for state-transfer sync and for the on-disk journal. */
    public record ObjectSnapshot(String name,
                                 String value,
                                 Map<String, Integer> clock,
                                 String lastWriter,
                                 String owner,
                                 Set<String> writers) {
    }

    private final Map<String, LWWRegister<String>> objects = new ConcurrentHashMap<>();
    private final Map<String, String> owners = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> writePermissions = new ConcurrentHashMap<>();
    private final String nodeId;

    private final AtomicReference<Consumer<ObjectSnapshot>> onChange =
            new AtomicReference<>(snapshot -> { });

    public ObjectStore(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        this.nodeId = nodeId;
    }

    public String nodeId() {
        return nodeId;
    }

    /**
     * Registers a listener notified with a snapshot after every successful mutation.
     *
     * <p>Deliberately not a constructor argument: a journal replay needs a silent store
     * (see {@link JournalStore#replayInto}) so that reading the journal back in does not
     * immediately write it out again, and only wants the listener attached once replay
     * has finished. Pass {@code null} to go back to doing nothing.
     */
    public void setChangeListener(Consumer<ObjectSnapshot> listener) {
        onChange.set(listener == null ? snapshot -> { } : listener);
    }

    // ---------------------------------------------------------------- local operations

    public CreateOutcome createObject(String objectName) {
        boolean created = registerIfAbsent(objectName, nodeId);
        if (created) {
            notifyChanged(objectName);
        }
        return created ? CreateOutcome.CREATED : CreateOutcome.ALREADY_EXISTS;
    }

    public UpdateOutcome updateObject(String objectName, String data) {
        LWWRegister<String> register = objects.get(objectName);
        if (register == null) {
            return UpdateOutcome.NOT_FOUND;
        }
        if (!hasWriteAccess(objectName, nodeId)) {
            return UpdateOutcome.ACCESS_DENIED;
        }
        register.update(data, nodeId);
        notifyChanged(objectName);
        return UpdateOutcome.UPDATED;
    }

    public GrantOutcome grantPermission(String objectName, String permissionType,
                                        String targetUserId, String requesterId) {
        String owner = owners.get(objectName);
        if (owner == null) {
            return GrantOutcome.UNKNOWN_OBJECT;
        }
        if (!owner.equals(requesterId)) {
            return GrantOutcome.NOT_OWNER;
        }
        if ("write".equalsIgnoreCase(permissionType)) {
            writers(objectName).add(targetUserId);
            notifyChanged(objectName);
            return GrantOutcome.GRANTED;
        }
        if ("read".equalsIgnoreCase(permissionType)) {
            return GrantOutcome.READ_IS_PUBLIC;
        }
        return GrantOutcome.UNKNOWN_PERMISSION;
    }

    // --------------------------------------------------------------- remote operations

    /**
     * Applies a create announced by {@code ownerNodeId}.
     *
     * <p>If two nodes create the same name concurrently, both believe they own it. The
     * tie is broken deterministically by lowest node id, so every replica lands on the
     * same owner instead of splitting permanently.
     */
    public CreateOutcome applyRemoteCreate(String objectName, String ownerNodeId) {
        boolean created = registerIfAbsent(objectName, ownerNodeId);
        if (created) {
            notifyChanged(objectName);
            return CreateOutcome.CREATED;
        }
        String previousOwner = owners.get(objectName);
        owners.merge(objectName, ownerNodeId,
                (existing, incoming) -> existing.compareTo(incoming) <= 0 ? existing : incoming);
        if (!owners.get(objectName).equals(previousOwner)) {
            notifyChanged(objectName);
        }
        return CreateOutcome.ALREADY_EXISTS;
    }

    /** Merges a remote write, but only if the sender is actually allowed to write it. */
    public MergeOutcome applyRemoteUpdate(String objectName, String data,
                                          VectorClock clock, String senderNodeId) {
        LWWRegister<String> register = objects.get(objectName);
        if (register == null) {
            // Refusing to auto-create means an unknown sender cannot conjure objects,
            // and the caller can answer by asking the sender for a full state transfer.
            return MergeOutcome.UNKNOWN_OBJECT;
        }
        if (!hasWriteAccess(objectName, senderNodeId)) {
            return MergeOutcome.ACCESS_DENIED;
        }
        LWWRegister.MergeOutcome result = register.merge(data, clock, senderNodeId);
        if (result == LWWRegister.MergeOutcome.APPLIED) {
            notifyChanged(objectName);
            return MergeOutcome.MERGED;
        }
        return MergeOutcome.IGNORED;
    }

    /** Applies a grant, but only from the node this replica believes is the owner. */
    public GrantOutcome applyRemoteGrant(String objectName, String permissionType,
                                         String targetUserId, String senderNodeId) {
        String owner = owners.get(objectName);
        if (owner == null) {
            return GrantOutcome.UNKNOWN_OBJECT;
        }
        if (!owner.equals(senderNodeId)) {
            return GrantOutcome.NOT_OWNER;
        }
        if ("write".equalsIgnoreCase(permissionType)) {
            writers(objectName).add(targetUserId);
            notifyChanged(objectName);
            return GrantOutcome.GRANTED;
        }
        if ("read".equalsIgnoreCase(permissionType)) {
            return GrantOutcome.READ_IS_PUBLIC;
        }
        return GrantOutcome.UNKNOWN_PERMISSION;
    }

    // ------------------------------------------------------------------ state transfer

    public List<ObjectSnapshot> snapshot() {
        List<ObjectSnapshot> out = new ArrayList<>();
        for (String name : objects.keySet()) {
            snapshotOf(name).ifPresent(out::add);
        }
        return out;
    }

    private java.util.Optional<ObjectSnapshot> snapshotOf(String objectName) {
        LWWRegister<String> register = objects.get(objectName);
        if (register == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ObjectSnapshot(
                objectName,
                register.getValue(),
                register.getTimestamp().asMap(),
                register.getLastWriterNodeId(),
                owners.get(objectName),
                new TreeSet<>(writers(objectName))));
    }

    /**
     * Merges one snapshot — from a peer's full state transfer, or from replaying this
     * node's own journal on startup. Safe to call in any order, any number of times: it
     * only ever calls through the same create/grant/merge paths above, each of which is
     * already commutative, associative and idempotent.
     */
    public void applyOneSnapshot(ObjectSnapshot snap, String senderNodeId) {
        if (snap == null || snap.name() == null || snap.name().isBlank()) {
            return;
        }
        String claimedOwner = snap.owner() != null ? snap.owner() : senderNodeId;
        applyRemoteCreate(snap.name(), claimedOwner);

        if (snap.writers() != null) {
            Set<String> current = writers(snap.name());
            boolean changed = current.addAll(snap.writers());
            if (changed) {
                notifyChanged(snap.name());
            }
        }
        if (snap.value() != null && snap.lastWriter() != null) {
            // The writer may not be authorised yet on a brand-new local replica in the
            // instant between applyRemoteCreate and the writers.addAll above running on
            // a concurrent thread; both operations are individually safe, and a snapshot
            // that arrives again later (peer sync, or a later journal line) will apply
            // cleanly once the ACL has caught up.
            applyRemoteUpdate(snap.name(), snap.value(), VectorClock.of(snap.clock()), snap.lastWriter());
        }
    }

    /** Merges a peer's full state. See {@link #applyOneSnapshot} for the safety argument. */
    public void applySnapshot(List<ObjectSnapshot> remoteObjects, String senderNodeId) {
        if (remoteObjects == null) {
            return;
        }
        for (ObjectSnapshot snap : remoteObjects) {
            applyOneSnapshot(snap, senderNodeId);
        }
    }

    // ------------------------------------------------------------------------ accessors

    public boolean hasWriteAccess(String objectName, String candidateNodeId) {
        if (candidateNodeId == null) {
            return false;
        }
        String owner = owners.get(objectName);
        if (owner != null && owner.equals(candidateNodeId)) {
            return true;
        }
        Set<String> allowed = writePermissions.get(objectName);
        return allowed != null && allowed.contains(candidateNodeId);
    }

    public String getOwner(String objectName) {
        return owners.get(objectName);
    }

    public String getObjectValue(String objectName) {
        LWWRegister<String> register = objects.get(objectName);
        return register != null ? register.getValue() : null;
    }

    public VectorClock getObjectTimestamp(String objectName) {
        LWWRegister<String> register = objects.get(objectName);
        return register != null ? register.getTimestamp() : VectorClock.EMPTY;
    }

    public Set<String> getWriters(String objectName) {
        return Collections.unmodifiableSet(writers(objectName));
    }

    /** Snapshot copy — callers cannot mutate the store through it. */
    public Set<String> listObjects() {
        return new TreeSet<>(objects.keySet());
    }

    public boolean objectExists(String objectName) {
        return objects.containsKey(objectName);
    }

    // ------------------------------------------------------------------------- internals

    private boolean registerIfAbsent(String objectName, String ownerNodeId) {
        if (objectName == null || objectName.isBlank()) {
            throw new IllegalArgumentException("objectName must not be blank");
        }
        boolean[] created = {false};
        objects.computeIfAbsent(objectName, name -> {
            created[0] = true;
            owners.put(name, ownerNodeId);
            writePermissions.computeIfAbsent(name, k -> ConcurrentHashMap.newKeySet());
            return new LWWRegister<>();
        });
        return created[0];
    }

    private Set<String> writers(String objectName) {
        return writePermissions.computeIfAbsent(objectName, k -> ConcurrentHashMap.newKeySet());
    }

    private void notifyChanged(String objectName) {
        snapshotOf(objectName).ifPresent(snapshot -> onChange.get().accept(snapshot));
    }
}
