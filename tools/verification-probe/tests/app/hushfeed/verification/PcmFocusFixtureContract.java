package app.hushfeed.verification;

/** Runs without Android. Rejects unbounded durations and a silent or malformed PCM fixture. */
public final class PcmFocusFixtureContract {
    public static void main(String[] args) {
        byte[] pcm = PcmFocusFixture.oneSecond();
        require(pcm.length == 16000, "one second of PCM16 mono at 8 kHz");
        long sum = 0, energy = 0;
        int peak = 0;
        for (int i = 0; i < pcm.length; i += 2) {
            short sample = (short) ((pcm[i] & 255) | (pcm[i + 1] & 255) << 8);
            peak = Math.max(peak, Math.abs(sample));
            sum += sample;
            energy += (long) sample * sample;
        }
        require(sum == 0 && peak == 8192 && energy > 0, "bounded non-silent centered waveform");
        require(PcmFocusFixture.duration(5) == 5 && PcmFocusFixture.duration(90) == 90, "bounded endpoints");
        for (int invalid : new int[]{Integer.MIN_VALUE, -1, 0, 4, 91, Integer.MAX_VALUE}) {
            try { PcmFocusFixture.duration(invalid); throw new AssertionError("unbounded duration accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        System.out.println("PCM fixture contract passed");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
