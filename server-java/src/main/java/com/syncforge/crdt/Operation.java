package com.syncforge.crdt;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A delta: the changed fields of a single shape, each already carrying the
 * stamp of the write that produced it. This is what actually travels on the
 * wire — never a whole board, just "shape {@code id}, these fields changed."
 *
 * <p>An operation is really a one-shape {@link Shape} fragment, and applying it
 * is the exact same per-field LWW join used by state merge (see
 * {@link BoardCrdt#apply}). That equivalence is deliberate: because op-apply and
 * state-merge are the same join, the system is simultaneously an operation-based
 * and a state-based CRDT, and neither ordering, duplication, nor a mix of live
 * ops and a catch-up snapshot can pull two replicas apart.
 */
public final class Operation {

    private final String shapeId;
    private final Map<String, LwwRegister> fields;

    public Operation(String shapeId, Map<String, LwwRegister> fields) {
        this.shapeId = Objects.requireNonNull(shapeId, "shapeId");
        this.fields = new HashMap<>(fields);
    }

    public String shapeId() {
        return shapeId;
    }

    public Map<String, LwwRegister> fields() {
        return fields;
    }

    /** The greatest stamp carried by this op, or {@code null} if it is empty. */
    public Stamp maxStamp() {
        Stamp max = null;
        for (LwwRegister r : fields.values()) {
            if (max == null || r.stamp().dominates(max)) {
                max = r.stamp();
            }
        }
        return max;
    }

    /** Render this op as the shape fragment it represents. */
    public Shape asShape() {
        return new Shape(shapeId, fields);
    }

    @Override
    public String toString() {
        return "op(" + shapeId + ", " + fields + ")";
    }
}
