package com.syncforge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.bus.Broadcaster;
import com.syncforge.bus.InMemoryBroadcaster;
import com.syncforge.bus.RedisBroadcaster;
import com.syncforge.wire.WireCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Wires the two pluggable seams. The {@link Broadcaster} bean is chosen by
 * profile: the default single-node {@link InMemoryBroadcaster}, or the
 * {@link RedisBroadcaster} when the {@code redis} profile is active. Nothing
 * else in the app knows which one it got.
 */
@Configuration
public class SyncforgeConfig {

    @Bean
    public WireCodec wireCodec(ObjectMapper mapper) {
        return new WireCodec(mapper);
    }

    @Bean
    @Profile("!redis")
    public Broadcaster inMemoryBroadcaster() {
        return new InMemoryBroadcaster();
    }

    @Bean
    @Profile("redis")
    public Broadcaster redisBroadcaster(
            @Value("${syncforge.redis.host:localhost}") String host,
            @Value("${syncforge.redis.port:6379}") int port) {
        return new RedisBroadcaster(host, port);
    }
}
