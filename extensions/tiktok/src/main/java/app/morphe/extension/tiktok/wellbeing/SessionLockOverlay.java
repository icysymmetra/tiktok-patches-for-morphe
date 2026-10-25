/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.wellbeing;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * Covers the feed while a hold is running, and nothing else.
 *
 * <p>It is a view over the activity's content root, the same place the block button lives, shown
 * only while the feed itself is on screen. Messages, a profile and search are all still there
 * underneath it, because the only thing that decides whether this is visible is whether the home
 * tab is selected. It also does not touch a single feed item, so the batch TikTok already
 * fetched is still sitting there when the hold ends, and nothing is refetched.
 *
 * <p>The way out of the hold is on the panel. Anything else drawn on the content root ends up
 * underneath it, because this covers the whole root and swallows every touch, so a banner
 * offering an Undo would be both invisible and untappable.
 */
public final class SessionLockOverlay {
    private static final long TICK_MS = 1_000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<View> overlayReference = new WeakReference<>(null);
    private static WeakReference<ViewGroup> geometryRootReference = new WeakReference<>(null);
    private static final ViewTreeObserver.OnGlobalLayoutListener GEOMETRY =
            SessionLockOverlay::syncGeometry;
    // All overlay geometry runs on the main thread. Reuse these on player and layout callbacks.
    private static final int[] ROOT_POSITION = new int[2];
    private static final int[] NAVIGATION_POSITION = new int[2];
    /** What each view behind the panel said about itself before the hold covered it. */
    /** What a curtained child was, and which panels are over it. */
    private static final class Curtained {
        final int previous;
        final java.util.Set<View> panels = java.util.Collections.newSetFromMap(
                new java.util.WeakHashMap<>());

        Curtained(int previous) {
            this.previous = previous;
        }
    }

    private static final java.util.WeakHashMap<View, Curtained> previousAccessibilityImportance =
            new java.util.WeakHashMap<>();
    private static WeakReference<TextView> titleReference = new WeakReference<>(null);
    private static WeakReference<TextView> remainingReference = new WeakReference<>(null);
    private static WeakReference<TextView> releaseReference = new WeakReference<>(null);
    private static WeakReference<TextView> hintReference = new WeakReference<>(null);
    private static WeakReference<TextView> messagesReference = new WeakReference<>(null);
    private static volatile boolean ticking;
    /**
     * Whether the app is on screen. A stopped activity whose decor is still attached is not
     * finishing and not destroyed, so nothing in {@link #sync()} could tell the reader had gone
     * to another app, and a hold that runs to the reset hour kept a once-a-second main-thread
     * timer going for the whole of it while TikTok sat in recents.
     */
    private static volatile boolean foreground = true;

