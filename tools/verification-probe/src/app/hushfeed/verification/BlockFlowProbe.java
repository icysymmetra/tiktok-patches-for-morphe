package app.hushfeed.verification;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Device-only UI acceptance. Replaces the transport in memory, never blocks a real account. */
final class BlockFlowProbe {
    private static boolean running;
    private static final String PREFIX = "app.morphe.extension.tiktok.blockauthor.";

    static void run(ClassLoader loader, String mode) throws Exception {
        if (running) throw new IllegalStateException("block UI test already running");
        if (!"confirmed".equals(mode) && !"undo".equals(mode)
                && !"rejected".equals(mode) && !"unconfirmed".equals(mode)) {
            throw new IllegalArgumentException("expected confirmed, undo, rejected or unconfirmed");
        }
        Activity activity = (Activity) loader.loadClass("app.morphe.extension.shared.Utils")
                .getMethod("getActivity").invoke(null);
        if (activity == null || !activity.hasWindowFocus()) throw new IllegalStateException("no focused TikTok");
        Class<?> overlay = loader.loadClass(PREFIX + "BlockAuthorOverlay");
        Field busy = field(overlay, "requestInFlight");
        if (busy.getBoolean(null)) throw new IllegalStateException("real block in progress");
        View button = (View) ((WeakReference<?>) field(overlay, "buttonReference").get(null)).get();
        if (button == null || !button.isShown() || !button.isEnabled()) {
            throw new IllegalStateException("block control is not visible and enabled");
        }
        ViewGroup root = activity.findViewById(android.R.id.content);
        View pager = pager(root);
        if (pager == null) throw new IllegalStateException("no native feed pager");
        Method currentItem = pager.getClass().getMethod("getCurrentItem");
        int before = (Integer) currentItem.invoke(pager);
        Class<?> service = loader.loadClass(PREFIX + "BlockAuthorService");
        Field cachedService = field(service, "cachedService");
        Field cachedMethod = field(service, "cachedBlockMethod");
        Object oldService = cachedService.get(null);
        Object oldMethod = cachedMethod.get(null);
        Transport transport = new Transport(mode);
        cachedService.set(null, transport);
        cachedMethod.set(null, Transport.class.getMethod("block", String.class, String.class, int.class, int.class));
        running = true;
        Handler main = new Handler(Looper.getMainLooper());
        long start = SystemClock.uptimeMillis();
        final boolean[] observed = {false};
        final long[] shownAt = {0};
        Runnable poll = new Runnable() {
            @Override public void run() {
                try {
                    View chip = (View) ((WeakReference<?>) field(overlay, "undoReference").get(null)).get();
                    if (!observed[0] && chip instanceof TextView && chip.getWidth() > 0
                            && "Unblock".contentEquals(((TextView) chip).getText())) {
                        observed[0] = true;
                        shownAt[0] = SystemClock.uptimeMillis();
                        int[] point = new int[2];
                        chip.getLocationOnScreen(point);
                        Log.i(Probe.TAG, "block-flow chip latencyMs=" + (shownAt[0] - start)
                                + " x=" + point[0] + " y=" + point[1]
                                + " width=" + chip.getWidth() + " height=" + chip.getHeight());
                        chip.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                            @Override public void onViewAttachedToWindow(View view) { }
                            @Override public void onViewDetachedFromWindow(View view) {
                                Log.i(Probe.TAG, "block-flow chip removedAfterMs=" + (SystemClock.uptimeMillis() - shownAt[0]));
                            }
                        });
                        if ("undo".equals(mode)) main.postDelayed(chip::performClick, 300);
                    }
                    if (SystemClock.uptimeMillis() - start < 2_700) main.postDelayed(this, 20);
                } catch (Exception error) {
                    Log.e(Probe.TAG, "block-flow observation failed: " + error.getClass().getSimpleName());
                }
            }
        };
        main.postDelayed(() -> {
            try {
                Log.i(Probe.TAG, "block-flow result mode=" + mode + " syntheticTransport=true realAccountRequests=0"
                        + " blocks=" + transport.blocks + " unblocks=" + transport.unblocks
                        + " undoOriginal=" + transport.undoOriginal + " pagerDelta=" + ((Integer) currentItem.invoke(pager) - before)
                        + " chipObserved=" + observed[0] + " busy=" + busy.getBoolean(null));
            } catch (Exception error) {
                Log.e(Probe.TAG, "block-flow result failed: " + error.getClass().getSimpleName());
            } finally {
                try {
                    Method dismiss = overlay.getDeclaredMethod("dismissUndo");
                    dismiss.setAccessible(true);
                    dismiss.invoke(null);
                    cachedService.set(null, oldService);
                    cachedMethod.set(null, oldMethod);
                }
                catch (Exception error) { Log.e(Probe.TAG, "block-flow restore failed"); }
                running = false;
            }
        }, 3_000);
        button.performClick();
        main.post(poll);
        Log.i(Probe.TAG, "block-flow started syntheticTransport=true realAccountRequests=0");
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field value = type.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }

    private static View pager(View view) {
        if (!view.isShown()) return null;
        if ("com.ss.android.ugc.aweme.common.widget.VerticalViewPager".equals(view.getClass().getName())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = pager(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }

    public static final class Transport {
        private final String mode;
        volatile int blocks;
        volatile int unblocks;
        volatile boolean undoOriginal;
        private String original;
        Transport(String mode) { this.mode = mode; }
        public Call block(String uid, String sec, int type, int source) {
            if (type == 1) { blocks++; original = uid; }
            else { unblocks++; undoOriginal = uid.equals(original); }
            return new Call("unconfirmed".equals(mode) ? null : "rejected".equals(mode) ? 9 : 0);
        }
    }

    public static final class Call {
        private final Integer status;
        Call(Integer status) { this.status = status; }
        public Reply execute() { return new Reply(status); }
    }

    public static final class Reply {
        public final Integer statusCode;
        Reply(Integer status) { statusCode = status; }
        public Reply body() { return this; }
    }
}
