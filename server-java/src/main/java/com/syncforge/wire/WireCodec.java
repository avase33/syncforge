package com.syncforge.wire;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.syncforge.crdt.BoardCrdt;
import com.syncforge.crdt.LwwRegister;
import com.syncforge.crdt.Operation;
import com.syncforge.crdt.Shape;
import com.syncforge.crdt.Stamp;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Translates between the CRDT types and the JSON frames defined in
 * {@code proto/protocol.md}. The board layer stays free of Jackson; all wire
 * knowledge lives here so the protocol can change without touching the algebra.
 *
 * <p>Numbers are normalised to {@code double} on decode so a value has one
 * stable Java type regardless of whether the client sent {@code 120} or
 * {@code 120.0}; strings and booleans pass through as-is. Which value wins is
 * never decided by the value anyway — only by its {@link Stamp}.
 */
public final class WireCodec {

    private final ObjectMapper json;

    public WireCodec(ObjectMapper json) {
        this.json = json;
    }

    // ---- decode -----------------------------------------------------------

    /** Parse an {@code {"t":"op","id":...,"fields":{...}}} frame. */
    public Operation parseOp(JsonNode frame) {
        String id = frame.get("id").asText();
        Map<String, LwwRegister> fields = parseFields(frame.get("fields"));
        return new Operation(id, fields);
    }

    /** Parse a {@code {"shapes":{id:{fields:{...}}}}} board body. */
    public BoardCrdt parseBoard(JsonNode boardNode) {
        BoardCrdt board = new BoardCrdt();
        JsonNode shapes = boardNode.get("shapes");
        if (shapes != null) {
            Iterator<Map.Entry<String, JsonNode>> it = shapes.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                Shape shape = new Shape(e.getKey(), parseFields(e.getValue().get("fields")));
                board.shapes().put(e.getKey(), shape);
            }
        }
        return board;
    }

    private Map<String, LwwRegister> parseFields(JsonNode fieldsNode) {
        Map<String, LwwRegister> fields = new HashMap<>();
        if (fieldsNode == null) {
            return fields;
        }
        Iterator<Map.Entry<String, JsonNode>> it = fieldsNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode reg = e.getValue();
            Object value = decodeValue(reg.get("v"));
            Stamp stamp = new Stamp(reg.get("c").asLong(), reg.get("r").asText());
            fields.put(e.getKey(), new LwwRegister(value, stamp));
        }
        return fields;
    }

    private Object decodeValue(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isTextual()) return v.asText();
        if (v.isBoolean()) return v.asBoolean();
        if (v.isNumber()) return v.asDouble();
        return v.asText();
    }

    // ---- encode -----------------------------------------------------------

    public ObjectNode encodeOp(Operation op) {
        ObjectNode frame = json.createObjectNode();
        frame.put("t", "op");
        frame.put("id", op.shapeId());
        frame.set("fields", encodeFields(op.fields()));
        return frame;
    }

    public ObjectNode encodeSnapshot(String site, BoardCrdt board) {
        ObjectNode frame = json.createObjectNode();
        frame.put("t", "snapshot");
        frame.put("site", site);
        ObjectNode boardNode = json.createObjectNode();
        ObjectNode shapes = json.createObjectNode();
        for (Map.Entry<String, Shape> e : board.shapes().entrySet()) {
            ObjectNode shapeNode = json.createObjectNode();
            shapeNode.set("fields", encodeFields(e.getValue().fields()));
            shapes.set(e.getKey(), shapeNode);
        }
        boardNode.set("shapes", shapes);
        frame.set("board", boardNode);
        return frame;
    }

    private ObjectNode encodeFields(Map<String, LwwRegister> fields) {
        ObjectNode out = json.createObjectNode();
        for (Map.Entry<String, LwwRegister> e : fields.entrySet()) {
            LwwRegister r = e.getValue();
            ObjectNode reg = json.createObjectNode();
            putValue(reg, "v", r.value());
            reg.put("c", r.stamp().counter());
            reg.put("r", r.stamp().replica());
            out.set(e.getKey(), reg);
        }
        return out;
    }

    private void putValue(ObjectNode node, String key, Object value) {
        if (value == null) {
            node.putNull(key);
        } else if (value instanceof String s) {
            node.put(key, s);
        } else if (value instanceof Boolean b) {
            node.put(key, b);
        } else if (value instanceof Double d) {
            node.put(key, d);
        } else if (value instanceof Number n) {
            node.put(key, n.doubleValue());
        } else {
            node.put(key, value.toString());
        }
    }

    public ObjectMapper mapper() {
        return json;
    }

    /** Convenience for tests and diagnostics. */
    public ArrayNode emptyArray() {
        return json.createArrayNode();
    }
}
