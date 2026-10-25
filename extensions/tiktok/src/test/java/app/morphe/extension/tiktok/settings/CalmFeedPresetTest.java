package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CalmFeedPresetTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final Map<Setting<?>, Object> original = new LinkedHashMap<>();
    private Context context;

    @Before public void rememberSettings() {
        context = RuntimeEnvironment.getApplication();
        for (Setting<?> setting : allTouchedSettings()) original.put(setting, setting.get());
        CalmFeedPreset.clearForTests(context);
    }

    @After public void restoreSettings() throws Exception {
        Setting.saveAll(original);
        CalmFeedPreset.clearForTests(context);
    }

    @Test public void applyAndRestoreRoundTripTheExactPreviousSetup() throws Exception {
        Settings.REMOVE_ADS.save(false);
        Settings.HIDE_SHOP.save(false);
        Settings.HIDE_LIVE.save(false);
        Settings.HIDE_LIVE_REPLAYS.save(true);
        Settings.HIDE_PROMOTIONAL_MUSIC.save(false);
        Settings.HIDE_PAID_PARTNERSHIP.save(true);

        CalmFeedPreset.Result applied = CalmFeedPreset.apply(context);
        assertEquals(4, applied.changedCount);
        assertEquals(3, applied.restartChangedCount);
        assertEquals(CalmFeedPreset.State.ACTIVE, CalmFeedPreset.state(context));
        assertTrue(Settings.REMOVE_ADS.get());
        assertTrue(Settings.HIDE_SHOP.get());
        assertTrue(Settings.HIDE_LIVE.get());
        assertTrue(Settings.HIDE_LIVE_REPLAYS.get());
        assertTrue(Settings.HIDE_PROMOTIONAL_MUSIC.get());
        assertTrue(Settings.HIDE_PAID_PARTNERSHIP.get());

        // A later manual adjustment must not overwrite the saved route back.
        Settings.HIDE_SHOP.save(false);
        assertEquals(CalmFeedPreset.State.ADJUSTED, CalmFeedPreset.state(context));
        CalmFeedPreset.restore(context);

        assertFalse(Settings.REMOVE_ADS.get());
        assertFalse(Settings.HIDE_SHOP.get());
        assertFalse(Settings.HIDE_LIVE.get());
        assertTrue(Settings.HIDE_LIVE_REPLAYS.get());
        assertFalse(Settings.HIDE_PROMOTIONAL_MUSIC.get());
        assertTrue(Settings.HIDE_PAID_PARTNERSHIP.get());
        assertFalse(CalmFeedPreset.hasSnapshot(context));
        assertEquals(CalmFeedPreset.State.AVAILABLE, CalmFeedPreset.state(context));
    }

    @Test public void presetLeavesOrdinaryContentChoicesAlone() throws Exception {
        Settings.HIDE_STORY.save(true);
        Settings.HIDE_IMAGE.save(false);
        Settings.HIDE_AI_GENERATED.save(true);

        CalmFeedPreset.apply(context);

        assertTrue("a user's story filter was overwritten", Settings.HIDE_STORY.get());
        assertFalse("photo posts were hidden by a clutter preset", Settings.HIDE_IMAGE.get());
        assertTrue("a user's AI label choice was overwritten", Settings.HIDE_AI_GENERATED.get());
    }

    @Test public void resultSaysWhenNoRestartGatedValueChanged() throws Exception {
        Settings.REMOVE_ADS.save(true);
        Settings.HIDE_SHOP.save(true);
        Settings.HIDE_LIVE.save(true);
        Settings.HIDE_PAID_PARTNERSHIP.save(true);
        Settings.HIDE_LIVE_REPLAYS.save(false);
        Settings.HIDE_PROMOTIONAL_MUSIC.save(false);

        CalmFeedPreset.Result result = CalmFeedPreset.apply(context);

        assertEquals(2, result.changedCount);
        assertEquals(0, result.restartChangedCount);
    }

    private static Setting<?>[] allTouchedSettings() {
        return new Setting<?>[]{
                Settings.REMOVE_ADS,
                Settings.HIDE_SHOP,
                Settings.HIDE_LIVE,
                Settings.HIDE_LIVE_REPLAYS,
                Settings.HIDE_PROMOTIONAL_MUSIC,
                Settings.HIDE_PAID_PARTNERSHIP,
                Settings.HIDE_STORY,
                Settings.HIDE_IMAGE,
                Settings.HIDE_AI_GENERATED,
        };
    }
}
