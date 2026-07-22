package com.syncforge.crdt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A register is only correct if its merge is a semilattice join. These tests pin
 * the three laws directly; the board-level convergence test then relies on them.
 */
class LwwRegisterTest {

    private static LwwRegister reg(Object v, long c, String r) {
        return new LwwRegister(v, new Stamp(c, r));
    }

    @Test
    void mergeIsCommutative() {
        LwwRegister a = reg("red", 5, "alice");
        LwwRegister b = reg("blue", 6, "bob");
        assertEquals(a.merge(b), b.merge(a));
        assertEquals("blue", a.merge(b).value());
    }

    @Test
    void mergeIsIdempotent() {
        LwwRegister a = reg("red", 5, "alice");
        assertEquals(a, a.merge(a));
    }

    @Test
    void mergeIsAssociative() {
        LwwRegister a = reg("a", 3, "s1");
        LwwRegister b = reg("b", 7, "s2");
        LwwRegister c = reg("c", 5, "s3");
        assertEquals(a.merge(b).merge(c), a.merge(b.merge(c)));
        assertEquals("b", a.merge(b).merge(c).value());
    }

    @Test
    void higherStampWinsRegardlessOfMergeOrder() {
        LwwRegister older = reg(10.0, 4, "alice");
        LwwRegister newer = reg(99.0, 9, "alice");
        assertEquals(99.0, older.merge(newer).value());
        assertEquals(99.0, newer.merge(older).value());
    }
}
