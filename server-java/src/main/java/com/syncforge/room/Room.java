package com.syncforge.room;

import com.syncforge.crdt.BoardCrdt;
import com.syncforge.crdt.LamportClock;
import com.syncforge.crdt.Operation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A single collaborative board plus everyone currently looking at it. Sessions
 * come and go on their own virtual threads, so every mutation of the board runs
 * under one short lock; the CRDT itself is lock-free by design, and this is the
 * one place that serialises access to it.
 *
 * <p>The board is authoritative state. Presence (cursors) is deliberately kept
 * out of it — see {@link #presence} — because a live cursor is worthless a
 * second later and must never end up in a snapshot or survive a disconnect.
 */
public final class Room {

    private final String roomId;
    private final BoardCrdt board = new BoardCrdt();
    private final LamportClock clock;
    private final ReentrantLock boardLock = new ReentrantLock();

    private final Map<String, SyncSession> sessions = new ConcurrentHashMap<>();
    private final Presence presence = new Presence();
    private final AtomicLong opsApplied = new AtomicLong();

    public Room(String roomId, String instanceId) {
        this.roomId = roomId;
        this.clock = new LamportClock(instanceId);
    }

    public String roomId() {
        return roomId;
    }

    public int sessionCount() {
        return sessions.size();
    }

    public long opsApplied() {
        return opsApplied.get();
    }

    public void addSession(SyncSession session) {
        sessions.put(session.id(), session);
    }

    /** Remove a session and return its last known presence for a leave notice. */
    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
    }

    public Presence presence() {
        return presence;
    }

    /**
     * Merge an operation into the authoritative board. Idempotent: replaying the
     * same op leaves the board unchanged, so it is safe to call from the local
     * WebSocket path and the cross-instance path without deduplication. The
     * clock witnesses the op's stamps so any board-originated write later
     * dominates what it has seen.
     */
    public void apply(Operation op) {
        boardLock.lock();
        try {
            board.apply(op);
            if (op.maxStamp() != null) {
                clock.witness(op.maxStamp());
            }
            opsApplied.incrementAndGet();
        } finally {
            boardLock.unlock();
        }
    }

    /** An independent copy of the board for a joining client's snapshot. */
    public BoardCrdt snapshot() {
        boardLock.lock();
        try {
            return board.copy();
        } finally {
            boardLock.unlock();
        }
    }

    /**
     * Send a frame to every local session except {@code exceptSessionId}
     * (typically the originator, which has already applied the change
     * optimistically). A dead session's failure to receive is swallowed; the
     * WebSocket layer is responsible for reaping it.
     */
    public void relayLocal(String frame, String exceptSessionId) {
        for (SyncSession s : sessions.values()) {
            if (!s.id().equals(exceptSessionId)) {
                try {
                    s.send(frame);
                } catch (RuntimeException ignored) {
                    // Broken pipe etc.; afterConnectionClosed will clean up.
                }
            }
        }
    }

    /** Snapshot of live board stats for the read API. */
    public Stats stats() {
        boardLock.lock();
        try {
            long live = board.shapes().values().stream().filter(s -> !s.deleted()).count();
            return new Stats(roomId, board.size(), live, sessions.size(), opsApplied.get());
        } finally {
            boardLock.unlock();
        }
    }

    public record Stats(String roomId, int shapes, long liveShapes, int sessions, long opsApplied) {
    }
}
