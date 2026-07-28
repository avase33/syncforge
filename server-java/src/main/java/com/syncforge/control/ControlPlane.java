package com.syncforge.control;

/**
 * The internal control plane — the Java view of the contract sketched in
 * {@code proto/control.proto}. Board traffic rides WebSocket; server-to-server
 * and service-to-service concerns (which instance owns a room, is this token
 * allowed) are separated out behind this interface so that a multi-node
 * deployment has a single seam to bind a transport to.
 *
 * <p>{@link InProcessControlPlane} is the only implementation that ships here:
 * one node owns every room and auth is permissive. Nothing in this repository
 * speaks gRPC — the {@code .proto} is an unbuilt design document, not generated
 * code — so this interface is best read as a boundary that keeps room ownership
 * and auth out of the transport layer, not as an abstraction over two working
 * transports.
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
