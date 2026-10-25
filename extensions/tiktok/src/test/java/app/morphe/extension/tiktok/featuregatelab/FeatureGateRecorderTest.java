package app.morphe.extension.tiktok.featuregatelab;

import static org.junit.Assert.*;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.FeatureGateRecorderPreference;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FeatureGateRecorderTest {
    @Before public void setup() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SettingsManagerObservationRecorder.clear();
        SettingsStatus.featureGateRecorderEnabled = true;
        FeatureGateLearnMode.cancel();
    }
    @After public void tearDown() {
        SettingsStatus.featureGateRecorderEnabled = false;
        FeatureGateLearnMode.cancel();
        SettingsManagerObservationRecorder.clear();
    }
    @Test public void theExportedReportCarriesNoAddressesOrIdentifiers() throws Exception {
        // This is the one artifact the issue templates ask people to attach, and gate values are
        // server config: CDN hosts, endpoint addresses and per-install ids turn up in them.
        String secret = "{\"cdn\":\"https://v16-webapp.tiktokcdn.com/x?token=abc\",\"device_id\":\"1234567890123456789\"}";
        SettingsManagerObservationRecorder.observeWithDefault("config", String.class, "", "before");
        FeatureGateLearnMode.begin();
        SettingsManagerObservationRecorder.observeWithoutDefault("config", String.class, secret);

        String reportText = FeatureGateLearnMode.stopAndBuildReport();

        assertFalse(reportText, reportText.contains("tiktokcdn.com"));
        assertFalse(reportText, reportText.contains("token=abc"));
        assertFalse(reportText, reportText.contains("1234567890123456789"));
        // Redacting values rather than structure, so the file is still a report.
        JSONObject report = new JSONObject(reportText);
        String after = report.getJSONArray("gates").getJSONObject(0).getString("after");
        assertTrue(after, after.contains("omitted"));
        assertTrue("the gate name is what makes the report useful", reportText.contains("config"));
    }

    @Test public void recordsRepeatedReadsNewKeysAndChangedValuesWithoutOverriding() throws Exception {
        assertTrue(FeatureGateLabRuntime.overrideBoolean("known", true));
        FeatureGateLearnMode.begin();
        assertTrue(FeatureGateLabRuntime.overrideBoolean("known", true));
        assertFalse(FeatureGateLabRuntime.overrideBoolean("known", false));
        assertEquals(7, FeatureGateLabRuntime.overrideInt("new_key", 7));
        assertEquals(2.5f, (Float) FeatureGateLabRuntime.overridePlayerValue("speed", Float.class, 2.5f), 0f);
        JSONObject report = new JSONObject(FeatureGateLearnMode.stopAndBuildReport());
        assertEquals(3, report.getInt("gate_count"));
        assertEquals(2, report.getInt("new_count"));
        assertEquals(1, report.getInt("changed_count"));
        JSONObject known = report.getJSONArray("gates").getJSONObject(0);
        assertEquals("changed", known.getString("state"));
        assertEquals(2, known.getInt("calls"));
        assertEquals("true", known.getString("before"));
        assertEquals("false", known.getString("after"));
        String stopped = FeatureGateLearnMode.lastReport();
        FeatureGateLabRuntime.overrideInt("after_stop", 9);
        assertEquals(stopped, FeatureGateLearnMode.stopAndBuildReport());
        FeatureGateLearnMode.begin();
        FeatureGateLabRuntime.overrideBoolean("known", false);
        assertEquals("read", new JSONObject(FeatureGateLearnMode.stopAndBuildReport())
                .getJSONArray("gates").getJSONObject(0).getString("state"));
    }
    @Test public void deduplicatedSettingsManagerReadsStillAppearDuringRecording() throws Exception {
        SettingsManagerObservationRecorder.observeWithDefault("structured", String.class, "", "before");
        FeatureGateLearnMode.begin();
        SettingsManagerObservationRecorder.observeWithDefault("structured", String.class, "", "after");
        SettingsManagerObservationRecorder.observeWithoutDefault("structured", String.class, "after");
        JSONObject report = new JSONObject(FeatureGateLearnMode.stopAndBuildReport());
        assertEquals(2, report.getInt("gate_count"));
        assertEquals(1, report.getInt("changed_count"));
    }
    @Test public void recordingRemainsBoundedAndReportsDroppedReads() throws Exception {
        FeatureGateLearnMode.begin();
        for (int i = 0; i < 2050; i++) FeatureGateLearnMode.observe("test", "gate_" + i, "INT", i);
        JSONObject report = new JSONObject(FeatureGateLearnMode.stopAndBuildReport());
        assertEquals(2048, report.getInt("gate_count"));
        assertEquals(2, report.getInt("dropped_reads"));
        FeatureGateLearnMode.begin();
        FeatureGateLearnMode.observe("test", "new_after_full_baseline", "INT", 1);
        assertEquals(1, new JSONObject(FeatureGateLearnMode.stopAndBuildReport()).getInt("gate_count"));
    }
    @Test @Config(qualifiers = "w480dp-h960dp-night-mdpi")
    public void controlStartsImmediatelyAndStopShowsTheReport() throws Exception { captureReport(true); }

    @Test @Config(qualifiers = "w480dp-h960dp-notnight-mdpi")
    public void reportRemainsReadableInLightTheme() throws Exception { captureReport(false); }

    @Test public void aFreshInstallSaysNothingHasBeenRecordedAndActsOnTheTap() {
        FeatureGateLearnMode.resetForTests();
        FeatureGateRecorderPreference control = new FeatureGateRecorderPreference(RuntimeEnvironment.getApplication());
        assertEquals("No recording yet. Start one, use a feature, then return here.",
                String.valueOf(control.getSummary()));
        assertTrue("a tap that starts recording on the spot wore a chevron", control.actsOnTap());
        control.getOnPreferenceClickListener().onPreferenceClick(control);
        assertTrue(FeatureGateLearnMode.isRecording());
        assertFalse("Stop opens a dialog, so the chevron is right while recording", control.actsOnTap());
    }

    @Test public void aStopWithNothingReadIsAnEmptyStateWithNoExportButtons() throws Exception {
        FeatureGateLearnMode.resetForTests();
        try (var owner = Robolectric.buildActivity(
                app.morphe.extension.tiktok.settings.SettingsPagesTest.PageActivity.class).setup().visible()) {
            var activity = owner.get();
            Utils.setContext(activity);
            FeatureGateRecorderPreference control = new FeatureGateRecorderPreference(activity);
            control.getOnPreferenceClickListener().onPreferenceClick(control);
            control.getOnPreferenceClickListener().onPreferenceClick(control);
            var dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(dialog.isShowing());
            android.widget.Button save = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
            android.widget.Button copy = dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL);
            assertTrue("Save report was offered for an empty recording",
                    save == null || save.getVisibility() != android.view.View.VISIBLE);
            assertTrue("Copy report was offered for an empty recording",
                    copy == null || copy.getVisibility() != android.view.View.VISIBLE);
            android.widget.ScrollView scroll = (android.widget.ScrollView) org.robolectric.Shadows.shadowOf(dialog).getView();
            String shown = ((android.widget.TextView) scroll.getChildAt(0)).getText().toString();
            assertTrue(shown, shown.startsWith("No gate reads were recorded. Use a TikTok feature while recording."));
            dialog.dismiss();
        }
    }

    @Test public void copyLeavesTheReportOnScreenAndSaveSitsLast() throws Exception {
        FeatureGateLearnMode.resetForTests();
        FeatureGateLearnMode.observe("abmock", "circle_search_block", "BOOLEAN", false);
        try (var owner = Robolectric.buildActivity(
                app.morphe.extension.tiktok.settings.SettingsPagesTest.PageActivity.class).setup().visible()) {
            var activity = owner.get();
            Utils.setContext(activity);
            FeatureGateRecorderPreference control = new FeatureGateRecorderPreference(activity);
            control.getOnPreferenceClickListener().onPreferenceClick(control);
            FeatureGateLabRuntime.overrideBoolean("circle_search_block", false);
            control.getOnPreferenceClickListener().onPreferenceClick(control);
            var dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("Save report", dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertEquals("Close", dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).getText().toString());

            org.robolectric.shadows.ShadowToast.reset();
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue("Copy closed the report", dialog.isShowing());
            assertEquals("Feature gate report copied",
                    String.valueOf(org.robolectric.shadows.ShadowToast.getTextOfLatestToast()));
            dialog.dismiss();

            // The report outlives its dialog: the row offers it again rather than starting on the spot.
            assertFalse(control.actsOnTap());
            control.getOnPreferenceClickListener().onPreferenceClick(control);
            var offer = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("Show last report", offer.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).getText().toString());
            offer.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            var again = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(again != offer && again.isShowing());
            again.dismiss();
        }
    }

    private void captureReport(boolean dark) throws Exception {
        // Two things in this report are drawn into the picture and neither is about this test.
        // The times it started and stopped come off the wall clock, so no two captures of an
        // unchanged tree match. And whether the gate reads as new or as changed comes from what
        // the recorder remembers from every earlier test in the sandbox, so the class order and
        // running one case alone both moved it. Frozen and emptied, the picture says what this
        // case did.
        FeatureGateLearnMode.setClockForTests(() -> 1_757_000_000_000L);
        FeatureGateLearnMode.resetForTests();
        FeatureGateLearnMode.observe("abmock", "circle_search_block", "BOOLEAN", false);
        try (var owner = Robolectric.buildActivity(
                app.morphe.extension.tiktok.settings.SettingsPagesTest.PageActivity.class).setup().visible()) {
            var activity = owner.get();
            Utils.setContext(activity);
            Utils.setIsDarkModeEnabled(dark);
            FeatureGateRecorderPreference control = new FeatureGateRecorderPreference(activity);
            control.getOnPreferenceClickListener().onPreferenceClick(control);
            assertTrue(FeatureGateLearnMode.isRecording());
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
            FeatureGateLabRuntime.overrideBoolean("circle_search_block", false);
            control.getOnPreferenceClickListener().onPreferenceClick(control);
            assertFalse(FeatureGateLearnMode.isRecording());
            var dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(dialog.isShowing());
            assertTrue(FeatureGateLearnMode.lastReport().contains("circle_search_block"));
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            app.morphe.extension.tiktok.UiCapture.save(dialog.getWindow().getDecorView(),
                    dark ? "gate-recording.png" : "gate-recording-light.png");
            dialog.dismiss();
        } finally {
            FeatureGateLearnMode.setClockForTests(null);
        }
    }
}
