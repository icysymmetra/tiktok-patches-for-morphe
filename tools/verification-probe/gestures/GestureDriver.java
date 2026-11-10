package app.hushfeed.verification.gestures;

import java.util.List;

/** Drives the actual input adapter with bounded time and cancellation after partial input. */
final class GestureDriver {
    interface Host {
        long now();
        void sleep(long delay);
        boolean ownsWindow();
        void send(GestureSequence.Event event, long down);
    }

    static final class Outcome {
        String cancel;
    }

    static void inject(List<GestureSequence.Event> events, Host host, Outcome outcome) {
        long down = host.now();
        GestureSequence.Event active = null;
        try {
            for (GestureSequence.Event event : events) {
                requireDeadline(host, down);
                long delay = down + event.offset - host.now();
                if (delay > 0) host.sleep(delay);
                if (!host.ownsWindow()) throw new IllegalStateException("Target window changed");
                requireDeadline(host, down);
                int action = event.action & 255;
                if (action == GestureSequence.DOWN || action == GestureSequence.POINTER_DOWN
                        || action == GestureSequence.MOVE) active = event;
                host.send(event, down);
                active = action == GestureSequence.UP ? null : event;
                if (action == GestureSequence.POINTER_UP) {
                    active = new GestureSequence.Event(GestureSequence.MOVE, 0,
                            new float[]{event.xs[0]}, new float[]{event.ys[0]});
                }
                requireDeadline(host, down);
            }
        } finally {
            if (active != null) {
                try {
                    if (!host.ownsWindow()) {
                        outcome.cancel = "focus_changed_no_further_input";
                    } else {
                        host.send(new GestureSequence.Event(GestureSequence.CANCEL, 0, active.xs, active.ys), down);
                        outcome.cancel = "sent";
                    }
                } catch (RuntimeException error) {
                    outcome.cancel = "failed_" + error.getClass().getSimpleName();
                }
            }
        }
    }

    private static void requireDeadline(Host host, long down) {
        if (host.now() - down > 5000) throw new IllegalStateException("Gesture exceeded five seconds");
    }
}
