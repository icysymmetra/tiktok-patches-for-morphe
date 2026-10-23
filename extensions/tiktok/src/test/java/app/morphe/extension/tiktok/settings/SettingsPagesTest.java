package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.*;
import app.morphe.extension.tiktok.SettingsContextRule;
import android.app.Activity;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.UiCapture;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w480dp-h960dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SuppressWarnings("deprecation")
public class SettingsPagesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private static final String[] SECTIONS = {"FEED_FILTER", "FEED_NAVIGATION", "INTERFACE",
            "COMMENTS", "DOWNLOADS", "REGION", "SHARE", "BEHAVIOR", "DIAGNOSTICS", "BACKUP"};
    private static final String[] TITLES = {"Feed controls", "Feed navigation", "Interface",
            "Comments and translation", "Downloads", "Region spoof", "Share sheet", "App behavior",
            "Diagnostics", "Backup and restore"};
    /**
     * The home page under search and Quick setups: each heading and the rows under it, with the
     * two rows that leave settings, the report and Ko-fi, last.
     */
    private static final String[] HOME_LAYOUT = {
            "Feed controls", "Feed navigation", "Interface", "Comments and translation", "Downloads",
            "Region spoof", "Share sheet", "App behavior",
            "# Metra", "[status card]", "[Pause Metra]", "Diagnostics", "Backup and restore", "About Metra",
            "Report a problem", "Support Metra"};
    private final Map<Field, Boolean> statuses = new LinkedHashMap<>();

    public static class PageActivity extends Activity {
        @Override public void onCreate(Bundle state) {
            boolean dark = (getResources().getConfiguration().uiMode & 0x30) == 0x20;
            setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
            super.onCreate(state);
        }
    }
    @Before public void installControls() throws Exception {
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                statuses.put(field, field.getBoolean(null));
                field.setBoolean(null, true);
            }
        }
    }
    @After public void restoreControls() throws Exception {
        for (var entry : statuses.entrySet()) entry.getKey().setBoolean(null, entry.getValue());
    }
    @Test public void aBundleWithOnlyTheSettingsPatchOffersNoEmptyPages() throws Exception {
        // The @Before turns every status flag on, which is the opposite of the case that
        // matters here: a user who selected Settings and nothing else must not be given rows
        // that lead nowhere.
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                field.setBoolean(null, false);
            }
        }
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment home = new TikTokPreferenceFragment();
            activity.getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, home).commit();
            activity.getFragmentManager().executePendingTransactions();

            java.util.Set<String> routes = reachableMenuTitles(home);
            for (String title : new String[]{"Feed filter", "Feed tabs", "Feed screen",
                    "Playback", "Privacy", "Comments", "Inbox", "Downloads", "Share sheet",
                    "Screen time", "Region", "App"}) {
                assertFalse(title + " has nothing in it without its patches", routes.contains(title));
            }
            assertTrue(routes.contains("About Metra"));
            assertTrue(routes.contains("Backup and restore"));
            // A heading with every page under it left out of the bundle isn't shown either.
            var screen = home.getPreferenceScreen();
            for (int index = 0; index < screen.getPreferenceCount(); index++) {
                Preference row = screen.getPreference(index);
                if (!(row instanceof app.morphe.extension.tiktok.settings.preference.SectionHeadingPreference)) continue;
                String heading = String.valueOf(row.getTitle());
                assertFalse(heading + " heads nothing without its patches", heading.equals("Your feed")
                        || heading.equals("Conversations") || heading.equals("Saving and sharing"));
            }
        }
    }

    @Test public void everyPageWithSomethingOnItHasARowIntoIt() throws Exception {
        // The page and the row into it used to keep separate copies of the same condition, and
        // two of them drifted: the Playback page grew the daily budget behind the block author
        // patch while its row stayed on the four playback patches, so a bundle with only that
        // patch could reach the budget through search and nowhere else.
        Class<?>[] pages = {
            app.morphe.extension.tiktok.settings.preference.categories.FeedFilterPreferenceCategory.class,
            app.morphe.extension.tiktok.settings.preference.categories.FeedNavigationPreferenceCategory.class,
            app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory.class,
            app.morphe.extension.tiktok.settings.preference.categories.CommentsPreferenceCategory.class,
            app.morphe.extension.tiktok.settings.preference.categories.DownloadsPreferenceCategory.class,
            app.morphe.extension.tiktok.settings.preference.categories.PlaybackPreferenceCategory.class,
            app.morphe.extension.tiktok.settings.preference.categories.ScreenTimePreferenceCategory.class,
            app.morphe.extension.tiktok.settings.preference.categories.InboxPreferenceCategory.class,
            app.morphe.extension.tiktok.settings.preference.categories.SharePreferenceCategory.class,
            app.morphe.extension.tiktok.settings.preference.categories.SimSpoofPreferenceCategory.class,
            app.morphe.extension.tiktok.settings.preference.categories.PrivacyPreferenceCategory.class,
            // Left out until 2026-09-14, and it was the one that had drifted: its row asked a
            // copy of the question kept in the fragment, and the copy was missing the launcher
            // shortcuts flag. A page this sweep does not walk is a page the drift can hide in.
            app.morphe.extension.tiktok.settings.preference.categories.ExtensionPreferenceCategory.class,
            app.morphe.extension.tiktok.settings.preference.categories.DebugPreferenceCategory.class,
        };
        String[] titles = {"Feed controls", "Feed navigation", "Interface", "Comments and translation",
            "Downloads", "App behavior", "App behavior", "Interface", "Share sheet", "Region spoof", "App behavior", "App behavior",
            "Diagnostics"};

        // What each page builds with nothing in the bundle at all. Those rows are unconditional
        // and show whenever something else opens the page, so they are the floor to compare
        // against rather than a finding.
        int[] baseline = new int[pages.length];
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                field.setBoolean(null, false);
            }
        }
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment home = new TikTokPreferenceFragment();
            activity.getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, home).commit();
            activity.getFragmentManager().executePendingTransactions();
            for (int page = 0; page < pages.length; page++) {
                baseline[page] = rowsBuiltBy(pages[page], activity, home);
            }
        }

        // One patch at a time, which is the shape that finds a drifted gate.
        for (Field flag : SettingsStatus.class.getDeclaredFields()) {
            if (flag.getType() != boolean.class || !Modifier.isStatic(flag.getModifiers())) continue;
            for (Field other : SettingsStatus.class.getDeclaredFields()) {
                if (other.getType() == boolean.class && Modifier.isStatic(other.getModifiers())) {
                    other.setAccessible(true);
                    other.setBoolean(null, false);
                }
            }
            flag.setAccessible(true);
            flag.setBoolean(null, true);

            try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
                Activity activity = owner.get();
                Utils.setContext(activity);
                TikTokPreferenceFragment home = new TikTokPreferenceFragment();
                activity.getFragmentManager().beginTransaction()
                        .replace(android.R.id.content, home).commit();
                activity.getFragmentManager().executePendingTransactions();

                java.util.Set<String> rows = reachableMenuTitles(home);
                for (int page = 0; page < pages.length; page++) {
                    boolean available = (Boolean) pages[page].getMethod("isAvailable").invoke(null);
                    if (available) {
                        assertTrue(titles[page] + " has rows to show with only " + flag.getName()
                                + " set, but the menu hierarchy offers no way in",
                                rows.contains(titles[page]));
                        continue;
                    }

                    // The other direction, which is where the drift actually hides. Asking only
                    // "available means a row" is a page answering its own question. What matters
                    // is whether this flag makes the page build anything it would not have built
                    // without it, because a switch behind a flag the page does not know about
                    // appears nowhere at all: no row, no page, and not in search either, since
                    // search builds the same category. Rows the page builds whatever is in the
                    // bundle are not this: they only ever show when something else opens it.
                    assertEquals(titles[page] + " builds settings behind " + flag.getName()
                            + " but does not count it as making the page available, so they are"
                            + " unreachable", baseline[page], rowsBuiltBy(pages[page], activity, home));
                }
            }
        }
    }

    /** Hide CAPTCHA popups is a switch again, for its own setting, with the old notice row gone. */
    @Test public void theCaptchaRowSwitchesItsOwnSetting() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment page = attachSection(activity, "INTERFACE");
            assertNull(page.findPreference("hushfeed_captcha_unavailable"));
            Preference row = page.findPreference(Settings.HIDE_CAPTCHA_POPUPS.key);
            assertNotNull(row);
            assertTrue(row instanceof android.preference.SwitchPreference);
            assertTrue(row.isEnabled());
            assertSame(Settings.HIDE_CAPTCHA_POPUPS,
                    app.morphe.extension.shared.settings.Setting.getSettingFromPath(row.getKey()));
        } finally {
            Settings.HIDE_CAPTCHA_POPUPS.resetToDefault();
        }
    }

    @Test @Config(fontScale = 2f)
    public void independentNativeTextSizesRenderAndSpeakTheirDefaultsInDarkTheme() throws Exception {
        assertNativeTextSizeRows("dark");
    }

    @Test @Config(qualifiers = "w480dp-h960dp-notnight-mdpi", fontScale = 2f)
    public void independentNativeTextSizesRenderAndSpeakTheirDefaultsInLightTheme() throws Exception {
        assertNativeTextSizeRows("light");
    }

    private void assertNativeTextSizeRows(String theme) throws Exception {
        int previousDescription = Settings.FEED_DESCRIPTION_TEXT_SIZE.savedValue();
        int previousAuthor = Settings.FEED_AUTHOR_TEXT_SIZE.savedValue();
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            for (Field field : statuses.keySet()) field.setBoolean(null, false);
            Settings.FEED_DESCRIPTION_TEXT_SIZE.save(0);
            Settings.FEED_AUTHOR_TEXT_SIZE.save(0);
            TikTokPreferenceFragment absent = attachSection(activity, "INTERFACE");
            assertNull(absent.findPreference(Settings.FEED_DESCRIPTION_TEXT_SIZE.key));
            assertNull(absent.findPreference(Settings.FEED_AUTHOR_TEXT_SIZE.key));
            SettingsStatus.feedTextSizeEnabled = true;
            TikTokPreferenceFragment page = attachSection(activity, "INTERFACE");
            String[] keys = {Settings.FEED_DESCRIPTION_TEXT_SIZE.key, Settings.FEED_AUTHOR_TEXT_SIZE.key};
            String[] titles = {"Description text size", "Author text size"};
            String[] images = {"description", "author"};
            ListView list = page.getView().findViewById(android.R.id.list);
            for (int i = 0; i < keys.length; i++) {
                Preference row = page.findPreference(keys[i]);
                assertNotNull(row);
                assertTrue(row instanceof app.morphe.extension.tiktok.settings.preference.NumberInputPreference);
                assertTrue(row.isEnabled());
                assertTrue(row.getSummary().toString().contains(L10n.t(activity, "TikTok's size")));
                int position = positionOf(list, keys[i]);
                assertTrue(position >= 0);
                list.setSelectionFromTop(position, 30);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                View rendered = list.getChildAt(position - list.getFirstVisiblePosition());
                assertNotNull(rendered);
                android.widget.TextView title = rendered.findViewById(android.R.id.title);
                android.widget.TextView summary = rendered.findViewById(android.R.id.summary);
                assertNotNull(title);
                assertNotNull(summary);
                assertEquals(L10n.t(activity, titles[i]),
                        String.valueOf(title.createAccessibilityNodeInfo().getText()));
                assertTrue(String.valueOf(summary.createAccessibilityNodeInfo().getText())
                        .contains(L10n.t(activity, "TikTok's size")));
                UiCapture.save(page.getView(), "pages/" + theme + "/feed-text-size-" + images[i] + ".png");
            }
            assertNull(page.findPreference(Settings.CAPTION_TEXT_SIZE.key));
        } finally {
            Settings.FEED_DESCRIPTION_TEXT_SIZE.save(previousDescription);
            Settings.FEED_AUTHOR_TEXT_SIZE.save(previousAuthor);
        }
    }

    @Test public void noPageGatesItsRowsOnAnEarlyReturn() throws Exception {
        // The other half of the same bug, and one a rendering test cannot see. An early return on
        // one patch's flag takes every later patch's rows with it: the offline videos limit set
        // its own flag, gated its own rows on it, and still put nothing on the page because an
        // "if (!downloadEnabled) return;" sat above them. Blocks, not returns.
        java.util.List<String> offenders = new java.util.ArrayList<>();
        int scanned = 0;
        for (java.io.File file : categorySources()) {
            String source = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            scanned++;
            for (String found : earlyReturns(source)) {
                offenders.add(file.getName() + ": " + found);
            }
        }
        assertTrue("no categories were read at all", scanned > 5);
        assertEquals("a flag returning early from a settings page hides every flag below it: "
                + offenders, 0, offenders.size());
    }

    @Test public void theEarlyReturnScanCanActuallyFail() {
        // A scan with nothing to find proves nothing, and the first version of this one was
        // defeated by a brace, a line break and a trailing comment. Every shape it has to catch
        // is put in front of it here, along with the ones it must leave alone.
        String source = "class Sample {\n"
                + "    void addPreferences(Context context) {\n"
                + "        char quote = '\"';\n"
                + "        if (!SettingsStatus.bare) return;\n"
                + "        if (!SettingsStatus.braced) { return; }\n"
                + "        if (!SettingsStatus.wrapped)\n            return;\n"
                + "        if (!SettingsStatus.commented) return; // and a note\n"
                + "        if (!isAvailable()) return;\n"
                + "        if (!Settings.SOME_TOGGLE.get()) return;\n"
                + "        if (context == null) return;\n"
                + "        // if (!SettingsStatus.inAComment) return;\n"
                + "        String text = \"if (!SettingsStatus.inAString) return;\";\n"
                + "        if (SettingsStatus.positive) { addPreference(null); }\n"
                + "    }\n"
                + "}\n";

        java.util.List<String> found = earlyReturns(source);
        assertEquals("the scan missed one of the shapes it exists for: " + found, 6, found.size());
        for (String shape : new String[]{"bare", "braced", "wrapped", "commented",
                "isAvailable()", "SOME_TOGGLE"}) {
            assertTrue(shape + " walked past the scan: " + found,
                    found.toString().contains(shape));
        }
        assertFalse("an ordinary null guard was reported: " + found,
                found.toString().contains("context"));
        assertFalse("a commented-out line was reported: " + found,
                found.toString().contains("inAComment"));
        assertFalse("a string literal was reported: " + found,
                found.toString().contains("inAString"));
    }

    /** Every preference category source, wherever a page is built from. */
    private static java.io.File[] categorySources() {
        java.io.File directory = new java.io.File(
                "src/main/java/app/morphe/extension/tiktok/settings/preference/categories");
        if (!directory.isDirectory()) directory = new java.io.File(
                "extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/preference/categories");
        assertTrue("could not find " + directory.getAbsolutePath(), directory.isDirectory());
        java.io.File[] files = directory.listFiles(
                (dir, name) -> name.endsWith(".java"));
        return java.util.Objects.requireNonNull(files);
    }

    /**
     * Guards that end a page's build early on something a patch decides. Comments and both kinds
     * of literal go first and then all whitespace, so the shape is caught however it is written:
     * on one line or two, braced or bare, with a note after it.
     */
    private static java.util.List<String> earlyReturns(String source) {
        java.util.List<String> found = new java.util.ArrayList<>();
        String packed = withoutCommentsOrLiterals(source).replaceAll("\\s+", "");
        int at = 0;
        while ((at = packed.indexOf("if(", at)) >= 0) {
            int close = matchingBracket(packed, at + 2);
            if (close < 0) break;
            String condition = packed.substring(at + 3, close);
            String rest = packed.substring(close + 1);
            boolean returnsNow = rest.startsWith("return;") || rest.startsWith("{return;}");
            boolean aboutAPatch = condition.startsWith("!")
                    && (condition.contains("SettingsStatus.") || condition.contains("isAvailable()")
                            || condition.contains("Settings."));
            if (returnsNow && aboutAPatch) found.add("if (" + condition + ") return;");
            at = close + 1;
        }
        return found;
    }

    /** Index of the ')' matching the '(' at {@code open}, or -1. */
    private static int matchingBracket(String text, int open) {
        int depth = 0;
        for (int at = open; at < text.length(); at++) {
            char c = text.charAt(at);
            if (c == '(') depth++;
            if (c == ')' && --depth == 0) return at;
        }
        return -1;
    }

    /** Source with comments and both kinds of literal removed, so their text is not read as code. */
    private static String withoutCommentsOrLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        for (int at = 0; at < source.length(); ) {
            char c = source.charAt(at);
            if (source.startsWith("//", at)) {
                while (at < source.length() && source.charAt(at) != '\n') at++;
            } else if (source.startsWith("/*", at)) {
                int end = source.indexOf("*/", at + 2);
                at = end < 0 ? source.length() : end + 2;
            } else if (c == '"' || c == '\'') {
                // A char literal holding a quote used to leave the scanner reading the rest of
                // the file as one string, which swallowed any real finding after it.
                char quote = c;
                at++;
                while (at < source.length() && source.charAt(at) != quote) {
                    if (source.charAt(at) == '\\') at++;
                    at++;
                }
                at++;
            } else {
                out.append(c);
                at++;
            }
        }
        return out.toString();
    }

    /** How many rows a page's own addPreferences puts on it under the flags set right now. */
    private static int rowsBuiltBy(Class<?> page, Activity activity, TikTokPreferenceFragment home)
            throws Exception {
        android.preference.PreferenceScreen scratch =
                home.getPreferenceManager().createPreferenceScreen(activity);
        var built = (app.morphe.extension.tiktok.settings.preference.categories
                .ConditionalPreferenceCategory) page.getConstructor(android.content.Context.class,
                        android.preference.PreferenceScreen.class).newInstance(activity, scratch);
        if (!built.getSettingsStatus()) {
            // The gate kept it off the screen, so it has no preference manager yet and nothing
            // could be added to it. Attaching it is what makes addPreferences work.
            scratch.addPreference(built);
            built.addPreferences(activity);
        }
        return built.getPreferenceCount();
    }

    /**
     * Every page is a row on the home page itself, under the heading it belongs with, and
     * Hushfeed's own rows come last with the Ko-fi row at the very end. No row opens another
     * menu: the Hush standard allows one level below the home page (Matt, 2026-10-12), and the
     * four groups that sat between it and Feed filter, Comments, Downloads or Region were a
     * second one.
     */
    @Test public void theHomePageListsEveryPageUnderItsHeading() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment home = new TikTokPreferenceFragment();
            activity.getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, home).commit();
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            android.preference.PreferenceScreen screen = home.getPreferenceScreen();
            assertNull("duplicate quick routes returned to the home menu", screen.findPreference("hushfeed_quick_routes"));
            java.util.List<String> layout = new java.util.ArrayList<>();
            for (int index = 0; index < screen.getPreferenceCount(); index++) {
                Preference row = screen.getPreference(index);
                if (row instanceof app.morphe.extension.tiktok.settings.preference.SectionHeadingPreference) {
                    layout.add("# " + row.getTitle());
                } else if (row instanceof app.morphe.extension.tiktok.settings.preference.SettingsMenuPreference) {
                    layout.add(String.valueOf(row.getTitle()));
                } else if (row instanceof app.morphe.extension.tiktok.settings.preference.SettingsStatusPreference) {
                    layout.add("[status card]");
                } else if (app.morphe.extension.shared.settings.BaseSettings.PAUSED.key.equals(row.getKey())) {
                    layout.add("[Pause Metra]");
                }
            }
            assertEquals(java.util.Arrays.asList(HOME_LAYOUT), layout);
            for (var row : menuRows(home)) {
                assertTrue(row.getTitle() + " opens a menu of more menus", row.getKey().startsWith("section_")
                        || "hub_about".equals(row.getKey()) || row.getKey().startsWith("action_"));
            }
            Preference search = screen.findPreference("action_search_settings");
            assertNotNull("the home menu has no search field", search);
            assertEquals("Search settings", String.valueOf(search.getTitle()));
            assertTrue("Search is not above the first destination",
                    preferenceIndex(screen, search) < preferenceIndex(screen, menuRows(home).get(0)));
            TextView heading = home.getView().findViewWithTag("hushfeed_page_title");
            assertEquals("Settings", heading.getText().toString());
            TextView subtitle = home.getView().findViewWithTag("hushfeed_home_subtitle");
            assertNotNull("the home introduction is missing", subtitle);
            assertEquals("MENUS", subtitle.getText().toString());

            // The status line in the header takes the reader down to the status card, which is
            // below the fold, without leaving the page.
            layout(home.getView(), 480, 960);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ListView list = home.getView().findViewById(android.R.id.list);
            int card = positionOf(list, "hushfeed_status");
            assertTrue("the status card shows without scrolling, so this proves nothing",
                    card > list.getLastVisiblePosition());
            View status = home.getView().findViewWithTag("hushfeed_compact_status");
            assertNotNull("the compact status is missing", status);
            assertAccessibleButton(activity, status, String.valueOf(status.getContentDescription()));
            assertTrue("the compact status has no spoken state", status.getContentDescription().length() > 0);
            assertTrue("the compact status ignored the press", status.performClick());
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            layout(home.getView(), 480, 960);
            assertSame("the compact status left the home page", home,
                    activity.getFragmentManager().findFragmentById(android.R.id.content));
            assertTrue("the compact status didn't bring the status card into view",
                    list.getFirstVisiblePosition() <= card && card <= list.getLastVisiblePosition());
        }
    }

    private static int preferenceIndex(
            android.preference.PreferenceScreen screen,
            Preference wanted
    ) {
        for (int index = 0; index < screen.getPreferenceCount(); index++) {
            if (screen.getPreference(index) == wanted) return index;
        }
        throw new AssertionError("preference is not on the screen: " + wanted);
    }

    private static void assertAccessibleButton(Activity activity, View button, String label) {
        assertNotNull(label + " is missing", button);
        int minimum = app.morphe.extension.tiktok.settings.preference.SettingsUi
                .dp(activity, 48);
        assertTrue(label + " is shorter than 48dp", button.getMinimumHeight() >= minimum);
        assertTrue(label + " is narrower than 48dp", button.getMinimumWidth() >= minimum);
        android.view.accessibility.AccessibilityNodeInfo node = button.createAccessibilityNodeInfo();
        assertEquals(label + " is not announced as a button",
                android.widget.Button.class.getName(), String.valueOf(node.getClassName()));
        assertTrue(label + " cannot be activated", node.isClickable());
        assertEquals(label, String.valueOf(button.getContentDescription()));
    }

    @Test
    @Config(qualifiers = "w480dp-h960dp-night-mdpi", fontScale = 2f)
    public void advancedStatusActionsStackInsteadOfClippingAtLargeText() {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Preference status = new app.morphe.extension.tiktok.settings.preference
                    .SettingsStatusPreference(activity, () -> {});
            View statusView = status.getView(null, null);
            android.widget.LinearLayout statusCard = statusView.findViewWithTag(
                    "hushfeed_status_card");
            assertEquals("large text kept Diagnostics beside the version sentence",
                    android.widget.LinearLayout.VERTICAL, statusCard.getOrientation());
            assertAccessibleButton(activity,
                    statusView.findViewWithTag("hushfeed_status_diagnostics"), "Diagnostics");
        }
    }

    @Test
    @Config(qualifiers = "w480dp-h960dp-night-mdpi", fontScale = 1.3f)
    public void theCompactHomeHeaderKeepsItsActionsReadableAtLargeText() {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment home = new TikTokPreferenceFragment();
            activity.getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, home).commit();
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            layout(home.getView(), 480, 960);
            TextView title = home.getView().findViewWithTag("hushfeed_page_title");
            assertTextFits(title);
            View status = home.getView().findViewWithTag("hushfeed_compact_status");
            assertAccessibleButton(activity, status, String.valueOf(status.getContentDescription()));
            assertRootRowsReadable(home.getView().findViewById(android.R.id.list));
        }
    }

    // The published pages are captured on Android 10, the first version every row is available on,
    // so none of them is shown greyed out. Keep a paused video paused needs it.
    @Test @Config(sdk = 29)
    public void darkPagesNavigateAndRender() throws Exception { capturePages("dark"); }
    @Test @Config(sdk = 29, qualifiers = "w480dp-h960dp-notnight-mdpi")
    public void lightPagesNavigateAndRender() throws Exception { capturePages("light"); }

    @Test @Config(sdk = 29, qualifiers = "w390dp-h844dp-night-mdpi")
    public void theQuietIndexFitsAStandardPhoneInDarkTheme() throws Exception {
        captureQuietIndex("dark");
    }

    @Test @Config(sdk = 29, qualifiers = "w390dp-h844dp-notnight-mdpi")
    public void theQuietIndexFitsAStandardPhoneInLightTheme() throws Exception {
        captureQuietIndex("light");
    }

    private void captureQuietIndex(String theme) throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment home = attachHome(activity);
            layout(home.getView(), 390, 844);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ListView list = home.getView().findViewById(android.R.id.list);
            // Metra's destinations open the page. Presets stay available below the main menus.
            int quickSetups = positionOf(list, "quick_setups");
            assertTrue("Quick setups is missing from the home page", quickSetups >= 0);
            assertTrue("Quick setups displaced Metra's feature menus",
                    quickSetups > positionOf(list, "section_behavior"));
            assertTrue("the first destination no longer shows without scrolling",
                    positionOf(list, menuRows(home).get(0).getKey()) <= list.getLastVisiblePosition());
            for (var row : menuRows(home)) {
                if (row.getKey().startsWith("section_")) {
                    assertFalse("Metra's feature routes changed their binding mode", row.isCompact());
                }
            }
            TextView heading = home.getView().findViewWithTag("hushfeed_page_title");
            assertTextFits(heading);
            assertRootRowsReadable(list);
            UiCapture.save(home.getView(), "pages/" + theme + "/settings-quiet-index.png", 390, 844);
        }
    }

    private void capturePages(String theme) throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Settings.DEFAULT_SPEED_ENABLED.get();
            for (var setting : app.morphe.extension.shared.settings.Setting.allLoadedSettings()) setting.resetToDefault();
            Settings.AUTO_ADVANCE.save(false);
            Settings.DEFAULT_SPEED_ENABLED.save(true);
            Settings.DEFAULT_SPEED.save("1.5");
            // The Diagnostics capture is meant to show what a hook report looks like. An empty
            // registry renders "nothing has been looked up yet", which is the one state that
            // says nothing about the feature, so the surfaces are seeded the way a few minutes
            // of use would leave them.
            HookStatus.clear();
            HookStatus.bound("overlay", "cover");
            HookStatus.bound("overlay", "caption");
            HookStatus.bound("comments", "like_button");
            HookStatus.missingViewId("comments", "jlk");
            TikTokPreferenceFragment home = new TikTokPreferenceFragment();
            activity.getFragmentManager().beginTransaction().replace(android.R.id.content, home).commit();
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            UiCapture.save(home.getView(), "pages/" + theme + "/settings.png");
            // The rest of the home page: the headings further down, the Hushfeed group with the
            // status card and Pause, and the report and Ko-fi rows at the end.
            ListView homeList = home.getView().findViewById(android.R.id.list);
            homeList.setSelection(homeList.getCount() - 1);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            UiCapture.save(home.getView(), "pages/" + theme + "/settings-end.png");
            homeList.setSelection(0);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            TikTokPreferenceFragment about = openMenu(activity, home, "hub_about");
            assertEquals("ABOUT", about.getArguments().getString("morphe_settings_hub"));
            UiCapture.save(about.getView(), "pages/" + theme + "/hub-about.png");
            assertTrue(activity.getFragmentManager().popBackStackImmediate());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertSame(home, activity.getFragmentManager().findFragmentById(android.R.id.content));
            for (int i = 0; i < SECTIONS.length; i++) {
                Preference menu = home.findPreference("section_" + SECTIONS[i].toLowerCase(java.util.Locale.ROOT));
                assertNotNull("Missing menu: " + SECTIONS[i], menu);
                assertEquals(TITLES[i], String.valueOf(menu.getTitle()));
                TikTokPreferenceFragment page = openMenu(activity, home, menu.getKey());
                assertEquals(SECTIONS[i], page.getArguments().getString("morphe_settings_section"));
                assertTrue(page.getPreferenceScreen().getPreferenceCount() > 2);
                String name = "pages/" + theme + "/" + SECTIONS[i].toLowerCase(java.util.Locale.ROOT);
                UiCapture.save(page.getView(), name + ".png");
                ListView list = page.getView().findViewById(android.R.id.list);
                if ("BEHAVIOR".equals(SECTIONS[i])) {
                    int root = positionOf(list, Settings.HIDE_ROOT.key);
                    assertTrue("the root privacy control is missing", root > 0);
                    list.setSelectionFromTop(Math.max(0, root - 2), 70);
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    UiCapture.save(page.getView(), name + "-device.png");
                }
                if ("SHARE".equals(SECTIONS[i])) {
                    for (int row = 0; row < list.getCount(); row++) {
                        Object item = list.getAdapter().getItem(row);
                        if (item instanceof Preference) {
                            assertNotEquals("The removed friend-send confirmation returned to the tour",
                                    "Confirm before sending to a friend",
                                    String.valueOf(((Preference) item).getTitle()));
                        }
                    }
                }
                if ("BEHAVIOR".equals(SECTIONS[i])) {
                    // Player and privacy controls share Metra's App behavior page. Keep both
                    // scroll targets exercised after merging the upstream pages.
                    int player = positionOf(list, Settings.SHOW_SEEKBAR.key);
                    assertTrue("the Player controls are missing", player > 0);
                    list.setSelectionFromTop(player - 1, 70);
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    UiCapture.save(page.getView(), name + "-sound.png");
                }
                list.setSelection(list.getCount() - 1);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                UiCapture.save(page.getView(), name + "-end.png");
                activity.getFragmentManager().popBackStackImmediate();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertSame(home, activity.getFragmentManager().findFragmentById(android.R.id.content));
            }
        }
    }

    @Test public void aPressStaysInsideTheCardTheRowIsPartOf() throws Exception {
        // The ripple mask was an opaque rectangle while the row draws a rounded card, so a press
        // at the corner of a card's first or last row filled the transparent notch outside it.
        // The mask has to be the shape the row draws: rounded where the card is, square where
        // the row meets its neighbour.
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            float radius = app.morphe.extension.tiktok.settings.preference.SettingsUi
                    .dp(activity, app.morphe.extension.tiktok.settings.preference.SettingsUi.RADIUS_SETTINGS_CARD);

            for (boolean[] ends : new boolean[][]{{true, false}, {false, true},
                    {true, true}, {false, false}}) {
                var mask = (android.graphics.drawable.GradientDrawable)
                        app.morphe.extension.tiktok.settings.preference.SettingsUi
                                .groupRowMask(activity, ends[0], ends[1]);
                float[] radii = mask.getCornerRadii();
                assertNotNull("the mask went back to a plain rectangle", radii);
                float top = ends[0] ? radius : 0f;
                float bottom = ends[1] ? radius : 0f;
                assertEquals("the top of a first=" + ends[0] + " row", top, radii[0], 0.01f);
                assertEquals(top, radii[3], 0.01f);
                assertEquals("the bottom of a last=" + ends[1] + " row", bottom, radii[4], 0.01f);
                assertEquals(bottom, radii[7], 0.01f);
            }
        }
    }

    @Test
    @Config(sdk = 28, qualifiers = "xhdpi")
    public void theHandDrawnGlyphsMirrorAndAreDrawnInDp() throws Exception {
        // Two things, both invisible on the captured screenshots because those are density 1.
        // The containers mirror by margin and gravity, so an Arabic or Hebrew reader had a left
        // pointing back arrow at the right edge and chevrons pointing back into the text. And
        // Paint.setStrokeWidth takes canvas pixels, so on a dense screen the menu tile's lines
        // came out under a dp wide beside 40sp type.
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            float density = activity.getResources().getDisplayMetrics().density;
            assertTrue("the fixture is not a dense screen, so this proves nothing", density >= 2f);

            var back = new app.morphe.extension.tiktok.settings.preference
                    .SettingsHeaderPreference.BackDrawable(activity);
            var chevron = new app.morphe.extension.tiktok.settings.preference
                    .SettingsMenuPreference.ChevronDrawable(activity);

            assertTrue("the back arrow does not mirror", back.isAutoMirrored());
            assertTrue("the chevron does not mirror", chevron.isAutoMirrored());

            assertEquals("the back arrow is a hairline on a dense screen",
                    2.1f * density, strokeOf(back), 0.01f);
            assertEquals("the chevron is a hairline on a dense screen",
                    1.8f * density, strokeOf(chevron), 0.01f);
            assertTrue("the chevron is under 2dp wide", strokeOf(chevron) >= 2f);

            // And the glyph itself turns round, rather than only its container. Declaring
            // isAutoMirrored is a promise; this is the drawing that keeps it. Drawn both ways
            // and compared against its own mirror image, which is what turning round means and
            // needs no opinion about which end is the point.
            assertTrue("the back arrow draws the same picture in both directions",
                    isHorizontalMirror(back));
            assertTrue("the chevron draws the same picture in both directions",
                    isHorizontalMirror(chevron));
        }
    }

    @Test
    public void everyMenuIconIsItsOwnPicture() {
        // Feed screen and App wore the same sliders glyph for a release, which defeats the point
        // of an icon column. Every tile is drawn and compared with every other one.
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            java.util.Map<String, int[]> pictures = new java.util.LinkedHashMap<>();
            for (var icon : app.morphe.extension.tiktok.settings.preference.SettingsMenuPreference.Icon.values()) {
                var row = new app.morphe.extension.tiktok.settings.preference.SettingsMenuPreference(
                        activity, "row", "summary", icon, preference -> true);
                pictures.put(icon.name(), render(row.getIcon(), View.LAYOUT_DIRECTION_LTR, 48));
            }
            for (var first : pictures.entrySet()) {
                for (var second : pictures.entrySet()) {
                    if (first.getKey().compareTo(second.getKey()) >= 0) continue;
                    assertTrue(first.getKey() + " and " + second.getKey() + " draw the same picture",
                            !java.util.Arrays.equals(first.getValue(), second.getValue()));
                }
            }
        }
    }

    /** The stroke the drawable's own paint carries. */
    private static float strokeOf(android.graphics.drawable.Drawable drawable) throws Exception {
        var field = drawable.getClass().getDeclaredField("paint");
        field.setAccessible(true);
        return ((android.graphics.Paint) field.get(drawable)).getStrokeWidth();
    }

    /**
     * Whether the glyph drawn in a mirrored layout is the mirror image of the one drawn plainly.
     *
     * <p>That is what turning round means, and it holds whichever end is the point, so the test
     * does not have to have an opinion about the shape. Fails if either the negation in draw()
     * or the mirroring itself is taken out.
     */
    private static boolean isHorizontalMirror(android.graphics.drawable.Drawable glyph) {
        int size = 48;
        int[] ltr = render(glyph, View.LAYOUT_DIRECTION_LTR, size);
        int[] rtl = render(glyph, View.LAYOUT_DIRECTION_RTL, size);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int drawn = android.graphics.Color.alpha(ltr[y * size + x]);
                int mirrored = android.graphics.Color.alpha(rtl[y * size + (size - 1 - x)]);
                // Anti-aliasing along the diagonal costs a few levels. Measured on
                // this fixture: 2302 of the 2304 pixels match exactly and the worst
                // pair is 16 apart, so 24 is antialiasing and anything above it is
                // a different picture.
                if (Math.abs(drawn - mirrored) > 24) return false;
            }
        }
        // And the two are not simply the same picture, which they would be for a glyph that
        // never turned round and happens to be symmetric.
        for (int index = 0; index < ltr.length; index++) {
            if (ltr[index] != rtl[index]) return true;
        }
        return false;
    }

    private static int[] render(
            android.graphics.drawable.Drawable glyph, int direction, int size) {
        glyph.setBounds(0, 0, size, size);
        glyph.setLayoutDirection(direction);
        var bitmap = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.ARGB_8888);
        glyph.draw(new android.graphics.Canvas(bitmap));
        int[] pixels = new int[size * size];
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size);
        bitmap.recycle();
        return pixels;
    }

    @Test public void aLongPageTitleLeavesRoomForThePageAtAnyTextScale() throws Exception {
        // Display headings cap their rendered growth so that the page stays reachable at 2x.
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            var configuration = activity.getResources().getConfiguration();
            float original = configuration.fontScale;
            try {
                configuration.fontScale = 1.0f;
                assertEquals("the ordinary scale should match Metra's home heading", 32,
                        app.morphe.extension.tiktok.settings.preference.SettingsHeaderPreference
                                .headingSizeSp(activity));

                configuration.fontScale = 2.0f;
                int large = app.morphe.extension.tiktok.settings.preference
                        .SettingsHeaderPreference.headingSizeSp(activity);
                assertTrue("the title still fills the screen at 2x: " + large, large <= 22);
                assertTrue("the title shrank past readable: " + large, large >= 20);
                assertTrue("the rendered height grew by more than a third",
                        large * 2.0f <= 32 * 1.35f);
            } finally {
                configuration.fontScale = original;
            }
        }
    }

    @Test public void theSimSwitchOnlyPromisesTheRowsThatArePresent() throws Exception {
        // The operator rows are added only when the SIM spoof patch is in the bundle, and the
        // switch's summary promised "the selected country and operator values" either way.
        try (var owner = Robolectric.buildActivity(
                app.morphe.extension.tiktok.interaction.GestureActionsTest.TestActivity.class)
                .setup()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            boolean original = app.morphe.extension.tiktok.settings.SettingsStatus
                    .simSpoofEnabled;
            try {
                app.morphe.extension.tiktok.settings.SettingsStatus.simSpoofEnabled = false;
                assertTrue("a bundle without the patch still promised the carrier rows",
                        !summaryOfSimSwitch(activity).contains("carrier"));

                app.morphe.extension.tiktok.settings.SettingsStatus.simSpoofEnabled = true;
                assertTrue("a bundle with the patch stopped naming the carrier rows",
                        summaryOfSimSwitch(activity).contains("carrier"));
            } finally {
                app.morphe.extension.tiktok.settings.SettingsStatus.simSpoofEnabled = original;
            }
        }
    }

    private static String summaryOfSimSwitch(Activity activity) {
        var host = (android.preference.PreferenceActivity) activity;
        var screen = host.getPreferenceManager().createPreferenceScreen(activity);
        new app.morphe.extension.tiktok.settings.preference.categories
                .SimSpoofPreferenceCategory(activity, screen);
        var row = screen.findPreference(
                app.morphe.extension.tiktok.settings.Settings.SIM_SPOOF.key);
        assertNotNull("the SIM switch is not on the page", row);
        return String.valueOf(row.getSummary());
    }

    @Test public void everyDialogActionIsPressableAndReadsAsAButton() throws Exception {
        // The flat actions in the hand built dialogs are TextViews with a click listener, so
        // TalkBack read them as labels rather than as something to press, and the two pickers
        // gave them about 35dp of touch height against a 48dp guideline. Asserted at the one
        // point every one of them goes through.
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);

            for (boolean primary : new boolean[]{true, false}) {
                android.widget.TextView action = new android.widget.TextView(activity);
                action.setText("Save");
                app.morphe.extension.tiktok.settings.preference.SettingsUi
                        .styleTextAction(action, primary);

                int expected = app.morphe.extension.tiktok.settings.preference.SettingsUi
                        .dp(activity, 48);
                assertEquals("a dialog action under the touch guideline",
                        expected, action.getMinimumHeight());
                assertEquals(expected, action.getMinimumWidth());

                var info = android.view.accessibility.AccessibilityNodeInfo.obtain();
                action.onInitializeAccessibilityNodeInfo(info);
                assertEquals("a screen reader would read this as text",
                        android.widget.Button.class.getName(),
                        String.valueOf(info.getClassName()));

                // Weight has to follow rank from whatever face the caller built. The hand-built
                // actions are made bold and then styled, and setTypeface(tf, NORMAL) keeps the
                // face it is handed, so a secondary action stayed as heavy as a primary one and
                // went on reading as the action to take.
                android.widget.TextView built = app.morphe.extension.tiktok.settings.preference
                        .SettingsUi.text(activity, "Save", 14, 0xFFFFFFFF,
                                android.graphics.Typeface.BOLD);
                assertTrue("the fixture did not start from a bold face",
                        built.getTypeface().isBold());
                app.morphe.extension.tiktok.settings.preference.SettingsUi
                        .styleTextAction(built, primary);
                assertEquals("a " + (primary ? "primary" : "secondary")
                                + " action is not the weight its rank calls for",
                        primary, built.getTypeface().isBold());
                assertFalse("a secondary action was faked bold instead",
                        !primary && built.getPaint().isFakeBoldText());
            }
        }
    }

    @Test public void renderedControlsSaveAndOpenTheirNativeEditors() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Settings.AUTO_ADVANCE.save(false);
            // The speed picker is live only while its switch is on; it greys with it off.
            Settings.DEFAULT_SPEED_ENABLED.save(true);
            TikTokPreferenceFragment page = attachSection(activity, "PLAYBACK");
            UiCapture.save(page.getView(), "pages/dark/playback-controls.png");
            ListView list = page.getView().findViewById(android.R.id.list);
            int autoAdvancePosition = positionOf(list, "auto_advance");
            assertTrue(autoAdvancePosition >= 0);
            assertTrue(list.performItemClick(list.getChildAt(autoAdvancePosition), autoAdvancePosition,
                    list.getAdapter().getItemId(autoAdvancePosition)));
            assertTrue(Settings.AUTO_ADVANCE.get());
            int defaultSpeedPosition = positionOf(list, "default_speed");
            assertTrue(defaultSpeedPosition >= 0);
            list.performItemClick(list.getChildAt(defaultSpeedPosition), defaultSpeedPosition,
                    list.getAdapter().getItemId(defaultSpeedPosition));
            android.app.AlertDialog dialog = (android.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertTrue(dialog.isShowing());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            UiCapture.save(dialog.getWindow().getDecorView(), "pages/dark/speed-picker.png");
            ListView choices = dialog.getListView();
            int selected = -1;
            int unselected = -1;
            for (int position = 0; position < choices.getChildCount(); position++) {
                CheckedTextView row = findCheckedTextView(choices.getChildAt(position));
                if (row != null && row.isChecked()) selected = position;
                if (row != null && !row.isChecked() && unselected < 0) unselected = position;
            }
            assertTrue(selected >= 0);
            assertTrue(unselected >= 0);
            assertChoiceIndicator(choices, selected, true, true);
            assertChoiceIndicator(choices, unselected, false, true);
            choices.performItemClick(null, 0, choices.getAdapter().getItemId(0));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("0.5", Settings.DEFAULT_SPEED.get());
            assertFalse(dialog.isShowing());
        } finally {
            Settings.DEFAULT_SPEED_ENABLED.resetToDefault();
        }
    }

    @Test public void settingsSearchFindsTranslatedControlAndOpensOriginal() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment home = attachHome(activity);
            Preference search = findPreference(home.getPreferenceScreen(), "Search settings");
            assertNotNull(search);
            assertTrue(search.getOnPreferenceClickListener().onPreferenceClick(search));
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            TikTokPreferenceFragment page = (TikTokPreferenceFragment) activity.getFragmentManager()
                    .findFragmentById(android.R.id.content);
            EditText input = (EditText) page.getView().findViewWithTag("settings_search_input");
            assertNotNull(input);
            input.setText("video length");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            UiCapture.save(page.getView(), "pages/dark/search.png");
            java.util.List<android.widget.EditText> editors = new java.util.ArrayList<>();
            collectEditors(page.getView(), editors);
            assertEquals(1, editors.size());
            Preference result = findPreference(page.getPreferenceScreen(), "Maximum video length");
            assertNotNull(result);
            assertTrue(String.valueOf(result.getSummary()).startsWith("Feed filter"));

            assertTrue(result.getOnPreferenceClickListener().onPreferenceClick(result));
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            TikTokPreferenceFragment targetPage = (TikTokPreferenceFragment) activity.getFragmentManager()
                    .findFragmentById(android.R.id.content);
            assertEquals("FEED_FILTER", targetPage.getArguments().getString("morphe_settings_section"));
            ListView list = targetPage.getView().findViewById(android.R.id.list);
            int targetPosition = positionOf(list, "max_video_seconds");
            assertTrue(targetPosition >= 0);
            assertTrue(list.getFirstVisiblePosition() <= targetPosition);
            assertTrue(list.getLastVisiblePosition() >= targetPosition);
            int childIndex = targetPosition - list.getFirstVisiblePosition();
            if (childIndex >= 0 && childIndex < list.getChildCount()) {
                View row = list.getChildAt(childIndex);
                assertTrue("the target row sits flush at the top with no context above it",
                        row.getTop() > list.getPaddingTop());
            }
        }
    }

    /** A row greyed until its switch is on is found too; its page says which switch that is. */
    @Test public void settingsSearchFindsARowWaitingOnItsSwitch() throws Exception {
        Settings.AUTO_ADVANCE.save(false);
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment home = attachHome(activity);
            Preference search = findPreference(home.getPreferenceScreen(), "Search settings");
            assertTrue(search.getOnPreferenceClickListener().onPreferenceClick(search));
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            TikTokPreferenceFragment page = (TikTokPreferenceFragment) activity.getFragmentManager()
                    .findFragmentById(android.R.id.content);
            ((EditText) page.getView().findViewWithTag("settings_search_input")).setText("session limit");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull("a row greyed by its switch dropped out of search",
                    findPreference(page.getPreferenceScreen(), "Auto-advance session limit"));
        } finally {
            Settings.AUTO_ADVANCE.resetToDefault();
        }
    }

    /**
     * Pause keeps its stable setting key, and its search result opens the page the row is on,
     * the home page, aimed at the row, with Back going to the results like any other result.
     */
    @Test public void settingsSearchOpensPauseHushfeedOnTheMasterMenu() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment home = attachHome(activity);
            Preference search = findPreference(home.getPreferenceScreen(), "Search settings");
            assertTrue(search.getOnPreferenceClickListener().onPreferenceClick(search));
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            TikTokPreferenceFragment page = (TikTokPreferenceFragment) activity.getFragmentManager()
                    .findFragmentById(android.R.id.content);
            EditText input = (EditText) page.getView().findViewWithTag("settings_search_input");
            input.setText("pause");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Preference result = findPreference(page.getPreferenceScreen(), "Pause Metra");
            assertNotNull(result);

            assertTrue(result.getOnPreferenceClickListener().onPreferenceClick(result));
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            TikTokPreferenceFragment targetPage = (TikTokPreferenceFragment) activity.getFragmentManager()
                    .findFragmentById(android.R.id.content);
            assertNotSame("the result left the results page up", page, targetPage);
            assertNull(targetPage.getArguments().getString("morphe_settings_section"));
            assertNull(targetPage.getArguments().getString("morphe_settings_hub"));
            assertEquals(app.morphe.extension.shared.settings.BaseSettings.PAUSED.key,
                    targetPage.getArguments().getString("morphe_settings_target_key"));
            ListView list = targetPage.getView().findViewById(android.R.id.list);
            assertTrue(positionOf(list, app.morphe.extension.shared.settings.BaseSettings.PAUSED.key) >= 0);

            // The page's own Back arrow, not only the system Back: on this copy of the home page
            // it has to go to the results rather than close settings.
            list.setSelection(0);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            android.view.ViewGroup toolbar = targetPage.getView().findViewWithTag("hushfeed_toolbar");
            assertTrue(toolbar.getChildAt(0).performClick());
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse("the Back arrow closed settings", activity.isFinishing());
            assertTrue("Back from the row didn't go to the results", ((TikTokPreferenceFragment) activity
                    .getFragmentManager().findFragmentById(android.R.id.content)).getArguments()
                    .getBoolean("morphe_settings_search"));
        }
    }

    @Test @Config(qualifiers = "de-rDE-w480dp-h960dp-night-mdpi")
    public void settingsSearchUsesGermanTextAndClearState() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment home = attachHome(activity);
            Preference search = findPreference(home.getPreferenceScreen(), "Einstellungen durchsuchen");
            assertNotNull(search);
            assertTrue(search.getOnPreferenceClickListener().onPreferenceClick(search));
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            TikTokPreferenceFragment page = (TikTokPreferenceFragment) activity.getFragmentManager()
                    .findFragmentById(android.R.id.content);
            EditText input = (EditText) page.getView().findViewWithTag("settings_search_input");
            assertNotNull(input);
            input.setText("Videolänge");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Preference result = findPreference(page.getPreferenceScreen(), "Maximale Videolänge");
            assertNotNull(result);
            assertTrue(String.valueOf(result.getSummary()).startsWith("Feed-Filter"));

            input.setText("kein Treffer");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(findPreference(page.getPreferenceScreen(), "Keine passenden Einstellungen"));
            View clear = page.getView().findViewWithTag("settings_search_clear");
            assertNotNull(clear);
            assertTrue(clear.performClick());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("", input.getText().toString());
            assertNotNull(findPreference(page.getPreferenceScreen(), "Tippe zum Suchen"));
            View toolbar = page.getView().findViewWithTag("hushfeed_toolbar");
            assertNotNull(toolbar);
            assertTrue(((android.view.ViewGroup) toolbar).getChildAt(0).performClick());
            activity.getFragmentManager().executePendingTransactions();
            assertSame(home, activity.getFragmentManager().findFragmentById(android.R.id.content));
        }
    }

    @Test @Config(qualifiers = "in-rID-w480dp-h960dp-night-mdpi")
    public void settingsSearchUsesIndonesianTextAndCategoryContext() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment home = attachHome(activity);
            Preference search = findPreference(home.getPreferenceScreen(), "Cari setelan");
            assertNotNull(search);
            assertTrue(search.getOnPreferenceClickListener().onPreferenceClick(search));
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            TikTokPreferenceFragment page = (TikTokPreferenceFragment) activity.getFragmentManager()
                    .findFragmentById(android.R.id.content);
            EditText input = (EditText) page.getView().findViewWithTag("settings_search_input");
            assertNotNull(input);
            input.setText("Durasi video maksimum");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Preference result = findPreference(page.getPreferenceScreen(), "Durasi video maksimum");
            assertNotNull(result);
            assertTrue(String.valueOf(result.getSummary()).startsWith("Filter feed"));
        }
    }

    @Test public void settingsSearchExcludesStatusGatedControls() throws Exception {
        SettingsStatus.feedFilterEnabled = false;
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment home = attachHome(activity);
            Preference search = findPreference(home.getPreferenceScreen(), "Search settings");
            assertNotNull(search);
            assertTrue(search.getOnPreferenceClickListener().onPreferenceClick(search));
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            TikTokPreferenceFragment page = (TikTokPreferenceFragment) activity.getFragmentManager()
                    .findFragmentById(android.R.id.content);
            EditText input = (EditText) page.getView().findViewWithTag("settings_search_input");
            // Not just "video length": Show video length on profile grids matches that, and its patch is present here.
            input.setText("maximum video length");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(findPreference(page.getPreferenceScreen(), "Maximum video length"));
            assertNotNull(findPreference(page.getPreferenceScreen(), "No matching settings"));
        }
    }

    @Test public void calmFeedPresetComesBeforeIndividualFeedFilters() throws Exception {
        boolean shop = Settings.HIDE_SHOP.get();
        CalmFeedPreset.clearForTests(org.robolectric.RuntimeEnvironment.getApplication());
        Settings.HIDE_SHOP.save(false);
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment page = attachSection(activity, "FEED_FILTER");
            Preference preset = page.findPreference("calm_feed_preset");
            assertNotNull("the Feed filter page has no calm starting point", preset);
            assertEquals("Calm feed", preset.getTitle());
            assertTrue(String.valueOf(preset.getSummary()).contains("ads"));

            ListView list = page.getView().findViewById(android.R.id.list);
            int presetPosition = positionOf(list, "calm_feed_preset");
            int firstFilterPosition = positionOf(list, Settings.REMOVE_ADS.key);
            assertTrue("the preset is buried below the switches",
                    presetPosition >= 0 && presetPosition < firstFilterPosition);
        } finally {
            Settings.HIDE_SHOP.save(shop);
            CalmFeedPreset.clearForTests(org.robolectric.RuntimeEnvironment.getApplication());
        }
    }

    @Test public void adControlsStayTogetherBeforeOtherPostKinds() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment page = attachSection(activity, "FEED_FILTER");
            ListView list = page.getView().findViewById(android.R.id.list);

            int removeAds = positionOf(list, Settings.REMOVE_ADS.key);
            int partnerships = positionOf(list, Settings.HIDE_PAID_PARTNERSHIP.key);
            int promotionalMusic = positionOf(list, Settings.HIDE_PROMOTIONAL_MUSIC.key);
            int shop = positionOf(list, Settings.HIDE_SHOP.key);
            assertTrue(removeAds >= 0);
            assertEquals(removeAds + 1, partnerships);
            assertEquals(partnerships + 1, promotionalMusic);
            assertTrue("the ad controls were scattered among unrelated post types",
                    promotionalMusic < shop);
        }
    }

    @Test public void appProfileSectionOffersTheRewardsShortcutDeclutterControl() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment page = attachSection(activity, "BEHAVIOR");
            Preference rewards = page.findPreference(Settings.HIDE_PROFILE_REWARDS_SHORTCUT.key);
            assertNotNull(rewards);
            assertEquals("Hide the rewards shortcut", rewards.getTitle());
            assertTrue(String.valueOf(rewards.getSummary()).contains("Add friends"));
        }
    }

    @Test @Config(qualifiers = "de-rDE-w320dp-h800dp-night-mdpi", fontScale = 2f)
    public void calmFeedPresetStacksAndKeepsAnAccessibleActionAtLargeText() throws Exception {
        boolean shop = Settings.HIDE_SHOP.get();
        CalmFeedPreset.clearForTests(org.robolectric.RuntimeEnvironment.getApplication());
        Settings.HIDE_SHOP.save(false);
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Preference preset = new app.morphe.extension.tiktok.settings.preference
                    .CalmFeedPresetPreference(activity);
            app.morphe.extension.tiktok.settings.preference.SettingsUi.syncDarkMode(activity);
            View view = preset.getView(null, null);
            android.widget.LinearLayout heading = view.findViewWithTag(
                    "calm_feed_preset_heading");
            assertNotNull(heading);
            assertEquals("large text kept the status beside the title",
                    android.widget.LinearLayout.VERTICAL, heading.getOrientation());
            assertAccessibleButton(activity,
                    view.findViewWithTag("calm_feed_preset_action"),
                    "„Ruhiger Feed“ verwenden");
            view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST));
            view.layout(0, 0, 320, view.getMeasuredHeight());
            String theme = app.morphe.extension.tiktok.settings.preference.SettingsUi.isDarkMode()
                    ? "dark" : "light";
            UiCapture.save(view, "audit/calm-feed-large-text-" + theme + ".png", 320, view.getMeasuredHeight());
            TextView title = (TextView) heading.getChildAt(0);
            assertTrue("the stacked heading measured its title at zero width", title.getWidth() > 0);
            assertTrue("the title did not render any text", title.getLayout().getLineCount() > 0);
            assertEquals("the heading clipped its last line", title.getText().length(),
                    title.getLayout().getLineEnd(title.getLayout().getLineCount() - 1));
        } finally {
            Settings.HIDE_SHOP.save(shop);
            CalmFeedPreset.clearForTests(org.robolectric.RuntimeEnvironment.getApplication());
        }
    }

    @Test @Config(qualifiers = "de-rDE-w320dp-h800dp-notnight-mdpi", fontScale = 2f)
    public void theLightCalmFeedPresetKeepsItsLargeTextHeadingAndAction() throws Exception {
        calmFeedPresetStacksAndKeepsAnAccessibleActionAtLargeText();
    }

    @Test public void localCreatorEditorFiltersAndRemovesIndividualEntries() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Settings.LOCAL_HIDDEN_CREATORS.save("alpha, beta");
            TikTokPreferenceFragment page = attachSection(activity, "FEED_FILTER");
            Preference preference = page.findPreference("local_hidden_creators");
            assertNotNull(preference);
            ListView list = page.getView().findViewById(android.R.id.list);
            int position = positionOf(list, "local_hidden_creators");
            assertTrue(position >= 0);
            assertTrue(list.performItemClick(list.getChildAt(position), position,
                    list.getAdapter().getItemId(position)));
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            android.app.AlertDialog dialog = (android.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertTrue(dialog.isShowing());
            EditText search = dialog.getWindow().getDecorView().findViewWithTag("creator_list_search");
            assertNotNull(search);
            search.setText("beta");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            UiCapture.save(dialog.getWindow().getDecorView(), "pages/dark/creator-list.png");
            View remove = dialog.getWindow().getDecorView().findViewWithTag("creator_remove_beta");
            assertNotNull(remove);
            assertTrue(remove.performClick());
            EditText add = dialog.getWindow().getDecorView().findViewWithTag("creator_list_add");
            assertNotNull(add);
            add.setText("gamma");
            View addButton = dialog.getWindow().getDecorView().findViewWithTag("creator_list_add_button");
            assertNotNull(addButton);
            assertTrue(addButton.performClick());
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("alpha, gamma", Settings.LOCAL_HIDDEN_CREATORS.get());
            Settings.LOCAL_HIDDEN_CREATORS.save("");
        }
    }

    @Test @Config(qualifiers = "de-rDE-w360dp-h800dp-night-mdpi")
    public void longGermanLabelsWrapAtLargeTextSize() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            var configuration = activity.getResources().getConfiguration();
            configuration.fontScale = 1.3f;
            activity.getResources().updateConfiguration(configuration, activity.getResources().getDisplayMetrics());
            // Backup and restore: "Sichern und wiederherstellen" is the longest page title in
            // German now that Comments is one word, so it is the one that has to wrap cleanly.
            TikTokPreferenceFragment page = attachSection(activity, "BACKUP");
            layout(page.getView(), 360, 800);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            UiCapture.save(page.getView(), "pages/dark/backup-german-large.png", 360, 800);
            android.widget.TextView heading = page.getView().findViewWithTag("hushfeed_page_title");
            assertNotNull(heading);
            assertEquals("the page title is not in German, so this proves nothing",
                    "Sichern und wiederherstellen", heading.getText().toString());
            assertTrue("the compact header lost its title", heading.getLineCount() > 0);
            assertEquals(0, heading.getLayout().getEllipsisCount(heading.getLineCount() - 1));
            assertTrue(heading.getHeight() >= heading.getLayout().getHeight());
            String renderedTitle = heading.getText().toString();
            for (int line = 0; line < heading.getLineCount() - 1; line++) {
                int end = heading.getLayout().getLineEnd(line);
                boolean atWordBoundary = end > 0 && Character.isWhitespace(renderedTitle.charAt(end - 1));
                if (!atWordBoundary && end < renderedTitle.length()) {
                    atWordBoundary = Character.isWhitespace(renderedTitle.charAt(end));
                }
                assertTrue("the title broke a word at line " + line + ": " + renderedTitle,
                        atWordBoundary);
            }
            TextView caption = findTextViewContaining(page.getView(), "Einstellungen sichern");
            assertNotNull(caption);
            assertTextFits(caption);
            assertRowsReadable(page.getView().findViewById(android.R.id.list), 48);
        }
    }

    /**
     * The same page in Spanish at twice the text size. Spanish and Portuguese run about a fifth
     * longer than English, so a row that fits in English and in German still has to be looked
     * at once in a Romance language: "Silenciar el feed mientras los comentarios están abiertos"
     * is not a line German would have produced.
     */
    @Test @Config(qualifiers = "es-rES-w320dp-h800dp-night-mdpi")
    public void longSpanishLabelsStayReadableAtTwoTimesTextSize() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            var configuration = activity.getResources().getConfiguration();
            configuration.fontScale = 2.0f;
            activity.getResources().updateConfiguration(
                    configuration, activity.getResources().getDisplayMetrics());
            TikTokPreferenceFragment page = attachSection(activity, "COMMENTS");
            layout(page.getView(), 320, 800);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            TextView heading = page.getView().findViewWithTag("hushfeed_page_title");
            assertNotNull(heading);
            assertEquals("the page title is not in Spanish, so this proves nothing",
                    "Comentarios y traducción", heading.getText().toString());
            assertTextFits(heading);
            TextView caption = findTextViewContaining(page.getView(), "Filtros, traducción");
            assertNotNull("the page description is not in Spanish", caption);
            assertTextFits(caption);
            assertRowsReadable(page.getView().findViewById(android.R.id.list), 48);
            assertTrue("no switch row was on screen, so this proves nothing",
                    assertSwitchRowsReflowed(page.getView().findViewById(android.R.id.list), false) > 0);
            UiCapture.save(page.getView(), "pages/dark/comments-spanish-large.png", 320, 800);
        }
    }

    @Test @Config(qualifiers = "de-rDE-w320dp-h800dp-notnight-mdpi")
    public void controlsAndEditorsStayReadableAtTwoTimesTextSize() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            var configuration = activity.getResources().getConfiguration();
            configuration.fontScale = 2.0f;
            activity.getResources().updateConfiguration(configuration, activity.getResources().getDisplayMetrics());
            TikTokPreferenceFragment page = attachSection(activity, "COMMENTS");
            layout(page.getView(), 320, 800);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            TextView caption = findTextViewContaining(page.getView(), "Filter, Übersetzung");
            assertNotNull(caption);
            assertTextFits(caption);
            assertRowsReadable(page.getView().findViewById(android.R.id.list), 48);
            assertTrue("no switch row was on screen, so this proves nothing",
                    assertSwitchRowsReflowed(page.getView().findViewById(android.R.id.list), false) > 0);
            assertEditorRowFits(page, "comment_blocked_keywords", activity, 320, 800, 1);
            // Captured here: the fragment is replaced below, and its view goes with it.
            UiCapture.save(page.getView(), "pages/light/two-times-text.png", 320, 800);
            TikTokPreferenceFragment filterPage = attachSection(activity, "FEED_FILTER");
            layout(filterPage.getView(), 320, 800);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEditorRowFits(filterPage, "max_video_seconds", activity, 320, 800, 1);
            filterPage = attachSection(activity, "FEED_FILTER");
            layout(filterPage.getView(), 320, 800);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEditorRowFits(filterPage, "min_max_views", activity, 320, 800, 2);
        }
    }

    /**
     * The same at twice the text size in the dark theme, which is what most readers are on. A
     * capture of one theme says nothing about the other: the row backgrounds, the dividers and
     * the disabled colours are all different, and a caption that fits on white can still be
     * unreadable on black.
     */
    @Test @Config(qualifiers = "de-rDE-w320dp-h800dp-night-mdpi")
    public void controlsAndEditorsStayReadableAtTwoTimesTextSizeInTheDark() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            var configuration = activity.getResources().getConfiguration();
            configuration.fontScale = 2.0f;
            activity.getResources().updateConfiguration(configuration, activity.getResources().getDisplayMetrics());

            for (String section : MAIN_PAGES) {
                TikTokPreferenceFragment page = attachSection(activity, section);
                layout(page.getView(), 320, 800);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                TextView heading = page.getView().findViewWithTag("hushfeed_page_title");
                assertNotNull(section, heading);
                assertTextFits(heading);
                assertRowsReadable(page.getView().findViewById(android.R.id.list), 48);
                assertSwitchRowsReflowed(page.getView().findViewById(android.R.id.list), false);
                if ("COMMENTS".equals(section)) {
                    UiCapture.save(page.getView(), "pages/dark/two-times-text.png", 320, 800);
                }
            }
        }
    }

    @Test @Config(qualifiers = "ar-rXB-w320dp-h800dp-night-mdpi")
    public void rtlLargeTextKeepsLabelsOrderedAndTouchTargetsUsable() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            var configuration = activity.getResources().getConfiguration();
            configuration.fontScale = 2.0f;
            configuration.setLayoutDirection(new java.util.Locale("ar", "XB"));
            activity.getResources().updateConfiguration(configuration, activity.getResources().getDisplayMetrics());
            TikTokPreferenceFragment page = attachSection(activity, "COMMENTS");
            // Robolectric keeps the activity root's direction from its creation configuration;
            // apply the pseudolocale direction to the fragment tree before measuring its rows.
            forceRtl(page.getView());
            layout(page.getView(), 320, 800);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            forceRtl(page.getView());
            // setLayoutDirection throws away the resolved direction and asks for another
            // layout. Turning the tree round and then capturing it gave every view the left
            // to right default back, and a view hands that on to its drawables, so the arrow
            // in the picture pointed the way it came.
            layout(page.getView(), 320, 800);
