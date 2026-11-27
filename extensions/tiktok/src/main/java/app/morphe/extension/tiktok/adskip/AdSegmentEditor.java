/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.download.AdAudioSource;
import app.morphe.extension.tiktok.settings.L10n;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fullscreen dialog for selecting, previewing, fingerprinting, and saving an ad segment from a video. */
final class AdSegmentEditor {
    private static final long MAX_LOAD_MS = 10L * 60 * 1000;
    private static final long DEFAULT_LENGTH_MS = 15_000;
    private static final int THUMBS = 10;
    private static final int ENVELOPE_MS = 20;
    private static final int MIN_HASHES = 40;
    private static final long MIN_LENGTH_MS = SegmentRangeView.MIN_LENGTH_MS;
    private static final long MAX_LENGTH_MS = SegmentRangeView.MAX_LENGTH_MS;

    private final Activity activity;
    private final Object aweme;
    private final String aid;
    private final Dialog dialog;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancel = new AtomicBoolean();

    private FrameLayout videoBox;
    private TextureView texture;
    private View loadingBox;
    private TextView status;
    private ProgressBar spinner;
    private AdSkipUi.IconChip retryButton;
    private ImageView flash;
    private LinearLayout controls;
    private SegmentRangeView range;
    private TextView startLabel, endLabel, lengthLabel, positionLabel;
    private ImageView meterIcon;
    private TextView meterText;
    private View[] meterBars;
    private View shareRow;
    private AdSkipUi.IconChip saveButton;
    private AdSkipUi.IconChip playAdButton;
    private ImageView playButton, zoomButton;
    private EditText nameField;
    private boolean changed;
    private boolean tipShown;

    private File pictureFile, soundFile;
    private MediaPlayer picture, sound;
    private Surface surface;
    private int videoWidth, videoHeight;
    private boolean prepared;
    private long durationMs;
    private Landmarks.Print print;
    private long stopAt = -1;
    private long returnTo;
    private long pendingSeek = -1;
    private long seekTarget;
    private int seeksInFlight;
    private int seekToken;
    private boolean playWhenSeeked;
    private boolean resyncing;
    private static final long DRIFT_MS = 200;
    private static final long SEEK_TIMEOUT_MS = 700;
    private static final long ZOOM_FROM_MS = 45_000;
    private final long initialPositionMs;

    private AdSegmentEditor(Activity activity, Object aweme) {
        this.activity = activity;
        this.aweme = aweme;
        this.aid = Reflect.string(aweme, "getAid", "aid");
        this.initialPositionMs = Math.max(0, AdSkip.playingPositionFor(aid));
        this.dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
    }

    static void open(Activity activity, Object aweme) {
        if (activity == null || aweme == null || activity.isFinishing()) return;
        Utils.runOnMainThread(() -> {
            try {
                new AdSegmentEditor(activity, aweme).show();
            } catch (Throwable failure) {
                Logger.printException(() -> "Ad skip: could not open the editor", failure);
                Utils.showToastShort(L10n.t("Couldn't open the ad editor. Close the share sheet and try again."));
            }
        });
    }

    // region layout

    private void show() {
        Context c = activity;
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AdSkipUi.BG);

