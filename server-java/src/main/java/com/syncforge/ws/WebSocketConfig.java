package com.syncforge.ws;

import com.syncforge.room.RoomManager;
import com.syncforge.wire.WireCodec;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Publishes the sync handler at {@code /rooms/{roomId}} and allows all origins,
 * since the reference dashboard runs on a different port in development. Tighten
 * {@code setAllowedOrigins} for a real deployment.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RoomManager rooms;
    private final WireCodec codec;

    public WebSocketConfig(RoomManager rooms, WireCodec codec) {
        this.rooms = rooms;
        this.codec = codec;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new SyncWebSocketHandler(rooms, codec), "/rooms/*")
                .setAllowedOriginPatterns("*");
    }
}
