package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.preference.PreferenceActivity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.ViewGroup;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.SettingsRegistryRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The range line under a range row (Views range and its siblings) follows the stored value.
 *
 * <p>A restore, a reset and an undo all set the value through {@code setValue} and never open the
 * dialog, so a row that only rebuilt its summary on dialog close kept showing the range it had
 * just replaced.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RangeValueSummaryTest {
    public static final class TestActivity extends PreferenceActivity {}

    @Rule public final SettingsRegistryRule settingsRegistry = new SettingsRegistryRule();

    @Before
    public void installContext() {
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    @Test
    public void theRangeLineFollowsAValueSetWithoutTheDialog() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Context context = controller.get();
            StringSetting setting =
                    new StringSetting("range_summary_test_views", "20000-1500000");
            RangeValuePreference preference =
                    new RangeValuePreference(context, "Views", "Summary", setting);

            assertEquals("Summary\n20K to 1.5M", preference.getSummary().toString());

            // What Reset settings does: the stored value goes back to its default through the
            // same call the restore path uses.
            preference.setValue("100-2000");
            assertEquals("Summary\n100 to 2K", preference.getSummary().toString());

            preference.setValue("0-" + Long.MAX_VALUE);
            assertEquals("Summary\n0 and above", preference.getSummary().toString());
        }
    }

    @Test
    public void theDialogStoresWhatWasTypedAndRefusesWhatItCannotRead() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Context context = controller.get();
            StringSetting setting =
                    new StringSetting("range_dialog_test_views", "20000-1500000");
            RangeValuePreference preference =
                    new RangeValuePreference(context, "Views", "Summary", setting);

            // Typed the way the row shows it, which is the reason the field is not a number
            // keyboard. Both forms have to come back as plain digits in the stored value.
            type(preference, "5K", "1.5M");
            preference.onDialogClosed(true);
            assertEquals("5000-1500000", preference.getValue());
            assertEquals("Summary\n5K to 1.5M", preference.getSummary().toString());

            // An empty maximum is no upper bound, not a value that could not be read.
            type(preference, "10", "");
            preference.onDialogClosed(true);
            assertEquals("10-" + Long.MAX_VALUE, preference.getValue());
            assertEquals("Summary\n10 and above", preference.getSummary().toString());

            // Cancel leaves everything as it was, whatever is in the fields.
            type(preference, "1", "2");
            preference.onDialogClosed(false);
            assertEquals("10-" + Long.MAX_VALUE, preference.getValue());
        }
    }

    @Test
    public void aRangeThatCannotBeReadOrIsTheWrongWayRoundIsNotStored() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Context context = controller.get();
            StringSetting setting =
                    new StringSetting("range_dialog_test_likes", "100-200");
            RangeValuePreference preference =
                    new RangeValuePreference(context, "Likes", "Summary", setting);

            type(preference, "seven", "200");
            preference.onDialogClosed(true);
            assertEquals("the unreadable minimum was stored", "100-200", preference.getValue());

            type(preference, "-5", "200");
            preference.onDialogClosed(true);
            assertEquals("a negative minimum was stored", "100-200", preference.getValue());

            type(preference, "900", "200");
            preference.onDialogClosed(true);
            assertEquals("an inverted range was stored", "100-200", preference.getValue());

            // The positive control: the same dialog does save a range it can read.
            type(preference, "300", "900");
            preference.onDialogClosed(true);
            assertEquals("300-900", preference.getValue());
        }
    }

    @Test
    @Config(sdk = 29)
    public void bothFieldsSayWhichOneTheyAreToAScreenReader() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Context context = controller.get();
            StringSetting setting =
                    new StringSetting("range_a11y_test_views", "20000-1500000");
            RangeValuePreference preference =
                    new RangeValuePreference(context, "Views", "Summary", setting);

            List<EditText> fields = new ArrayList<>();
            collect(preference.onCreateDialogView(), fields);
            assertEquals(2, fields.size());

            // TalkBack used to read these as "edit box" and "edit box, Unlimited": the visible
            // headings are separate views and the only hint was a value, not a label.
            // The placeholders say what an empty field means; the spoken names stay the headings.
            assertEquals("0", String.valueOf(fields.get(0).getHint()));
            assertEquals("No limit", String.valueOf(fields.get(1).getHint()));
            assertEquals("Minimum", String.valueOf(describe(fields.get(0)).getHintText()));
            assertEquals("Maximum", String.valueOf(describe(fields.get(1)).getHintText()));
        }
    }

    /** What a screen reader would be handed for one field. */
    private static AccessibilityNodeInfo describe(View field) {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        field.onInitializeAccessibilityNodeInfo(info);
        return info;
    }

    /** Types into the dialog's own two fields, so the preference sees it as a person typing. */
    private static void type(RangeValuePreference preference, String minimum, String maximum) {
        List<EditText> fields = new ArrayList<>();
        collect(preference.onCreateDialogView(), fields);
        assertEquals("the dialog no longer has two fields", 2, fields.size());
        assertNotNull(fields.get(0));
        fields.get(0).setText(minimum);
        fields.get(1).setText(maximum);
    }

    private static void collect(View view, List<EditText> found) {
        if (view instanceof EditText) {
            found.add((EditText) view);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int at = 0; at < group.getChildCount(); at++) collect(group.getChildAt(at), found);
        }
    }
}
