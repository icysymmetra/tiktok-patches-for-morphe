/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.blockauthor;

import android.app.Activity;
import android.app.Application;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.feedfilter.SoundIdentity;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SystemBarInsets;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.notinterested.NotInterested;
import app.morphe.extension.tiktok.playback.FeedMute;
import app.morphe.extension.tiktok.wellbeing.SessionBudget;
import app.morphe.extension.tiktok.wellbeing.SessionLockOverlay;

import java.lang.ref.WeakReference;

/**
 * The block button that sits on the video player, plus the undo banner shown after a block.
 *
 * Everything is built in code. TikTok resources cannot be compiled by this patch set, so
 * the button is drawn rather than inflated, and it is attached to the activity content
 * root instead of TikTok's own action rail. That keeps it working across builds that
 * reshuffle the player view hierarchy.
 *
 * Long pressing any control enters drag mode so each one can be parked independently. Positions
 * are stored as fractions of the screen, so they survive rotation and a different device.
 */
public final class BlockAuthorOverlay {
    // 44 clears WCAG 2.5.5 and is under Android's own 48dp guidance, and these four have no
    // TouchDelegate to make up the difference. They sit in a column on the feed, where a
    // miss is a like or a follow on somebody's video.
    private static final int BUTTON_SIZE_DP = 48;
    private static final int BUTTON_GAP_DP = 8;
    private static final long UNDO_VISIBLE_MS = 6_000L;
    private static final long BLOCK_UNDO_VISIBLE_MS = 2_000L;

    /**
     * Left of TikTok's own action rail, level with the top of it.
     *
     * <p>At 0.91 the column of chips ran straight down the rail: on 46.2.3 at 1080 wide the
     * avatar sits at x 932 to 1057 and the hide chip landed on it, so a tap meant for the
     * creator's profile hid the creator instead. The rail starts at about 0.86 of the width, and
     * a chip is 48dp wide, so its centre has to sit under 0.80 to clear it; 0.74 leaves a
     * finger's width between. A position the reader has dragged to is honoured over this.
     */
    private static final float DEFAULT_X_FRACTION = 0.74f;
    private static final float DEFAULT_Y_FRACTION = 0.40f;

    private static WeakReference<View> buttonReference = new WeakReference<>(null);
    private static WeakReference<View> localHideReference = new WeakReference<>(null);
    private static WeakReference<View> soundButtonReference = new WeakReference<>(null);
    private static WeakReference<View> notInterestedReference = new WeakReference<>(null);
    private static WeakReference<View> muteReference = new WeakReference<>(null);

    /** Which banner a queued dismiss belongs to. Main thread only. */
    private static int undoGeneration;
    private static WeakReference<ViewGroup> rootReference = new WeakReference<>(null);
    private static WeakReference<Application> followed = new WeakReference<>(null);
    private static ViewTreeObserver.OnGlobalLayoutListener visibilityListener;
    private static WeakReference<View> undoReference = new WeakReference<>(null);

    /** Guards against a double tap blocking, then unblocking, the same account. */
    private static volatile boolean requestInFlight;

    /**
     * Ids for the move and reset actions a screen reader gets instead of the pointer drag.
     *
     * <p>Above {@code ACTION_TYPE_MASK}, so none of them can be read as one of the platform's
     * own action bits, and fixed rather than generated because a generated view id falls in the
     * range the legacy standard actions use.
     */
    private static final int ACTION_MOVE_UP = 0x0F0A0001;
    private static final int ACTION_MOVE_DOWN = 0x0F0A0002;
    private static final int ACTION_MOVE_LEFT = 0x0F0A0003;
    private static final int ACTION_MOVE_RIGHT = 0x0F0A0004;
    private static final int ACTION_RESET_POSITION = 0x0F0A0005;

    /** True while the user is dragging the button, which suppresses the click. */
    private static boolean dragging;
    private static WeakReference<View> pressedControlReference = new WeakReference<>(null);
    private static float dragOffsetX;
    private static float dragOffsetY;

    private BlockAuthorOverlay() {
    }

    /** Applies changed control settings without waiting for a different creator. */
    public static void refresh() {
        onAuthorChanged(CurrentVideoAuthor.get());
    }

    /** @param author the new current author, or null when the current item has none. */
    static void onAuthorChanged(VideoAuthor author) {
        boolean anyBlockControl = Settings.BLOCK_AUTHOR_BUTTON.get()
                || (SettingsStatus.feedFilterEnabled
                && (Settings.LOCAL_HIDE_BUTTON.get() || Settings.BLOCK_SOUND_BUTTON.get()));
        if (!anyBlockControl && !notInterestedEnabled() && !muteButtonEnabled()) {
            Utils.runOnMainThread(BlockAuthorOverlay::detach);
            return;
        }
        if (author == null) {
            Utils.runOnMainThread(BlockAuthorOverlay::syncVisibility);
            return;
        }
        Utils.runOnMainThread(() -> attach(author));
    }

    /**
     * Shows or hides the button as the feed comes and goes.
     *
     * The button is an overlay on the activity content root, so nothing removes it when
     * the user leaves the video feed. This is the seam that does it.
     */
    public static void setFeedVisible(boolean visible) {
        View button = buttonReference.get();
        if (button == null) {
            return;
        }
        // A control enabled from settings can be attached after a retained hold panel.
        visible = visible && !SessionBudget.isLocked();
        int wanted = visible && Settings.BLOCK_AUTHOR_BUTTON.get() ? View.VISIBLE : View.GONE;
        if (button.getVisibility() != wanted) {
            button.setVisibility(wanted);
            if (!visible) {
                dismissUndo();
            }
        }
        View soundButton = soundButtonReference.get();
        if (soundButton != null) {
            // The sound button needs a sound to act on, and a feed filter to act through.
            boolean soundWanted = visible && Settings.BLOCK_SOUND_BUTTON.get()
                    && SettingsStatus.feedFilterEnabled
                    && CurrentVideoSound.get() != null && CurrentVideoSound.get().isUsable();
            int soundVisibility = soundWanted ? View.VISIBLE : View.GONE;
            if (soundButton.getVisibility() != soundVisibility) {
                soundButton.setVisibility(soundVisibility);
            }
        }
        View localHide = localHideReference.get();
        if (localHide != null) {
            boolean localWanted = visible && Settings.LOCAL_HIDE_BUTTON.get()
                    && SettingsStatus.feedFilterEnabled;
            int localVisibility = localWanted ? View.VISIBLE : View.GONE;
            if (localHide.getVisibility() != localVisibility) {
                localHide.setVisibility(localVisibility);
            }
        }
        View feedback = notInterestedReference.get();
        if (feedback != null) feedback.setVisibility(visible && notInterestedEnabled() ? View.VISIBLE : View.GONE);
        View mute = muteReference.get();
        if (mute != null) {
            // Not over a story or a LIVE replay, which play with their sound whatever it says.
            VideoAuthor playing = CurrentVideoAuthor.get();
            boolean governed = FeedMute.appliesTo(playing == null ? null : playing.awemeId);
            mute.setVisibility(visible && muteButtonEnabled() && governed ? View.VISIBLE : View.GONE);
            showMuteState(mute);
        }
    }

