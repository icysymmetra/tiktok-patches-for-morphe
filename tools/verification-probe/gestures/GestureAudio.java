package app.hushfeed.verification.gestures;

/** Audio restoration uses the unmuted index because Android reports zero while muted. */
final class GestureAudio {
    interface Port {
        int volume();
        boolean muted();
        void setVolume(int volume);
        void unmute();
    }

    static final class Snapshot {
        final int volume;
        final boolean muted;

        Snapshot(Port audio) {
            volume = audio.volume();
            muted = audio.muted();
        }

        void requireInputAllowed() {
            if (muted) {
                throw new IllegalStateException("Input requires an initially unmuted music stream; inspect remains available");
            }
        }

        void restore(Port audio, boolean attemptedInput) {
            if (!attemptedInput) return;
            requireInputAllowed();
            if (audio.muted()) audio.unmute();
            if (audio.volume() != volume) audio.setVolume(volume);
            // Setting volume zero may mute the stream again.
            if (audio.muted()) audio.unmute();
        }
    }
}
