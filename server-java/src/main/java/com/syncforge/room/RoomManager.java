package com.syncforge.room;

import com.fasterxml.jackson.databind.JsonNode;
import com.syncforge.bus.Broadcaster;
import com.syncforge.crdt.Operation;
import com.syncforge.wire.WireCodec;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The registry of live rooms and the single place that decides what happens to
 * an operation. It bridges three sources into one authoritative board per room:
 * local WebSocket edits, the read API, and — under the {@code redis} profile —
 * ops replicated from peer instances.
 *
 * <p>The local and remote paths intentionally converge on {@link Room#apply}:
 * an op is an idempotent CRDT delta, so "apply it and relay it" is correct
 * whether the op came from a socket on this box or a Redis channel from another.
 */
@Component
public final class RoomManager {

    private static final Logger log = LoggerFactory.getLogger(RoomManager.class);

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Broadcaster broadcaster;
    private final WireCodec codec;

    public RoomManager(Broadcaster broadcaster, WireCodec codec) {
        this.broadcaster = broadcaster;
        this.codec = codec;
    }

    @PostConstruct
    void wireRemoteOps() {
        broadcaster.onRemoteOp((roomId, opJson) -> {
            Room room = rooms.get(roomId);
            if (room == null) {
                return; // no local sessions care about this room
            }
            try {
                JsonNode frame = codec.mapper().readTree(opJson);
                Operation op = codec.parseOp(frame);
                room.apply(op);
                // A peer op has no local originator, so every local session sees it.
                room.relayLocal(opJson, null);
            } catch (Exception e) {
                log.warn("dropping malformed remote op for room {}: {}", roomId, e.toString());
            }
        });
        log.info("RoomManager bound to broadcaster instance {}", broadcaster.instanceId());
    }

    public String instanceId() {
        return broadcaster.instanceId();
    }

    public Room getOrCreate(String roomId) {
        return rooms.computeIfAbsent(roomId, id -> {
            log.info("opening room {}", id);
            return new Room(id, broadcaster.instanceId());
        });
    }

    public Room get(String roomId) {
        return rooms.get(roomId);
    }

    public Collection<Room> rooms() {
        return rooms.values();
    }

    /**
     * The local edit path: merge the op into the board, relay it to the other
     * sessions in this room, and publish it to peer instances. Called from the
     * WebSocket handler on a virtual thread.
     */
    public void submitLocalOp(String roomId, Operation op, String originSessionId, String opFrame) {
        Room room = getOrCreate(roomId);
        room.apply(op);
        room.relayLocal(opFrame, originSessionId);
        broadcaster.publish(roomId, opFrame);
    }

    /** Drop an empty room so idle rooms do not accumulate. */
    public void closeIfEmpty(String roomId) {
        rooms.computeIfPresent(roomId, (id, room) -> {
            if (room.sessionCount() == 0) {
                log.info("closing empty room {}", id);
                return null;
            }
            return room;
        });
    }
}
