package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.preference.PreferenceActivity;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * What the SIM preset row says underneath itself.
 *
 * <p>Three answers: the preset it recognises, nothing chosen at all, and details that match no
 * preset. The row is 313 lines and none of them were reached by a test.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@SuppressWarnings("deprecation")
public class SimPresetRowTest {
    public static final class TestActivity extends PreferenceActivity {}

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.SIM_SPOOF_ISO.resetToDefault();
        Settings.SIMSPOOF_MCCMNC.resetToDefault();
        Settings.SIMSPOOF_OP_NAME.resetToDefault();
    }

    @Test
    public void theRowNamesThePresetItRecognises() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            SimPresetPreference row = build(controller.get());

            // The defaults are a real preset, so the row should name it rather than call it custom.
            row.refreshSummary(
                    Settings.SIM_SPOOF_ISO.defaultValue,
                    Settings.SIMSPOOF_MCCMNC.defaultValue,
                    Settings.SIMSPOOF_OP_NAME.defaultValue);
            String named = row.getSummary().toString();
            assertEquals("(" + Settings.SIMSPOOF_MCCMNC.defaultValue + ")",
                    named.substring(named.lastIndexOf('(')));
        }
    }

    @Test
    public void emptyDetailsReadAsNothingChosenAndAMixtureReadsAsCustom() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            SimPresetPreference row = build(controller.get());

            row.refreshSummary("", "", "");
            assertEquals("No preset selected. Choose a preset above.", row.getSummary().toString());

            row.refreshSummary("us", "310260", "Not a real operator");
            assertEquals("Custom SIM details", row.getSummary().toString());
        }
    }

    /**
     * The dialog behind the row, on a German phone.
     *
     * <p>Its title, its helper sentence and the search box were built with plain English
     * strings, so they went out in English however many tables held them: two of them had a
     * German row already and simply never asked for it.
     */
    @Test
    @Config(sdk = 28, qualifiers = "de-rDE")
    public void thePresetDialogReadsGermanOnAGermanPhone() throws Exception {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());
            SimPresetPreference row = build(controller.get());

            java.lang.reflect.Method show =
                    SimPresetPreference.class.getDeclaredMethod("showPresetDialog");
            show.setAccessible(true);
            show.invoke(row);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            android.app.Dialog dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertNotNull("the preset dialog did not open", dialog);
            List<String> shown = new ArrayList<>();
            collectText(dialog.getWindow().getDecorView(), shown);

            assertTrue("the dialog title is still English: " + shown,
                    shown.contains("SIM-Landesvorlage") || shown.contains(germanFor(
                            "SIM country preset")));
            assertTrue("the helper sentence is still English: " + shown,
                    shown.contains(germanFor("Choose a preset to fill the SIM details.")));
            assertTrue("the search box hint is still English: " + shown,
                    shown.contains(germanFor("Search countries or operators")));
        }
    }

    /** What the shipped table says, so the test does not repeat the translation. */
    private static String germanFor(String english) {
        String translated = app.morphe.extension.tiktok.settings.L10n.t(
                RuntimeEnvironment.getApplication().createConfigurationContext(
                        germanConfiguration()), english);
        assertNotEquals("no German row for: " + english, english, translated);
        return translated;
    }

    private static android.content.res.Configuration germanConfiguration() {
        android.content.res.Configuration german = new android.content.res.Configuration(
                RuntimeEnvironment.getApplication().getResources().getConfiguration());
        german.setLocale(java.util.Locale.GERMANY);
        return german;
    }

    private static void collectText(android.view.View view, List<String> found) {
        if (view instanceof android.widget.TextView) {
            android.widget.TextView label = (android.widget.TextView) view;
            if (label.getText() != null && label.getText().length() > 0) {
                found.add(label.getText().toString());
            }
            if (label.getHint() != null && label.getHint().length() > 0) {
                found.add(label.getHint().toString());
            }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectText(group.getChildAt(index), found);
            }
        }
    }

    /**
     * The diagnostics picker on a German phone. It lives in the shared library, which cannot
     * reach a translation table because it is shared with bundles that carry none, so this
     * bundle hands it the words through the hooks the row already used for its own title.
     */
    @Test
    @Config(sdk = 28, qualifiers = "de-rDE")
    public void theDiagnosticsPickerReadsGermanOnAGermanPhone() throws Exception {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            // body below
            Utils.setContext(controller.get());
            // The page only carries this row on a bundle with the diagnostics patch in it.
            app.morphe.extension.tiktok.settings.SettingsStatus.diagnosticsEnabled = true;
            android.preference.PreferenceScreen screen =
                    controller.get().getPreferenceManager().createPreferenceScreen(
                            controller.get());
            new app.morphe.extension.tiktok.settings.preference.categories
                    .DebugPreferenceCategory(controller.get(), screen);

            android.preference.Preference row = screen.findPreference("action_included_diagnostics");
            assertNotNull("the diagnostics picker is not on the page", row);
            row.getOnPreferenceClickListener().onPreferenceClick(row);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            android.app.Dialog dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertNotNull("the picker did not open", dialog);
            var shadow = org.robolectric.Shadows.shadowOf((android.app.AlertDialog) dialog);
            assertEquals("the picker title is still English",
                    germanFor("Include diagnostic events"), shadow.getTitle());
            java.util.List<String> items = new ArrayList<>();
            for (CharSequence item : shadow.getItems()) items.add(item.toString());
            assertTrue("the picker options are still English: " + items,
                    items.contains(germanFor("All events")));
            assertEquals("the Apply button is still English",
                    germanFor("Apply"),
                    ((android.app.AlertDialog) dialog)
                            .getButton(android.app.AlertDialog.BUTTON_POSITIVE).getText()
                            .toString());
        } finally {
            app.morphe.extension.tiktok.settings.SettingsStatus.diagnosticsEnabled = false;
        }
    }

    /**
     * The sentence under the diagnostics picker, in German and in English.
     *
     * <p>The shared library lower-cased each kind before joining it into the sentence, which
     * reads right for English and wrong for a language that capitalises its nouns:
     * Einstellungen came out as einstellungen. Overriding that to leave the label alone moved
     * the fault rather than fixing it, because every table capitalises these labels and only
     * German wanted them that way mid-sentence. The list comes after a colon now, so each
     * language keeps its own capital, and both phones are checked here: a German-only test is
     * what let the English regression through.
     */
    @Test
    @Config(sdk = 28, qualifiers = "de-rDE")
    public void theDiagnosticsSummaryKeepsTheCapitalsGermanNounsHave() throws Exception {
        assertSummaryKeepsItsCapitals(SimPresetRowTest::germanFor);
    }

    @Test
    @Config(sdk = 28, qualifiers = "en-rUS")
    public void theDiagnosticsSummaryKeepsTheCapitalsInEnglishToo() throws Exception {
        // No table answers for English, so the label is its own key, capital and all.
        assertSummaryKeepsItsCapitals(english -> english);
    }

    private static void assertSummaryKeepsItsCapitals(
            java.util.function.UnaryOperator<String> translate) throws Exception {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());
            app.morphe.extension.tiktok.settings.SettingsStatus.diagnosticsEnabled = true;
            app.morphe.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.save(
                    "settings,errors");
            android.preference.PreferenceScreen screen =
                    controller.get().getPreferenceManager().createPreferenceScreen(
                            controller.get());
            new app.morphe.extension.tiktok.settings.preference.categories
                    .DebugPreferenceCategory(controller.get(), screen);

            android.preference.Preference row = screen.findPreference("action_included_diagnostics");
            assertNotNull("the diagnostics picker is not on the page", row);
            String summary = String.valueOf(row.getSummary());

            for (String english : new String[]{"Settings", "Errors"}) {
                String label = translate.apply(english);
                assertTrue("the kind is missing from the sentence: " + summary,
                        summary.contains(label));
                assertFalse("the kind was lower-cased on its way into the sentence: " + summary,
                        summary.contains(label.toLowerCase(java.util.Locale.ROOT)));
            }
        } finally {
            app.morphe.extension.tiktok.settings.SettingsStatus.diagnosticsEnabled = false;
            app.morphe.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
        }
    }

    /**
     * A query that matches nothing used to put a null in the result list and render it as a
     * disabled row, so the list reported one item and the explanation read as a country nobody
     * was allowed to pick.
     */
    @Test
    public void aQueryThatMatchesNothingEmptiesTheListAndExplainsItself() throws Exception {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            SimPresetPreference row = build(controller.get());
            java.lang.reflect.Method show =
                    SimPresetPreference.class.getDeclaredMethod("showPresetDialog");
            show.setAccessible(true);
            show.invoke(row);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            android.app.AlertDialog dialog = (android.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertNotNull("the preset dialog never opened", dialog);
            android.view.View root = dialog.getWindow().getDecorView();
            android.widget.ListView list = findView(root, android.widget.ListView.class);
            android.widget.EditText search = findView(root, android.widget.EditText.class);
            android.widget.TextView count = root.findViewWithTag("sim_preset_result_count");
            android.widget.TextView empty = root.findViewWithTag("sim_preset_empty_state");
            assertNotNull(list);
            assertNotNull(search);
            assertNotNull("the dialog has no result status", count);
            assertNotNull("the dialog has no empty state", empty);

            int all = list.getAdapter().getCount();
            assertTrue("the fixture listed no presets at all", all > 1);
            assertEquals(all + " results", count.getText().toString());
            assertEquals(android.view.View.GONE, empty.getVisibility());
            assertEquals(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE,
                    count.getAccessibilityLiveRegion());
            assertEquals("the explanation is a second thing that speaks",
                    android.view.View.ACCESSIBILITY_LIVE_REGION_NONE,
                    empty.getAccessibilityLiveRegion());

            search.setText("zzzzz-no-such-country");
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            assertEquals("a no-match still offered something to pick",
                    0, list.getAdapter().getCount());
            assertEquals("0 results", count.getText().toString());
            assertEquals(android.view.View.VISIBLE, empty.getVisibility());
            assertFalse("the explanation can be focused like a result", empty.isFocusable());
            String explanation = empty.getText().toString();
            assertTrue(explanation, explanation.contains("No matching countries"));
            assertTrue(explanation, explanation.contains("two-letter code"));

            search.setText("");
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals("clearing the query did not bring the presets back",
                    all, list.getAdapter().getCount());
            assertEquals(all + " results", count.getText().toString());
            assertEquals(android.view.View.GONE, empty.getVisibility());
            dialog.dismiss();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends android.view.View> T findView(android.view.View view, Class<T> type) {
        if (type.isInstance(view)) return (T) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                T found = findView(group.getChildAt(index), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static SimPresetPreference build(Context context) {
        return new SimPresetPreference(
                context,
                new InputTextPreference(context, "ISO", "", Settings.SIM_SPOOF_ISO),
                new InputTextPreference(context, "MCC/MNC", "", Settings.SIMSPOOF_MCCMNC),
                new InputTextPreference(context, "Operator", "", Settings.SIMSPOOF_OP_NAME));
    }
    /**
     * A preset row answers a press and shows when focus reaches it.
     *
     * <p>Each row was painted an opaque colour, and a ListView draws its selector underneath the
     * item, so the one press highlight the row could have had was covered by its own fill.
     */
    @Test
    public void aPresetRowAnswersAPressAndShowsItsFocus() throws Exception {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            SimPresetPreference row = build(controller.get());
            java.lang.reflect.Method show =
                    SimPresetPreference.class.getDeclaredMethod("showPresetDialog");
            show.setAccessible(true);
            show.invoke(row);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            android.app.AlertDialog dialog = (android.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            android.widget.ListView list = findView(
                    dialog.getWindow().getDecorView(), android.widget.ListView.class);
            android.view.View presetRow = list.getAdapter().getView(0, null, list);

            android.graphics.drawable.Drawable background = presetRow.getBackground();
            assertTrue("a preset row is painted flat, so a press shows nothing: "
                            + background.getClass().getSimpleName(),
                    background instanceof android.graphics.drawable.RippleDrawable);
            assertNotEquals("a preset row looks the same focused as it does at rest",
                    renderOf(background, new int[0]),
                    renderOf(background, new int[]{android.R.attr.state_focused}));
            // A ListView never focuses its rows: the d-pad marks the row it is on as selected and
            // draws its own selector beneath, where the row's opaque fill hid it. The wash has to
            // answer the selected state too, or a keyboard reader sees nothing move.
            assertNotEquals("a preset row shows nothing when the d-pad lands on it",
                    renderOf(background, new int[0]),
                    renderOf(background, new int[]{android.R.attr.state_selected}));
            dialog.dismiss();
        }
    }

    /**
     * The preset the fields already hold is marked and brought into view, and Clear preset takes
     * the row back to none. The list used to be sixty identical rows with no sign of which one
     * was on.
     */
    @Test
    public void theChosenPresetIsMarkedAndClearPresetUnpicksIt() throws Exception {
        app.morphe.extension.tiktok.spoof.sim.SimPreset germany = null;
        for (app.morphe.extension.tiktok.spoof.sim.SimPreset preset
                : app.morphe.extension.tiktok.spoof.sim.SimPresets.PRESETS) {
            if ("Germany".equals(preset.country)) germany = preset;
        }
        org.junit.Assert.assertNotNull(germany);
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Settings.SIM_SPOOF_ISO.save(germany.iso);
            Settings.SIMSPOOF_MCCMNC.save(germany.mccMnc);
            Settings.SIMSPOOF_OP_NAME.save(germany.operatorName);
            SimPresetPreference row = build(controller.get());
            java.lang.reflect.Method show =
                    SimPresetPreference.class.getDeclaredMethod("showPresetDialog");
            show.setAccessible(true);
            show.invoke(row);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            android.app.AlertDialog dialog = (android.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            android.widget.ListView list = findView(
                    dialog.getWindow().getDecorView(), android.widget.ListView.class);
            int index = java.util.Arrays.asList(
                    app.morphe.extension.tiktok.spoof.sim.SimPresets.PRESETS).indexOf(germany);
            org.junit.Assert.assertEquals("the chosen preset is not marked",
                    index, list.getCheckedItemPosition());
            // A ListView in touch mode keeps a requested position for its next layout rather
            // than as a selection, so the row's place is read after one.
            list.measure(android.view.View.MeasureSpec.makeMeasureSpec(480, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(320, android.view.View.MeasureSpec.EXACTLY));
            list.layout(0, 0, 480, 320);
            org.junit.Assert.assertEquals("the chosen preset was not brought into view",
                    index, list.getFirstVisiblePosition());
            android.view.View chosen = list.getAdapter().getView(index, null, list);
            // TalkBack hears the choice from the list's checked row (asserted above) through the
            // item delegate the list gives rows that have none. A delegate of the row's own
            // replaced it, and took the click action Switch Access picks a preset with.
            org.junit.Assert.assertNull("a row delegate would replace the list's item delegate",
                    chosen.getAccessibilityDelegate());
            org.junit.Assert.assertFalse("the chosen preset wears the focus wash", chosen.isSelected());
            android.widget.TextView title = chosen.findViewById(android.R.id.text1);
            org.junit.Assert.assertNotNull("the row carries no mark",
                    title.getCompoundDrawablesRelative()[0]);
            android.view.View other = list.getAdapter().getView(index == 0 ? 1 : 0, null, list);
            org.junit.Assert.assertFalse(other.isSelected());
            org.junit.Assert.assertNull(((android.widget.TextView) other.findViewById(
                    android.R.id.text1)).getCompoundDrawablesRelative()[0]);

            android.view.View clear = dialog.getWindow().getDecorView()
                    .findViewWithTag("sim_preset_clear");
            org.junit.Assert.assertNotNull("there is no way back to no preset", clear);
            clear.performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            org.junit.Assert.assertFalse(dialog.isShowing());
            org.junit.Assert.assertEquals("", Settings.SIM_SPOOF_ISO.get());
            org.junit.Assert.assertEquals("No preset selected. Choose a preset above.", String.valueOf(row.getSummary()));
        } finally {
            Settings.SIM_SPOOF_ISO.resetToDefault();
            Settings.SIMSPOOF_MCCMNC.resetToDefault();
            Settings.SIMSPOOF_OP_NAME.resetToDefault();
        }
    }

    /**
     * The chosen row's operator line sits where every other row's does.
     *
     * <p>The radio mark went on the title at its own 32dp height, taller than a line of the
     * title, so the chosen row's title grew and pushed its operator line about 10dp below the
     * other rows' (seen on the S22, 2026-09-29).
     */
    @Test
    public void theChosenRowsOperatorLineSitsWhereTheOthersDo() throws Exception {
        app.morphe.extension.tiktok.spoof.sim.SimPreset germany = null;
        for (app.morphe.extension.tiktok.spoof.sim.SimPreset preset
                : app.morphe.extension.tiktok.spoof.sim.SimPresets.PRESETS) {
            if ("Germany".equals(preset.country)) germany = preset;
        }
        assertNotNull(germany);
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Settings.SIM_SPOOF_ISO.save(germany.iso);
            Settings.SIMSPOOF_MCCMNC.save(germany.mccMnc);
            Settings.SIMSPOOF_OP_NAME.save(germany.operatorName);
            SimPresetPreference row = build(controller.get());
            java.lang.reflect.Method show =
                    SimPresetPreference.class.getDeclaredMethod("showPresetDialog");
            show.setAccessible(true);
            show.invoke(row);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            android.app.AlertDialog dialog = (android.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            android.widget.ListView list = findView(
                    dialog.getWindow().getDecorView(), android.widget.ListView.class);
            int index = java.util.Arrays.asList(
                    app.morphe.extension.tiktok.spoof.sim.SimPresets.PRESETS).indexOf(germany);
            // Rows the list itself laid out, attached to the dialog's window: a start drawable is
            // only resolved, and only counts toward the title's height, on an attached view.
            list.measure(android.view.View.MeasureSpec.makeMeasureSpec(480, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(640, android.view.View.MeasureSpec.EXACTLY));
            list.layout(0, 0, 480, 640);
            int at = index - list.getFirstVisiblePosition();
            android.view.View chosen = list.getChildAt(at);
            android.view.View other = list.getChildAt(at == 0 ? 1 : at - 1);
            assertNotNull("the chosen row was not laid out", chosen);
            assertNotNull("no second row was laid out", other);
            assertNotNull("the row carries no mark", ((android.widget.TextView)
                    chosen.findViewById(android.R.id.text1)).getCompoundDrawablesRelative()[0]);

            assertEquals("the mark makes the chosen row's title taller than the others'",
                    other.findViewById(android.R.id.text1).getHeight(),
                    chosen.findViewById(android.R.id.text1).getHeight());
            assertEquals("the chosen row's operator line sits lower than the others'",
                    other.findViewById(android.R.id.text2).getTop(),
                    chosen.findViewById(android.R.id.text2).getTop());
            dialog.dismiss();
        } finally {
            Settings.SIM_SPOOF_ISO.resetToDefault();
            Settings.SIMSPOOF_MCCMNC.resetToDefault();
            Settings.SIMSPOOF_OP_NAME.resetToDefault();
        }
    }

    /**
     * The same row chosen and not, at large font sizes and narrow widths. The mark took 40dp of
     * the chosen title's width, so a long country name wrapped only when chosen and pushed its
     * operator line down a line.
     */
    @Test
    public void theChosenRowsOperatorLineSitsWhereItWouldUnchosenAtLargeFontSizes() throws Exception {
        List<String> moved = new ArrayList<>();
        float[] scales = {1.0f, 1.5f, 1.8f, 2.0f};
        int[] widths = {280, 328, 360};
        String[] countries = {"United Arab Emirates", "United Kingdom", "Czech Republic"};
        try {
            for (String country : countries) {
                app.morphe.extension.tiktok.spoof.sim.SimPreset target = null, other = null;
                for (app.morphe.extension.tiktok.spoof.sim.SimPreset preset
                        : app.morphe.extension.tiktok.spoof.sim.SimPresets.PRESETS) {
                    if (country.equals(preset.country)) target = preset;
                    if ("Germany".equals(preset.country)) other = preset;
                }
                assertNotNull(country, target);
                int index = java.util.Arrays.asList(
                        app.morphe.extension.tiktok.spoof.sim.SimPresets.PRESETS).indexOf(target);
                for (float scale : scales) {
                    RuntimeEnvironment.setFontScale(scale);
                    int[][] tops = new int[2][widths.length];
                    for (int pass = 0; pass < 2; pass++) {
                        app.morphe.extension.tiktok.spoof.sim.SimPreset chosen = pass == 0 ? target : other;
                        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
                            Settings.SIM_SPOOF_ISO.save(chosen.iso);
                            Settings.SIMSPOOF_MCCMNC.save(chosen.mccMnc);
                            Settings.SIMSPOOF_OP_NAME.save(chosen.operatorName);
                            SimPresetPreference row = build(controller.get());
                            java.lang.reflect.Method show =
                                    SimPresetPreference.class.getDeclaredMethod("showPresetDialog");
                            show.setAccessible(true);
                            show.invoke(row);
                            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                            android.app.AlertDialog dialog = (android.app.AlertDialog)
                                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
                            android.widget.ListView list = findView(
                                    dialog.getWindow().getDecorView(), android.widget.ListView.class);
                            for (int w = 0; w < widths.length; w++) {
                                android.view.View view = null;
                                for (int attempt = 0; attempt < 3 && view == null; attempt++) {
                                    list.setSelectionFromTop(index, 0);
                                    list.measure(android.view.View.MeasureSpec.makeMeasureSpec(
                                                    widths[w], android.view.View.MeasureSpec.EXACTLY),
                                            android.view.View.MeasureSpec.makeMeasureSpec(
                                                    1200, android.view.View.MeasureSpec.EXACTLY));
                                    list.layout(0, 0, widths[w], 1200);
                                    for (int c = 0; c < list.getChildCount(); c++) {
                                        android.view.View child = list.getChildAt(c);
                                        android.widget.TextView text = child.findViewById(android.R.id.text1);
                                        if (text != null && country.contentEquals(text.getText())) view = child;
                                    }
                                }
                                assertNotNull(country + " row not laid out", view);
                                tops[pass][w] = view.findViewById(android.R.id.text2).getTop();
                            }
                            dialog.dismiss();
                        }
                    }
                    for (int w = 0; w < widths.length; w++) {
                        if (tops[0][w] != tops[1][w]) {
                            moved.add(country + " at font scale " + scale + ", width " + widths[w]
                                    + ": chosen " + tops[0][w] + ", unchosen " + tops[1][w]);
                        }
                    }
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(1.0f);
            Settings.SIM_SPOOF_ISO.resetToDefault();
            Settings.SIMSPOOF_MCCMNC.resetToDefault();
            Settings.SIMSPOOF_OP_NAME.resetToDefault();
        }
        assertTrue(String.join("\n", moved), moved.isEmpty());
    }

    /** What a background actually paints in the state given, every pixel of it, as one number. */
    private static int renderOf(android.graphics.drawable.Drawable background, int[] state) {
        background.setState(state);
        background.setBounds(0, 0, 64, 48);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                64, 48, android.graphics.Bitmap.Config.ARGB_8888);
        background.draw(new android.graphics.Canvas(bitmap));
        int hash = 17;
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 48; y++) hash = hash * 31 + bitmap.getPixel(x, y);
        }
        bitmap.recycle();
        return hash;
    }
}
