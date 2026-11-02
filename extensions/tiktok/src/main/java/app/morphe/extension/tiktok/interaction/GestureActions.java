/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.interaction;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.download.CoverSaver;
import app.morphe.extension.tiktok.download.OriginalSoundDownloads;
import app.morphe.extension.tiktok.download.ExternalDownloader;
import app.morphe.extension.tiktok.download.FrameSaver;
import app.morphe.extension.tiktok.share.ShareUrlSanitizer;
import app.morphe.extension.tiktok.feedfilter.SoundIdentity;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.speed.PlaybackSpeedPatch;
import app.morphe.extension.tiktok.wellbeing.SleepTimer;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

public final class GestureActions {
    private static final Map<Object, CommentControl> COMMENTS = new WeakHashMap<>();
    private GestureActions() {}

    private static final class CommentControl {
        WeakReference<View> view = new WeakReference<>(null);
        String videoId;
        /** The post bound with the id, for the long press actions that need more than its id. */
        WeakReference<Object> aweme = new WeakReference<>(null);
    }

    private static CommentControl commentControl(Object owner) {
        CommentControl control = COMMENTS.get(owner);
        if (control == null) {
            control = new CommentControl();
            COMMENTS.put(owner, control);
        }
        return control;
    }

    public static void registerCommentView(Object owner, View view) {
        for (Map.Entry<Object, CommentControl> entry : COMMENTS.entrySet()) {
            if (entry.getKey() != owner && entry.getValue().view.get() == view) entry.getValue().view.clear();
        }
        commentControl(owner).view = new WeakReference<>(view);
    }

    public static void bindCommentView(Object owner, Object params) {
        Object aweme = Reflect.property(params, "getAweme", "aweme");
        CommentControl control = commentControl(owner);
        control.videoId = Reflect.string(aweme, "getAid", "aid");
        control.aweme = new WeakReference<>(aweme);
    }

    /**
     * Each heart's assem and its view, for Like the video. The heart needs no video id of its own:
     * it's found beside the comment button the gesture picked, and its assem likes the post it's
     * bound to.
     */
    private static final Map<Object, WeakReference<View>> HEARTS = new WeakHashMap<>();

    /** {@code VideoDiggAssem.onViewCreated}, which TikTok runs again as a cell's assems are reused. */
    public static void registerLikeView(Object owner, View view) {
        for (Map.Entry<Object, WeakReference<View>> entry : HEARTS.entrySet()) {
            if (entry.getKey() != owner && entry.getValue().get() == view) entry.getValue().clear();
        }
        HEARTS.put(owner, new WeakReference<>(view));
    }

    /**
     * Runs at the start of each method a double tap reaches on its way to TikTok's like, on the
     * main thread. Returning true swallows the gesture, which is what keeps the like from also
     * happening. The legacy panel hands the tap on to the modern one, so a swallowed tap never
     * reaches the second guard and an action runs once.
     *
     * <p>A double tap lands with the finger still down, as a long press does, so the Long press
     * actions run from here unchanged: the sleep timer's picker is posted until the touch is done
     * with, and a frame is read at the tap. The edge seek stays with the long press. Nothing here
     * says where the tap landed.
     *
     * <p>Each guard hands over the tap's event. A route that reached a second guard with the same
     * tap, rather than through the panel that swallows it, gets the first guard's answer back and
     * the action doesn't run twice. A copy of the event carries the same times, so it counts too.
     */
    public static boolean onDoubleTap(MotionEvent event) {
        if (event != null && event.getDownTime() == answeredTapDown && event.getEventTime() == answeredTapAt) {
            return answeredTap;
        }
        boolean answer = onDoubleTap();
        if (event != null) {
            answeredTapDown = event.getDownTime();
            answeredTapAt = event.getEventTime();
            answeredTap = answer;
            if (answer) {
                tookTapFrom = event.getDownTime();
                tookTapAt = SystemClock.uptimeMillis();
            }
        }
        return answer;
    }

    /** The double tap the last guard answered, by its event's times, and what it answered. */
    private static long answeredTapDown = Long.MIN_VALUE;
    private static long answeredTapAt = Long.MIN_VALUE;
    private static boolean answeredTap;

    /**
     * The last double tap Hushfeed took: the down time of the event it was handed and when it ran,
     * on the uptime clock touch events use. Android's gesture detector still times a long press
     * from a double tap's second touch, and so do TikTok's own timers, so a finger left down a
     * moment too long also got TikTok's long press, and its share panel opened over the comments
     * the tap had opened (#63).
     */
    private static long tookTapFrom = Long.MIN_VALUE;
    private static long tookTapAt = Long.MIN_VALUE;

    /** Forgets the last double taps answered and taken, for tests that share the class across runs. */
    static void forgetDoubleTaps() {
        answeredTapDown = Long.MIN_VALUE;
        answeredTapAt = Long.MIN_VALUE;
        tookTapFrom = Long.MIN_VALUE;
        tookTapAt = Long.MIN_VALUE;
    }

