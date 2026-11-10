package app.hushfeed.verification;

import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewParent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Android adapter for the bounded evidence contract. No bind, filter, layout or settings calls. */
final class SearchLynxSnapshot {
    private SearchLynxSnapshot() {}

    static List<String> read(ClassLoader loader) {
        if (Looper.myLooper() != Looper.getMainLooper()) return unavailable("main-thread-required");
        try {
            Class<?> owner = Class.forName("app.morphe.extension.tiktok.feedfilter.SearchLynxCards", false, loader);
            Field lockField = owner.getDeclaredField("LOCK"), boundField = owner.getDeclaredField("BOUND");
            lockField.setAccessible(true); boundField.setAccessible(true);
            Object lock = lockField.get(null);
            Object bound = boundField.get(null);
            if (lock == null || !(bound instanceof Map)) return unavailable("registry-schema");
            Method template = owner.getDeclaredMethod("lynxTemplate", Object.class);
            template.setAccessible(true);
            return SearchLynxEvidence.snapshot(lock, (Map<?, ?>) bound, SearchLynxSnapshot::row,
                    schema -> {
                        Object value = template.invoke(null, schema);
                        return value instanceof String ? (String) value : null;
                    }, SystemClock.uptimeMillis(), System.currentTimeMillis());
        } catch (Throwable failure) {
            // A native Error must not reach Probe's generic stack-trace logger either. Report the
            // unavailable observation, never exception text or a successful empty snapshot.
            return unavailable("registry-unavailable");
        }
    }

    private static List<String> unavailable(String status) {
        return Collections.singletonList("{\"schema\":1,\"kind\":\"snapshot\",\"status\":\""
                + status + "\",\"missingFields\":\"unknown\"}");
    }

    private static SearchLynxEvidence.Row row(Object value) {
        if (!(value instanceof View)) return null;
        View view = (View) value;
        Rect visible = new Rect();
        boolean clipped = view.getGlobalVisibleRect(visible);
        Rect localVisible = new Rect();
        clipped = view.getLocalVisibleRect(localVisible) && clipped;
        int[] rootAt = new int[2];
        view.getRootView().getLocationOnScreen(rootAt);
        visible.offset(rootAt[0], rootAt[1]);
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        Rect selfClip = view.getClipBounds();
        float alpha = view.getAlpha();
        List<String> parents = new ArrayList<>();
        ViewParent parent = view.getParent();
        for (int depth = 0; parent instanceof View && depth < 64; depth++) {
            View ancestor = (View) parent;
            if (parents.size() < 8) parents.add(ancestor.getClass().getName());
            alpha *= ancestor.getAlpha();
            parent = ancestor.getParent();
        }
        if (parent instanceof View) return null; // Cannot verify all ancestor opacity within the bound.
        return new SearchLynxEvidence.Row(view.getClass().getName(), parents, view.isAttachedToWindow(),
                view.isShown(), view.getWindowVisibility() == View.VISIBLE, clipped, alpha,
                at[0], at[1], view.getWidth(), view.getHeight(), visible.left, visible.top,
                visible.right, visible.bottom, selfClip == null ? null
                        : new int[]{selfClip.left, selfClip.top, selfClip.right, selfClip.bottom},
                new int[]{localVisible.left, localVisible.top, localVisible.right, localVisible.bottom});
    }
}
