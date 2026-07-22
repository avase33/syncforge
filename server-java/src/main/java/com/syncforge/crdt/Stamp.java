package com.syncforge.crdt;

import java.util.Objects;

/**
 * A Lamport stamp: a logical counter plus the id of the replica that produced
 * it. Stamps impose a <em>total</em> order across replicas that never
 * communicate directly — compare the counters, and break ties on the replica
 * id. That total order is the entire basis of last-write-wins: given two
 * concurrent writes to the same field, "last" is defined as the greater stamp,
 * and every replica computes the same answer because the order is total and
 * deterministic.
 *
 * <p>The tie-break on replica id is not cosmetic. Two replicas can legitimately
 * assign the same counter to concurrent writes; without a deterministic
 * tie-break they would each keep their own value and diverge forever. Ordering
 * on the id makes the choice arbitrary but <em>identical everywhere</em>, which
 * is what convergence requires.
 */
public final class Stamp implements Comparable<Stamp> {

    private final long counter;
    private final String replica;

    public Stamp(long counter, String replica) {
        this.counter = counter;
        this.replica = Objects.requireNonNull(replica, "replica");
    }

    public long counter() {
        return counter;
    }

    public String replica() {
        return replica;
    }

    /** {@code true} if this stamp is strictly greater than {@code other}. */
    public boolean dominates(Stamp other) {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(Stamp other) {
        int byCounter = Long.compare(this.counter, other.counter);
        if (byCounter != 0) {
            return byCounter;
        }
        return this.replica.compareTo(other.replica);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Stamp other)) return false;
        return counter == other.counter && replica.equals(other.replica);
    }

    @Override
    public int hashCode() {
        return Objects.hash(counter, replica);
    }

    @Override
    public String toString() {
        return counter + "@" + replica;
    }
}
