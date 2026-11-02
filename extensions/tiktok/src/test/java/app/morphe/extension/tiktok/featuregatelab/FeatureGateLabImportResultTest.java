package app.morphe.extension.tiktok.featuregatelab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The import result is lines a reader can take in, with the rejection text selectable.
 *
 * <p>It used to be one five-clause toast that vanished in 3.5 seconds and could not be copied.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class FeatureGateLabImportResultTest {
    @Test public void eachCountIsItsOwnLineAndTheRejectionCanBeSelected() {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        String rejection = "settings_manager.foo has type INT, but the file says STRING";

        LinearLayout lines = FeatureGateLabFragment.importResultView(context, 12, 3, 1, 2, rejection);

        assertEquals(6, lines.getChildCount());
        assertEquals("Imported 12 values", text(lines, 0));
        assertEquals("3 already matched", text(lines, 1));
        assertEquals("1 unavailable", text(lines, 2));
        assertEquals("2 rejected", text(lines, 3));
        assertEquals("First rejection:", text(lines, 4));
        assertEquals(rejection, text(lines, 5));
        assertTrue("the rejection is there to be copied", ((TextView) lines.getChildAt(5)).isTextSelectable());
    }

    @Test public void noRejectionMeansNoRejectionLines() {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        LinearLayout lines = FeatureGateLabFragment.importResultView(context, 4, 0, 0, 0, null);
        assertEquals(4, lines.getChildCount());
        assertEquals("0 rejected", text(lines, 3));
    }

    private static String text(LinearLayout lines, int index) {
        return ((TextView) lines.getChildAt(index)).getText().toString();
    }
}
