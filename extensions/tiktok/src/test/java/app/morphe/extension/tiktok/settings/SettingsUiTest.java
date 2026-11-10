package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.UiCapture;
import app.morphe.extension.tiktok.settings.preference.SettingsMenuPreference;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import app.morphe.extension.tiktok.settings.preference.categories.DebugPreferenceCategory;

import java.lang.reflect.Field;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w480dp-h960dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SuppressWarnings("deprecation")
public class SettingsUiTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static class DialogActivity extends Activity {
        @Override
        protected void onCreate(android.os.Bundle state) {
            boolean dark = (getResources().getConfiguration().uiMode & 0x30) == 0x20;
            setTheme(dark ? android.R.style.Theme_Material_NoActionBar
                    : android.R.style.Theme_Material_Light_NoActionBar);
            Utils.setIsDarkModeEnabled(dark);
            super.onCreate(state);
        }
    }

    public static class PreferenceDialogActivity extends PreferenceActivity {
        @Override
        protected void onCreate(android.os.Bundle state) {
            boolean dark = (getResources().getConfiguration().uiMode & 0x30) == 0x20;
            setTheme(dark ? android.R.style.Theme_Material_NoActionBar
                    : android.R.style.Theme_Material_Light_NoActionBar);
            Utils.setIsDarkModeEnabled(dark);
            super.onCreate(state);
        }
    }

    @After
    public void restoreDarkMode() {
        Utils.setIsDarkModeEnabled(true);
    }

    @Test
    public void sharedTextControlsExposeTheirDisabledStateAndActionRole() {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);

            TextView label = SettingsUi.text(
                    activity, "Save", 14, SettingsUi.accent(), android.graphics.Typeface.BOLD);
            assertEquals(SettingsUi.accent(), label.getCurrentTextColor());
            label.setEnabled(false);
            assertEquals("a disabled text control kept its active colour",
                    SettingsUi.textDisabled(), label.getCurrentTextColor());

            TextView action = SettingsUi.text(
                    activity, "Save", 14, SettingsUi.accent(), android.graphics.Typeface.BOLD);
            SettingsUi.styleTextAction(action, true);
            assertEquals(android.widget.Button.class.getName(),
                    action.createAccessibilityNodeInfo().getClassName());

            EditText field = new EditText(activity);
            SettingsUi.styleEditText(field);
            field.setEnabled(false);
            assertEquals("a disabled field kept its active text colour",
                    SettingsUi.textDisabled(), field.getCurrentTextColor());
        }
    }

    @Test
    public void refusedFieldValueUsesAnInlineLiveErrorThatClearsWhileTyping() {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            android.widget.LinearLayout form = new android.widget.LinearLayout(activity);
            form.setOrientation(android.widget.LinearLayout.VERTICAL);
            EditText field = new EditText(activity);
            SettingsUi.styleEditText(field);
            form.addView(field);
            activity.setContentView(form);

            SettingsUi.reportFieldError(field, "Enter a number.");

            assertEquals("the platform error bubble was used instead of an inline message",
                    null, field.getError());
            assertEquals("the field error is not directly below its field", 2,
                    form.getChildCount());
            assertTrue("the inline field error is not text",
                    form.getChildAt(1) instanceof TextView);
            TextView error = (TextView) form.getChildAt(1);
            assertEquals("Enter a number.", error.getText().toString());
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE,
                    error.getAccessibilityLiveRegion());
            assertTrue("the field did not receive focus after Save was refused",
                    field.hasFocus());

            field.setText("4");
            assertEquals("a stale field error stayed visible after the value changed",
                    View.GONE, error.getVisibility());
            assertEquals("a hidden field error kept stale text", "", error.getText().toString());
        }
    }

    @Test
    @Config(sdk = 29, qualifiers = "w480dp-h960dp-night-mdpi")
    public void errorTextAndScrollableEdgesUseTheSharedThemeTokens() {
        Activity activity = Robolectric.buildActivity(DialogActivity.class).setup().get();
        Utils.setContext(activity);
        for (boolean dark : new boolean[]{true, false}) {
            Utils.setIsDarkModeEnabled(dark);
            assertTrue("field error text misses 4.5:1 contrast in the "
                            + (dark ? "dark" : "light") + " theme",
                    contrast(SettingsUi.error(), SettingsUi.surface()) >= 4.5);

            ListView list = new ListView(activity);
            SettingsUi.styleScrollableList(list);
            assertEquals("the top overscroll glow is still the host colour",
                    SettingsUi.accent(), list.getTopEdgeEffectColor());
            assertEquals("the bottom overscroll glow is still the host colour",
                    SettingsUi.accent(), list.getBottomEdgeEffectColor());
        }
    }

    @Test
    public void dropdownControlsCarryTheHushfeedFrameAndPopupSurface() {
        Activity activity = Robolectric.buildActivity(DialogActivity.class).setup().get();
        Utils.setContext(activity);
        android.widget.Spinner spinner = new android.widget.Spinner(
                activity, android.widget.Spinner.MODE_DROPDOWN);

        SettingsUi.styleSpinner(spinner);
        activity.setContentView(spinner);

        assertTrue("the value control is shorter than a 48dp touch target",
                spinner.getMinimumHeight() >= SettingsUi.dp(activity, 48));
        assertNotNull("the value popup kept the host background", spinner.getPopupBackground());
        assertTrue("the value popup is not a Metra surface",
                spinner.getPopupBackground() instanceof android.graphics.drawable.GradientDrawable);
    }

    @Test
    @Config(sdk = 29, qualifiers = "w480dp-h960dp-night-mdpi")
    public void editorCaretAndSelectionHandlesUseTheAccent() {
        Activity activity = Robolectric.buildActivity(DialogActivity.class).setup().get();
        Utils.setContext(activity);
        EditText field = new EditText(activity);
        field.setTextCursorDrawable(new android.graphics.drawable.ColorDrawable(
                android.graphics.Color.GREEN));
        field.setTextSelectHandle(new android.graphics.drawable.ColorDrawable(
                android.graphics.Color.GREEN));
        field.setTextSelectHandleLeft(new android.graphics.drawable.ColorDrawable(
                android.graphics.Color.GREEN));
        field.setTextSelectHandleRight(new android.graphics.drawable.ColorDrawable(
                android.graphics.Color.GREEN));

        SettingsUi.styleEditText(field);

        int expected = SettingsUi.accent() | 0xff000000;
        assertEquals("the caret kept the host colour", expected,
                centreOf(field.getTextCursorDrawable(), false) | 0xff000000);
        assertEquals("the selection handle kept the host colour", expected,
                centreOf(field.getTextSelectHandle(), false) | 0xff000000);
        assertEquals("the left selection handle kept the host colour", expected,
                centreOf(field.getTextSelectHandleLeft(), false) | 0xff000000);
        assertEquals("the right selection handle kept the host colour", expected,
                centreOf(field.getTextSelectHandleRight(), false) | 0xff000000);
    }

    @Test
    public void sharedDialogHeadingAndResultStatusCarryAccessibilitySemantics() {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TextView heading = SettingsUi.text(
                    activity, "Dialog title", 20, SettingsUi.textPrimary(), 1);
            SettingsUi.markDialogHeading(heading);
            assertTrue(heading.isAccessibilityHeading());

            TextView count = SettingsUi.resultCount(activity, "test_result_count");
            SettingsUi.setResultCount(count, 2);
            assertEquals("2 results", count.getText().toString());
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                    count.getAccessibilityLiveRegion());
        }
    }

    @Test
    @Config(sdk = 23, qualifiers = "w480dp-h960dp-night-mdpi")
    public void dialogHeadingKeepsItsSemanticsBeforeThePlatformHeadingApi() {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            TextView heading = SettingsUi.text(
                    activity, "Dialog title", 20, SettingsUi.textPrimary(), 1);
            SettingsUi.markDialogHeading(heading);
            activity.setContentView(heading);

            AccessibilityNodeInfo info = heading.createAccessibilityNodeInfo();
            assertNotNull("the legacy heading has no collection item metadata",
                    info.getCollectionItemInfo());
            assertTrue("the legacy collection item is not marked as a heading",
                    info.getCollectionItemInfo().isHeading());
        }
    }

    @Test
    public void darkSingleChoiceUsesOneRadioAndNativeSelection() throws Exception {
        assertSingleChoice("dialogs/dark/single-choice.png");
    }

    @Test
    @Config(qualifiers = "w480dp-h960dp-notnight-mdpi")
    public void lightSingleChoiceUsesOneRadioAndNativeSelection() throws Exception {
        assertSingleChoice("dialogs/light/single-choice.png");
    }

    private void assertSingleChoice(String screenshotPath) throws Exception {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("Playback speed")
                    .setSingleChoiceItems(new String[]{"0.5x", "1x"}, 0, (ignored, which) -> {})
                    .setNegativeButton("Cancel", null)
                    .create();
            dialog.show();
            SettingsUi.styleStandardAlertDialog(dialog);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            ListView list = dialog.getListView();
            assertEquals(ListView.CHOICE_MODE_SINGLE, list.getChoiceMode());
            CheckedTextView selected = checkedTextView(list, 0);
            CheckedTextView unselected = checkedTextView(list, 1);
            assertNotNull(selected);
            assertNotNull(unselected);
            assertTrue(selected.isChecked());
            assertFalse(unselected.isChecked());
            assertRadio(selected, true);
            assertRadio(unselected, true);
            assertTrue(selected.getCheckMarkDrawable() == null);
            assertTrue(unselected.getCheckMarkDrawable() == null);
            UiCapture.save(dialog.getWindow().getDecorView(), screenshotPath);

            list.performItemClick(unselected, 1, list.getAdapter().getItemId(1));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(list.isItemChecked(1));
            assertTrue(unselected.isChecked());
            assertEquals("1x", list.getAdapter().getItem(1));
        }
    }

    @Test
    public void darkIncludedDiagnosticsPickerShowsItsRealChoicesAndActions() throws Exception {
        assertIncludedDiagnosticsPicker("dialogs/dark/multi-choice.png");
    }

    @Test
    @Config(qualifiers = "w480dp-h960dp-notnight-mdpi")
    public void lightIncludedDiagnosticsPickerShowsItsRealChoicesAndActions() throws Exception {
        assertIncludedDiagnosticsPicker("dialogs/light/multi-choice.png");
    }

    private void assertIncludedDiagnosticsPicker(String screenshotPath) throws Exception {
        boolean diagnosticsWereEnabled = SettingsStatus.diagnosticsEnabled;
        String savedFilter = BaseSettings.DEBUG_LOG_FILTERS.get();
        try {
            SettingsStatus.diagnosticsEnabled = true;
            BaseSettings.DEBUG_LOG_FILTERS.save("downloads,errors");

            try (var owner = Robolectric.buildActivity(PreferenceDialogActivity.class)
                    .setup().visible()) {
                PreferenceActivity activity = owner.get();
                Utils.setContext(activity);
                PreferenceScreen screen = activity.getPreferenceManager()
                        .createPreferenceScreen(activity);
                new DebugPreferenceCategory(activity, screen);
                activity.setPreferenceScreen(screen);

                Preference row = screen.findPreference("action_included_diagnostics");
                assertNotNull("the shipped Included diagnostics row is gone", row);
                assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
                AlertDialog dialog = (AlertDialog)
                        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
                Shadows.shadowOf(Looper.getMainLooper()).idle();

                assertEquals("Include diagnostic events",
                        Shadows.shadowOf(dialog).getTitle().toString());
                assertEquals("Apply", dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .getText().toString());
                assertEquals("Cancel", dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                        .getText().toString());
                int visibleActions = 0;
                for (int which : new int[]{AlertDialog.BUTTON_POSITIVE,
                        AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
                    android.widget.Button button = dialog.getButton(which);
                    if (button == null || button.getVisibility() != View.VISIBLE) continue;
                    visibleActions++;
                    assertFalse("the invented Done action came back",
                            "Done".contentEquals(button.getText()));
                }
                assertEquals("the real picker does not have exactly Apply and Cancel",
                        2, visibleActions);

                ListView list = dialog.getListView();
                assertEquals(ListView.CHOICE_MODE_MULTIPLE, list.getChoiceMode());
                String[] expected = {
                        "All events", "Follow probe", "Downloads", "Feed and navigation",
                        "Feature Gate Lab", "Settings", "Errors", "Other"
                };
                assertEquals("the screenshot no longer shows every shipped choice",
                        expected.length, list.getAdapter().getCount());
                for (int i = 0; i < expected.length; i++) {
                    assertEquals(expected[i], String.valueOf(list.getAdapter().getItem(i)));
                }

                CheckedTextView selected = checkedTextView(list, 2);
                CheckedTextView unselected = checkedTextView(list, 1);
                assertNotNull(selected);
                assertNotNull(unselected);
                assertTrue(selected.isChecked());
                assertFalse(unselected.isChecked());
                assertRadio(selected, false);
                assertRadio(unselected, false);
                assertTrue(selected.getCheckMarkDrawable() == null);
                assertTrue(unselected.getCheckMarkDrawable() == null);
                UiCapture.save(dialog.getWindow().getDecorView(), screenshotPath);

                list.performItemClick(unselected, 1, list.getAdapter().getItemId(1));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(list.isItemChecked(1));
                assertTrue(unselected.isChecked());
                assertTrue(dialog.isShowing());
            }
        } finally {
            BaseSettings.DEBUG_LOG_FILTERS.save(savedFilter);
            SettingsStatus.diagnosticsEnabled = diagnosticsWereEnabled;
        }
    }

    private static CheckedTextView checkedTextView(ListView list, int position) {
        View row = list.getChildAt(position);
        return findCheckedTextView(row);
    }

    private static CheckedTextView findCheckedTextView(View view) {
        if (view instanceof CheckedTextView) {
            return (CheckedTextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                CheckedTextView result = findCheckedTextView(group.getChildAt(i));
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * A chosen row looks different from the one beside it, in both themes.
     *
     * <p>The row is told it is chosen with {@code setActivated}, and the background it draws
     * ignored that: the Lab's selection bar said "2 gates selected" over rows that looked exactly
     * like the rest, and Enable, Disable and Reset then acted on gates nobody could see.
     */
    @Test
    public void aChosenRowIsDrawnDifferentlyFromItsNeighbour() {
        for (boolean dark : new boolean[]{true, false}) {
            Utils.setIsDarkModeEnabled(dark);
            Activity activity = Robolectric.buildActivity(DialogActivity.class).setup().get();
            Utils.setContext(activity);

            int plain = centreOf(SettingsUi.groupedRow(activity, true, true), false);
            int chosen = centreOf(SettingsUi.groupedRow(activity, true, true), true);

            assertNotEquals("a chosen row is painted exactly like an unchosen one, dark=" + dark,
                    plain, chosen);
            // And it is the accent the press already uses, over the surface it already had.
            int expected = blend(SettingsUi.activatedFill(), SettingsUi.surface());
            // Skia composites premultiplied 8-bit channels. Compared with a single floating
            // point blend, either rounding path may differ by one channel level.
            for (int shift : new int[]{0, 8, 16}) {
                assertTrue("the chosen fill is not the accent the ripple leaves, dark=" + dark,
                        Math.abs(((expected >>> shift) & 255) - ((chosen >>> shift) & 255)) <= 1);
            }
        }
    }

    /**
     * The chosen mark draws a check, not an empty box.
     *
     * <p>An ImageView hands a stateful drawable its own state the moment it is set, and no
     * ImageView state carries {@code state_checked}, so a mark that listened to its state would
     * be unchecked before it was ever drawn: the reader who needs the glyph most, a colour-blind
     * one, would see an outlined empty square by a chosen row.
     */
    @Test
    public void theChosenMarkDrawsACheckWhateverStateItIsGiven() {
        Utils.setIsDarkModeEnabled(true);
        Activity activity = Robolectric.buildActivity(DialogActivity.class).setup().get();
        Utils.setContext(activity);

        android.widget.ImageView holder = new android.widget.ImageView(activity);
        holder.setImageDrawable(SettingsUi.checkMark(activity));
        Drawable mark = holder.getDrawable();
        // The states an ordinary, un-checked ImageView would push onto it.
        mark.setState(new int[]{android.R.attr.state_enabled});
        mark.setBounds(0, 0, 48, 48);
        android.graphics.Bitmap bitmap =
                android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888);
        mark.draw(new android.graphics.Canvas(bitmap));
        // A drawn check fills its box; an empty box is a stroked outline whose middle stays clear.
        int centre = bitmap.getPixel(24, 24);
        // A corner of the fill, clear of the white check line that antialiases through the centre.
        int fill = bitmap.getPixel(18, 30);
        bitmap.recycle();
        assertEquals("the chosen mark drew an empty box: its centre is not filled",
                255, android.graphics.Color.alpha(centre));
        assertEquals("the chosen mark is filled with something other than the accent",
                SettingsUi.accent() | 0xff000000, fill | 0xff000000);
    }

    /**
     * A text field says whether typing will land in it.
     *
     * <p>The underline tint had two states, disabled and everything else, so the field holding
     * the cursor looked exactly like the one beside it. The Min and Max dialog is where that
     * shows worst: two accent underlines, one caret.
     */
    @Test
    public void aFieldWithTheCursorInItIsUnderlinedDifferently() {
        // One activity, then the flag. DialogActivity.onCreate reads the night qualifier and
        // sets the flag itself, so building inside the loop put the dark theme back every time
        // and the light pass compared the dark colours with themselves.
        Activity activity = Robolectric.buildActivity(DialogActivity.class).setup().get();
        Utils.setContext(activity);
        for (boolean dark : new boolean[]{true, false}) {
            Utils.setIsDarkModeEnabled(dark);
            String theme = dark ? "dark" : "light";
            assertEquals("the fixture is not in the " + theme + " theme it claims",
                    dark, SettingsUi.surface() == SettingsUi.DARK_SURFACE);

            EditText field = new EditText(activity);
            SettingsUi.styleEditText(field);
            android.content.res.ColorStateList tint = field.getBackgroundTintList();
            assertNotNull("the field was left without a tint", tint);

            int focused = tint.getColorForState(
                    new int[]{android.R.attr.state_enabled, android.R.attr.state_focused}, 0);
            int resting = tint.getColorForState(new int[]{android.R.attr.state_enabled}, 0);
            int disabled = tint.getColorForState(new int[]{-android.R.attr.state_enabled}, 0);
            assertEquals("the focused underline is not the accent in " + theme,
                    SettingsUi.accent(), focused);
            assertNotEquals("a resting field is underlined like the focused one in " + theme,
                    focused, resting);
            assertEquals("a disabled field lost its border colour in " + theme,
                    SettingsUi.border(), disabled);

            // Different is not enough: the field with the cursor has to be the louder of the
            // two. The first version used the secondary text colour, which reads stronger
            // against the surface than the accent does, so the resting field was the loud one.
            double quiet = contrast(SettingsUi.surface(), resting);
            double loud = contrast(SettingsUi.surface(), focused);
            assertTrue("the resting underline is louder than the focused one in " + theme
                            + ": " + quiet + ":1 against " + loud + ":1", loud > quiet);
            assertTrue("the two underlines are too close to tell apart in " + theme + ": "
                            + contrast(focused, resting) + ":1",
                    contrast(focused, resting) >= 3.0);
        }
    }

    /**
     * A row the keyboard, the d-pad or switch access has landed on wears a ring.
     *
     * <p>Every row's only stateful background was the ripple, and a RippleDrawable paints
     * {@code state_focused} as its own tint at 60% of its opacity: the accent at 15% alpha came
     * out around 9% over the surface, about 1.3:1, which nobody can see. The ring is the accent
     * at full strength, inside the card's own corners, on the first row, a middle one and the
     * last, and on the header's back button. A press still shows the ripple and no ring.
     */
    @Test
    public void aFocusedRowWearsARingTheReaderCanSee() {
        Utils.setIsDarkModeEnabled(true);
        Activity activity = Robolectric.buildActivity(DialogActivity.class).setup().get();
        Utils.setContext(activity);

        int[] focused = {android.R.attr.state_enabled, android.R.attr.state_focused};
        int[] resting = {android.R.attr.state_enabled};
        int[] pressed = {android.R.attr.state_enabled, android.R.attr.state_pressed};
        boolean[][] shapes = {{true, false}, {false, false}, {false, true}};
        for (boolean[] shape : shapes) {
            String which = shape[0] ? "first" : shape[1] ? "last" : "middle";
            assertTrue("a " + which + " row drew no ring while focused",
                    ringIsDrawn(SettingsUi.groupedRow(activity, shape[0], shape[1]), focused));
            assertFalse("a " + which + " row drew a ring while resting",
                    ringIsDrawn(SettingsUi.groupedRow(activity, shape[0], shape[1]), resting));
            assertFalse("a " + which + " row drew a ring under a press",
                    ringIsDrawn(SettingsUi.groupedRow(activity, shape[0], shape[1]), pressed));
        }
        // A list moves a d-pad by marking a row selected rather than focusing it.
        assertTrue("a selected row drew no ring",
                ringIsDrawn(SettingsUi.groupedRow(activity, false, false),
                        new int[]{android.R.attr.state_enabled, android.R.attr.state_selected}));

        // All four edges, not two. A middle row's card frame runs a radius past the top and the
        // bottom of its own box, and the canvas is clipped to that box, so a ring drawn on the
        // frame lost both horizontal strokes and the reader saw a pair of vertical bars. This
        // also pins the order the row draws in: the Paint is shared, and a ring drawn before the
        // divider leaves the divider at the ring's width, which then covers the bottom edge.
        for (boolean[] shape : shapes) {
            String which = shape[0] ? "first" : shape[1] ? "last" : "middle";
            boolean[] edges = ringEdges(SettingsUi.groupedRow(activity, shape[0], shape[1]), focused);
            assertTrue("a focused " + which + " row drew no top edge", edges[0]);
            assertTrue("a focused " + which + " row drew no bottom edge", edges[1]);
        }


        // The header's back button, wrapped the way the header ships it: the ring is the
        // ripple's content layer, which is where a mask or a lost state change would hide it.
        assertTrue("the back button drew no ring while focused",
                ringIsDrawn(backButtonBackground(activity), focused));
        assertFalse("the back button drew a ring while resting",
                ringIsDrawn(backButtonBackground(activity), resting));

        assertTrue("the ring is not readable against the surface it is drawn on: "
                        + contrast(SettingsUi.accent(), SettingsUi.surface()) + ":1",
                contrast(SettingsUi.accent(), SettingsUi.surface()) >= 3.0);
        Utils.setIsDarkModeEnabled(false);
        assertTrue("the ring is not readable on the light surface: "
                        + contrast(SettingsUi.accent(), SettingsUi.surface()) + ":1",
                contrast(SettingsUi.accent(), SettingsUi.surface()) >= 3.0);
        // Drawn in the light theme too: the accent differs between the two, so a ring that only
        // painted in the dark one would pass every assertion above.
        assertTrue("a focused row drew no ring in the light theme",
                ringIsDrawn(SettingsUi.groupedRow(activity, false, false), focused));
        assertFalse("a resting row drew a ring in the light theme",
                ringIsDrawn(SettingsUi.groupedRow(activity, false, false), resting));
    }

    /** Whether the drawable paints the accent along its edge in the state it is given. */
    private static boolean ringIsDrawn(Drawable drawable, int[] state) {
        int size = 120;
        drawable.setState(state);
        drawable.setBounds(0, 0, size, size);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.ARGB_8888);
        drawable.draw(new android.graphics.Canvas(bitmap));
        // Down the left edge, away from the corners, where only a ring can put the accent.
        int accent = SettingsUi.accent() | 0xff000000;
        boolean found = false;
        for (int y = size / 3; y < size * 2 / 3; y++) {
            for (int x = 0; x < 4; x++) {
                if ((bitmap.getPixel(x, y) | 0xff000000) == accent) {
                    found = true;
                    break;
                }
            }
        }
        bitmap.recycle();
        return found;
    }

    /** The header back button's background, built the way SettingsHeaderPreference builds it. */
    private static Drawable backButtonBackground(Activity activity) {
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(
                        (SettingsUi.accent() & 0x00ffffff) | 0x26000000),
                SettingsUi.focusRing(activity, 6),
                SettingsUi.roundedSurface(activity, 6, false));
    }

    /** Whether the accent appears along the top and the bottom edge in the given state. */
    private static boolean[] ringEdges(Drawable drawable, int[] state) {
        int size = 120;
        drawable.setState(state);
        drawable.setBounds(0, 0, size, size);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.ARGB_8888);
        drawable.draw(new android.graphics.Canvas(bitmap));
        int accent = SettingsUi.accent() | 0xff000000;
        boolean top = false;
        boolean bottom = false;
        for (int x = size / 3; x < size * 2 / 3; x++) {
            for (int y = 0; y < 4; y++) {
                if ((bitmap.getPixel(x, y) | 0xff000000) == accent) top = true;
                if ((bitmap.getPixel(x, size - 1 - y) | 0xff000000) == accent) bottom = true;
            }
        }
        bitmap.recycle();
        return new boolean[]{top, bottom};
    }

    /** WCAG contrast between two opaque colours. */
    private static double contrast(int first, int second) {
        double one = luminance(first), two = luminance(second);
        return (Math.max(one, two) + 0.05) / (Math.min(one, two) + 0.05);
    }

    private static double luminance(int colour) {
        double[] parts = new double[3];
        int[] raw = {android.graphics.Color.red(colour), android.graphics.Color.green(colour),
                android.graphics.Color.blue(colour)};
        for (int at = 0; at < 3; at++) {
            double channel = raw[at] / 255.0;
            parts[at] = channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * parts[0] + 0.7152 * parts[1] + 0.0722 * parts[2];
    }

    /**
     * An action drawn onto one of TikTok's own surfaces takes the ring and ripple in a tone that
     * shows on that surface.
     *
     * <p>The inbox header and the sticker sheet follow the app's theme, so the white ring and
     * white ripple every over-video control carries vanish on them in the light theme. This
     * overload paints them in the host's own text colour instead.
     */
    @Test
    public void anActionOnAHostSurfaceRingsInTheToneItIsGiven() {
        Activity activity = Robolectric.buildActivity(DialogActivity.class).setup().get();
        Utils.setContext(activity);

        int tone = android.graphics.Color.rgb(12, 12, 12);
        Drawable background = SettingsUi.overlayAction(activity, SettingsUi.RADIUS_CONTROL, tone);
        background.setState(new int[]{android.R.attr.state_focused});
        background.setBounds(0, 0, 64, 64);
        android.graphics.Bitmap bitmap =
                android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888);
        background.draw(new android.graphics.Canvas(bitmap));
        // The 2dp ring sits on the edge. The middle of the top edge is on it.
        int ring = bitmap.getPixel(32, 1);
        bitmap.recycle();
        assertTrue("the focus ring did not draw at all", android.graphics.Color.alpha(ring) > 0);
        assertTrue("the focus ring is white where it should carry the host tone",
                android.graphics.Color.red(ring) < 128
                        && android.graphics.Color.green(ring) < 128
                        && android.graphics.Color.blue(ring) < 128);
    }

    /** The colour a row's background paints in the middle, with or without the chosen state. */
    private static int centreOf(Drawable background, boolean activated) {
        background.setState(activated
                ? new int[]{android.R.attr.state_activated} : new int[0]);
        background.setBounds(0, 0, 120, 80);
        android.graphics.Bitmap bitmap =
                android.graphics.Bitmap.createBitmap(120, 80, android.graphics.Bitmap.Config.ARGB_8888);
        background.draw(new android.graphics.Canvas(bitmap));
        int pixel = bitmap.getPixel(60, 40);
        bitmap.recycle();
        return pixel;
    }

    /** What an alpha colour comes out as over an opaque one, the way the canvas composites it. */
    private static int blend(int over, int under) {
        float alpha = android.graphics.Color.alpha(over) / 255f;
        int red = Math.round(android.graphics.Color.red(over) * alpha
                + android.graphics.Color.red(under) * (1 - alpha));
        int green = Math.round(android.graphics.Color.green(over) * alpha
                + android.graphics.Color.green(under) * (1 - alpha));
        int blue = Math.round(android.graphics.Color.blue(over) * alpha
                + android.graphics.Color.blue(under) * (1 - alpha));
        return android.graphics.Color.argb(255, red, green, blue);
    }

    private static void assertRadio(CheckedTextView view, boolean expected) throws Exception {
        Drawable[] drawables = view.getCompoundDrawablesRelative();
        Drawable drawable = drawables[0];
        assertNotNull(drawable);
        assertEquals("DialogCheckMarkDrawable", drawable.getClass().getSimpleName());
        Field field = drawable.getClass().getDeclaredField("radio");
        field.setAccessible(true);
        assertEquals(expected, field.getBoolean(drawable));
    }

    @Test public void aRowBoundAgainKeepsItsDrawables() {
        // A list recycles its rows on every frame of a scroll. The same view bound with the same
        // edges in the same theme keeps its background and the switch keeps its track and thumb;
        // a changed edge paints again, since the card's corners are what changed.
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        android.widget.FrameLayout row = new android.widget.FrameLayout(context);
        SettingsUi.applyGroupedRow(row, true, false);
        android.graphics.drawable.Drawable painted = row.getBackground();
        assertNotNull(painted);
        SettingsUi.applyGroupedRow(row, true, false);
        assertSame("the same edges painted the row again", painted, row.getBackground());
        SettingsUi.applyGroupedRow(row, true, true);
        assertNotSame("a changed edge kept the old card", painted, row.getBackground());

        android.widget.Switch control = new android.widget.Switch(context);
        SettingsUi.styleSwitch(control);
        android.graphics.drawable.Drawable track = control.getTrackDrawable();
        android.graphics.drawable.Drawable thumb = control.getThumbDrawable();
        assertNotNull(track);
        SettingsUi.styleSwitch(control);
        assertSame("the switch track was rebuilt on a rebind", track, control.getTrackDrawable());
        assertSame("the switch thumb was rebuilt on a rebind", thumb, control.getThumbDrawable());
    }

    @Test @Config(qualifiers = "w360dp-h800dp-night-450dpi")
    public void menuTilesKeepTheirNativeScaleInsideAnImageViewAtPhoneDensity() throws Exception {
        try (var owner = Robolectric.buildActivity(DialogActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            for (int densityDpi : new int[]{160, 450}) {
                Configuration configuration = new Configuration(activity.getResources().getConfiguration());
                configuration.densityDpi = densityDpi;
                Context context = activity.createConfigurationContext(configuration);
                assertEquals("the fixture lost its requested density", densityDpi / 160f,
                        context.getResources().getDisplayMetrics().density, 0.0001f);
                int size = SettingsUi.dp(context, 40);
                for (SettingsMenuPreference.Icon icon : SettingsMenuPreference.Icon.values()) {
                    ImageView image = new ImageView(context);
                    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    Drawable drawable = SettingsMenuPreference.iconDrawable(context, icon);
                    image.setImageDrawable(drawable);
                    image.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
                    image.layout(0, 0, size, size);
                    Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                    try {
                        image.draw(new Canvas(bitmap));
                        assertTrue(icon + " did not render", bitmap.getPixel(size / 2, size / 2) != 0);
                        // The paints already carry pixel-scaled strokes. FIT_CENTER must not
                        // scale the drawing a second time on a dense phone.
                        float[] matrix = new float[9];
                        image.getImageMatrix().getValues(matrix);
                        assertEquals(icon + " scales its already scaled stroke at " + densityDpi + "dpi",
                                1f, matrix[Matrix.MSCALE_X], 0.0001f);
                        assertEquals(1f, matrix[Matrix.MSCALE_Y], 0.0001f);
                        assertEquals(size, drawable.getBounds().width());
                        assertEquals(size, drawable.getBounds().height());
                        if (icon == SettingsMenuPreference.Icon.BEHAVIOR) {
                            UiCapture.save(image, "audit/menu-icon-" + densityDpi + "dpi.png", size, size);
                        }
                    } finally {
                        bitmap.recycle();
                    }
                }
            }
        }
    }
}
