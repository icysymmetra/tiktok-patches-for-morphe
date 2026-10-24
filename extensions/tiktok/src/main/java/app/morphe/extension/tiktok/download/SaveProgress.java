/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.app.Activity;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.content.res.ColorStateList;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A running count for several files and optional stream progress for a video save.
 *
 * <p>Saving a dozen original photos, a story's photos or a video with its sound and subtitles
 * ran fire-and-toast: a slow save looked like a failed one, and nothing short of killing TikTok
 * could stop it. The default row starts at {@link #MIN_FILES}; original-photo saves count
 * every file. The row offers Cancel, which lets the file under way finish and leaves the rest.
 * The row is read out once when it appears, once when Cancel is pressed and the result once at
 * the end, not on every file; the count changes silently in between.
 *
 * <p>A save that has to wait for the media queue shows its row as it is accepted, waiting, with a
 * Cancel that takes it out of line; the row turns into the count when the save starts, and says
 * so once. More than {@link #MAX_ROWS} rows at once keep the rest out of sight behind a single
 * line that counts them, so a long queue can't cover the video.
 */
final class SaveProgress {
    /** The default count row starts here; optional stream progress also covers smaller saves. */
    static final int MIN_FILES = 3;
    /** Room under the row for a banner (a block's Undo, a finished save) to appear beneath it. */
    private static final int ABOVE_BANNER_DP = 56;
    /** Space between simultaneous saves, in addition to each measured row height. */
    private static final int ROW_GAP_DP = 8;
    /** Rows shown at once. The media queue runs three saves, so the rest are waiting anyway. */
    static final int MAX_ROWS = 3;
    /** The rows up right now, main thread only, so two saves at once do not share pixels. */
    private static final List<View> LIVE_ROWS = new ArrayList<>();
    /** The line counting the rows past {@link #MAX_ROWS}, above the highest row shown. Main thread only. */
    private static TextView overflow;
    /** The line with its Cancel, the view that is placed. Main thread only. */
    private static LinearLayout overflowRow;
    /** Whose row each live row is, so the line's Cancel can reach a save out of sight. Main thread only. */
    private static final WeakHashMap<View, SaveProgress> OWNERS = new WeakHashMap<>();
    /** How many rows it counted last, so it speaks when the count grows and not when it falls. */
    private static int overflowCount;
    /** For the tests: what the row said aloud, in order. Null keeps it to the phone. */
    static volatile List<String> announcementsForTests;

    interface Step {
        /** Saves file {@code index}, counted from 0; throwing skips that file. */
        void save(int index) throws IOException;
    }

    /** Why a run ended before its last file when it was not Cancel. */
    enum Stop { NONE, NO_SPACE, NO_TIME, SERVER_WAIT }

    /** What a run of steps came to. */
    static final class Outcome {
        final int total;
        final int saved;
        final int skipped;
        final int cancelled;
        final Stop stop;
        /**
         * The files that didn't land through no choice of the reader's, in run order: each one
         * that failed, and with a {@link #stop} the file it stopped at and every one after it.
         * Files left after a Cancel aren't here, so a retry never brings back what was cancelled.
         */
        final List<Integer> unsaved;

        Outcome(int total, int saved, int skipped, int cancelled) {
            this(total, saved, skipped, cancelled, Stop.NONE);
        }

        Outcome(int total, int saved, int skipped, int cancelled, Stop stop) {
            this(total, saved, skipped, cancelled, stop, java.util.Collections.<Integer>emptyList());
        }

        Outcome(int total, int saved, int skipped, int cancelled, Stop stop, List<Integer> unsaved) {
            this.total = total;
            this.saved = saved;
            this.skipped = skipped;
            this.cancelled = cancelled;
            this.stop = stop;
            this.unsaved = java.util.Collections.unmodifiableList(new ArrayList<>(unsaved));
        }

        boolean complete() {
            return saved == total;
        }
    }

    private final int total;
    private final boolean showTransfer;
    /** Count every file, including one, and offer Cancel below the default row threshold. */
    private final boolean fileCount;
    /** The queued job this row follows, for Cancel while it waits; null for a row with none. */
    private volatile MediaJobScheduler.Job job;
    /** False from {@link #queued} until {@link #run}: the row says the save is waiting. */
    private volatile boolean started;
    /** The row came up waiting, so its start is said once when it comes. */
    private volatile boolean shownWaiting;
    private volatile TextView stopButton;
    private volatile int current = 1;
    private volatile int percent = -1;
    /**
     * The file under way is having its caption written in. That step stops at once on Cancel
     * rather than after the file, so a single video's row offers Cancel while it runs.
     */
    private volatile boolean captioning;
    private volatile ProgressBar transferBar;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile boolean finished;
    private volatile View row;
    private volatile TextView count;

    private SaveProgress(int total, boolean showTransfer, boolean started, boolean fileCount) {
        this.total = total;
        this.showTransfer = showTransfer;
        this.started = started;
        this.fileCount = fileCount;
    }

    /** A progress row for {@code total} files, or a silent one below {@link #MIN_FILES}. */
    static SaveProgress begin(int total) {
        return begin(total, false);
    }

    static SaveProgress begin(int total, boolean showTransfer) {
        SaveProgress progress = new SaveProgress(total, showTransfer, true, false);
        if (progress.showsRow()) progress.show();
        return progress;
    }

    /** A row for a save about to join the media queue. Nothing shows until {@link #submit}. */
    static SaveProgress queued(int total, boolean showTransfer) {
        return new SaveProgress(total, showTransfer, false, false);
    }

    /** A file-count row with Cancel for every save, without a video stream progress bar. */
    static SaveProgress queuedFiles(int total) {
        return new SaveProgress(total, false, false, true);
    }

    /** Default threshold, optional stream progress, or an explicitly requested file count. */
    boolean showsRow() {
        return fileCount || total >= MIN_FILES || showTransfer;
    }

    /**
     * Queues {@code work} with this row following it: waiting with Cancel while it is in line,
     * counting once {@link #run} starts, and taken down when the job is over however it ends.
     * {@code done} runs after that, exactly once. Null when the line is full, and no row shows.
     */
    MediaJobScheduler.Job submit(String label, String key, Runnable work, Runnable done) {
        return submit(label, key, total, work, done);
    }

    /**
     * As above, for a save that writes {@code files} files while its row counts {@link #total}:
     * a story's video counts one on the row, but its sound goes to the gallery beside it.
     */
    MediaJobScheduler.Job submit(String label, String key, int files, Runnable work, Runnable done) {
        MediaJobScheduler.Job queued = MediaJobScheduler.submit(label, key, Math.max(files, total), work, () -> {
            dismiss();
            if (done != null) done.run();
        });
        if (queued == null) return null;
        job = queued;
        if (showsRow()) show();
        return queued;
    }

    /**
     * The word the save gets as it is accepted. A row shows its own wait, so only
     * {@code starting} is said beside it; without a row the toast carries the wait too.
     */
    void acknowledge(String starting, String waiting) {
        MediaJobScheduler.Job queued = job;
        if (queued == null) return;
        if (!showsRow()) MediaJobScheduler.acknowledge(queued, starting, waiting);
        else if (starting != null) Utils.showToastShort(starting);
    }

    /** The current stream, not an estimate of later streams, muxing or publication. */
    void transfer(long bytes, long expected) {
        if (!showTransfer || finished || cancelled.get()) return;
        int next = expected <= 0 ? -1 : (int) Math.max(0, Math.min(99, bytes * 100.0 / expected));
        if (next == percent) return;
        percent = next;
        updateTransfer();
    }

    /**
     * How far writing the caption into the video has got, {@code done} of {@code expected}
     * microseconds of it. It encodes the whole video again, the slow part of such a save, so the
     * row says what it's doing rather than "Saving video", and offers Cancel for it.
     */
    void captioning(long done, long expected) {
        if (finished || cancelled.get()) return;
        int next = expected <= 0 ? -1 : (int) Math.max(0, Math.min(99, done * 100.0 / expected));
        if (captioning && next == percent) return;
        captioning = true;
        percent = next;
        updateTransfer();
    }

    /** The caption step is over, written in or given up, so the row says it's saving again. */
    void captionDone() {
        if (!captioning) return;
        captioning = false;
        percent = -1;
        updateTransfer();
    }

    private String progressText() {
        if (!started && fileCount) return L10n.quantity(Utils.getContext(), total,
                "Waiting to save one file", "Waiting to save %1$s files");
        if (!started) return total == 1 ? L10n.t("Waiting to save video")
                : L10n.f("Waiting to save %1$s files", String.valueOf(total));
        if (captioning) return percent < 0 ? L10n.t("Writing the caption on the video")
                : L10n.f("Writing the caption on the video: %1$s",
                        java.text.NumberFormat.getPercentInstance().format(percent / 100.0));
        if (percent < 0) return total == 1 && !fileCount ? L10n.t("Saving video")
                : L10n.f("Saving %1$s of %2$s", String.valueOf(current), String.valueOf(total));
        String value = java.text.NumberFormat.getPercentInstance().format(percent / 100.0);
        return total == 1 ? L10n.f("Saving video: %1$s", value)
                : L10n.f("Saving %1$s of %2$s: %3$s", String.valueOf(current), String.valueOf(total), value);
    }

    private void updateTransfer() {
        Utils.runOnMainThread(() -> {
            TextView label = count;
            if (label == null || cancelled.get() || finished) return;
            String text = progressText();
            label.setText(text);
            ProgressBar bar = transferBar;
            if (bar != null) {
                bar.setIndeterminate(percent < 0);
                if (percent >= 0) bar.setProgress(percent);
            }
            if (!started) return;
            TextView button = stopButton;
            // Single-video stream progress keeps its existing no-Cancel behavior while running,
            // except while the caption is written in, which Cancel does stop.
            if (button != null && total == 1 && !fileCount) button.setVisibility(captioning ? View.VISIBLE : View.GONE);
            View shown = row;
            if (shownWaiting && shown != null && shown.getVisibility() == View.VISIBLE) {
                shownWaiting = false;
                announce(label, text);
            }
        });
    }

    int total() {
        return total;
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Stops after the file under way without a word, for a save that failed: its own message
     * says so, and "Stopping after this file" read aloud over it named a file that never came.
     */
    void stop() {
        cancelled.set(true);
    }

    /** Stops after the file under way. The row says so, aloud once, until that file is done. */
    void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        // Writing the caption in stops now, not after the file, and the save's own word on it
        // follows at once, so "Stopping after this file" would name a file that never comes.
        if (captioning) return;
        Utils.runOnMainThread(() -> {
            TextView view = count;
            if (view == null) return;
            String text = L10n.t("Stopping after this file");
            view.setText(text);
            announce(view, text);
        });
    }

    /**
     * Runs {@code step} for each file until cancelled, counting what came of each, and takes
     * the row down. A step that throws is a skipped file, not the end of the save: the photos
     * after a bad one still land, and the result says how many did not. A refusal from the disk
     * or the clock is the end of it, since every file after would fail the same way.
     *
     * <p>Each file gets the job's full time from the moment it starts (#27). One deadline for
     * the whole run let a 50-video save stop a few files in with "the rest ran out of time",
     * though every file was moving; a single file that stalls still runs out on its own.
     */
    Outcome run(Step step) {
        started = true;
        Outcome[] result = new Outcome[1];
        try {
            MediaBudget.runWithJobDeadline(() -> {
                MediaBudget.Deadline deadline = MediaBudget.deadline();
                deadline.cancellation = cancelled;
                int saved = 0, skipped = 0, index = 0;
                Stop stop = Stop.NONE;
                List<Integer> unsaved = new ArrayList<>();
                for (; index < total; index++) {
                    if (isCancelled()) break;
                    showCount(index + 1);
                    try {
                        if (deadline.networkStop != null) throw deadline.networkStop;
                        deadline.allowAtLeast(MediaBudget.JOB_DEADLINE_MS);
                        step.save(index);
                        saved++;
                    } catch (MediaBudget.StopException refusal) {
                        int which = index + 1;
                        Logger.printException(() -> "The save stopped at file " + which + " of " + total, refusal);
                        switch (refusal.reason) {
                            case SPACE: stop = Stop.NO_SPACE; break;
                            case TIME: stop = Stop.NO_TIME; break;
                            case SERVER_WAIT: stop = Stop.SERVER_WAIT; break;
                            case CANCELLED: break;
                        }
                        if (stop != Stop.NONE) for (int rest = index; rest < total; rest++) unsaved.add(rest);
                        break;
                    } catch (IOException | RuntimeException failure) {
                        int which = index + 1;
                        Logger.printException(() -> "File " + which + " of " + total + " was not saved", failure);
                        skipped++;
                        unsaved.add(index);
                    }
                }
                result[0] = new Outcome(total, saved, skipped, total - index, stop, unsaved);
            });
        } finally {
            dismiss();
        }
        return result[0];
    }

    /** {@code complete} when every file landed, otherwise what did and what did not, and why. */
    static String message(Outcome outcome, String complete) {
        if (outcome.complete()) return complete;
        String saved = String.valueOf(outcome.saved);
        String total = String.valueOf(outcome.total);
        String skipped = String.valueOf(outcome.skipped);
        if (outcome.stop == Stop.NO_SPACE) return L10n.f("Saved %1$s of %2$s, the rest need more free space", saved, total);
        if (outcome.stop == Stop.NO_TIME) return L10n.f("Saved %1$s of %2$s, the rest ran out of time", saved, total);
        if (outcome.stop == Stop.SERVER_WAIT) return L10n.f("Saved %1$s of %2$s. The server asked us to wait. Try again later.", saved, total);
        if (outcome.cancelled > 0 && outcome.skipped > 0) {
            return L10n.f("Saved %1$s of %2$s, %3$s skipped and the rest cancelled", saved, total, skipped);
        }
        if (outcome.cancelled > 0) return L10n.f("Saved %1$s of %2$s, the rest cancelled", saved, total);
        return L10n.f("Saved %1$s of %2$s, %3$s skipped", saved, total, skipped);
    }

    private void show() {
        // Not at once: a save starts as TikTok swaps its share sheet away, and a row put on the
        // window on top at that instant goes with the sheet, unseen (the save banner learnt this
        // first and waits the same). A save that is over inside the wait never gets a row.
        Utils.runOnMainThreadDelayed(() -> {
            if (finished) return;
            try {
                Activity activity = Utils.getActivity();
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                ViewGroup root = SaveNotice.topWindowRoot(activity);
                if (root == null) root = activity.findViewById(android.R.id.content);
                if (root == null) return;

                LinearLayout banner = new LinearLayout(activity);
                banner.setOrientation(LinearLayout.HORIZONTAL);
                banner.setGravity(Gravity.CENTER_VERTICAL);
                banner.setPadding(SettingsUi.dp(activity, 16), SettingsUi.dp(activity, 12),
                        SettingsUi.dp(activity, 16), SettingsUi.dp(activity, 12));
                banner.setBackground(SettingsUi.overlayBanner(activity));
                banner.setTag("hushfeed_save_progress");

                shownWaiting = !started;
                TextView label = new TextView(activity);
                String text = progressText();
                label.setText(text);
                label.setTextColor(SettingsUi.OVERLAY_TEXT);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY_SMALL);
                if (showTransfer) {
                    LinearLayout body = new LinearLayout(activity);
                    body.setOrientation(LinearLayout.VERTICAL);
                    body.addView(label, new LinearLayout.LayoutParams(-1, -2));
                    ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
                    bar.setMax(100);
                    bar.setIndeterminate(percent < 0);
                    if (percent >= 0) bar.setProgress(percent);
                    bar.setProgressTintList(ColorStateList.valueOf(SettingsUi.OVERLAY_ACCENT));
                    bar.setIndeterminateTintList(ColorStateList.valueOf(SettingsUi.OVERLAY_ACCENT));
                    // The adjacent text gives its value to TalkBack without per-byte announcements.
                    bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, SettingsUi.dp(activity, 4));
                    barParams.topMargin = SettingsUi.dp(activity, 8);
                    body.addView(bar, barParams);
                    transferBar = bar;
                    banner.addView(body, new LinearLayout.LayoutParams(0, -2, 1f));
                } else {
                    banner.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
                }

                TextView stop = cancelButton(activity);
                stop.setOnClickListener(view -> cancelFromRow());
                // While the save waits, Cancel takes it out of line. Once it runs, Cancel stops
                // after the current file. File-count jobs keep the control for every count.
                if (started && total == 1 && !fileCount && !captioning) stop.setVisibility(View.GONE);
                banner.addView(stop, new LinearLayout.LayoutParams(-2, -2));
                stopButton = stop;

                // Above where a banner goes, so a finished save or a block's Undo can show
                // beneath a save still running, and above any row already up.
                FrameLayout.LayoutParams params = BlockAuthorOverlay.bannerParams(activity, root);
                params.bottomMargin += SettingsUi.dp(activity, ABOVE_BANNER_DP);
                banner.setLayoutParams(params);
                // Should the window it sits on go away with the save still running (a sheet
                // closing late), the row moves to the activity's own content.
                banner.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View view) {
                    }

                    @Override public void onViewDetachedFromWindow(View view) {
                        if (row != view) return;
                        Utils.runOnMainThread(() -> rehome(activity, view));
                    }
                });
                banner.addOnLayoutChangeListener((view, left, top, right, bottom,
                        oldLeft, oldTop, oldRight, oldBottom) -> {
                    if (bottom - top != oldBottom - oldTop) placeRows();
                });
                root.addView(banner);
                LIVE_ROWS.add(banner);
                OWNERS.put(banner, this);
                row = banner;
                count = label;
                placeRows();
                // Said once here, by hand; the count is not a live region, so the files that
                // follow change it without a word, and the result is the banner's to announce.
                // A row past the cap is said when it comes into sight instead.
                if (banner.getVisibility() == View.VISIBLE) announce(banner, text);
            } catch (Throwable failure) {
                Logger.printException(() -> "Could not show the save progress row", failure);
            }
        }, SaveNotice.SHEET_SETTLE_MS);
    }

    private void rehome(Activity activity, View view) {
        if (row != view || activity.isFinishing() || activity.isDestroyed()) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || view.getParent() == content) return;
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        content.addView(view);
        placeRows();
    }

    /**
     * Stacks the rows up from above the banner. A translated label or larger text can make a
     * row taller than the default spacing, so each sits on the measured heights below it. The
     * first {@link #MAX_ROWS} show; the rest wait out of sight, counted by one line on top, and
     * come into sight in order as the rows below them go.
     */
    private static void placeRows() {
        int shown = 0;
        int hidden = 0;
        View highest = null;
        int aboveHighest = 0;
        for (View view : LIVE_ROWS) {
            // A row left on a screen that has closed (rehome gives up on a finishing activity)
            // can't be seen, so it takes no place: counted, it kept a new save's row out of sight
            // behind rows nobody could see until their saves ended.
            if (!(view.getParent() instanceof ViewGroup) || !view.isAttachedToWindow()) continue;
            if (shown == MAX_ROWS) {
                view.setVisibility(View.GONE);
                hidden++;
                continue;
            }
            shown++;
            if (view.getVisibility() != View.VISIBLE) {
                view.setVisibility(View.VISIBLE);
                CharSequence text = labelOf(view);
                if (text != null) announce(view, text.toString());
            }
            ViewGroup root = (ViewGroup) view.getParent();
            Activity activity = (Activity) view.getContext();
            int gap = SettingsUi.dp(activity, ROW_GAP_DP);
            int bottom = BlockAuthorOverlay.bannerParams(activity, root).bottomMargin
                    + SettingsUi.dp(activity, ABOVE_BANNER_DP);
            for (View previous : LIVE_ROWS) {
                if (previous == view) break;
                if (previous.getParent() == root && previous.getVisibility() == View.VISIBLE) {
                    bottom += previous.getHeight() + gap;
                }
            }
            setBottomMargin(view, bottom);
            highest = view;
            aboveHighest = bottom + view.getHeight() + gap;
        }
        placeOverflow(highest, aboveHighest, hidden);
    }

    /**
     * The one line that stands for every row out of sight, or none when all of them show.
     *
     * <p>It has a Cancel of its own. The queue runs three saves and the first three rows are
     * theirs, so a save in line always has its row out of sight, and its row's Cancel with it:
     * on a phone a fourth save could never be taken out of line. This Cancel takes the last
     * save in line out; pressed again, the one before it.
     */
    private static void placeOverflow(View highest, int bottom, int hidden) {
        TextView line = overflow;
        LinearLayout box = overflowRow;
        if (hidden == 0 || highest == null) {
            if (box != null && box.getParent() instanceof ViewGroup) ((ViewGroup) box.getParent()).removeView(box);
            overflow = null;
            overflowRow = null;
            overflowCount = 0;
            return;
        }
        ViewGroup root = (ViewGroup) highest.getParent();
        Activity activity = (Activity) highest.getContext();
        if (box == null || line == null || box.getContext() != activity) {
            if (box != null && box.getParent() instanceof ViewGroup) ((ViewGroup) box.getParent()).removeView(box);
            box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);
            box.setTag("hushfeed_save_waiting_row");
            box.setPadding(SettingsUi.dp(activity, 16), 0, SettingsUi.dp(activity, 16), 0);
            box.setBackground(SettingsUi.overlayBanner(activity));
            line = new TextView(activity);
            line.setTag("hushfeed_save_waiting");
            line.setTextColor(SettingsUi.OVERLAY_TEXT);
            line.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY_SMALL);
            line.setPadding(0, SettingsUi.dp(activity, 8), 0, SettingsUi.dp(activity, 8));
            box.addView(line, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView stop = cancelButton(activity);
            stop.setOnClickListener(view -> cancelLastInLine());
            box.addView(stop, new LinearLayout.LayoutParams(-2, -2));
            overflow = line;
            overflowRow = box;
        }
        if (box.getParent() != root) {
            if (box.getParent() instanceof ViewGroup) ((ViewGroup) box.getParent()).removeView(box);
            root.addView(box, BlockAuthorOverlay.bannerParams(activity, root));
        }
        String text = L10n.quantity(activity, hidden, "One more save waiting", "%1$s more saves waiting");
        line.setText(text);
        setBottomMargin(box, bottom);
        if (hidden > overflowCount) announce(line, text);
        overflowCount = hidden;
    }

    /** The line's Cancel: the last save still in line, among the rows out of sight, leaves it. */
    private static void cancelLastInLine() {
        for (int index = LIVE_ROWS.size() - 1; index >= 0; index--) {
            View view = LIVE_ROWS.get(index);
            if (view.getVisibility() != View.GONE) continue;
            SaveProgress owner = OWNERS.get(view);
            MediaJobScheduler.Job waitingOn = owner == null ? null : owner.job;
            if (waitingOn != null && waitingOn.cancel()) {
                // The job's end has taken the row down, and placeRows the line with it.
                Utils.showToastShort(L10n.t("Save cancelled. Nothing was saved."));
                return;
            }
        }
    }

    /** A row's Cancel: an accent label on the overlay's action background, sized for a thumb. */
    private static TextView cancelButton(Activity activity) {
        TextView stop = new TextView(activity);
        String cancelLabel = L10n.t(activity, "Cancel");
        stop.setText(cancelLabel);
        stop.setContentDescription(cancelLabel);
        stop.setTextColor(SettingsUi.OVERLAY_ACCENT);
        stop.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY_SMALL);
        stop.setPadding(SettingsUi.dp(activity, 16), SettingsUi.dp(activity, 12),
                SettingsUi.dp(activity, 16), SettingsUi.dp(activity, 12));
        stop.setMinimumHeight(SettingsUi.dp(activity, 48));
        stop.setMinimumWidth(SettingsUi.dp(activity, 48));
        stop.setGravity(Gravity.CENTER);
        stop.setBackground(SettingsUi.overlayAction(activity, SettingsUi.RADIUS_OVERLAY));
        stop.setFocusable(true);
        SettingsUi.markAsButton(stop);
        return stop;
    }

    /**
     * Lifts a row to {@code bottom}. The top window can have any ViewGroup at its root, and one
     * that isn't a FrameLayout swaps the banner's params for its own kind as the row goes in, so
     * a cast back to FrameLayout's threw on the next pass here, which a row's end runs outside
     * any catch. A row whose params carry no margin stays where that layout puts it.
     */
    private static void setBottomMargin(View view, int bottom) {
        if (!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        if (params.bottomMargin == bottom) return;
        params.bottomMargin = bottom;
        view.setLayoutParams(params);
    }

    /** What a row says: its label, which sits first in it or first in its body. */
    private static CharSequence labelOf(View row) {
        View first = row instanceof ViewGroup ? ((ViewGroup) row).getChildAt(0) : null;
        if (first instanceof ViewGroup) first = ((ViewGroup) first).getChildAt(0);
        return first instanceof TextView ? ((TextView) first).getText() : null;
    }

    /**
     * Cancel on the row. A save still in line is taken out of it and never starts; one that has
     * started stops after the file under way. File-count jobs retain that control at every
     * count; the single-video stream row keeps its existing no-Cancel behavior while running,
     * apart from the caption step, which stops at once.
     */
    private void cancelFromRow() {
        MediaJobScheduler.Job waitingOn = job;
        if (waitingOn != null && waitingOn.cancel()) {
            // The job's end has taken the row down and let go of the save.
            Utils.showToastShort(L10n.t("Save cancelled. Nothing was saved."));
            return;
        }
        if (total > 1 || fileCount || captioning) cancel();
    }

    private void showCount(int current) {
        this.current = current;
        captioning = false;
        percent = -1;
        updateTransfer();
    }

    /** Takes the row down for good, or keeps it from ever coming up. Harmless twice. */
    void dismiss() {
        finished = true;
        Utils.runOnMainThread(() -> {
            View view = row;
            row = null;
            count = null;
            transferBar = null;
            stopButton = null;
            if (view == null) return;
            LIVE_ROWS.remove(view);
            if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
            placeRows();
        });
    }

    private static void announce(View view, String text) {
        List<String> log = announcementsForTests;
        if (log != null) log.add(text);
        view.announceForAccessibility(text);
    }
}
