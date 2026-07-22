package com.syncforge.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.bus.InMemoryBroadcaster;
import com.syncforge.crdt.LwwRegister;
import com.syncforge.crdt.Operation;
import com.syncforge.crdt.Stamp;
import com.syncforge.wire.WireCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomManagerTest {

    private final WireCodec codec = new WireCodec(new ObjectMapper());

    /** A SyncSession that just records what it was sent. */
    private static final class RecordingSession implements SyncSession {
        final String id;
        final List<String> received = new ArrayList<>();
        RecordingSession(String id) { this.id = id; }
        public String id() { return id; }
        public String site() { return id; }
        public void send(String frame) { received.add(frame); }
    }

    @Test
    void localOpIsMergedAndRelayedToEveryoneButTheOriginator() {
        RoomManager rooms = new RoomManager(new InMemoryBroadcaster(), codec);
        Room room = rooms.getOrCreate("r1");

        RecordingSession author = new RecordingSession("author");
        RecordingSession peer = new RecordingSession("peer");
        room.addSession(author);
        room.addSession(peer);

        Operation op = new Operation("s1", Map.of(
                "x", new LwwRegister(42.0, new Stamp(1, "author"))));
        String frame = codec.encodeOp(op).toString();
        rooms.submitLocalOp("r1", op, author.id(), frame);

        // The board saw it...
        assertEquals(42.0, room.snapshot().shape("s1").value("x"));
        // ...the peer was told...
        assertEquals(List.of(frame), peer.received);
        // ...and the author, who already applied it optimistically, was not.
        assertTrue(author.received.isEmpty());
    }

    @Test
    void emptyRoomsAreReaped() {
        RoomManager rooms = new RoomManager(new InMemoryBroadcaster(), codec);
        Room room = rooms.getOrCreate("temp");
        RecordingSession s = new RecordingSession("s");
        room.addSession(s);

        rooms.closeIfEmpty("temp");           // still occupied
        assertEquals(1, rooms.rooms().size());

        room.removeSession("s");
        rooms.closeIfEmpty("temp");           // now empty
        assertTrue(rooms.rooms().isEmpty());
    }
}
