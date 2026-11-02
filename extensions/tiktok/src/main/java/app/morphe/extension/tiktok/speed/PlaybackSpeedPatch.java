/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/speed/PlaybackSpeedPatch.java
 */
package app.morphe.extension.tiktok.speed;

import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.TextView;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Helper methods injected by the TikTok playback speed bytecode patch. */
public final class PlaybackSpeedPatch {
    private static final String EDGE_SPEEDUP_CLASS =
        "com.ss.android.ugc.aweme.feed.longvideo.edgespeedup.EdgeSpeedupAssem";
    private static final String SOURCE_LONG_PRESS = "long_press";
    private static final String SOURCE_SHARE_BUTTON = "click_share_button";
    private static final String SOURCE_IMMERSIVE_PICKER = "immersive_click";
    private static final String SOURCE_ON_SCREEN_BUTTON = "on_screen_button";
    private static final String SOURCE_SWIPE_LOCK = "swipe_up_lock_persist";

    /**
     * What the speed menu row accepts. The row's summary and its refusal say these numbers,
     * formatted in from here, so the words follow the limit.
     */
    public static final int MAX_MENU_SPEEDS = 8;
    public static final float MIN_SPEED = 0.5f;
    public static final float MAX_SPEED = 3f;

    /** TikTok's own hold speed, written into the gesture and into its words for it. */
    static final float TIKTOK_HOLD_SPEED = 2f;
    private static final Pattern STANDALONE_TWO = Pattern.compile("(?<![\\p{Nd}.,])2(?![\\p{Nd}.,])");
    /** The hold banner's labels and the text TikTok gave each, kept while the view lives. */
    private static final Map<TextView, CharSequence> BANNER_TEXT = new WeakHashMap<>();
    private static volatile float loggedHoldSpeed = Float.NaN;

    private static volatile float rememberedSpeed = 1.0f;
    private static String currentVideoId = "";
    private static float manualSpeed = Float.NaN;
    /** The Default speed value the first-frame bridge last gave TikTok's speed state, or NaN. */
    private static float defaultInNativeState = Float.NaN;
    /**
     * The last live change, which is saved nowhere: the video it was made on and its speed. It's
     * kept apart from {@link #currentVideoId}, since a preloaded neighbour's early first frame moves
     * that on while the dragged video is still the one on screen.
     */
    private static String liveVideoId = "";
    private static float liveSpeed = Float.NaN;

    private PlaybackSpeedPatch() {}

    /** A speed the way the row writes it: 0.5, 1.25, 3. No trailing zero, no locale comma. */
    public static String speedLabel(float speed) {
        // Float.toString gives the shortest decimal that round-trips the float. BigDecimal.valueOf
        // has no float overload, so it would widen to double first and render 1.1f as the double's
        // "1.100000023841858": harmless for the hold's fixed values, wrong for a free-text menu speed.
        return new java.math.BigDecimal(Float.toString(speed)).stripTrailingZeros().toPlainString();
    }

    public static synchronized void beginVideo(Aweme aweme) {
        String id = aweme == null || aweme.getAid() == null ? "" : aweme.getAid();
        if (!id.equals(currentVideoId) || id.isEmpty()) {
            currentVideoId = id;
            manualSpeed = Float.NaN;
        }
    }

    public static synchronized void onSelection(float speed, Aweme aweme, String event, String source) {
        if (!isValidSpeed(speed) ||
                (!isExplicitSelectionSource(source) && !isEdgeSpeedupSelection(source))) return;
        beginVideo(aweme);
        manualSpeed = speed;
        defaultInNativeState = Float.NaN;
        forgetLiveSpeed();
        rememberPlaybackSpeed(speed, source);
    }

