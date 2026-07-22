package com.syncforge.bus;

/**
 * The seam between one server instance and the rest of the fleet. A single
 * instance already fans an operation out to its own connected sessions; the
 * broadcaster is what carries that same operation to sessions attached to
 * <em>other</em> instances so that, say, a user on {@code srv-1} and a user on
 * {@code srv-2} editing the same room still converge.
 *
 * <p>Two implementations:
 * <ul>
 *   <li>{@link InMemoryBroadcaster} — the default. There is only one instance,
 *       so there is nothing to publish to; the whole fleet is this JVM.</li>
 *   <li>{@code RedisBroadcaster} — active under the {@code redis} profile.
 *       Publishes each op to a Redis channel and delivers ops other instances
 *       publish. This is the blueprint's Redis Pub/Sub layer.</li>
 * </ul>
 *
 * <p>Because ops are idempotent CRDT deltas, the fan-out needs no ordering or
 * exactly-once guarantee: a duplicate or a reordering cannot corrupt a board.
 */
public interface Broadcaster {

    /** This instance's id, e.g. {@code "srv-7f3a"}. */
    String instanceId();

    /** Publish an op (raw JSON frame) originating on this instance to peers. */
    void publish(String roomId, String opJson);

    /** Register the sink that applies ops arriving from peer instances. */
    void onRemoteOp(RemoteOpListener listener);

    @FunctionalInterface
    interface RemoteOpListener {
        void accept(String roomId, String opJson);
    }
}
