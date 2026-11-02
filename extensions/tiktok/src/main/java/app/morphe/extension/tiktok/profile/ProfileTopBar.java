/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.profile;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceIdCache;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Takes the profile views button and Add friends off the top bar of your Profile tab (#158).
 *
 * <p>The bar is a TuxNavBar. On 47.1.4 its end row holds, from the left, the profile views
 * entrance, Add friends and the menu button (S22, 2026-10-14). The entrance is one view of
 * TikTok's own, a FrameLayout with the views capsule inside it, whether it shows footprints or
 * "2 profile views". Add friends and the menu are icons the profile's nav bar builds in code,
 * with no id. Only a row holding the entrance is your own profile's: another profile has a bell
 * and a More button there and no entrance, and nothing is taken from that row. A row of any
 * other shape keeps everything, and with debug on its shape is logged once.
 *
 * <p>Runs on every layout pass of the window, from the video overlay hider's listener, so a button
 * TikTok shows again is gone before it's drawn. Off, or paused, puts back what was there.
 */
public final class ProfileTopBar {
    /** TuxNavBar's end row, the icons at the right of a page's top bar. */
    private static final String[] END_ROW_IDS = {"47.4.3:pu9"};
    /** The capsule inside the profile views entrance, which keeps it in both of its looks. */
    private static final String[] VIEWS_CAPSULE_IDS = {"47.4.3:t_v"};
    private static final String FAMILY = "profile top bar";
    private static final ResourceIdCache RESOURCE_IDS = new ResourceIdCache();

    /** Entrances this class hid, with the visibility each had before. */
    private static final Map<View, Integer> VIEWS_HIDDEN = new WeakHashMap<>();
    /** Add friends buttons this class hid, with the visibility each had before. */
    private static final Map<View, Integer> FRIENDS_HIDDEN = new WeakHashMap<>();

    /** The last row shape logged, so a row that keeps its buttons is described once. */
    private static volatile String lastShape;

    private ProfileTopBar() {
    }

    /** One pass over the window under {@code root}. */
    public static void apply(View root) {
        boolean views = Settings.HIDE_PROFILE_VIEWS_BUTTON.get();
        boolean friends = Settings.HIDE_PROFILE_ADD_FRIENDS.get();
        if (!views) restore(VIEWS_HIDDEN);
        if (!friends) restore(FRIENDS_HIDDEN);
        if ((!views && !friends) || root == null) return;
        int rowId = identifier(root, END_ROW_IDS);
        int capsuleId = identifier(root, VIEWS_CAPSULE_IDS);
        if (rowId == 0 || capsuleId == 0) return;
        List<ViewGroup> rows = new ArrayList<>();
        collect(root, rowId, rows);
        for (ViewGroup row : rows) {
            View entrance = entrance(row, capsuleId);
            if (entrance == null) continue;
            if (views) hide(entrance, VIEWS_HIDDEN);
            if (!friends) continue;
            View addFriends = addFriends(row, entrance);
            if (addFriends != null) {
                hide(addFriends, FRIENDS_HIDDEN);
            } else {
                logShape(row, entrance);
            }
        }
    }

    /**
     * The row's profile views entrance: its one child holding the capsule, never its last child,
     * which is the menu. Null for a row with none (another profile, any other page's bar) or with
     * a shape this wasn't written for.
     */
    static View entrance(ViewGroup row, int capsuleId) {
        int count = row.getChildCount();
        View found = null;
        for (int i = 0; i < count; i++) {
            View child = row.getChildAt(i);
            if (child.findViewById(capsuleId) == null) continue;
            if (found != null) return null;
            found = child;
        }
        if (found == null || found == row.getChildAt(count - 1)) return null;
        return found;
    }

    /**
     * Add friends: the one icon in the row with no id that isn't the entrance or the menu, the
     * row's last child. Null when there is no such icon or more than one.
     */
    static View addFriends(ViewGroup row, View entrance) {
        int last = row.getChildCount() - 1;
        View found = null;
        for (int i = 0; i < last; i++) {
            View child = row.getChildAt(i);
            if (child == entrance || !(child instanceof ImageView) || child.getId() != View.NO_ID) continue;
            if (found != null) return null;
            found = child;
        }
        return found;
    }

    /** Hides a view, noting the visibility it had; a view TikTok showed again is noted again. */
    private static void hide(View view, Map<View, Integer> held) {
        int visibility = view.getVisibility();
        if (visibility == View.GONE) return;
        held.put(view, visibility);
        view.setVisibility(View.GONE);
    }

    /** Puts back every view in {@code held} that is still the way this class left it. */
    private static void restore(Map<View, Integer> held) {
        if (held.isEmpty()) return;
        for (Map.Entry<View, Integer> entry : new ArrayList<>(held.entrySet())) {
            View view = entry.getKey();
            if (view != null && view.getVisibility() == View.GONE) view.setVisibility(entry.getValue());
        }
        held.clear();
    }

    private static void collect(View view, int id, List<ViewGroup> out) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        if (group.getId() == id) out.add(group);
        for (int i = 0, count = group.getChildCount(); i < count; i++) {
            collect(group.getChildAt(i), id, out);
        }
    }

    private static int identifier(View view, String[] names) {
        for (String name : names) {
            int id = RESOURCE_IDS.resolve(view.getResources(), view.getContext().getPackageName(), name, false);
            if (id != 0) {
                HookStatus.bound(FAMILY, name);
                return id;
            }
        }
        HookStatus.missingViewId(FAMILY, String.join("|", names));
        return 0;
    }

    private static void logShape(ViewGroup row, View entrance) {
        StringBuilder shape = new StringBuilder();
        for (int i = 0, count = row.getChildCount(); i < count; i++) {
            View child = row.getChildAt(i);
            if (i > 0) shape.append(", ");
            shape.append(child == entrance ? "entrance" : child.getClass().getSimpleName())
                    .append(child.getId() == View.NO_ID ? "" : " (id)");
        }
        String text = shape.toString();
        if (text.equals(lastShape)) return;
        lastShape = text;
        Logger.printDebug(() -> "Profile top bar kept Add friends, row is: " + text);
    }

    /** For tests: forgets which views this class hid. */
    static void resetForTests() {
        VIEWS_HIDDEN.clear();
        FRIENDS_HIDDEN.clear();
        lastShape = null;
        RESOURCE_IDS.clear();
    }

    /** For tests: supplies the two ids the way the running build would. */
    static void putIdsForTests(String packageName, int rowId, int capsuleId) {
        RESOURCE_IDS.putForTests(packageName, END_ROW_IDS[0], rowId);
        RESOURCE_IDS.putForTests(packageName, VIEWS_CAPSULE_IDS[0], capsuleId);
    }
}
