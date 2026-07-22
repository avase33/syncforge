package com.syncforge.room;

/**
 * The room's view of one connected client. Deliberately tiny so the room and
 * its tests do not depend on the servlet WebSocket API — the production
 * implementation wraps a {@code WebSocketSession}, and unit tests supply a
 * list-backed fake.
 */
public interface SyncSession {

    /** Stable per-connection id (distinct from the CRDT site/replica id). */
    String id();

    /** The replica/site id this client stamps its writes with. */
    String site();

    /** Deliver a JSON frame to this client. Must tolerate being called from
     * many threads; implementations serialise as needed. */
    void send(String frame);
}
