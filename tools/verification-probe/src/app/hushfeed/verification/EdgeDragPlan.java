package app.hushfeed.verification;

import java.util.ArrayList;
import java.util.List;

/** A single-pointer drag anchored on freshly observed native comments bounds. */
final class EdgeDragPlan {
    static final int DOWN = 0, UP = 1, MOVE = 2, CANCEL = 3;
    static final long LIMIT_MS = 4500;

    static final class Event {
        final int action;
        final long offset;
        final float x, y;
        Event(int action, long offset, float x, float y) {
            this.action = action; this.offset = offset; this.x = x; this.y = y;
        }
    }

    interface Driver {
        long now();
        void waitUntil(long time) throws Exception;
        void guard() throws Exception;
        void inject(Event event, long downTime) throws Exception;
        void completed();
        void cancel(Event last, long downTime) throws Exception;
        void restore() throws Exception;
    }

    static List<Event> create(int left, int top, int right, int bottom, float x, float y,
            float level, int slop, int steps, long duration) {
        if (right <= left || bottom <= top || !Float.isFinite(x) || !Float.isFinite(y)
                || !Float.isFinite(level) || level < 0 || level > 1 || slop < 1
                || steps < 12 || steps > 32 || duration < 300 || duration > 900) {
            throw new IllegalArgumentException("invalid drag geometry, level or timing");
        }
        if (x < right - (right - left) * 0.15f || x >= right || y <= top || y >= bottom) {
            throw new IllegalArgumentException("comments anchor must lie in the right 15 percent strip");
        }
        float travel = Math.min(320f, (bottom - top) * 0.14f);
        travel = Math.min(travel, level <= 0.5f ? y - top - 8f : bottom - y - 8f);
        if (travel < Math.max(80f, slop * 4f)) {
            throw new IllegalArgumentException("insufficient observed room for a decisive vertical drag");
        }
        float end = y + (level <= 0.5f ? -travel : travel);
        List<Event> events = new ArrayList<>();
        events.add(new Event(DOWN, 0, x, y));
        for (int step = 1; step <= steps; step++) {
            events.add(new Event(MOVE, duration * step / steps, x, y + (end - y) * step / steps));
        }
        events.add(new Event(UP, duration + 20, x, end));
        return events;
    }

    /** Cancellation and restoration run even if a guard, native injection or cancellation fails. */
    static void execute(List<Event> events, Driver driver) throws Exception {
        long down = driver.now();
        boolean pointerOpen = false;
        Event last = events.get(0);
        try {
            for (Event event : events) {
                driver.waitUntil(down + event.offset);
                deadline(driver, down);
                driver.guard();
                deadline(driver, down);
                last = event;
                if (event.action == DOWN) pointerOpen = true;
                driver.inject(event, down);
                deadline(driver, down);
                if (event.action == UP) pointerOpen = false;
            }
            deadline(driver, down);
            driver.completed();
        } finally {
            try {
                if (pointerOpen) driver.cancel(last, down);
            } finally {
                driver.restore();
            }
        }
    }

    private static void deadline(Driver driver, long down) {
        if (driver.now() - down > LIMIT_MS) throw new IllegalStateException("native drag deadline exceeded");
    }

    private EdgeDragPlan() { }
}
