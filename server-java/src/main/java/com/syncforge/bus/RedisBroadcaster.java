package com.syncforge.bus;

import io.lettuce.core.RedisClient;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cross-instance fan-out over Redis Pub/Sub — the blueprint's multi-server path,
 * active only under the {@code redis} profile. Each op is published to a
 * per-room channel wrapped in an envelope that names its origin instance; every
 * instance subscribes to all room channels and applies ops that did not
 * originate with it. Since ops are idempotent CRDT deltas, the fact that Redis
 * Pub/Sub is at-most-once and unordered is harmless — a dropped or reordered op
 * cannot desynchronise a board, it just delays one client's view by a frame.
 *
 * <p>The connection is created lazily in {@link #init()}, so nothing touches
 * Redis unless this profile is on. Host and port come from
 * {@code syncforge.redis.*} (defaults localhost:6379).
 */
public final class RedisBroadcaster implements Broadcaster {

    private static final Logger log = LoggerFactory.getLogger(RedisBroadcaster.class);
    private static final String CHANNEL_PREFIX = "syncforge:ops:";

    private final String host;
    private final int port;
    private final String instanceId;
    private final AtomicReference<RemoteOpListener> listener = new AtomicReference<>();

    private RedisClient client;
    private StatefulRedisPubSubConnection<String, String> publishConn;
    private StatefulRedisPubSubConnection<String, String> subscribeConn;

    public RedisBroadcaster(String host, int port) {
        this.host = host;
        this.port = port;
        this.instanceId = "srv-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0xffff));
    }

    @PostConstruct
    public void init() {
        client = RedisClient.create("redis://" + host + ":" + port);
        publishConn = client.connectPubSub();

        subscribeConn = client.connectPubSub();
        subscribeConn.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String pattern, String channel, String message) {
                handle(channel, message);
            }
        });
        subscribeConn.sync().psubscribe(CHANNEL_PREFIX + "*");
        log.info("RedisBroadcaster {} subscribed to {}*", instanceId, CHANNEL_PREFIX);
    }

    private void handle(String channel, String envelope) {
        // envelope = "<originInstance>\n<opJson>"
        int nl = envelope.indexOf('\n');
        if (nl < 0) {
            return;
        }
        String origin = envelope.substring(0, nl);
        if (instanceId.equals(origin)) {
            return; // our own echo
        }
        String roomId = channel.substring(CHANNEL_PREFIX.length());
        RemoteOpListener l = listener.get();
        if (l != null) {
            l.accept(roomId, envelope.substring(nl + 1));
        }
    }

    @Override
    public String instanceId() {
        return instanceId;
    }

    @Override
    public void publish(String roomId, String opJson) {
        RedisPubSubCommands<String, String> sync = publishConn.sync();
        sync.publish(CHANNEL_PREFIX + roomId, instanceId + "\n" + opJson);
    }

    @Override
    public void onRemoteOp(RemoteOpListener l) {
        listener.set(l);
    }

    @PreDestroy
    public void close() {
        if (subscribeConn != null) subscribeConn.close();
        if (publishConn != null) publishConn.close();
        if (client != null) client.shutdown();
    }
}
