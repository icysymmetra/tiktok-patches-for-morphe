package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.captions.CaptionStyle;
import app.morphe.extension.tiktok.feedfilter.FeedRuleLimits;
import app.morphe.extension.tiktok.interaction.TapConfirmation;
import app.morphe.extension.tiktok.offline.CustomOfflineVideosLimitPatch;
import app.morphe.extension.tiktok.seen.SeenVideoHistory;
import app.morphe.extension.tiktok.settings.preference.categories.DownloadsPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.FeedFilterPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.PlaybackPreferenceCategory;
import app.morphe.extension.tiktok.speed.PlaybackSpeedPatch;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * A limit the reader is told about is formatted in from the constant that enforces it.
 *
 * <p>"Four seconds" was typed into three rows, "10,000" into two, "8 speeds from 0.5 to 3"
 * into two, and so on for 256 KB, 4,096, 64 KB, 1 to 1000 and 12 to 48. A changed limit left
 * five languages describing the old one and changed the English key anyway. Each expected
 * text here is built from the constant, so the row follows the code.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LimitTextFollowsConstantsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @Test public void theRowsSayTheLimitsTheCodeEnforces() throws Exception {
        boolean[] before = snapshot();
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            setEveryStatus(true);
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, screen);
            new DownloadsPreferenceCategory(activity, screen);
            new FeedFilterPreferenceCategory(activity, screen);
            new PlaybackPreferenceCategory(activity, screen);

            String window = TapConfirmation.CONFIRM_WINDOW_SECONDS + " seconds";
            assertTrue(summary(screen, "Confirm before following").contains(window));
            assertTrue(summary(screen, "Confirm before liking").contains(window));
            assertTrue(summary(screen, "Caption text size")
                    .contains(CaptionStyle.MIN_TEXT_SIZE + " to " + CaptionStyle.MAX_TEXT_SIZE));
            assertTrue(summary(screen, "Offline videos limit").contains(
                    CustomOfflineVideosLimitPatch.MIN_LIMIT + " to " + CustomOfflineVideosLimitPatch.MAX_LIMIT));
            assertTrue(summary(screen, "Forget seen videos after")
                    .contains(NumberFormat.getInstance().format(SeenVideoHistory.MAX_RECORDS)));
            assertTrue(summary(screen, "Speed menu choices").contains("Up to "
                    + PlaybackSpeedPatch.MAX_MENU_SPEEDS + " speeds from "
                    + PlaybackSpeedPatch.speedLabel(PlaybackSpeedPatch.MIN_SPEED) + " to "
                    + PlaybackSpeedPatch.speedLabel(PlaybackSpeedPatch.MAX_SPEED)));
            // No placeholder survives into what the reader sees.
            for (String title : new String[]{"Confirm before following", "Confirm before liking",
                    "Caption text size", "Offline videos limit",
                    "Forget seen videos after", "Speed menu choices"}) {
                assertTrue(title + " still carries a placeholder: " + summary(screen, title),
                        !summary(screen, title).contains("%"));
            }
        } finally {
            restore(before);
        }
    }

    /** The Lab's two size refusals are checked beside the Lab, where their types are visible. */
    @Test public void theListRefusalsSayTheLimitsTheCodeEnforces() {
        Utils.setContext(org.robolectric.RuntimeEnvironment.getApplication());
        assertTrue(FeedRuleLimits.message(FeedRuleLimits.Violation.UTF8_BYTES).contains(
                NumberFormat.getInstance().format(FeedRuleLimits.MAX_UTF8_BYTES / 1024) + " KB"));
        assertTrue(FeedRuleLimits.message(FeedRuleLimits.Violation.ENTRIES).contains(
                NumberFormat.getInstance().format(FeedRuleLimits.MAX_ENTRIES) + " or fewer"));
    }

    @Test public void aSpeedIsWrittenTheWayTheRowWritesIt() {
        assertEquals("0.5", PlaybackSpeedPatch.speedLabel(0.5f));
        assertEquals("3", PlaybackSpeedPatch.speedLabel(3f));
        assertEquals("1.25", PlaybackSpeedPatch.speedLabel(1.25f));
        assertEquals("2", PlaybackSpeedPatch.speedLabel(2.0f));
    }

    private static String summary(PreferenceGroup group, String title) {
        Preference row = find(group, title);
        assertNotNull("no row titled " + title, row);
        return String.valueOf(row.getSummary());
    }

    private static Preference find(PreferenceGroup group, String title) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            if (preference.getTitle() != null && title.contentEquals(preference.getTitle())) return preference;
            if (preference instanceof PreferenceGroup) {
                Preference nested = find((PreferenceGroup) preference, title);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static List<Field> statusFields() {
        List<Field> fields = new ArrayList<>();
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                fields.add(field);
            }
        }
        return fields;
    }

    private static boolean[] snapshot() throws Exception {
        List<Field> fields = statusFields();
        boolean[] values = new boolean[fields.size()];
        for (int i = 0; i < fields.size(); i++) values[i] = fields.get(i).getBoolean(null);
        return values;
    }

    private static void setEveryStatus(boolean value) throws Exception {
        for (Field field : statusFields()) field.setBoolean(null, value);
    }

    private static void restore(boolean[] values) throws Exception {
        List<Field> fields = statusFields();
        for (int i = 0; i < fields.size(); i++) fields.get(i).setBoolean(null, values[i]);
    }
}
