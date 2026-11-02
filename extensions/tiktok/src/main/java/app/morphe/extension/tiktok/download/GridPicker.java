/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Picking several videos on a profile, Liked or Favorites grid, or in a Favorites collection
 * folder, and saving them in one run (#27).
 *
 * <p>Each grid's cell holder hands every bind to {@link #bind}: the cell's root view and the post
 * TikTok is about to draw in it. RecyclerView recycles cells, so the same view shows one post and
 * then another as the grid scrolls. Nothing here keeps a post with a view beyond the latest bind:
 * {@link #BOUND} is overwritten on every bind, and a tap reads the post the cell shows at that
 * moment. What is picked is kept by post id, so a recycled cell shows a tick only while it shows
 * a picked post, and scrolling a picked post away and back keeps its tick.
 *
 * <p>With the switch on, each cell gets a small circle in its top corner. Tapping it picks the
 * post. While anything is picked, a clear cover sits over every cell so a tap anywhere on a cell
 * picks or unpicks it instead of opening the video, and a bar at the bottom counts the picks
 * with Pick all, Save and Cancel. Save hands the picked posts to {@link BatchSave} as one run,
 * with the running count and Cancel of {@link SaveProgress}. Pick all takes every post the list
 * has loaded (see {@link #pickAll}), so a whole Liked, Favorites or Reposts list saves in runs of
 * {@link #MAX_PICKED}, each one picking up where the last stopped. TikTok's own tap and long press on a cell are
 * never replaced: the circle and the cover are views of ours laid over the cell, and they go
 * (GONE, then removed on the next bind) when the switch is off or Hushfeed is paused.
 *
 * <p>Main thread only: binds and taps both arrive there.
 */
public final class GridPicker {
    /** The most posts one run takes, so a long grid can't queue an unbounded save. */
    public static final int MAX_PICKED = 50;
    static final String CIRCLE_TAG = "hushfeed_grid_pick";
    static final String COVER_TAG = "hushfeed_grid_pick_cover";
    static final String BAR_TAG = "hushfeed_grid_pick_bar";
    static final String LAYER_TAG = "hushfeed_grid_pick_layer";
    private static final int CIRCLE_DP = 24;
    private static final int TARGET_DP = 48;

    /** The post each cell shows right now, by its root view. Rewritten on every bind. */
    private static final Map<View, Object> BOUND = new WeakHashMap<>();
    /** The fields that hold a row's post, by the row's class. */
    private static final Map<Class<?>, List<Field>> POST_FIELDS = new HashMap<>();
    /** The picked posts by id, in the order they were picked. */
    private static final LinkedHashMap<String, Object> PICKED = new LinkedHashMap<>();
    /** The bar counting the picks, and the activity it sits in. */
    private static WeakReference<View> bar = new WeakReference<>(null);
    private static WeakReference<TextView> barLabel = new WeakReference<>(null);
    private static WeakReference<View> barPickAll = new WeakReference<>(null);
    /** The cell picked from last, whose list Pick all reads. */
    private static WeakReference<View> lastCell = new WeakReference<>(null);
    /** For the tests: what was handed to a save run. Null starts a real one. */
    static volatile List<List<Object>> runsForTests;

    private GridPicker() {
    }

    static boolean enabled() {
        return SettingsStatus.advancedDownloadsEnabled && Settings.PICK_GRID_VIDEOS.get();
    }

    /**
     * The cell holder's bind, before TikTok draws {@code item} in {@code cell}. Never throws:
     * this runs inside TikTok's RecyclerView bind.
     */
    public static void bind(View cell, Object item) {
        try {
            if (!(cell instanceof ViewGroup)) return;
            if (!enabled()) {
                BOUND.remove(cell);
                strip((ViewGroup) cell);
                if (!PICKED.isEmpty()) clear();
                return;
            }
            String id = pickableId(item);
            if (id == null) {
                // A story cell, an ad or anything without an id: no circle, and a tap on it is
                // TikTok's, even while other cells are being picked.
                BOUND.remove(cell);
                strip((ViewGroup) cell);
                return;
            }
            BOUND.put(cell, item);
            show(cell, host((ViewGroup) cell), id);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not bind the grid picker", failure);
        }
    }

    /**
     * Where the circle and the cover go: the cell itself when it is a FrameLayout, as a profile
     * grid's is, or a full-size layer of ours laid last in it otherwise. A Favorites collection
     * folder's cell is a ConstraintLayout (X/10J3 on 47.1.4), which would put both in its top
     * left corner at their own size. The layer takes no tap of its own, so with nothing showing a
     * tap goes through it to TikTok's views.
     */
    private static FrameLayout host(ViewGroup cell) {
        if (cell instanceof FrameLayout) return (FrameLayout) cell;
        View layer = cell.findViewWithTag(LAYER_TAG);
        if (layer instanceof FrameLayout && layer.getParent() == cell) {
            layer.bringToFront();
            return (FrameLayout) layer;
        }
        FrameLayout created = new FrameLayout(cell.getContext());
        created.setTag(LAYER_TAG);
        cell.addView(created, new ViewGroup.LayoutParams(-1, -1));
        return created;
    }

    /** The post's id when it can be picked; null for a story, an ad or an unreadable item. */
    static String pickableId(Object item) {
        if (item == null || StoryShareSave.isStory(item)) return null;
        if (Boolean.TRUE.equals(Reflect.invoke(item, "isAd"))) return null;
        String id = Reflect.string(item, "getAid", "aid");
        return id == null || id.isEmpty() ? null : id;
    }

    /** The post the cell shows right now, or null once it has been bound to something else. */
    static Object boundTo(View cell) {
        return BOUND.get(cell);
    }

    static int pickedCount() {
        return PICKED.size();
    }

    static boolean isPicked(String id) {
        return id != null && PICKED.containsKey(id);
    }

    /**
     * Puts the circle and the cover in the cell's host, or brings them up to date. A tap on
     * either reads the cell, which is what {@link #BOUND} knows, not the host.
     */
    private static void show(View cell, FrameLayout host, String id) {
        Context context = cell.getContext();
        TextView circle = host.findViewWithTag(CIRCLE_TAG);
        View cover = host.findViewWithTag(COVER_TAG);
        if (cover == null) {
            cover = new View(context);
            cover.setTag(COVER_TAG);
            // The circle is the control a screen reader uses, so the cover is left out of its
            // tree and doesn't read each cell twice.
            cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cover.setOnClickListener(view -> toggle(cell));
            host.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        }
        if (circle == null) {
            circle = new TextView(context);
            circle.setTag(CIRCLE_TAG);
            circle.setGravity(Gravity.CENTER);
            circle.setTextColor(Color.WHITE);
            circle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            circle.setIncludeFontPadding(false);
            int target = SettingsUi.dp(context, TARGET_DP);
            int inset = (target - SettingsUi.dp(context, CIRCLE_DP)) / 2;
            circle.setPadding(inset, inset, inset, inset);
            circle.setContentDescription(L10n.t(context, "Pick for saving"));
            circle.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View view, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(view, info);
                    info.setClassName("android.widget.CheckBox");
                    info.setCheckable(true);
                    info.setChecked(view.isSelected());
                }
            });
            circle.setOnClickListener(view -> toggle(cell));
            // A collection folder's cell keeps TikTok's Select posts CheckBox (0x7f0a7583 on
            // 47.1.4) in its top right corner, so there the circle takes the top left one.
            int corner = host == cell ? Gravity.END : Gravity.START;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(target, target, Gravity.TOP | corner);
            host.addView(circle, params);
        }
        // Last in the host, so both sit over TikTok's own views and get the tap first.
        cover.bringToFront();
        circle.bringToFront();
        render(cell, circle, cover, isPicked(id));
    }

    private static void render(View cell, TextView circle, View cover, boolean picked) {
        Context context = cell.getContext();
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(picked ? SettingsUi.OVERLAY_ACCENT : Color.argb(80, 0, 0, 0));
        dot.setStroke(SettingsUi.dp(context, 2), Color.WHITE);
        int inset = circle.getPaddingLeft();
        circle.setBackground(new android.graphics.drawable.InsetDrawable(dot, inset));
        circle.setText(picked ? "✓" : "");
        circle.setSelected(picked);
        circle.setVisibility(View.VISIBLE);
        // While anything is picked a tap anywhere on a cell picks it; otherwise the cell is TikTok's.
        cover.setVisibility(PICKED.isEmpty() ? View.GONE : View.VISIBLE);
        cover.setBackgroundColor(picked ? Color.argb(70, 0, 0, 0) : Color.TRANSPARENT);
    }

    /** Takes our views off a cell, leaving TikTok's as they were. */
    private static void strip(ViewGroup cell) {
        for (String tag : new String[]{CIRCLE_TAG, COVER_TAG, LAYER_TAG}) {
            View ours = cell.findViewWithTag(tag);
            if (ours != null && ours.getParent() instanceof ViewGroup) ((ViewGroup) ours.getParent()).removeView(ours);
        }
    }

    /** A tap on a cell's circle or cover: picks or unpicks the post the cell shows now. */
    static void toggle(View cell) {
        try {
            if (!enabled()) {
                // Turned off or paused since the cell was bound: give every cell back.
                clear();
                for (View bound : new ArrayList<>(BOUND.keySet())) {
                    if (bound instanceof ViewGroup) strip((ViewGroup) bound);
                }
                BOUND.clear();
                return;
            }
            Object item = BOUND.get(cell);
            String id = pickableId(item);
            if (id == null) return;
            lastCell = new WeakReference<>(cell);
            if (PICKED.containsKey(id)) {
                PICKED.remove(id);
            } else if (PICKED.size() >= MAX_PICKED) {
                Utils.showToastShort(L10n.f(cell.getContext(), "You can pick up to %1$s at a time",
                        String.valueOf(MAX_PICKED)));
                return;
            } else {
                PICKED.put(id, item);
            }
            refresh();
            if (PICKED.isEmpty()) hideBar();
            else showBar(cell);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not pick a grid video", failure);
        }
    }

    /** Redraws every cell still bound, after the picks changed. */
    private static void refresh() {
        for (Map.Entry<View, Object> entry : new ArrayList<>(BOUND.entrySet())) {
            View view = entry.getKey();
            if (!(view instanceof ViewGroup)) continue;
            TextView circle = view.findViewWithTag(CIRCLE_TAG);
            View cover = view.findViewWithTag(COVER_TAG);
            if (circle == null || cover == null) continue;
            render(view, circle, cover, isPicked(pickableId(entry.getValue())));
        }
    }

    /** Unpicks everything and takes the bar down. */
    static void clear() {
        PICKED.clear();
        refresh();
        hideBar();
    }

    /**
     * Save on the bar: the picked posts, in the order picked, go to one run. The picks are
     * cleared at once, so the grid is TikTok's again while the run saves.
     */
    static void saveAll(Context context) {
        List<Object> posts = new ArrayList<>(PICKED.values());
        clear();
        if (posts.isEmpty()) return;
        List<List<Object>> log = runsForTests;
        if (log != null) {
            log.add(posts);
            return;
        }
        BatchSave.start(context, posts);
    }

    /**
     * Pick all on the bar: every post the list has loaded, in the list's order, leaving out what
     * is picked already and, with Check for already-saved videos on, the videos saved here before.
     * Picking stops at {@link #MAX_PICKED}, so a long list saves in runs: save these, and the next
     * Pick all starts where this run ended. Only posts TikTok has loaded are picked, from the
     * adapter's own list, so nothing is requested from TikTok's servers; scrolling down loads more.
     */
    static void pickAll() {
        try {
            View cell = lastCell.get();
            if (cell == null || !enabled()) return;
            List<?> posts = listOf(cell);
            if (posts == null) return;
            boolean skipSaved = Settings.CHECK_SAVED_VIDEOS.get();
            boolean full = false;
            int added = 0;
            for (Object row : new ArrayList<>(posts)) {
                Object post = postIn(row);
                String id = pickableId(post);
                if (id == null || PICKED.containsKey(id)) continue;
                if (skipSaved && SavedVideoArchive.isSaved(id)) continue;
                if (PICKED.size() >= MAX_PICKED) {
                    full = true;
                    break;
                }
                PICKED.put(id, post);
                added++;
            }
            refresh();
            showBar(cell);
            if (full) {
                Utils.showToastLong(L10n.f(cell.getContext(),
                        "Picked the first %1$s. Save them, then tap Pick all again for the rest.",
                        String.valueOf(MAX_PICKED)));
            } else if (added == 0) {
                Utils.showToastLong(L10n.t(cell.getContext(),
                        "Everything this list has loaded is picked or saved. Scroll down to load more."));
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not pick a whole grid", failure);
        }
    }

    /**
     * The post a list row stands for: the row itself when it is a post, as in a profile grid's
     * list, or the post it wraps. A Favorites collection folder's list holds rows (X/10Hq on
     * 47.1.4) with the post in one field beside an int, and its header and banner rows have none
     * there, so they come back null and are passed over like a story. The field is found by its
     * type, since TikTok renames it between builds.
     */
    static Object postIn(Object row) {
        if (row == null || row instanceof Aweme) return row;
        for (Field field : postFields(row.getClass())) {
            try {
                Object post = field.get(row);
                if (post != null) return post;
            } catch (Throwable ignored) {
                // An unreadable field holds nothing to pick.
            }
        }
        return null;
    }

    private static List<Field> postFields(Class<?> type) {
        List<Field> fields = POST_FIELDS.get(type);
        if (fields != null) return fields;
        fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !Aweme.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    fields.add(field);
                } catch (Throwable ignored) {
                    // Left out: a field that can't be opened can't be read either.
                }
            }
        }
        POST_FIELDS.put(type, fields);
        return fields;
    }

    /**
     * The rows the cell's list holds: its RecyclerView's adapter's {@code getData()}, the list the
     * profile, Liked and Favorites grids draw from (X/0Of2 on 47.1.4), and a Favorites collection
     * folder's (X/10J1, whose rows wrap their posts; see {@link #postIn}). Null when there is no
     * such list.
     */
    static List<?> listOf(View cell) {
        for (android.view.ViewParent parent = cell.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (!isRecyclerView(parent.getClass())) continue;
            Object data = Reflect.invoke(Reflect.invoke(parent, "getAdapter"), "getData");
            return data instanceof List ? (List<?>) data : null;
        }
        return null;
    }

    private static boolean isRecyclerView(Class<?> type) {
        for (Class<?> current = type; current != null && current != View.class; current = current.getSuperclass()) {
            if ("RecyclerView".equals(current.getSimpleName())) return true;
        }
        return false;
    }

    private static void showBar(View cell) {
        Activity activity = activityOf(cell);
        if (activity == null) return;
        View shown = bar.get();
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        if (shown != null && shown.getParent() != content) {
            // Picked on another screen than the bar's: the bar follows the picking.
            if (shown.getParent() instanceof ViewGroup) ((ViewGroup) shown.getParent()).removeView(shown);
            shown = null;
        }
        if (shown == null) {
            shown = newBar(activity, content);
            content.addView(shown, BlockAuthorOverlay.bannerParams(activity, content));
            bar = new WeakReference<>(shown);
        }
        TextView label = barLabel.get();
        if (label != null) {
            String text = L10n.quantity(activity, PICKED.size(), "One picked", "%1$s picked",
                    String.valueOf(PICKED.size()));
            label.setText(text);
            label.setContentDescription(text);
        }
        View pickAll = barPickAll.get();
        // Only offered where the list can be read, so the button never does nothing.
        if (pickAll != null) pickAll.setVisibility(listOf(cell) != null ? View.VISIBLE : View.GONE);
    }

    private static View newBar(Activity activity, ViewGroup content) {
        LinearLayout row = new LinearLayout(activity);
        row.setTag(BAR_TAG);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(SettingsUi.dp(activity, 16), SettingsUi.dp(activity, 4),
                SettingsUi.dp(activity, 8), SettingsUi.dp(activity, 4));
        row.setBackground(SettingsUi.overlayBanner(activity));
        // A tap between the buttons mustn't fall through to the grid under the bar.
        row.setClickable(true);

        TextView label = new TextView(activity);
        label.setTextColor(SettingsUi.OVERLAY_TEXT);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY_SMALL);
        // The count is read out as it changes, since a pick by touch says nothing else.
        label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        barLabel = new WeakReference<>(label);

        TextView pickAll = button(activity, L10n.t(activity, "Pick all"));
        pickAll.setOnClickListener(view -> pickAll());
        row.addView(pickAll, new LinearLayout.LayoutParams(-2, -2));
        barPickAll = new WeakReference<>(pickAll);

        TextView save = button(activity, L10n.t(activity, "Save"));
        save.setOnClickListener(view -> saveAll(activity));
        row.addView(save, new LinearLayout.LayoutParams(-2, -2));
        TextView cancel = button(activity, L10n.t(activity, "Cancel"));
        cancel.setOnClickListener(view -> clear());
        row.addView(cancel, new LinearLayout.LayoutParams(-2, -2));

        // The picks belong to this screen: when it closes, nothing stays picked behind it.
        row.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
            }

            @Override public void onViewDetachedFromWindow(View view) {
                if (bar.get() != view) return;
                if (activity.isFinishing() || activity.isDestroyed()) {
                    PICKED.clear();
                    bar = new WeakReference<>(null);
                    refresh();
                }
            }
        });
        return row;
    }

    private static TextView button(Activity activity, String text) {
        TextView button = new TextView(activity);
        button.setText(text);
        button.setContentDescription(text);
        button.setTextColor(SettingsUi.OVERLAY_ACCENT);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY_SMALL);
        button.setGravity(Gravity.CENTER);
        button.setPadding(SettingsUi.dp(activity, 16), SettingsUi.dp(activity, 12),
                SettingsUi.dp(activity, 16), SettingsUi.dp(activity, 12));
        button.setMinimumHeight(SettingsUi.dp(activity, 48));
        button.setMinimumWidth(SettingsUi.dp(activity, 48));
        button.setBackground(SettingsUi.overlayAction(activity, SettingsUi.RADIUS_OVERLAY));
        button.setFocusable(true);
        SettingsUi.markAsButton(button);
        return button;
    }

    private static void hideBar() {
        View shown = bar.get();
        bar = new WeakReference<>(null);
        barLabel = new WeakReference<>(null);
        barPickAll = new WeakReference<>(null);
        if (shown != null && shown.getParent() instanceof ViewGroup) ((ViewGroup) shown.getParent()).removeView(shown);
    }

    private static Activity activityOf(View view) {
        Context context = view.getContext();
        while (context instanceof android.content.ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((android.content.ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** For the tests: the bar on screen, or null. */
    static View barForTests() {
        return bar.get();
    }

    static void resetForTests() {
        PICKED.clear();
        BOUND.clear();
        hideBar();
        lastCell = new WeakReference<>(null);
        runsForTests = null;
    }
}