    public static synchronized float getPlaybackSpeedForVideo(Aweme aweme) {
        beginVideo(aweme);
        // A speed dragged in for this video stands for as long as the video does, even when the
        // first frame is drawn again (nothing was saved for it to come back from).
        float live = liveSpeedFor(currentVideoId);
        float speed = isValidSpeed(live) ? live : getPlaybackSpeed();
        // The bridge writes this speed into TikTok's own speed state, which TikTok hands the next
        // video at the swipe. One that came from Default speed is kept so that, once the switch is
        // off, the swipe doesn't carry it on (a preloaded neighbour drew its first frame with it).
        defaultInNativeState = !isValidSpeed(live) && !isValidSpeed(manualSpeed)
                && Settings.DEFAULT_SPEED_ENABLED.get() ? speed : Float.NaN;
        return speed;
    }

    /** The speed dragged in for video {@code id}, or NaN when the last live change wasn't on it. */
    private static float liveSpeedFor(String id) {
        return !id.isEmpty() && id.equals(liveVideoId) ? liveSpeed : Float.NaN;
    }

    private static void forgetLiveSpeed() {
        liveVideoId = "";
        liveSpeed = Float.NaN;
    }

    /** Replaced with the verified native Aweme getter and controller setSpeed calls. */
    public static void onFirstFrame(Object controller) { }

    /** The speeds a live change lands on, in steps of this much from {@link #MIN_SPEED} up. */
    public static final float LIVE_STEP = 0.25f;

    /**
     * The player that last reported a video playing, with that video's id. A preloaded neighbour
     * can draw its first frame early, so the first frame says nothing about what is on screen. The
     * progress report is the signal the on-screen tracker (CurrentVideoAuthor) already trusts.
     */
    private static final class OnScreen {
        final WeakReference<Object> controller;
        final String aid;

        OnScreen(Object controller, String aid) {
            this.controller = new WeakReference<>(controller);
            this.aid = aid;
        }
    }

    private static volatile OnScreen onScreen;

    /** A player and the video it has now, both checked against what the progress report named. */
    private static final class Live {
        final Object controller;
        final Aweme aweme;

        Live(Object controller, Aweme aweme) {
            this.controller = controller;
            this.aweme = aweme;
        }
    }

    /** What the player's own members answer, so a test can stand in for TikTok's player. */
    public interface NativePlayer {
        Aweme aweme(Object controller);

        void setSpeed(Object controller, float speed);
    }

    /** Set by tests only. Unpatched, as in the tests, the two bridge methods below do nothing. */
    public static volatile NativePlayer nativeForTests;

    /**
     * Called from {@code PlayerController.onPlayProgressChange} with p0 and p1. It fires several
     * times a second while a video plays, so a report that repeats the last one costs nothing.
     */
    public static void onPlayerProgress(Object controller, String aid) {
        if (controller == null || aid == null || aid.isEmpty()) return;
        OnScreen known = onScreen;
        if (known != null && known.controller.get() == controller && aid.equals(known.aid)) return;
        onScreen = new OnScreen(controller, aid);
    }

    /** Replaced with a call to the verified native getter of the video a PlayerController has. */
    public static Aweme nativeAweme(Object controller) { return null; }

    /**
     * Replaced with what the first-frame bridge does for a speed it picked itself: the native
     * selection state is brought to this speed and video, then the controller's setSpeed is called.
     */
    public static void setNativeSpeed(Object controller, float speed) { }

    private static Aweme awemeOf(Object controller) {
        NativePlayer player = nativeForTests;
        return player != null ? player.aweme(controller) : nativeAweme(controller);
    }

    private static void setSpeedOn(Object controller, float speed) {
        NativePlayer player = nativeForTests;
        if (player != null) player.setSpeed(controller, speed);
        else setNativeSpeed(controller, speed);
    }

