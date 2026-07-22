package com.syncforge.bus;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The default, zero-dependency broadcaster: this JVM is the entire fleet, so
 * there are no peers to publish to and no peer ops to receive. Everything a room
 * needs already happens in-process via {@code Room.relayLocal}. This is what
 * makes {@code mvn spring-boot:run} a complete, working collaborative server
 * with nothing else installed.
 */
public final class InMemoryBroadcaster implements Broadcaster {

    private final String instanceId;

    public InMemoryBroadcaster() {
        this.instanceId = "srv-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0xffff));
    }

    @Override
    public String instanceId() {
        return instanceId;
    }

    @Override
    public void publish(String roomId, String opJson) {
        // Single instance: no peers. The op has already been relayed locally.
    }

    @Override
    public void onRemoteOp(RemoteOpListener listener) {
        // No peers can ever deliver an op, so the listener is never invoked.
    }
}
