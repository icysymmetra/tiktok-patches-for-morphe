package app.hushfeed.verification;

import java.io.IOException;

/** Actual restore policy, including a setter that changes state before failing. */
public final class EdgeDragRestorationContract {
    private static int checks;
    private static final class Audio implements EdgeDragRestoration.Audio {
        int index = 3, min, max = 15, writes, hiddenLastAudible = 8;
        boolean muted, failAfterIndex, refuseMute, changeRouteAfterUnmute, changeRangeAfterUnmute;
        String route = "speaker";
        @Override public int index() { return muted ? 0 : index; }
        @Override public boolean muted() { return muted; }
        @Override public int min() { return min; }
        @Override public int max() { return max; }
        @Override public String route() { return route; }
        @Override public void index(int value) throws IOException {
            writes++; index = value; hiddenLastAudible = value; muted = value == 0;
            if (failAfterIndex) { failAfterIndex = false; throw new IOException("injected partial audio setter failure"); }
        }
        @Override public void muted(boolean value) {
            writes++;
            if (!refuseMute) muted = value;
            if (!value && changeRouteAfterUnmute) route = "headphones";
            if (!value && changeRangeAfterUnmute) max++;
        }
    }
    private static final class Brightness implements EdgeDragRestoration.Brightness {
        float value = .8f;
        boolean present = true, failAfterValue, refuseMembership;
        Object membership = Boolean.TRUE;
        @Override public float value() { return value; }
        @Override public boolean dimmedPresent() { return present; }
        @Override public Object dimmed() { return membership; }
        @Override public void value(float next) throws IOException {
            value = next;
            if (failAfterValue) { failAfterValue = false; throw new IOException("injected partial window setter failure"); }
        }
        @Override public void dimmed(boolean next, Object raw) { if (!refuseMembership) { present = next; membership = raw; } }
    }
    private interface Work { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        Audio audio = new Audio(); restore(audio, 0, false, true);
        require(audio.index() == 0 && !audio.muted && audio.writes == 2,
                "zero with mute off is restored after the index setter mutes it");
        restore(audio, 0, false, true);
        require(audio.writes == 2, "repeat restoration is inert");
        Audio hidden = new Audio(); hidden.muted = true; hidden.index = 7; hidden.max = 50;
        EdgeDragRestoration.audio(hidden, 0, false, 0, 50, "speaker", true);
        require(hidden.index() == 0 && !hidden.muted && hidden.index == 0 && hidden.writes == 3,
                "a hidden audible index is exposed and reset before restoring zero with mute off");
        Audio outsideHidden = new Audio(); outsideHidden.muted = true; outsideHidden.index = 7;
        fails(() -> restore(outsideHidden, 0, false, true));
        require(outsideHidden.index == 7 && !outsideHidden.muted && outsideHidden.writes == 1,
                "the exposed actual index must fit plausible native drag travel before any index write");
        Audio routeAfterUnmute = new Audio(); routeAfterUnmute.muted = true; routeAfterUnmute.index = 3;
        routeAfterUnmute.changeRouteAfterUnmute = true;
        fails(() -> restore(routeAfterUnmute, 0, false, true));
        require(routeAfterUnmute.index == 3 && routeAfterUnmute.writes == 1,
                "route ownership is revalidated after exposing a muted stream");
        Audio rangeAfterUnmute = new Audio(); rangeAfterUnmute.muted = true; rangeAfterUnmute.index = 3;
        rangeAfterUnmute.changeRangeAfterUnmute = true;
        fails(() -> restore(rangeAfterUnmute, 0, false, true));
        require(rangeAfterUnmute.index == 3 && rangeAfterUnmute.writes == 1,
                "range ownership is revalidated after exposing a muted stream");
        audio = new Audio(); audio.muted = true; audio.index = 8;
        restore(audio, 0, true, false);
        require(audio.writes == 0 && audio.hiddenLastAudible == 8, "unchanged muted brightness baseline preserves hidden audible index");
        Audio mutedOwner = audio; mutedOwner.muted = false; mutedOwner.index = 0;
        fails(() -> restore(mutedOwner, 0, true, true));
        require(mutedOwner.writes == 0, "a pre-muted snapshot is never synthesized from an exposed zero");
        Audio moved = new Audio(); moved.route = "headphones";
        fails(() -> restore(moved, 0, false, true)); require(moved.writes == 0, "changed audio route is preserved");
        Audio range = new Audio(); range.max = 30;
        fails(() -> restore(range, 0, false, true)); require(range.writes == 0, "changed native stream range is preserved");
        Audio external = new Audio(); external.index = 15;
        fails(() -> restore(external, 0, false, true)); require(external.writes == 0, "outside-drag volume is preserved");
        Audio brightnessAudio = new Audio();
        fails(() -> restore(brightnessAudio, 0, false, false)); require(brightnessAudio.writes == 0, "brightness mode cannot claim an unrelated volume change");
        Audio partial = new Audio(); partial.failAfterIndex = true;
        fails(() -> restore(partial, 0, false, true));
        require(partial.index() == 0 && partial.muted, "partial index failure retains a verifiable retry state");
        restore(partial, 0, false, true);
        require(partial.index() == 0 && !partial.muted && partial.writes == 2, "retry finishes the original exact audio snapshot");
        Audio refused = new Audio(); refused.refuseMute = true;
        fails(() -> restore(refused, 0, false, true));
        require(refused.muted, "native refusal cannot be reported as restored");
        for (float baseline : new float[]{-1f, 0f, .37f, 1f}) {
            Brightness window = new Brightness();
            EdgeDragRestoration.brightness(window, baseline, false, null);
            require(Float.floatToIntBits(window.value) == Float.floatToIntBits(baseline) && !window.present && window.membership == null,
                    "exact system sentinel or per-window scalar and unowned membership restored");
        }
        Brightness held = new Brightness();
        EdgeDragRestoration.brightness(held, .37f, true, Boolean.TRUE);
        require(held.value == .37f && held.present && held.membership == Boolean.TRUE, "pre-existing native brightness ownership survives");
        Brightness partialWindow = new Brightness(); partialWindow.failAfterValue = true;
        fails(() -> EdgeDragRestoration.brightness(partialWindow, .37f, false, null));
        EdgeDragRestoration.brightness(partialWindow, .37f, false, null);
        require(partialWindow.value == .37f && !partialWindow.present, "partial window restore retries the same snapshot");
        Brightness wrongMap = new Brightness(); wrongMap.refuseMembership = true;
        fails(() -> EdgeDragRestoration.brightness(wrongMap, -1f, false, null));
        require(wrongMap.present, "native owner-map refusal cannot claim exact restoration");
        System.out.println("[edge-restoration] " + checks + " contracts passed");
    }
    private static void restore(Audio audio, int index, boolean mute, boolean volumeMode) throws Exception {
        EdgeDragRestoration.audio(audio, index, mute, 0, 15, "speaker", volumeMode);
    }
    private static void fails(Work work) throws Exception {
        try { work.run(); throw new AssertionError("expected restoration rejection"); }
        catch (IOException | IllegalStateException expected) { checks++; }
    }
    private static void require(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