assertEquals(View.LAYOUT_DIRECTION_RTL, configuration.getLayoutDirection());
            assertBackArrowPointsTheWayTheReaderReads(page.getView());
            assertRowsReadable(page.getView().findViewById(android.R.id.list), 48);
            assertTrue("no switch row was on screen, so this proves nothing",
                    assertSwitchRowsReflowed(page.getView().findViewById(android.R.id.list), true) > 0);
            TextView heading = page.getView().findViewWithTag("hushfeed_page_title");
            assertNotNull(heading);
            assertTextFits(heading);
            UiCapture.save(page.getView(), "pages/dark/rtl-large.png", 320, 800);

            // One page proves the row shape; the rest are where a caption written for one
            // language quietly runs off the edge in a mirrored layout.
            for (String section : MAIN_PAGES) {
                TikTokPreferenceFragment other = attachSection(activity, section);
                forceRtl(other.getView());
                layout(other.getView(), 320, 800);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                forceRtl(other.getView());
                TextView title = other.getView().findViewWithTag("hushfeed_page_title");
                assertNotNull(section, title);
                assertTextFits(title);
                assertRowsReadable(other.getView().findViewById(android.R.id.list), 48);
                assertSwitchRowsReflowed(other.getView().findViewById(android.R.id.list), true);
            }
        }
    }

    /**
     * The mirrored layout in the light theme. Both themes are captured because the two are laid
     * out from the same code but read very differently, and the only way to know a label has not
     * run off the edge in one of them is to have both.
     */
    @Test @Config(qualifiers = "ar-rXB-w320dp-h800dp-notnight-mdpi")
    public void rtlLargeTextIsReadableInTheLightThemeToo() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            var configuration = activity.getResources().getConfiguration();
            configuration.fontScale = 2.0f;
            configuration.setLayoutDirection(new java.util.Locale("ar", "XB"));
            activity.getResources().updateConfiguration(configuration, activity.getResources().getDisplayMetrics());
            TikTokPreferenceFragment page = attachSection(activity, "COMMENTS");
            forceRtl(page.getView());
            layout(page.getView(), 320, 800);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            forceRtl(page.getView());
            // setLayoutDirection throws away the resolved direction and asks for another
            // layout. Turning the tree round and then capturing it gave every view the left
            // to right default back, and a view hands that on to its drawables, so the arrow
            // in the picture pointed the way it came.
            layout(page.getView(), 320, 800);
