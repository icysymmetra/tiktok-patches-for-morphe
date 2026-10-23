/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import android.graphics.Color;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Says on a profile whether it follows you, and marks the accounts in a follow list that you
 * follow and that don't follow you back.
 *
 * <p>Both come from data TikTok already has. A profile's header is laid out by TikTok's server
 * from text items, and each item can reach the profile's common info, whose relation part holds
 * {@code followStatus} (yours towards them: 0 none, 1 following, 2 mutual) and
 * {@code followerStatus} (theirs towards you: 1 when they follow you). A follow list cell binds
 * the account's {@code User}, which carries the same two numbers. The label is a small text view
 * of ours placed under the @username, or beside it when the row it sits in is horizontal. Where
 * the layout is something else, nothing is added.
 */
public final class FollowStatus {
    static final int FOLLOWING = 1;
    static final int MUTUAL = 2;
    static final String FOLLOWS_YOU = "Follows you";
    static final String NOT_FOLLOWING_BACK = "Doesn't follow you back";
    /** Marks the label views this class adds, so a rebind finds and reuses them. */
    static final String LABEL_TAG = "hushfeed_follow_status";
    private static final int MAX_CLIMB = 4;
    private static final String USER_CLASS = "com.ss.android.ugc.aweme.profile.model.User";

    private static final Map<Class<?>, Field> USER_FIELDS = new ConcurrentHashMap<>();

    private FollowStatus() {
    }

    static boolean enabled() {
        return SettingsStatus.followStatusEnabled && Settings.SHOW_FOLLOW_STATUS.get();
    }

    /** The header label for a relation, or null when there is nothing to say. */
    @Nullable
    static String headerLabel(@Nullable Integer followStatus, @Nullable Integer followerStatus) {
        int mine = followStatus == null ? 0 : followStatus;
        int theirs = followerStatus == null ? 0 : followerStatus;
        if (theirs == FOLLOWING || mine == MUTUAL) return FOLLOWS_YOU;
        if (mine == FOLLOWING) return NOT_FOLLOWING_BACK;
        return null;
    }

    /** A follow list only marks the accounts you follow that don't follow back. */
    @Nullable
    static String listLabel(@Nullable Integer followStatus, @Nullable Integer followerStatus) {
        int mine = followStatus == null ? 0 : followStatus;
        int theirs = followerStatus == null ? 0 : followerStatus;
        return mine == FOLLOWING && theirs != FOLLOWING ? NOT_FOLLOWING_BACK : null;
    }

    /**
     * From TikTok's bind of each text item in a profile header, with the item's view and the
     * profile's common info. Only the item showing the @username gets the label.
     */
    public static void onHeaderText(View view, Object commonInfo) {
        try {
            if (!(view instanceof TextView)) return;
            if (!enabled()) {
                hideLabels(view);
                return;
            }
            if (commonInfo == null) return;
            Object profile = Reflect.property(commonInfo, "getUserProfileInfo", "userProfileInfo");
            Object relation = Reflect.property(commonInfo, "getUserRelationInfo", "userRelationInfo");
            String username = Reflect.string(profile, "getUsername", "username");
            if (username == null) return;
            String uid = Reflect.string(profile, "getUid", "uid");
            String label = isSignedInUser(uid) ? null : headerLabel(
                    asInteger(Reflect.property(relation, "getFollowStatus", "followStatus")),
                    asInteger(Reflect.property(relation, "getFollowerStatus", "followerStatus")));
            TextView text = (TextView) view;
            // The item sets its text after this call returns, so read it once the bind is done.
            text.post(() -> {
                try {
                    if (isHandle(text.getText(), username)) place(text, label, null);
                } catch (Throwable error) {
                    Logger.printException(() -> "Follow status: could not label the profile header", error);
                }
            });
        } catch (Throwable error) {
            Logger.printException(() -> "Follow status: could not read the profile header", error);
        }
    }

    /** From TikTok's bind of a follower or following list cell, with the cell and its item. */
    public static void onRelationCell(View cell, Object item) {
        try {
            if (!(cell instanceof ViewGroup)) return;
            Object user = enabled() ? userOf(item) : null;
            if (user == null) {
                hideLabels(cell, cell);
                return;
            }
            String handle = Reflect.string(user, "getUniqueId", "uniqueId");
            String nickname = Reflect.string(user, "getNickname", "nickname");
            String uid = Reflect.string(user, "getUid", "uid");
            String label = isSignedInUser(uid) ? null : listLabel(
                    asInteger(Reflect.property(user, "getFollowStatus", "followStatus")),
                    asInteger(Reflect.property(user, "getFollowerStatus", "followerStatus")));
            ViewGroup root = (ViewGroup) cell;
            root.post(() -> {
                try {
                    hideLabels(root, root);
                    if (label == null) return;
                    TextView anchor = findText(root, handle, true);
                    if (anchor == null) anchor = findText(root, nickname, false);
                    if (anchor != null) place(anchor, label, root);
                } catch (Throwable error) {
                    Logger.printException(() -> "Follow status: could not label a follow list cell", error);
                }
            });
        } catch (Throwable error) {
            Logger.printException(() -> "Follow status: could not read a follow list cell", error);
        }
    }

