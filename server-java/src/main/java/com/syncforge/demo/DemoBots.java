package com.syncforge.demo;

import com.syncforge.crdt.LamportClock;
import com.syncforge.crdt.LwwRegister;
import com.syncforge.crdt.Operation;
import com.syncforge.room.RoomManager;
import com.syncforge.wire.WireCodec;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Three synthetic collaborators that draw and drag shapes in the {@code demo}
 * room, so the dashboard is alive the moment the server boots — the same idea as
 * a factory-floor simulator, but for a whiteboard. Each bot is its own replica
 * with its own {@link LamportClock}, stamping ops exactly as a browser tab
 * would, then feeding them through the normal {@link RoomManager} path. Open the
 * client on room {@code demo} and you are watching real CRDT merges, not a
 * canned animation.
 *
 * <p>Each bot runs on a virtual thread — the whole server is built around
 * Loom — so idling three (or three thousand) of them costs almost nothing.
 * Disable with {@code syncforge.demo.enabled=false}.
 */
@Component
@ConditionalOnProperty(value = "syncforge.demo.enabled", havingValue = "true", matchIfMissing = true)
public final class DemoBots {

    private static final Logger log = LoggerFactory.getLogger(DemoBots.class);
    private static final String ROOM = "demo";

    private final RoomManager rooms;
    private final WireCodec codec;
    private volatile boolean running = true;

    private record Bot(String site, String shapeId, String color, LamportClock clock) {
    }

    private final List<Bot> bots = List.of(
            new Bot("bot-alice", "shape-alice", "#ef4444", new LamportClock("bot-alice")),
            new Bot("bot-bob", "shape-bob", "#3b82f6", new LamportClock("bot-bob")),
            new Bot("bot-carol", "shape-carol", "#22c55e", new LamportClock("bot-carol"))
    );

    public DemoBots(RoomManager rooms, WireCodec codec) {
        this.rooms = rooms;
        this.codec = codec;
    }

    @PostConstruct
    public void start() {
        rooms.getOrCreate(ROOM);
        for (Bot bot : bots) {
            spawnShape(bot);
            Thread.ofVirtual().name("demo-" + bot.site()).start(() -> drive(bot));
        }
        log.info("demo bots drawing in room '{}' (disable with syncforge.demo.enabled=false)", ROOM);
    }

    private void spawnShape(Bot bot) {
        Map<String, Object> initial = new LinkedHashMap<>();
        initial.put("type", "rect");
        initial.put("x", 120.0 + ThreadLocalRandom.current().nextInt(400));
        initial.put("y", 80.0 + ThreadLocalRandom.current().nextInt(260));
        initial.put("w", 140.0);
        initial.put("h", 90.0);
        initial.put("color", bot.color());
        initial.put("z", 1.0);
        initial.put("deleted", false);
        submit(bot, initial);
    }

    private void drive(Bot bot) {
        double x = 300, y = 200;
        while (running) {
            try {
                Thread.sleep(650 + ThreadLocalRandom.current().nextInt(400));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // A gentle random walk kept inside the canvas.
            x = clamp(x + ThreadLocalRandom.current().nextInt(-60, 60), 20, 900);
            y = clamp(y + ThreadLocalRandom.current().nextInt(-40, 40), 20, 560);
            Map<String, Object> move = new LinkedHashMap<>();
            move.put("x", x);
            move.put("y", y);
            submit(bot, move);
        }
    }

    private void submit(Bot bot, Map<String, Object> changed) {
        Map<String, LwwRegister> fields = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : changed.entrySet()) {
            fields.put(e.getKey(), new LwwRegister(e.getValue(), bot.clock().tick()));
        }
        Operation op = new Operation(bot.shapeId(), fields);
        rooms.submitLocalOp(ROOM, op, null, codec.encodeOp(op).toString());
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @PreDestroy
    public void stop() {
        running = false;
    }
}
