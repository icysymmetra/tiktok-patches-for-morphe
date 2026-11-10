package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;

import com.bytedance.ies.ugc.aweme.commercialize.compliance.personalization.AdPersonalizationActivity;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/**
 * Back on the settings screen rode entirely on the host's onBackPressed, which stops being
 * called once TikTok drops enableOnBackInvokedCallback="false" from its manifest at target 36.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@SuppressWarnings("deprecation")
public class SystemBackHandlerTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    @Test public void aRegisteredCallbackRunsTheAction() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        AtomicInteger backs = new AtomicInteger();
        SystemBackHandler handler = new SystemBackHandler("TestBackCallback");
        handler.register(activity, backs::incrementAndGet);

        Object callback = handler.callbackForTests();
        assertNotNull("nothing was registered with the dispatcher", callback);
        invokeBack(callback);
        assertTrue("the callback did not run the action", backs.get() == 1);

        handler.unregister();
        assertNull("the callback was kept after unregistering", handler.callbackForTests());
    }

    @Config(sdk = 28)
    @Test public void nothingIsRegisteredBelowTheApiThatHasIt() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SystemBackHandler handler = new SystemBackHandler("TestBackCallback");
        handler.register(activity, () -> { throw new AssertionError("ran below API 33"); });
        assertNull("a callback was registered on a build with no dispatcher",
                handler.callbackForTests());
    }

    @Test public void theCallbackGoesWhenTheScreenLeavesTheWindow() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        FrameLayout root = new FrameLayout(activity);
        activity.setContentView(root);
        SystemBackHandler handler = new SystemBackHandler("TestBackCallback");
        handler.registerUntilDetached(activity, root, () -> { });
        assertNotNull(handler.callbackForTests());

        ((ViewGroup) root.getParent()).removeView(root);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertNull("the callback outlived the screen", handler.callbackForTests());
    }

    @Test public void theSettingsScreenRegistersOneThatFinishesAtTheRoot() {
        Intent intent = new Intent().putExtra("morphe", true);
        Activity activity = Robolectric.buildActivity(AdPersonalizationActivity.class, intent)
                .setup().get();
        assertTrue("the settings screen did not open",
                TikTokActivityHook.initialize((AdPersonalizationActivity) activity));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        // The handler is created and dropped inside initialize, so the registration is read back
        // from the view it was hung on rather than from a field.
        ViewGroup content = activity.findViewById(android.R.id.content);
        View root = content.getChildAt(0);
        assertNotNull(root);
        assertTrue("nothing is listening for the screen leaving the window",
                hasAttachListener(root));

        // Calling handleBackPressed from here would say nothing about what the system calls.
        // The registered callback is what Back reaches, so Back is what the test presses.
        Object callback = registeredCallback(root);
        assertNotNull("the settings screen registered no back callback", callback);
        invokeBack(callback);
        assertTrue("Back at the root did not close the settings screen", activity.isFinishing());
    }

    private static boolean hasAttachListener(View view) {
        return !attachListeners(view).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Object> attachListeners(View view) {
        Object info = ReflectionHelpers.getField(view, "mListenerInfo");
        if (info == null) return java.util.List.of();
        Object listeners = ReflectionHelpers.getField(info, "mOnAttachStateChangeListeners");
        return listeners == null ? java.util.List.of() : (java.util.List<Object>) listeners;
    }

    /**
     * The back callback the screen registered, taken from the view it hung its unregister on.
     *
     * <p>Robolectric's back dispatcher drops what it is given, because the window it would
     * forward to has no session, so the system's own copy cannot be read back. The listener
     * {@code registerUntilDetached} leaves on the root belongs to the handler that registered,
     * which is the same object and the only one still reachable.
     */
    private static Object registeredCallback(View root) {
        for (Object listener : attachListeners(root)) {
            for (java.lang.reflect.Field field : listener.getClass().getDeclaredFields()) {
                if (!SystemBackHandler.class.equals(field.getType())) continue;
                field.setAccessible(true);
                try {
                    return ((SystemBackHandler) field.get(listener)).callbackForTests();
                } catch (IllegalAccessException closed) {
                    throw new AssertionError("could not read the handler back", closed);
                }
            }
        }
        return null;
    }

    /** Calls onBackInvoked on the registered proxy, the way the system would. */
    private static void invokeBack(Object callback) {
        try {
            Class<?> callbackClass = Class.forName("android.window.OnBackInvokedCallback");
            callbackClass.getMethod("onBackInvoked").invoke(callback);
        } catch (Exception failed) {
            throw new AssertionError("could not invoke the registered callback", failed);
        }
    }
}