    /**
     * Re-checks the cached feed selection and the active hold on each layout pass.
     */
    private static void syncVisibility() {
        Activity activity = controlsActivity();
        if (activity == null) {
            return;
        }
        // Clear display is the feed, but with TikTok's controls put away the chips go too.
        setFeedVisible(FeedVisibility.isOnFeed(activity)
                && !FeedVisibility.isFeedCleared(activity)
                && !FeedVisibility.isCommentSheetVisible(activity)
                && CurrentVideoAuthor.get() != null);
    }

    /**
     * The window the controls and their banners belong on: the feed, or the detail pager in front
     * of it, where a video opened from a profile, a hashtag or a sound plays. Any other screen in
     * front leaves them on the feed, where they have always been.
     */
    static Activity controlsActivity() {
        Activity visible = Utils.getVisibleActivity();
        return FeedVisibility.isFeedWindow(visible) ? visible : Utils.getActivity();
    }

    /**
     * A grid video by the creator already playing names no new author, so nothing else would move
     * the controls into the detail pager, or back when it closes. They follow the window instead.
     */
    private static void follow(Application application) {
        if (application == null || followed.get() == application) return;
        followed = new WeakReference<>(application);
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity resumed) {
                // Whether the controls are wanted, not whether a view is held: the pager's views
                // can be collected by the time the reader is back, and a weak handle that came
                // back empty left the feed without controls until the next swipe.
                if (!FeedVisibility.isFeedWindow(resumed)) return;
                boolean anyBlockControl = Settings.BLOCK_AUTHOR_BUTTON.get()
                        || (SettingsStatus.feedFilterEnabled
                        && (Settings.LOCAL_HIDE_BUTTON.get() || Settings.BLOCK_SOUND_BUTTON.get()));
                if (!anyBlockControl && !notInterestedEnabled() && !muteButtonEnabled()) return;
                VideoAuthor author = CurrentVideoAuthor.get();
                if (author != null) attach(author, resumed);
            }