    /** Reads the account a follow list item holds: its one field of TikTok's User type. */
    @Nullable
    public static Object userOf(Object item) {
        if (item == null) return null;
        try {
            Class<?> type = item.getClass();
            Field field = USER_FIELDS.get(type);
            if (field == null) {
                for (Class<?> owner = type; owner != null && field == null; owner = owner.getSuperclass()) {
                    for (Field candidate : owner.getDeclaredFields()) {
                        if (USER_CLASS.equals(candidate.getType().getName())) {
                            candidate.setAccessible(true);
                            field = candidate;
                            break;
                        }
                    }
                }
                if (field == null) return null;
                USER_FIELDS.put(type, field);
            }
            return field.get(item);
        } catch (Throwable missing) {
            return null;
        }
    }

    static boolean isSignedInUser(@Nullable String uid) {
        if (uid == null) return false;
        String own = SignedInUser.id();
        return own != null && own.equals(uid);
    }

    @Nullable
    static Integer asInteger(@Nullable Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    /** The text TikTok shows, without the trailing spaces and badge placeholders it adds. */
    static String shown(@Nullable CharSequence text) {
        if (text == null) return "";
        int end = text.length();
        while (end > 0) {
            char last = text.charAt(end - 1);
            if (last != ' ' && last != ' ' && last != '￼') break;
            end--;
        }
        return text.subSequence(0, end).toString().trim();
    }

    static boolean isHandle(@Nullable CharSequence text, @Nullable String handle) {
        if (handle == null || handle.isEmpty()) return false;
        String value = shown(text);
        if (value.startsWith("@")) value = value.substring(1);
        String wanted = handle.startsWith("@") ? handle.substring(1) : handle;
        return value.equals(wanted);
    }

    @Nullable
    private static TextView findText(View view, @Nullable String value, boolean handle) {
        if (value == null || value.isEmpty()) return null;
        if (view instanceof TextView) {
            if (LABEL_TAG.equals(view.getTag())) return null;
            CharSequence text = ((TextView) view).getText();
            boolean match = handle ? isHandle(text, value) : shown(text).equals(value.trim());
            return match && view.getVisibility() == View.VISIBLE ? (TextView) view : null;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            TextView found = findText(group.getChildAt(index), value, handle);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Puts the label right after the anchor's row in the nearest vertical LinearLayout above it,
     * or beside the anchor when the only LinearLayout is a horizontal one. Any other layout gets
     * nothing, since a view added there without its own rules would land on top of something.
     * The climb never goes past {@code limit}, so a list cell's label stays inside the cell, where its
     * next bind can find it again.
     */
    static void place(TextView anchor, @Nullable String label, @Nullable View limit) {
        View child = anchor;
        LinearLayout row = null;
        View rowChild = null;
        ViewParent parent = anchor.getParent();
        for (int step = 0; step < MAX_CLIMB && parent instanceof ViewGroup; step++) {
            if (parent instanceof LinearLayout) {
                LinearLayout layout = (LinearLayout) parent;
                if (layout.getOrientation() == LinearLayout.VERTICAL) {
                    show(layout, child, anchor, label, true);
                    return;
                }
                if (row == null) {
                    row = layout;
                    rowChild = child;
                }
            }
            if (parent == limit) break;
            child = (View) parent;
            parent = parent.getParent();
        }
        if (row != null) show(row, rowChild, anchor, label, false);
    }

    private static void show(LinearLayout layout, View after, TextView anchor, @Nullable String label,
                             boolean below) {
        TextView view = labelIn(layout);
        if (label == null) {
            if (view != null) view.setVisibility(View.GONE);
            return;
        }
        if (view == null) {
            view = new TextView(anchor.getContext());
            view.setTag(LABEL_TAG);
            view.setSingleLine(true);
            view.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int gap = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, below ? 2 : 6,
                    anchor.getResources().getDisplayMetrics()));
            if (below) {
                // The row's own gravity, or none so the label lines up with its parent's like the row.
                params.topMargin = gap;
                params.gravity = gravityOf(after);
            } else {
                params.setMarginStart(gap);
                params.gravity = Gravity.CENTER_VERTICAL;
            }
            layout.addView(view, Math.min(layout.indexOfChild(after) + 1, layout.getChildCount()), params);
        }
        int color = anchor.getCurrentTextColor();
        view.setTextColor(Color.argb(Math.round(Color.alpha(color) * 0.7f),
                Color.red(color), Color.green(color), Color.blue(color)));
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, anchor.getTextSize() * 0.85f);
        view.setTypeface(anchor.getTypeface());
        view.setText(L10n.t(anchor.getContext(), label));
        view.setVisibility(View.VISIBLE);
    }

    private static int gravityOf(View child) {
        ViewGroup.LayoutParams params = child.getLayoutParams();
        int gravity = params instanceof LinearLayout.LayoutParams ? ((LinearLayout.LayoutParams) params).gravity : -1;
        return gravity > 0 ? gravity : -1;
    }

    @Nullable
    private static TextView labelIn(ViewGroup layout) {
        for (int index = 0; index < layout.getChildCount(); index++) {
            View child = layout.getChildAt(index);
            if (child instanceof TextView && LABEL_TAG.equals(child.getTag())) return (TextView) child;
        }
        return null;
    }

    /** Hides every label of ours near a header item, as a switch turned off asks. */
    private static void hideLabels(View view) {
        View root = view;
        for (int step = 0; step < MAX_CLIMB && root.getParent() instanceof View; step++) {
            root = (View) root.getParent();
        }
        hideLabels(root, view);
    }

    private static void hideLabels(View root, View origin) {
        if (root instanceof TextView) {
            if (root != origin && LABEL_TAG.equals(root.getTag())) root.setVisibility(View.GONE);
            return;
        }
        if (!(root instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) root;
        for (int index = 0; index < group.getChildCount(); index++) hideLabels(group.getChildAt(index), origin);
    }
}
