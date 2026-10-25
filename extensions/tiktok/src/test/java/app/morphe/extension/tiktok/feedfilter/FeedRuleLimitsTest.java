package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import java.nio.charset.StandardCharsets;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class FeedRuleLimitsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void reset() {
        Settings.BLOCKED_CAPTION_WORDS.resetToDefault();
        Settings.BLOCKED_CREATORS.resetToDefault();
        Settings.LOCAL_HIDDEN_CREATORS.resetToDefault();
    }

    @Test public void exactByteAndEntryLimitsAreAcceptedAndOneMoreIsRefused() {
        String exactBytes = repeat("é", FeedRuleLimits.MAX_UTF8_BYTES / 2);
        assertEquals(FeedRuleLimits.MAX_UTF8_BYTES,
                exactBytes.getBytes(StandardCharsets.UTF_8).length);
        assertNull(FeedRuleLimits.captionProblem(exactBytes));
        assertEquals("That list is too large. Keep it to 256 KB or less.",
                FeedRuleLimits.captionProblem(exactBytes + "a"));

        String exactEntries = entries(FeedRuleLimits.MAX_ENTRIES);
        assertNull(FeedRuleLimits.captionProblem(exactEntries));
        assertNull(FeedRuleLimits.creatorProblem(exactEntries));
        String oneTooMany = exactEntries + ", item" + FeedRuleLimits.MAX_ENTRIES;
        assertEquals("That list has too many entries. Keep it to 10,000 or fewer.",
                FeedRuleLimits.captionProblem(oneTooMany));
        assertEquals("That list has too many entries. Keep it to 10,000 or fewer.",
                FeedRuleLimits.creatorProblem(oneTooMany));
        assertNull("empty separators were counted as creator entries",
                FeedRuleLimits.creatorProblem(repeat(",", FeedRuleLimits.MAX_ENTRIES + 1)));
    }

    @Test public void boundedSettingsRejectBeforeChangingMemoryOrPreferences() {
        Utils.setContext(org.robolectric.RuntimeEnvironment.getApplication());
        Settings.BLOCKED_CAPTION_WORDS.save("keep-caption");
        Settings.BLOCKED_CREATORS.save("keep-creator");
        String tooMany = entries(FeedRuleLimits.MAX_ENTRIES + 1);

        assertThrows(IllegalArgumentException.class,
                () -> Settings.BLOCKED_CAPTION_WORDS.save(tooMany));
        assertThrows(IllegalArgumentException.class,
                () -> Settings.BLOCKED_CREATORS.save(tooMany));
        assertEquals("keep-caption", Settings.BLOCKED_CAPTION_WORDS.get());
        assertEquals("keep-creator", Settings.BLOCKED_CREATORS.get());
        assertEquals("keep-caption", SettingValue.stored(Settings.BLOCKED_CAPTION_WORDS.key));
        assertEquals("keep-creator", SettingValue.stored(Settings.BLOCKED_CREATORS.key));
    }

    private static String entries(int count) {
        StringBuilder value = new StringBuilder(count * 8);
        for (int index = 0; index < count; index++) {
            if (index > 0) value.append(',');
            value.append("item").append(index);
        }
        return value.toString();
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }

    private static final class SettingValue {
        static String stored(String key) {
            return app.morphe.extension.shared.settings.Setting.preferences
                    .preferences.getString(key, null);
        }
    }
}
