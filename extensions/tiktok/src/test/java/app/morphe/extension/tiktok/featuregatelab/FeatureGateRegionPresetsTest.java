package app.morphe.extension.tiktok.featuregatelab;

import static org.junit.Assert.*;

import android.os.Looper;
import app.morphe.extension.shared.BuildNames;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
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
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * The scrolling comments, several-photos and LIVE auto-translate presets from TikTok's regional
 * builds (#98).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FeatureGateRegionPresetsTest {
    private static final String DANMAKU = "danmaku_exp";
    private static final String LANDSCAPE = "landscape_support_danmaku";
    private static final String PHOTOS = "comment_multi_photo_group";
    private static final String LIVE_TRANSLATE = "live_auto_translate_model_audience";
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Map<String, FeatureGateCatalog.Entry> catalog;

    @Before public void setup() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        FeatureGateCatalog.awaitForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        BuildNames.setRunningBuildForTests("47.4.3");
        FeatureGateLabStore.resetAllLabData();
        FeatureGateLabUndo.resetForTests();
        FeatureGateCatalog.resetForTests();
        FeatureGateLabSession.resetForTests();
        catalog = new LinkedHashMap<>();
        Map<String, String> types = new LinkedHashMap<>();
        for (FeatureGateCatalog.Entry entry : FeatureGateCatalog.readStaticCatalog("47.4.3")) {
            if (entry.manager.equals("abmock") && (entry.key.equals(DANMAKU)
                    || entry.key.equals(LANDSCAPE) || entry.key.equals(PHOTOS))) {
                catalog.put(entry.identity(), entry);
                types.put(entry.key, entry.type);
            }
            if (entry.manager.equals("live") && entry.key.equals(LIVE_TRANSLATE)) {
                catalog.put(entry.identity(), entry);
            }
        }
        assertEquals("all four keys are in the shipped catalog", 4, catalog.size());
        var cachedTypes = FeatureGateCatalog.class.getDeclaredField("cachedAbTypes");
        cachedTypes.setAccessible(true);
        cachedTypes.set(null, Map.copyOf(types));
    }

    @After public void cleanup() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        FeatureGateCatalog.awaitForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        FeatureGateLabStore.resetAllLabData();
        FeatureGateLabUndo.resetForTests();
        FeatureGateCatalog.resetForTests();
        FeatureGateLabSession.resetForTests();
        BuildNames.setRunningBuildForTests(null);
    }

    @Test public void scrollingCommentsSetBothGroupsAndUndoPutsTikToksValuesBack() throws Exception {
        FeatureGateLabStore.saveRule("abmock", PHOTOS, "INT", "1", true);
        FeatureGateLabStore.setMasterEnabled(true);
        JSONObject before = FeatureGateLabStore.exportSettings();

        FeatureGateLabUndo.applyPreset("47.4.3", "scrolling_comments", catalog);
        assertEquals(3, FeatureGateLabStore.rules().size());
        for (int stock : new int[]{0, 1, 2}) {
            assertEquals(1, FeatureGateLabRuntime.overrideInt(DANMAKU, stock));
            assertEquals(1, FeatureGateLabRuntime.overrideInt(LANDSCAPE, stock));
        }
        assertEquals(Integer.valueOf(1), FeatureGateLabRuntime.overrideRawAbValue(DANMAKU, 0, false));
        assertEquals("a rule the preset doesn't name stays as it was", 1,
                FeatureGateLabRuntime.overrideInt(PHOTOS, 0));

        FeatureGateLabUndo.undo();
        assertTrue(FeatureGateLabStore.settingsMatch(before));
        assertEquals(0, FeatureGateLabRuntime.overrideInt(DANMAKU, 0));
        assertEquals(0, FeatureGateLabRuntime.overrideInt(LANDSCAPE, 0));
    }

    @Test public void severalPhotosSelectGroupTwoOnlyWhileOverridesAreOn() throws Exception {
        FeatureGateLabUndo.applyPreset("47.4.3", "comment_photos", catalog);
        assertEquals(1, FeatureGateLabStore.rules().size());
        assertFalse("a preset never turns the overrides switch on", FeatureGateLabStore.masterEnabled());
        assertEquals("off leaves TikTok's value", 0, FeatureGateLabRuntime.overrideInt(PHOTOS, 0));

        FeatureGateLabStore.setMasterEnabled(true);
        for (int stock : new int[]{0, 1, 3}) {
            assertEquals(2, FeatureGateLabRuntime.overrideInt(PHOTOS, stock));
        }
        assertEquals(0, FeatureGateLabRuntime.overrideInt(DANMAKU, 0));

        FeatureGateLabStore.setMasterEnabled(false);
        assertEquals(1, FeatureGateLabRuntime.overrideInt(PHOTOS, 1));
    }

    @Test public void liveTranslateWritesTheLiveSettingAndNotAnAbKey() throws Exception {
        FeatureGateLabUndo.applyPreset("47.4.3", "live_translate", catalog);
        assertEquals(1, FeatureGateLabStore.rules().size());
        FeatureGateLabStore.Rule rule = FeatureGateLabStore.rule("live", LIVE_TRANSLATE, "INT");
        assertNotNull("the rule is a LIVE one", rule);
        assertEquals("2", rule.value);
        assertNull(FeatureGateLabStore.rule("abmock", LIVE_TRANSLATE, "INT"));
        assertFalse("a preset never turns the overrides switch on", FeatureGateLabStore.masterEnabled());
        assertEquals("off leaves TikTok's value", 0, FeatureGateLabRuntime.overrideLiveInt(LIVE_TRANSLATE, 0));

        FeatureGateLabStore.setMasterEnabled(true);
        for (int stock : new int[]{0, 1}) {
            assertEquals(2, FeatureGateLabRuntime.overrideLiveInt(LIVE_TRANSLATE, stock));
        }
        assertEquals("the AB reader of the same name is left alone", 0,
                FeatureGateLabRuntime.overrideInt(LIVE_TRANSLATE, 0));

        FeatureGateLabUndo.undo();
        assertTrue(FeatureGateLabStore.rules().isEmpty());
        assertEquals(0, FeatureGateLabRuntime.overrideLiveInt(LIVE_TRANSLATE, 0));
    }

    @Test public void liveTranslateRefusesAMissingKeyAndAnotherBuild() throws Exception {
        Map<String, FeatureGateCatalog.Entry> missing = new LinkedHashMap<>(catalog);
        missing.remove("live\n" + LIVE_TRANSLATE);
        assertThrows(JSONException.class,
                () -> FeatureGateLabUndo.applyPreset("47.4.3", "live_translate", missing));
        BuildNames.setRunningBuildForTests("47.2.1");
        assertThrows(JSONException.class,
                () -> FeatureGateLabUndo.applyPreset("47.4.3", "live_translate", catalog));
        assertTrue(FeatureGateLabStore.rules().isEmpty());
    }

    @Test public void eitherPresetRefusesMissingOrMistypedKeysAndOtherBuilds() throws Exception {
        for (String[] preset : new String[][]{
                {"scrolling_comments", DANMAKU, "BOOLEAN"}, {"scrolling_comments", LANDSCAPE, "STRING"},
                {"comment_photos", PHOTOS, "BOOLEAN"}}) {
            Map<String, FeatureGateCatalog.Entry> missing = new LinkedHashMap<>(catalog);
            missing.remove("abmock\n" + preset[1]);
            assertThrows(JSONException.class,
                    () -> FeatureGateLabUndo.applyPreset("47.4.3", preset[0], missing));
            Map<String, FeatureGateCatalog.Entry> mistyped = new LinkedHashMap<>(catalog);
            mistyped.put("abmock\n" + preset[1], new FeatureGateCatalog.Entry(
                    preset[1], preset[1], "abmock", preset[2], true, true,
                    List.of(), List.of(), List.of(), "", "", false, null, null));
            assertThrows(JSONException.class,
                    () -> FeatureGateLabUndo.applyPreset("47.4.3", preset[0], mistyped));
            BuildNames.setRunningBuildForTests("47.2.1");
            assertThrows(JSONException.class,
                    () -> FeatureGateLabUndo.applyPreset("47.4.3", preset[0], catalog));
            BuildNames.setRunningBuildForTests("47.4.3");
        }
        assertTrue(FeatureGateLabStore.rules().isEmpty());
        assertFalse(FeatureGateLabUndo.canUndo());
    }
}
