package app.morphe.extension.tiktok.featuregatelab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.content.Context;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.misc.BackgroundPlay;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The Lab says what TikTok gets for background_play_enable while Hushfeed's switch decides it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BackgroundPlayGateTextTest {
    private static final String DECIDED = "2, from Keep playing in the background";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static FeatureGateCatalog.Entry entry(String key) {
        return new FeatureGateCatalog.Entry(key, key, "abmock", "INT", true, true,
                List.of("0"), List.of(), List.of(), "", "", true, "0", "INT");
    }

    @Test public void theSwitchIsWhatTikTokGets() {
        Context context = RuntimeEnvironment.getApplication();
        boolean was = SettingsStatus.backgroundPlayEnabled;
        try {
            SettingsStatus.backgroundPlayEnabled = true;
            Settings.BACKGROUND_PLAY.save(true);
            assertEquals(DECIDED, FeatureGateLabText.effectiveValue(context, entry(BackgroundPlay.GATE_KEY)));
            assertNotEquals(DECIDED, FeatureGateLabText.effectiveValue(context, entry("background_play_collection_enable")));

            Settings.BACKGROUND_PLAY.save(false);
            assertNotEquals(DECIDED, FeatureGateLabText.effectiveValue(context, entry(BackgroundPlay.GATE_KEY)));
        } finally {
            Settings.BACKGROUND_PLAY.save(false);
            SettingsStatus.backgroundPlayEnabled = was;
        }
    }
}
