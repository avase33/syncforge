package com.syncforge.room;

import com.syncforge.crdt.BoardCrdt;
import com.syncforge.crdt.LamportClock;
import com.syncforge.crdt.LwwRegister;
import com.syncforge.crdt.Operation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A room is hammered by many virtual threads at once, exactly as it would be by
 * many WebSocket connections. Because {@link Room#apply} serialises board
 * mutation and the CRDT is order-independent, the concurrent result must equal a
 * plain serial replay of the same operations — no lost updates, no corruption.
 */
class RoomConcurrencyTest {

    @Test
    void concurrentApplyFromManyThreadsMatchesSerialReplay() throws Exception {
        int threads = 8;
        int opsPerThread = 500;

        // Each thread is its own replica writing to a shared pool of shapes.
        List<List<Operation>> perThread = new ArrayList<>();
        List<Operation> all = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            LamportClock clock = new LamportClock("s" + t);
            List<Operation> ops = new ArrayList<>();
            for (int i = 0; i < opsPerThread; i++) {
                String shapeId = "shape-" + (i % 10);
                Operation op = new Operation(shapeId, Map.of(
                        "x", new LwwRegister((double) (t * 1000 + i), clock.tick())));
                ops.add(op);
                all.add(op);
            }
            perThread.add(ops);
        }

        // Serial reference.
        BoardCrdt reference = new BoardCrdt();
        all.forEach(reference::apply);

        // Concurrent application into a live Room.
        Room room = new Room("stress", "srv-test");
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            List<Operation> ops = perThread.get(t);
            Thread worker = Thread.ofVirtual().unstarted(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                ops.forEach(room::apply);
            });
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        for (Thread w : workers) {
            w.join();
        }

        assertEquals(reference, room.snapshot());
    }
}
