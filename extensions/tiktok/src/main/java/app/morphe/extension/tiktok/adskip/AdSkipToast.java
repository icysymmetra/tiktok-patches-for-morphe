/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

import java.lang.ref.WeakReference;

/** Floating skip notice pill with countdown ring, Watch-it action, and vote controls. */
final class AdSkipToast {
    private static final long VISIBLE_MS = KawaiiLottie.MIN_VISIBLE_MS;
    private static final float BASE_ALPHA = 0.80f;
    private static final float FADE_HEAD_FRACTION = 0.14f;
    private static final float FADE_TAIL_FRACTION = 0.20f;
    private static final int ABOVE_NAME_PX = 20;
    private static final int FALLBACK_BOTTOM_PX = 200;
    private static final String NAME_ID = "title";
    private static final String BAR_ID = "video_seek_bar";
    private static final int UP_COLOR = 0xFF2BD96B;
    private static final int DOWN_COLOR = 0xFFFF6B6B;

    private static WeakReference<View> shown = new WeakReference<>(null);
    private static int generation;

    private AdSkipToast() {}

    static void show(String name, String serverId, @Nullable Runnable watch) {
        Utils.runOnMainThread(() -> {
            try {
                Activity activity = Utils.getActivity();
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                ViewGroup root = activity.findViewById(android.R.id.content);
                if (root == null) return;
                dismiss();
                int token = ++generation;
                Pill pill = build(activity, name, serverId, watch);
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                params.bottomMargin = bottomMargin(activity, root);
                root.addView(pill, params);
                pill.setAlpha(0f);
                shown = new WeakReference<>(pill);
                pill.countDown(VISIBLE_MS, () -> {
                    if (token == generation) dismiss();
                });
            } catch (Throwable failure) {
                Logger.printException(() -> "Ad skip: could not show the notice", failure);
            }
        });
    }

    /** From the root's bottom to 20 px above the playing video's creator name. */
    private static int bottomMargin(Activity activity, ViewGroup root) {
        int[] rootAt = new int[2];
        root.getLocationOnScreen(rootAt);
        int rootBottom = rootAt[1] + root.getHeight();
        View name = shownNamed(root, id(activity, NAME_ID));
        if (name != null) {
            int[] at = new int[2];
            name.getLocationOnScreen(at);
            int margin = rootBottom - at[1] + ABOVE_NAME_PX;
            if (margin > 0 && margin < root.getHeight() * 0.8f) return margin;
        }
        View bar = shownNamed(root, id(activity, BAR_ID));
        if (bar != null) {
            int[] at = new int[2];
            bar.getLocationOnScreen(at);
            int margin = rootBottom - at[1] + FALLBACK_BOTTOM_PX;
            if (margin > 0 && margin < root.getHeight() * 0.8f) return margin;
        }
        return Math.round(root.getHeight() * 0.25f);
    }

    private static int id(Activity activity, String name) {
        return activity.getResources().getIdentifier(name, "id", activity.getPackageName());
    }

    /** Finds the view with {@code id} currently visible on screen. */
    @Nullable
    private static View shownNamed(ViewGroup root, int id) {
        if (id == 0) return null;
        int width = root.getResources().getDisplayMetrics().widthPixels;
        int height = root.getResources().getDisplayMetrics().heightPixels;
        return find(root, id, width, height, new Rect());
    }