    /**
     * Whether a long press whose touch went down at {@code downTime} is the second touch of a
     * double tap Hushfeed took. The detector hands the tap either touch's down, and either went
     * down before the tap ran, so a touch that went down in between is the second one. A new
     * touch goes down after.
     */
    static boolean isTakenDoubleTap(long downTime) {
        return tookTapAt != Long.MIN_VALUE && downTime >= tookTapFrom && downTime <= tookTapAt;
    }

    /**
     * The same for a press that arrives without its touch, from TikTok's own timers. They fire
     * 350 to 500 ms after the down, and the tap ran at that down, so a press that soon after the
     * tap is its touch. A new one needs a lift and a full hold first, so only a third touch that
     * goes down within about a third of a second of the tap and stays down loses its press here.
     */
    static boolean isTakenDoubleTap() {
        if (tookTapAt == Long.MIN_VALUE) return false;
        long since = SystemClock.uptimeMillis() - tookTapAt;
        return since >= 0 && since <= Math.max(ViewConfiguration.getLongPressTimeout(), 500) + 300;
    }

    /** {@link #onDoubleTap(MotionEvent)} for a tap with no event to tell it apart by. */
    public static boolean onDoubleTap() {
        return runAction(Settings.DOUBLE_TAP_ACTION.get());
    }

    /**
     * Opens the comments of the video on screen, for a gesture made on it, or says there are none.
     *
     * <p>Not by the playing video's id. That follows the player's progress, which names the new
     * video only once it starts playing, so right after a swipe it still named the one before and
     * the press went to that video's button: its comments opened, its "restricted" message showed,
     * or with no button bound to it Hushfeed's toast did (#63). The id now only breaks a tie.
     */
    static void openOnScreenComments() {
        String playing = Reflect.string(CurrentVideoAuthor.getAweme(), "getAid", "aid");
        if (!openVisibleComments(playing)) {
            Utils.showToastShort(L10n.t("Comments aren't available for this video"));
        }
    }

    /** Eligibility callback from the native edge-speedup component before its 300 ms timer. */
    public static boolean allowNativeEdgeSpeedup(float x) {
        if (edgeSeekDelta(x) != 0) return false;
        // Sides only: the middle third is TikTok's, so its own hold may start there. On the
        // sides the Long press choice runs, and the 2x hold must not start under it (#136).
        if (leavesPressToTikTok(sideOf(x))) return true;
        return !takesLongPress(Settings.LONG_PRESS_ACTION.get());
    }

    /** Whether this Long press choice gives the press to an action of Hushfeed's, not TikTok's. */
    public static boolean takesLongPress(String action) {
        return "nothing".equals(action) || "comments".equals(action) || "like".equals(action)
                || "original_sound".equals(action) || "copy_link".equals(action)
                || "copy_sound_link".equals(action) || "youtube_music".equals(action)
                || "sleep_timer".equals(action) || "save_frame".equals(action)
                || "save_cover".equals(action);
    }

    /**
     * How far a long press at this point should move the video, in milliseconds. Zero when
     * the press landed in the middle, when edge seeking is off, or when there is no screen
     * to measure the press against.
     */
    static long edgeSeekDelta(MotionEvent event) {
        return event == null ? 0 : edgeSeekDelta(event.getX());
    }

    private static long edgeSeekDelta(float x) {
        if (!Settings.EDGE_SEEK.get()) return 0;
        int seconds = Settings.EDGE_SEEK_SECONDS.get();
        if (seconds <= 0) return 0;
        // The dialog offers at most 60. A restored backup is never asked, and a press that
        // jumps an hour is indistinguishable from the video ending.
        seconds = Math.min(60, seconds);
        int side = sideOf(x);
        if (side == SIDE_LEFT) return -seconds * 1000L;
        if (side == SIDE_RIGHT) return seconds * 1000L;
        return 0;
    }

    private static final int SIDE_LEFT = -1;
    private static final int SIDE_MIDDLE = 0;
    private static final int SIDE_RIGHT = 1;
    /** No window to place the press against. */
    private static final int SIDE_UNKNOWN = 2;

    /**
     * Which third of the window a press at {@code x} landed in, shared by Seek from the edges and
     * Only on the sides of the video (#136) so both draw the line in the same place.
     */
    private static int sideOf(float x) {
        // The window's width, not the display's: the press is placed against the window.
        Context context = Utils.getWindowContext();
        if (context == null) return SIDE_UNKNOWN;
        int width = context.getResources().getDisplayMetrics().widthPixels;
        if (width <= 0) return SIDE_UNKNOWN;

        // Whole pixels, so a press exactly on a third lands on one side of the line every
        // time. A screen width times a third does not, and 106.666664 sits below 106.66667.
        int third = width / 3;
        // The listener sits on the cell's touch layer, which fills the window, so the press
        // is placed against the window. getRawX would be the position on the whole display,
        // which in a side by side split view puts every press in the right hand third.
        if (x < third) return SIDE_LEFT;
        if (x >= width - third) return SIDE_RIGHT;
        return SIDE_MIDDLE;
    }

