package app.morphe.extension.tiktok.featuregatelab;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import app.morphe.extension.shared.BuildNames;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.UiCapture;
import app.morphe.extension.tiktok.settings.SettingsPagesTest.PageActivity;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

/** Presets need all-or-nothing application, version checks and one undo for the whole set. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w480dp-h960dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FeatureGatePresetsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Map<String, FeatureGateCatalog.Entry> catalog;

    @Before public void setup() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        FeatureGateCatalog.awaitForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        BuildNames.setRunningBuildForTests("47.4.3");
        FeatureGateLabStore.resetAllLabData();
        FeatureGateLabUndo.resetForTests();
        FeatureGateLabFragment.resetForTests();
        FeatureGateCatalog.resetForTests();
        FeatureGateLabSession.resetForTests();
        FeatureGateLabSession.begin();
        Utils.setIsDarkModeEnabled(true);
        catalog = new LinkedHashMap<>();
        for (FeatureGateCatalog.Entry entry : FeatureGateCatalog.readStaticCatalog("47.4.3")) {
            if (entry.key.equals("feed_translation_reverse")
                    || entry.key.equals("cla_translate_button_weaken_v2")) catalog.put(entry.identity(), entry);
        }
        assertEquals("both preset gates must exist in the shipped catalog", 2, catalog.size());
    }

    @After public void cleanup() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        FeatureGateLabStore.resetAllLabData();
        FeatureGateLabUndo.resetForTests();
        FeatureGateCatalog.resetForTests();
        BuildNames.setRunningBuildForTests(null);
    }

    @Test public void aPresetEnablesBothRulesAndOneUndoRestoresPreviousState() throws Exception {
        FeatureGateLabStore.saveRule("abmock", "feed_translation_reverse", "INT", "1", false);
        FeatureGateLabStore.saveRule("abmock", "unrelated", "BOOLEAN", "true", true);
        JSONObject before = FeatureGateLabStore.exportSettings();
        FeatureGateLabUndo.applyPreset("47.4.3", "see_translation", catalog);
        for (FeatureGateCatalog.Entry entry : catalog.values()) {
            FeatureGateLabStore.Rule rule = FeatureGateLabStore.rule(entry.manager, entry.key, entry.type);
            assertEquals("0", rule.value);
            assertTrue(rule.enabled);
        }
        assertTrue(FeatureGateLabStore.rule("abmock", "unrelated", "BOOLEAN").enabled);
        assertFalse("a preset must not enable every other stored override", FeatureGateLabStore.masterEnabled());
        FeatureGateLabUndo.undo();
        // Restore stamps rule updates again; ordering is not part of the saved settings.
        assertTrue(FeatureGateLabStore.settingsMatch(before));
        assertEquals(2, FeatureGateLabStore.rules().size());
        assertEquals("1", FeatureGateLabStore.rule("abmock", "feed_translation_reverse", "INT").value);
        assertFalse(FeatureGateLabStore.rule("abmock", "feed_translation_reverse", "INT").enabled);
        assertNull(FeatureGateLabStore.rule("abmock", "cla_translate_button_weaken_v2", "INT"));
        assertEquals("true", FeatureGateLabStore.rule("abmock", "unrelated", "BOOLEAN").value);
        assertTrue(FeatureGateLabStore.rule("abmock", "unrelated", "BOOLEAN").enabled);
    }

    /**
     * Each preset against the catalog shipped for every build it lists, reviewed the way Apply
     * reviews it: every key registered with the preset's type and a value the Lab accepts. The
     * catalog default is the one in TikTok's code, which the server overrides, so a preset may
     * pin it (See translation does exactly that).
     */
    @Test public void everyPresetMatchesTheShippedCatalogOfEveryBuildItLists() throws Exception {
        int reviewed = 0;
        for (String build : GeneratedGateCatalogBuilds.BUILDS) {
            Map<String, FeatureGateCatalog.Entry> shipped = new LinkedHashMap<>();
            for (FeatureGateCatalog.Entry entry : FeatureGateCatalog.readStaticCatalog(build)) {
                shipped.put(entry.identity(), entry);
            }
            BuildNames.setRunningBuildForTests(build);
            for (FeatureGateLabStore.Preset preset : FeatureGateLabStore.PRESETS) {
                if (!Arrays.asList(preset.builds).contains(build)) continue;
                FeatureGateLabStore.ImportReview review = FeatureGateLabStore.reviewPreset(build, preset.id, shipped);
                assertEquals(preset.id + " on " + build, preset.rules.length, review.accepted.size());
                reviewed++;
            }
        }
        assertEquals(FeatureGateLabStore.PRESETS.length * GeneratedGateCatalogBuilds.BUILDS.length, reviewed);
    }

    /**
     * LIVE chat auto-translate's button also wants a Japanese SIM, which no gate gives it (#98).
     * The preview says so ahead of the values; a preset with nothing more to say adds no line.
     */
    @Test public void aPresetsNoteLeadsItsPreview() throws Exception {
        JSONObject presets = FeatureGateLabStore.reviewedPresets().getJSONObject("47.4.3");
        assertTrue(presets.getJSONObject("live_translate").getString("note").contains("Japanese SIM"));
        assertFalse(presets.getJSONObject("see_translation").has("note"));
        Map<String, FeatureGateCatalog.Entry> everything = new LinkedHashMap<>();
        for (FeatureGateCatalog.Entry entry : FeatureGateCatalog.readStaticCatalog("47.4.3")) {
            everything.put(entry.identity(), entry);
        }
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            var activity = owner.get();
            Utils.setContext(activity);
            var lab = new FeatureGateLabFragment();
            activity.getFragmentManager().beginTransaction().replace(android.R.id.content, lab).commit();
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            var field = FeatureGateLabFragment.class.getDeclaredField("snapshot");
            field.setAccessible(true);
            field.set(lab, new FeatureGateCatalog.Snapshot(List.copyOf(everything.values()), everything, 0, 0, true));
            var show = FeatureGateLabFragment.class.getDeclaredMethod("showPreset", String.class, String.class);
            show.setAccessible(true);
            show.invoke(lab, "47.4.3", "live_translate");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog preview = (AlertDialog) ShadowDialog.getLatestDialog();
            String text = ((TextView) preview.findViewById(android.R.id.message)).getText().toString();
            int note = text.indexOf("Override SIM details");
            assertTrue(text, note > 0);
            assertTrue("the note comes before the values", note < text.indexOf("live_auto_translate_model_audience"));
            preview.dismiss();

            show.invoke(lab, "47.4.3", "see_translation");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            preview = (AlertDialog) ShadowDialog.getLatestDialog();
            text = ((TextView) preview.findViewById(android.R.id.message)).getText().toString();
            assertFalse(text, text.contains("SIM"));
        }
    }

    @Test public void aMissingGateOrAnotherBuildCannotApplyHalfAPreset() throws Exception {
        catalog.remove("abmock\nfeed_translation_reverse");
        assertThrows(JSONException.class, () -> FeatureGateLabUndo.applyPreset("47.4.3", "see_translation", catalog));
        assertTrue(FeatureGateLabStore.rules().isEmpty());
        assertFalse(FeatureGateLabUndo.canUndo());
        BuildNames.setRunningBuildForTests("47.2.1");
        assertThrows(JSONException.class, () -> FeatureGateLabUndo.applyPreset("47.4.3", "see_translation", catalog));
        assertTrue(FeatureGateLabStore.rules().isEmpty());
        assertFalse(FeatureGateLabUndo.canUndo());
    }

    /**
     * The first snapshot holds only TikTok's stored values. Reviewed against it, a preset whose
     * gates TikTok never sent was refused as not matching; it waits for the whole catalog.
     */
    @Test public void aPresetWaitsForTheWholeCatalog() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            var activity = owner.get();
            Utils.setContext(activity);
            var lab = new FeatureGateLabFragment();
            activity.getFragmentManager().beginTransaction().replace(android.R.id.content, lab).commit();
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            var field = FeatureGateLabFragment.class.getDeclaredField("snapshot");
            field.setAccessible(true);
            field.set(lab, new FeatureGateCatalog.Snapshot(List.copyOf(catalog.values()), catalog, 0, 0, false));
            org.robolectric.shadows.ShadowToast.reset();
            var show = FeatureGateLabFragment.class.getDeclaredMethod("showPreset", String.class, String.class);
            show.setAccessible(true);
            show.invoke(lab, "47.4.3", "see_translation");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("Loaded values are still being read. Try again in a moment.",
                    org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
            assertTrue(FeatureGateLabStore.rules().isEmpty());
        }
    }

    @Test public void previewShowsBothChangesBeforeApplyAndMismatchOffersNoApply() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            var activity = owner.get();
            Utils.setContext(activity);
            var cache = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
            cache.setAccessible(true);
            cache.set(null, new FeatureGateCatalog.Snapshot(List.copyOf(catalog.values()), catalog, 0, 0, true));
            var lab = new FeatureGateLabFragment();
            activity.getFragmentManager().beginTransaction().replace(android.R.id.content, lab).commit();
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(lab.getView().findViewWithTag("feature_gate_menu").performClick());
            AlertDialog menu = (AlertDialog) ShadowDialog.getLatestDialog();
            var items = menu.getListView();
            int presetIndex = -1;
            for (int index = 0; index < items.getCount(); index++) {
                if ("Reviewed presets".equals(items.getItemAtPosition(index))) presetIndex = index;
            }
            assertTrue(presetIndex >= 0);
            items.performItemClick(null, presetIndex, items.getItemIdAtPosition(presetIndex));
            AlertDialog presets = (AlertDialog) ShadowDialog.getLatestDialog();
            presets.getListView().performItemClick(null, 0, 0);
            AlertDialog preview = (AlertDialog) ShadowDialog.getLatestDialog();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            String text = ((TextView) preview.findViewById(android.R.id.message)).getText().toString();
            assertTrue(text.contains("feed_translation_reverse"));
            assertTrue(text.contains("cla_translate_button_weaken_v2"));
            assertTrue(text.contains("to 0"));
            assertTrue(text.contains("Turn on overrides"));
            assertTrue(FeatureGateLabStore.rules().isEmpty());
            UiCapture.save(preview.getWindow().getDecorView(), "pages/dark/lab-preset.png", 480, 400);
            preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            // AlertDialog posts its button listener to the main looper before it queues work.
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Utils.awaitBackgroundTasksForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(2, FeatureGateLabStore.rules().size());
            FeatureGateLabUndo.undo();
            assertTrue(FeatureGateLabStore.rules().isEmpty());

            BuildNames.setRunningBuildForTests("47.2.1");
            var show = FeatureGateLabFragment.class.getDeclaredMethod("showPreset", String.class, String.class);
            show.setAccessible(true);
            show.invoke(lab, "47.4.3", "see_translation");
            AlertDialog mismatch = (AlertDialog) ShadowDialog.getLatestDialog();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            View apply = mismatch.getButton(AlertDialog.BUTTON_POSITIVE);
            assertTrue(apply == null || apply.getVisibility() != View.VISIBLE);
            assertTrue(((TextView) mismatch.findViewById(android.R.id.message)).getText().toString()
                    .contains("isn't available"));
            UiCapture.save(mismatch.getWindow().getDecorView(), "pages/dark/lab-preset-mismatch.png", 480, 300);
            assertTrue(FeatureGateLabStore.rules().isEmpty());
            mismatch.dismiss();
        }
    }
}
