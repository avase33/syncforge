package com.syncforge.crdt;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The whole canvas as a CRDT: a map from shape id to {@link Shape}, where each
 * shape is itself a map of {@link LwwRegister}s. It is a state-based CRDT built
 * by composing two joins — a map union on the outside, per-field LWW on the
 * inside — and a composition of joins is still a join, so the board inherits
 * commutativity, associativity and idempotency for free.
 *
 * <p>What that buys, concretely: hand any number of replicas the same bag of
 * operations, let them apply those ops (and each other's snapshots) in whatever
 * arbitrary, duplicated, interleaved order the network delivers them, and every
 * replica ends up {@link #equals equal}. {@code BoardCrdtConvergenceTest}
 * asserts exactly that over 400 randomized schedules of a 1,500-op history.
 *
 * <p>This class is not thread-safe on its own; {@code Room} owns a board and
 * serialises access to it. Keeping the CRDT free of locks keeps it easy to
 * reason about and to test.
 */
public final class BoardCrdt {

    private final Map<String, Shape> shapes;

    public BoardCrdt() {
        this.shapes = new HashMap<>();
    }

    private BoardCrdt(Map<String, Shape> shapes) {
        this.shapes = shapes;
    }

    /** Live view of the shapes. Callers must not mutate the returned map. */
    public Map<String, Shape> shapes() {
        return shapes;
    }

    public Shape shape(String id) {
        return shapes.get(id);
    }

    public int size() {
        return shapes.size();
    }

    /**
     * Apply a single operation: merge its field fragment into the addressed
     * shape, creating the shape if this is the first time it has been seen.
     * Idempotent and commutative, because it delegates to the same LWW joins as
     * {@link #merge}.
     */
    public void apply(Operation op) {
        shapes.merge(op.shapeId(), op.asShape().copy(), Shape::merge);
    }

    /**
     * Fold an entire other board into this one, shape by shape. Used for
     * snapshot catch-up when a client joins and for cross-instance state
     * reconciliation. Order-independent.
     */
    public BoardCrdt merge(BoardCrdt other) {
        for (Map.Entry<String, Shape> e : other.shapes.entrySet()) {
            shapes.merge(e.getKey(), e.getValue().copy(), Shape::merge);
        }
        return this;
    }

    /** An independent copy, safe to snapshot and ship to another replica. */
    public BoardCrdt copy() {
        Map<String, Shape> out = new HashMap<>(shapes.size() * 2);
        for (Map.Entry<String, Shape> e : shapes.entrySet()) {
            out.put(e.getKey(), e.getValue().copy());
        }
        return new BoardCrdt(out);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BoardCrdt other)) return false;
        return shapes.equals(other.shapes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(shapes);
    }

    @Override
    public String toString() {
        return "BoardCrdt" + new TreeMap<>(shapes);
    }
}
