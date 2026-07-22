package com.syncforge.crdt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StampTest {

    @Test
    void higherCounterAlwaysWins() {
        Stamp low = new Stamp(3, "zzz");
        Stamp high = new Stamp(4, "aaa");
        // Counter dominates even though "aaa" < "zzz" lexicographically.
        assertTrue(high.dominates(low));
        assertFalse(low.dominates(high));
    }

    @Test
    void equalCounterBreaksTieOnReplicaDeterministically() {
        Stamp a = new Stamp(7, "alice");
        Stamp b = new Stamp(7, "bob");
        // Some order must be picked, and it must be the same on every node.
        assertTrue(b.dominates(a));   // "bob" > "alice"
        assertFalse(a.dominates(b));
    }

    @Test
    void aStampNeverDominatesItself() {
        Stamp s = new Stamp(5, "alice");
        assertFalse(s.dominates(new Stamp(5, "alice")));
    }
}
