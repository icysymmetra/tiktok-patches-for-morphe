/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.captions;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

public final class CaptionTools {
    private static final Map<Object, String> OWNERS = new WeakHashMap<>();
    private static WeakReference<TextView> overlay = new WeakReference<>(null);
    private static WeakReference<View> captionSource = new WeakReference<>(null);
    private static WeakReference<View> captionStrip = new WeakReference<>(null);
    private static WeakReference<View> observedDecor = new WeakReference<>(null);
    private static ViewTreeObserver.OnPreDrawListener preDrawListener;
    private static String currentId, cueId, cue = "";
    private static boolean clear;
    /**
     * How far above the window's bottom TikTok's caption strip last ended, in pixels, or -1
     * before one has been seen. The kept line used to sit at a fixed 96 dp, near the bottom edge
     * and below where the reader had been looking (#141), so it takes the strip's spot now. It
     * carries over to the next video, since Automatic clear display can clear one before its
     * first caption shows.
     */
    private static int stripGap = -1;
    /** The window height {@link #stripGap} was read in. After a rotation or split screen it's left unused. */
    private static int stripGapHeight;
    /** The last frame's reading, which the next frame has to repeat before it counts. */
    private static int pendingGap = -1;

    /**
     * Puts every static back to a fresh process's state. {@code onVideoChanged(null)} is not that:
     * it returns early when the id has not changed, so a null id after a null id clears nothing
     * and the next test inherits the last one's overlay, listener and cue. Three caption tests
     * only ever passed because of what ran before them in the same fork.
     */
    static void resetForTests() {
        Utils.runOnMainThreadNowOrLater(() -> {
            detachOverlay();
            OWNERS.clear();
            captionSource = new WeakReference<>(null);
            captionStrip = new WeakReference<>(null);
            stripGap = -1;
            stripGapHeight = 0;
            pendingGap = -1;
            currentId = null;
            cueId = null;
            cue = "";
            clear = false;
        });
    }

    public static void onVideoChanged(String id) {
        Utils.runOnMainThreadNowOrLater(() -> {
            if (Objects.equals(currentId, id)) return;
            currentId = id;
            cueId = null;
            cue = "";
            clear = false;
            refresh();
        });
    }

    public static void onClear(Object aweme, boolean enabled) {
        String id = Reflect.string(aweme, "getAid", "aid");
        Utils.runOnMainThreadNowOrLater(() -> {
            if (id != null && id.equals(currentId)) {
                // TikTok hasn't hidden its strip yet, so it still sits where the reader saw it.
                View decor = observedDecor.get();
                if (enabled && !clear && decor != null) noteStrip(decor, true);
                clear = enabled;
                refresh();
            }
        });
    }

    public static void onCaption(Object renderer, String id, Object state, String text) {
        try {
            FrameLayout root = rootOf(renderer);
            if (root == null) return;
            CaptionStyle.apply(root);
            if (id != null) OWNERS.put(renderer, id);
            String owner = OWNERS.get(renderer);
            if (owner == null || !owner.equals(currentId)) return;
            captionSource = new WeakReference<>(root);
            captionStrip = new WeakReference<>(CaptionStyle.strip(root));
            cueId = owner;
            cue = "EXPANDED".equals(String.valueOf(state)) && text != null ? text : "";
            if (Settings.KEEP_CAPTIONS_CLEAR_DISPLAY.get()) attach(root);
            refresh();
        } catch (RuntimeException error) { Logger.printException(() -> "Could not update captions", error); }
    }

    /** Applies the clear-display switch immediately, without waiting for another caption cue. */
    public static void onSettingChanged() {
        Utils.runOnMainThreadNowOrLater(() -> {
            if (!Settings.KEEP_CAPTIONS_CLEAR_DISPLAY.get()) {
                detachOverlay();
                return;
            }
            View source = captionSource.get();
            if (source != null) attach(source);
            refresh();
        });
    }

