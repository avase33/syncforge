package com.syncforge.crdt;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The property this whole project exists to guarantee: <strong>given the same
 * set of operations, every replica converges to the same board no matter what
 * order it applies them in, no matter how many duplicates it sees.</strong>
 *
 * <p>The strategy is generate-once, replay-many. A single random history of ops
 * across several replicas is produced (each op carrying a fixed stamp), a
 * reference board is built from it, and then that identical history is shuffled
 * and replayed — with every op applied twice — into a fresh board 400 times
 * over. If any schedule diverged, the CRDT would be wrong. The seed is fixed, so
 * a failure is reproducible rather than a flake.
 */
class BoardCrdtConvergenceTest {

    private static final String[] FIELDS = {"x", "y", "w", "h", "color", "z", "deleted", "type"};
    private static final String[] COLORS = {"#ef4444", "#3b82f6", "#22c55e", "#eab308", "#a855f7"};
    private static final String[] TYPES = {"rect", "ellipse", "line", "note"};

    @Test
    void anyOrderWithDuplicatesConvergesToTheSameBoard() {
        Random rng = new Random(20260722L);

        List<LamportClock> replicas = clocks(5);
        List<String> shapeIds = shapeIds(12);
        List<Operation> history = randomHistory(rng, replicas, shapeIds, 1500);

        // The canonical outcome: apply the history in generation order.
        BoardCrdt reference = new BoardCrdt();
        history.forEach(reference::apply);

        // Every shuffled, duplicate-laden replay must land on that same board.
        for (int trial = 0; trial < 400; trial++) {
            List<Operation> shuffled = new ArrayList<>(history);
            Collections.shuffle(shuffled, rng);

            BoardCrdt replica = new BoardCrdt();
            for (Operation op : shuffled) {
                replica.apply(op);
                replica.apply(op); // idempotency: a redelivery changes nothing
            }
            assertEquals(reference, replica, "diverged on trial " + trial);
        }
    }

    @Test
    void stateMergeConvergesJustLikeOpReplay() {
        Random rng = new Random(7L);
        List<LamportClock> replicas = clocks(4);
        List<String> shapeIds = shapeIds(8);
        List<Operation> history = randomHistory(rng, replicas, shapeIds, 800);

        BoardCrdt reference = new BoardCrdt();
        history.forEach(reference::apply);

        // Partition the history across three independent boards, then reconcile
        // them by state merge in an arbitrary order. Must equal the op replay.
        BoardCrdt a = new BoardCrdt();
        BoardCrdt b = new BoardCrdt();
        BoardCrdt c = new BoardCrdt();
        for (int i = 0; i < history.size(); i++) {
            switch (i % 3) {
                case 0 -> a.apply(history.get(i));
                case 1 -> b.apply(history.get(i));
                default -> c.apply(history.get(i));
            }
        }
        BoardCrdt merged = new BoardCrdt().merge(c).merge(a).merge(b).merge(a);
        assertEquals(reference, merged);
    }

    @Test
    void concurrentDeleteAndEditResolveByStampEverywhere() {
        // Alice creates a shape and later deletes it; Bob, having seen the
        // create, moves it. Because deleted and x are independent registers, the
        // delete and the move do not fight — both land — and the two replicas
        // agree on the result whichever order they apply the three ops.
        LamportClock alice = new LamportClock("alice");
        LamportClock bob = new LamportClock("bob");

        Operation create = op("s", field("type", "rect", alice), field("x", 10.0, alice),
                field("deleted", false, alice));           // stamps 1..3 @ alice

        // Bob observes the create (max stamp 3@alice) before acting, so his move
        // is causally later and dominates the create's x.
        bob.witness(new Stamp(3, "alice"));
        Operation bobMoves = op("s", field("x", 500.0, bob));   // 4 @ bob

        Operation aliceDeletes = op("s", field("deleted", true, alice)); // 4 @ alice

        BoardCrdt one = new BoardCrdt();   // sees create, move, delete
        one.apply(create);
        one.apply(bobMoves);
        one.apply(aliceDeletes);

        BoardCrdt two = new BoardCrdt();   // sees them in the opposite order
        two.apply(aliceDeletes);
        two.apply(bobMoves);
        two.apply(create);

        assertEquals(one, two);
        assertEquals(500.0, one.shape("s").value("x"));         // Bob's move survived
        assertEquals(true, one.shape("s").value("deleted"));    // Alice's delete survived
    }

    @Test
    void anEmptyOpHistoryYieldsAnEmptyBoard() {
        assertEquals(new BoardCrdt(), new BoardCrdt());
        assertTrue(new BoardCrdt().shapes().isEmpty());
    }

    // ---- generators -------------------------------------------------------

    private static List<LamportClock> clocks(int n) {
        List<LamportClock> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new LamportClock("s" + i));
        }
        return out;
    }

    private static List<String> shapeIds(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add("shape-" + i);
        }
        return out;
    }

    private static List<Operation> randomHistory(Random rng, List<LamportClock> replicas,
                                                 List<String> shapeIds, int count) {
        List<Operation> history = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            LamportClock clock = replicas.get(rng.nextInt(replicas.size()));
            String shapeId = shapeIds.get(rng.nextInt(shapeIds.size()));
            int fieldCount = 1 + rng.nextInt(3);
            List<String> chosen = new ArrayList<>(List.of(FIELDS));
            Collections.shuffle(chosen, rng);
            List<Field> fields = new ArrayList<>();
            for (int f = 0; f < fieldCount; f++) {
                String name = chosen.get(f);
                fields.add(field(name, randomValue(name, rng), clock));
            }
            history.add(op(shapeId, fields.toArray(new Field[0])));
        }
        return history;
    }

    private static Object randomValue(String field, Random rng) {
        return switch (field) {
            case "color" -> COLORS[rng.nextInt(COLORS.length)];
            case "type" -> TYPES[rng.nextInt(TYPES.length)];
            case "deleted" -> rng.nextBoolean();
            default -> (double) rng.nextInt(1000);
        };
    }

    // ---- tiny op-builder helpers -----------------------------------------

    private record Field(String name, LwwRegister register) {
    }

    private static Field field(String name, Object value, LamportClock clock) {
        return new Field(name, new LwwRegister(value, clock.tick()));
    }

    private static Operation op(String shapeId, Field... fields) {
        var map = new java.util.HashMap<String, LwwRegister>();
        for (Field f : fields) {
            map.put(f.name(), f.register());
        }
        return new Operation(shapeId, map);
    }
}
