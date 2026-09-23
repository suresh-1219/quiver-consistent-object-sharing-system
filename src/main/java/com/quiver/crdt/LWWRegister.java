package com.quiver.crdt;

import java.util.Objects;

/**
 * A last-writer-wins register whose timestamps are vector clocks.
 *
 * <p>The register keeps two clocks, and the distinction is the whole point:
 * <ul>
 *   <li>{@code valueClock} — the clock of the value currently held. Conflict resolution
 *       compares against this, so a losing merge never inflates the winner's timestamp.</li>
 *   <li>{@code seenClock} — the join of every clock this replica has observed. Local
 *       updates are stamped from this, so a replica's own counter can never go
 *       backwards after it loses a merge (the old implementation replaced the clock
 *       wholesale and silently reused counter values).</li>
 * </ul>
 *
 * <p>Convergence: {@link #applyUpdate} keeps whichever of (current, incoming) is greater
 * under {@link Writes#ORDER}, a strict total order. Taking the maximum under a total
 * order is commutative, associative and idempotent, so replicas that observe the same
 * set of updates converge regardless of delivery order or duplication.
 *
 * <p>Thread safety: every method is synchronised on the instance. Fields are only ever
 * replaced (never mutated in place), since {@link VectorClock} is immutable.
 */
public final class LWWRegister<T> {

    /** What a merge did, so callers can log or test it without inspecting state. */
    public enum MergeOutcome { APPLIED, IGNORED_STALE, IGNORED_LOST_TIEBREAK, NO_CHANGE }

    private T value;
    private VectorClock valueClock = VectorClock.EMPTY;
    private String lastWriterNodeId;

    private VectorClock seenClock = VectorClock.EMPTY;

    /** Applies a local write by {@code nodeId}, stamped one tick past everything seen. */
    public synchronized MergeOutcome update(T newValue, String nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        return applyUpdate(newValue, seenClock.incremented(nodeId), nodeId);
    }

    /** Merges a write that arrived from another replica. */
    public synchronized MergeOutcome merge(T incomingValue, VectorClock incomingClock, String incomingNodeId) {
        Objects.requireNonNull(incomingNodeId, "incomingNodeId");
        return applyUpdate(incomingValue, incomingClock == null ? VectorClock.EMPTY : incomingClock, incomingNodeId);
    }

    private MergeOutcome applyUpdate(T newValue, VectorClock newClock, String nodeId) {
        seenClock = seenClock.join(newClock);

        if (lastWriterNodeId == null && valueClock.isEmpty() && value == null) {
            // First write this replica has ever seen for the object.
            this.value = newValue;
            this.valueClock = newClock;
            this.lastWriterNodeId = nodeId;
            return MergeOutcome.APPLIED;
        }

        Writes incoming = new Writes(newClock, nodeId, str(newValue));
        Writes current = new Writes(valueClock, lastWriterNodeId, str(value));

        int cmp = Writes.ORDER.compare(incoming, current);
        if (cmp == 0) {
            return MergeOutcome.NO_CHANGE; // duplicate delivery
        }
        if (cmp < 0) {
            return newClock.compare(valueClock) == VectorClock.Ordering.BEFORE
                    ? MergeOutcome.IGNORED_STALE
                    : MergeOutcome.IGNORED_LOST_TIEBREAK;
        }
        this.value = newValue;
        this.valueClock = newClock;
        this.lastWriterNodeId = nodeId;
        return MergeOutcome.APPLIED;
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    public synchronized T getValue() {
        return value;
    }

    /** The clock of the value currently held. */
    public synchronized VectorClock getTimestamp() {
        return valueClock;
    }

    /** Everything this replica has observed; used to stamp the next local write. */
    public synchronized VectorClock getSeenClock() {
        return seenClock;
    }

    public synchronized String getLastWriterNodeId() {
        return lastWriterNodeId;
    }

    /** Reports the causal relationship of an incoming clock without applying it. */
    public synchronized VectorClock.Ordering relationTo(VectorClock other) {
        return other.compare(valueClock);
    }

    @Override
    public synchronized String toString() {
        return "value=" + value + ", clock=" + valueClock + ", lastWriter=" + lastWriterNodeId;
    }

    /**
     * A candidate write, ordered deterministically.
     *
     * <p>Clock order comes first (so causality always beats a tiebreak), then writer id,
     * then the value itself. The final two components only matter for concurrent writes
     * and exist so that every replica breaks the tie identically.
     */
    private record Writes(VectorClock clock, String writerNodeId, String value) {

        static final java.util.Comparator<Writes> ORDER =
                java.util.Comparator.<Writes, VectorClock>comparing(Writes::clock, VectorClock.TOTAL_ORDER)
                        .thenComparing(w -> w.writerNodeId() == null ? "" : w.writerNodeId())
                        .thenComparing(Writes::value);
    }
}
