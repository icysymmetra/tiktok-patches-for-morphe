package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.InboxPreferenceCategory;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An Inbox row's title already names the row it hides, so a summary that opens by saying the
 * title again ("Hide archive" over "Hide the Archive row.") tells the reader nothing. Nine rows
 * read that way. Each summary now says a consequence the title does not.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class InboxRowSummariesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @Test public void noInboxSummaryOpensBySayingItsTitleAgain() {
        boolean[] before = {SettingsStatus.inboxFilterEnabled,
                SettingsStatus.hideSuggestedAccountsEnabled, SettingsStatus.hideInboxStoriesEnabled,
                SettingsStatus.expandActivityListEnabled, SettingsStatus.notificationControlsEnabled};
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            // Every Inbox patch installed, so every row is on the page.
            SettingsStatus.inboxFilterEnabled = true;
            SettingsStatus.hideSuggestedAccountsEnabled = true;
            SettingsStatus.hideInboxStoriesEnabled = true;
            SettingsStatus.expandActivityListEnabled = true;
            SettingsStatus.notificationControlsEnabled = true;
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InboxPreferenceCategory(activity, screen);

            List<String> restating = new ArrayList<>();
            int rows = 0;
            for (Preference row : rows(screen)) {
                if (row.getTitle() == null || row.getSummary() == null) continue;
                rows++;
                if (opensWithItsTitle(row.getTitle(), row.getSummary())) {
                    restating.add(row.getTitle() + " / " + row.getSummary());
                }
            }
            // The page builds sixteen rows with every flag on. A floor at that number means a
            // row that stops being built is noticed here rather than slipping past the rule.
            assertTrue("too few Inbox rows to mean anything: " + rows, rows >= 16);
            assertEquals("Inbox summaries that open by saying the title again:\n"
                    + String.join("\n", restating), 0, restating.size());
        } finally {
            SettingsStatus.inboxFilterEnabled = before[0];
            SettingsStatus.hideSuggestedAccountsEnabled = before[1];
            SettingsStatus.hideInboxStoriesEnabled = before[2];
            SettingsStatus.expandActivityListEnabled = before[3];
            SettingsStatus.notificationControlsEnabled = before[4];
        }
    }

    /** The rule catches the wording that was there, and lets a consequence through. */
    @Test public void theRuleCanActuallyFail() {
        assertTrue(opensWithItsTitle("Hide archive", "Hide the Archive row."));
        assertTrue(opensWithItsTitle("Hide the add people icon",
                "Hide the add people icon in the Inbox header."));
        assertTrue(opensWithItsTitle("Hide activity status",
                "Hide the activity status control in the Inbox header."));
        assertFalse(opensWithItsTitle("Hide archive",
                "The Archive row leaves the Inbox. Archived chats are kept."));
        assertFalse(opensWithItsTitle("Hide stories tray",
                "The row of story avatars across the top of the Inbox goes."));
        // The rule reads words, not meaning: a summary that says the title over again in other
        // words gets through it. That is why the page's summaries each name a consequence, and
        // why this one was reworded rather than blessed here.
        assertFalse(opensWithItsTitle("Hide stories tray",
                "Hide the row of story avatars across the top of the Inbox."));
    }

    /**
     * Whether the summary's first words are the title's words. Articles and punctuation are
     * ignored, so "Hide the Archive row." still counts as opening with "Hide archive".
     */
    static boolean opensWithItsTitle(CharSequence title, CharSequence summary) {
        List<String> titleWords = words(title);
        List<String> summaryWords = words(summary);
        return !titleWords.isEmpty() && summaryWords.size() >= titleWords.size()
                && summaryWords.subList(0, titleWords.size()).equals(titleWords);
    }

    private static List<String> words(CharSequence text) {
        List<String> words = new ArrayList<>();
        for (String word : text.toString().toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (word.isEmpty() || word.equals("the") || word.equals("a") || word.equals("an")) continue;
            words.add(word);
        }
        return words;
    }

    private static List<Preference> rows(PreferenceGroup group) {
        List<Preference> rows = new ArrayList<>();
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            if (preference instanceof PreferenceGroup) {
                rows.addAll(rows((PreferenceGroup) preference));
            } else {
                rows.add(preference);
            }
        }
        return rows;
    }
}
