package com.syncforge.crdt;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A thread-safe Lamport clock scoped to one replica (one server instance, or
 * one browser tab). It hands out monotonically increasing {@link Stamp}s for
 * local writes and advances itself whenever it witnesses a remote stamp, so a
 * local write that <em>causally follows</em> something it has already seen is
 * guaranteed to carry a greater stamp.
 *
 * <p>The rule is the classic one: local events increment the counter; observing
 * a remote counter fast-forwards the local counter to at least that value, so
 * the next local {@link #tick()} strictly dominates it.
 */
public final class LamportClock {

    private final String replica;
    private final AtomicLong counter;

    public LamportClock(String replica) {
        this(replica, 0);
    }

    public LamportClock(String replica, long start) {
        this.replica = replica;
        this.counter = new AtomicLong(start);
    }

    public String replica() {
        return replica;
    }

    /** Produce the next stamp for a local write. */
    public Stamp tick() {
        return new Stamp(counter.incrementAndGet(), replica);
    }

    /**
     * Record that a remote stamp has been seen, advancing the clock so future
     * local writes dominate it. Safe to call from many threads.
     */
    public void witness(Stamp remote) {
        long seen = remote.counter();
        counter.updateAndGet(cur -> Math.max(cur, seen));
    }

    public long peek() {
        return counter.get();
    }
}
