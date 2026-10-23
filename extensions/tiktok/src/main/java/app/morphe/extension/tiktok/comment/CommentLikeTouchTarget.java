/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import android.annotation.TargetApi;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Adds pointer halos in unused row space: around the comment heart (Larger comment Like target),
 * and around the control Block from comment puts in the thumbs down's place, grown to 48 x 48 dp
 * where the row has room. Native listeners, layout and semantics stay intact.
 *
 * <p>A view holds one TouchDelegate, so every halo on a host goes through one {@link Host}, which
 * hands each press to the halo it landed in and nowhere else.
 */
public final class CommentLikeTouchTarget {
    private static final WeakHashMap<View, Boolean> ROWS = new WeakHashMap<>();
    private static final WeakHashMap<View, WeakReference<Binding>> BINDINGS = new WeakHashMap<>();

    /** Receives only the native Like install proven by Comment.isUserDigged at patch time. */
    public static void setNativeListener(View view, View.OnTouchListener listener) {
        if (view == null) return;
        Binding binding = binding(view);
        if (binding != null) binding.cancel();
        view.setOnTouchListener(listener);
        HookStatus.bound("comment like target", "native Like touch install");
        if (!Settings.LARGER_COMMENT_LIKE_TARGET.get()) return;
        attach(view, binding, false);
    }

    /**
     * The control Block from comment took over. On the S25's dark sheet its clickable bounds were
     * about 40 x 24 dp, well under the 48 dp a finger needs.
     */
    public static void attachBlockHalo(View button) {
        if (button == null) return;
        attach(button, binding(button), true);
    }

    /** The control goes back to TikTok, and its halo with it. */
    public static void detachBlockHalo(View button) {
        Binding binding = button == null ? null : binding(button);
        if (binding == null || !binding.block) return;
        binding.wanted = false;
        binding.release();
    }

    /** A row boundary prevents a halo from reaching another comment or the sheet's controls. */
    public static void onCellBound(View row) {
        if (row == null) return;
        if (!Settings.LARGER_COMMENT_LIKE_TARGET.get() && !Settings.BLOCK_FROM_COMMENT.get()
                && BINDINGS.isEmpty()) return;
        ROWS.put(row, Boolean.TRUE);
        visit(row);
    }

    private static void attach(View view, Binding binding, boolean block) {
        try {
            if (binding == null) {
                binding = new Binding(view, block);
                BINDINGS.put(view, new WeakReference<>(binding));
                view.addOnAttachStateChangeListener(binding);
            }
            binding.wanted = true;
            view.post(binding);
        } catch (Exception error) {
            Logger.printException(() -> "Could not expand a comment control's target", error);
        }
    }

    private static Binding binding(View view) {
        WeakReference<Binding> reference = BINDINGS.get(view);
        return reference == null ? null : reference.get();
    }

