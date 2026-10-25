package app.morphe.extension.tiktok.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.GlobalLayoutHook;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class GlobalLayoutHookTest {
    @Test public void replacingRootsDetachesTheOldListenerAndDetachStopsTheNewOne() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout first = new FrameLayout(activity);
        FrameLayout second = new FrameLayout(activity);
        AtomicInteger calls = new AtomicInteger();
        GlobalLayoutHook hook = new GlobalLayoutHook();

        assertTrue(hook.install(first, calls::incrementAndGet));
        first.getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals(1, calls.get());
        assertFalse(hook.install(first, calls::incrementAndGet));

        assertTrue(hook.install(second, calls::incrementAndGet));
        first.getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals(1, calls.get());
        second.getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals(2, calls.get());

        hook.detach();
        second.getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals(2, calls.get());
    }

    /**
     * A listener added before its root reached a window moves to the window's observer when the
     * root is attached, and the observer it was added to reports dead. Installing again on that
     * root added a second listener, so every layout pass ran twice, and detach couldn't reach it.
     */
    @Test public void aRootAttachedAfterInstallKeepsOneListenerThatDetachStillReaches() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            FrameLayout root = new FrameLayout(activity);
            AtomicInteger calls = new AtomicInteger();
            GlobalLayoutHook hook = new GlobalLayoutHook();

            assertTrue(hook.install(root, calls::incrementAndGet));
            activity.setContentView(root);
            controller.visible();
            assertTrue("the root never reached a window", root.isAttachedToWindow());

            assertFalse(hook.install(root, calls::incrementAndGet));
            calls.set(0);
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            assertEquals("the pass ran more than once", 1, calls.get());

            hook.detach();
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            assertEquals("detach left the listener on the window", 1, calls.get());
        }
    }

    @Test public void theSameDialogReopensWithOneListenerWithoutAnActivityLayout() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Dialog dialog = new Dialog(activity);
            dialog.setContentView(new FrameLayout(activity));
            GlobalLayoutHook hook = new GlobalLayoutHook();
            AtomicInteger calls = new AtomicInteger();
            AtomicInteger activityLayouts = new AtomicInteger();
            activity.getWindow().getDecorView().getViewTreeObserver()
                    .addOnGlobalLayoutListener(activityLayouts::incrementAndGet);
            try {
                dialog.show();
                ViewGroup root = (ViewGroup) dialog.getWindow().getDecorView();
                ViewTreeObserver first = root.getViewTreeObserver();
                assertTrue(hook.install(root, calls::incrementAndGet));
                first.dispatchOnGlobalLayout();
                assertEquals(1, calls.get());

                activityLayouts.set(0);
                dialog.dismiss();
                dialog.show();
                assertSame("the Dialog replaced its root", root, dialog.getWindow().getDecorView());
                ViewTreeObserver reopened = root.getViewTreeObserver();
                assertEquals("an activity layout intervened", 0, activityLayouts.get());

                assertFalse(hook.install(root, calls::incrementAndGet));
                assertFalse(hook.install(root, calls::incrementAndGet));
                assertSame("the hook must register the root's current observer", reopened,
                        ReflectionHelpers.getField(hook, "observer"));
                calls.set(0);
                reopened.dispatchOnGlobalLayout();
                assertEquals("the reopened Dialog needs exactly one callback", 1, calls.get());

                hook.detach();
                reopened.dispatchOnGlobalLayout();
                assertEquals("detach left the listener on the reopened Dialog", 1, calls.get());
            } finally {
                hook.detach();
                dialog.dismiss();
            }
        }
    }

    @Test public void anAliveStaleObserverLosesItsListenerWhenTheSameRootReturnsANewObserver() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            ObserverRoot root = new ObserverRoot(controller.get());
            GlobalLayoutHook hook = new GlobalLayoutHook();
            AtomicInteger calls = new AtomicInteger();
            ViewTreeObserver first = root.getViewTreeObserver();
            try {
                assertTrue(hook.install(root, calls::incrementAndGet));
                first.dispatchOnGlobalLayout();
                assertEquals(1, calls.get());
                root.replaceObserver();
                ViewTreeObserver current = root.getViewTreeObserver();
                assertNotSame(first, current);
                assertTrue("the stale observer is alive", first.isAlive());
                assertTrue(current.isAlive());
                // Attachment can also move an already registered listener to the current observer.
                current.addOnGlobalLayoutListener(ReflectionHelpers.getField(hook, "listener"));

                assertFalse(hook.install(root, calls::incrementAndGet));
                assertFalse(hook.install(root, calls::incrementAndGet));
                assertSame(current, ReflectionHelpers.getField(hook, "observer"));
                calls.set(0);
                current.dispatchOnGlobalLayout();
                assertEquals("the new observer needs exactly one listener", 1, calls.get());
                first.dispatchOnGlobalLayout();
                assertEquals("the alive stale observer must lose its listener", 1, calls.get());
                hook.detach();
                current.dispatchOnGlobalLayout();
                first.dispatchOnGlobalLayout();
                assertEquals("detach removes all owned listeners", 1, calls.get());
            } finally {
                hook.detach();
            }
        }
    }

    /** Two actual observers isolate ownership from a Dialog shadow's choice to reuse one. */
    private static final class ObserverRoot extends FrameLayout {
        private ViewTreeObserver current;

        ObserverRoot(Context context) {
            super(context);
            current = super.getViewTreeObserver();
        }

        void replaceObserver() { current = new FrameLayout(getContext()).getViewTreeObserver(); }

        @Override public ViewTreeObserver getViewTreeObserver() {
            return current == null ? super.getViewTreeObserver() : current;
        }
    }

    @Test public void invalidInstallDetachesTheExistingListener() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        AtomicInteger calls = new AtomicInteger();
        GlobalLayoutHook hook = new GlobalLayoutHook();

        assertTrue(hook.install(root, calls::incrementAndGet));
        assertFalse(hook.install(null, calls::incrementAndGet));
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals(0, calls.get());
    }
}
