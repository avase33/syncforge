package com.syncforge.control;

import com.syncforge.room.RoomManager;
import org.springframework.stereotype.Component;

/**
 * The default — and currently the only — {@link ControlPlane} implementation: a
 * single node owns every room, every token is accepted, and the site id is taken
 * from the token when one is present.
 *
 * <p><strong>There is no gRPC implementation in this repository.</strong>
 * {@code proto/control.proto} records the intended wire contract for this
 * interface, but it is a documentation artifact only: the build declares no
 * protobuf plugin and no grpc-java dependency, so nothing is generated from it
 * and no {@code grpc} Maven profile exists. Sharding rooms across a fleet would
 * mean adding those and writing a gRPC-backed implementation of this interface;
 * that work has not been done. Single-node syncforge does not need it.
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