    private static void visit(View view) {
        Binding binding = binding(view);
        if (binding != null) {
            binding.cancel(); // A pending press must never follow a recycled row's next model.
            view.post(binding);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) visit(group.getChildAt(i));
        }
    }

    /** The one delegate a host holds, shared by every halo on it. */
    private static final class Host extends TouchDelegate {
        final ViewGroup view;
        final List<Binding> bindings = new ArrayList<>(2);
        Binding owner;

        Host(ViewGroup view) {
            super(new Rect(), view);
            this.view = view;
        }

        void add(Binding binding) {
            if (!bindings.contains(binding)) bindings.add(binding);
        }

        void remove(Binding binding) {
            bindings.remove(binding);
            if (owner == binding) owner = null;
            if (bindings.isEmpty() && view.getTouchDelegate() == this) view.setTouchDelegate(null);
        }

        /** A press goes to the halo it landed in; the nearer control wins where two would take it. */
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                owner = null;
                Binding pick = null;
                long best = Long.MAX_VALUE;
                for (Binding binding : new ArrayList<>(bindings)) {
                    if (!binding.claims(event)) continue;
                    long distance = binding.distanceSquared(event.getX(), event.getY());
                    if (distance < best) {
                        best = distance;
                        pick = binding;
                    }
                }
                if (pick == null) return false;
                owner = pick;
                return pick.onTouchEvent(event);
            }
            Binding current = owner;
            if (current == null) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) owner = null;
            return current.onTouchEvent(event);
        }

        @TargetApi(29)
        @Override public AccessibilityNodeInfo.TouchDelegateInfo getTouchDelegateInfo() {
            Map<Region, View> regions = new HashMap<>();
            for (Binding binding : bindings) {
                if (binding.enabled() && binding.geometry(view)) regions.put(new Region(binding.area), binding.target);
            }
            return regions.isEmpty() ? super.getTouchDelegateInfo()
                    : new AccessibilityNodeInfo.TouchDelegateInfo(regions);
        }
    }

    private static final class Binding implements Runnable, View.OnAttachStateChangeListener {
        final View target;
        final boolean block;
        final int padding, minimum, slop;
        final Rect original = new Rect();
        final Region area = new Region();
        Host host;
        volatile boolean wanted;
        boolean active, discard;
        float downX, downY;
        long downTime;

        Binding(View target, boolean block) {
            this.target = target;
            this.block = block;
            float density = target.getResources().getDisplayMetrics().density;
            padding = Math.round(12 * density);
            minimum = Math.round(48 * density);
            slop = ViewConfiguration.get(target.getContext()).getScaledTouchSlop();
        }

        boolean enabled() {
            return block ? wanted && Settings.BLOCK_FROM_COMMENT.get() : Settings.LARGER_COMMENT_LIKE_TARGET.get();
        }

        String family() {
            return block ? "comment block target" : "comment like target";
        }

        @Override public void run() {
            if (!enabled() || !target.isAttachedToWindow()) {
                release();
                return;
            }
            if (host != null) {
                // Another owner replacing our delegate wins. Never overwrite its new behavior.
                if (host.view.getTouchDelegate() != host) return;
                if (geometry(host.view)) return;
                release();
            }
            View row = target;
            while (!ROWS.containsKey(row) && row.getParent() instanceof View) row = (View) row.getParent();
            if (!ROWS.containsKey(row)) return;
            View ancestor = target;
            ViewGroup best = null;
            long bestReach = 0;
            while (ancestor != row && ancestor.getParent() instanceof ViewGroup) {
                ViewGroup candidate = (ViewGroup) ancestor.getParent();
                TouchDelegate existing = candidate.getTouchDelegate();
                // Existing delegates may have invisible regions of their own. Leave them alone.
                if (existing != null && !(existing instanceof Host)) break;
                if (geometry(candidate)) {
                    // The heart takes the first ancestor with room. The block control's own row
                    // of buttons is as tall as it is, so it takes whichever ancestor lets it
                    // reach furthest toward 48 dp.
                    if (!block) {
                        attachTo(candidate);
                        return;
                    }
                    Rect bounds = area.getBounds();
                    long reach = (long) bounds.width() * bounds.height();
                    if (reach > bestReach) {
                        bestReach = reach;
                        best = candidate;
                    }
                }
                ancestor = candidate;
            }
            if (best != null && geometry(best)) attachTo(best);
        }

        private void attachTo(ViewGroup candidate) {
            TouchDelegate existing = candidate.getTouchDelegate();
            Host shared = existing instanceof Host ? (Host) existing : new Host(candidate);
            shared.add(this);
            host = shared;
            if (existing == null) candidate.setTouchDelegate(shared);
            HookStatus.bound(family(), "blank-space halo attached");
        }

        /** Subtract every visible sibling, including nonclickable text, not just other buttons. */
        boolean geometry(ViewGroup candidate) {
            if (!target.isShown() || !target.isEnabled() || !candidate.isEnabled() || candidate.getWidth() == 0) return false;
            View ancestor = target;
            while (ancestor != candidate && ancestor.getParent() instanceof View) ancestor = (View) ancestor.getParent();
            if (ancestor != candidate) return false;
            original.set(0, 0, target.getWidth(), target.getHeight());
            candidate.offsetDescendantRectToMyCoords(target, original);
            Rect expanded = new Rect(original);
            if (block) {
                // Only as far as 48 dp across and down, not a fixed ring: the control is wider
                // than it is tall, and the next row's text sits close below it. What one side
                // lacks the room for, the other side takes, so a neighbour flush against the
                // control doesn't leave it short.
                int[] room = room(candidate);
                int[] across = split(minimum - original.width(), room[0], room[2]);
                int[] down = split(minimum - original.height(), room[1], room[3]);
                expanded.set(original.left - across[0], original.top - down[0],
                        original.right + across[1], original.bottom + down[1]);
            } else {
                expanded.inset(-padding, -padding);
            }
            if (!expanded.intersect(0, 0, candidate.getWidth(), candidate.getHeight())) return false;
            area.set(expanded);
            View current = target;
            while (current != candidate && current.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) current.getParent();
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View sibling = parent.getChildAt(i);
                    if (sibling == current || sibling.getVisibility() != View.VISIBLE) continue;
                    Rect occupied = new Rect(0, 0, sibling.getWidth(), sibling.getHeight());
                    candidate.offsetDescendantRectToMyCoords(sibling, occupied);
                    // Don't claim a diagonal corner on the far side of the neighboring button.
                    if (occupied.top < original.bottom && occupied.bottom > original.top &&
                            (occupied.right <= original.left || occupied.left >= original.right)) {
                        occupied.top = expanded.top;
                        occupied.bottom = expanded.bottom;
                    } else if (occupied.left < original.right && occupied.right > original.left &&
                            (occupied.bottom <= original.top || occupied.top >= original.bottom)) {
                        occupied.left = expanded.left;
                        occupied.right = expanded.right;
                    }
                    area.op(occupied, Region.Op.DIFFERENCE);
                }
                current = parent;
            }
            Region added = new Region(area);
            added.op(original, Region.Op.DIFFERENCE);
            return current == candidate && !added.isEmpty();
        }

        /**
         * Free space beside the control up to the first sibling sharing its row or column, or the
         * host's edge: left, top, right, bottom. A sibling off at a diagonal limits nothing here;
         * the subtraction below still keeps its corner clear.
         */
        private int[] room(ViewGroup candidate) {
            int[] room = {original.left, original.top,
                    candidate.getWidth() - original.right, candidate.getHeight() - original.bottom};
            View current = target;
            while (current != candidate && current.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) current.getParent();
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View sibling = parent.getChildAt(i);
                    if (sibling == current || sibling.getVisibility() != View.VISIBLE) continue;
                    // An empty container takes no room. On 47.1.3 a zero-height view sits right under
                    // every comment's controls and held the block's halo to the control's own height.
                    if (sibling.getWidth() == 0 || sibling.getHeight() == 0) continue;
                    Rect occupied = new Rect(0, 0, sibling.getWidth(), sibling.getHeight());
                    candidate.offsetDescendantRectToMyCoords(sibling, occupied);
                    if (occupied.top < original.bottom && occupied.bottom > original.top) {
                        if (occupied.right <= original.left) room[0] = Math.min(room[0], original.left - occupied.right);
                        if (occupied.left >= original.right) room[2] = Math.min(room[2], occupied.left - original.right);
                    } else if (occupied.left < original.right && occupied.right > original.left) {
                        if (occupied.bottom <= original.top) room[1] = Math.min(room[1], original.top - occupied.bottom);
                        if (occupied.top >= original.bottom) room[3] = Math.min(room[3], occupied.top - original.bottom);
                    }
                }
                current = parent;
            }
            return room;
        }

        /** A shortfall shared out on two sides: half each, then the rest where there is room. */
        private static int[] split(int shortfall, int before, int after) {
            if (shortfall <= 0) return new int[]{0, 0};
            int first = Math.max(0, Math.min(before, (shortfall + 1) / 2));
            int second = Math.max(0, Math.min(after, shortfall - first));
            first = Math.max(0, Math.min(before, shortfall - second));
            return new int[]{first, second};
        }

        /** Whether a single-finger press at this point is in this halo and not on the control itself. */
        boolean claims(MotionEvent event) {
            return enabled() && host != null && event.getPointerCount() == 1 && geometry(host.view)
                    && area.contains((int) event.getX(), (int) event.getY())
                    && !original.contains((int) event.getX(), (int) event.getY());
        }

        /** Squared distance from a point to the control's own bounds, zero inside them. */
        long distanceSquared(float x, float y) {
            float dx = x < original.left ? original.left - x : x > original.right ? x - original.right : 0;
            float dy = y < original.top ? original.top - y : y > original.bottom ? y - original.bottom : 0;
            return (long) (dx * dx + dy * dy);
        }

        boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                cancel();
                discard = false;
                if (!claims(event)) return false;
                downX = event.getX();
                downY = event.getY();
                downTime = event.getEventTime();
                active = forward(event, MotionEvent.ACTION_DOWN);
                return active;
            }
            if (!active && !discard) return false;
            if (active && (action == MotionEvent.ACTION_CANCEL || event.getPointerCount() != 1 ||
                    !enabled() || !target.isShown() || !target.isEnabled() ||
                    Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop ||
                    event.getEventTime() - downTime >= ViewConfiguration.getLongPressTimeout())) cancel();
            if (active) {
                if (action == MotionEvent.ACTION_UP) active = false;
                forward(event, action);
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) discard = false;
            return true;
        }

        boolean forward(MotionEvent event, int action) {
            MotionEvent copy = MotionEvent.obtain(event);
            try {
                copy.setAction(action);
                copy.setLocation(target.getWidth() / 2f, target.getHeight() / 2f);
                return target.dispatchTouchEvent(copy);
            } finally { copy.recycle(); }
        }

        void cancel() {
            if (!active) return;
            active = false;
            discard = true;
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_CANCEL, 0, 0, 0);
            try { forward(event, MotionEvent.ACTION_CANCEL); } finally { event.recycle(); }
        }

        void release() {
            cancel();
            if (host != null) host.remove(this);
            host = null;
        }

        @Override public void onViewAttachedToWindow(View view) { view.post(this); }
        @Override public void onViewDetachedFromWindow(View view) { view.removeCallbacks(this); release(); }
    }

    private CommentLikeTouchTarget() { }
}
