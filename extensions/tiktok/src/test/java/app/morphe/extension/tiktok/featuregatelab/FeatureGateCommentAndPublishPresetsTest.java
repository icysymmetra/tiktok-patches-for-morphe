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

/** Exact typed presets for the native comment emoji and publish hashtag readers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FeatureGateCommentAndPublishPresetsTest {
    private static final String HASHTAG_SWITCH = "studio_publish_limit_hashtag_switch";
    private static final String HASHTAG_MAX = "studio_publish_hashtag_max_limit";
    private static final String COMMENT_EMOJI = "tt_cmt_new_emoji";
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
            if (entry.manager.equals("abmock") && (entry.key.equals(HASHTAG_SWITCH)
                    || entry.key.equals(HASHTAG_MAX) || entry.key.equals(COMMENT_EMOJI)
                    || entry.key.equals("tt_im_new_emoji") || entry.key.equals("tt_repost_new_emoji"))) {
                catalog.put(entry.identity(), entry);
                types.put(entry.key, entry.type);
            }
        }
        assertEquals("all native keys must exist in the shipped catalog", 5, catalog.size());
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

    @Test public void hashtagPresetDisablesOnlyTheBooleanClientCheckAndUndoRestoresIt() throws Exception {
        FeatureGateLabStore.saveRule("abmock", HASHTAG_SWITCH, "BOOLEAN", "true", true);
        FeatureGateLabStore.saveRule("abmock", "tt_im_new_emoji", "INT", "3", false);
        FeatureGateLabStore.setMasterEnabled(true);
        JSONObject before = FeatureGateLabStore.exportSettings();

        FeatureGateLabUndo.applyPreset("47.4.3", "hashtag_limit", catalog);
        assertEquals(2, FeatureGateLabStore.rules().size());
        assertFalse(FeatureGateLabRuntime.overrideBoolean(HASHTAG_SWITCH, true));
        assertFalse(FeatureGateLabRuntime.overrideBoolean(HASHTAG_SWITCH, false));
        assertEquals(Boolean.FALSE, FeatureGateLabRuntime.overrideRawAbValue(HASHTAG_SWITCH, true, false));
        assertEquals(0, FeatureGateLabRuntime.overrideInt(HASHTAG_MAX, 0));
        assertEquals(5, FeatureGateLabRuntime.overrideInt(HASHTAG_MAX, 5));
        assertEquals(7, FeatureGateLabRuntime.overrideInt(HASHTAG_SWITCH, 7));
        assertTrue(FeatureGateLabRuntime.overrideLiveBoolean(HASHTAG_SWITCH, true));
        assertEquals(1, FeatureGateLabRuntime.overrideInt(COMMENT_EMOJI, 1));
        assertFalse(FeatureGateLabStore.rule("abmock", "tt_im_new_emoji", "INT").enabled);
        assertNull(FeatureGateLabStore.rule("abmock", HASHTAG_MAX, "INT"));

        FeatureGateLabUndo.undo();
        assertTrue(FeatureGateLabStore.settingsMatch(before));
        assertTrue(FeatureGateLabRuntime.overrideBoolean(HASHTAG_SWITCH, false));
    }

    @Test public void commentEmojiPresetReturnsGroupTwoAtBothTypedAndRawBoundaries() throws Exception {
        FeatureGateLabStore.setMasterEnabled(true);
        JSONObject before = FeatureGateLabStore.exportSettings();
        FeatureGateLabSession.resetForTests();

        FeatureGateLabUndo.applyPreset("47.4.3", "comment_emoji", catalog);
        assertEquals(1, FeatureGateLabStore.rules().size());
        for (int nativeGroup : new int[]{0, 1, 2, 3}) {
            assertEquals(2, FeatureGateLabRuntime.overrideInt(COMMENT_EMOJI, nativeGroup));
            assertEquals(Integer.valueOf(2),
                    FeatureGateLabRuntime.overrideRawAbValue(COMMENT_EMOJI, nativeGroup, false));
        }
        assertEquals(Integer.valueOf(2), FeatureGateLabRuntime.overrideRawAbValue(COMMENT_EMOJI, null, false));
        assertTrue(FeatureGateLabRuntime.overrideBoolean(COMMENT_EMOJI, true));
        assertEquals(1, FeatureGateLabRuntime.overrideLiveInt(COMMENT_EMOJI, 1));
        assertEquals(1, FeatureGateLabRuntime.overrideInt("tt_im_new_emoji", 1));
        assertEquals(1, FeatureGateLabRuntime.overrideInt("tt_repost_new_emoji", 1));
        assertTrue(FeatureGateLabRuntime.overrideBoolean(HASHTAG_SWITCH, true));
        assertTrue("the lazy native reader requires a restart", FeatureGateLabSession.restartNeeded());

        FeatureGateLabUndo.undo();
        assertTrue(FeatureGateLabStore.settingsMatch(before));
        assertEquals(1, FeatureGateLabRuntime.overrideInt(COMMENT_EMOJI, 1));
    }

    @Test public void theTwoPresetsRemainIndependentAndDoNotTurnOnTheMasterOverride() throws Exception {
        FeatureGateLabUndo.applyPreset("47.4.3", "hashtag_limit", catalog);
        assertNull(FeatureGateLabStore.rule("abmock", COMMENT_EMOJI, "INT"));
        FeatureGateLabUndo.applyPreset("47.4.3", "comment_emoji", catalog);
        assertEquals(2, FeatureGateLabStore.rules().size());
        assertFalse(FeatureGateLabStore.masterEnabled());
        assertTrue(FeatureGateLabRuntime.overrideBoolean(HASHTAG_SWITCH, true));
        assertEquals(1, FeatureGateLabRuntime.overrideInt(COMMENT_EMOJI, 1));

        FeatureGateLabStore.setMasterEnabled(true);
        assertFalse(FeatureGateLabRuntime.overrideBoolean(HASHTAG_SWITCH, true));
        assertEquals(2, FeatureGateLabRuntime.overrideInt(COMMENT_EMOJI, 1));
    }

    @Test public void eitherPresetRefusesMissingOrMistypedKeysAndUnreviewedBuilds() throws Exception {
        for (String[] preset : new String[][]{
                {"hashtag_limit", HASHTAG_SWITCH, "INT"}, {"comment_emoji", COMMENT_EMOJI, "BOOLEAN"}}) {
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
            assertThrows(JSONException.class,
                    () -> FeatureGateLabUndo.applyPreset("47.2.1", preset[0], catalog));
            BuildNames.setRunningBuildForTests("47.4.3");
        }
        assertTrue(FeatureGateLabStore.rules().isEmpty());
        assertFalse(FeatureGateLabUndo.canUndo());
    }
}
