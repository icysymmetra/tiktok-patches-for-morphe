/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/** The home page ends with Report a problem, then the Ko-fi row, and both open the browser. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class SettingsFooterRowsTest {
    private static final String ISSUE_FORM = "https://github.com/icysymmetra/tiktok-patches-for-morphe/issues/new?template=bug_report.yml&";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Test public void reportAProblemSitsAboveTheKoFiRowWhichIsLast() throws Exception {
        try (ActivityController<Activity> owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            PreferenceScreen screen = homePage(activity);
            int count = screen.getPreferenceCount();
            Preference support = screen.getPreference(count - 1);
            Preference report = screen.getPreference(count - 2);
            assertEquals(TikTokPreferenceFragment.SUPPORT_KEY, support.getKey());
            assertEquals("Support Metra", String.valueOf(support.getTitle()));
            assertEquals("the Support row carries its title and nothing under it",
                    "", String.valueOf(support.getSummary()));
            assertEquals(TikTokPreferenceFragment.REPORT_PROBLEM_KEY, report.getKey());
            assertEquals("Report a problem", String.valueOf(report.getTitle()));
            assertEquals("Opens a GitHub report with your versions and settings filled in. Add what happened.",
                    String.valueOf(report.getSummary()));

            assertTrue(support.getOnPreferenceClickListener().onPreferenceClick(support));
            Intent kofi = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull("the Ko-fi row opened nothing", kofi);
            assertEquals(Intent.ACTION_VIEW, kofi.getAction());
            assertEquals("https://ko-fi.com/P5P5YOUU7", String.valueOf(kofi.getData()));

            assertTrue(report.getOnPreferenceClickListener().onPreferenceClick(report));
            Intent issue = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull("Report a problem opened nothing", issue);
            assertEquals(Intent.ACTION_VIEW, issue.getAction());
            assertTrue(String.valueOf(issue.getData()), String.valueOf(issue.getData()).startsWith(ISSUE_FORM));
            assertNull("no browser answered, so the link is left to open like any other", issue.getPackage());
        }
    }

    /** GitHub's app takes a new-issue link into its own composer, which drops the filled-in fields. */
    @Test public void reportAProblemNamesTheBrowserSoNoOtherAppTakesTheLink() throws Exception {
        try (ActivityController<Activity> owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            answersWebLinks(activity, "com.example.browser");
            PreferenceScreen screen = homePage(activity);
            int count = screen.getPreferenceCount();
            Preference support = screen.getPreference(count - 1);
            Preference report = screen.getPreference(count - 2);

            assertTrue(report.getOnPreferenceClickListener().onPreferenceClick(report));
            Intent issue = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull("Report a problem opened nothing", issue);
            assertEquals("com.example.browser", issue.getPackage());
            assertTrue(String.valueOf(issue.getData()), String.valueOf(issue.getData()).startsWith(ISSUE_FORM));
            assertTrue("the report left TikTok's task",
                    (issue.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);

            assertTrue(support.getOnPreferenceClickListener().onPreferenceClick(support));
            Intent kofi = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull("the Ko-fi row opened nothing", kofi);
            assertNull("the Ko-fi page has no form to lose, so it opens like any other link", kofi.getPackage());
        }
    }

    /** With several browsers and none the default, Android answers with its own chooser. */
    @Test public void theChooserIsNotTakenForABrowser() throws Exception {
        try (ActivityController<Activity> owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            assertNull(TikTokPreferenceFragment.browserPackage(activity));

            answersWebLinks(activity, "android");
            assertNull(TikTokPreferenceFragment.browserPackage(activity));

            answersWebLinks(activity, "com.example.second");
            assertEquals("com.example.second", TikTokPreferenceFragment.browserPackage(activity));
            assertEquals("com.example.second",
                    TikTokPreferenceFragment.browserPageIntent(activity, ISSUE_FORM).getPackage());
        }
    }

    /** An address on the question would let the app that claims that site answer as a browser. */
    @Test public void theBrowserQuestionCarriesNoAddress() {
        Intent question = TikTokPreferenceFragment.anyWebPageIntent();
        assertEquals(Intent.ACTION_VIEW, question.getAction());
        assertTrue(question.hasCategory(Intent.CATEGORY_BROWSABLE));
        assertEquals("https", question.getData().getScheme());
        assertNull(question.getData().getHost());
        assertNull(question.getPackage());
    }

    private static PreferenceScreen homePage(Activity activity) {
        Utils.setContext(activity);
        TikTokPreferenceFragment home = new TikTokPreferenceFragment();
        activity.getFragmentManager().beginTransaction().replace(android.R.id.content, home).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return home.getPreferenceScreen();
    }

    private static void answersWebLinks(Activity activity, String packageName) {
        ResolveInfo info = new ResolveInfo();
        info.isDefault = true;
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = packageName;
        info.activityInfo.name = packageName + ".Pages";
        Shadows.shadowOf(activity.getPackageManager()).addResolveInfoForIntent(
                TikTokPreferenceFragment.anyWebPageIntent(), info);
    }
}
