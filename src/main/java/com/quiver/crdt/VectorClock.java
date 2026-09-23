package com.quiver.crdt;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * An immutable vector clock.
 *
 * <p>Immutability is deliberate: clocks are read by the CLI thread while network
 * threads merge remote updates, and the previous mutable-{@code HashMap} version
 * leaked its internal map through {@code getClock()}, so a clock could be mutated
 * while it was being serialised.
 *
 * <p>{@link #compare(VectorClock)} reports the <em>causal</em> relationship, which is a
 * partial order. {@link #TOTAL_ORDER} is a strict total order over clocks that
 * <em>extends</em> that partial order (if X dominates Y then X sorts above Y). Conflict
 * resolution needs the total order; causality reporting needs the partial one.
 */
public final class VectorClock {

    /** Causal relationship between two clocks. */
    public enum Ordering { BEFORE, AFTER, CONCURRENT, EQUAL }

    public static final VectorClock EMPTY = new VectorClock(Collections.emptyMap());

    private final Map<String, Integer> clock; // unmodifiable, never escapes mutable

    private VectorClock(Map<String, Integer> clock) {
        this.clock = Collections.unmodifiableMap(clock);
    }

    /** Builds a clock from an untrusted map (e.g. a deserialised message). Null-safe. */
    public static VectorClock of(Map<String, Integer> entries) {
        if (entries == null || entries.isEmpty()) {
            return EMPTY;
        }
        Map<String, Integer> copy = new HashMap<>();
        for (Map.Entry<String, Integer> e : entries.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) {
                throw new IllegalArgumentException("Malformed vector clock entry: " + e);
            }
            if (e.getValue() < 0) {
                throw new IllegalArgumentException("Negative clock counter for node " + e.getKey());
            }
            if (e.getValue() > 0) {
                copy.put(e.getKey(), e.getValue());
            }
        }
        return copy.isEmpty() ? EMPTY : new VectorClock(copy);
    }

    /** Returns a new clock with {@code nodeId}'s counter incremented by one. */
    public VectorClock incremented(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        Map<String, Integer> next = new HashMap<>(clock);
        next.merge(nodeId, 1, Integer::sum);
        return new VectorClock(next);
    }

    /** Pointwise maximum: the least upper bound of the two clocks. */
    public VectorClock join(VectorClock other) {
        if (other == null || other.clock.isEmpty()) {
            return this;
        }
        if (this.clock.isEmpty()) {
            return other;
        }
        Map<String, Integer> joined = new HashMap<>(this.clock);
        for (Map.Entry<String, Integer> e : other.clock.entrySet()) {
            joined.merge(e.getKey(), e.getValue(), Math::max);
        }
        return new VectorClock(joined);
    }

    /** Causal relationship of this clock to {@code other}. */
    public Ordering compare(VectorClock other) {
        boolean thisLessOrEqual = true;
        boolean otherLessOrEqual = true;

        Set<String> allNodes = new HashSet<>(this.clock.keySet());
        allNodes.addAll(other.clock.keySet());

        for (String node : allNodes) {
            int thisVal = this.clock.getOrDefault(node, 0);
            int otherVal = other.clock.getOrDefault(node, 0);
            if (thisVal > otherVal) {
                thisLessOrEqual = false;
            }
            if (thisVal < otherVal) {
                otherLessOrEqual = false;
            }
        }

        if (thisLessOrEqual && otherLessOrEqual) {
            return Ordering.EQUAL;
        } else if (thisLessOrEqual) {
            return Ordering.BEFORE;
        } else if (otherLessOrEqual) {
            return Ordering.AFTER;
        } else {
            return Ordering.CONCURRENT;
        }
    }

    /**
     * Strict total order over clocks, consistent with causality.
     *
     * <p>Sorts by the sum of all counters first. If X causally dominates Y then X has at
     * least one strictly larger counter and no smaller one, so its sum is strictly
     * greater — which is why this order never contradicts {@link #compare}. Equal sums
     * (i.e. concurrent clocks) are separated by the canonical string form, which is
     * deterministic on every replica.
     */
    public static final Comparator<VectorClock> TOTAL_ORDER =
            Comparator.<VectorClock>comparingLong(VectorClock::counterSum)
                      .thenComparing(VectorClock::canonicalForm);

    private long counterSum() {
        long sum = 0;
        for (int v : clock.values()) {
            sum += v;
        }
        return sum;
    }

    /** Stable string form, independent of hash iteration order. */
    public String canonicalForm() {
        return new TreeMap<>(clock).toString();
    }

    /** Defensive copy for serialisation. */
    public Map<String, Integer> asMap() {
        return new HashMap<>(clock);
    }

    public int counterFor(String nodeId) {
        return clock.getOrDefault(nodeId, 0);
    }

    public boolean isEmpty() {
        return clock.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof VectorClock other) && clock.equals(other.clock);
    }

    @Override
    public int hashCode() {
        return clock.hashCode();
    }

    @Override
    public String toString() {
        return canonicalForm();
    }
}
