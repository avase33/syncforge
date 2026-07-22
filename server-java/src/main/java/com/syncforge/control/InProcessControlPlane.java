package com.syncforge.control;

import com.syncforge.room.RoomManager;
import org.springframework.stereotype.Component;

/**
 * The default control plane: a single node owns every room and every token is
 * accepted, with the site id taken from the token when present. This is the
 * implementation the local build runs; swapping in a gRPC-backed
 * {@code GrpcControlPlane} (behind the {@code grpc} profile) is the only change
 * needed to shard rooms across a fleet.
 */
@Component
public class InProcessControlPlane implements ControlPlane {

    private final RoomManager rooms;

    public InProcessControlPlane(RoomManager rooms) {
        this.rooms = rooms;
    }

    @Override
    public RoomLocation locateRoom(String roomId) {
        boolean created = rooms.get(roomId) == null;
        return new RoomLocation(rooms.instanceId(), "in-process", created);
    }

    @Override
    public AuthResult authenticate(String token, String roomId) {
        String site = (token == null || token.isBlank()) ? "anon" : token;
        return new AuthResult(true, site, site);
    }
}
