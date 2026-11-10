/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.PreferenceActivity;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.TextView;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.download.DownloadDestination;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Accessibility contract for the three hand-built settings editor dialogs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en-w480dp-h960dp-night-mdpi")
public class EditorAccessibilityTest {
    public static final class TestActivity extends PreferenceActivity {}

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void seedValues() {
        Settings.BLOCKED_CAPTION_WORDS.save("spoiler");
        Settings.MAX_VIDEO_SECONDS.save(42);
        Settings.DOWNLOAD_VIDEO_PATH.save("Movies/Hushfeed");
    }

    @After public void resetValues() {
        Settings.BLOCKED_CAPTION_WORDS.resetToDefault();
        Settings.MAX_VIDEO_SECONDS.resetToDefault();
        Settings.DOWNLOAD_VIDEO_PATH.resetToDefault();
    }

    @Test @Config(fontScale = 1f)
    public void preferenceEditorsHaveOneNamedInputAtNormalTextSize() {
        assertPreferenceEditors(1f);
    }

    @Test @Config(fontScale = 2f)
    public void preferenceEditorsHaveOneNamedInputAtDoubleTextSize() {
        assertPreferenceEditors(2f);
    }

    private static void assertPreferenceEditors(float expectedScale) {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            Activity activity = owner.get();
            assertEquals(expectedScale,
                    activity.getResources().getConfiguration().fontScale, 0.01f);

            InputTextPreference text = new InputTextPreference(
                    activity,
                    "Blocked caption words",
                    "Comma separated words or phrases.",
                    Settings.BLOCKED_CAPTION_WORDS
            );
            assertEditor(text.onCreateDialogView(), "Blocked caption words", "spoiler",
                    InputType.TYPE_CLASS_TEXT);

            NumberInputPreference number = new NumberInputPreference(
                    activity,
                    "Maximum video length",
                    "Seconds. Zero keeps every length.",
                    Settings.MAX_VIDEO_SECONDS,
                    "%1$s second",
                    "%1$s seconds"
            );
            assertEditor(number.onCreateDialogView(), "Maximum video length", "42",
                    InputType.TYPE_CLASS_NUMBER);

            DownloadPathPreference destination = new DownloadPathPreference(
                    activity,
                    "Video destination",
                    Settings.DOWNLOAD_VIDEO_PATH,
                    DownloadDestination.Kind.VIDEO
            );
            assertEditor(destination.onCreateDialogView(), "Video destination",
                    "Movies/Hushfeed", InputType.TYPE_CLASS_TEXT);
        }
    }

    private static void assertEditor(View dialog, String labelText, String currentValue,
                                     int expectedInputClass) {
        TextView label = findText(dialog, labelText);
        EditText input = find(dialog, EditText.class);
        assertTrue("the visible label is missing", label != null);
        assertTrue("the dialog input is missing", input != null);
        assertNotEquals("the input has no address for its label", View.NO_ID, input.getId());
        assertEquals("the visible label is not connected to its input",
                input.getId(), label.getLabelFor());
        assertEquals("the visible label is a second accessibility stop",
                View.IMPORTANT_FOR_ACCESSIBILITY_NO, label.getImportantForAccessibility());
        assertFalse("the visible label takes focus before the input", label.isFocusable());
        assertTrue("the input cannot take focus", input.isFocusable());
        assertNull("the accessible name replaced the value with a content description",
                input.getContentDescription());

        // EditTextPreference copies its stored value during the platform bind that follows this
        // custom-view creation. Type it here so this node-level test exercises the same final
        // state without depending on AlertDialog's unrelated binding internals.
        input.setText(currentValue);
        AccessibilityNodeInfo node = input.createAccessibilityNodeInfo();
        assertEquals(currentValue, String.valueOf(node.getText()));
        assertTrue("the node does not name the setting it edits",
                String.valueOf(node.getHintText()).contains(labelText));
        assertTrue("the node does not expose its required input class",
                (node.getInputType() & InputType.TYPE_MASK_CLASS) == expectedInputClass);
        assertTrue("the node does not expose its editable state", node.isEditable());
        assertTrue("an enabled input is announced as disabled", node.isEnabled());
        assertNull("the node's name replaced its current value",
                node.getContentDescription());

        input.setEnabled(false);
        AccessibilityNodeInfo disabled = input.createAccessibilityNodeInfo();
        assertFalse("a disabled input is announced as enabled", disabled.isEnabled());
        assertEquals(currentValue, String.valueOf(disabled.getText()));
        assertEquals(node.getInputType(), disabled.getInputType());
        assertTrue(String.valueOf(disabled.getHintText()).contains(labelText));
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView found = findText(group.getChildAt(index), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                T found = find(group.getChildAt(index), type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