    private static void attach(View source) {
        Activity activity = activity(source.getContext());
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        View decor = activity.getWindow().getDecorView();
        TextView existing = overlay.get();
        if (existing != null && existing.getParent() != null
                && existing.getRootView() == decor && observedDecor.get() == decor) return;
        detachOverlay();
        if (!(decor instanceof FrameLayout)) return;
        TextView text = new TextView(activity);
        text.setTextColor(Color.WHITE);
        text.setGravity(Gravity.CENTER);
        // In pixels, so on a 3x screen this was about 0.7dp of blur. It is what separates white
        // caption text from the video when the reader picks the transparent background.
        float density = activity.getResources().getDisplayMetrics().density;
        text.setShadowLayer(2 * density, 0, density, Color.BLACK);
        text.setClickable(false);
        text.setFocusable(false);
        int padding = Math.round(12 * text.getResources().getDisplayMetrics().density);
        text.setPadding(padding, padding / 2, padding, padding / 2);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = fallbackMargin(text);
        params.leftMargin = params.rightMargin = padding * 2;
        text.setVisibility(View.GONE);
        ((FrameLayout) decor).addView(text, params);
        overlay = new WeakReference<>(text);
        ViewTreeObserver.OnPreDrawListener draw = () -> {
            if (!clear) noteStrip(decor, false);
            refresh();
            return true;
        };
        observedDecor = new WeakReference<>(decor);
        preDrawListener = draw;
        decor.getViewTreeObserver().addOnPreDrawListener(draw);
        text.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) {
                if (overlay.get() == view) detachOverlay();
            }
        });
    }

    private static void detachOverlay() {
        TextView text = overlay.get();
        View decor = observedDecor.get();
        ViewTreeObserver.OnPreDrawListener draw = preDrawListener;
        overlay = new WeakReference<>(null);
        observedDecor = new WeakReference<>(null);
        preDrawListener = null;
        if (decor != null && draw != null && decor.getViewTreeObserver().isAlive()) {
            decor.getViewTreeObserver().removeOnPreDrawListener(draw);
        }
        if (text != null) {
            text.setVisibility(View.GONE);
            if (text.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) text.getParent()).removeView(text);
            }
        }
    }

    static void refresh() {
        if (!Settings.KEEP_CAPTIONS_CLEAR_DISPLAY.get()) {
            detachOverlay();
            return;
        }
        TextView text = overlay.get();
        if (text == null) return;
        View decor = observedDecor.get();
        Activity activity = activity(text.getContext());
        View source = captionSource.get();
        boolean visible = clear && !cue.isEmpty()
                && cueId != null && cueId.equals(currentId) && source != null && source.isAttachedToWindow()
                && activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && activity.hasWindowFocus() && FeedVisibility.isOnFeed(activity)
                // It draws on the window's own view, above the daily hold's panel.
                && !app.morphe.extension.tiktok.wellbeing.SessionBudget.isLocked();
        if (visible) {
            if (!cue.contentEquals(text.getText())) text.setText(cue);
            int size = CaptionStyle.size();
            float wanted = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                    size == 0 ? 18 : size, text.getResources().getDisplayMetrics());
            if (text.getTextSize() != wanted) text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, wanted);
            int color = CaptionStyle.backgroundColor();
            if (!Objects.equals(text.getTag(), color)) { text.setBackgroundColor(color); text.setTag(color); }
            place(text, decor);
        }
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (text.getVisibility() != visibility) text.setVisibility(visibility);
    }

    /**
     * Notes where TikTok's caption strip ends while clear display is off. Each frame's reading
     * counts only once the next frame repeats it, so a strip sliding away in a swipe, or sliding
     * back after clear display, isn't caught partway. {@code settled} skips that wait for the
     * moment clear display starts. A strip in the window's top half isn't where the feed draws
     * captions, so it's left out.
     */
    private static void noteStrip(View decor, boolean settled) {
        View strip = captionStrip.get();
        int height = decor.getHeight();
        int gap = -1;
        if (strip != null && height != 0 && strip.getHeight() != 0 && strip.isShown()
                && strip.getRootView() == decor) {
            int[] at = new int[2];
            strip.getLocationInWindow(at);
            gap = height - at[1] - strip.getHeight();
            if (gap >= height / 2) gap = -1;
        }
        if (gap >= 0 && (settled || gap == pendingGap)) {
            stripGap = gap;
            stripGapHeight = height;
        }
        pendingGap = gap;
    }

    /** Lines the kept caption's bottom up with the strip's, or the old spot before one was seen. */
    private static void place(TextView text, View decor) {
        if (!(text.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) text.getLayoutParams();
        int margin = stripGap < 0 || decor == null || decor.getHeight() != stripGapHeight
                ? fallbackMargin(text)
                : Math.max(0, stripGap - decor.getPaddingBottom());
        if (params.bottomMargin == margin) return;
        params.bottomMargin = margin;
        text.setLayoutParams(params);
    }

    private static int fallbackMargin(View text) {
        return Math.round(12 * text.getResources().getDisplayMetrics().density) * 8;
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }

    // Replaced with the verified native renderer's FrameLayout field.
    private static FrameLayout rootOf(Object renderer) { return renderer instanceof FrameLayout ? (FrameLayout) renderer : null; }
}
