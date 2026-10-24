package app.hushfeed.verification.gestures;

import java.util.List;

/** Run with plain javac/java. Verifies the actual event plans, not a fake host view. */
public final class GestureSequenceContract {
    private static int checks;

    public static void main(String[] args) {
        for (boolean railFirst : new boolean[]{true, false}) {
            for (float scale : new float[]{0.35f, 1.1f}) {
                float a = railFirst ? 940 : 500, b = railFirst ? 500 : 940;
                List<GestureSequence.Event> events = GestureSequence.pinch(0, 0, 1000, 2000,
                        a, 1000, b, 1000, scale, 16, 400);
                check(events.size() == 20, "Bounded event count");
                check(events.get(0).action == 0 && events.get(0).xs.length == 1, "One-pointer down");
                check(events.get(1).action == 261 && events.get(1).xs.length == 2, "Second pointer down index");
                check(events.get(18).action == 262 && events.get(18).xs.length == 2, "Second pointer up index");
                check(events.get(19).action == 1 && events.get(19).xs.length == 1, "Final pointer up");
                long previous = -1;
                for (GestureSequence.Event event : events) {
                    check(event.offset > previous, "Strictly increasing event offsets");
                    previous = event.offset;
                }
                float start = Math.abs(a - b);
                GestureSequence.Event end = events.get(17);
                check(Math.abs(Math.abs(end.xs[0] - end.xs[1]) / start - scale) < 0.00001f,
                        "Requested physical pinch distance");
            }
        }
        List<GestureSequence.Event> tap = GestureSequence.tap(0, 0, 1000, 2000, 930, 1800);
        check(tap.size() == 2 && tap.get(1).action == 1, "Bounded photo-X tap");
        for (float scale : new float[]{Float.NaN, Float.POSITIVE_INFINITY, 0, 1, 3, 2}) {
            reject(() -> GestureSequence.pinch(0, 0, 1000, 2000, 940, 1000, 500, 1000, scale, 16, 400));
        }
        reject(() -> GestureSequence.pinch(0, 0, 1000, 2000, 940, 1000, 930, 1000, 0.35f, 16, 400));
        reject(() -> GestureSequence.pinch(0, 0, 1000, 2000, Float.NaN, 1000, 500, 1000, 0.35f, 16, 400));
        reject(() -> GestureSequence.pinch(0, 0, 1000, 2000, 940, 1000, 500, 1000, 0.35f, 1000, 400));
        reject(() -> GestureSequence.pinch(0, 0, 1000, 2000, 940, 1000, 500, 1000, 0.35f, 16, 10_000));
        reject(() -> GestureSequence.tap(0, 0, 1000, 2000, 0, 1000));
        reject(() -> GestureSequence.tap(1000, 0, 0, 2000, 500, 1000));
        System.out.println("[gesture-sequence] " + checks + " contracts passed");
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
        checks++;
    }

    private static void reject(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            checks++;
            return;
        }
        throw new AssertionError("Unsafe gesture accepted");
    }
}
