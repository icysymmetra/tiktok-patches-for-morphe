package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.CommentsPreferenceCategory;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Lift text length limits: what each box's limit becomes, and the row. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LengthLimitsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @After public void tearDown() {
        Settings.LIFT_LENGTH_LIMITS.save(false);
        SettingsStatus.lengthLimitsEnabled = false;
    }

    @Test public void offKeepsTikTokLimits() {
        for (int limit : new int[]{0, 30, 150, 160, 2200}) assertEquals(limit, LengthLimits.limit(limit));
    }

    @Test public void onRaisesEveryLimitAndNeverLowersOne() {
        Settings.LIFT_LENGTH_LIMITS.save(true);
        assertEquals(LengthLimits.LIFTED, LengthLimits.limit(30));
        assertEquals(LengthLimits.LIFTED, LengthLimits.limit(160));
        assertEquals(LengthLimits.LIFTED, LengthLimits.limit(2200));
        assertEquals("a larger limit than ours stays", 20_000, LengthLimits.limit(20_000));
    }

    @Test public void theSwitchIsOnTheCommentsPageOnlyWhenPatched() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);

            SettingsStatus.lengthLimitsEnabled = false;
            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new CommentsPreferenceCategory(activity, without);
            assertNull(without.findPreference(Settings.LIFT_LENGTH_LIMITS.key));

            SettingsStatus.lengthLimitsEnabled = true;
            assertTrue(CommentsPreferenceCategory.isAvailable());
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new CommentsPreferenceCategory(activity, with);
            assertNotNull(with.findPreference(Settings.LIFT_LENGTH_LIMITS.key));
        }
    }
}
