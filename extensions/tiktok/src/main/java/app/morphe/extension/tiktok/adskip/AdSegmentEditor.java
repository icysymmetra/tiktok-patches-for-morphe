/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
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
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

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

/**
 * Cuts an ad out of a video: the video plays at the top with its sound, a strip of frames and
 * the waveform below it, and two handles choose where the ad starts and ends. "Play ad" plays
 * just the chosen part, so the reader hears exactly what will be matched. Saving fingerprints
 * the cut, checks it can be found again in this very video, and adds it to the list.
 */
final class AdSegmentEditor {
    private static final long MAX_LOAD_MS = 10L * 60 * 1000;
    private static final long DEFAULT_LENGTH_MS = 15_000;
    private static final int THUMBS = 10;
    /** Envelope resolution: one value per 20 ms. */
    private static final int ENVELOPE_MS = 20;
    /** Fewer hashes than this is too little sound to recognise. */
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
    private TextView status;
    private ProgressBar spinner;
    private LinearLayout controls;
    private SegmentRangeView range;
    private TextView startLabel, endLabel, lengthLabel, positionLabel, hint;
    private TextView saveButton;
    private AdSkipUi.IconChip playAdButton;
    private ImageView playButton, zoomButton;
    private EditText nameField;

    private File pictureFile, soundFile;
    private MediaPlayer picture, sound;
    private Surface surface;
    private int videoWidth, videoHeight;
    private boolean prepared;
    private long durationMs;
    private Landmarks.Print print;
    /** When the play should stop: the end of the ad for Play ad, or -1; and where it goes back to. */
    private long stopAt = -1;
    private long returnTo;
    private long pendingSeek = -1;
    private long seekTarget;
    private int seeksInFlight;
    private int seekToken;
    private boolean playWhenSeeked;
    /** The picture is being brought back to the sound's clock. */
    private boolean resyncing;
    private static final long DRIFT_MS = 200;
    private static final long SEEK_TIMEOUT_MS = 700;
    /** Past this length the timeline opens zoomed in on the chosen part. */
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

