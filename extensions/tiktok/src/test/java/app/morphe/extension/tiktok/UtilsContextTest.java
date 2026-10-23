package app.morphe.extension.tiktok;

import static org.junit.Assert.assertSame;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import app.morphe.extension.shared.Utils;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The shared context is the application, not the MainActivity it was handed: holding the
 * activity kept a finished one reachable until the next was created. The activity is still
 * there for the callers that need one, and sizes that follow the window use it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class UtilsContextTest {
    @After public void tearDown() {
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    @Test public void anActivityIsKeptWeaklyAndTheApplicationIsTheContext() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            assertSame(activity.getApplicationContext(), Utils.getContext());
            assertSame(activity, Utils.getActivity());
            assertSame("a window-sized read uses the activity", activity, Utils.getWindowContext());
        }
    }

    @Test public void anyOtherContextIsKeptAsGiven() {
        // Tests hand in wrappers that redirect files and preferences; those stay what they are.
        Context wrapper = new ContextWrapper(RuntimeEnvironment.getApplication());
        Utils.setContext(wrapper);
        assertSame(wrapper, Utils.getContext());
    }
}
