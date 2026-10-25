package app.hushfeed.verification;

import java.util.Objects;

/** Restoration policy shared by the native bridge and injected-failure contracts. */
final class EdgeDragRestoration {
    interface Audio {
        int index();
        boolean muted();
        int min();
        int max();
        String route();
        void index(int value) throws Exception;
        void muted(boolean value) throws Exception;
    }
    interface Brightness {
        float value();
        void value(float value) throws Exception;
        boolean dimmedPresent();
        Object dimmed();
        void dimmed(boolean present, Object value) throws Exception;
    }
    static void audio(Audio audio, int index, boolean muted, int min, int max, String route, boolean volumeMode) throws Exception {
        if (min < 0 || max <= min || index < min || index > max || route == null || route.isEmpty()) {
            throw new IllegalArgumentException("invalid original audio snapshot");
        }
        audioOwner(audio, min, max, route);
        if (audio.index() == index && audio.muted() == muted) return;
        if (!volumeMode || muted) {
            throw new IllegalStateException("music state no longer belongs to this native drag");
        }
        // A muted stream can report zero while retaining a different audible index.
        if (audio.muted()) {
            audio.muted(false);
            audioOwner(audio, min, max, route);
            if (audio.muted()) throw new IllegalStateException("native stream refused to expose its audible index");
        }
        int possibleTravel = (int) Math.ceil(max * .14 / .75) + 2;
        if (Math.abs(audio.index() - index) > possibleTravel) {
            throw new IllegalStateException("music state no longer belongs to this native drag");
        }
        if (audio.index() != index) audio.index(index);
        audioOwner(audio, min, max, route);
        if (audio.muted() != muted) audio.muted(muted);
        audioOwner(audio, min, max, route);
        if (audio.index() != index || audio.muted() != muted) throw new IllegalStateException("exact audio restoration failed");
    }
    private static void audioOwner(Audio audio, int min, int max, String route) {
        if (audio.min() != min || audio.max() != max || !route.equals(audio.route())) {
            throw new IllegalStateException("audio restoration route owner changed");
        }
    }
    static void brightness(Brightness target, float before, boolean dimmedPresent, Object dimmed) throws Exception {
        if (!Float.isFinite(before) || (before != -1f && (before < 0 || before > 1))) {
            throw new IllegalArgumentException("invalid original window brightness");
        }
        target.value(before);
        target.dimmed(dimmedPresent, dimmed);
        if (Float.floatToIntBits(target.value()) != Float.floatToIntBits(before)
                || target.dimmedPresent() != dimmedPresent || !Objects.equals(target.dimmed(), dimmed)) {
            throw new IllegalStateException("exact per-window brightness restoration failed");
        }
    }
    private EdgeDragRestoration() { }
}