    /** {@link #sideOf(float)} for a press that may have no event to place. */
    private static int sideOf(MotionEvent event) {
        return event == null ? SIDE_UNKNOWN : sideOf(event.getX());
    }

    /**
     * Whether Only on the sides of the video hands a press in this third back to TikTok: the
     * switch is on, the Long press choice is one Hushfeed takes, and the press is in the middle.
     * A press with no known position keeps today's behavior and runs the choice.
     */
    private static boolean leavesPressToTikTok(int side) {
        return side == SIDE_MIDDLE && Settings.LONG_PRESS_SIDES_ONLY.get()
                && takesLongPress(Settings.LONG_PRESS_ACTION.get());
    }

    /**
     * Runs before TikTok's own long press handling. Returning true swallows the gesture,
     * which is what keeps the 2x hold and the quick share sheet from also firing. A double tap
     * Hushfeed took keeps its second touch: the press is swallowed and nothing else runs.
     */
    public static boolean onLongPress(MotionEvent event) {
        if (event == null ? isTakenDoubleTap() : isTakenDoubleTap(event.getDownTime())) return true;
        return handleLongPress(edgeSeekDelta(event), sideOf(event));
    }

    /** Ordinary feed cells deliver the saved local DOWN x through their native timer. */
    public static boolean onLongPress(float x) {
        if (isTakenDoubleTap()) return true;
        return handleLongPress(edgeSeekDelta(x), sideOf(x));
    }

    private static boolean handleLongPress(long delta, int side) {
        if (delta != 0) {
            // Named, so a post that never reported progress cannot move the video before it. Right
            // after a swipe the new video may not have, and the press says so instead.
            String videoId = Reflect.string(onScreenAweme(), "getAid", "aid");
            if (!FeedSeek.seekBy(videoId, delta)) Utils.showToastShort(L10n.t("Nothing is playing to seek"));
            // The edge belongs to the seek whether or not it worked, so the 2x hold that would
            // otherwise start under the finger does not fire on top of it.
            return true;
        }
        // Only on the sides: a press in the middle opens TikTok's own menu (#136).
        if (leavesPressToTikTok(side)) return false;
        return runAction(Settings.LONG_PRESS_ACTION.get());
    }

    /**
     * What a gesture set to {@code action} does, for Double tap and Long press alike. True when
     * the gesture was Hushfeed's; false leaves it to TikTok, which is what its own choice asks for
     * and what a value this build doesn't know gets, as a backup from a newer build can carry.
     */
    static boolean runAction(String action) {
        if ("nothing".equals(action)) return true;
        if ("like".equals(action)) {
            likeOnScreenVideo();
            return true;
        }
        if ("copy_link".equals(action)) {
            copyVideoLink(onScreenAweme());
            return true;
        }
        if ("copy_sound_link".equals(action)) {
            // The same treatment the video link above gets. TikTok's own sound share URL is a
            // share URL like any other: it carries the parameters that say who sent it, and the
            // custom share domain belongs on it too. Only the link this builds from the sound's
            // id has never had a query on it.
            String sound = soundLink(onScreenAweme());
            String clean = sound == null ? null : ShareUrlSanitizer.rewriteShareUrl(sound);
            if (copyToClipboard("TikTok sound", clean)) {
                if (android.os.Build.VERSION.SDK_INT < 33) {
                    Utils.showToastShort(L10n.t("Sound link copied"));
                }
            } else {
                Utils.showToastShort(L10n.t("This video has no sound of its own"));
            }
            return true;
        }
        if ("original_sound".equals(action)) {
            OriginalSoundDownloads.start(onScreenAweme(), Utils.getActivity());
            return true;
        }
        if ("youtube_music".equals(action)) {
            Context context = Utils.getActivity();
            YouTubeMusicSearch.open(onScreenAweme(),
                    context != null ? context : Utils.getContext());
            return true;
        }
        if ("sleep_timer".equals(action)) {
            // Posted, so the picker opens after the press is done with the touch.
            Utils.runOnMainThread(() -> SleepTimer.choose(Utils.getVisibleActivity()));
            return true;
        }
        if ("save_frame".equals(action)) {
            // Read on the press itself, so the frame saved is the one under the finger. The
            // position only names the file, and only when the ticks are this video's.
            Object aweme = onScreenAweme();
            String videoId = Reflect.string(aweme, "getAid", "aid");
            FrameSaver.save(Utils.getVisibleActivity(), aweme, FeedSeek.positionOf(videoId));
            return true;
        }
        if ("save_cover".equals(action)) {
            CoverSaver.save(Utils.getActivity(), onScreenAweme());
            return true;
        }
        if ("speed_toggle".equals(action)) {
            // This video's speed alone, through the player on screen, so the next one plays as usual (#136).
            float speed = PlaybackSpeedPatch.toggleBoost();
            Utils.showToastShort(Float.isNaN(speed) ? L10n.t("Nothing is playing to speed up")
                    : L10n.f("Playing at %1$sx", PlaybackSpeedPatch.speedLabel(speed)));
            return true;
        }
        if (!"comments".equals(action)) return false;
        openOnScreenComments();
        return true;
    }