        // Top bar: close, the title over the creator's name, and Save.
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(12), dp(8));
        ImageView close = AdSkipUi.iconButton(c, AdIcons.Icon.CLOSE, L10n.t("Close"), 40);
        close.setOnClickListener(v -> requestClose());
        bar.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout heading = new LinearLayout(c);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(dp(12), 0, dp(8), 0);
        TextView title = AdSkipUi.text(c, L10n.t("Mark an ad"), 17, AdSkipUi.TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        heading.addView(title);
        String nick = authorName();
        if (nick != null) {
            TextView subtitle = AdSkipUi.text(c, "@" + nick, 12, AdSkipUi.TEXT_2, false);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            heading.addView(subtitle);
        }
        bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));
        saveButton = new AdSkipUi.IconChip(c, true);
        saveButton.set(AdIcons.Icon.CHECK, L10n.t("Save"));
        saveButton.setOnClickListener(v -> save());
        AdSkipUi.enable(saveButton, false);
        bar.addView(saveButton, new LinearLayout.LayoutParams(-2, dp(40)));
        root.addView(bar);

        // Video, with its loading state and a play or pause flash in the middle.
        videoBox = new FrameLayout(c);
        videoBox.setBackgroundColor(Color.BLACK);
        texture = new TextureView(c);
        videoBox.addView(texture, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        LinearLayout loading = new LinearLayout(c);
        loading.setOrientation(LinearLayout.VERTICAL);
        loading.setGravity(Gravity.CENTER_HORIZONTAL);
        spinner = AdSkipUi.spinner(c);
        loading.addView(spinner, new LinearLayout.LayoutParams(dp(40), dp(40)));
        status = AdSkipUi.text(c, L10n.t("Loading the video and its sound…"), 13, AdSkipUi.TEXT_2, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(32), dp(14), dp(32), 0);
        loading.addView(status, new LinearLayout.LayoutParams(-1, -2));
        retryButton = new AdSkipUi.IconChip(c, false);
        retryButton.set(AdIcons.Icon.REPLAY, L10n.t("Try again"));
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(v -> retry());
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(-2, dp(40));
        retryParams.topMargin = dp(16);
        loading.addView(retryButton, retryParams);
        loadingBox = loading;
        videoBox.addView(loading, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        flash = new ImageView(c);
        flash.setScaleType(ImageView.ScaleType.CENTER);
        flash.setBackground(AdSkipUi.round(0x8C000000, dp(32)));
        flash.setAlpha(0f);
        videoBox.addView(flash, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));
        positionLabel = AdSkipUi.text(c, "", 12, Color.WHITE, true);
        positionLabel.setBackground(AdSkipUi.round(0x99000000, dp(10)));
        positionLabel.setPadding(dp(8), dp(3), dp(8), dp(3));
        positionLabel.setVisibility(View.GONE);
        FrameLayout.LayoutParams posParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        posParams.setMargins(0, 0, dp(10), dp(10));
        videoBox.addView(positionLabel, posParams);
        videoBox.setOnClickListener(v -> togglePlay());
        root.addView(videoBox, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Controls: everything below the video fits one screen, nothing scrolls.
        controls = new LinearLayout(c);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(12), dp(6), dp(12), dp(10));
        controls.setBackgroundColor(AdSkipUi.BG);

        range = new SegmentRangeView(c, AdSkipUi.ACCENT);
        range.setListener(new SegmentRangeView.Listener() {
            @Override
            public void rangeChanged(long startMs, long endMs, boolean fromUser, boolean dragging) {
                if (fromUser) changed = true;
                updateLabels();
                if (!dragging) stopAt = -1;
            }

            @Override
            public void seekRequested(long ms, boolean dragging) {
                // The picture follows the finger; nothing plays until the reader asks.
                pause();
                seek(ms);
            }

            @Override
            public void zoomChanged(boolean zoomed) {
                AdSkipUi.setIcon(zoomButton, zoomed ? AdIcons.Icon.ZOOM_OUT : AdIcons.Icon.ZOOM_IN, BUTTON_DP);
                zoomButton.setContentDescription(L10n.t(zoomed ? "Zoom out" : "Zoom in"));
            }

            @Override
            public void fineZoomed() {
                learnedFineZoom();
            }
        });
        controls.addView(range, new LinearLayout.LayoutParams(-1, -2));

        // One line under the strip: a gesture tip, the sound check, or a warning.
        LinearLayout meter = new LinearLayout(c);
        meter.setOrientation(LinearLayout.HORIZONTAL);
        meter.setGravity(Gravity.CENTER_VERTICAL);
        meter.setPadding(dp(4), dp(2), dp(4), dp(2));
        meterIcon = new ImageView(c);
        meter.addView(meterIcon, new LinearLayout.LayoutParams(dp(16), dp(16)));
        meterText = AdSkipUi.text(c, "", 12, AdSkipUi.TEXT_2, false);
        meterText.setMaxLines(2);
        meterText.setEllipsize(TextUtils.TruncateAt.END);
        meterText.setPadding(dp(8), 0, dp(8), 0);
        meter.addView(meterText, new LinearLayout.LayoutParams(0, -2, 1f));
        meterBars = new View[3];
        for (int i = 0; i < meterBars.length; i++) {
            View barView = new View(c);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(14), dp(4));
            params.leftMargin = dp(3);
            meter.addView(barView, params);
            meterBars[i] = barView;
        }
        meter.setMinimumHeight(dp(26));
        controls.addView(meter, new LinearLayout.LayoutParams(-1, -2));

        // Start and end, each with a tenth of a second either way, and the length between.
        LinearLayout edges = new LinearLayout(c);
        edges.setOrientation(LinearLayout.HORIZONTAL);
        edges.setGravity(Gravity.CENTER_VERTICAL);
        edges.setPadding(0, dp(6), 0, dp(6));
        edges.addView(nudge(true, -NUDGE_MS), nudgeParams());
        startLabel = edgeChip(true);
        edges.addView((View) startLabel.getParent());
        edges.addView(nudge(true, NUDGE_MS), nudgeParams());
        LinearLayout length = new LinearLayout(c);
        length.setOrientation(LinearLayout.VERTICAL);
        length.setGravity(Gravity.CENTER);
        lengthLabel = AdSkipUi.text(c, "", 16, AdSkipUi.TEXT, true);
        lengthLabel.setGravity(Gravity.CENTER);
        length.addView(lengthLabel);
        TextView lengthCaption = AdSkipUi.text(c, L10n.t("Length"), 11, AdSkipUi.TEXT_2, false);
        lengthCaption.setGravity(Gravity.CENTER);
        length.addView(lengthCaption);
        edges.addView(length, new LinearLayout.LayoutParams(0, -2, 1f));
        edges.addView(nudge(false, -NUDGE_MS), nudgeParams());
        endLabel = edgeChip(false);
        edges.addView((View) endLabel.getParent());
        edges.addView(nudge(false, NUDGE_MS), nudgeParams());
        controls.addView(edges);

        // Playback: the whole video, just the ad, and zoom, one height for all three.
        LinearLayout play = new LinearLayout(c);
        play.setOrientation(LinearLayout.HORIZONTAL);
        play.setGravity(Gravity.CENTER_VERTICAL);
        play.setPadding(0, dp(2), 0, dp(10));
        playButton = AdSkipUi.iconButton(c, AdIcons.Icon.PLAY, L10n.t("Play"), BUTTON_DP);
        playButton.setOnClickListener(v -> togglePlay());
        play.addView(playButton, new LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)));
        playAdButton = new AdSkipUi.IconChip(c, AdSkipUi.SURFACE_HI, AdSkipUi.TEXT, true);
        playAdButton.set(AdIcons.Icon.HEADPHONES, L10n.t("Play ad"));
        playAdButton.setOnClickListener(v -> playSelection());
        LinearLayout.LayoutParams playAdParams = new LinearLayout.LayoutParams(0, dp(BUTTON_DP), 1f);
        playAdParams.leftMargin = dp(10);
        playAdParams.rightMargin = dp(10);
        play.addView(playAdButton, playAdParams);
        zoomButton = AdSkipUi.iconButton(c, AdIcons.Icon.ZOOM_IN, L10n.t("Zoom in"), BUTTON_DP);
        zoomButton.setOnClickListener(v -> range.zoom(!range.zoomed()));
        play.addView(zoomButton, new LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)));
        controls.addView(play);

        // Name.
        nameField = new EditText(c);
        nameField.setSingleLine(true);
        nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nameField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        nameField.setTextColor(AdSkipUi.TEXT);
        nameField.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        nameField.setHintTextColor(AdSkipUi.TEXT_2);
        nameField.setHint(L10n.t("Name"));
        nameField.setText(defaultName());
        nameField.setBackground(AdSkipUi.round(AdSkipUi.SURFACE, dp(12)));
        nameField.setPadding(dp(14), 0, dp(14), 0);
        nameField.setCompoundDrawablesRelative(AdIcons.of(c, AdIcons.Icon.EDIT, AdSkipUi.TEXT_2, 18), null, null, null);
        nameField.setCompoundDrawablePadding(dp(10));
        nameField.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            hideKeyboard();
            nameField.clearFocus();
            return true;
        });
        nameField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void afterTextChanged(android.text.Editable s) { changed = true; }
        });
        controls.addView(nameField, new LinearLayout.LayoutParams(-1, dp(46)));

        controls.addView(shareRow(), new LinearLayout.LayoutParams(-1, -2));

        root.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        AdSkipUi.padForSystemBars(root);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.setStatusBarColor(AdSkipUi.BG);
            window.setNavigationBarColor(AdSkipUi.BG);
        }
        // Back asks before throwing a chosen part away, like the close button.
        dialog.setCancelable(false);
        dialog.setOnKeyListener((d, code, event) -> {
            if (code != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) requestClose();
            return true;
        });
        if (Build.VERSION.SDK_INT >= 33) {
            dialog.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::requestClose);
        }
        dialog.setOnDismissListener(d -> release());
        // Leaving the app pauses the editor's video; coming back pauses the feed again, which
        // TikTok starts by itself on return, so its sound never plays behind the editor.
        focusListener = hasFocus -> {
            if (hasFocus) AdSkip.setFeedPlaying(false);
            else pause();
        };
        root.getViewTreeObserver().addOnWindowFocusChangeListener(focusListener);
        focusRoot = root;
        AdSkip.setPaused(true);
        AdSkip.setFeedPlaying(false);
        enableControls(false);
        showTip();
        dialog.show();

        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                surface = new Surface(st);
                if (picture != null) picture.setSurface(surface);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                fitVideo();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (picture != null) picture.setSurface(null);
                if (surface != null) surface.release();
                surface = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
        videoBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitVideo());
        load();
    }

    private static final float BUTTON_DP = 48;
    private static final long NUDGE_MS = 100;
    private static final long REPEAT_FIRST_MS = 380, REPEAT_MS = 110, REPEAT_FLOOR_MS = 40;

    private LinearLayout.LayoutParams nudgeParams() {
        return new LinearLayout.LayoutParams(dp(34), dp(34));
    }

    /** Adjusts the start or end boundary by {@code delta} ms, accelerating while held. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private View nudge(boolean start, long delta) {
        ImageView button = AdSkipUi.iconButton(activity, delta < 0 ? AdIcons.Icon.MINUS : AdIcons.Icon.PLUS,
                L10n.t(start ? (delta < 0 ? "Start earlier" : "Start later") : (delta < 0 ? "End earlier" : "End later")), 34);
        Runnable step = () -> setEdge(start, (start ? range.start() : range.end()) + delta);
        Runnable[] repeat = new Runnable[1];
        long[] interval = {REPEAT_MS};
        repeat[0] = () -> {
            step.run();
            button.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            interval[0] = Math.max(REPEAT_FLOOR_MS, interval[0] - 8);
            main.postDelayed(repeat[0], interval[0]);
        };
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!v.isEnabled()) return false;
                    v.setPressed(true);
                    step.run();
                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    interval[0] = REPEAT_MS;
                    main.postDelayed(repeat[0], REPEAT_FIRST_MS);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    main.removeCallbacks(repeat[0]);
                    return true;
                default:
                    return true;
            }
        });
        // Switch Access and TalkBack click rather than touch.
        button.setOnClickListener(v -> step.run());
        return button;
    }

    /** Chip showing the start or end timestamp; tapping previews 1.5 s of audio inside that edge. */
    private TextView edgeChip(boolean start) {
        Context c = activity;
        LinearLayout chip = new LinearLayout(c);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(8), dp(4), dp(8), dp(4));
        chip.setMinimumWidth(dp(74));
        chip.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), AdSkipUi.round(AdSkipUi.SURFACE, dp(12)), null));
        chip.setClickable(true);
        chip.setFocusable(true);
        TextView caption = AdSkipUi.text(c, L10n.t(start ? "Start" : "End"), 11, AdSkipUi.TEXT_2, false);
        caption.setGravity(Gravity.CENTER);
        caption.setCompoundDrawablesRelative(null, null, AdIcons.of(c, AdIcons.Icon.HEADPHONES, AdSkipUi.TEXT_2, 11), null);
        caption.setCompoundDrawablePadding(dp(4));
        chip.addView(caption);
        TextView time = AdSkipUi.text(c, "", 15, AdSkipUi.TEXT, true);
        time.setGravity(Gravity.CENTER);
        chip.addView(time);
        chip.setContentDescription(L10n.t(start ? "Listen to the start" : "Listen to the end"));
        chip.setOnClickListener(v -> listenToEdge(start));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        chip.setLayoutParams(params);
        return time;
    }

    /** Sharing on its own row: what it does, and why it is off when the shared base is. */
    private View shareRow() {
        Context c = activity;
        boolean sharedEnabled = app.morphe.extension.tiktok.settings.Settings.AD_SKIP_SHARED_DB.get();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(10), 0, 0);
        ImageView icon = new ImageView(c);
        icon.setImageDrawable(AdIcons.of(c, AdIcons.Icon.CLOUD, sharedEnabled ? AdSkipBar.MARK : AdSkipUi.TEXT_2, 20));
        row.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, dp(8), 0);
        text.addView(AdSkipUi.text(c, L10n.t("Share with everyone"), 14, AdSkipUi.TEXT, true));
        TextView detail = AdSkipUi.text(c, L10n.t(sharedEnabled
                ? "Everyone with Metra skips this ad too."
                : "Turn on the shared base in settings to share."), 12, AdSkipUi.TEXT_2, false);
        text.addView(detail);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.Switch toggle = new android.widget.Switch(c);
        AdSkipUi.tint(toggle, AdSkipBar.MARK);
        toggle.setChecked(sharedEnabled && app.morphe.extension.tiktok.settings.Settings.AD_SKIP_SHARE_AUTO.get());
        toggle.setEnabled(sharedEnabled);
        toggle.setOnCheckedChangeListener((btn, checked) ->
                app.morphe.extension.tiktok.settings.Settings.AD_SKIP_SHARE_AUTO.save(checked));
        row.addView(toggle);
        if (sharedEnabled) row.setOnClickListener(v -> toggle.toggle());
        row.setAlpha(sharedEnabled ? 1f : 0.55f);
        shareRow = row;
        return row;
    }

    @Nullable
    private String authorName() {
        Object author = Reflect.property(aweme, "getAuthor", "author");
        String nick = Reflect.string(author, "getNickname", "nickname");
        return nick == null || nick.trim().isEmpty() ? null : nick.trim();
    }

    private String defaultName() {
        String nick = authorName();
        String base = L10n.t("Ad");
        int n = AdSegmentStore.ownCount() + 1;
        return nick == null ? base + " " + n : base + " " + n + " · " + nick;
    }

    private void enableControls(boolean enabled) {
        controls.setAlpha(enabled ? 1f : 0.35f);
        setEnabledDeep(controls, enabled);
        nameField.setEnabled(true);
        if (shareRow != null) shareRow.setAlpha(app.morphe.extension.tiktok.settings.Settings.AD_SKIP_SHARED_DB.get() ? 1f : 0.55f);
    }

    private static void setEnabledDeep(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setEnabledDeep(group.getChildAt(i), enabled);
        }
    }

    /** Closes at once when nothing was chosen; otherwise asks first. */
    private void requestClose() {
        if (!changed) {
            dialog.dismiss();
            return;
        }
        pause();
        new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(L10n.t("Discard this ad?"))
                .setMessage(L10n.t("The part you chose is not saved."))
                .setPositiveButton(L10n.t("Discard"), (d, w) -> dialog.dismiss())
                .setNegativeButton(L10n.t("Keep editing"), null)
                .show();
    }

    /** A play or pause sign that blooms in the middle of the video and fades. */
    private void flash(AdIcons.Icon icon) {
        flash.setImageDrawable(AdIcons.of(activity, icon, Color.WHITE, 30));
        flash.animate().cancel();
        flash.setAlpha(1f);
        flash.setScaleX(0.85f);
        flash.setScaleY(0.85f);
        flash.animate().scaleX(1.1f).scaleY(1.1f).alpha(0f).setStartDelay(120).setDuration(420).start();
    }

    // region tips and the sound check

    private static final String KEY_FINE_ZOOMS = "editor_fine_zooms";
    private static final int TIP_UNTIL = 3;
    private static final int STRONG_HASHES = 150;

    private void showTip() {
        android.content.SharedPreferences prefs = AdSkipPrefs.get();
        tipShown = prefs == null || prefs.getInt(KEY_FINE_ZOOMS, 0) < TIP_UNTIL;
        if (tipShown) setTipText();
        else setMeter(AdIcons.Icon.GRAPHIC_EQ, AdSkipUi.TEXT_2, "", 0);
    }

    private void setTipText() {
        setMeter(AdIcons.Icon.INFO, AdSkipUi.TEXT_2,
                L10n.t("Hold a handle still to zoom in. Pinch the strip to zoom."), 0);
    }

    private void learnedFineZoom() {
        android.content.SharedPreferences prefs = AdSkipPrefs.get();
        if (prefs != null) prefs.edit().putInt(KEY_FINE_ZOOMS, prefs.getInt(KEY_FINE_ZOOMS, 0) + 1).apply();
        if (tipShown) {
            tipShown = false;
            updateSound();
        }
    }

    /** Updates the landmark-strength indicator and Save button state for the selected range. */
    private void updateSound() {
        if (print == null) {
            AdSkipUi.enable(saveButton, false);
            return;
        }
        int count = soundCount();
        // Why Save is off always wins over the gesture tip.
        if (count < MIN_HASHES) {
            setMeter(AdIcons.Icon.WARNING, AdSkipUi.ACCENT, L10n.t("Too little sound to recognise. Choose a louder part."), 1);
        } else if (tipShown) {
            setTipText();
        } else if (count < STRONG_HASHES) {
            setMeter(AdIcons.Icon.GRAPHIC_EQ, AdSkipUi.WARN, L10n.t("Weak sound. A longer or louder part is found more reliably."), 2);
        } else {
            setMeter(AdIcons.Icon.GRAPHIC_EQ, AdSkipUi.GOOD, L10n.t("Clear sound. This part is easy to recognise."), 3);
        }
        AdSkipUi.enable(saveButton, prepared && count >= MIN_HASHES);
    }

    private int soundCount() {
        if (print == null) return 0;
        return print.countIn(Landmarks.msToFrame(range.start()), Landmarks.msToFrame(range.end()));
    }

    /** The line under the strip: an icon, a sentence, and 0 to 3 lit bars in the same colour. */
    private void setMeter(AdIcons.Icon icon, int color, String text, int lit) {
        meterIcon.setImageDrawable(AdIcons.of(activity, icon, color, 16));
        meterText.setText(text);
        meterText.setTextColor(color == AdSkipUi.TEXT_2 ? AdSkipUi.TEXT_2 : color);
        for (int i = 0; i < meterBars.length; i++) {
            meterBars[i].setVisibility(lit == 0 ? View.GONE : View.VISIBLE);
            meterBars[i].setBackground(AdSkipUi.round(i < lit ? color : AdSkipUi.SURFACE_HI, dp(2)));
        }
    }

    // endregion

    // endregion

    // region loading

    private void load() {
        Utils.runOnOwnThread("metra-adskip-editor", () -> {
            try {
                Context context = activity.getApplicationContext();
                AdAudioSource.sweep(context);
                setStatus(L10n.t("Downloading the video…"));
                File pic = AdAudioSource.fetchUrls(context, AdAudioSource.videoUrls(aweme), "edit-video");
                if (cancel.get()) { pic.delete(); return; }
                File snd = null;
                if (AdAudioSource.separateSound(aweme) || !AdAudioSource.hasSound(pic)) {
                    setStatus(L10n.t("Downloading the sound…"));
                    snd = AdAudioSource.fetch(context, aweme, "edit-sound");
                }
                final File soundSource = snd != null ? snd : pic;
                pictureFile = pic;
                soundFile = snd;

                setStatus(L10n.t("Reading the sound…"));
                AdAudioSource.Pcm pcm = AdAudioSource.decode(soundSource, Landmarks.SAMPLE_RATE, MAX_LOAD_MS, cancel);
                if (cancel.get()) return;
                float[] envelope = envelope(pcm);
                Landmarks.Print full = Landmarks.compute(pcm.samples, pcm.length);
                long audioMs = pcm.durationMs();
                main.post(() -> {
                    if (cancel.get()) return;
                    print = full;
                    preparePlayers(audioMs);
                    range.setEnvelope(envelope);
                    updateSound();
                });

                Bitmap[] thumbs = thumbs(pic);
                main.post(() -> {
                    if (!cancel.get()) range.setThumbs(thumbs);
                });
            } catch (Throwable failure) {
                Logger.printException(() -> "Ad skip: the editor could not load the video", failure);
                main.post(() -> {
                    if (cancel.get()) return;
                    spinner.setVisibility(View.GONE);
                    status.setText(L10n.t("Couldn't load this video. Check the connection and try again."));
                    status.setTextColor(AdSkipUi.WARN);
                    retryButton.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    /** After a failed download: back to the spinner and the same steps again. */
    private void retry() {
        if (prepared || cancel.get()) return;
        retryButton.setVisibility(View.GONE);
        spinner.setVisibility(View.VISIBLE);
        status.setTextColor(AdSkipUi.TEXT_2);
        status.setText(L10n.t("Loading the video and its sound…"));
        for (File file : new File[]{pictureFile, soundFile}) {
            if (file != null) //noinspection ResultOfMethodCallIgnored
                file.delete();
        }
        pictureFile = null;
        soundFile = null;
        load();
    }

    private void setStatus(String text) {
        main.post(() -> {
            if (!cancel.get()) status.setText(text);
        });
    }

    /** The loudest sample in each 20 ms, scaled so the loudest window fills the strip. */
    private static float[] envelope(AdAudioSource.Pcm pcm) {
        int window = Math.max(1, pcm.sampleRate * ENVELOPE_MS / 1000);
        int n = Math.max(1, pcm.length / window);
        float[] values = new float[n];
        float max = 1;
        for (int i = 0; i < n; i++) {
            int peak = 0;
            int from = i * window, to = Math.min(pcm.length, from + window);
            for (int k = from; k < to; k++) {
                int v = Math.abs(pcm.samples[k]);
                if (v > peak) peak = v;
            }
            values[i] = peak;
            if (peak > max) max = peak;
        }
        for (int i = 0; i < n; i++) values[i] = (float) Math.sqrt(values[i] / max);
        return values;
    }

    private Bitmap[] thumbs(File file) {
        Bitmap[] out = new Bitmap[THUMBS];
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long duration = d == null ? 0 : Long.parseLong(d);
            if (duration <= 0) return out;
            for (int i = 0; i < THUMBS && !cancel.get(); i++) {
                long us = (duration * 1000L) * (2L * i + 1) / (2L * THUMBS);
                Bitmap frame;
                if (Build.VERSION.SDK_INT >= 27) {
                    frame = retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 284);
                } else {
                    Bitmap big = retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    frame = big == null ? null : Bitmap.createScaledBitmap(big, 160,
                            Math.max(1, Math.round(160f * big.getHeight() / big.getWidth())), true);
                    if (big != null && big != frame) big.recycle();
                }
                out[i] = frame;
                final Bitmap[] partial = out.clone();
                main.post(() -> {
                    if (!cancel.get()) range.setThumbs(partial);
                });
            }
        } catch (Throwable failure) {
            Logger.printDebug(() -> "Ad skip: no frames for the strip");
        } finally {
            try { retriever.release(); } catch (Throwable ignored) { }
        }
        return out;
    }

    private void preparePlayers(long audioMs) {
        try {
            picture = new MediaPlayer();
            picture.setDataSource(pictureFile.getAbsolutePath());
            if (surface != null) picture.setSurface(surface);
            if (soundFile != null) picture.setVolume(0f, 0f);
            picture.setOnVideoSizeChangedListener((mp, w, h) -> {
                videoWidth = w;
                videoHeight = h;
                fitVideo();
            });
            picture.setOnSeekCompleteListener(mp -> {
                if (resyncing) {
                    resyncing = false;
                    return;
                }
                seekDone();
            });
            picture.setOnCompletionListener(mp -> {
                pauseSound();
                updatePlayButtons();
            });
            picture.setOnPreparedListener(mp -> {
                if (cancel.get()) return;
                if (soundFile != null) {
                    try {
                        sound = new MediaPlayer();
                        sound.setDataSource(soundFile.getAbsolutePath());
                        sound.prepare();
                        sound.setOnSeekCompleteListener(p -> seekDone());
                    } catch (Exception failure) {
                        Logger.printException(() -> "Ad skip: no separate sound player", failure);
                        sound = null;
                        picture.setVolume(1f, 1f);
                    }
                }
                prepared = true;
                long videoMs = picture.getDuration();
                durationMs = videoMs > 0 && audioMs > 0 ? Math.min(videoMs, audioMs) : Math.max(videoMs, audioMs);
                range.setDuration(durationMs);
                long start = Math.min(initialPositionMs, Math.max(0, durationMs - MIN_LENGTH_MS));
                long end = Math.min(durationMs, start + DEFAULT_LENGTH_MS);
                if (end - start < MIN_LENGTH_MS) start = Math.max(0, end - DEFAULT_LENGTH_MS);
                range.setRange(start, end);
                if (durationMs > ZOOM_FROM_MS) range.zoom(true);
                updateLabels();
                loadingBox.setVisibility(View.GONE);
                positionLabel.setVisibility(View.VISIBLE);
                enableControls(true);
                updateSound();
                seek(start);
                main.post(ticker);
            });
            picture.prepareAsync();
        } catch (Exception failure) {
            Logger.printException(() -> "Ad skip: could not play the video in the editor", failure);
            spinner.setVisibility(View.GONE);
            status.setTextColor(AdSkipUi.WARN);
            status.setText(L10n.t("Couldn't play this video. Close the editor and try again."));
        }
    }

    private void fitVideo() {
        if (videoWidth <= 0 || videoHeight <= 0 || texture == null) return;
        float boxW = videoBox.getWidth(), boxH = videoBox.getHeight();
        if (boxW <= 0 || boxH <= 0) return;
        float scale = Math.min(boxW / videoWidth, boxH / videoHeight);
        float w = videoWidth * scale, h = videoHeight * scale;
        Matrix matrix = new Matrix();
        matrix.setScale(w / boxW, h / boxH, boxW / 2f, boxH / 2f);
        texture.setTransform(matrix);
        texture.invalidate();
    }

    // endregion

    // region playback

    /** Separate-audio posts use two MediaPlayers with the audio player as master clock. */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (cancel.get() || picture == null) return;
            try {
                long now = currentMs();
                range.setPlayhead(now);
                positionLabel.setText(AdSkipUi.time(now, true) + " / " + AdSkipUi.time(durationMs, false));
                if (picture.isPlaying()) {
                    if (stopAt >= 0 && now >= stopAt) {
                        long back = returnTo;
                        stopAt = -1;
                        pause();
                        seek(back);
                    } else if (sound != null && sound.isPlaying() && seeksInFlight == 0 && !resyncing
                            && Math.abs(picture.getCurrentPosition() - now) > DRIFT_MS) {
                        resyncing = true;
                        seekPlayer(picture, now);
                    }
                }
            } catch (IllegalStateException ignored) {
                // A player being released.
            }
            main.postDelayed(this, 33);
        }
    };

    private long currentMs() {
        if (picture == null || !prepared) return 0;
        if (seeksInFlight > 0) return pendingSeek >= 0 ? pendingSeek : seekTarget;
        try {
            if (sound != null && sound.isPlaying()) return sound.getCurrentPosition();
            return picture.getCurrentPosition();
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    private void seek(long ms) {
        if (picture == null || !prepared) return;
        ms = Math.max(0, Math.min(durationMs, ms));
        range.setPlayhead(ms);
        if (seeksInFlight > 0) {
            pendingSeek = ms;
            return;
        }
        seekTarget = ms;
        int token = ++seekToken;
        try {
            seeksInFlight = sound != null ? 2 : 1;
            seekPlayer(picture, ms);
            if (sound != null) seekPlayer(sound, ms);
        } catch (IllegalStateException e) {
            seeksInFlight = 0;
            return;
        }
        // Some decoders never report a seek that lands where they already are.
        main.postDelayed(() -> {
            if (token == seekToken && seeksInFlight > 0) {
                seeksInFlight = 1;
                seekDone();
            }
        }, SEEK_TIMEOUT_MS);
    }

    private static void seekPlayer(MediaPlayer player, long ms) {
        if (Build.VERSION.SDK_INT >= 26) player.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
        else player.seekTo((int) ms);
    }

    private void seekDone() {
        if (seeksInFlight > 0) seeksInFlight--;
        if (seeksInFlight > 0) return;
        if (pendingSeek >= 0) {
            long next = pendingSeek;
            pendingSeek = -1;
            seek(next);
            return;
        }
        if (playWhenSeeked) {
            playWhenSeeked = false;
            startPlayers();
        }
    }

    /** Plays from this position once both players are there. */
    private void playFrom(long ms) {
        if (!prepared) return;
        playWhenSeeked = true;
        seek(ms);
        updatePlayButtons();
    }

    private void startPlayers() {
        if (picture == null || !prepared || cancel.get()) return;
        try {
            if (sound != null) sound.start();
            picture.start();
        } catch (IllegalStateException ignored) {
            // Released.
        }
        updatePlayButtons();
    }

    private void pause() {
        playWhenSeeked = false;
        if (picture == null || !prepared) return;
        try {
            if (picture.isPlaying()) picture.pause();
        } catch (IllegalStateException ignored) {
            // Released.
        }
        pauseSound();
        updatePlayButtons();
    }

    private void pauseSound() {
        try {
            if (sound != null && sound.isPlaying()) sound.pause();
        } catch (IllegalStateException ignored) {
            // Released.
        }
    }

    private boolean isPlaying() {
        try {
            return playWhenSeeked || (picture != null && prepared && picture.isPlaying());
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private void togglePlay() {
        if (!prepared) return;
        stopAt = -1;
        if (isPlaying()) {
            pause();
            flash(AdIcons.Icon.PAUSE);
            return;
        }
        long from = currentMs();
        if (from >= durationMs - 100) from = 0;
        playFrom(from);
        flash(AdIcons.Icon.PLAY);
    }

    private void playSelection() {
        if (!prepared) return;
        if (isPlaying() && stopAt >= 0) {
            stopAt = -1;
            pause();
            seek(range.start());
            return;
        }
        stopAt = range.end();
        returnTo = range.start();
        playFrom(range.start());
    }

    /** Plays 1.5 s of audio adjacent to the start or end boundary inside the selection. */
    private void listenToEdge(boolean start) {
        if (!prepared) return;
        long edge = start ? range.start() : range.end();
        long from = start ? edge : Math.max(range.start(), edge - EDGE_LISTEN_MS);
        stopAt = start ? Math.min(range.end(), edge + EDGE_LISTEN_MS) : edge;
        returnTo = edge;
        playFrom(from);
    }

    private static final long EDGE_LISTEN_MS = 1500;

    private void updatePlayButtons() {
        boolean playing = isPlaying();
        boolean selection = playing && stopAt >= 0;
        AdSkipUi.setIcon(playButton, playing && !selection ? AdIcons.Icon.PAUSE : AdIcons.Icon.PLAY, BUTTON_DP);
        playButton.setContentDescription(L10n.t(playing && !selection ? "Pause" : "Play"));
        playAdButton.set(selection ? AdIcons.Icon.STOP : AdIcons.Icon.HEADPHONES, L10n.t(selection ? "Stop" : "Play ad"));
    }

    private void setEdge(boolean start, long ms) {
        if (!prepared) return;
        long s = range.start(), e = range.end();
        if (start) s = Math.max(Math.max(0, e - MAX_LENGTH_MS), Math.min(ms, e - MIN_LENGTH_MS));
        else e = Math.min(Math.min(durationMs, s + MAX_LENGTH_MS), Math.max(ms, s + MIN_LENGTH_MS));
        range.setRange(s, e);
        range.reveal(start ? s : e);
        changed = true;
        updateLabels();
        stopAt = -1;
        pause();
        seek(start ? s : e);
    }

    private void updateLabels() {
        long s = range.start(), e = range.end();
        startLabel.setText(AdSkipUi.time(s, true));
        endLabel.setText(AdSkipUi.time(e, true));
        lengthLabel.setText(L10n.f("%1$s s", AdSkipUi.seconds(e - s)));
        updateSound();
    }

    // endregion

    // region saving

    private void save() {
        if (!prepared || print == null) return;
        long s = range.start(), e = range.end();
        Landmarks.Print cut = print.slice(Landmarks.msToFrame(s), Landmarks.msToFrame(e));
        if (cut.count < MIN_HASHES) {
            updateSound();
            return;
        }
        String name = nameField.getText().toString().trim();
        if (name.isEmpty()) name = defaultName();
        pause();

        AdSegment segment = AdSegmentStore.add(name, e - s, aid, cut);
        // If the user chose to share, submit to the shared base.
        if (app.morphe.extension.tiktok.settings.Settings.AD_SKIP_SHARED_DB.get()
                && app.morphe.extension.tiktok.settings.Settings.AD_SKIP_SHARE_AUTO.get()) {
            android.content.Context ctx = activity.getApplicationContext();
            app.morphe.extension.tiktok.adskip.SharedDb.submit(ctx, segment);
        }
        // The cut must be found again in its own video, at its own place.
        List<AdMatcher.Match> check = AdMatcher.match(AdMatcher.Index.build(java.util.Collections.singletonList(segment)),
                print, AdMatcher.Strictness.NORMAL);
        boolean found = false;
        for (AdMatcher.Match m : check) if (Math.abs(m.startMs - s) < 1500) found = true;
        AdSkipLog.add(aid, "saved \"" + name + "\" " + cut.count + " hashes, self-check " + (found ? "ok" : "weak"));
        List<AdMatcher.Match> fullMatches = AdMatcher.match(AdSegmentStore.index(), print,
                AdMatcher.Strictness.of(app.morphe.extension.tiktok.settings.Settings.AD_SKIP_STRICTNESS.get()));
        AdScanner.recordMarked(aid, segment, s, e, cut.count, durationMs);
        if (!fullMatches.isEmpty()) {
            for (AdMatcher.Match m : fullMatches) {
                if (m.segment != segment) {
                    AdScanner.recordMarked(aid, m.segment, m.startMs, m.endMs, m.votes, durationMs);
                }
            }
        }
        hideKeyboard();
        dialog.dismiss();
        Utils.showToastLong(found
                ? L10n.f("Saved \"%1$s\". This ad will be skipped in other videos.", name)
                : L10n.f("Saved \"%1$s\", but its sound is weak. A longer or louder cut is found more reliably.", name));
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(nameField.getWindowToken(), 0);
        } catch (Throwable ignored) {
            // Nothing to hide.
        }
    }

    // endregion

    private android.view.ViewTreeObserver.OnWindowFocusChangeListener focusListener;
    private View focusRoot;

    private void release() {
        if (focusRoot != null && focusListener != null && focusRoot.getViewTreeObserver().isAlive()) {
            focusRoot.getViewTreeObserver().removeOnWindowFocusChangeListener(focusListener);
        }
        focusListener = null;
        cancel.set(true);
        main.removeCallbacksAndMessages(null);
        for (MediaPlayer player : new MediaPlayer[]{picture, sound}) {
            if (player == null) continue;
            try { player.release(); } catch (Throwable ignored) { }
        }
        picture = null;
        sound = null;
        if (pictureFile != null) //noinspection ResultOfMethodCallIgnored
            pictureFile.delete();
        if (soundFile != null) //noinspection ResultOfMethodCallIgnored
            soundFile.delete();
        AdSkip.setPaused(false);
        AdSkip.setFeedPlaying(true);
    }

    private int dp(float v) {
        return AdSkipUi.dp(activity, v);
    }

    @SuppressWarnings("unused")
    private static String date(long ms) {
        return DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(ms));
    }
}