    /**
     * The player of the video on screen, or null when no player has reported yet, it is gone, or it
     * has moved on to another video since: a photo post reports nothing, and its neighbour's player
     * would otherwise take the change.
     */
    private static Live liveTarget() {
        OnScreen target = onScreen;
        if (target == null) return null;
        Object controller = target.controller.get();
        if (controller == null) return null;
        try {
            Aweme aweme = awemeOf(controller);
            if (aweme == null || !target.aid.equals(aweme.getAid())) return null;
            return new Live(controller, aweme);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not read the video on screen for a live speed change", ex);
            return null;
        }
    }

    /**
     * The speed the video on screen plays at now, from where a drag should start, or NaN when no
     * player is known for it. A choice made for this video stands; otherwise it is the speed this
     * patch gave it at its first frame.
     */
    public static float liveStartSpeed() {
        Live live = liveTarget();
        return live == null ? Float.NaN : liveStartSpeed(live);
    }

    private static float liveStartSpeed(Live live) {
        String id = live.aweme.getAid();
        synchronized (PlaybackSpeedPatch.class) {
            float dragged = liveSpeedFor(id == null ? "" : id);
            if (isValidSpeed(dragged)) return dragged;
            if (id != null && id.equals(currentVideoId) && isValidSpeed(manualSpeed)) return manualSpeed;
            return getPlaybackSpeed();
        }
    }

    /** The grid a live change lands on: whole steps of {@link #LIVE_STEP}, held to 0.5x through 3x. */
    public static float snapLiveSpeed(float speed) {
        if (Float.isNaN(speed)) return Float.NaN;
        float stepped = Math.round(speed / LIVE_STEP) * LIVE_STEP;
        return Math.max(MIN_SPEED, Math.min(MAX_SPEED, stepped));
    }

    /**
     * Plays the video on screen at {@code speed} (snapped), for a gesture. The choice is this
     * video's alone: it's kept as this video's live speed (so its first frame drawn again keeps
     * it), the next video starts at the default or the remembered speed, and nothing is saved.
     *
     * @return whether the video's own player took it
     */
    public static boolean setLiveSpeed(float speed) {
        if (!isValidSpeed(snapLiveSpeed(speed))) return false;
        Live live = liveTarget();
        return live != null && setLiveSpeed(live, speed);
    }

    private static boolean setLiveSpeed(Live live, float speed) {
        float snapped = snapLiveSpeed(speed);
        if (!isValidSpeed(snapped)) return false;
        try {
            // The player first: a player that refuses leaves nothing recorded as this video's speed.
            setSpeedOn(live.controller, snapped);
            synchronized (PlaybackSpeedPatch.class) {
                beginVideo(live.aweme);
                manualSpeed = snapped;
                liveVideoId = live.aweme.getAid();
                liveSpeed = snapped;
            }
            return true;
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not change the speed of the video on screen", ex);
            return false;
        }
    }

    /**
     * Double tap's speed toggle (#136): the video it sped up, the speed it set, and the speed and
     * selection state it found, so a second toggle puts back exactly that and not a snapped step.
     */
    private static String boostedVideoId = "";
    private static float boostedTo = Float.NaN;
    private static float boostedFrom = Float.NaN;
    private static float manualBeforeBoost = Float.NaN;
    private static String liveIdBeforeBoost = "";
    private static float liveBeforeBoost = Float.NaN;

    /** The speed the toggle plays at: the hold speed picked under Speed, or TikTok's own 2x. */
    static float boostSpeed() {
        float chosen = chosenHoldSpeed();
        return snapLiveSpeed(Float.isNaN(chosen) ? TIKTOK_HOLD_SPEED : chosen);
    }

