package com.syncforge.control;

/**
 * The internal control plane — the Java view of {@code proto/control.proto}.
 * Board traffic rides WebSocket; server-to-server and service-to-service
 * concerns (which instance owns a room, is this token allowed) ride this typed
 * contract, which a multi-node deployment binds to gRPC.
 *
 * <p>The default build supplies {@link InProcessControlPlane}: one node owns
 * every room and auth is permissive, which is exactly right for local
 * development and keeps the reference build free of a gRPC runtime.
 */
public interface ControlPlane {

    /** Which instance currently owns {@code roomId} (sticky routing). */
    RoomLocation locateRoom(String roomId);

    /** Validate a token for a room before a WebSocket upgrade is accepted. */
    AuthResult authenticate(String token, String roomId);

    record RoomLocation(String instanceId, String address, boolean created) {
    }

    record AuthResult(boolean allowed, String siteId, String displayName) {
    }
}