    @Nullable
    private static View find(View view, int id, int width, int height, Rect rect) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view.getId() == id && view.getGlobalVisibleRect(rect)
                && rect.left >= 0 && rect.right <= width && rect.top >= 0 && rect.bottom <= height) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), id, width, height, rect);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Pill build(Activity activity, String name, String serverId, @Nullable Runnable watch) {
        Pill pill = new Pill(activity);
        pill.setPadding(dp(activity, 6), dp(activity, 5), dp(activity, watch == null ? 14 : 6), dp(activity, 5));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xC0151517);
        background.setCornerRadius(dp(activity, 22));
        background.setStroke(Math.max(1, dp(activity, 0.75f)), 0x18FFFFFF);
        pill.setBackground(background);
        pill.setElevation(dp(activity, 5));
        pill.setContentDescription(L10n.f("Ad skipped: %1$s", name));

        pill.addView(pill.ring, new LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 36)));

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(activity, 9), 0, dp(activity, 8), 0);
        TextView caption = new TextView(activity);
        caption.setText(L10n.t("Ad skipped"));
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        caption.setTextColor(AdSkipBar.MARK);
        caption.setTypeface(Typeface.DEFAULT_BOLD);
        caption.setLetterSpacing(0.02f);
        caption.setIncludeFontPadding(false);
        text.addView(caption);
        TextView label = new TextView(activity);
        label.setText(name);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        label.setTextColor(0xF2FFFFFF);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setMaxWidth(dp(activity, 168));
        label.setIncludeFontPadding(false);
        label.setPadding(0, dp(activity, 3), 0, 0);
        text.addView(label);
        pill.addView(text, centred());

        if (watch != null) {
            LinearLayout action = new LinearLayout(activity);
            action.setOrientation(LinearLayout.HORIZONTAL);
            action.setGravity(Gravity.CENTER);
            action.setPadding(dp(activity, 9), dp(activity, 7), dp(activity, 11), dp(activity, 7));
            GradientDrawable fill = new GradientDrawable();
            fill.setColor(0x242BD96B);
            fill.setCornerRadius(dp(activity, 16));
            action.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), fill, null));
            ImageView icon = new ImageView(activity);
            icon.setImageDrawable(AdIcons.of(activity, AdIcons.Icon.REPLAY, AdSkipBar.MARK, 15));
            action.addView(icon, new LinearLayout.LayoutParams(dp(activity, 15), dp(activity, 15)));
            TextView words = text14(activity, L10n.t("Watch it"), AdSkipBar.MARK, true);
            words.setPadding(dp(activity, 5), 0, 0, 0);
            action.addView(words, centred());
            action.setContentDescription(L10n.t("Watch it"));
            action.setOnClickListener(v -> {
                dismiss();
                try { watch.run(); }
                catch (Throwable failure) { Logger.printException(() -> "Ad skip: could not go back to the ad", failure); }
            });
            pill.addView(action, centred());
        }

        if (SharedDb.canVote(serverId)) {
            pill.addView(divider(activity));
            ImageView up = voteButton(activity);
            ImageView down = voteButton(activity);
            showVote(activity, up, down, SharedDb.myVote(serverId));
            up.setOnClickListener(v -> castVote(activity, serverId, 1, up, down));
            down.setOnClickListener(v -> castVote(activity, serverId, -1, up, down));
            up.setContentDescription(L10n.t("Correct"));
            down.setContentDescription(L10n.t("Wrong"));
            pill.addView(up);
            pill.addView(down);
        }
        return pill;
    }

    /**
     * The pill itself. It runs its own countdown so a finger can hold it: touching anywhere on it
     * stops the ring, letting go starts it again, and a sideways swipe throws it off the screen.
     */
    static final class Pill extends LinearLayout {
        final Ring ring;
        private final int slop;
        private Runnable onTimeUp;
        private long total = 1, remaining, resumedAt;
        private boolean running;
        private float downX, downY;
        private boolean swiping;

        Pill(Activity activity) {
            super(activity);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER);
            setClickable(true);
            ring = new Ring(activity);
            slop = android.view.ViewConfiguration.get(activity).getScaledTouchSlop();
        }

        /**
         * Goes after ms of time on screen, not counting time held. The deadline runs on the
         * clock, not on the animation, so a phone with animations turned off still shows the
         * notice for the whole time; the ring only draws it.
         */
        void countDown(long ms, Runnable done) {
            onTimeUp = done;
            total = ms;
            remaining = ms;
            resumeTimer();
        }

        private final Runnable expire = () -> {
            remaining = 0;
            if (onTimeUp != null) onTimeUp.run();
        };

        private void resumeTimer() {
            if (onTimeUp == null || remaining <= 0 || running) return;
            running = true;
            resumedAt = android.os.SystemClock.uptimeMillis();
            postDelayed(expire, remaining);
            if (ring.drawing != null) ring.drawing.cancel();
            ValueAnimator draw = ValueAnimator.ofFloat(remaining / (float) total, 0f);
            draw.setDuration(remaining);
            draw.setInterpolator(new LinearInterpolator());
            draw.addUpdateListener(a -> {
                float left = (float) a.getAnimatedValue();
                ring.setLeft(left);
                if (!swiping) {
                    float elapsed = 1f - left;
                    if (elapsed < FADE_HEAD_FRACTION) {
                        float t = Math.max(0f, elapsed / FADE_HEAD_FRACTION);
                        setAlpha(BASE_ALPHA * t);
                    } else if (left < FADE_TAIL_FRACTION) {
                        float t = Math.max(0f, left / FADE_TAIL_FRACTION);
                        setAlpha(BASE_ALPHA * t);
                    } else if (getAlpha() != BASE_ALPHA) {
                        setAlpha(BASE_ALPHA);
                    }
                }
            });
            ring.drawing = draw;
            draw.start();
        }

        private void pauseTimer() {
            pauseTimer(true);
        }

        private void pauseTimer(boolean restoreAlpha) {
            if (!running) return;
            running = false;
            removeCallbacks(expire);
            remaining = Math.max(0, remaining - (android.os.SystemClock.uptimeMillis() - resumedAt));
            if (ring.drawing != null) ring.drawing.cancel();
            ring.setLeft(remaining / (float) total);
            // Restore full BASE_ALPHA only when the reader touches the pill, never on natural expiry/dismiss!
            if (restoreAlpha && !swiping) setAlpha(BASE_ALPHA);
        }

        void stopTimer() {
            pauseTimer(false);
            onTimeUp = null;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) pauseTimer(true);
            else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) resumeTimer();
            return super.dispatchTouchEvent(event);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stopTimer();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    swiping = false;
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                    if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                        swiping = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                    return false;
                }
                default:
                    return false;
            }
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float dx = event.getRawX() - downX;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    swiping = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!swiping && Math.abs(dx) > slop) {
                        swiping = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (swiping) {
                        setTranslationX(dx);
                        setAlpha(Math.max(0.16f, BASE_ALPHA * (1f - Math.abs(dx) / Math.max(1f, getWidth()))));
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (swiping && Math.abs(dx) > getWidth() * 0.3f) {
                        stopTimer();
                        animate().translationX(Math.signum(dx) * getWidth() * 1.2f).alpha(0f).setDuration(160)
                                .withEndAction(() -> {
                                    if (shown.get() == this) shown = new WeakReference<>(null);
                                    ViewGroup parent = (ViewGroup) getParent();
                                    if (parent != null) parent.removeView(this);
                                }).start();
                    } else if (swiping) {
                        animate().translationX(0).alpha(BASE_ALPHA).setDuration(180).start();
                    }
                    swiping = false;
                    return true;
                default:
                    return true;
            }
        }
    }

    /** The Kawaii Lottie emoji on a soft green disc, with a ring around it that empties as time runs out. */
    static final class Ring extends View {
        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private final AdIcons fallbackIcon;
        private final int emojiIdx;
        @Nullable private android.graphics.drawable.Drawable lottie;
        private float left = 1f;
        /** The animation drawing the countdown; the deadline itself is the pill's. */
        ValueAnimator drawing;

        Ring(Activity activity) {
            super(activity);
            float density = activity.getResources().getDisplayMetrics().density;
            disc.setColor(0x2E2BD96B);
            track.setColor(0x382BD96B);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(2 * density);
            arc.setColor(AdSkipBar.MARK);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(2 * density);
            arc.setStrokeCap(Paint.Cap.ROUND);
            fallbackIcon = AdIcons.of(activity, AdIcons.Icon.FAST_FORWARD, AdSkipBar.MARK, 16);
            emojiIdx = KawaiiLottie.nextIndex();
            lottie = KawaiiLottie.createDrawable(emojiIdx, this, ready -> {
                lottie = ready;
                syncLottieFrame();
                invalidate();
            });
            syncLottieFrame();
        }

        long visibleDurationMs() {
            return KawaiiLottie.visibleDurationMs(emojiIdx);
        }

        private void syncLottieFrame() {
            if (lottie == null) return;
            // Play exactly 2 full cycles (CYCLES_PER_SHOW = 2) over the countdown window.
            float elapsedFraction = Math.max(0f, Math.min(1f, 1f - left));
            float scaled = elapsedFraction * KawaiiLottie.CYCLES_PER_SHOW;
            float cycleProgress = scaled >= KawaiiLottie.CYCLES_PER_SHOW ? 1.0f : (scaled % 1.0f);
            KawaiiLottie.setProgress(lottie, cycleProgress);
        }

        void setLeft(float fraction) {
            left = fraction;
            syncLottieFrame();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float stroke = arc.getStrokeWidth();
            float r = Math.min(w, h) / 2f - stroke;
            canvas.drawCircle(w / 2f, h / 2f, r, disc);
            oval.set(w / 2f - r, h / 2f - r, w / 2f + r, h / 2f + r);
            canvas.drawOval(oval, track);
            canvas.drawArc(oval, -90, 360 * left, false, arc);
            android.graphics.drawable.Drawable anim = lottie;
            if (anim != null) {
                int inner = Math.max(1, Math.round((r - stroke * 0.7f) * 1.62f));
                int x = Math.round((w - inner) / 2f), y = Math.round((h - inner) / 2f);
                anim.setBounds(0, 0, inner, inner);
                canvas.save();
                canvas.translate(x, y);
                anim.draw(canvas);
                canvas.restore();
            } else {
                int side = fallbackIcon.getBounds().width();
                int x = Math.round((w - side) / 2f), y = Math.round((h - side) / 2f);
                canvas.save();
                canvas.translate(x, y);
                fallbackIcon.draw(canvas);
                canvas.restore();
            }
        }
    }

    private static View divider(Activity activity) {
        View line = new View(activity);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(activity, 1), dp(activity, 18));
        params.leftMargin = dp(activity, 5);
        params.rightMargin = dp(activity, 5);
        params.gravity = Gravity.CENTER_VERTICAL;
        line.setBackgroundColor(0x33FFFFFF);
        line.setLayoutParams(params);
        return line;
    }

    private static ImageView voteButton(Activity activity) {
        ImageView button = new ImageView(activity);
        int size = dp(activity, 30);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.gravity = Gravity.CENTER_VERTICAL;
        params.leftMargin = dp(activity, 1);
        params.rightMargin = dp(activity, 1);
        button.setLayoutParams(params);
        button.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(size / 2f);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, mask));
        return button;
    }

    /** The chosen vote filled and bright, the other one outlined; both plain before a vote. */
    private static void showVote(Activity activity, ImageView up, ImageView down, int vote) {
        up.setImageDrawable(AdIcons.of(activity, vote > 0 ? AdIcons.Icon.THUMB_UP_FILLED : AdIcons.Icon.THUMB_UP,
                UP_COLOR, 18));
        down.setImageDrawable(AdIcons.of(activity, vote < 0 ? AdIcons.Icon.THUMB_DOWN_FILLED : AdIcons.Icon.THUMB_DOWN,
                DOWN_COLOR, 18));
        up.setAlpha(vote < 0 ? 0.4f : 1f);
        down.setAlpha(vote > 0 ? 0.4f : 1f);
    }

    private static void castVote(Activity activity, String serverId, int vote,
            ImageView up, ImageView down) {
        if (!SharedDb.vote(serverId, vote)) return;
        showVote(activity, up, down, vote);
        ImageView chosen = vote > 0 ? up : down;
        chosen.animate().scaleX(1.25f).scaleY(1.25f).setDuration(120)
                .withEndAction(() -> chosen.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start();
        int token = generation;
        Utils.runOnMainThreadDelayed(() -> {
            if (token == generation) dismiss();
        }, 900);
    }

    private static TextView text14(Activity activity, String text, int color, boolean bold) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        tv.setTextColor(color);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    private static LinearLayout.LayoutParams centred() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.gravity = Gravity.CENTER_VERTICAL;
        return params;
    }

    static void dismiss() {
        View pill = shown.get();
        shown = new WeakReference<>(null);
        if (pill == null) return;
        if (pill instanceof Pill) ((Pill) pill).stopTimer();
        pill.animate().cancel();
        if (pill.getAlpha() <= 0.02f) {
            ViewGroup parent = (ViewGroup) pill.getParent();
            if (parent != null) parent.removeView(pill);
            return;
        }
        // Pure fade out from whatever alpha it currently has, with no downward drop.
        pill.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            ViewGroup parent = (ViewGroup) pill.getParent();
            if (parent != null) parent.removeView(pill);
        }).start();
    }

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
