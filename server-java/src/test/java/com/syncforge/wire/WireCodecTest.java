package com.syncforge.wire;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.crdt.BoardCrdt;
import com.syncforge.crdt.LwwRegister;
import com.syncforge.crdt.Operation;
import com.syncforge.crdt.Stamp;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Serialization must be value- and type-preserving, because board equality —
 * and therefore convergence across the wire — depends on a register holding the
 * same Java value after a round trip. A number that came back as an int where it
 * left as a double would make two boards that are "the same" compare unequal.
 */
class WireCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WireCodec codec = new WireCodec(mapper);

    private Operation sampleOp() {
        return new Operation("s1", Map.of(
                "x", new LwwRegister(140.0, new Stamp(4, "alice")),
                "color", new LwwRegister("#3b82f6", new Stamp(4, "alice")),
                "deleted", new LwwRegister(false, new Stamp(2, "bob"))));
    }

    @Test
    void opRoundTripsThroughJsonUnchanged() throws Exception {
        Operation op = sampleOp();
        JsonNode frame = mapper.readTree(codec.encodeOp(op).toString());
        Operation parsed = codec.parseOp(frame);

        BoardCrdt fromOriginal = new BoardCrdt();
        fromOriginal.apply(op);
        BoardCrdt fromParsed = new BoardCrdt();
        fromParsed.apply(parsed);

        assertEquals(fromOriginal, fromParsed);
        assertEquals(140.0, parsed.fields().get("x").value());
        assertEquals(false, parsed.fields().get("deleted").value());
        assertEquals("#3b82f6", parsed.fields().get("color").value());
        assertEquals(new Stamp(4, "alice"), parsed.fields().get("x").stamp());
    }

    @Test
    void snapshotRoundTripsThroughJsonUnchanged() throws Exception {
        BoardCrdt board = new BoardCrdt();
        board.apply(sampleOp());
        board.apply(new Operation("s2", Map.of(
                "type", new LwwRegister("ellipse", new Stamp(1, "carol")),
                "y", new LwwRegister(88.0, new Stamp(1, "carol")))));

        JsonNode snapshot = mapper.readTree(codec.encodeSnapshot("srv-1", board).toString());
        BoardCrdt restored = codec.parseBoard(snapshot.get("board"));

        assertEquals(board, restored);
    }
}