            @Override public void onActivityCreated(Activity created, Bundle state) { }
            @Override public void onActivityStarted(Activity started) { }
            @Override public void onActivityPaused(Activity paused) { }
            @Override public void onActivityStopped(Activity stopped) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity destroyed) { }
        });
    }

    private static void attach(VideoAuthor author) {
        attach(author, controlsActivity());
    }

    private static void attach(VideoAuthor author, Activity activity) {
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                return;
            }

            ViewGroup root = activity.findViewById(android.R.id.content);
            if (root == null) {
                return;
            }

            View existing = buttonReference.get();
            if (existing != null && existing.getParent() == root) {
                syncVisibility();
                clampCurrentPositions(root);
                return;
            }
            // The controls are on another window: the feed behind a detail pager that just
            // opened, or a pager just closed. They move rather than leave a second set behind.
            if (existing != null) detach();

            final View button = createButton(activity);
            final int size = SettingsUi.dp(activity, BUTTON_SIZE_DP);
            // Absolute LEFT, not START. Every position here is a pixel worked out from a raw
            // touch and written to leftMargin, and a mirrored layout resolves START to RIGHT and
            // then reads rightMargin, which nothing sets: the saved position was discarded, a
            // drag moved nothing sideways, and the Not interested button landed on top of the
            // block button because the two differ only in leftMargin.
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    size, size, Gravity.TOP | Gravity.LEFT);
            button.setLayoutParams(params);

            root.addView(button);
            buttonReference = new WeakReference<>(button);

            final View localHide = createLocalHideButton(activity);
            localHide.setLayoutParams(new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.LEFT));
            root.addView(localHide);
            localHideReference = new WeakReference<>(localHide);

            final View soundButton = createSoundButton(activity);
            soundButton.setLayoutParams(new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.LEFT));
            root.addView(soundButton);
            soundButtonReference = new WeakReference<>(soundButton);
            View feedback = createNotInterestedButton(activity);
            feedback.setLayoutParams(new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.LEFT));
            root.addView(feedback);
            notInterestedReference = new WeakReference<>(feedback);
            View mute = createMuteButton(activity);
            mute.setLayoutParams(new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.LEFT));
            root.addView(mute);
            muteReference = new WeakReference<>(mute);

            // The root has no measured size until it lays out, so the saved fraction can
            // only be turned into margins once dimensions are known.
            root.post(() -> applyPositions(root));

            installVisibilityListener(root);
            follow(activity.getApplication());
            syncVisibility();

            Logger.printDebug(() -> "Block button attached for " + author.reference());
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not attach the block button", ex);
        }
    }

    /**
     * Puts every control where its saved fraction says, and the ones with nothing saved where
     * they sit relative to the block button. Runs once per attach, after the root has a size.
     */
    private static void applyPositions(ViewGroup root) {
        View button = buttonReference.get();
        View localHide = localHideReference.get();
        View soundButton = soundButtonReference.get();
        View feedback = notInterestedReference.get();
        View mute = muteReference.get();
        if (button == null || localHide == null || soundButton == null || feedback == null || mute == null) return;
        // A second attach can swap these references to another activity's controls before this
        // runs, since it is posted. Positioning those against this root would read the wrong
        // width and cast layout params off a view that is not a child of it.
        if (button.getParent() != root) return;
        if (root.getWidth() == 0 || root.getHeight() == 0) return;

        int size = SettingsUi.dp(root.getContext(), BUTTON_SIZE_DP);
        int step = size + SettingsUi.dp(root.getContext(), BUTTON_GAP_DP);
        // The block button first: the other three default to positions relative to wherever it
        // ended up, so it has to be off its own saved fraction before they are worked out.
        applySavedPosition(button, root, size, Settings.BLOCK_AUTHOR_BUTTON_POSITION,
                DEFAULT_X_FRACTION, DEFAULT_Y_FRACTION);
        for (View view : new View[]{localHide, soundButton, feedback, mute}) {
            float[] fractions = defaultFractions(view, root, button, size, step);
            applySavedPosition(view, root, size, positionSetting(view), fractions[0], fractions[1]);
        }
    }

    /**
     * Where one control sits when nothing has been saved for it.
     *
     * <p>The block button has a fixed corner of the screen. The other three are placed around
     * wherever the block button actually is, turning away from the edge it is nearest so a
     * column of four does not run off the bottom or a pair overlap on the right.
     */
    private static float[] defaultFractions(
            View view, ViewGroup root, View button, int size, int step) {
        if (view == button) {
            return new float[]{DEFAULT_X_FRACTION, DEFAULT_Y_FRACTION};
        }

        FrameLayout.LayoutParams blockPosition = (FrameLayout.LayoutParams) button.getLayoutParams();
        float blockX = blockPosition.leftMargin + size / 2f;
        float blockY = blockPosition.topMargin + size / 2f;
        int maxTop = Math.max(0, root.getHeight() - size);
        float verticalDirection = maxTop - blockPosition.topMargin >= step * 2 ? 1f : -1f;

        if (view == localHideReference.get()) {
            return new float[]{blockX / root.getWidth(),
                    (blockY + step * verticalDirection) / root.getHeight()};
        }
        if (view == soundButtonReference.get()) {
            return new float[]{blockX / root.getWidth(),
                    (blockY + step * 2f * verticalDirection) / root.getHeight()};
        }
        float horizontalDirection = blockPosition.leftMargin >= step ? -1f : 1f;
        if (view == muteReference.get()) {
            // Beside the local-hide control, level with it, the corner the other three leave free.
            return new float[]{(blockX + step * horizontalDirection) / root.getWidth(),
                    (blockY + step * verticalDirection) / root.getHeight()};
        }
        return new float[]{(blockX + step * horizontalDirection) / root.getWidth(),
                blockY / root.getHeight()};
    }

    private static void installVisibilityListener(ViewGroup root) {
        if (visibilityListener != null && rootReference.get() == root) {
            return;
        }
        removeVisibilityListener();

        // The listener sits in a static field, so it reads the root through the weak reference
        // rather than capturing it: a captured root kept the finished activity's whole view tree
        // alive until the next install, whatever the weak reference beside it said.
        rootReference = new WeakReference<>(root);
        visibilityListener = () -> {
            syncVisibility();
            ViewGroup current = rootReference.get();
            if (current != null) clampCurrentPositions(current);
        };
        root.getViewTreeObserver().addOnGlobalLayoutListener(visibilityListener);
    }

    /** Re-applies only the safe-area bounds, without moving a control back during a drag. */
    private static void clampCurrentPositions(ViewGroup root) {
        if (root.getWidth() == 0 || root.getHeight() == 0) return;
        for (View view : new View[]{buttonReference.get(), localHideReference.get(),
                soundButtonReference.get(), notInterestedReference.get(), muteReference.get()}) {
            if (view == null || view.getParent() != root) continue;
            ViewGroup.MarginLayoutParams params =
                    (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            moveTo(view, root, params.leftMargin, params.topMargin);
        }
    }

    private static void removeVisibilityListener() {
        ViewGroup root = rootReference.get();
        if (root != null && visibilityListener != null) {
            root.getViewTreeObserver().removeOnGlobalLayoutListener(visibilityListener);
        }
        visibilityListener = null;
        rootReference = new WeakReference<>(null);
    }

    private static void detach() {
        removeVisibilityListener();
        resetDragState();
        View button = buttonReference.get();
        if (button != null && button.getParent() instanceof ViewGroup) {
            ((ViewGroup) button.getParent()).removeView(button);
        }
        buttonReference = new WeakReference<>(null);
        View soundButton = soundButtonReference.get();
        if (soundButton != null && soundButton.getParent() instanceof ViewGroup) {
            ((ViewGroup) soundButton.getParent()).removeView(soundButton);
        }
        soundButtonReference = new WeakReference<>(null);
        View localHide = localHideReference.get();
        if (localHide != null && localHide.getParent() instanceof ViewGroup) {
            ((ViewGroup) localHide.getParent()).removeView(localHide);
        }
        localHideReference = new WeakReference<>(null);
        View feedback = notInterestedReference.get();
        if (feedback != null && feedback.getParent() instanceof ViewGroup) {
            ((ViewGroup) feedback.getParent()).removeView(feedback);
        }
        notInterestedReference = new WeakReference<>(null);
        View mute = muteReference.get();
        if (mute != null && mute.getParent() instanceof ViewGroup) {
            ((ViewGroup) mute.getParent()).removeView(mute);
        }
        muteReference = new WeakReference<>(null);
        dismissUndo();
    }

    private static View createSoundButton(Activity activity) {
        // All four controls are drawn the same way now: one OverlayGlyphDrawable at the same
        // stroke weight and radius fraction, over the same scrim. They were three fonts and one
        // drawing, and the font TikTok picks is not one any of them can rely on.
        TextView button = new TextView(activity);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(L10n.t(activity, "Block this sound"));
        button.setFocusable(true);
        Drawable glyph = new OverlayGlyphDrawable(OverlayGlyphDrawable.Shape.NOTE,
                SettingsUi.OVERLAY_TEXT, SettingsUi.dp(activity, 2));
        button.setBackground(SettingsUi.overlayControl(activity, SettingsUi.RADIUS_OVERLAY, glyph));
        button.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            onBlockSoundTapped();
        });
        installDrag(button);
        return button;
    }

    private static View createLocalHideButton(Activity activity) {
        TextView button = new TextView(activity);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(L10n.t(activity, "Hide this creator on this phone"));
        button.setFocusable(true);
        Drawable glyph = new OverlayGlyphDrawable(OverlayGlyphDrawable.Shape.CROSS,
                SettingsUi.OVERLAY_TEXT, SettingsUi.dp(activity, 2));
        button.setBackground(SettingsUi.overlayControl(activity, SettingsUi.RADIUS_OVERLAY, glyph));
        button.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            onLocalHideTapped();
        });
        installDrag(button);
        return button;
    }

    private static boolean notInterestedEnabled() {
        return SettingsStatus.notInterestedEnabled && Settings.NOT_INTERESTED_BUTTON.get();
    }

    private static View createNotInterestedButton(Activity activity) {
        TextView button = new TextView(activity);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(L10n.t(activity, "Not interested in this video"));
        button.setFocusable(true);
        Drawable glyph = new OverlayGlyphDrawable(OverlayGlyphDrawable.Shape.MINUS,
                SettingsUi.OVERLAY_TEXT, SettingsUi.dp(activity, 2));
        button.setBackground(SettingsUi.overlayControl(activity, SettingsUi.RADIUS_OVERLAY, glyph));
        button.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            NotInterested.submit();
        });
        installDrag(button);
        return button;
    }

    private static boolean muteButtonEnabled() {
        return SettingsStatus.feedMuteEnabled && Settings.FEED_MUTE_BUTTON.get();
    }

    /**
     * Mute feed videos. The name stays "Mute feed videos" whichever way it is set, since a
     * reader finds it by name; the state is read out as checked, and as Muted or Sound on.
     */
    private static View createMuteButton(Activity activity) {
        TextView button = new TextView(activity);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(L10n.t(activity, "Mute feed videos"));
        button.setFocusable(true);
        showMuteState(button);
        button.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            FeedMute.setMuted(!FeedMute.isMuted());
            showMuteState(view);
        });
        installDrag(button, true);
        return button;
    }

    /** The glyph and the read-out state, redrawn only when the mute changed. */
    private static void showMuteState(View button) {
        boolean muted = FeedMute.isMuted();
        if (Boolean.valueOf(muted).equals(button.getTag())) return;
        button.setTag(muted);
        Drawable glyph = new OverlayGlyphDrawable(
                muted ? OverlayGlyphDrawable.Shape.SPEAKER_OFF : OverlayGlyphDrawable.Shape.SPEAKER,
                SettingsUi.OVERLAY_TEXT, SettingsUi.dp(button.getContext(), 2));
        button.setBackground(SettingsUi.overlayControl(button.getContext(), SettingsUi.RADIUS_OVERLAY, glyph));
        button.setSelected(muted);
        if (Build.VERSION.SDK_INT >= 30) {
            button.setStateDescription(L10n.t(button.getContext(), muted ? "Muted" : "Sound on"));
        }
    }

    /**
     * Records the current sound so the feed filter skips every video that uses it. Ids
     * are exact; a sound with no id is recorded by name, which also catches re-uploads.
     */
    private static void onBlockSoundTapped() {
        CurrentVideoSound sound = CurrentVideoSound.get();
        if (sound == null || !sound.isUsable()) {
            Utils.showToastShort(L10n.t("No sound to block on this video"));
            return;
        }

        final boolean byId = sound.id != null && !sound.id.isEmpty();
        StringSetting setting = byId ? Settings.BLOCKED_SOUND_IDS : Settings.BLOCKED_SOUND_NAMES;
        String identity = byId ? sound.id : sound.name;
        if (!saveAction(setting, SoundIdentity.withEntry(setting.get(), identity))) return;
        // The sound's author is a creator, so the line says how the sound was recorded, not which.
        Logger.printDebug(() -> "Blocked sound " + (byId ? "by id" : "by name"));

        showUndoBanner(L10n.f("Skipping videos with %1$s", sound.label()), () -> {
            if (saveAction(setting, SoundIdentity.withoutEntry(setting.get(), identity))) {
                Utils.showToastShort(L10n.f("Unblocked %1$s", sound.label()));
            }
        });
    }

    /** A local action may claim success only after its preference commit succeeded. */
    static <T> boolean saveAction(Setting<T> setting, T value) {
        if (setting.save(value)) return true;
        Utils.showToastLong(L10n.t("This change couldn't be saved. Try again."));
        return false;
    }

    private static View createButton(Activity activity) {
        TextView button = new TextView(activity);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(L10n.t(activity, "Block this creator"));
        button.setFocusable(true);

        Drawable glyph = new OverlayGlyphDrawable(OverlayGlyphDrawable.Shape.BLOCK,
                SettingsUi.OVERLAY_TEXT, SettingsUi.dp(activity, 2));
        button.setBackground(SettingsUi.overlayControl(activity, SettingsUi.RADIUS_OVERLAY, glyph));

        button.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            onBlockTapped();
        });
        installDrag(button);
        return button;
    }

    /** Gives every feed control the same independent long-press drag behavior. */
    private static void installDrag(View button) {
        installDrag(button, false);
    }

    /** @param toggle the control is a switch, read out as checked while the feed is muted */
    private static void installDrag(View button, boolean toggle) {
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(
                    View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Button.class.getName());
                if (toggle) {
                    info.setCheckable(true);
                    info.setChecked(FeedMute.isMuted());
                }
                // Pointer drag has a release event. A standalone accessibility long-click does
                // not, so do not advertise an action that cannot complete the gesture.
                info.setLongClickable(false);
                info.removeAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
                        .ACTION_LONG_CLICK);
                // What the refused long-press owed a reader: a way to park the control, and a
                // way back if it ends up somewhere useless.
                info.addAction(moveAction(host, ACTION_MOVE_UP, "Move up"));
                info.addAction(moveAction(host, ACTION_MOVE_DOWN, "Move down"));
                info.addAction(moveAction(host, ACTION_MOVE_LEFT, "Move left"));
                info.addAction(moveAction(host, ACTION_MOVE_RIGHT, "Move right"));
                info.addAction(moveAction(host, ACTION_RESET_POSITION, "Reset position"));
            }

            @Override public boolean performAccessibilityAction(View host, int action,
                    android.os.Bundle arguments) {
                if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK) {
                    return false;
                }
                if (action == ACTION_MOVE_UP) return nudge(host, 0, -1);
                if (action == ACTION_MOVE_DOWN) return nudge(host, 0, 1);
                if (action == ACTION_MOVE_LEFT) return nudge(host, -1, 0);
                if (action == ACTION_MOVE_RIGHT) return nudge(host, 1, 0);
                if (action == ACTION_RESET_POSITION) return resetPosition(host);
                return super.performAccessibilityAction(host, action, arguments);
            }
        });
        button.setOnLongClickListener(view -> {
            // Accessibility ACTION_LONG_CLICK has no pointer down or release. Entering raw drag
            // mode from it left every later click stuck behind a gesture that could never finish.
            if (pressedControlReference.get() != view) return false;
            dragging = true;
            view.setAlpha(0.75f);
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (positionSetting(view).get().isEmpty()) {
                Utils.showToastShort(L10n.t("Drag to move, release to place"));
            }
            return true;
        });

        button.setOnTouchListener(BlockAuthorOverlay::onButtonTouch);
    }

    private static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction moveAction(
            View host, int id, String label) {
        return new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                id, L10n.t(host.getContext(), label));
    }

    /**
     * One step of the control's own width, in the direction the action names. Clamped by
     * {@link #moveTo}, so an action at an edge is still performed and simply stays put.
     *
     * @param stepsX -1 for left, 1 for right, 0 for neither
     * @param stepsY -1 for up, 1 for down, 0 for neither
     */
    private static boolean nudge(View view, int stepsX, int stepsY) {
        ViewGroup parent = view.getParent() instanceof ViewGroup
                ? (ViewGroup) view.getParent()
                : null;
        if (parent == null || parent.getWidth() == 0 || parent.getHeight() == 0) return false;

        int step = SettingsUi.dp(view.getContext(), BUTTON_SIZE_DP);
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        moveTo(view, parent, params.leftMargin + stepsX * step, params.topMargin + stepsY * step);
        savePosition(view, parent);
        return true;
    }

    /**
     * Gives one control its default position back and moves nothing else.
     *
     * <p>Only this control is placed. Re-running the whole positioning pass would look
     * equivalent and is not: the other three default to positions relative to the block button,
     * so a pass would drag every control with nothing saved to wherever the block button has
     * since been moved, from a reset performed on a different control.
     */
    private static boolean resetPosition(View view) {
        ViewGroup parent = view.getParent() instanceof ViewGroup
                ? (ViewGroup) view.getParent()
                : null;
        View button = buttonReference.get();
        if (parent == null || button == null) return false;
        if (parent.getWidth() == 0 || parent.getHeight() == 0) return false;

        StringSetting setting = positionSetting(view);
        if (!saveAction(setting, setting.defaultValue)) return false;
        int size = SettingsUi.dp(parent.getContext(), BUTTON_SIZE_DP);
        int step = size + SettingsUi.dp(parent.getContext(), BUTTON_GAP_DP);
        float[] fractions = defaultFractions(view, parent, button, size, step);
        applySavedPosition(view, parent, size, setting, fractions[0], fractions[1]);
        return true;
    }

    /**
     * Handles dragging. Returns false unless a drag is in progress so that normal click
     * and long press handling is left alone.
     */
    private static boolean onButtonTouch(View view, MotionEvent event) {
        if (!dragging) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                pressedControlReference = new WeakReference<>(view);
                dragOffsetX = event.getX();
                dragOffsetY = event.getY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                pressedControlReference = new WeakReference<>(null);
            }
            return false;
        }

        ViewGroup parent = view.getParent() instanceof ViewGroup
                ? (ViewGroup) view.getParent()
                : null;
        if (parent == null) {
            resetDragState();
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float left = event.getRawX() - dragOffsetX - parentLeft(parent);
                float top = event.getRawY() - dragOffsetY - parentTop(parent);
                moveTo(view, parent, left, top);
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                savePosition(view, parent);
                resetDragState();
                return true;
            }

            default:
                return true;
        }
    }

    private static void resetDragState() {
        View pressed = pressedControlReference.get();
        if (pressed != null) pressed.setAlpha(1f);
        dragging = false;
        pressedControlReference = new WeakReference<>(null);
    }

    private static int parentLeft(ViewGroup parent) {
        int[] location = new int[2];
        parent.getLocationOnScreen(location);
        return location[0];
    }

    private static int parentTop(ViewGroup parent) {
        int[] location = new int[2];
        parent.getLocationOnScreen(location);
        return location[1];
    }

    /** Moves the button, keeping it clear of screen edges, cutouts and TikTok's tab bar. */
    private static void moveTo(View view, ViewGroup parent, float left, float top) {
        // Before the first layout the view has no size, so fall back to the size it was
        // given, or the clamp would let it sit partly off the right and bottom edges.
        ViewGroup.LayoutParams layout = view.getLayoutParams();
        int width = view.getWidth() > 0 ? view.getWidth() : layout.width;
        int height = view.getHeight() > 0 ? view.getHeight() : layout.height;
        Rect systemInsets = SystemBarInsets.current(parent);
        int minimumLeft = Math.max(0, systemInsets.left);
        int minimumTop = Math.max(0, systemInsets.top);
        int reservedBottom = Math.max(0, systemInsets.bottom);
        Activity activity = controlsActivity();
        if (activity != null) {
            reservedBottom = Math.max(reservedBottom,
                    SessionLockOverlay.navigationHeight(activity, parent));
        }
        int maxLeft = Math.max(minimumLeft,
                parent.getWidth() - Math.max(0, systemInsets.right) - width);
        int maxTop = Math.max(minimumTop,
                parent.getHeight() - reservedBottom - height);

        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        int nextLeft = Math.round(Math.min(Math.max(left, minimumLeft), maxLeft));
        int nextTop = Math.round(Math.min(Math.max(top, minimumTop), maxTop));
        if (params.leftMargin == nextLeft && params.topMargin == nextTop) return;
        params.leftMargin = nextLeft;
        params.topMargin = nextTop;
        view.setLayoutParams(params);
    }

    private static void applySavedPosition(
            View view,
            ViewGroup parent,
            int size,
            StringSetting setting,
            float defaultX,
            float defaultY
    ) {
        if (parent.getWidth() == 0 || parent.getHeight() == 0) {
            return;
        }

        float[] fractions = loadPositionFractions(setting, defaultX, defaultY);
        float left = fractions[0] * parent.getWidth() - size / 2f;
        float top = fractions[1] * parent.getHeight() - size / 2f;
        moveTo(view, parent, left, top);
    }

    /** @return the stored centre position as {x, y} fractions of the parent. */
    private static float[] loadPositionFractions(
            StringSetting setting,
            float defaultX,
            float defaultY
    ) {
        String stored = setting.get();
        if (stored != null && !stored.isEmpty()) {
            String[] parts = stored.split(",");
            if (parts.length == 2) {
                try {
                    float x = Float.parseFloat(parts[0].trim());
                    float y = Float.parseFloat(parts[1].trim());
                    if (x >= 0f && x <= 1f && y >= 0f && y <= 1f) {
                        return new float[]{x, y};
                    }
                } catch (NumberFormatException ignored) {
                    // Fall through to the default.
                }
            }
        }
        return new float[]{clampFraction(defaultX), clampFraction(defaultY)};
    }

    private static void savePosition(View view, ViewGroup parent) {
        if (parent.getWidth() == 0 || parent.getHeight() == 0) {
            return;
        }

        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        // The same fallback moveTo uses. A control with nothing measured reports zero, and the
        // centre would then be written down half a button off the position it was placed at,
        // so reading the fraction back would move it.
        float width = view.getWidth() > 0 ? view.getWidth() : params.width;
        float height = view.getHeight() > 0 ? view.getHeight() : params.height;
        float x = (params.leftMargin + width / 2f) / parent.getWidth();
        float y = (params.topMargin + height / 2f) / parent.getHeight();

        StringSetting setting = positionSetting(view);
        String position = round(x) + "," + round(y);
        if (saveAction(setting, position)) {
            Logger.printDebug(() -> String.valueOf(view.getContentDescription()) + " moved to " + position);
            return;
        }

        View button = buttonReference.get();
        if (button == null) return;
        int size = SettingsUi.dp(parent.getContext(), BUTTON_SIZE_DP);
        int step = size + SettingsUi.dp(parent.getContext(), BUTTON_GAP_DP);
        float[] defaults = defaultFractions(view, parent, button, size, step);
        applySavedPosition(view, parent, size, setting, defaults[0], defaults[1]);
    }

    private static StringSetting positionSetting(View view) {
        if (view == localHideReference.get()) return Settings.LOCAL_HIDE_BUTTON_POSITION;
        if (view == soundButtonReference.get()) return Settings.BLOCK_SOUND_BUTTON_POSITION;
        if (view == notInterestedReference.get()) return Settings.NOT_INTERESTED_BUTTON_POSITION;
        if (view == muteReference.get()) return Settings.FEED_MUTE_BUTTON_POSITION;
        return Settings.BLOCK_AUTHOR_BUTTON_POSITION;
    }

    private static float clampFraction(float value) {
        return Math.min(Math.max(value, 0f), 1f);
    }

    private static String round(float value) {
        return String.valueOf(Math.round(value * 1000f) / 1000f);
    }

    private static void onBlockTapped() {
        if (requestInFlight) {
            return;
        }

        VideoAuthor author = CurrentVideoAuthor.get();
        if (author == null || !author.isUsable()) {
            Utils.showToastShort(L10n.t("No creator to block on this video"));
            return;
        }

        requestInFlight = true;
        setButtonEnabled(false);
        dismissUndo();
        BlockFeedAdvance advance = BlockFeedAdvance.capture(author);

        BlockAuthorService.block(author, result -> {
            requestInFlight = false;
            setButtonEnabled(true);
            if (result == BlockAuthorService.Result.CONFIRMED && advance != null) advance.advance();
            reportBlockResult(author, result);
        });
    }

    static void reportBlockResult(VideoAuthor author, BlockAuthorService.Result result) {
        if (result == BlockAuthorService.Result.CONFIRMED) {
            showUndo(author);
            return;
        }
        Utils.showToastLong(BlockAuthorMessages.blockFailure(
                Utils.getContext(), result, author.label()));
    }

    private static void onLocalHideTapped() {
        VideoAuthor author = CurrentVideoAuthor.get();
        if (author == null || !author.isUsable() || author.stableId() == null
                || author.stableId().isEmpty()) {
            Utils.showToastShort(L10n.t("No creator to hide on this video"));
            return;
        }

        String before = Settings.LOCAL_HIDDEN_CREATORS.get();
        String after = app.morphe.extension.tiktok.feedfilter.AdvancedFeedRules.addCreatorEntry(
                before, author.stableId());
        if (after.equals(before)) {
            Utils.showToastShort(L10n.f("%1$s is already hidden", author.label()));
            return;
        }
        String problem = app.morphe.extension.tiktok.feedfilter.FeedRuleLimits.creatorProblem(after);
        if (problem != null) {
            Utils.showToastLong(problem);
            return;
        }
        if (!saveAction(Settings.LOCAL_HIDDEN_CREATORS, after)) return;
        showUndoBanner(L10n.f("Hidden %1$s on this phone", author.label()), () -> {
            if (saveAction(Settings.LOCAL_HIDDEN_CREATORS, before)) {
                Utils.showToastShort(L10n.f("Showing %1$s again", author.label()));
            }
        });
    }

    private static void setButtonEnabled(boolean enabled) {
        View button = buttonReference.get();
        if (button == null) return;
        SettingsUi.setBusy(button, !enabled, L10n.t(button.getContext(), "Blocking"));
        // The glyph loses contrast, the scrim behind it does not. Fading the whole chip took
        // the backdrop's alpha down with it and left a smudge over a bright frame.
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(
                    enabled ? SettingsUi.OVERLAY_TEXT : SettingsUi.OVERLAY_TEXT_MUTED);
        }
    }

    /**
     * Blocking is immediate and has no confirmation, so the undo banner is the safety net
     * for a mis-tap while scrolling.
     */
    private static void showUndo(VideoAuthor author) {
        Activity activity = controlsActivity();
        ViewGroup root = activity == null || activity.isFinishing() || activity.isDestroyed()
                ? null : activity.findViewById(android.R.id.content);
        if (root == null || !activity.hasWindowFocus()) {
            Utils.showToastShort(L10n.f("Blocked %1$s", author.label()));
            return;
        }
        dismissUndo();
        TextView chip = new TextView(activity);
        chip.setText(L10n.t(activity, "Unblock"));
        chip.setContentDescription(L10n.f(activity, "Blocked %1$s. Unblock", author.label()));
        chip.setTextColor(SettingsUi.OVERLAY_TEXT);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY_SMALL);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(SettingsUi.dp(activity, 12), 0, SettingsUi.dp(activity, 12), 0);
        chip.setMinimumHeight(SettingsUi.dp(activity, 48));
        chip.setMinimumWidth(SettingsUi.dp(activity, 48));
        chip.setBackground(new LayerDrawable(new Drawable[]{SettingsUi.overlayBanner(activity),
                SettingsUi.overlayAction(activity, SettingsUi.RADIUS_OVERLAY)}));
        chip.setFocusable(true);
        chip.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        SettingsUi.markAsButton(chip);
        chip.setOnClickListener(view -> {
            dismissUndo();
            requestInFlight = true;
            setButtonEnabled(false);
            BlockAuthorService.unblock(author, result -> {
                requestInFlight = false;
                setButtonEnabled(true);
                reportUnblockResult(author, result);
            });
        });
        Rect bars = SystemBarInsets.current(root);
        int[] origin = new int[2];
        root.getLocationOnScreen(origin);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.LEFT);
        params.leftMargin = Math.max(0, bars.left - origin[0]) + SettingsUi.dp(activity, 12);
        params.topMargin = Math.max(0, bars.top - origin[1]) + SettingsUi.dp(activity, 8);
        root.addView(chip, params);
        undoReference = new WeakReference<>(chip);
        final int token = ++undoGeneration;
        // No entrance/exit animation extends the requested two-second lifetime.
        Utils.runOnMainThreadDelayed(() -> {
            if (token == undoGeneration) dismissUndo();
        }, SettingsUi.feedbackTimeout(activity, (int) BLOCK_UNDO_VISIBLE_MS, true));
    }

    static void reportUnblockResult(VideoAuthor author, BlockAuthorService.Result result) {
        Utils.showToastShort(BlockAuthorMessages.unblockResult(
                Utils.getContext(), result, author.label()));
    }

    /**
     * Shows a message with an UNDO action for six seconds, over whatever activity is on
     * screen. Falls back to a plain toast when there is nowhere to draw it.
     */
    public static void showUndoBanner(String message, Runnable undoAction) {
        Utils.runOnMainThread(() -> {
            Activity activity = controlsActivity();
            ViewGroup root = activity == null || activity.isFinishing() || activity.isDestroyed()
                    ? null : activity.findViewById(android.R.id.content);
            showUndoBanner(root, message, undoAction);
        });
    }

    /**
     * Shows a message with an UNDO action for six seconds inside {@code root}, which
     * should be the window the user is looking at. A banner added to the activity's
     * content root is invisible under a panel that has its own window, which is why the
     * root is a parameter. Falls back to a plain toast when there is nowhere to draw it.
     */
    public static void showUndoBanner(ViewGroup root, String message, Runnable undoAction) {
        showBanner(root, message, undoAction, null, null);
    }

    /**
     * The same banner with nothing to press, for anything that only has something to say.
     *
     * <p>Everything worth having is in the shape rather than in the Undo: it takes no focus,
     * takes itself away after six seconds, and announces itself once as a polite live region,
     * which a toast does not.
     */
    public static void showNoticeBanner(ViewGroup root, String message) {
        showBanner(root, message, null, null, null);
    }

    /**
     * The notice banner, telling the caller when it was read out in full.
     *
     * <p>{@code ranItsTime} runs on the main thread once the banner has been up for its whole
     * time and is still on the screen, or at once when there was only a toast to show. Another
     * banner replacing it, the controls going away, or the reader leaving the screen cuts it
     * short, and then it never runs: a caller that only forgets what it said once it was seen
     * can say it again later.
     */
    public static void showNoticeBanner(ViewGroup root, String message, Runnable ranItsTime) {
        showBanner(root, message, null, null, ranItsTime);
    }

    /**
     * The same banner with an action of your own instead of Undo.
     *
     * <p>Undo was the only thing a banner could offer, so anything with a different action had
     * to be a dialog, and a dialog over the feed stops a scroll dead for something whose only
     * purpose is to be read.
     */
    public static void showActionBanner(String message, String actionLabel, Runnable action) {
        Utils.runOnMainThread(() -> {
            Activity activity = controlsActivity();
            ViewGroup root = activity == null || activity.isFinishing() || activity.isDestroyed()
                    ? null : activity.findViewById(android.R.id.content);
            showBanner(root, message, action, actionLabel, null);
        });
    }

    public static void showActionBanner(ViewGroup root, String message, String actionLabel,
            Runnable action) {
        showBanner(root, message, action, actionLabel, null);
    }

    /** The action banner with a second action after the first, as a save that half landed has. */
    public static void showActionBanner(ViewGroup root, String message, String actionLabel,
            Runnable action, String secondLabel, Runnable second) {
        showBanner(root, message, action, actionLabel, second, secondLabel, null);
    }

    private static void showBanner(ViewGroup root, String message, Runnable action,
            String actionLabel, Runnable ranItsTime) {
        showBanner(root, message, action, actionLabel, null, null, ranItsTime);
    }

    private static void showBanner(ViewGroup root, String message, Runnable action,
            String actionLabel, Runnable second, String secondLabel, Runnable ranItsTime) {
        Utils.runOnMainThread(() -> {
            try {
                if (root == null) {
                    toastInstead(message, ranItsTime);
                    return;
                }
                Activity activity = controlsActivity();
                if (activity == null) {
                    toastInstead(message, ranItsTime);
                    return;
                }

                dismissUndo();

                LinearLayout banner = new LinearLayout(activity);
                banner.setOrientation(LinearLayout.HORIZONTAL);
                banner.setGravity(Gravity.CENTER_VERTICAL);
                banner.setPadding(SettingsUi.dp(activity, 16), SettingsUi.dp(activity, 12), SettingsUi.dp(activity, 16), SettingsUi.dp(activity, 12));

                banner.setBackground(SettingsUi.overlayBanner(activity));

                TextView label = new TextView(activity);
                label.setText(message);
                label.setTextColor(SettingsUi.OVERLAY_TEXT);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY_SMALL);
                banner.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

                if (action != null) {
                    addAction(activity, banner,
                            actionLabel == null ? L10n.t(activity, "Undo") : actionLabel, action);
                }
                if (second != null) addAction(activity, banner, secondLabel, second);
                // Nothing announced this banner, so a reader using TalkBack never knew there
                // was a way back at all.
                banner.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

                banner.setLayoutParams(bannerParams(activity, root));

                root.addView(banner);
                undoReference = new WeakReference<>(banner);

                // A second banner inside the six seconds replaced the first, and the first
                // banner's dismiss was still queued: it took the new banner away early, with
                // its Undo. Nothing here can cancel a posted runnable, so each dismiss checks
                // whether it is still the one that was scheduled.
                final int token = ++undoGeneration;
                Utils.runOnMainThreadDelayed(() -> {
                    if (token != undoGeneration) return;
                    // Only a banner still on the screen at the end was read out in full. The
                    // reader backing out or going Home stops nothing here, so the timeout comes
                    // anyway, over a screen that is gone or stopped.
                    boolean seen = banner.isAttachedToWindow() && banner.isShown();
                    dismissUndo();
                    if (seen && ranItsTime != null) ranItsTime.run();
                }, SettingsUi.feedbackTimeout(activity, (int) UNDO_VISIBLE_MS, action != null || second != null));
            } catch (Throwable ex) {
                Logger.printException(() -> "Could not show the undo banner", ex);
                toastInstead(message, ranItsTime);
            }
        });
    }

    /** A toast can't be replaced by the next banner, so it has run its time once it's shown. */
    private static void toastInstead(String message, Runnable ranItsTime) {
        Utils.showToastShort(message);
        if (ranItsTime != null) ranItsTime.run();
    }

    /** Where the banner sat before it measured anything: a fixed 96dp up from the bottom. */
    private static final int BANNER_FALLBACK_BOTTOM_DP = 96;
    private static final int BANNER_GAP_DP = 16;

    /**
     * Where the banner goes, decided by what is under it.
     *
     * <p>Over the feed it sits a gap above TikTok's tab bar, measured the way the hold panel
     * measures it, so it never covers the tabs or the caption; a build the bar cannot be found
     * on gets the old fixed offset. In a sheet, which is any root that is not the activity's
     * own window, it sits a gap above the lowest text field, which is the comments sheet's
     * input row, and near the bottom when there is no field to clear. Every window decor is a
     * FrameLayout, so gravity params work in any root.
     */
    public static FrameLayout.LayoutParams bannerParams(Activity activity, ViewGroup root) {
        int side = SettingsUi.dp(activity, BANNER_GAP_DP);
        int gap = side;
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        boolean feed = decor != null && root.getRootView() == decor;
        if (feed) {
            int navigation = SessionLockOverlay.navigationHeight(activity, root);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            params.setMargins(side, 0, side, navigation > 0
                    ? navigation + gap : SettingsUi.dp(activity, BANNER_FALLBACK_BOTTOM_DP));
            return params;
        }
        View input = lowestShownEditText(root);
        if (input != null && root.getHeight() > 0) {
            int above = root.getHeight() - root.getPaddingBottom() - topWithin(input, root);
            if (above > 0 && above < root.getHeight()) {
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                params.setMargins(side, 0, side, above + gap);
                return params;
            }
        }
        // No field to clear. The top of the sheet put the banner over whatever the full-screen
        // sheet window is transparent to, the dark video above TikTok's share panel among it,
        // where a dark banner reads as nothing (S22, the save banner). The bottom is where the
        // eye already is in a sheet, and the fallback offset keeps its lowest row readable.
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.setMargins(side, 0, side, SettingsUi.dp(activity, BANNER_FALLBACK_BOTTOM_DP));
        return params;
    }

    /** The lowest visible text field inside the group, or null when it has none. */
    private static View lowestShownEditText(ViewGroup group) {
        View lowest = null;
        int lowestTop = Integer.MIN_VALUE;
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            if (child.getVisibility() != View.VISIBLE) continue;
            View candidate = child instanceof android.widget.EditText ? child
                    : child instanceof ViewGroup ? lowestShownEditText((ViewGroup) child) : null;
            if (candidate == null) continue;
            int top = topWithin(candidate, group);
            if (top > lowestTop) {
                lowestTop = top;
                lowest = candidate;
            }
        }
        return lowest;
    }

    /**
     * A descendant's top edge in the root's own coordinates, walked up through its parents.
     * Screen coordinates would do the same on a phone and answer zero for a root that is not in
     * a window yet, which is every root a test hands over.
     */
    private static int topWithin(View view, ViewGroup root) {
        int top = 0;
        for (View at = view; at != null && at != root; at = at.getParent() instanceof View
                ? (View) at.getParent() : null) {
            top += at.getTop();
            if (at.getParent() instanceof View) top -= ((View) at.getParent()).getScrollY();
        }
        return top;
    }

    private static void addAction(Activity activity, LinearLayout banner, String label,
            Runnable action) {
        TextView undo = new TextView(activity);
        undo.setText(label);
        undo.setContentDescription(label);
        undo.setTextColor(SettingsUi.OVERLAY_ACCENT);
        undo.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY_SMALL);
        // A banner that dismisses itself is the worst place for a small target.
        undo.setPadding(SettingsUi.dp(activity, 16), SettingsUi.dp(activity, 12),
                SettingsUi.dp(activity, 16), SettingsUi.dp(activity, 12));
        undo.setMinimumHeight(SettingsUi.dp(activity, 48));
        undo.setMinimumWidth(SettingsUi.dp(activity, 48));
        undo.setGravity(Gravity.CENTER);
        // The one way back from a block, on a banner that takes itself away after six seconds,
        // and it gave no sign at all that it had been pressed or that focus had reached it.
        undo.setBackground(SettingsUi.overlayAction(activity, SettingsUi.RADIUS_OVERLAY));
        undo.setFocusable(true);
        SettingsUi.markAsButton(undo);
        undo.setOnClickListener(view -> {
            dismissUndo();
            action.run();
        });
        banner.addView(undo, new LinearLayout.LayoutParams(-2, -2));
    }

    private static void dismissUndo() {
        undoGeneration++;
        View banner = undoReference.get();
        if (banner != null && banner.getParent() instanceof ViewGroup) {
            ((ViewGroup) banner.getParent()).removeView(banner);
        }
        undoReference = new WeakReference<>(null);
    }

}