    /**
     * Speeds the video on screen up to {@link #boostSpeed()}, or back to what it played at before
     * when this video was the one last sped up and nothing has moved its speed since. A video
     * already at the boost speed goes to 1x instead. Another video starts untoggled, and like
     * {@link #setLiveSpeed} nothing is saved, so the next video plays at its usual speed.
     *
     * @return the speed the video plays at now, or NaN when no player took the change
     */
    public static float toggleBoost() {
        // One read of the player on screen for the whole toggle, so a swipe that lands mid-toggle
        // can't speed up one video while the boost is recorded against another.
        Live live = liveTarget();
        if (live == null) return Float.NaN;
        String id = live.aweme.getAid();
        if (id == null || id.isEmpty()) return Float.NaN;
        boolean restore;
        float from;
        synchronized (PlaybackSpeedPatch.class) {
            restore = id.equals(boostedVideoId) && Float.compare(liveSpeedFor(id), boostedTo) == 0;
            from = boostedFrom;
        }
        if (restore) {
            try {
                setSpeedOn(live.controller, from);
            } catch (Throwable ex) {
                Logger.printException(() -> "Could not put the video's speed back", ex);
                return Float.NaN;
            }
            synchronized (PlaybackSpeedPatch.class) {
                if (id.equals(currentVideoId)) manualSpeed = manualBeforeBoost;
                liveVideoId = liveIdBeforeBoost;
                liveSpeed = liveBeforeBoost;
                forgetBoost();
            }
            return from;
        }
        from = liveStartSpeed(live);
        if (!isValidSpeed(from)) return Float.NaN;
        float boost = boostSpeed();
        float target = Float.compare(snapLiveSpeed(from), boost) == 0 ? 1f : boost;
        float manual;
        String liveId;
        float liveBefore;
        synchronized (PlaybackSpeedPatch.class) {
            manual = id.equals(currentVideoId) ? manualSpeed : Float.NaN;
            liveId = liveVideoId;
            liveBefore = liveSpeed;
        }
        if (!setLiveSpeed(live, target)) return Float.NaN;
        synchronized (PlaybackSpeedPatch.class) {
            boostedVideoId = id;
            boostedTo = target;
            boostedFrom = from;
            manualBeforeBoost = manual;
            liveIdBeforeBoost = liveId;
            liveBeforeBoost = liveBefore;
        }
        return target;
    }

    private static void forgetBoost() {
        boostedVideoId = "";
        boostedTo = Float.NaN;
        boostedFrom = Float.NaN;
        manualBeforeBoost = Float.NaN;
        liveIdBeforeBoost = "";
        liveBeforeBoost = Float.NaN;
    }

