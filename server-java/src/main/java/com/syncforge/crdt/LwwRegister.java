package com.syncforge.crdt;

import java.util.Objects;

/**
 * A last-write-wins register: a single value tagged with the {@link Stamp} of
 * the write that produced it. This is the atom of the whole CRDT — every mutable
 * property of every shape is one of these.
 *
 * <p>{@link #merge(LwwRegister)} is a join over the total order of stamps: keep
 * whichever value carries the greater stamp. That single operation is
 * <strong>commutative</strong> (max ignores argument order),
 * <strong>associative</strong>, and <strong>idempotent</strong>
 * ({@code merge(x, x) == x}). Those three properties are exactly the definition
 * of a state-based CRDT, and they are why replicas that apply the same set of
 * writes in any order — with duplicates, out of sequence, whatever — always land
 * on the same value.
 *
 * <p>Values are stored as plain JSON scalars ({@link String}, {@link Number},
 * {@link Boolean}) so a register round-trips through the wire protocol without
 * the CRDT layer needing to know anything about shapes.
 */
public final class LwwRegister {

    private final Object value;
    private final Stamp stamp;

    public LwwRegister(Object value, Stamp stamp) {
        this.value = value;
        this.stamp = Objects.requireNonNull(stamp, "stamp");
    }

    public Object value() {
        return value;
    }

    public Stamp stamp() {
        return stamp;
    }

    /**
     * Join two registers. Returns the one with the dominating stamp; on the
     * (impossible-for-distinct-writes) exact tie, returns {@code this}, which is
     * still deterministic because equal stamps imply equal writes.
     */
    public LwwRegister merge(LwwRegister other) {
        if (other == null) {
            return this;
        }
        return other.stamp.dominates(this.stamp) ? other : this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LwwRegister other)) return false;
        return Objects.equals(value, other.value) && stamp.equals(other.stamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, stamp);
    }

    @Override
    public String toString() {
        return "{" + value + " @ " + stamp + "}";
    }
}
