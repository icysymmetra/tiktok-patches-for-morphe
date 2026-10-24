/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.blockauthor;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.wellbeing.SessionBudget;
import java.lang.ref.WeakReference;

/** One native pager position, captured before an asynchronous account block. */
final class BlockFeedAdvance {
    private static final String PAGER = "com.ss.android.ugc.aweme.common.widget.VerticalViewPager";
    private static final String FAMILY = "block feed advance";
    private final WeakReference<View> pager;
    private final String videoId;
    private final int position;
    private boolean used;

    private BlockFeedAdvance(View pager, String videoId, int position) {
        this.pager = new WeakReference<>(pager);
        this.videoId = videoId;
        this.position = position;
    }

    static BlockFeedAdvance capture(VideoAuthor author) {
        Activity activity = Utils.getActivity();
        if (author == null || author.awemeId == null || author.awemeId.isEmpty()
                || !feedAvailable(activity)) return null;
        View pager = findPager(activity.findViewById(android.R.id.content));
        if (pager == null) return null;
        Object position = Reflect.required(pager, "getCurrentItem", FAMILY);
        Object state = Reflect.required(pager, "getScrollState", FAMILY);
        if (!(position instanceof Integer) || (Integer) position < 0
                || !Integer.valueOf(0).equals(state)) return null;
        return new BlockFeedAdvance(pager, author.awemeId, (Integer) position);
    }

    boolean advance() {
        if (used) return false;
        used = true;
        Activity activity = Utils.getActivity();
        VideoAuthor current = CurrentVideoAuthor.get();
        View view = pager.get();
        if (!feedAvailable(activity) || current == null || !videoId.equals(current.awemeId)
                || view == null || !view.isAttachedToWindow() || !view.isShown()
                || view.getRootView() != activity.getWindow().getDecorView()
                || !view.getGlobalVisibleRect(new Rect())
                || !Integer.valueOf(position).equals(Reflect.required(view, "getCurrentItem", FAMILY))
                || !Integer.valueOf(0).equals(Reflect.required(view, "getScrollState", FAMILY))
                || !view.canScrollVertically(1)) return false;
        try {
            // The native pager clamps to its adapter and performs its own selection callbacks.
            // Never synthesize a gesture or guess an obfuscated field when this contract moves.
            view.getClass().getMethod("setCurrentItem", int.class).invoke(view, position + 1);
            boolean advanced = Integer.valueOf(position + 1)
                    .equals(Reflect.required(view, "getCurrentItem", FAMILY));
            if (advanced) HookStatus.bound(FAMILY, "native next item");
            return advanced;
        } catch (Exception error) {
            HookStatus.missingMember(FAMILY, "method", PAGER, "setCurrentItem(int)");
            Logger.printException(() -> "Could not advance after blocking; swipe remains available", error);
            return false;
        }
    }

    private static boolean feedAvailable(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && activity.hasWindowFocus() && !SessionBudget.isLocked()
                && FeedVisibility.isOnFeed(activity) && !FeedVisibility.isCommentSheetVisible(activity)
                && !FeedVisibility.isStoryVisible(activity);
    }

    private static View findPager(View view) {
        if (view == null || !view.isShown() || !view.getGlobalVisibleRect(new Rect())) return null;
        if (PAGER.equals(view.getClass().getName())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findPager(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