    public static List<Float> parseMenuSpeeds(String text) {
        if (text == null || text.trim().isEmpty()) return List.of();
        String[] entries = text.split(",", -1);
        if (entries.length > MAX_MENU_SPEEDS) {
            throw new IllegalArgumentException("Use at most " + MAX_MENU_SPEEDS + " speeds");
        }
        LinkedHashSet<Float> values = new LinkedHashSet<>();
        for (String entry : entries) {
            float speed;
            try { speed = Float.parseFloat(entry.trim()); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid speed", error); }
            if (!isValidSpeed(speed) || speed < MIN_SPEED || speed > MAX_SPEED) {
                throw new IllegalArgumentException(
                        "Use speeds from " + speedLabel(MIN_SPEED) + " to " + speedLabel(MAX_SPEED));
            }
            values.add(speed);
        }
        return new ArrayList<>(values);
    }

    public static Object menuSpeeds(Object original) {
        try {
            List<Float> values = parseMenuSpeeds(Settings.CUSTOM_SPEEDS.get());
            return values.isEmpty() ? original : values;
        } catch (IllegalArgumentException error) {
            return original;
        }
    }

    public static void rememberPlaybackSpeed(float speed, String source) {
        if (!isValidSpeed(speed) ||
            (!isExplicitSelectionSource(source) && !isEdgeSpeedupSelection(source))) {
            return;
        }

        // With the switch off, a new video starts at 1x (TikTok's default) and a choice made
        // from the menu applies to that video only. Upstream #168 asked for this: the remembered
        // speed always applied, and dropping the patch was the only way to get per-video reset.
        if (!Settings.REMEMBER_SPEED.get()) {
            rememberedSpeed = 1.0f;
            return;
        }

        rememberedSpeed = speed;
        try {
            Settings.REMEMBERED_SPEED.save(speed);
        } catch (Throwable ignored) {
            // Settings can be unavailable during early startup.
        }
    }

    /**
     * The speed the row under Speed picks for the hold gesture, or NaN when TikTok's own stands:
     * Hushfeed paused, or a value the player would refuse.
     */
    static float chosenHoldSpeed() {
        if (Setting.isPaused()) return Float.NaN;
        try {
            float value = Float.parseFloat(Settings.HOLD_SPEED.get());
            return isValidSpeed(value) && value >= MIN_SPEED && value <= MAX_SPEED ? value : Float.NaN;
        } catch (NumberFormatException refused) {
            return Float.NaN;
        }
    }

    /**
     * The speed the hold gesture plays at and its pull-down lock keeps (upstream #52). TikTok
     * writes it as a literal 2x in the gesture, in its banner's "already at that speed" check
     * and in the lock's telemetry; each of those literals comes through here.
     */
    public static float holdSpeed(float nativeSpeed) {
        float chosen = chosenHoldSpeed();
        if (Float.isNaN(chosen)) return nativeSpeed;
        if (Float.compare(chosen, nativeSpeed) != 0 && Float.compare(chosen, loggedHoldSpeed) != 0) {
            // Once per value per process: the export says the gesture was reached and what it
            // ran at, without a line for every hold.
            loggedHoldSpeed = chosen;
            Logger.printInfo(() -> "Hold gesture speed " + speedLabel(chosen)
                    + "x in place of TikTok's " + speedLabel(nativeSpeed) + "x");
        }
        return chosen;
    }

    /**
     * TikTok's own words for the hold carry its fixed number: "Speed: 2x", "Pull down to lock
     * 2x speed", "Locked at 2× speed". With another hold speed chosen, the one standalone 2 in
     * such a text becomes that speed, whatever the language puts around it. A text with no 2 in
     * it ("Back to normal speed") or with more than one is left as TikTok wrote it.
     */
    public static String holdSpeedText(String text) {
        return rewordSpeedNumber(text, chosenHoldSpeed());
    }

    /**
     * TikTok's speed menu toast names the nearest built-in speed for a custom one: choosing 2.5x
     * plays at 2.5x but the toast reads "Playing at 2x speed", because the click handler picks the
     * label by exact value and only 0.5, 1.5, 2 and 3 have their own. Every other speed falls to
     * the "2x" label, whose standalone 2 becomes the chosen speed here, so "2x" reads "2.5x" in the
     * phone's own format. 1.0 has its own "normal speed" text and never reaches this, and TikTok's
     * own 0.5, 1.5 and 3 carry a different number, so their label rewords to itself.
     */
    public static String menuSpeedText(String label, float speed) {
        return rewordSpeedNumber(label, speed);
    }

    /**
     * The one standalone 2 in TikTok's text becomes {@code speed}. A text with no 2, with more
     * than one, or a {@code speed} that is TikTok's own 2x or not a speed is left as TikTok wrote
     * it, whatever the language puts around the number.
     */
    private static String rewordSpeedNumber(String text, float speed) {
        if (text == null || Float.isNaN(speed) || Float.compare(speed, TIKTOK_HOLD_SPEED) == 0) return text;
        Matcher two = STANDALONE_TWO.matcher(text);
        if (!two.find()) return text;
        int at = two.start();
        if (two.find()) return text;
        return text.substring(0, at) + speedLabel(speed) + text.substring(at + 1);
    }

    /**
     * The lock's toast, which TikTok sets from a string id: the reworded text, or null to keep
     * TikTok's own. The id is resolved the way TikTok resolves it, through its activity, because
     * most of its strings are served at run time rather than kept in the APK.
     */
    public static CharSequence holdSpeedToast(int id) {
        try {
            android.content.Context context = Utils.getActivity();
            if (context == null) context = Utils.getContext();
            if (context == null) return null;
            String text = context.getString(id);
            String wanted = holdSpeedText(text);
            return wanted == null || wanted.equals(text) ? null : wanted;
        } catch (RuntimeException unreadable) {
            Logger.printInfo(() -> "Hold speed toast left as TikTok wrote it", unreadable);
            return null;
        }
    }

    /**
     * The banner TikTok shows during a hold when the lock is off, a label it builds once with
     * "Speed: 2x" in it. It is shown and hidden from here, and each time the label is set from
     * the text TikTok gave it, so a changed row or a pause shows up at the next hold.
     */
    public static void holdSpeedBanner(ViewGroup banner) {
        for (int i = 0; i < banner.getChildCount(); i++) {
            if (!(banner.getChildAt(i) instanceof TextView)) continue;
            TextView label = (TextView) banner.getChildAt(i);
            CharSequence original = BANNER_TEXT.get(label);
            if (original == null) {
                original = label.getText();
                BANNER_TEXT.put(label, original);
            }
            String wanted = holdSpeedText(String.valueOf(original));
            if (!TextUtils.equals(wanted, label.getText())) label.setText(wanted);
        }
    }

    public static float preserveTransitionSpeed(float requestedSpeed) {
        synchronized (PlaybackSpeedPatch.class) {
            // The live bridge brought TikTok's own speed state to a dragged speed, and TikTok carries
            // that state into the next video. The transition began that video just before this, so
            // a dragged speed asked for any other video is the dragged one's alone.
            if (isValidSpeed(liveSpeed) && !currentVideoId.equals(liveVideoId)
                    && Float.compare(requestedSpeed, liveSpeed) == 0) {
                return getPlaybackSpeed();
            }
            // Default speed's value, left in that state by a video started while the switch was on,
            // is not a speed TikTok chose.
            if (!Settings.DEFAULT_SPEED_ENABLED.get()
                    && Float.compare(requestedSpeed, defaultInNativeState) == 0) {
                return getPlaybackSpeed();
            }
        }
        if (Settings.DEFAULT_SPEED_ENABLED.get()) return getPlaybackSpeed();
        if (Float.compare(requestedSpeed, 1.0f) != 0) {
            return requestedSpeed;
        }
        return getPlaybackSpeed();
    }

    public static synchronized float getPlaybackSpeed() {
        try {
            if (Settings.DEFAULT_SPEED_ENABLED.get()) {
                if (!currentVideoId.isEmpty() && isValidSpeed(manualSpeed)) return manualSpeed;
                try {
                    float value = Float.parseFloat(Settings.DEFAULT_SPEED.get());
                    return isValidSpeed(value) && value >= MIN_SPEED && value <= MAX_SPEED ? value : 1.5f;
                } catch (NumberFormatException error) {
                    return 1.5f;
                }
            }
            if (!Settings.REMEMBER_SPEED.get()) return 1.0f;
            float persisted = Settings.REMEMBERED_SPEED.get();
            return isValidSpeed(persisted) ? persisted : rememberedSpeed;
        } catch (Throwable ignored) {
            return rememberedSpeed;
        }
    }

    private static boolean isValidSpeed(float speed) {
        return !Float.isNaN(speed) && !Float.isInfinite(speed) && speed > 0.0f;
    }

    private static boolean isExplicitSelectionSource(String source) {
        return SOURCE_LONG_PRESS.equals(source)
            || SOURCE_SHARE_BUTTON.equals(source)
            || SOURCE_IMMERSIVE_PICKER.equals(source)
            || SOURCE_ON_SCREEN_BUTTON.equals(source)
            || SOURCE_SWIPE_LOCK.equals(source);
    }

    private static boolean isEdgeSpeedupSelection(String source) {
        if (source != null) {
            return false;
        }
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if (EDGE_SPEEDUP_CLASS.equals(frame.getClassName())) {
                return true;
            }
        }
        return false;
    }
}
