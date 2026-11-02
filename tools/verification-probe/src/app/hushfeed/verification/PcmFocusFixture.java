package app.hushfeed.verification;

/** A deterministic, local, one-second PCM16 mono fixture. The AudioTrack mutes itself. */
final class PcmFocusFixture {
    static final int SAMPLE_RATE = 8000;
    static final int MIN_SECONDS = 5;
    static final int MAX_SECONDS = 90;

    private PcmFocusFixture() { }

    static int duration(int seconds) {
        if (seconds < MIN_SECONDS || seconds > MAX_SECONDS) {
            throw new IllegalArgumentException("fixture duration must be 5 through 90 seconds");
        }
        return seconds;
    }

    static byte[] oneSecond() {
        byte[] pcm = new byte[SAMPLE_RATE * 2];
        for (int frame = 0; frame < SAMPLE_RATE; frame++) {
            short sample = (short) Math.round(Math.sin(frame * Math.PI / 10) * 8192);
            pcm[frame * 2] = (byte) sample;
            pcm[frame * 2 + 1] = (byte) (sample >>> 8);
        }
        return pcm;
    }
}