    /**
     * Runs only while a hold is running and the app is on screen. A repeating timer that
     * outlives the hold, or the app being looked at, would be a second-by-second wake-up for
     * nobody.
     */
    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            if (!foreground) {
                ticking = false;
                return;
            }
            // A change to the feed lock that waited for the day to start over lands here, since
            // nothing counts for a lock that has no budget behind it.
            BudgetChanges.applyDue(SessionBudget.now());
            sync();
            if (!wantsTick()) {
                ticking = false;
                return;
            }
            MAIN.postDelayed(this, TICK_MS);
        }
    };

    /** Written from the audio focus callback, which is not guaranteed to be the main thread. */
    private static volatile boolean quietened;
    private static AudioManager quietManager;
    /** A transient loss keeps the request on the stack without granting playback permission. */
    private static volatile boolean focusGranted;

    /**
     * Held while the panel covers the feed. Losing focus prevents an explicit player resume
     * when the hold ends, so another app keeps control of the sound.
     */
    private static final AudioManager.OnAudioFocusChangeListener QUIET = change -> {
        // Losing it for good, to a call or to another app, drops us off the focus stack, and
        // the flag has to follow or nothing would ever ask again. What must not happen is
        // asking again a second later: that is how you take the sound off the call that just
        // took it, once a second, for the length of the hold. So the flag is cleared and the
        // next request waits for the panel to be put up again, which only happens when the
        // reader comes back to the feed.
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            quietened = false;
            focusGranted = false;
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            focusGranted = false;
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            focusGranted = quietened;
        }
    };

    private SessionLockOverlay() {
    }

    static void resetForTests() {
        ticking = false;
        // PausePlayback's lifecycle callbacks report the app gone when a suite's activities stop,
        // and a stale false stops the countdown, and the sync it runs, for every later suite.
        foreground = true;
    }

    /**
     * Whether the timer has anything to do: a hold running, or the feed lock's panel in place,
     * shown or tucked away. The lock has no countdown, but it has a panel that must come down
     * the moment a bottom tab leaves the feed and go back up when one returns to it, and a
     * paused video sends no progress to say so.
     */
    private static boolean wantsTick() {
        return SessionBudget.isLocked() || (FeedLock.isOn() && overlayReference.get() != null);
    }

    /** The app left the screen: the countdown stops on its next tick and the panel stays as it is. */
    public static void onBackground() {
        foreground = false;
    }

    /** The app is back: a hold still running gets its countdown back. */
    public static void onForeground() {
        foreground = true;
        BudgetChanges.applyDue(SessionBudget.now());
        ensureRunning();
        // A hold that ended while nothing ticked, the app away past the reset hour, left its
        // panel up with nothing to take it down: the tick needs a running hold to start, and the
        // player's progress never syncs an ended one.
        if (!SessionBudget.isLocked()) {
            Utils.runOnMainThread(() -> {
                // The feed lock has no countdown to bring a panel down or up, so a return to
                // the app is where it follows the switch.
                if (!SessionBudget.isLocked()
                        && (overlayReference.get() != null || FeedLock.isOn())) {
                    sync();
                }
            });
        }
    }

    /**
     * Starts the countdown if a hold is running. Called from the player's progress callback, so
     * the already-running case must not reach the budget's monitor at all.
     */
    public static void ensureRunning() {
        if (ticking || !(SessionBudget.isLocked() || FeedLock.isOn())) return;
        Utils.runOnMainThread(() -> {
            if (ticking || !(SessionBudget.isLocked() || FeedLock.isOn())) return;
            ticking = true;
            MAIN.post(TICK);
        });
    }

    /** Puts the overlay where it belongs for the moment, attaching or removing as needed. */
    public static void sync() {
        try {
            boolean budgetHold = SessionBudget.isLocked();
            if (!budgetHold && !FeedLock.covers()) {
                detach();
                return;
            }
            // The last video is playing out and the feed's swipe is off meanwhile. The panel and
            // its pause follow the video's end, which calls this again; the countdown's own tick
            // catches a wait that ran out at its limit instead.
            if (budgetHold && FinishLastVideo.pending()) {
                detach();
                return;
            }
            Activity activity = Utils.getActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                // Mid teardown, so there is nothing to attach to yet. The hold is still on and
                // the player may still be running, so the sound is still worth asking for.
                requestQuiet();
                return;
            }
            // The feed lock alone is stricter about what counts as the feed: a video opened from
            // a message or a search is a page of the same activity, and it is not covered.
            boolean onFeed = budgetHold ? FeedVisibility.isOnFeed(activity)
                    : FeedLock.coversFeedOn(activity);
            if (!onFeed) {
                // Messages, profiles and search still work, which the panel says in as many
                // words, so the hold has no business silencing anything played there.
                releaseQuiet();
                View existing = overlayReference.get();
                if (existing != null) {
                    existing.setVisibility(View.GONE);
                    // Hidden is not attached: the panel is left in place while the reader is on
                    // messages or a profile, and the feed has to be readable again meanwhile.
                    hideBehind(parentOf(existing), existing, false);
                }
                return;
            }
            // A native resume can arrive while the same panel is already visible. Reapply the
            // actual player pause on every hold sync, before audio focus changes its play state.
            SessionPlaybackHold.pauseIfPlaying();
            View before = overlayReference.get();
            View overlay = attach(activity);
            if (overlay == null) {
                // No content root to hold the panel. The feed is still running, so ask for the
                // sound anyway rather than leaving the hold with nothing at all.
                requestQuiet();
                return;
            }
            updateNavigationMargin(activity, parentOf(overlay), overlay);
            // The lock's panel follows the tabs by this timer: no tick or progress report comes
            // for a paused video on a tab the reader just left.
            if (!budgetHold) ensureRunning();
            // A panel that was just built, or one coming back from the reader being away on
            // messages or search. Not every tick: a tick that asked again would be asking a
            // phone call to give the sound back once a second, for as long as the hold runs.
            boolean goingUp = before != overlay || overlay.getVisibility() != View.VISIBLE;
            overlay.setVisibility(View.VISIBLE);
            if (goingUp) hideBehind(parentOf(overlay), overlay, true);
            if (goingUp) requestQuiet();
            // Over a cleared screen the tabs the panel points to are gone: bring them back.
            if (goingUp) RememberClearDisplayPatch.leaveForHold();
            if (goingUp && before == overlay && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                // A retained panel shown again after the reader was away on messages or search.
                // From P the pane title announces the arrival each time; before it, the one
                // announcement made at attach has already been spent.
                overlay.announceForAccessibility(holdTitle());
            }
            // Only as the panel goes up. Whether there is an Inbox tab changes when TikTok
            // rebuilds its tab bar, not second by second, and for a reader who has hidden Inbox
            // the answer is a failed lookup every time: asking on every tick would be a walk of
            // the whole view tree once a second for the length of the hold.
            if (goingUp) applyMessagesAction(activity);
            // Only when the number moved. The countdown is in whole minutes and this runs once a
            // second, so fifty-nine writes in sixty say exactly what was already there, and each
            // of them interrupted a screen reader.
            SettingsUi.setTextIfChanged(remainingReference.get(), remainingLabel());
            applyLockedState();
        } catch (Throwable error) {
            Logger.printException(() -> "Could not update the session lock overlay", error);
        }
    }

    /**
     * Offers the way to messages only while there is an Inbox tab to open.
     *
     * <p>Absent when this build renames the tab and when the reader has hidden Inbox in Feed
     * navigation, which is the same thing as far as the panel is concerned: the filter drops the
     * tab from the model, so no view is ever built and there is nothing to offer.
     */
    private static void applyMessagesAction(Activity activity) {
        TextView messages = messagesReference.get();
        if (messages == null) return;
        boolean reachable = activity != null && FeedVisibility.inboxTabView(activity) != null;
        messages.setVisibility(reachable ? View.VISIBLE : View.GONE);
    }

    /**
     * Takes the way out away on a day the reader locked, and says when it comes back.
     *
     * <p>Run on every sync rather than only when the panel is built, because the panel can
     * outlive the day: it is left attached while the reader is on messages or a profile.
     */
    private static void applyLockedState() {
        boolean budgetHold = SessionBudget.isLocked();
        // The title, the countdown and the way out belong to the budget's hold. The feed lock
        // alone is a calm panel with the title and the way to messages.
        // Whichever reason holds the panel now: a budget hold can take over a lock's panel and
        // give it back, and the pane title is what a screen reader announces on each arrival.
        String titleText = holdTitle();
        SettingsUi.setTextIfChanged(titleReference.get(), titleText);
        View panel = overlayReference.get();
        if (panel != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && !titleText.contentEquals(String.valueOf(panel.getAccessibilityPaneTitle()))) {
            panel.setAccessibilityPaneTitle(titleText);
        }
        TextView countdown = remainingReference.get();
        if (countdown != null) countdown.setVisibility(budgetHold ? View.VISIBLE : View.GONE);
        boolean locked = SessionBudget.lockedToday();
        TextView release = releaseReference.get();
        if (release != null) {
            int left = SessionBudget.passesLeftToday();
            // Spent is the same as locked as far as this control goes: there is no way through
            // today. The hint below still says what does work, so the panel is not a dead end.
            // The pass is the budget's. While the feed lock is on it would only say the feed is open
            // again over a panel that stays up, so it isn't offered.
            release.setVisibility(!budgetHold || FeedLock.isOn() || locked || left == 0
                    ? View.GONE : View.VISIBLE);
            String label = releaseLabel(left);
            // Both only when they moved: a content description is announced the same way the
            // text is, so writing it back unchanged is the same interruption.
            if (!android.text.TextUtils.equals(release.getText(), label)) {
                release.setText(label);
                release.setContentDescription(label);
            }
        }
        TextView hint = hintReference.get();
        if (hint == null) return;
        // One literal, because the translation gate reads the literal handed to L10n and a
        // string built from two of them is two entries it cannot find.
        SettingsUi.setTextIfChanged(hint, budgetHold && locked && !FeedLock.isOn()
                ? L10n.f("Today's budget is locked. The feed opens again at %1$s. Messages, profiles and search still work.", resetTimeLabel())
                : L10n.t("Messages, profiles and search still work."));
    }

    /**
     * What the way out says, which is how many are left when the reader capped them.
     *
     * <p>Each form is its own literal rather than a label with a count appended, because the
     * translation gate reads the literal handed to L10n and a sentence built from two of them is
     * two entries it cannot find.
     */
    static String releaseLabel(int passesLeft) {
        if (passesLeft == Integer.MAX_VALUE) return L10n.t("Open the feed anyway");
        if (passesLeft == 1) return L10n.t("Open the feed anyway, the last time today");
        return L10n.f("Open the feed anyway, %1$d left today", passesLeft);
    }

    /** The hour the locked day ends, on the reader's own clock. */
    public static String resetTimeLabel() {
        long until = SessionBudget.lockedUntilMs();
        if (until <= 0) return "";
        return timeLabel(until);
    }

    /** A moment on the reader's own clock, 4:00 AM or 04:00 as the phone is set. */
    public static String timeLabel(long at) {
        Context context = Utils.getContext();
        if (context == null) {
            return java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                    .format(new java.util.Date(at));
        }
        return android.text.format.DateFormat.getTimeFormat(context)
                .format(new java.util.Date(at));
    }

    static String remainingLabel() {
        long remainingMs = SessionBudget.lockRemainingMs();
        long totalMinutes = (remainingMs + 59_999L) / 60_000L;
        if (totalMinutes <= 0) return L10n.t("Less than a minute left");
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours == 0) {
            return L10n.quantity(Utils.getContext(), minutes, "One minute left", "%1$d minutes left");
        }
        // Built before the call so the clock face is not mistaken for text to translate.
        String clock = String.format(Locale.getDefault(), "%d:%02d", hours, minutes);
        return L10n.f("%1$s left", clock);
    }

    /** What the panel says it is for right now: the budget's message, or the feed lock's. */
    private static String holdTitle() {
        return SessionBudget.isLocked() ? SessionBudgetNotice.spentMessage() : L10n.t("The feed is locked");
    }

    private static View attach(Activity activity) {
        View existing = overlayReference.get();
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) return null;
        if (existing != null && existing.getParent() == root) return existing;

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        int sidePad = SettingsUi.dp(activity, 24);
        panel.setPadding(sidePad, sidePad, sidePad, sidePad);
        panel.setBackgroundColor(SettingsUi.OVERLAY_SCRIM_SOLID);
        // Swallows every touch, so the feed underneath stops scrolling without being emptied.
        panel.setClickable(true);
        panel.setFocusable(true);
        // Touch was the only thing it swallowed. A screen reader was told nothing when the hold
        // went up, and could still swipe through to the like, comment and share controls behind
        // it, which is the one thing the hold exists to stop. The arrival is announced by the
        // pane title below, and the countdown carries the live region itself: marking the whole
        // panel read the entire hold out again on every tick, because a live region announces
        // for anything that changes anywhere under it.

        TextView title = new TextView(activity);
        String titleText = holdTitle();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            panel.setAccessibilityPaneTitle(titleText);
        }
        title.setText(titleText);
        title.setTextColor(SettingsUi.OVERLAY_TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_HEADLINE);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);
        titleReference = new WeakReference<>(title);

        TextView remaining = new TextView(activity);
        remaining.setText(remainingLabel());
        // The one thing on the panel that changes while the hold runs, so it is the one thing
        // worth announcing. Set here rather than on the panel, and written only when the minute
        // changes (see sync), so the reader hears a new number and nothing else.
        remaining.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        // The panel is always this near-black scrim, so the countdown takes the colour meant
        // for what this project draws over the app rather than the settings accent, which is a
        // dark crimson in the light theme and 3:1 on black. SettingsUi.isDarkMode is a cached
        // flag the settings screen sets, so off the settings screen it answers for the system
        // theme rather than for this panel.
        remaining.setTextColor(SettingsUi.overlayAccentOn(true));
        remaining.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_DISPLAY);
        remaining.setGravity(Gravity.CENTER);
        remaining.setPadding(0, SettingsUi.dp(activity, 12), 0, SettingsUi.dp(activity, 12));
        panel.addView(remaining);
        remainingReference = new WeakReference<>(remaining);

        TextView hint = new TextView(activity);
        hint.setText(L10n.t(activity, "Messages, profiles and search still work."));
        hint.setTextColor(SettingsUi.OVERLAY_TEXT_MUTED);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY_SMALL);
        hint.setGravity(Gravity.CENTER);
        panel.addView(hint);
        hintReference = new WeakReference<>(hint);

        // The way out. A budget nobody can overrule is a budget people switch off instead, and
        // this has to be here rather than on a banner, which the panel would cover.
        TextView release = new TextView(activity);
        release.setText(L10n.t(activity, "Open the feed anyway"));
        release.setContentDescription(L10n.t(activity, "Open the feed anyway"));
        SettingsUi.markAsButton(release);
        release.setTextColor(SettingsUi.OVERLAY_TEXT);
        release.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY);
        release.setGravity(Gravity.CENTER);
        // A radius from the scale. It was 24 on a 48dp control, which is a pill, and it was the
        // only pill in the bundle: the four feed controls and the budget cue it sits beside all
        // read as rounded rectangles.
        // The press and focus states every control this bundle draws now carries. It was a flat
        // fill that looked the same before, during and after a press.
        release.setBackground(SettingsUi.overlayControl(activity, SettingsUi.RADIUS_OVERLAY));
        release.setFocusable(true);
        int padding = SettingsUi.dp(activity, 20);
        release.setPadding(padding, SettingsUi.dp(activity, 14), padding, SettingsUi.dp(activity, 14));
        release.setMinimumHeight(SettingsUi.dp(activity, 48));
        LinearLayout.LayoutParams releaseParams = new LinearLayout.LayoutParams(-2, -2);
        releaseParams.topMargin = SettingsUi.dp(activity, 28);
        release.setLayoutParams(releaseParams);
        release.setOnClickListener(view -> {
            // The model refuses this on a locked day and on a day whose passes are spent.
            // Checked through its answer, so a refused tap says nothing rather than claiming
            // the feed opened.
            if (FeedLock.isOn() || SessionBudget.lockedToday()) return;
            if (!SessionBudget.releaseLock()) {
                sync();
                return;
            }
            sync();
            Utils.showToastShort(L10n.t("The feed is open again"));
        });
        panel.addView(release);
        releaseReference = new WeakReference<>(release);

        // The panel says messages still work and then covers everything, so the reader has to
        // already know the Inbox tab is under it. This is that tab's own view, clicked the way a
        // finger would: no call into a renamed method, and TikTok's own listener does the rest.
        TextView messages = new TextView(activity);
        messages.setText(L10n.t(activity, "Open messages"));
        messages.setContentDescription(L10n.t(activity, "Open messages"));
        SettingsUi.markAsButton(messages);
        messages.setTextColor(SettingsUi.OVERLAY_TEXT_MUTED);
        messages.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY);
        messages.setGravity(Gravity.CENTER);
        messages.setPadding(padding, SettingsUi.dp(activity, 12), padding,
                SettingsUi.dp(activity, 12));
        messages.setMinimumHeight(SettingsUi.dp(activity, 48));
        messages.setBackground(SettingsUi.overlayAction(activity, SettingsUi.RADIUS_OVERLAY));
        messages.setFocusable(true);
        LinearLayout.LayoutParams messagesParams = new LinearLayout.LayoutParams(-2, -2);
        messagesParams.topMargin = SettingsUi.dp(activity, 8);
        messages.setLayoutParams(messagesParams);
        messages.setOnClickListener(view -> {
            View inbox = FeedVisibility.inboxTabView(activity);
            if (inbox == null) return;
            inbox.performClick();
            // Off the feed the panel hides itself rather than detaching, so the hold is still
            // running and comes back the moment the reader does.
            sync();
        });
        panel.addView(messages);
        messagesReference = new WeakReference<>(messages);
        applyMessagesAction(activity);

        applyLockedState();

        // Stops above the navigation. Covering the whole content root would take the tab bar
        // with it, and then messages, profiles and search are not reachable at all, which is the
        // one thing the panel says it leaves alone.
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        params.bottomMargin = navigationHeight(activity, root);
        panel.setLayoutParams(params);
        root.addView(panel);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // There is no pane title before P, so on those builds the arrival is said once by
            // hand. Needs a parent, which is why it is here and not beside the pane title.
            panel.announceForAccessibility(titleText);
        }
        hideBehind(root, panel, true);
        overlayReference = new WeakReference<>(panel);
        panel.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                if (overlayReference.get() == view) watchGeometry(parentOf(view));
            }

            @Override public void onViewDetachedFromWindow(View view) {
                if (overlayReference.get() == view) stopWatchingGeometry();
            }
        });
        watchGeometry(root);
        Logger.printDebug(() -> "Session lock overlay attached");
        return panel;
    }

    /**
     * The bottom margin that leaves TikTok's navigation reachable, measured on the main thread.
     *
     * <p>The content root can extend behind the system navigation bar. Its margin must include
     * that gap below TikTok's row, while root padding has already been deducted by FrameLayout.
     * Unknown or implausible geometry keeps the feed fully covered until a later layout.
     */
    public static int navigationHeight(Activity activity, ViewGroup root) {
        if (activity == null || root == null || root.getHeight() <= 0) return 0;
        View homeTab = FeedVisibility.homeTabView(activity);
        if (homeTab == null) return 0;

        View bar = homeTab;
        int contentWidth = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
        // Up to the row that spans the width, which is the bar rather than the one tab in it.
        for (int step = 0; step < 4 && bar.getParent() instanceof ViewGroup; step++) {
            ViewGroup parent = (ViewGroup) bar.getParent();
            if (parent == root) break;
            bar = parent;
            if (bar.getWidth() >= contentWidth && bar.getWidth() > 0) break;
        }
        // A bar clear display set GONE keeps its last size and place, and that strip is where
        // TikTok draws its own clear display bar, whose X brings the tabs back. It stays out from
        // under the panel like the tabs do: covered, nothing on screen led anywhere but the
        // panel's own buttons.
        int height = bar.getHeight();
        if (height <= 0 || height >= root.getHeight() / 3) return 0;
        View ancestor = bar;
        while (ancestor != root && ancestor.getParent() instanceof View) {
            ancestor = (View) ancestor.getParent();
        }
        if (ancestor != root) return 0;

        root.getLocationOnScreen(ROOT_POSITION);
        bar.getLocationOnScreen(NAVIGATION_POSITION);
        int margin = ROOT_POSITION[1] + root.getHeight() - root.getPaddingBottom()
                - NAVIGATION_POSITION[1];
        return margin >= height && margin < root.getHeight() / 3 ? margin : 0;
    }

    /** A geometry change cannot request focus or change native playback. */
    private static void syncGeometry() {
        try {
            View overlay = overlayReference.get();
            ViewGroup root = geometryRootReference.get();
            Activity activity = Utils.getActivity();
            if (overlay == null || overlay.getVisibility() != View.VISIBLE || root == null
                    || parentOf(overlay) != root || activity == null
                    || activity.isFinishing() || activity.isDestroyed()
                    || activity.findViewById(android.R.id.content) != root) return;
            updateNavigationMargin(activity, root, overlay);
        } catch (Throwable error) {
            Logger.printException(() -> "Could not update the session hold geometry", error);
        }
    }

    private static void updateNavigationMargin(Activity activity, ViewGroup root, View overlay) {
        if (!(overlay.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) overlay.getLayoutParams();
        int margin = navigationHeight(activity, root);
        if (params.bottomMargin == margin) return;
        params.bottomMargin = margin;
        overlay.setLayoutParams(params);
    }

    private static void watchGeometry(ViewGroup root) {
        if (root == geometryRootReference.get()) return;
        stopWatchingGeometry();
        if (root == null) return;
        root.getViewTreeObserver().addOnGlobalLayoutListener(GEOMETRY);
        geometryRootReference = new WeakReference<>(root);
    }

    private static void stopWatchingGeometry() {
        ViewGroup root = geometryRootReference.get();
        if (root != null && root.getViewTreeObserver().isAlive()) {
            root.getViewTreeObserver().removeOnGlobalLayoutListener(GEOMETRY);
        }
        geometryRootReference = new WeakReference<>(null);
    }

    /**
     * Keeps the hold on the audio focus stack as well as pausing the native player. Focus also
     * gives release a signal that another app took control while the hold was visible.
     */
    @SuppressWarnings("deprecation")
    private static void requestQuiet() {
        if (quietened) return;
        AudioManager audio = audioManager();
        if (audio == null) return;
        try {
            // The request-object form arrived in API 26 and this runs from 23. The older call is
            // deprecated rather than gone, and it is the one both understand.
            int granted = audio.requestAudioFocus(QUIET, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            quietManager = audio;
            quietened = true;
            focusGranted = granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Exception refused) {
            Logger.printDebug(() -> "The hold could not take the audio focus");
        }
    }

    @SuppressWarnings("deprecation")
    private static void releaseQuiet() {
        AudioManager audio = quietManager;
        quietManager = null;
        quietened = false;
        focusGranted = false;
        if (audio == null) return;
        try {
            audio.abandonAudioFocus(QUIET);
        } catch (Exception ignored) {
            Logger.printDebug(() -> "The hold could not hand the audio focus back");
        }
    }

    private static AudioManager audioManager() {
        Context context = Utils.getContext();
        if (context != null && context.getApplicationContext() != null) {
            context = context.getApplicationContext();
        }
        return context == null ? null
                : (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    private static void detach() {
        stopWatchingGeometry();
        boolean mayResume = quietened && focusGranted;
        releaseQuiet();
        SessionPlaybackHold.release(mayResume);
        View overlay = overlayReference.get();
        overlayReference = new WeakReference<>(null);
        titleReference = new WeakReference<>(null);
        remainingReference = new WeakReference<>(null);
        releaseReference = new WeakReference<>(null);
        hintReference = new WeakReference<>(null);
        if (overlay == null) return;
        ViewGroup parent = parentOf(overlay);
        if (parent != null) {
            hideBehind(parent, overlay, false);
            parent.removeView(overlay);
        }
    }

    private static ViewGroup parentOf(View view) {
        if (view == null) return null;
        return view.getParent() instanceof ViewGroup ? (ViewGroup) view.getParent() : null;
    }

    /**
     * Takes everything behind the panel out of the reading order, or puts it back.
     *
     * <p>The panel covers the feed for anyone looking at it, and covers nothing at all for
     * anyone swiping through it with a screen reader. Each view's own setting is kept so that
     * putting it back does not hand TikTok a value this project invented. Shared with the
     * catcher {@code PausePlayback} puts up on returning to the app, which is the same shape:
     * one view over the content root that has to be the only thing on the page.
     */
    public static void hideBehind(ViewGroup root, View panel, boolean hidden) {
        if (root == null) return;
        for (int index = 0; index < root.getChildCount(); index++) {
            View child = root.getChildAt(index);
            if (child == panel) continue;
            if (hidden) {
                Curtained state = previousAccessibilityImportance.get(child);
                if (state == null) {
                    state = new Curtained(child.getImportantForAccessibility());
                    previousAccessibilityImportance.put(child, state);
                }
                // Per panel, not a flag: the hold panel and the tap catcher share this, and
                // the later one lifting must not hand the feed back while the earlier one is
                // up. A panel curtaining twice, as the hold does on a relayout, counts once.
                state.panels.add(panel);
                child.setImportantForAccessibility(
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            } else {
                Curtained state = previousAccessibilityImportance.get(child);
                if (state == null) continue;
                state.panels.remove(panel);
                if (!state.panels.isEmpty()) continue;
                previousAccessibilityImportance.remove(child);
                child.setImportantForAccessibility(state.previous);
            }
        }
    }
}
