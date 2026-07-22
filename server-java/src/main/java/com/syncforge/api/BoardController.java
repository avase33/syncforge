package com.syncforge.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.syncforge.room.Room;
import com.syncforge.room.RoomManager;
import com.syncforge.wire.WireCodec;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * A small read-only HTTP surface over the live rooms — enough for the dashboard
 * to list rooms and fetch a board without opening a socket, and for health
 * checks. All mutation goes through WebSocket ops; there is deliberately no
 * write endpoint here, because a REST write would bypass the CRDT stamping that
 * keeps everything convergent.
 */
@RestController
@RequestMapping("/api")
public class BoardController {

    private final RoomManager rooms;
    private final WireCodec codec;

    public BoardController(RoomManager rooms, WireCodec codec) {
        this.rooms = rooms;
        this.codec = codec;
    }

    @GetMapping("/health")
    public ObjectNode health() {
        ObjectNode n = codec.mapper().createObjectNode();
        n.put("status", "ok");
        n.put("instance", rooms.instanceId());
        n.put("rooms", rooms.rooms().size());
        return n;
    }

    @GetMapping("/rooms")
    public List<Room.Stats> rooms() {
        return rooms.rooms().stream().map(Room::stats).toList();
    }

    @GetMapping("/rooms/{roomId}/stats")
    public ResponseEntity<Room.Stats> stats(@PathVariable String roomId) {
        Room room = rooms.get(roomId);
        return room == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(room.stats());
    }

    /** The full board as the snapshot frame a joining socket would receive. */
    @GetMapping("/rooms/{roomId}/snapshot")
    public ResponseEntity<ObjectNode> snapshot(@PathVariable String roomId) {
        Room room = rooms.get(roomId);
        if (room == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(codec.encodeSnapshot(rooms.instanceId(), room.snapshot()));
    }
}