        // Top bar.
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(8), dp(12), dp(8));
        ImageView close = AdSkipUi.iconButton(c, AdIcons.Icon.CLOSE, L10n.t("Close"), 40);
        close.setOnClickListener(v -> dialog.dismiss());
        bar.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = AdSkipUi.text(c, L10n.t("Mark an ad"), 18, AdSkipUi.TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(12), 0, dp(8), 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        saveButton = AdSkipUi.chip(c, L10n.t("Save"), true);
        saveButton.setOnClickListener(v -> save());
        AdSkipUi.enable(saveButton, false);
        bar.addView(saveButton);
        root.addView(bar);

        // Video.
        videoBox = new FrameLayout(c);
        videoBox.setBackgroundColor(Color.BLACK);
        texture = new TextureView(c);
        videoBox.addView(texture, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        spinner = new ProgressBar(c);
        videoBox.addView(spinner, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        status = AdSkipUi.text(c, L10n.t("Loading the video and its sound…"), 13, AdSkipUi.TEXT_2, false);
        status.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        statusParams.topMargin = dp(70);
        videoBox.addView(status, statusParams);
        positionLabel = AdSkipUi.text(c, "", 12, Color.WHITE, true);
        positionLabel.setBackground(AdSkipUi.round(0x99000000, dp(10)));
        positionLabel.setPadding(dp(8), dp(3), dp(8), dp(3));
        FrameLayout.LayoutParams posParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        posParams.setMargins(0, 0, dp(10), dp(10));
        videoBox.addView(positionLabel, posParams);
        videoBox.setOnClickListener(v -> togglePlay());
        root.addView(videoBox, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Controls: everything below the video fits one screen, nothing scrolls.
        controls = new LinearLayout(c);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(12), dp(8), dp(12), dp(8));
        controls.setBackgroundColor(AdSkipUi.BG);
        controls.setAlpha(0.35f);

        range = new SegmentRangeView(c, AdSkipUi.ACCENT);
        range.setListener(new SegmentRangeView.Listener() {
            @Override
            public void rangeChanged(long startMs, long endMs, boolean fromUser, boolean dragging) {
                updateLabels();
                if (!dragging) stopAt = -1;
            }

            @Override
            public void seekRequested(long ms, boolean dragging) {
                pause();
                seek(ms);
                if (!dragging) previewAround(ms);
            }

            @Override
            public void zoomChanged(boolean zoomed) {
                AdSkipUi.setIcon(zoomButton, zoomed ? AdIcons.Icon.ZOOM_OUT : AdIcons.Icon.ZOOM_IN, BUTTON_DP);
            }
        });
        controls.addView(range, new LinearLayout.LayoutParams(-1, -2));

        // Start and end, each with a tenth of a second either way, and the length between.
        LinearLayout edges = new LinearLayout(c);
        edges.setOrientation(LinearLayout.HORIZONTAL);
        edges.setGravity(Gravity.CENTER_VERTICAL);
        edges.setPadding(0, dp(6), 0, dp(6));
        edges.addView(nudge(true, -100), nudgeParams());
        startLabel = AdSkipUi.text(c, "", 14, AdSkipUi.TEXT, true);
        startLabel.setPadding(dp(8), 0, dp(8), 0);
        edges.addView(startLabel);
        edges.addView(nudge(true, 100), nudgeParams());
        lengthLabel = AdSkipUi.text(c, "", 14, AdSkipUi.ACCENT, true);
        lengthLabel.setGravity(Gravity.CENTER);
        edges.addView(lengthLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        edges.addView(nudge(false, -100), nudgeParams());
        endLabel = AdSkipUi.text(c, "", 14, AdSkipUi.TEXT, true);
        endLabel.setPadding(dp(8), 0, dp(8), 0);
        edges.addView(endLabel);
        edges.addView(nudge(false, 100), nudgeParams());
        controls.addView(edges);

        // Playback: the whole video, just the ad, and zoom, one height for all three.
        LinearLayout play = new LinearLayout(c);
        play.setOrientation(LinearLayout.HORIZONTAL);
        play.setGravity(Gravity.CENTER_VERTICAL);
        play.setPadding(0, dp(2), 0, dp(8));
        playButton = AdSkipUi.iconButton(c, AdIcons.Icon.PLAY, L10n.t("Play"), BUTTON_DP);
        playButton.setOnClickListener(v -> togglePlay());
        play.addView(playButton, new LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)));
        playAdButton = new AdSkipUi.IconChip(c, true);
        playAdButton.set(AdIcons.Icon.HEADPHONES, L10n.t("Play ad"));
        playAdButton.setOnClickListener(v -> playSelection());
        LinearLayout.LayoutParams playAdParams = new LinearLayout.LayoutParams(0, dp(BUTTON_DP), 1f);
        playAdParams.leftMargin = dp(10);
        playAdParams.rightMargin = dp(10);
        play.addView(playAdButton, playAdParams);
        zoomButton = AdSkipUi.iconButton(c, AdIcons.Icon.ZOOM_IN, L10n.t("Zoom"), BUTTON_DP);
        zoomButton.setOnClickListener(v -> range.zoom(!range.zoomed()));
        play.addView(zoomButton, new LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)));
        controls.addView(play);

        // Name.
        nameField = new EditText(c);
        nameField.setSingleLine(true);
        nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nameField.setTextColor(AdSkipUi.TEXT);
        nameField.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        nameField.setHintTextColor(AdSkipUi.TEXT_2);
        nameField.setHint(L10n.t("Name"));
        nameField.setText(defaultName());
        nameField.setBackground(AdSkipUi.round(AdSkipUi.SURFACE, dp(12)));
        nameField.setPadding(dp(14), 0, dp(14), 0);
        controls.addView(nameField, new LinearLayout.LayoutParams(-1, dp(44)));

        // Warnings take this line only when there is one.
        hint = AdSkipUi.text(c, "", 12, AdSkipUi.WARN, false);
        hint.setPadding(dp(4), dp(6), dp(4), 0);
        hint.setVisibility(View.GONE);
        controls.addView(hint);

        android.widget.CheckBox shareBox = new android.widget.CheckBox(c);
        shareBox.setText(L10n.t("Share with everyone"));
        shareBox.setTextColor(AdSkipUi.TEXT_2);
        shareBox.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        if (Build.VERSION.SDK_INT >= 21) shareBox.setButtonTintList(android.content.res.ColorStateList.valueOf(AdSkipUi.ACCENT));
        boolean sharedEnabled = app.morphe.extension.tiktok.settings.Settings.AD_SKIP_SHARED_DB.get();
        shareBox.setChecked(sharedEnabled && app.morphe.extension.tiktok.settings.Settings.AD_SKIP_SHARE_AUTO.get());
        shareBox.setEnabled(sharedEnabled);
        shareBox.setAlpha(sharedEnabled ? 1f : 0.4f);
        shareBox.setOnCheckedChangeListener((btn, checked) ->
                app.morphe.extension.tiktok.settings.Settings.AD_SKIP_SHARE_AUTO.save(checked));
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(-2, -2);
        shareParams.topMargin = dp(2);
        controls.addView(shareBox, shareParams);

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
        dialog.setOnDismissListener(d -> release());
        AdSkip.setPaused(true);
        AdSkip.setFeedPlaying(false);
        enableControls(false);
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

    private LinearLayout.LayoutParams nudgeParams() {
        return new LinearLayout.LayoutParams(dp(36), dp(36));
    }

    /** Moves one end by a tenth of a second and shows that frame, without playing. */
    private View nudge(boolean start, long delta) {
        ImageView button = AdSkipUi.iconButton(activity, delta < 0 ? AdIcons.Icon.MINUS : AdIcons.Icon.PLUS,
                (delta < 0 ? "−0.1" : "+0.1"), 36);
        button.setOnClickListener(v -> setEdge(start, (start ? range.start() : range.end()) + delta));
        return button;
    }

    private String defaultName() {
        Object author = Reflect.property(aweme, "getAuthor", "author");
        String nick = Reflect.string(author, "getNickname", "nickname");
        String base = L10n.t("Ad");
        int n = AdSegmentStore.ownCount() + 1;
        return nick == null ? base + " " + n : base + " " + n + " · " + nick;
    }

    private void enableControls(boolean enabled) {
        controls.setAlpha(enabled ? 1f : 0.35f);
        setEnabledDeep(controls, enabled);
        nameField.setEnabled(true);
    }

    private static void setEnabledDeep(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setEnabledDeep(group.getChildAt(i), enabled);
        }
    }

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
                });
            }
        });
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
                spinner.setVisibility(View.GONE);
                status.setVisibility(View.GONE);
                enableControls(true);
                AdSkipUi.enable(saveButton, true);
                seek(start);
                main.post(ticker);
            });
            picture.prepareAsync();
        } catch (Exception failure) {
            Logger.printException(() -> "Ad skip: could not play the video in the editor", failure);
            spinner.setVisibility(View.GONE);
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

    /*
     * A video whose sound comes as its own file plays on two players: the picture muted and the
     * sound beside it. The sound is the clock: it is never moved while it plays, only the picture
     * is brought back to it, so what is heard never stutters. Every play starts only after both
     * players report they reached the position, so the first moment heard is the right one.
     */

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
            return;
        }
        long from = currentMs();
        if (from >= durationMs - 100) from = 0;
        playFrom(from);
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

    /** After a handle lets go, a second and a half of sound from that edge. */
    private void previewAround(long ms) {
        if (!prepared) return;
        boolean isEnd = Math.abs(ms - range.end()) < 5;
        long from = isEnd ? Math.max(range.start(), ms - 1500) : ms;
        stopAt = isEnd ? ms : Math.min(range.end(), ms + 1500);
        returnTo = ms;
        playFrom(from);
    }

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
    }

    // endregion

    // region saving

    private void save() {
        if (!prepared || print == null) return;
        long s = range.start(), e = range.end();
        Landmarks.Print cut = print.slice(Landmarks.msToFrame(s), Landmarks.msToFrame(e));
        if (cut.count < MIN_HASHES) {
            warn(L10n.t("There's too little sound here to recognise. Choose a louder part."));
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
        AdScanner.forget(aid);
        hideKeyboard();
        dialog.dismiss();
        Utils.showToastLong(found
                ? L10n.f("Saved \"%1$s\". This ad will be skipped in other videos.", name)
                : L10n.f("Saved \"%1$s\", but its sound is weak. A longer or louder cut is found more reliably.", name));
    }

    private void warn(String message) {
        hint.setText(message);
        hint.setVisibility(View.VISIBLE);
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

    private void release() {
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
