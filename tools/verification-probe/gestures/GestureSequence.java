package app.hushfeed.verification.gestures;

import java.util.ArrayList;
import java.util.List;

/** Bounded touchscreen event descriptions. Native acceptance still needs the host app. */
final class GestureSequence {
    static final int DOWN = 0, UP = 1, MOVE = 2, CANCEL = 3, POINTER_DOWN = 5, POINTER_UP = 6;

    static final class Event {
        final int action;
        final long offset;
        final float[] xs, ys;

        Event(int action, long offset, float[] xs, float[] ys) {
            this.action = action;
            this.offset = offset;
            this.xs = xs.clone();
            this.ys = ys.clone();
        }
    }

    static List<Event> pinch(float left, float top, float right, float bottom,
                             float ax, float ay, float bx, float by, float scale, int steps, int duration) {
        if (!Float.isFinite(scale) || scale < 0.2f || scale > 2f || scale == 1f ||
                steps < 4 || steps > 32 || duration < 150 || duration > 1000) {
            throw new IllegalArgumentException("Invalid pinch scale, step count or duration");
        }
        float centerX = (ax + bx) / 2f, centerY = (ay + by) / 2f;
        if (Math.hypot(ax - bx, ay - by) < 40f) {
            throw new IllegalArgumentException("Pointers are too close together");
        }
        List<Event> events = new ArrayList<>();
        events.add(new Event(DOWN, 0, new float[]{ax}, new float[]{ay}));
        events.add(new Event(POINTER_DOWN | (1 << 8), 8, new float[]{ax, bx}, new float[]{ay, by}));
        for (int step = 1; step <= steps; step++) {
            float ratio = 1f + (scale - 1f) * step / steps;
            events.add(new Event(MOVE, 8L + (long) duration * step / steps,
                    new float[]{centerX + (ax - centerX) * ratio, centerX + (bx - centerX) * ratio},
                    new float[]{centerY + (ay - centerY) * ratio, centerY + (by - centerY) * ratio}));
        }
        Event end = events.get(events.size() - 1);
        events.add(new Event(POINTER_UP | (1 << 8), duration + 16L, end.xs, end.ys));
        events.add(new Event(UP, duration + 24L, new float[]{end.xs[0]}, new float[]{end.ys[0]}));
        validateBounds(events, left, top, right, bottom);
        return events;
    }

    static List<Event> tap(float left, float top, float right, float bottom, float x, float y) {
        List<Event> events = new ArrayList<>();
        events.add(new Event(DOWN, 0, new float[]{x}, new float[]{y}));
        events.add(new Event(UP, 60, new float[]{x}, new float[]{y}));
        validateBounds(events, left, top, right, bottom);
        return events;
    }

    private static void validateBounds(List<Event> events, float left, float top, float right, float bottom) {
        if (!Float.isFinite(left) || !Float.isFinite(top) || !Float.isFinite(right) || !Float.isFinite(bottom) ||
                right <= left || bottom <= top) throw new IllegalArgumentException("Invalid active window bounds");
        for (Event event : events) {
            for (int pointer = 0; pointer < event.xs.length; pointer++) {
                float x = event.xs[pointer], y = event.ys[pointer];
                if (!Float.isFinite(x) || !Float.isFinite(y) || x <= left || x >= right || y <= top || y >= bottom) {
                    throw new IllegalArgumentException("A gesture point leaves the fresh active window bounds");
                }
            }
        }
    }
}
