package com.syncforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * syncforge — a multiplayer state-sync server.
 *
 * <p>The whole product is a CRDT that guarantees every participant converges to
 * the same board regardless of the order edits arrive, wrapped in a
 * virtual-thread WebSocket server and a Redis Pub/Sub fan-out for running more
 * than one instance. Boots into a working, self-populating demo room with no
 * external services.
 */
@SpringBootApplication
public class SyncforgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(SyncforgeApplication.class, args);
    }
}
