package app.morphe.extension.tiktok.featuregatelab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.Switch;

import app.morphe.extension.shared.Utils;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * A Lab switch row is one stop for the screen reader and one target for a finger.
 *
 * <p>TalkBack used to read "Apply overrides", then the summary, then "Apply overrides"
 * again for the switch, and only the switch itself answered a tap.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class FeatureGateLabSwitchRowTest {
    @Test public void theRowIsTheSwitchForTouchAndForTheScreenReader() {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        Switch control = new Switch(context);
        AtomicInteger flips = new AtomicInteger();
        control.setOnCheckedChangeListener((button, checked) -> flips.incrementAndGet());

        LinearLayout row = FeatureGateLabUi.switchRow(context, "Apply overrides",
                "Replace values when TikTok asks for them", control);

        assertTrue(row.isClickable());
        assertTrue(row.isFocusable());
        assertEquals("Apply overrides. Replace values when TikTok asks for them",
                row.getContentDescription().toString());
        assertFalse("the switch itself must not be a second target", control.isClickable());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, control.getImportantForAccessibility());

        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        row.onInitializeAccessibilityNodeInfo(node);
        assertEquals(Switch.class.getName(), node.getClassName().toString());
        assertTrue(node.isCheckable());
        assertFalse(node.isChecked());
        assertTrue((node.getActions() & AccessibilityNodeInfo.ACTION_CLICK) != 0);

        assertTrue(row.performClick());
        assertTrue("a tap on the row text flips the switch", control.isChecked());
        assertEquals(1, flips.get());
        node = AccessibilityNodeInfo.obtain();
        row.onInitializeAccessibilityNodeInfo(node);
        assertTrue("the node reports the new state", node.isChecked());
    }

    @Test public void aDisabledSwitchDoesNotFlipFromTheRow() {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        Switch control = new Switch(context);
        control.setEnabled(false);
        LinearLayout row = FeatureGateLabUi.switchRow(context, "Forced result", "Off forces false", control);

        row.performClick();
        assertFalse(control.isChecked());
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        row.onInitializeAccessibilityNodeInfo(node);
        assertFalse(node.isEnabled());
    }
}
