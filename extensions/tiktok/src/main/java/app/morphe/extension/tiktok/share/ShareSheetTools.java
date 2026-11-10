/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.share;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;

import app.morphe.extension.shared.GlobalLayoutHook;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceIdCache;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Share sheet tools: hiding of chosen people or share options, or the whole "Send to" row.
 *
 * Ids were read off the live view hierarchy with the share sheet open. TikTok 47.0.3 moved
 * the panel into its own window and renamed all four anchors, and every build since renames
 * them again. Only 47.1.4's names are looked up; an older build's name (46.x's ibc, u3t, dqr,
 * a59) names some other view there.
 * <pre>
 *   ip5   frame around the "Send to" contacts row, and around any other row TikTok ranks
 *         first (the compact long-press sheet's actions row, #120)
 *   v3j   the contacts list; each child is the contact cell, content description
 *         = the display name, and is itself clickable
 *   dwr   the share channels row (Repost, Copy link, SMS, Facebook, ...)
 *   a5t   the actions row (Report, Not interested, Download, Create group, ...)
 * </pre>
 * Contacts put their label on the View. Native actions instead bind a child TextView and
 * expose its title through their accessibility delegate, so both descriptions are read.
 *
 * There used to be a confirm step here, a second tap before a video went to a friend. On every
 * build Hushfeed supports, a tap on a person only marks them chosen and TikTok's own Send button
 * sends, and the method the step was hooked to was the contact cell's impression callback, which
 * TikTok runs when a cell scrolls into view. So it asked for a second tap nobody had made a first
 * time (#58) and never held a real one.
 */
public final class ShareSheetTools {
    /** One family for everything that touches the sheet, so an export reads as one surface. */
    private static final String FAMILY = ShareModelFilter.FAMILY;
    private static final String[] CONTACTS_SECTION_IDS = {"47.4.3:iq_"};
    private static final String[] CONTACTS_LIST_IDS = {"47.4.3:v47"};
    private static final String[] CHANNELS_LIST_IDS = {"47.4.3:dx6"};
    private static final String[] ACTIONS_LIST_IDS = {"47.4.3:a5x"};

    private static final ResourceIdCache RESOURCE_IDS = new ResourceIdCache();

    /** Original layout width of each cell this class has shrunk, so it can be restored. */
    private static final WeakHashMap<View, Integer> ORIGINAL_WIDTHS = new WeakHashMap<>();

    /** Children of the Send to frame hidden here because the frame also holds a row that stays. */
    private static final WeakHashMap<View, Boolean> HIDDEN_SECTION_CHILDREN = new WeakHashMap<>();

    private static WeakReference<Activity> activityReference = new WeakReference<>(null);
    private static final GlobalLayoutHook LAYOUT_HOOK = new GlobalLayoutHook();
    private static final GlobalLayoutHook SHEET_LAYOUT_HOOK = new GlobalLayoutHook();
    private static WeakReference<View> panelRowReference = new WeakReference<>(null);
    private static WeakReference<ViewGroup> panelDrawRoot = new WeakReference<>(null);
    private static ViewTreeObserver panelDrawObserver;
    private static PanelRows panelRows;
    private static final ViewTreeObserver.OnPreDrawListener PANEL_DRAW_LISTENER = ShareSheetTools::beforePanelDraw;
    private static final View.OnAttachStateChangeListener PANEL_ATTACH_LISTENER =
            new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View row) {
                    if (panelRowReference.get() == row) panelBound(row);
                }

                @Override public void onViewDetachedFromWindow(View row) {
                    if (panelRowReference.get() == row) detachSheetHooks();
                }
            };

    private static boolean applyPosted;

    private ShareSheetTools() {
    }

    /** Called from the patched {@code MainActivity.onCreate}; the work is posted. */
    public static void install(Activity activity) {
        if (activity == null) {
            return;
        }
        Utils.runOnMainThread(() -> installNow(activity));
    }

    private static void installNow(Activity activity) {
        try {
            if (activity.isFinishing()) {
                LAYOUT_HOOK.detach();
                detachPanel();
                return;
            }
            ViewGroup root = activity.findViewById(android.R.id.content);
            if (root == null) {
                LAYOUT_HOOK.detach();
                detachPanel();
                Logger.printInfo(() -> "Share sheet tools found no content view to watch");
                return;
            }
            if (activityReference.get() != activity) detachPanel();
            boolean installed = LAYOUT_HOOK.install(root, ShareSheetTools::apply);
            activityReference = new WeakReference<>(activity);
            if (installed) {
                Logger.printDebug(() -> "Share sheet tools installed");
            }
        } catch (Throwable ex) {
            HookStatus.threw(FAMILY, "install", ex);
            Logger.printException(() -> "Could not install the share sheet tools", ex);
        }
    }

    private static void apply() {
        try {
            Activity activity = activityReference.get();
            if (activity == null) {
                LAYOUT_HOOK.detach();
                detachPanel();
                return;
            }
            if (activity.isFinishing()) {
                LAYOUT_HOOK.detach();
                detachPanel();
                return;
            }

            List<View> roots = windowRoots(activity);
            Set<Integer> wanted = new HashSet<>();
            addIds(activity, wanted, CONTACTS_LIST_IDS);
            addIds(activity, wanted, CONTACTS_SECTION_IDS);
            addIds(activity, wanted, CHANNELS_LIST_IDS);
            addIds(activity, wanted, ACTIONS_LIST_IDS);
            List<Map<Integer, View>> found = indexRoots(roots, wanted);
            View contacts = find(activity, roots, found, CONTACTS_LIST_IDS);
            View channels = find(activity, roots, found, CHANNELS_LIST_IDS);
            View actions = find(activity, roots, found, ACTIONS_LIST_IDS);
            watchSheetRoot(activity, actions != null ? actions : channels != null ? channels : contacts);
            View contactsSection = find(activity, roots, found, CONTACTS_SECTION_IDS);
            ViewGroup panel = panelDrawRoot.get();
            if (panel != null) {
                panelRows = new PanelRows(panel, contactsSection, contacts, channels,
                        actions != null ? actions : panelRowReference.get());
            }
            applyRows(contactsSection, contacts, channels, actions);
        } catch (Throwable ex) {
            HookStatus.threw(FAMILY, "layout pass", ex);
            Logger.printException(() -> "Share sheet tools failed", ex);
        }
    }

    // ---- hiding ------------------------------------------------------------------------

    /** Layouts discover rows. Pre-draw catches title changes that only invalidate their text. */
    private static void watchSheetRoot(Activity activity, View row) {
        View root = row == null ? null : row.getRootView();
        View activityRoot = activity == null || activity.getWindow() == null
                ? null : activity.getWindow().getDecorView();
        if (root instanceof ViewGroup && root != activityRoot) {
            SHEET_LAYOUT_HOOK.install((ViewGroup) root, ShareSheetTools::apply);
            watchPanelDraw((ViewGroup) root);
        } else {
            detachSheetHooks();
        }
    }

    private static void watchPanelDraw(ViewGroup root) {
        ViewTreeObserver current = root.getViewTreeObserver();
        if (panelDrawRoot.get() == root && panelDrawObserver == current && current.isAlive()) return;
        detachPanelDraw();
        current.addOnPreDrawListener(PANEL_DRAW_LISTENER);
        panelDrawRoot = new WeakReference<>(root);
        panelDrawObserver = current;
        // The native action row is known before the first layout or posted pass.
        panelRows = new PanelRows(root, null, null, null, panelRowReference.get());
    }

    /** Only known panel rows are read on redraw, never the activity or the window-root index. */
    private static boolean beforePanelDraw() {
        try {
            Activity activity = activityReference.get();
            if (activity == null || activity.isFinishing()) {
                LAYOUT_HOOK.detach();
                detachPanel();
                return true;
            }
            ViewGroup root = panelDrawRoot.get();
            PanelRows rows = panelRows;
            if (root != null && root.isAttachedToWindow() && rows != null) {
                applyRows(rows.get(rows.section, root), rows.get(rows.contacts, root),
                        rows.get(rows.channels, root), rows.get(rows.actions, root));
            }
        } catch (Throwable ex) {
            HookStatus.threw(FAMILY, "draw pass", ex);
            Logger.printException(() -> "Share sheet redraw filtering failed", ex);
        }
        return true;
    }

    /** Weak row snapshots are refreshed during layout, including replacement channel lists. */
    private static final class PanelRows {
        final WeakReference<View> section, contacts, channels, actions;

        PanelRows(View root, View section, View contacts, View channels, View actions) {
            this.section = inRoot(section, root);
            this.contacts = inRoot(contacts, root);
            this.channels = inRoot(channels, root);
            this.actions = inRoot(actions, root);
        }

        private static WeakReference<View> inRoot(View view, View root) {
            return new WeakReference<>(view != null && view.getRootView() == root ? view : null);
        }

        View get(WeakReference<View> reference, View root) {
            View view = reference.get();
            return view != null && view.isAttachedToWindow() && view.getRootView() == root ? view : null;
        }
    }

    private static void applyRows(View section, View contacts, View channels, View actions) {
        List<String> hidden = entries(ShareModelFilter.hiddenItems());
        boolean hideContacts = Settings.HIDE_SHARE_CONTACTS.get();
        setSectionHidden(section, hideContacts, actions, channels);
        if (!hideContacts) hideByLabel("contacts", contacts, hidden);
        hideByLabel("channels", channels, hidden);
        hideByLabel("actions", actions, hidden);
    }

    /**
     * Called after the native panel finds and casts its action row in either layout. This runs
     * even with no contacts, Pause or an empty exclusion list. The panel's onAttachedToWindow
     * can run before its row attaches, so keep an attach listener for that first layout and for
     * a reused, nonfocusable Dialog. No listener keeps a detached row or its Activity alive.
     */
    public static void panelBound(View row) {
        if (row == null) return;
        Utils.runOnMainThreadNowOrLater(() -> {
            try {
                View previous = panelRowReference.get();
                if (previous != row) {
                    detachPanel();
                    panelRowReference = new WeakReference<>(row);
                    row.addOnAttachStateChangeListener(PANEL_ATTACH_LISTENER);
                }
                watchSheetRoot(activityReference.get(), row);
                requestApply();
            } catch (Throwable ex) {
                HookStatus.threw(FAMILY, "panel bind", ex);
                Logger.printException(() -> "Could not watch the native share panel", ex);
            }
        });
    }

    private static void detachPanel() {
        View previous = panelRowReference.get();
        if (previous != null) previous.removeOnAttachStateChangeListener(PANEL_ATTACH_LISTENER);
        panelRowReference = new WeakReference<>(null);
        detachSheetHooks();
    }

    private static void detachSheetHooks() {
        SHEET_LAYOUT_HOOK.detach();
        detachPanelDraw();
    }

    private static void detachPanelDraw() {
        removePanelDrawListener(panelDrawObserver);
        ViewGroup root = panelDrawRoot.get();
        if (root != null) removePanelDrawListener(root.getViewTreeObserver());
        panelDrawRoot = new WeakReference<>(null);
        panelDrawObserver = null;
        panelRows = null;
    }

    private static void removePanelDrawListener(ViewTreeObserver observer) {
        if (observer == null) return;
        try {
            if (observer.isAlive()) observer.removeOnPreDrawListener(PANEL_DRAW_LISTENER);
        } catch (Throwable ignored) {
            // A destroyed window can invalidate its observer during removal.
        }
    }

    /**
     * TikTok's small share sheet lost every row but "Share with" once anything was hidden (#120),
     * on a server layout no test account here gets. The debug line names each cell as it goes,
     * with its row and shape, so a diagnostic export shows which row held what. A contact's label
     * is a person's name, so that row's stays out of the log.
     */
    private static void hideByLabel(String row, View list, List<String> hidden) {
        if (!(list instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) list;
        for (int index = 0; index < group.getChildCount(); index++) {
            View cell = group.getChildAt(index);
            String label = labelOf(cell);
            boolean hide = matches(hidden, label);
            if (hide && cell != null && cell.getVisibility() != View.GONE) {
                int position = index;
                String named = "contacts".equals(row) ? "a person" : "\"" + label + "\"";
                Logger.printDebug(() -> "Share sheet hides " + row + " cell " + position + " " + named + " ("
                        + cell.getClass().getSimpleName() + ", " + childCount(cell) + " children) in "
                        + list.getClass().getSimpleName() + " of " + group.getChildCount());
            }
            setCellHidden(cell, hide);
        }
    }

    private static int childCount(View view) {
        return view instanceof ViewGroup ? ((ViewGroup) view).getChildCount() : 0;
    }

    /**
     * The Send to frame is not only the Send to row. TikTok's panel adds every widget ranked at
     * the top into that same frame, and on the compact long-press sheet the actions row ranks at
     * the top too, so hiding the frame took Report, Download and the rest with it and left only
     * Share with (#120). When the frame holds the actions or apps row, it stays up and only its
     * other children go. Only children hidden here are shown again when the setting goes off.
     */
    static void setSectionHidden(View section, boolean hidden, View actions, View channels) {
        if (section == null) {
            return;
        }
        View keepActions = childHolding(section, actions);
        View keepChannels = childHolding(section, channels);
        boolean holdsRow = keepActions != null || keepChannels != null;
        if (section instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) section;
            for (int index = 0; index < group.getChildCount(); index++) {
                View child = group.getChildAt(index);
                setSectionChildHidden(child, hidden && holdsRow && child != keepActions && child != keepChannels);
            }
        }
        setVisible(section, !hidden || holdsRow);
    }

    private static void setSectionChildHidden(View child, boolean hidden) {
        if (child == null) {
            return;
        }
        if (hidden) {
            if (child.getVisibility() != View.GONE) {
                HIDDEN_SECTION_CHILDREN.put(child, Boolean.TRUE);
                child.setVisibility(View.GONE);
            }
        } else if (HIDDEN_SECTION_CHILDREN.remove(child) != null) {
            child.setVisibility(View.VISIBLE);
        }
    }

    /** The direct child of section that holds row, or null when row sits elsewhere. */
    private static View childHolding(View section, View row) {
        View view = row;
        while (view != null) {
            ViewParent parent = view.getParent();
            if (parent == section) {
                return view;
            }
            view = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /**
     * A RecyclerView lays a GONE child out at its full size, so the width goes to zero as
     * well. Cells are recycled, so the original width is kept and put back when the same
     * view later shows something that is not hidden.
     */
    static void setCellHidden(View cell, boolean hidden) {
        if (cell == null) {
            return;
        }
        ViewGroup.LayoutParams params = cell.getLayoutParams();
        if (params == null) {
            setVisible(cell, !hidden);
            return;
        }

        Integer original = ORIGINAL_WIDTHS.get(cell);
        if (hidden) {
            if (original == null) {
                ORIGINAL_WIDTHS.put(cell, params.width);
            }
            if (cell.getVisibility() != View.GONE || params.width != 0) {
                cell.setVisibility(View.GONE);
                params.width = 0;
                cell.setLayoutParams(params);
            }
        } else if (original != null) {
            if (cell.getVisibility() != View.VISIBLE || params.width != original) {
                cell.setVisibility(View.VISIBLE);
                params.width = original;
                cell.setLayoutParams(params);
            }
        }
    }

    private static void setVisible(View view, boolean visible) {
        int wanted = visible ? View.VISIBLE : View.GONE;
        if (view.getVisibility() != wanted) {
            view.setVisibility(wanted);
        }
    }

    private static boolean matches(List<String> hidden, String label) {
        if (label == null || hidden.isEmpty()) {
            return false;
        }
        for (String entry : hidden) {
            if (entry.equalsIgnoreCase(label)) {
                return true;
            }
        }
        return false;
    }

    // ---- contact binds -----------------------------------------------------------------

    /**
     * Called from the patched contact adapter each time it binds a cell in the Send to row.
     * 47.0.3 presents the panel in another WindowManager root, so the activity root's
     * global-layout listener does not reliably observe its first layout. A contact bind happens
     * while that window is being built, so it asks for one coalesced pass after the bind.
     */
    public static void contactBound() {
        try {
            requestApply();
        } catch (Throwable ex) {
            HookStatus.threw(FAMILY, "contact bind", ex);
            Logger.printException(() -> "Could not schedule a share sheet pass after a contact bind", ex);
        }
    }

    @SuppressWarnings("deprecation")
    static String labelOf(View view) {
        if (view == null) return null;
        String label = trimmedLabel(view.getContentDescription());
        if (label != null) return label;

        // The native delegate reads the bound child title. Do not search arbitrary descendants:
        // a vertical action row can also contain a whole channels group with several choices.
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain(view);
        try {
            view.onInitializeAccessibilityNodeInfo(node);
            return trimmedLabel(node.getContentDescription());
        } finally {
            node.recycle();
        }
    }

    private static String trimmedLabel(CharSequence description) {
        if (description == null) return null;
        String label = description.toString().trim();
        return label.isEmpty() ? null : label;
    }

    // ---- lookup ------------------------------------------------------------------------

    private static synchronized void requestApply() {
        if (applyPosted) return;
        applyPosted = true;
        Utils.runOnMainThread(() -> {
            synchronized (ShareSheetTools.class) {
                applyPosted = false;
            }
            apply();
        });
    }

    /**
     * Adds the resolved ids one group can name, so one walk per root can look for all four groups.
     * One array per call, like find(): RuntimeViewIdAnchorsTest traces every lookup's name to its
     * literals through a helper's parameter, and a varargs call left it nothing to follow.
     */
    private static void addIds(Activity activity, Set<Integer> ids, String[] candidates) {
        for (String name : candidates) {
            int id = RESOURCE_IDS.resolve(activity.getResources(), activity.getPackageName(), name, false);
            if (id != 0) ids.add(id);
        }
    }

    /**
     * The first view carrying each wanted id in each root, from one pre-order walk per root:
     * what root.findViewById(id) returns, since that checks a view and then its children in
     * order. findViewById never enters a child that is a root namespace, which the framework sets
     * on a window's DecorView, so a DecorView met below a root is left out the same way. apply()
     * used to walk every window once per group, four times on each layout pass of the main
     * window, sheet open or not.
     */
    static List<Map<Integer, View>> indexRoots(List<View> roots, Set<Integer> ids) {
        List<Map<Integer, View>> index = new ArrayList<>(roots.size());
        ArrayDeque<View> stack = new ArrayDeque<>();
        for (View root : roots) {
            Map<Integer, View> found = new HashMap<>();
            index.add(found);
            if (root == null || ids.isEmpty()) continue;
            stack.clear();
            stack.push(root);
            while (!stack.isEmpty() && found.size() < ids.size()) {
                View view = stack.pop();
                int id = view.getId();
                if (id != View.NO_ID && ids.contains(id) && !found.containsKey(id)) found.put(id, view);
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int child = group.getChildCount() - 1; child >= 0; child--) {
                        View next = group.getChildAt(child);
                        if (next != null && !isDecorView(next)) stack.push(next);
                    }
                }
            }
        }
        return index;
    }

    /** The window root class: com.android.internal.policy.DecorView, PhoneWindow$DecorView on API 23. */
    private static boolean isDecorView(View view) {
        String name = view.getClass().getName();
        return name.equals("com.android.internal.policy.DecorView") || name.endsWith("PhoneWindow$DecorView");
    }

    /** Chooses the newest candidate that occurs in one of the app's current windows. */
    private static View find(Activity activity, List<View> roots, List<Map<Integer, View>> index,
            String[] candidates) {
        if (activity == null) return null;
        boolean resolvedAny = false;
        String diagnostic = String.join("|", candidates);
        for (String name : candidates) {
            // The running package, not TikTok's: a cloned build renames it, resource table and all (#59).
            int id = RESOURCE_IDS.resolve(activity.getResources(), activity.getPackageName(), name, false);
            if (id == 0) continue;
            resolvedAny = true;
            for (int root = 0; root < roots.size(); root++) {
                View found = index.get(root).get(id);
                if (found == null) continue;
                HookStatus.recoveredViewId(FAMILY, diagnostic);
                HookStatus.bound(FAMILY, name);
                return found;
            }
        }
        // The sheet is normally absent, so a resolved id with no current view is not a miss.
        if (!resolvedAny) HookStatus.missingViewId(FAMILY, diagnostic);
        return null;
    }

    /** Activity content plus dialog and bottom-sheet roots currently owned by this process. */
    @SuppressWarnings("unchecked")
    private static List<View> windowRoots(Activity activity) {
        List<View> roots = new ArrayList<>();
        Set<View> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        View row = panelRowReference.get();
        if (row != null && row.isAttachedToWindow()) {
            View panel = row.getRootView();
            if (seen.add(panel)) roots.add(panel);
        }
        View decor = activity == null || activity.getWindow() == null
                ? null : activity.getWindow().getDecorView();
        if (decor != null && seen.add(decor)) roots.add(decor);
        if (windowViewsUnavailable) return roots;
        try {
            Object value = windowViews();
            if (value instanceof List) {
                for (Object candidate : (List<Object>) value) {
                    if (candidate instanceof View && seen.add((View) candidate)) {
                        roots.add((View) candidate);
                    }
                }
            }
        } catch (Throwable ex) {
            // The bound native row still supplies its own panel root. A non-SDK lookup failure
            // must not break the share sheet, and it
            // is not retried: this runs on every layout pass, and a refusal stays a refusal. Once
            // the lookup has worked, a failure is the read itself, a window list changing under
            // the walk say, and the next pass reads it again.
            if (windowViewsReader == null) windowViewsUnavailable = true;
            Logger.printDebug(() -> "Could not enumerate secondary share sheet windows: "
                    + ex.getClass().getSimpleName());
        }
        return roots;
    }

    /*
     * WindowManagerGlobal and the read of its window list, looked up once. apply() runs on every
     * layout pass of the main window, sheet open or not, and it used to repeat the class lookup,
     * two method lookups and the access changes each time. Main thread only.
     */
    private static Object windowGlobal;
    private static Object windowViewsReader;
    private static boolean windowViewsUnavailable;

    private static Object windowViews() throws ReflectiveOperationException {
        if (windowViewsReader == null) {
            Class<?> globalClass = Class.forName("android.view.WindowManagerGlobal");
            Method getInstance = globalClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object global = getInstance.invoke(null);
            Object reader;
            try {
                Method getWindowViews = globalClass.getDeclaredMethod("getWindowViews");
                getWindowViews.setAccessible(true);
                reader = getWindowViews;
            } catch (NoSuchMethodException missingMethod) {
                Field views = globalClass.getDeclaredField("mViews");
                views.setAccessible(true);
                reader = views;
            }
            windowGlobal = global;
            windowViewsReader = reader;
        }
        return windowViewsReader instanceof Method
                ? ((Method) windowViewsReader).invoke(windowGlobal)
                : ((Field) windowViewsReader).get(windowGlobal);
    }

    /**
     * The hidden list, split the way ShareModelFilter and the checklist split it: on commas and
     * line breaks. Split on commas alone, a list typed one per line hid nothing on the sheet.
     */
    static List<String> entries(String stored) {
        List<String> entries = new ArrayList<>();
        if (stored == null) {
            return entries;
        }
        for (String part : stored.split("[,\\n]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                entries.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        return entries;
    }
}
