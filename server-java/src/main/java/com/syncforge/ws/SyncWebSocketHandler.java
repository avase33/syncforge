package com.syncforge.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.syncforge.crdt.Operation;
import com.syncforge.room.Room;
import com.syncforge.room.RoomManager;
import com.syncforge.wire.WireCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The user-facing edge of the server. One handler instance serves every
 * connection; per-connection state lives in {@link #contexts}. The flow is:
 *
 * <ol>
 *   <li>On connect, resolve the room from the path and the site id from the
 *       {@code ?site=} query param (or mint one), register the session, and push
 *       a full board snapshot so the client starts converged.</li>
 *   <li>Each {@code op} frame is parsed to an {@link Operation} and handed to the
 *       {@link RoomManager}, which merges it and fans it out locally and to
 *       peers.</li>
 *   <li>Each {@code cursor} frame is relayed as ephemeral presence and forgotten.</li>
 *   <li>On disconnect, the session is removed and a {@code leave} is broadcast.</li>
 * </ol>
 */
public final class SyncWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SyncWebSocketHandler.class);

    private final RoomManager rooms;
    private final WireCodec codec;
    private final Map<String, Ctx> contexts = new ConcurrentHashMap<>();

    public SyncWebSocketHandler(RoomManager rooms, WireCodec codec) {
        this.rooms = rooms;
        this.codec = codec;
    }

    private record Ctx(String roomId, String site, WsSyncSession session) {
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String roomId = roomIdFrom(session.getUri());
        String site = siteFrom(session.getUri());
        WsSyncSession wss = new WsSyncSession(session, site);
        contexts.put(session.getId(), new Ctx(roomId, site, wss));

        Room room = rooms.getOrCreate(roomId);
        room.addSession(wss);

        // Assign the client its site id, then the board it should render.
        ObjectNode hello = codec.mapper().createObjectNode();
        hello.put("t", "hello");
        hello.put("site", site);
        hello.put("instance", rooms.instanceId());
        hello.put("room", roomId);
        wss.send(hello.toString());
        wss.send(codec.encodeSnapshot(rooms.instanceId(), room.snapshot()).toString());

        log.info("session {} joined room {} as {}", session.getId(), roomId, site);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Ctx ctx = contexts.get(session.getId());
        if (ctx == null) {
            return;
        }
        JsonNode frame = codec.mapper().readTree(message.getPayload());
        String type = frame.path("t").asText("");
        switch (type) {
            case "op" -> {
                Operation op = codec.parseOp(frame);
                // Re-encode canonically so every peer sees identical bytes.
                String canonical = codec.encodeOp(op).toString();
                rooms.submitLocalOp(ctx.roomId(), op, session.getId(), canonical);
            }
            case "cursor" -> {
                Room room = rooms.get(ctx.roomId());
                if (room != null) {
                    room.presence().update(ctx.site(), message.getPayload());
                    room.relayLocal(message.getPayload(), session.getId());
                }
            }
            case "ping" -> ctx.session().send("{\"t\":\"pong\"}");
            default -> log.debug("ignoring frame type '{}'", type);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Ctx ctx = contexts.remove(session.getId());
        if (ctx == null) {
            return;
        }
        Room room = rooms.get(ctx.roomId());
        if (room != null) {
            room.removeSession(session.getId());
            room.presence().remove(ctx.site());
            ObjectNode leave = codec.mapper().createObjectNode();
            leave.put("t", "leave");
            leave.put("site", ctx.site());
            room.relayLocal(leave.toString(), session.getId());
            rooms.closeIfEmpty(ctx.roomId());
        }
        log.info("session {} left room {}", session.getId(), ctx.roomId());
    }

    private static String roomIdFrom(URI uri) {
        String path = uri == null ? "" : uri.getPath();
        int idx = path.lastIndexOf('/');
        String room = idx >= 0 ? path.substring(idx + 1) : path;
        return room.isBlank() ? "lobby" : room;
    }

    private static String siteFrom(URI uri) {
        String query = uri == null ? null : uri.getQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals("site")) {
                    String v = pair.substring(eq + 1);
                    if (!v.isBlank()) {
                        return v;
                    }
                }
            }
        }
        return "u-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000, 0xfffff));
    }
}