    /** The page for the post's sound, or null when the post carries only its own audio. */
    static String soundLink(Object aweme) {
        Object music = aweme == null ? null : Reflect.property(aweme, "getMusic", "music");
        if (music == null) return null;
        Object shareInfo = Reflect.property(music, "getShareInfo", "shareInfo");
        String shared = shareInfo == null
                ? null : Reflect.string(shareInfo, "getShareUrl", "shareUrl");
        if (shared == null) shared = Reflect.string(music, "getShareUrl", "shareUrl");
        if (shared != null && shared.startsWith("https://")) return shared;

        String id = SoundIdentity.idOf(music);
        if (id == null || id.isEmpty()) return null;
        // The slug in front of the id is decoration; the id is what the page is looked up by.
        return "https://www.tiktok.com/music/x-" + id;
    }

    /**
     * Copies {@code aweme}'s link and says so, for a gesture and for a press on Share alike.
     * Android 13 and newer show their own note for every copy, so the toast is for older ones.
     */
    public static void copyVideoLink(Object aweme) {
        String link = ExternalDownloader.shareUrl(aweme);
        // The same treatment a shared link gets: TikTok's own link carries the parameters
        // that say who sent it, and the clipboard is somewhere else that goes.
        String clean = link == null ? null : ShareUrlSanitizer.rewriteShareUrl(link);
        if (copyToClipboard("TikTok link", clean)) {
            if (android.os.Build.VERSION.SDK_INT < 33) {
                Utils.showToastShort(L10n.t("Link copied"));
            }
        } else {
            Utils.showToastShort(L10n.t("This video has no link to copy"));
        }
    }

    /**
     * Puts a link on the clipboard through the shared helper, which marks the clip sensitive on
     * the Android versions that understand the flag. These two were the only clips in the bundle
     * building their own {@code ClipData}, so they were the only ones a clipboard viewer could
     * read back without the warning.
     */
    static boolean copyToClipboard(String label, String text) {
        if (text == null || text.isEmpty()) return false;
        Context context = Utils.getContext();
        if (context == null) return false;
        // Asked once and used once. Checking one call and letting the helper make its own
        // would be a guard over a different answer than the one that gets dereferenced.
        if (context.getSystemService(Context.CLIPBOARD_SERVICE) == null) return false;
        try {
            Utils.setClipboard(context, label, text);
        } catch (RuntimeException unavailable) {
            Logger.printException(() -> "Could not put " + label + " on the clipboard", unavailable);
            return false;
        }
        return true;
    }

    public static boolean openComments(String videoId) {
        return openComments(videoId, false);
    }

    /**
     * The button bound to this video. With {@code outsidePagerOnly}, a button in TikTok's feed
     * pager doesn't count: the screen already said its cell is out of sight.
     */
    private static boolean openComments(String videoId, boolean outsidePagerOnly) {
        Map.Entry<Object, CommentControl> bound = boundButton(videoId, outsidePagerOnly);
        return bound != null && press(bound.getKey(), bound.getValue().view.get());
    }

    /** The button bound to this video, a shown one before a hidden one, or null. */
    private static Map.Entry<Object, CommentControl> boundButton(String videoId, boolean outsidePagerOnly) {
        if (videoId == null || videoId.isEmpty()) return null;
        Map.Entry<Object, CommentControl> hidden = null;
        for (Map.Entry<Object, CommentControl> entry : COMMENTS.entrySet()) {
            CommentControl control = entry.getValue();
            View view = control.view.get();
            if (!videoId.equals(control.videoId) || view == null || !view.isAttachedToWindow()) continue;
            if (outsidePagerOnly && cellOf(view) != view) continue;
            if (view.isShown() && view.getGlobalVisibleRect(new Rect())) return entry;
            // Clear display can hide the action rail while its native click handler remains usable.
            hidden = entry;
        }
        return hidden;
    }

    /** The feed's pager, in the main feed and in a video opened from a profile or a search. */
    static final String FEED_PAGER = "com.ss.android.ugc.aweme.common.widget.VerticalViewPager";

    /**
     * Presses the comment button of the video on screen: the one in the window in front whose
     * cell shows most, the playing id breaking a tie. With nothing on screen to judge by, the
     * button bound to the playing id, as before, but only off TikTok's pager. On it, a LIVE or
     * an ad on screen has no comment button, and the playing id there still names the video
     * before, a page away.
     */
    static boolean openVisibleComments(String playingId) {
        OnScreen screen = onScreen(playingId);
        // A feed in front whose cell has no button is a LIVE or an ad: no comments, and nothing
        // elsewhere stands in for them.
        if (screen.control == null) return !screen.feedInFront && openComments(playingId, true);
        String pressed = screen.control.videoId;
        if (playingId == null || !playingId.equals(pressed)) {
            Logger.printDebug(() -> "Comments for the video on screen (" + pressed
                    + "), the player still names " + playingId);
        }
        return press(screen.owner, screen.view);
    }

