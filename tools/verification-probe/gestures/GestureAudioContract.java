package app.hushfeed.verification.gestures;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Exercises Android's masked muted index and zero-volume mute behavior through the used helper. */
public final class GestureAudioContract {
    private static int checks;

    public static void main(String[] args) {
        Audio hidden = new Audio(7, true);
        GestureAudio.Snapshot muted = new GestureAudio.Snapshot(hidden);
        check(muted.volume == 0 && muted.muted, "A muted public index hides the underlying seven");
        reject(muted::requireInputAllowed);
        muted.restore(hidden, false);
        check(hidden.index == 7 && hidden.mute && hidden.writes.isEmpty(),
                "Refused input and inspection preserve the hidden index without audio writes");

        Audio zero = new Audio(0, false);
        GestureAudio.Snapshot baseline = new GestureAudio.Snapshot(zero);
        baseline.requireInputAllowed();
        zero.index = 7;
        zero.mute = true;
        baseline.restore(zero, true);
        check(zero.index == 0 && !zero.mute, "Initially unmuted zero restores the real zero and mute state");
        check(zero.writes.equals(Arrays.asList("unmute", "volume:0", "unmute")),
                "Unmute reveals the changed index before comparison, then repairs zero-induced mute");

        Audio unchanged = new Audio(6, false);
        new GestureAudio.Snapshot(unchanged).restore(unchanged, true);
        check(unchanged.writes.isEmpty(), "Unchanged audio needs no restoration writes");

        Audio changed = new Audio(6, false);
        GestureAudio.Snapshot six = new GestureAudio.Snapshot(changed);
        changed.index = 2;
        six.restore(changed, true);
        check(changed.index == 6 && !changed.mute && changed.writes.equals(Arrays.asList("volume:6")),
                "An unmuted changed index restores without mute adjustments");
        changed.writes.clear();
        changed.mute = true;
        six.restore(changed, true);
        check(changed.index == 6 && !changed.mute && changed.writes.equals(Arrays.asList("unmute")),
                "A correct hidden index only needs unmuting");

        Audio inspected = new Audio(0, false);
        GestureAudio.Snapshot observed = new GestureAudio.Snapshot(inspected);
        inspected.index = 7;
        inspected.mute = true;
        observed.restore(inspected, false);
        check(inspected.index == 7 && inspected.mute && inspected.writes.isEmpty(),
                "Inspection does not overwrite intervening audio changes");

        Audio broken = new Audio(0, false);
        GestureAudio.Snapshot initial = new GestureAudio.Snapshot(broken);
        broken.index = 7;
        broken.mute = true;
        broken.failure = new IllegalStateException("Unmute rejected");
        check(reject(() -> initial.restore(broken, true)) == broken.failure,
                "Restoration failure remains visible to the instrumentation result");
        check(broken.index == 7 && broken.mute && broken.writes.equals(Arrays.asList("unmute")),
                "A failed unmute never writes an index through its masked value");
        System.out.println("[gesture-audio] " + checks + " contracts passed");
    }

    private static final class Audio implements GestureAudio.Port {
        int index;
        boolean mute;
        RuntimeException failure;
        final List<String> writes = new ArrayList<>();

        Audio(int index, boolean mute) { this.index = index; this.mute = mute; }
        @Override public int volume() { return mute ? 0 : index; }
        @Override public boolean muted() { return mute; }
        @Override public void setVolume(int value) {
            writes.add("volume:" + value);
            index = value;
            mute = value == 0;
        }
        @Override public void unmute() {
            writes.add("unmute");
            if (failure != null) throw failure;
            mute = false;
        }
    }

    private static RuntimeException reject(Runnable action) {
        try { action.run(); }
        catch (IllegalStateException expected) { checks++; return expected; }
        throw new AssertionError("Unsafe operation completed");
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
        checks++;
    }
}
