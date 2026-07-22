package com.syncforge.crdt;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One object on the canvas — a rectangle, ellipse, line, or sticky note —
 * modelled as a bag of {@link LwwRegister}s keyed by field name
 * ({@code x}, {@code y}, {@code w}, {@code h}, {@code color}, {@code type},
 * {@code z}, {@code deleted}).
 *
 * <p>Making every field an independent register is what lets two users edit the
 * <em>same shape</em> without clobbering each other: if Alice drags it while Bob
 * recolours it, Alice's writes land on {@code x}/{@code y} and Bob's on
 * {@code color}, and the merge keeps both. A coarser "whole shape is one
 * register" model would make the later of the two edits erase the other.
 *
 * <p>Deletion is not removal — it is an LWW write of {@code deleted = true}. A
 * tombstone rather than an erase, so that a delete and a concurrent edit still
 * converge (whichever has the greater stamp wins), and an undo can resurrect the
 * shape with a later {@code deleted = false}.
 */
public final class Shape {

    private final String id;
    private final Map<String, LwwRegister> fields;

    public Shape(String id) {
        this(id, new HashMap<>());
    }

    public Shape(String id, Map<String, LwwRegister> fields) {
        this.id = Objects.requireNonNull(id, "id");
        this.fields = new HashMap<>(fields);
    }

    public String id() {
        return id;
    }

    /** Live view of the registers. Callers must not mutate the returned map. */
    public Map<String, LwwRegister> fields() {
        return fields;
    }

    public LwwRegister field(String name) {
        return fields.get(name);
    }

    public Object value(String name) {
        LwwRegister r = fields.get(name);
        return r == null ? null : r.value();
    }

    public boolean deleted() {
        return Boolean.TRUE.equals(value("deleted"));
    }

    /** Set (or overwrite) a field with a fresh register. Mutates in place. */
    public void put(String name, Object value, Stamp stamp) {
        fields.put(name, new LwwRegister(value, stamp));
    }

    /**
     * Fold another view of this shape into this one, register by register.
     * Fields present on only one side survive; fields present on both are
     * resolved by {@link LwwRegister#merge}. Order-independent by construction.
     */
    public Shape merge(Shape other) {
        for (Map.Entry<String, LwwRegister> e : other.fields.entrySet()) {
            LwwRegister mine = this.fields.get(e.getKey());
            this.fields.put(e.getKey(), mine == null ? e.getValue() : mine.merge(e.getValue()));
        }
        return this;
    }

    /** A deep-ish copy safe to hand to another replica's board. */
    public Shape copy() {
        return new Shape(id, new HashMap<>(fields));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Shape other)) return false;
        return id.equals(other.id) && fields.equals(other.fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, fields);
    }

    @Override
    public String toString() {
        // Sorted for a stable, diff-friendly rendering in test failures.
        return id + new TreeMap<>(fields);
    }
}