    /**
     * Likes the video on screen through its heart, or says it can't. The video is the one a
     * gesture set to comments would open (#63), so a like right after a swipe doesn't land on the
     * video before.
     */
    static void likeOnScreenVideo() {
        String playing = Reflect.string(CurrentVideoAuthor.getAweme(), "getAid", "aid");
        if (!likeVisibleVideo(playing)) {
            Utils.showToastShort(L10n.t("Likes aren't available for this video"));
        }
    }

    /**
     * Presses the heart in the cell of the comment button {@link #openVisibleComments} would
     * press. A feed in front whose cell has no button is a LIVE or an ad, which nothing here
     * likes; with no feed in front, the button bound to the playing id stands in, as it does for
     * the comments.
     */
    static boolean likeVisibleVideo(String playingId) {
        OnScreen screen = onScreen(playingId);
        View button = screen.view;
        if (button == null && !screen.feedInFront) {
            Map.Entry<Object, CommentControl> bound = boundButton(playingId, true);
            if (bound != null) button = bound.getValue().view.get();
        }
        Object heart = button == null ? null : heartBeside(button);
        return heart != null && pressLike(heart);
    }

    /**
     * The heart nearest the comment button: the first registered heart under one of the button's
     * parents, going up. The climb ends at the button's cell, in TikTok's pager or in any other
     * pager or list, so a cell whose heart is missing never borrows the next video's. A heart
     * only has to be attached, since Clear display hides the rail and its assem still likes.
     */
    static Object heartBeside(View button) {
        for (ViewParent parent = button.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (holdsCells(parent)) break;
            for (Map.Entry<Object, WeakReference<View>> entry : HEARTS.entrySet()) {
                View heart = entry.getValue().get();
                if (heart != null && heart.isAttachedToWindow() && isInside(heart, (View) parent)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /**
     * Whether this parent lays out cells of its own: TikTok's feed pager, or a ViewPager,
     * RecyclerView or AdapterView, TikTok's subclasses included. On 47.1.4 the rail between a
     * comment button and its heart is a LinearLayout, which isn't one.
     */
    static boolean holdsCells(ViewParent parent) {
        if (parent instanceof android.widget.AdapterView) return true;
        for (Class<?> type = parent.getClass(); type != null && type != View.class; type = type.getSuperclass()) {
            String name = type.getSimpleName();
            if (FEED_PAGER.equals(type.getName()) || "ViewPager".equals(name) || "ViewPager2".equals(name)
                    || "RecyclerView".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInside(View view, View group) {
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (parent == group) return true;
        }
        return false;
    }

    /**
     * Presses the heart the way TikTok's own long press like does (LongPressDiggAssem). The heart's
     * click is a toggle that takes a like back off a liked video. This one likes only a video that
     * isn't liked yet, through the same handler the click runs, so the count, the animation and
     * Confirm likes all work as they do for a tap on the heart.
     */
    private static boolean pressLike(Object owner) {
        Method like = likePress(owner);
        if (like == null) return false;
        try {
            like.invoke(owner);
            Logger.printDebug(() -> "Liked through " + like.getName());
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            Logger.printException(() -> "Could not like the video from the feed gesture", exception);
            return false;
        }
    }

    /**
     * The post a long press was made on, for the actions that need more than its comments.
     *
     * <p>Not the playing post. Until a new video starts playing, that's still the one before,
     * so a link copied right after a swipe was that video's (#63). With a feed in front, it's
     * the post bound to its cell on screen, and none for a cell without a button (a LIVE or an
     * ad). Anywhere else, or with no button registered in front at all, which a build that
     * moved the button would look like, it's the playing post as before.
     */
    static Object onScreenAweme() {
        Object playing = CurrentVideoAuthor.getAweme();
        String playingId = Reflect.string(playing, "getAid", "aid");
        OnScreen screen = onScreen(playingId);
        if (!screen.feedInFront) return playing;
        if (screen.control == null) return screen.registeredInFront ? null : playing;
        Object aweme = screen.control.aweme.get();
        if (aweme != null) return aweme;
        return playingId != null && playingId.equals(screen.control.videoId) ? playing : null;
    }

    /** What the screen says about the video a gesture was made on. */
    private static final class OnScreen {
        /** The comment button of the cell showing most, its assem and its binding; or nulls. */
        Object owner;
        View view;
        CommentControl control;
        /** The window in front shows a cell of TikTok's feed pager, with a button or without. */
        boolean feedInFront;
        /** Some registered button, on screen or not, lives in the window in front. */
        boolean registeredInFront;
    }

    /**
     * The window in front first, then how much of the cell shows, then the playing id. With a
     * feed in front, only its own cells count: a feed left showing behind it, the main feed
     * under a video opened from a profile, is out of sight even when its window isn't.
     */
    private static OnScreen onScreen(String playingId) {
        OnScreen screen = new OnScreen();
        Activity front = Utils.getVisibleActivity();
        Window window = front == null ? null : front.getWindow();
        View frontRoot = window == null ? null : window.peekDecorView();
        Rect visible = new Rect();
        screen.feedInFront = showsFeedCell(frontRoot, visible);
        boolean bestInFront = false;
        boolean bestPlaying = false;
        float bestShare = 0;
        for (Map.Entry<Object, CommentControl> entry : COMMENTS.entrySet()) {
            View view = entry.getValue().view.get();
            if (view == null || !view.isAttachedToWindow()) continue;
            boolean inFront = frontRoot != null && view.getRootView() == frontRoot;
            screen.registeredInFront |= inFront;
            if (screen.feedInFront && !inFront) continue;
            float share = onScreenShare(cellOf(view), visible);
            if (share <= 0) continue;
            String id = entry.getValue().videoId;
            boolean playing = playingId != null && playingId.equals(id);
            if (screen.view != null
                    && !beats(inFront, share, playing, bestInFront, bestShare, bestPlaying)) continue;
            screen.owner = entry.getKey();
            screen.view = view;
            screen.control = entry.getValue();
            bestInFront = inFront;
            bestShare = share;
            bestPlaying = playing;
        }
        return screen;
    }

    /** Whether a cell of TikTok's feed pager shows anywhere under {@code view}. */
    private static boolean showsFeedCell(View view, Rect visible) {
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        boolean pager = FEED_PAGER.equals(group.getClass().getName());
        for (int i = 0, count = group.getChildCount(); i < count; i++) {
            View child = group.getChildAt(i);
            if (pager ? onScreenShare(child, visible) > 0 : showsFeedCell(child, visible)) return true;
        }
        return false;
    }

    /** The window in front first, then how much of the cell shows, then the playing id. */
    private static boolean beats(boolean inFront, float share, boolean playing,
                                 boolean bestInFront, float bestShare, boolean bestPlaying) {
        if (inFront != bestInFront) return inFront;
        if (share != bestShare) return share > bestShare;
        return playing && !bestPlaying;
    }

    /**
     * The video cell a comment button sits in: the pager's child above it. The button itself on
     * a surface without that pager, which then has to be shown to count.
     */
    public static View cellOf(View view) {
        View child = view;
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (FEED_PAGER.equals(parent.getClass().getName())) return child;
            child = (View) parent;
        }
        return view;
    }

    /**
     * How much of the cell is on screen, from 0 to 1. Nothing inside the cell is asked: Clear
     * display hides the rail of the video being watched, and on some builds lays the cell out
     * with it already hidden. Everything above the cell has to be shown, which leaves out a feed
     * behind another tab, or behind a detail page, whose cells keep their last layout.
     */
    public static float onScreenShare(View cell, Rect visible) {
        float area = (float) cell.getWidth() * cell.getHeight();
        ViewParent parent = cell.getParent();
        if (area <= 0 || cell.getVisibility() != View.VISIBLE || !(parent instanceof View)
                || !((View) parent).isShown() || !cell.getGlobalVisibleRect(visible)) {
            return 0;
        }
        return visible.width() * (float) visible.height() / area;
    }

    /** The Hook status family the comment press reports under. */
    static final String FAMILY = "double tap";
    /** The ability the comment assem implements; its one no-argument method is the icon press. */
    static final String COMMENT_ABILITY = "com.ss.android.ugc.aweme.feed.assem.ability.IVideoCommentAbility";

    /**
     * Presses the comment button the way TikTok's own keyboard shortcut does.
     *
     * <p>The registered view's click listener does nothing. TikTok wires the real handler as a
     * touch listener and leaves a placeholder click listener beside it, so {@code performClick()}
     * fires the placeholder, reports true, and no sheet opens; that is what a double tap set to
     * comments did on the S22 with 46.2.3 on 2026-09-14. The assem the view belongs to implements
     * {@code IVideoCommentAbility}, whose only no-argument method is the comment-icon press with
     * all of TikTok's own gating in front of it (comments turned off, a private account, FTC).
     * It is that shape on 46.2.3, 46.7.3 and 46.8.3 under three different names, so it is found
     * by shape. A build without the ability falls back to the click, and Hook status says so.
     */
    private static boolean press(Object owner, View view) {
        Method icon = commentPress(owner);
        if (icon == null) return click(view);
        try {
            icon.invoke(owner);
            Logger.printDebug(() -> "Pressed the comment button through " + icon.getName());
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            Logger.printException(() -> "Could not press the comment button from the feed gesture", exception);
            return false;
        }
    }

    /**
     * The ability's one no-argument method, or null when the owner has no such ability or the
     * ability has grown a second method of that shape and the press can no longer be told apart.
     */
    static Method commentPress(Object owner) {
        return abilityPress(owner, COMMENT_ABILITY, FAMILY);
    }

    /** The Hook status family the like press reports under. */
    static final String LIKE_FAMILY = "long press like";
    /**
     * The ability the heart's assem implements. Its one no-argument method is TikTok's long press
     * like, which likes a video that isn't liked and leaves a liked one be. On 47.1.4 it's MG0,
     * beside the heart's click (Dw0, the one that takes a String), and the shape picks it the way
     * it picks the comment press.
     */
    static final String LIKE_ABILITY = "com.ss.android.ugc.feed.platform.cell.ability.VideoDiggAssemAbility";

    /** The heart's ability's one no-argument method, found as the comment press is. */
    static Method likePress(Object owner) {
        return abilityPress(owner, LIKE_ABILITY, LIKE_FAMILY);
    }

    private static Method abilityPress(Object owner, String abilityName, String family) {
        if (owner == null) return null;
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (Class<?> ability : type.getInterfaces()) {
                if (!abilityName.equals(ability.getName())) continue;
                Method found = null;
                for (Method candidate : ability.getDeclaredMethods()) {
                    if (candidate.getParameterTypes().length != 0
                            || candidate.getReturnType() != void.class) {
                        continue;
                    }
                    if (found != null) {
                        HookStatus.missingMember(family, "one no-argument method on",
                                ability.getName(), "press");
                        return null;
                    }
                    found = candidate;
                }
                if (found == null) break;
                found.setAccessible(true);
                HookStatus.bound(family, ability.getName() + "#" + found.getName());
                return found;
            }
        }
        HookStatus.missingMember(family, "ability", owner.getClass().getName(),
                abilityName.substring(abilityName.lastIndexOf('.') + 1));
        return null;
    }

    private static boolean click(View view) {
        try {
            return view.performClick();
        } catch (RuntimeException exception) {
            Logger.printException(() -> "Could not open comments from the feed gesture", exception);
            return false;
        }
    }

    /**
     * Whether TikTok's main pager is on its feed page, the one a left swipe leaves for the creator's
     * profile. The profile is the pager's last page and the feed the one before it: on 47.0.3 the
     * home pager's adapter holds three pages, a side panel at 0, the feed at 1 and the profile at 2
     * (the probe's pagerstate on the S22, 2026-09-23), and a video opened from a profile, search or
     * a link sits in a pager of two, the video and the profile. Unreadable reads as not the feed.
     */
    static boolean onFeedPage(Object pager) {
        int page = currentPage(pager);
        int count = pageCount(pager);
        if (page < 0 || count < 2) return false;
        if (!swipeBound) {
            swipeBound = true;
            HookStatus.bound(SWIPE_FAMILY, "main pager");
        }
        return page == count - 2;
    }

    /*
     * Swipe left. TikTok's feed-to-profile pager (on 47.0.3 the base class X.05lU: the home
     * pager's own class extends it, and a video opened from a profile, search or a link sits in a
     * bare one inflated from XML) asks one private check whether it may page, from its dispatch,
     * its intercept and its own touch handling, each only while its paging valve is set. The
     * patch reads the gesture at the start of the dispatch, before anything asks, and hears
     * TikTok's own answer at the check's return.
     */

    /** The Hook status family the left swipe reports under. */
    static final String SWIPE_FAMILY = "swipe left";

    private static boolean swipeBound;
    private static float swipeDownX;
    private static float swipeDownY;
    /** Where the gesture is now, for the direction {@link #allowProfileSwipe} holds. */
    private static float swipeLastX;
    /** Whether TikTok itself would page during this gesture: its own check answered yes. */
    private static boolean swipePagingOn;
    /** Whether this gesture was held: TikTok said yes and the setting said no. */
    private static boolean swipeHeld;
    private static boolean swipeTracking;
    private static boolean swipeFired;

    /** Each event, at the start of the pager's dispatch: before anything in it asks whether it may page. */
    public static void onMainPagerDispatch(Object pager, MotionEvent event) {
        if (event == null) return;
        swipeLastX = event.getX();
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return;
        swipeDownX = event.getX();
        swipeDownY = event.getY();
        swipePagingOn = false;
        swipeHeld = false;
        swipeTracking = "comments".equals(Settings.SWIPE_LEFT_ACTION.get()) && onFeedPage(pager);
        swipeFired = false;
    }

    /**
     * TikTok's own answer to whether the pager may page, as its check returns it, and the answer
     * the pager gets. TikTok's no stays no: it turns paging off itself on surfaces that aren't
     * the feed (the Inbox, your own profile), and nothing here pages or opens anything there.
     */
    public static boolean pagingEnabled(Object pager, boolean original) {
        if (!original) return false;
        swipePagingOn = true;
        if (allowProfileSwipe(pager)) return true;
        swipeHeld = true;
        return false;
    }

    /**
     * Whether a pager TikTok lets page may page for this gesture. Only a gesture heading for the
     * profile is held, and only on the feed page: at the touch down TikTok still records where the
     * gesture starts, a swipe the other way still opens TikTok's side panel, from the profile the
     * swipe back stays TikTok's, and a pager whose pages can't be read pages as usual.
     */
    static boolean allowProfileSwipe(Object pager) {
        if ("default".equals(Settings.SWIPE_LEFT_ACTION.get())) return true;
        if (towardProfile(pager, swipeLastX - swipeDownX) <= 0) return true;
        return !onFeedPage(pager);
    }

    /**
     * How far a horizontal movement goes toward the profile page: to the left in a left-to-right
     * layout, to the right in a right-to-left one, where TikTok's pager runs the other way. Decided
     * the way TikTok decides it (X.05QH.LIZJ): the layout direction of the configuration.
     */
    static float towardProfile(Object pager, float dx) {
        boolean rtl = pager instanceof View
                ? ((View) pager).getContext().getResources().getConfiguration().getLayoutDirection()
                        == View.LAYOUT_DIRECTION_RTL
                : TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) == View.LAYOUT_DIRECTION_RTL;
        return rtl ? dx : -dx;
    }

    /**
     * Each event the pager's intercept or its own touch handling sees: from the intercept until it
     * takes the gesture, from onTouchEvent after. A child that takes the gesture for itself, a
     * photo carousel for one, keeps the rest from both, so only a swipe nothing else wanted gets
     * this far. With Swipe left set to comments, a clearly sideways move toward the profile from
     * the feed page opens the video's comments, once a swipe, where TikTok itself would page.
     */
    public static void onMainPagerTouch(Object pager, MotionEvent event) {
        if (event == null || event.getActionMasked() != MotionEvent.ACTION_MOVE) return;
        if (!swipeTracking || swipeFired || !swipePagingOn) return;
        float dx = event.getX() - swipeDownX;
        float dy = event.getY() - swipeDownY;
        if (towardProfile(pager, dx) < swipeDistance() || Math.abs(dx) < 2 * Math.abs(dy)) return;
        swipeFired = true;
        swipeCommentsOpener.run();
    }

    /**
     * The pager's own touch handling. TikTok hands it the up of every gesture whatever the check
     * says, and the pager picks its page there from the drag it saw and the fling: a gesture that
     * dragged a little before it was held, or touched down while the pager was settling back from
     * the profile, could land on the profile. A held gesture ends in a cancel instead, which
     * settles the pager on the page it is on.
     */
    public static void onMainPagerOwnTouch(Object pager, MotionEvent event) {
        onMainPagerTouch(pager, event);
        if (event != null && swipeHeld && event.getActionMasked() == MotionEvent.ACTION_UP) {
            event.setAction(MotionEvent.ACTION_CANCEL);
        }
    }

    /** How far left counts as a swipe: an eighth of the screen's width. */
    static float swipeDistance() {
        Context context = Utils.getWindowContext();
        int width = context == null ? 0 : context.getResources().getDisplayMetrics().widthPixels;
        return (width > 0 ? width : 1080) / 8f;
    }

    /** What a left swipe set to comments does; a test stands in its own. */
    static Runnable swipeCommentsOpener = GestureActions::openOnScreenComments;

    private static Method currentItem;
    private static Class<?> currentItemOwner;
    private static Method adapterGetter;
    private static Class<?> adapterOwner;
    private static Method countGetter;
    private static Class<?> countOwner;

    /** The pager's current page, or -1 when it can't be read. Asked on every touch, so the lookup is kept. */
    static int currentPage(Object pager) {
        if (pager == null) return -1;
        try {
            Method getter = currentItem;
            if (getter == null || currentItemOwner != pager.getClass()) {
                getter = pager.getClass().getMethod("getCurrentItem");
                getter.setAccessible(true);
                currentItem = getter;
                currentItemOwner = pager.getClass();
            }
            Object page = getter.invoke(pager);
            return page instanceof Integer ? (Integer) page : -1;
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            HookStatus.missingMember(SWIPE_FAMILY, "method", pager.getClass().getName(), "getCurrentItem");
            return -1;
        }
    }

    /** How many pages the pager's adapter holds, or -1 when it can't be read. */
    static int pageCount(Object pager) {
        if (pager == null) return -1;
        try {
            Method getter = adapterGetter;
            if (getter == null || adapterOwner != pager.getClass()) {
                getter = pager.getClass().getMethod("getAdapter");
                getter.setAccessible(true);
                adapterGetter = getter;
                adapterOwner = pager.getClass();
            }
            Object adapter = getter.invoke(pager);
            if (adapter == null) return -1;
            Method count = countGetter;
            if (count == null || countOwner != adapter.getClass()) {
                // Made accessible: the adapter's own class need not be public.
                count = adapter.getClass().getMethod("getCount");
                count.setAccessible(true);
                countGetter = count;
                countOwner = adapter.getClass();
            }
            Object pages = count.invoke(adapter);
            return pages instanceof Integer ? (Integer) pages : -1;
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            HookStatus.missingMember(SWIPE_FAMILY, "method", pager.getClass().getName(), "getAdapter().getCount()");
            return -1;
        }
    }
}