assertEquals(View.LAYOUT_DIRECTION_RTL, configuration.getLayoutDirection());
            assertBackArrowPointsTheWayTheReaderReads(page.getView());
            TextView heading = page.getView().findViewWithTag("hushfeed_page_title");
            assertNotNull(heading);
            assertTextFits(heading);
            assertRowsReadable(page.getView().findViewById(android.R.id.list), 48);
            assertTrue("no switch row was on screen, so this proves nothing",
                    assertSwitchRowsReflowed(page.getView().findViewById(android.R.id.list), true) > 0);
            UiCapture.save(page.getView(), "pages/light/rtl-large.png", 320, 800);
        }
    }

    /**
     * At twice the text size a 320 dp phone leaves a title about a dozen characters a line
     * beside its switch, and the German Comments page read Kom-, mentare, automatisch,
     * übersetzen with the description wrapped in the same column. The switch now moves under
     * the text, at the end of the row, and the title and description take the whole width.
     * Checked on the rows, not the picture: the frame sits below the text at the row's end, a
     * title or description that wraps runs into the switch's old column, and no title breaks
     * inside a word that fits.
     */
    @Test @Config(qualifiers = "de-rDE-w320dp-h800dp-night-mdpi")
    public void narrowSwitchRowsPutTheSwitchUnderTheTextAndGiveTheTextTheRow() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            var configuration = activity.getResources().getConfiguration();
            configuration.fontScale = 2.0f;
            activity.getResources().updateConfiguration(configuration, activity.getResources().getDisplayMetrics());
            TikTokPreferenceFragment page = attachSection(activity, "COMMENTS");
            layout(page.getView(), 320, 800);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ListView list = page.getView().findViewById(android.R.id.list);
            assertTrue("no switch row was on screen, so this proves nothing",
                    assertSwitchRowsReflowed(list, false) > 0);
            assertRowsReadable(list, 48);

            // The row still works as a switch with the control inside the text block.
            int position = firstSwitchPosition(list);
            View row = list.getChildAt(position - list.getFirstVisiblePosition());
            android.preference.TwoStatePreference toggle =
                    (android.preference.TwoStatePreference) list.getAdapter().getItem(position);
            boolean before = toggle.isChecked();
            try {
                assertTrue(list.performItemClick(row, position, list.getAdapter().getItemId(position)));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals("the tap did not flip the setting", !before, toggle.isChecked());
                assertEquals("the switch does not show the new state", !before, findSwitch(row).isChecked());
            } finally {
                toggle.setChecked(before);
            }

            // A row recycled into a wide list goes back to the usual shape, and back again.
            app.morphe.extension.tiktok.settings.preference.SettingsUi.reflowSwitchRow(row, 2000);
            assertSwitchRowBesideTheText(row);
            app.morphe.extension.tiktok.settings.preference.SettingsUi.reflowSwitchRow(row, 320);
            layout(page.getView(), 320, 800);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(assertSwitchRowsReflowed(list, false) > 0);
        }
    }

    /** The same on Android 14, whose text scaling is a curve rather than a factor. */
    @Test @Config(sdk = 34, qualifiers = "de-rDE-w320dp-h800dp-night-mdpi")
    public void narrowSwitchRowsReflowUnderAndroid14TextScalingToo() throws Exception {
        narrowSwitchRowsPutTheSwitchUnderTheTextAndGiveTheTextTheRow();
    }

    /**
     * A wide list keeps the switch beside the whole text block, at twice the text size too. At
     * the normal size even a 320 dp list keeps this shape, so only the large size shows the width
     * deciding: the same rows at a 320 dp phone's width move the switch under the text.
     */
    @Test @Config(qualifiers = "de-rDE-w960dp-h800dp-night-mdpi")
    public void wideSwitchRowsKeepTheSwitchBesideTheText() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            var configuration = activity.getResources().getConfiguration();
            configuration.fontScale = 2.0f;
            activity.getResources().updateConfiguration(configuration, activity.getResources().getDisplayMetrics());
            TikTokPreferenceFragment page = attachSection(activity, "COMMENTS");
            layout(page.getView(), 960, 800);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ListView list = page.getView().findViewById(android.R.id.list);
            int rows = 0;
            for (int i = 0; i < list.getChildCount(); i++) {
                View row = list.getChildAt(i);
                if (findSwitch(row) == null) continue;
                assertSwitchRowBesideTheText(row);
                View text = (View) row.findViewById(android.R.id.title).getParent();
                View widget = row.findViewById(android.R.id.widget_frame);
                assertTrue("the text block runs under the switch", text.getRight() <= widget.getLeft());

                app.morphe.extension.tiktok.settings.preference.SettingsUi.reflowSwitchRow(row, 320);
                assertSame("at a 320 dp phone's width this text size keeps the switch beside the text, "
                        + "so the wide list proved nothing", text, widget.getParent());
                app.morphe.extension.tiktok.settings.preference.SettingsUi.reflowSwitchRow(row, list.getWidth());
                assertSwitchRowBesideTheText(row);
                rows++;
            }
            assertTrue("no switch row was on screen, so this proves nothing", rows > 0);
        }
    }

    private static int firstSwitchPosition(ListView list) {
        for (int i = 0; i < list.getChildCount(); i++) {
            if (findSwitch(list.getChildAt(i)) != null) return list.getFirstVisiblePosition() + i;
        }
        throw new AssertionError("no switch row on screen");
    }

    private static android.widget.Switch findSwitch(View view) {
        if (view instanceof android.widget.Switch) return (android.widget.Switch) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.Switch found = findSwitch(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Every switch row on screen is in the narrow shape; returns how many there were. */
    private static int assertSwitchRowsReflowed(ListView list, boolean rtl) {
        float density = list.getResources().getDisplayMetrics().density;
        int rows = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            android.widget.Switch control = findSwitch(row);
            if (control == null) continue;
            rows++;
            TextView title = row.findViewById(android.R.id.title);
            TextView summary = row.findViewById(android.R.id.summary);
            View widget = row.findViewById(android.R.id.widget_frame);
            View text = (View) title.getParent();
            String name = String.valueOf(title.getText());
            assertSame("the switch of " + name + " is still beside the whole text block", text, widget.getParent());
            View last = summary != null && summary.getVisibility() == View.VISIBLE ? summary : title;
            assertTrue(name + ": the switch frame is not under the text", widget.getTop() >= last.getBottom());
            assertEquals(name + ": the switch is not at the end of the row",
                    rtl ? text.getPaddingLeft() : text.getWidth() - text.getPaddingRight(),
                    rtl ? widget.getLeft() : widget.getRight());
            // Only text that needs more than a line has a reason to reach the switch's old column.
            for (TextView line : new TextView[]{title, summary}) {
                if (line == null || line.getVisibility() != View.VISIBLE || line.getLineCount() < 2) continue;
                assertTrue(name + ": wrapped text still stops short of the switch's old column",
                        rtl ? line.getLeft() < widget.getRight() : line.getRight() > widget.getLeft());
            }
            assertTrue(name + ": the switch is smaller than its touch target",
                    control.getHeight() >= Math.round(48 * density));
            assertTrue(name + ": the switch is clipped by its frame",
                    control.getTop() >= 0 && control.getBottom() <= widget.getHeight());
            assertTrue(name + ": the switch frame leaves the row",
                    widget.getLeft() >= 0 && widget.getRight() <= text.getWidth()
                            && widget.getBottom() <= text.getHeight());
            assertNoBreakInsideAWordThatFits(title);
        }
        return rows;
    }

    private static void assertSwitchRowBesideTheText(View row) {
        TextView title = row.findViewById(android.R.id.title);
        View widget = row.findViewById(android.R.id.widget_frame);
        android.view.ViewGroup group = (android.view.ViewGroup) row;
        assertSame(title.getText() + ": the switch is not back beside the text block", row, widget.getParent());
        assertSame(title.getText() + ": the switch is back in the row but not at its end",
                widget, group.getChildAt(group.getChildCount() - 1));
    }

    /** A line may end inside a word only when that word is wider than the text's own width. */
    private static void assertNoBreakInsideAWordThatFits(TextView text) {
        android.text.Layout layout = text.getLayout();
        assertNotNull(layout);
        CharSequence chars = layout.getText();
        int width = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
        for (int line = 0; line < layout.getLineCount() - 1; line++) {
            int end = layout.getLineEnd(line);
            if (end <= 0 || end >= chars.length()) continue;
            if (Character.isWhitespace(chars.charAt(end - 1)) || Character.isWhitespace(chars.charAt(end))) continue;
            int start = end;
            while (start > 0 && !Character.isWhitespace(chars.charAt(start - 1))) start--;
            int stop = end;
            while (stop < chars.length() && !Character.isWhitespace(chars.charAt(stop))) stop++;
            String word = chars.subSequence(start, stop).toString();
            assertTrue("\"" + text.getText() + "\" breaks inside \"" + word + "\", which fits its " + width + " px",
                    text.getPaint().measureText(word) > width);
        }
    }

    /**
     * The pages a reader actually opens. Not every section: the Lab and the gate editors have
     * their own capture suite, and walking them here would double this test's runtime for
     * coverage that already exists.
     */
    private static final String[] MAIN_PAGES = SECTIONS;

    /**
     * Building a page to read what's on it doesn't change it. The search index builds every
     * page, and one row wrote the tidied form of its own stored value the moment it was built,
     * so opening the settings rewrote a setting the reader had never touched. (The home page's
     * changed-setting badges, which found this, went with the groups they were shown on.)
     */
    @Test public void buildingThePagesForSearchWritesNothing() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            for (var setting : app.morphe.extension.shared.settings.Setting.allLoadedSettings()) {
                setting.resetToDefault();
            }
            // Stored in an order the row would tidy, which is what made the write visible.
            Settings.FEED_NAVIGATION_TABS.save("MALL,HOT");

            TikTokPreferenceFragment home = attachHome(activity);
            Preference search = findPreference(home.getPreferenceScreen(), "Search settings");
            assertTrue(search.getOnPreferenceClickListener().onPreferenceClick(search));
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            TikTokPreferenceFragment page = (TikTokPreferenceFragment) activity.getFragmentManager()
                    .findFragmentById(android.R.id.content);
            ((EditText) page.getView().findViewWithTag("settings_search_input")).setText("feed tabs to keep");
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertNotNull("the Feed tabs page wasn't indexed, so this proves nothing",
                    findPreference(page.getPreferenceScreen(), "Feed tabs to keep"));
            assertEquals("opening the settings search rewrote a setting nobody touched",
                    "MALL,HOT", Settings.FEED_NAVIGATION_TABS.get());
            Settings.FEED_NAVIGATION_TABS.resetToDefault();
        }
    }

    private static java.util.List<app.morphe.extension.tiktok.settings.preference
            .SettingsMenuPreference> menuRows(TikTokPreferenceFragment fragment) {
        var found = new java.util.ArrayList<app.morphe.extension.tiktok.settings.preference
                .SettingsMenuPreference>();
        var screen = fragment.getPreferenceScreen();
        assertNotNull("the master menu was never built", screen);
        for (int index = 0; index < screen.getPreferenceCount(); index++) {
            Preference row = screen.getPreference(index);
            if (row instanceof app.morphe.extension.tiktok.settings.preference
                    .SettingsMenuPreference) {
                found.add((app.morphe.extension.tiktok.settings.preference
                        .SettingsMenuPreference) row);
            }
        }
        assertFalse("the menu has no rows, so this proves nothing", found.isEmpty());
        return found;
    }

    /**
     * A restart-gated change pins a row that restarts TikTok, on this page and on the master
     * menu after Back, and the row is absent on a fresh open.
     *
     * <p>Three restart-gated switches gave three identical toasts and then nothing: come back
     * later and nothing said a restart was still owed, and there was no way to do it although
     * the app can relaunch itself. Two of the "this does not work" reports on the tracker are
     * restart-gated switches.
     */
    @Test public void aRestartGatedChangePinsARowThatRestartsTikTok() throws Exception {
        app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment
                .restartPending.clear();
        java.util.List<android.content.Context> restarted = new java.util.ArrayList<>();
        app.morphe.extension.tiktok.settings.preference.RestartPendingPreference
                .setRestarterForTests(restarted::add);
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment page = attachSection(activity, "BEHAVIOR");
            String key = app.morphe.extension.tiktok.settings.preference
                    .RestartPendingPreference.KEY;
            assertNull("a fresh page owes a restart", page.findPreference(key));

            android.preference.SwitchPreference toggle = (android.preference.SwitchPreference)
                    page.findPreference(Settings.FOLDABLE_SPLIT_VIEW.key);
            assertNotNull("the fixture switch is not on this page", toggle);
            assertTrue("the fixture switch does not need a restart",
                    Settings.FOLDABLE_SPLIT_VIEW.rebootApp);
            toggle.setChecked(!toggle.isChecked());
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            Preference pinned = page.findPreference(key);
            assertNotNull("the change did not pin a restart row", pinned);
            assertEquals("the row is not at the top of the page", -950, pinned.getOrder());
            // With its full stop: the sentence it replaces sits inside prose on the S22's
            // App behavior page, and without one it read "Restart pending If the old layout".
            assertTrue("the toggled row does not say its change is waiting",
                    String.valueOf(toggle.getSummary()).contains("Restart pending."));
            assertFalse("the toggled row still carries the generic sentence",
                    String.valueOf(toggle.getSummary()).contains("Restart TikTok to apply this."));

            View row = pinned.getView(null, null).findViewWithTag("hushfeed_restart_pending_row");
            assertNotNull("the pinned row draws no button", row);
            assertEquals("Restart TikTok to apply this change",
                    ((android.widget.TextView) row).getText().toString());
            android.view.accessibility.AccessibilityNodeInfo node =
                    row.createAccessibilityNodeInfo();
            assertEquals("a screen reader would read the restart as text",
                    android.widget.Button.class.getName(), String.valueOf(node.getClassName()));
            assertTrue("the count is not what a screen reader hears",
                    String.valueOf(node.getText()).contains("this change"));

            // Flipped back: the process already runs the value the store holds, so nothing
            // is owed, the row goes, and the switch says what it said before.
            toggle.setChecked(!toggle.isChecked());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull("a switch flipped back still owes a restart", page.findPreference(key));
            assertTrue("the row kept saying its change was waiting",
                    String.valueOf(toggle.getSummary()).contains("Restart TikTok to apply this."));
            assertFalse(String.valueOf(toggle.getSummary()).contains("Restart pending."));

            // And on: owed again.
            toggle.setChecked(!toggle.isChecked());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(page.findPreference(key));

            // Back to the master menu: the debt is owed there as well.
            TikTokPreferenceFragment home = attachHome(activity);
            Preference onHome = home.findPreference(key);
            assertNotNull("the master menu does not show the restart owed", onHome);

            // Two changes: the count follows.
            app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment
                    .restartPending.add(Settings.REGION_SPOOF.key);
            home.onResume();
            View homeRow = home.findPreference(key).getView(null, null)
                    .findViewWithTag("hushfeed_restart_pending_row");
            assertEquals("Restart TikTok to apply 2 changes",
                    ((android.widget.TextView) homeRow).getText().toString());

            assertTrue(homeRow.performClick());
            assertEquals("the press did not restart TikTok", 1, restarted.size());
        } finally {
            app.morphe.extension.tiktok.settings.preference.RestartPendingPreference
                    .setRestarterForTests(null);
            app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment
                    .restartPending.clear();
            Settings.FOLDABLE_SPLIT_VIEW.resetToDefault();
        }
    }

    /**
     * Pause and the status card sit near the end of the home page, so a restart pressed there,
     * from the banner, comes back scrolled to where it was rather than at the top of the page.
     * Pressed at the top, it comes back at the top.
     */
    @Test public void aRestartFromFarDownTheHomePageComesBackThere() throws Exception {
        var restartPending = app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment.restartPending;
        restartPending.clear();
        restartPending.add(Settings.FOLDABLE_SPLIT_VIEW.key);
        java.util.List<android.content.Context> restarted = new java.util.ArrayList<>();
        app.morphe.extension.tiktok.settings.preference.RestartPendingPreference
                .setRestarterForTests(restarted::add);
        android.content.SharedPreferences preferences =
                app.morphe.extension.shared.settings.Setting.preferences.preferences;
        String rowKey = app.morphe.extension.tiktok.settings.preference.RestartPendingPreference.KEY;
        try {
            String marker;
            try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
                Activity activity = owner.get();
                Utils.setContext(activity);
                TikTokPreferenceFragment home = attachHome(activity);
                layout(home.getView(), 480, 960);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                View restart = home.findPreference(rowKey).getView(null, null)
                        .findViewWithTag("hushfeed_restart_pending_row");

                assertTrue(restart.performClick());
                assertEquals("home", preferences.getString(SettingsReturn.PAGE_KEY, null));

                ListView list = home.getView().findViewById(android.R.id.list);
                list.setSelection(list.getAdapter().getCount() - 1);
                layout(home.getView(), 480, 960);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(restart.performClick());
                assertEquals(2, restarted.size());
                marker = preferences.getString(SettingsReturn.PAGE_KEY, null);
                assertTrue("the marker doesn't say how far down the page was: " + marker,
                        marker != null && marker.startsWith("home#"));
            }

            String row = marker.substring("home#".length());
            try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
                Activity activity = owner.get();
                Utils.setContext(activity);
                TikTokPreferenceFragment home = TikTokPreferenceFragment.home(marker);
                activity.getFragmentManager().beginTransaction()
                        .replace(android.R.id.content, home).commit();
                activity.getFragmentManager().executePendingTransactions();
                layout(home.getView(), 480, 960);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                layout(home.getView(), 480, 960);
                ListView list = home.getView().findViewById(android.R.id.list);
                int position = positionOf(list, row);
                assertTrue("the marker names a row this page doesn't have: " + row, position > 0);
                assertTrue("the page came back at its top", list.getFirstVisiblePosition() > 0);
                assertTrue("the row the restart was pressed at is off screen",
                        position >= list.getFirstVisiblePosition() && position <= list.getLastVisiblePosition());
                // Scrolled back up by hand, the page stays there after a page opened from it is
                // closed: Back brings this same page back with a fresh list, and the restart's row
                // was for coming back from the restart, not for every return after it.
                list.setSelection(0);
                layout(home.getView(), 480, 960);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                openMenu(activity, home, "section_behavior");
                assertTrue(activity.getFragmentManager().popBackStackImmediate());
                assertSame(home, activity.getFragmentManager().findFragmentById(android.R.id.content));
                layout(home.getView(), 480, 960);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                layout(home.getView(), 480, 960);
                assertEquals("Back from a page jumped home down to the restart's row again", 0,
                        ((ListView) home.getView().findViewById(android.R.id.list)).getFirstVisiblePosition());
                // Still the page settings opened on: its Back arrow closes settings.
                android.view.ViewGroup toolbar = home.getView().findViewWithTag("hushfeed_toolbar");
                assertTrue(toolbar.getChildAt(0).performClick());
                assertTrue("the returned home page lost its way out of settings", activity.isFinishing());
            }
        } finally {
            app.morphe.extension.tiktok.settings.preference.RestartPendingPreference.setRestarterForTests(null);
            restartPending.clear();
            preferences.edit().remove(SettingsReturn.PAGE_KEY).remove(SettingsReturn.AT_KEY).commit();
        }
    }

    @Test public void tabPickerChangesJoinThePersistentRestartDebt() throws Exception {
        String original = Settings.BOTTOM_NAVIGATION_TABS.get();
        app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment
                .restartPending.clear();
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment page = attachSection(activity, "FEED_NAVIGATION");
            app.morphe.extension.tiktok.settings.preference.TabSelectionPreference tabs =
                    (app.morphe.extension.tiktok.settings.preference.TabSelectionPreference)
                            page.findPreference(Settings.BOTTOM_NAVIGATION_TABS.key);
            assertNotNull("the bottom-tab picker is not on Feed tabs", tabs);
            assertTrue("the bottom-tab picker does not require a restart",
                    Settings.BOTTOM_NAVIGATION_TABS.rebootApp);

            String next = tabs.getValue().contains("FRIENDS")
                    ? "HOME,PROFILE" : "HOME,FRIENDS,PROFILE";
            assertTrue("the test did not change the selected tabs", tabs.setValue(next));
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertEquals("the picker and Setting disagree", tabs.getValue(),
                    Settings.BOTTOM_NAVIGATION_TABS.get());
            assertTrue("the custom picker bypassed the restart tracker",
                    app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment
                            .restartPending.contains(Settings.BOTTOM_NAVIGATION_TABS.key));
            assertNotNull("the change did not pin the restart action",
                    page.findPreference(app.morphe.extension.tiktok.settings.preference
                            .RestartPendingPreference.KEY));
        } finally {
            Settings.BOTTOM_NAVIGATION_TABS.save(original);
            app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment
                    .restartPending.clear();
        }
    }

    private static TikTokPreferenceFragment attachSection(Activity activity, String section) {
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putString("morphe_settings_section", section);
        fragment.setArguments(arguments);
        activity.getFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return fragment;
    }

    private static TikTokPreferenceFragment attachHome(Activity activity) {
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        activity.getFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return fragment;
    }

    private static TikTokPreferenceFragment openMenu(Activity activity,
            TikTokPreferenceFragment parent, String key) {
        Preference route = parent.findPreference(key);
        assertNotNull("missing navigation row " + key, route);
        assertNotNull("navigation row has no action " + key, route.getOnPreferenceClickListener());
        assertTrue("navigation row ignored the press " + key,
                route.getOnPreferenceClickListener().onPreferenceClick(route));
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return (TikTokPreferenceFragment) activity.getFragmentManager().findFragmentById(android.R.id.content);
    }

    /** The titles of the home page's rows, which is every page there is a way into. */
    private static java.util.Set<String> reachableMenuTitles(TikTokPreferenceFragment home) {
        java.util.Set<String> titles = new java.util.HashSet<>();
        for (var route : menuRows(home)) titles.add(String.valueOf(route.getTitle()));
        return titles;
    }

    private static Preference findPreference(android.preference.PreferenceScreen screen, String title) {
        for (int index = 0; index < screen.getPreferenceCount(); index++) {
            Preference preference = screen.getPreference(index);
            if (title.equals(String.valueOf(preference.getTitle()))) {
                return preference;
            }
        }
        return null;
    }

    private static int positionOf(ListView list, String key) {
        for (int position = 0; position < list.getAdapter().getCount(); position++) {
            Object item = list.getAdapter().getItem(position);
            if (item instanceof Preference && key.equals(((Preference) item).getKey())) {
                return position;
            }
        }
        return -1;
    }

    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static TextView findTextViewContaining(View view, String token) {
        if (view instanceof TextView && String.valueOf(((TextView) view).getText()).contains(token)) {
            return (TextView) view;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView result = findTextViewContaining(group.getChildAt(i), token);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static void assertTextFits(TextView text) {
        assertNotNull(text.getLayout());
        int lastLine = Math.max(0, text.getLineCount() - 1);
        assertEquals(0, text.getLayout().getEllipsisCount(lastLine));
        assertTrue(text.getHeight() >= text.getCompoundPaddingTop()
                + text.getLayout().getHeight() + text.getCompoundPaddingBottom());
        assertTrue(text.getBottom() <= ((View) text.getParent()).getHeight());
    }

    private static void assertRowsReadable(ListView list, int minTouchDp) {
        assertNotNull(list);
        int minTouch = Math.round(minTouchDp * list.getResources().getDisplayMetrics().density);
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            Object item = list.getAdapter().getItem(i + list.getFirstVisiblePosition());
            // Section labels have heading semantics, not a touch action. Keep the target floor
            // for every selectable preference, including disabled actions, and unknown rows.
            if (!(item instanceof Preference) || ((Preference) item).isSelectable()) {
                assertTrue("row " + i + " is smaller than its touch target", row.getHeight() >= minTouch);
            }
            TextView title = row.findViewById(android.R.id.title);
            if (title != null && title.getLayout() != null) assertTextFits(title);
            TextView summary = row.findViewById(android.R.id.summary);
            if (summary != null && summary.getLayout() != null) assertTextFits(summary);
        }
    }

    private static void assertRootRowsReadable(ListView list) {
        assertNotNull(list);
        int target = app.morphe.extension.tiktok.settings.preference.SettingsUi.dp(list.getContext(), 48);
        for (int index = 0; index < list.getChildCount(); index++) {
            View view = list.getChildAt(index);
            Object item = list.getAdapter().getItem(index + list.getFirstVisiblePosition());
            if (item instanceof Preference && ((Preference) item).isSelectable()) {
                assertTrue("a home action is smaller than 48dp", view.getHeight() >= target);
            }
            TextView title = view.findViewById(android.R.id.title);
            TextView summary = view.findViewById(android.R.id.summary);
            if (title != null && title.getVisibility() == View.VISIBLE) assertTextFits(title);
            if (summary != null && summary.getVisibility() == View.VISIBLE) assertTextFits(summary);
        }
    }

    private static void assertEditorRowFits(TikTokPreferenceFragment page, String key,
                                            Activity activity, int width, int height,
                                            int expectedEditors) {
        Preference target = page.findPreference(key);
        assertNotNull(target);
        ListView list = page.getView().findViewById(android.R.id.list);
        int position = -1;
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            if (list.getAdapter().getItem(i) == target) position = i;
        }
        assertTrue(position >= 0);
        assertTrue(list.performItemClick(list.getChildAt(position), position, list.getAdapter().getItemId(position)));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        android.app.Dialog dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        layout(dialog.getWindow().getDecorView(), width, height);
        TextView dialogTitle = findTextViewContaining(dialog.getWindow().getDecorView(),
                String.valueOf(target.getTitle()));
        assertNotNull(dialogTitle);
        assertTextFits(dialogTitle);
        java.util.List<android.widget.EditText> editors = new java.util.ArrayList<>();
        collectEditors(dialog.getWindow().getDecorView(), editors);
        assertEquals(expectedEditors, editors.size());
        for (android.widget.EditText editor : editors) {
            assertTrue(editor.isFocusable());
            assertTrue(editor.getHeight() >= Math.round(48 * activity.getResources().getDisplayMetrics().density));
        }
        dialog.dismiss();
    }

    private static void collectEditors(View view, java.util.List<android.widget.EditText> editors) {
        if (view instanceof android.widget.EditText) editors.add((android.widget.EditText) view);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectEditors(group.getChildAt(i), editors);
        }
    }

    /**
     * The back arrow in the picture about to be captured turns round with the layout.
     *
     * <p>Turning the views round is not enough on its own. The glyph is a drawable, and a
     * drawable takes its direction from the view it hangs off only once that view resolves,
     * which is a thing a fixture assembled by hand does not always do. Without this the
     * mirroring item's acceptance clause, which is about the captured picture, rested on
     * nothing.
     */
    private static void assertBackArrowPointsTheWayTheReaderReads(View root) {
        View toolbar = root.findViewWithTag("hushfeed_toolbar");
        assertNotNull("the page has no toolbar, so this proves nothing", toolbar);
        View back = ((android.view.ViewGroup) toolbar).getChildAt(0);
        assertTrue("the first thing in the toolbar is not the back button",
                back instanceof android.widget.ImageView);
        Drawable arrow = ((android.widget.ImageView) back).getDrawable();
        assertNotNull("the back button carries no glyph", arrow);
        assertEquals("the arrow in the captured picture still points the other way",
                View.LAYOUT_DIRECTION_RTL, arrow.getLayoutDirection());
    }

    private static void forceRtl(View view) {
        view.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) forceRtl(group.getChildAt(i));
        }
    }

    @Config(fontScale = 2.0f, qualifiers = "w320dp-h320dp")
    @Test public void aDiagnosticsRowReachedByScrollingKeepsItsCheckMark() throws Exception {
        // Eight rows at about 48dp is taller than the dialog on a small screen, and taller than
        // any screen at this font scale. The rows past the fold are built after the styling pass
        // has run, so they used to arrive with the platform's end-side check mark and TikTok's
        // text colour.
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment page = attachSection(activity, "DIAGNOSTICS");
            ListView list = page.getView().findViewById(android.R.id.list);
            int position = positionOf(list, "action_included_diagnostics");
            assertTrue("the Included diagnostics row is gone", position >= 0);
            list.performItemClick(list.getChildAt(position), position,
                    list.getAdapter().getItemId(position));

            android.app.AlertDialog dialog =
                    (android.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertTrue(dialog.isShowing());
            ListView choices = dialog.getListView();
            assertNotNull(choices);
            int rows = choices.getAdapter().getCount();
            assertTrue("nothing to scroll through", rows >= 6);

            // Short before anything is idled, so the styling pass sees only the top of the list.
            // Laying it out at its natural height first would build every row while the styling
            // is still to come, and then nothing would ever be built unstyled.
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            int styled = choices.getChildCount();
            assertTrue("the whole list fits, so no row is ever built later",
                    styled > 0 && styled < rows);

            choices.setSelection(rows - 1);
            layout(choices, choices.getWidth(), choices.getHeight());
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            int last = choices.getChildCount() - 1;
            assertTrue("no rows after scrolling to the end", last >= 0);
            assertEquals("did not reach the last row", rows - 1,
                    choices.getFirstVisiblePosition() + last);
            CheckedTextView row = findCheckedTextView(choices.getChildAt(last));
            assertNotNull("the last row is not a choice row", row);
            assertNull("the platform check mark came back on a scrolled row",
                    row.getCheckMarkDrawable());
            Drawable indicator = row.getCompoundDrawablesRelative()[0];
            assertNotNull("a scrolled row has no check mark at all", indicator);
            assertEquals("DialogCheckMarkDrawable", indicator.getClass().getSimpleName());
            assertEquals("a scrolled row kept TikTok's text colour",
                    app.morphe.extension.tiktok.settings.preference.SettingsUi.textPrimary(),
                    row.getCurrentTextColor());
        }
    }

    private static void assertChoiceIndicator(ListView list, int position, boolean checked, boolean radio)
            throws Exception {
        CheckedTextView row = findCheckedTextView(list.getChildAt(position));
        assertNotNull(row);
        assertEquals(checked, row.isChecked());
        assertNull(row.getCheckMarkDrawable());
        Drawable indicator = row.getCompoundDrawablesRelative()[0];
        assertNotNull(indicator);
        assertEquals("DialogCheckMarkDrawable", indicator.getClass().getSimpleName());
        Field field = indicator.getClass().getDeclaredField("radio");
        field.setAccessible(true);
        assertEquals(radio, field.getBoolean(indicator));
    }

    private static CheckedTextView findCheckedTextView(android.view.View view) {
        if (view instanceof CheckedTextView) return (CheckedTextView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                CheckedTextView result = findCheckedTextView(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }

    @Test public void theResetHourReadsAsATimeOfDay() throws Exception {
        // The row used "o'clock" as its unit for both singular and plural, so it said
        // "Current: 13 o'clock" and, at midnight, "Current: 0 o'clock".
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Settings.SESSION_BUDGET_RESET_HOUR.save(4);
            var row = new app.morphe.extension.tiktok.settings.preference.ClockHourPreference(
                    activity, "Start the day at", "The hour both budgets reset.",
                    Settings.SESSION_BUDGET_RESET_HOUR);

            String summary = String.valueOf(row.getSummary());
            assertTrue("the hour does not read as a time: " + summary, summary.contains("04:00"));
            assertTrue("the unit is still there: " + summary, summary.indexOf("o'clock") < 0);

            // The two values the old wording actually broke on.
            Settings.SESSION_BUDGET_RESET_HOUR.save(13);
            row.setValue("13");
            assertTrue("an afternoon hour: " + row.getSummary(),
                    String.valueOf(row.getSummary()).contains("13:00"));

            Settings.SESSION_BUDGET_RESET_HOUR.save(0);
            row.setValue("0");
            assertTrue("midnight: " + row.getSummary(),
                    String.valueOf(row.getSummary()).contains("00:00"));
            Settings.SESSION_BUDGET_RESET_HOUR.resetToDefault();
        }
    }

    /**
     * Opening Search lands in the field with the keyboard up, the way every native search does.
     *
     * <p>The page used to open with the box unfocused and no keyboard, so the reader tapped
     * it first, and the keyboard's action key was the generic one with autocorrect free to
     * rewrite a setting's name.
     */
    @Test public void openingSearchLandsInTheFieldWithTheKeyboardUp() {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment home = attachHome(activity);
            Preference search = findPreference(home.getPreferenceScreen(), "Search settings");
            assertNotNull(search);
            assertTrue(search.getOnPreferenceClickListener().onPreferenceClick(search));
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            TikTokPreferenceFragment page = (TikTokPreferenceFragment) activity.getFragmentManager()
                    .findFragmentById(android.R.id.content);
            EditText input = (EditText) page.getView().findViewWithTag("settings_search_input");
            assertNotNull(input);
            assertTrue("the field is not focused on entry", input.isFocused());
            org.robolectric.shadows.ShadowInputMethodManager keyboard = Shadows.shadowOf(
                    (android.view.inputmethod.InputMethodManager) activity.getSystemService(
                            android.content.Context.INPUT_METHOD_SERVICE));
            assertTrue("the keyboard was not asked for", keyboard.isSoftInputVisible());
            assertEquals(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH,
                    input.getImeOptions() & android.view.inputmethod.EditorInfo.IME_MASK_ACTION);
            assertTrue("autocorrect can rewrite a setting's name",
                    (input.getInputType() & android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0);

            // The search key takes the keyboard down so the results can be read.
            input.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            assertFalse("the search key left the keyboard up", keyboard.isSoftInputVisible());
        }
    }

    /**
     * The header's Back takes the keyboard down with the search page.
     *
     * <p>Popping the page left the keyboard up over the settings home and every page opened
     * after it, until Back was pressed once more and closed the keyboard instead of navigating
     * (S22, 2026-09-21: the Diagnostics page opened under it).
     */
    @Test public void leavingSearchWithTheHeadersBackTakesTheKeyboardDown() {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment home = attachHome(activity);
            Preference search = findPreference(home.getPreferenceScreen(), "Search settings");
            assertTrue(search.getOnPreferenceClickListener().onPreferenceClick(search));
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            TikTokPreferenceFragment page = (TikTokPreferenceFragment) activity.getFragmentManager()
                    .findFragmentById(android.R.id.content);
            org.robolectric.shadows.ShadowInputMethodManager keyboard = Shadows.shadowOf(
                    (android.view.inputmethod.InputMethodManager) activity.getSystemService(
                            android.content.Context.INPUT_METHOD_SERVICE));
            assertTrue("the keyboard was not asked for", keyboard.isSoftInputVisible());

            View back = viewWithContentDescription(page.getView(), "Back");
            assertNotNull("the search page has no Back in its header", back);
            assertTrue(back.performClick());
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertFalse("Back left the keyboard up over the next page", keyboard.isSoftInputVisible());
            assertSame("Back did not return to the settings home", home,
                    activity.getFragmentManager().findFragmentById(android.R.id.content));
        }
    }

    private static View viewWithContentDescription(View root, String description) {
        if (root == null) return null;
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = viewWithContentDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * A row greyed by its parent says which switch would turn it on, and takes the reason back
     * off when it does.
     *
     * <p>Four rows grey out when their parent is off and said nothing about it, so the row read
     * as broken and a screen reader announced only "dimmed". The two Region rows were worse:
     * they said in prose that they needed another switch and were not wired to it at all, so
     * either could be switched on while doing nothing.
     */
    @Test public void aRowItsParentGreyedSaysWhichSwitchWouldTurnItOn() throws Exception {
        boolean sim = SettingsStatus.simSpoofEnabled;
        boolean region = SettingsStatus.regionSpoofEnabled;
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            SettingsStatus.simSpoofEnabled = true;
            SettingsStatus.regionSpoofEnabled = true;
            Settings.SIM_SPOOF.save(false);
            Settings.REGION_SPOOF.save(false);

            TikTokPreferenceFragment page = attachSection(activity, "REGION");
            Preference dependent = findPreference(page.getPreferenceScreen(),
                    "Match locale and timezone to country");
            Preference parent = findPreference(page.getPreferenceScreen(), "Override SIM details");
            assertNotNull("the Region row is not on the page", dependent);
            assertNotNull("the SIM row is not on the page", parent);

            assertFalse("a row whose parent is off is not greyed", dependent.isEnabled());
            String greyed = String.valueOf(dependent.getSummary());
            assertTrue("the greyed row does not say what would turn it on: " + greyed,
                    greyed.endsWith("Turn on " + parent.getTitle() + " first."));

            // A second pass while it is still off adds the reason once, not twice.
            refreshAvailability(page);
            assertEquals("the reason was appended twice", greyed,
                    String.valueOf(dependent.getSummary()));

            // Turning the parent on takes the reason off rather than leaving it stacked.
            Settings.SIM_SPOOF.save(true);
            refreshAvailability(page);
            assertTrue("the row stayed greyed after its parent was turned on", dependent.isEnabled());
            String live = String.valueOf(dependent.getSummary());
            assertFalse("the reason is still on the row: " + live, live.contains("Turn on "));
            assertEquals("the row lost more than the reason", greyed,
                    live + " Turn on " + parent.getTitle() + " first.");
        } finally {
            Settings.SIM_SPOOF.resetToDefault();
            Settings.REGION_SPOOF.resetToDefault();
            SettingsStatus.simSpoofEnabled = sim;
            SettingsStatus.regionSpoofEnabled = region;
        }
    }

    /**
     * The store-region row names the switch that is off. It always named Match locale, so with
     * Match locale on and Override SIM details off it sent the reader to a switch already on.
     */
    @Test public void theStoreRegionRowNamesTheSwitchThatIsActuallyOff() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Settings.SIM_SPOOF.save(false);
            Settings.REGION_SPOOF.save(true);
            TikTokPreferenceFragment page = attachSection(activity, "REGION");
            Preference store = findPreference(page.getPreferenceScreen(), "Override store region (experimental)");
            assertNotNull("the store region row is not on the page", store);
            assertFalse(store.isEnabled());
            String simOff = String.valueOf(store.getSummary());
            assertTrue("the row named a switch that is already on: " + simOff,
                    simOff.endsWith("Turn on Override SIM details first."));

            Settings.REGION_SPOOF.save(false);
            refreshAvailability(page);
            String bothOff = String.valueOf(store.getSummary());
            assertTrue("with both off the nearer switch is the one named: " + bothOff,
                    bothOff.endsWith("Turn on Match locale and timezone to country first."));
        } finally {
            Settings.SIM_SPOOF.resetToDefault();
            Settings.REGION_SPOOF.resetToDefault();
        }
    }

    /** A row waiting on a text field says what to put in it, not which switch to turn on. */
    @Test public void theYtdlnisRowsSayWhatToPutInTheField() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Settings.EXTERNAL_DOWNLOADER_PACKAGE.save("");
            TikTokPreferenceFragment page = attachSection(activity, "DOWNLOADS");
            for (String title : new String[]{"YTDLnis download type", "YTDLnis background mode"}) {
                Preference row = findPreference(page.getPreferenceScreen(), title);
                assertNotNull(title + " is not on the page", row);
                assertFalse(title + " is not greyed", row.isEnabled());
                String summary = String.valueOf(row.getSummary());
                assertTrue(title + " asked for a switch: " + summary, summary.endsWith(
                        "Put " + Settings.YTDLNIS_PACKAGE_NAME + " in Send links to another app first."));
            }
        } finally {
            Settings.EXTERNAL_DOWNLOADER_PACKAGE.resetToDefault();
        }
    }

    /** Two rows that aren't auto-advance no longer sit under its heading. */
    @Test public void onlyAutoAdvanceRowsSitUnderItsHeading() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            android.preference.PreferenceScreen screen = attachSection(activity, "PLAYBACK").getPreferenceScreen();
            for (String title : new String[]{"Pause the video while comments are open", "Stay on the video in full screen"}) {
                String heading = null;
                for (int index = 0; index < screen.getPreferenceCount(); index++) {
                    Preference row = screen.getPreference(index);
                    if (row instanceof app.morphe.extension.tiktok.settings.preference.SectionHeadingPreference) {
                        heading = String.valueOf(row.getTitle());
                    }
                    if (title.equals(String.valueOf(row.getTitle()))) break;
                }
                assertEquals(title + " sits under the wrong heading", "Staying on a video", heading);
            }
        }
    }

    /**
     * The default speed is read only while its switch is on. Its row took a choice that did
     * nothing and said so nowhere; now it greys, and the note sits under the value on its own
     * line rather than running on from it.
     */
    @Test public void aChoiceWaitingOnItsSwitchGreysAndSaysSoOnItsOwnLine() throws Exception {
        boolean speed = SettingsStatus.playbackSpeedEnabled;
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            SettingsStatus.playbackSpeedEnabled = true;
            Settings.DEFAULT_SPEED_ENABLED.save(false);
            TikTokPreferenceFragment page = attachSection(activity, "PLAYBACK");
            Preference choice = findPreference(page.getPreferenceScreen(), "Default playback speed");
            assertNotNull(choice);
            assertFalse("the default speed stayed live with its switch off", choice.isEnabled());
            String[] lines = String.valueOf(choice.getSummary()).split("\n");
            assertEquals("Turn on Use a default playback speed first.", lines[lines.length - 1]);
            assertTrue("the value lost its own line", lines.length >= 2);
        } finally {
            Settings.DEFAULT_SPEED_ENABLED.resetToDefault();
            SettingsStatus.playbackSpeedEnabled = speed;
        }
    }

    /** A greyed number row puts the note on its own line; it ran into "Current: Off" before. */
    @Test public void aGreyedNumberRowPutsTheNoteOnALineOfItsOwn() throws Exception {
        boolean autoAdvance = SettingsStatus.autoAdvanceEnabled;
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            SettingsStatus.autoAdvanceEnabled = true;
            Settings.AUTO_ADVANCE.save(false);
            Settings.AUTO_ADVANCE_LIMIT.save(0);
            TikTokPreferenceFragment page = attachSection(activity, "PLAYBACK");
            Preference limit = findPreference(page.getPreferenceScreen(), "Auto-advance session limit");
            Preference parent = findPreference(page.getPreferenceScreen(), "Auto-advance videos");
            assertNotNull(limit);
            assertNotNull(parent);
            String[] lines = String.valueOf(limit.getSummary()).split("\n");
            assertEquals("Turn on " + parent.getTitle() + " first.", lines[lines.length - 1]);
            assertEquals("Current: Off", lines[lines.length - 2]);

            Settings.AUTO_ADVANCE.save(true);
            refreshAvailability(page);
            assertTrue(String.valueOf(limit.getSummary()).endsWith("Current: Off"));
        } finally {
            Settings.AUTO_ADVANCE.resetToDefault();
            Settings.AUTO_ADVANCE_LIMIT.resetToDefault();
            SettingsStatus.autoAdvanceEnabled = autoAdvance;
        }
    }

    /** The pass the settings screen makes over every row when it opens. */
    private static void refreshAvailability(TikTokPreferenceFragment page) throws Exception {
        java.lang.reflect.Method method = app.morphe.extension.shared.settings.preference
                .AbstractPreferenceFragment.class.getDeclaredMethod("updateUIToSettingValues");
        method.setAccessible(true);
        method.invoke(page);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /**
     * A switch greyed by its parent still shows whether it is on. The track put -state_enabled
     * first, so a disabled switch never reached the checked entry and looked the same on as
     * off: a reader could not see what it would come back as.
     */
    @Test public void aGreyedSwitchStillShowsWhetherItIsOn() {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            android.widget.Switch control = new android.widget.Switch(activity);
            app.morphe.extension.tiktok.settings.preference.SettingsUi.styleSwitch(control);
            android.graphics.drawable.Drawable track = control.getTrackDrawable();

            int offEnabled = trackColour(track, new int[]{android.R.attr.state_enabled});
            int onEnabled = trackColour(track,
                    new int[]{android.R.attr.state_enabled, android.R.attr.state_checked});
            int offDisabled = trackColour(track, new int[]{-android.R.attr.state_enabled});
            int onDisabled = trackColour(track,
                    new int[]{-android.R.attr.state_enabled, android.R.attr.state_checked});

            assertNotEquals("on and off look the same while enabled", offEnabled, onEnabled);
            assertNotEquals("a greyed switch looks the same on as off", offDisabled, onDisabled);
            assertNotEquals("a greyed switch that is on looks live", onEnabled, onDisabled);
        }
    }

    @Test public void metraThemeKeepsFlatRowsCompactHeadersAndPinkSwitchThumbs() throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment home = attachHome(activity);
            layout(home.getView(), 480, 960);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ListView list = home.getView().findViewById(android.R.id.list);
            View menu = list.getAdapter().getView(positionOf(list, "section_feed_filter"), null, list);
            TextView title = menu.findViewById(android.R.id.title);
            assertEquals(15.5f, title.getTextSize() / activity.getResources().getDisplayMetrics().scaledDensity, .01f);
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(480, 72,
                    android.graphics.Bitmap.Config.ARGB_8888);
            menu.getBackground().setBounds(0, 0, 480, 72);
            menu.getBackground().draw(new android.graphics.Canvas(bitmap));
            assertEquals("the row still draws an enclosing card at its edge",
                    app.morphe.extension.tiktok.settings.preference.SettingsUi.background(), bitmap.getPixel(0, 10));
            assertEquals("the flat rows lost their divider",
                    app.morphe.extension.tiktok.settings.preference.SettingsUi.divider(), bitmap.getPixel(240, 71));
            bitmap.recycle();

            TikTokPreferenceFragment page = openMenu(activity, home, "section_feed_filter");
            layout(page.getView(), 480, 960);
            TextView heading = page.getView().findViewWithTag("hushfeed_page_title");
            assertEquals(17f, heading.getTextSize() / activity.getResources().getDisplayMetrics().scaledDensity, .01f);
            assertTrue("the section still has a separate display header", heading.getBottom() <= 56);

            android.widget.Switch control = new android.widget.Switch(activity);
            app.morphe.extension.tiktok.settings.preference.SettingsUi.styleSwitch(control);
            int[] checked = {android.R.attr.state_enabled, android.R.attr.state_checked};
            assertEquals("enabled switches lost Metra's pink thumb",
                    app.morphe.extension.tiktok.settings.preference.SettingsUi.accent(),
                    trackColour(control.getThumbDrawable(), checked));
            assertNotEquals("the switch thumb and track have the same fill",
                    trackColour(control.getThumbDrawable(), checked), trackColour(control.getTrackDrawable(), checked));
        }
    }

    @Test @Config(qualifiers = "w480dp-h960dp-notnight-mdpi")
    public void lightMetraThemeKeepsTheSameLayoutAndControls() throws Exception {
        metraThemeKeepsFlatRowsCompactHeadersAndPinkSwitchThumbs();
    }

    /** What the track paints in the state given, at its centre. */
    private static int trackColour(android.graphics.drawable.Drawable track, int[] state) {
        track.setState(state);
        android.graphics.drawable.Drawable current = track.getCurrent();
        int width = Math.max(1, current.getIntrinsicWidth());
        int height = Math.max(1, current.getIntrinsicHeight());
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                width, height, android.graphics.Bitmap.Config.ARGB_8888);
        current.setBounds(0, 0, width, height);
        current.draw(new android.graphics.Canvas(bitmap));
        return bitmap.getPixel(width / 2, height / 2);
    }
}
