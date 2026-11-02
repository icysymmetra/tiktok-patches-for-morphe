/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.publishdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeStatistics;

import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.feed.ProfileGridCount;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class AlwaysShowPublishDatePatchTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** 2025-10-07 15:04:05 UTC. */
    private static final long POSTED = 1_759_849_445L;

    /** A post ID made at {@link #POSTED}: the time in its top 32 bits, 0x12345678 under them. */
    private static final String POSTED_ID = "7558495812464170616";

    /** 2025-11-20 00:00 UTC, later the same year. */
    private static final long SAME_YEAR_NOW = 1_763_596_800_000L;

    /** 2026-03-01 00:00 UTC, the year after. */
    private static final long NEXT_YEAR_NOW = 1_772_323_200_000L;

    /** An item as the creator's row and the grid hand it over. */
    public static final class Post extends Aweme {
        long createTime;
        final String aid;
        AwemeStatistics statistics;

        Post(long createTime, String aid) {
            this.createTime = createTime;
            this.aid = aid;
        }

        @Override public long getCreateTime() { return createTime; }
        @Override public String getAid() { return aid; }
        @Override public AwemeStatistics getStatistics() { return statistics; }
    }

    /** A story, as its header hands it over: flagged as one, or only typed as one (40, 45). */
    public static final class Story extends Aweme {
        final long createTime;
        final boolean flagged;
        final int type;

        Story(long createTime) {
            this(createTime, true, 0);
        }

        Story(long createTime, boolean flagged, int type) {
            this.createTime = createTime;
            this.flagged = flagged;
            this.type = type;
        }

        @Override public long getCreateTime() { return createTime; }
        @Override public String getAid() { return POSTED_ID; }
        @Override public boolean getIsTikTokStory() { return flagged; }
        @Override public int getAwemeType() { return type; }
    }

    /** A video from a build that renamed the story getter: the call fails to link. */
    public static final class RenamedStoryGetter extends Aweme {
        final long createTime;

        RenamedStoryGetter(long createTime) {
            this.createTime = createTime;
        }

        @Override public long getCreateTime() { return createTime; }
        @Override public String getAid() { return POSTED_ID; }
        @Override public boolean getIsTikTokStory() { throw new NoSuchMethodError("getIsTikTokStory"); }
    }

    /** A model whose createTime getter isn't there: the stub's throws, as a missing one would. */
    public static final class Untimed extends Aweme {
        final String aid;

        Untimed(String aid) {
            this.aid = aid;
        }

        @Override public String getAid() { return aid; }
    }

    public static final class Views extends AwemeStatistics {
        @Override public long getPlayCount() { return 10_000; }
        @Override public long getDiggCount() { return 300; }
        @Override public long getCommentCount() { return 50; }
        @Override public long getShareCount() { return 40; }
        @Override public long getCollectCount() { return 30; }
    }

    private Locale locale;
    private TimeZone zone;
    private boolean publishDateEnabled;
    private boolean engagementEnabled;

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        locale = Locale.getDefault();
        zone = TimeZone.getDefault();
        publishDateEnabled = SettingsStatus.alwaysShowPublishDateEnabled;
        engagementEnabled = SettingsStatus.engagementRateEnabled;
        Locale.setDefault(Locale.US);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SettingsStatus.alwaysShowPublishDateEnabled = true;
        SettingsStatus.engagementRateEnabled = false;
        Settings.ALWAYS_SHOW_PUBLISH_DATE.save(false);
    }

    @After
    public void tearDown() {
        setPaused(false);
        Locale.setDefault(locale);
        TimeZone.setDefault(zone);
        SettingsStatus.alwaysShowPublishDateEnabled = publishDateEnabled;
        SettingsStatus.engagementRateEnabled = engagementEnabled;
        Settings.ALWAYS_SHOW_PUBLISH_DATE.save(false);
        Settings.PUBLISH_DATE_EXACT_TIME.resetToDefault();
        Settings.PUBLISH_DATE_TIME_ZONE.resetToDefault();
        Settings.PUBLISH_DATE_SHORT_DATE.resetToDefault();
        Settings.PUBLISH_DATE_ON_GRID.resetToDefault();
        Settings.SHOW_ENGAGEMENT_RATE.resetToDefault();
    }

    private static long utcSeconds(int year, int month, int day, int hour, int minute) {
        java.util.Calendar calendar = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTimeInMillis() / 1000L;
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ClassParameter.from(boolean.class, value));
    }

    @Test
    public void theNativeSuppressionIsOverriddenWhenTheSettingIsOn() {
        Settings.ALWAYS_SHOW_PUBLISH_DATE.save(true);
        assertFalse(AlwaysShowPublishDatePatch.showPostTimeForMainFeeds(true));
        assertFalse(AlwaysShowPublishDatePatch.showPostTimeForMainFeeds(false));
    }

    @Test
    public void theNativeAnswerPassesThroughWhenTheSettingIsOff() {
        assertTrue(AlwaysShowPublishDatePatch.showPostTimeForMainFeeds(true));
        assertFalse(AlwaysShowPublishDatePatch.showPostTimeForMainFeeds(false));
    }

    @Test
    public void bothOptionsStartOffAndLeaveTikToksTextAlone() {
        assertEquals(Boolean.FALSE, Settings.PUBLISH_DATE_EXACT_TIME.defaultValue);
        assertEquals(Boolean.FALSE, Settings.PUBLISH_DATE_ON_GRID.defaultValue);
        Post post = new Post(POSTED, POSTED_ID);
        assertEquals("2d ago", AlwaysShowPublishDatePatch.postTime("2d ago", post));
        assertNull(AlwaysShowPublishDatePatch.gridDate(post));
        assertEquals("12.3K", ProfileGridCount.text("12.3K", post));
    }

    @Test
    public void theExactTimeHasTheSecondsAndTheZone() {
        String utc = AlwaysShowPublishDatePatch.exactTime(POSTED, Locale.US, TimeZone.getTimeZone("UTC"), false);
        assertTrue(utc, utc.startsWith("Oct 7, 2025"));
        assertTrue(utc, utc.contains("3:04:05"));
        assertTrue(utc, utc.contains("PM"));
        assertTrue(utc, utc.contains("UTC"));

        String eastern = AlwaysShowPublishDatePatch.exactTime(POSTED, Locale.US, TimeZone.getTimeZone("America/New_York"), false);
        assertTrue(eastern, eastern.contains("11:04:05"));
        assertTrue(eastern, eastern.contains("EDT"));
    }

    @Test
    public void theExactTimeIsWrittenTheWayThePhonesLanguageWritesIt() {
        String german = AlwaysShowPublishDatePatch.exactTime(POSTED, Locale.GERMANY, TimeZone.getTimeZone("UTC"), true);
        assertTrue(german, german.contains("07.10.2025"));
        assertTrue(german, german.contains("15:04:05"));
        assertFalse(german, german.contains("PM"));
    }

    @Test
    public void theHoursFollowThePhonesClockSetting() {
        String us24 = AlwaysShowPublishDatePatch.exactTime(POSTED, Locale.US, TimeZone.getTimeZone("UTC"), true);
        assertTrue(us24, us24.startsWith("Oct 7, 2025"));
        assertTrue(us24, us24.contains("15:04:05"));
        assertFalse(us24, us24.contains("PM"));
        assertTrue(us24, us24.contains("UTC"));

        String german12 = AlwaysShowPublishDatePatch.exactTime(POSTED, Locale.GERMANY, TimeZone.getTimeZone("UTC"), false);
        assertTrue(german12, german12.contains("07.10.2025"));
        assertTrue(german12, Pattern.compile("\\b3:04:05\\b").matcher(german12).find());
        assertTrue(german12, german12.contains("PM"));
    }

    @Test
    public void theClockRewriteLeavesQuotesAndMatchingPatternsAlone() {
        assertEquals("MMM d, y, HH:mm:ss z", AlwaysShowPublishDatePatch.hourStyle("MMM d, y, h:mm:ss a z", true));
        assertEquals("MMM d, y, h:mm:ss a z", AlwaysShowPublishDatePatch.hourStyle("MMM d, y, h:mm:ss a z", false));
        assertEquals("dd.MM.y, h:mm:ss a z", AlwaysShowPublishDatePatch.hourStyle("dd.MM.y, HH:mm:ss z", false));
        assertEquals("dd.MM.y, HH:mm:ss z", AlwaysShowPublishDatePatch.hourStyle("dd.MM.y, HH:mm:ss z", true));
        assertEquals("y. M. d. HH:mm:ss z", AlwaysShowPublishDatePatch.hourStyle("y. M. d. a h:mm:ss z", true));
        assertEquals("d 'h' HH:mm", AlwaysShowPublishDatePatch.hourStyle("d 'h' h:mm a", true));
    }

    @Test
    public void aMarkerJoinedToTheHourLeavesTheSpaceBeforeIt() {
        // zh_HK writes "y年M月d日 ah:mm:ss [z]": the space parts the date from the time.
        assertEquals("y年M月d日 HH:mm:ss [z]",
                AlwaysShowPublishDatePatch.hourStyle("y年M月d日 ah:mm:ss [z]", true));
        assertEquals("y/MM/dd HH:mm:ss z", AlwaysShowPublishDatePatch.hourStyle("y/MM/dd aK:mm:ss z", true));
        // A flexible day period ("下午") goes the same way as AM/PM.
        assertEquals("y/M/d HH:mm:ss", AlwaysShowPublishDatePatch.hourStyle("y/M/d Bh:mm:ss", true));
        assertEquals("y/M/d Bh:mm:ss", AlwaysShowPublishDatePatch.hourStyle("y/M/d BH:mm:ss", false));
        // en_US puts a narrow no-break space before AM/PM; the plain one after it stays.
        assertEquals("MMM d, y, HH:mm:ss z", AlwaysShowPublishDatePatch.hourStyle("MMM d, y, h:mm:ss a z", true));
    }

    @Test
    public void theSwitchPutsTheExactTimeOnTheCreatorsRow() {
        Settings.PUBLISH_DATE_EXACT_TIME.save(true);
        String text = AlwaysShowPublishDatePatch.postTime("2d ago", new Post(POSTED, POSTED_ID));
        assertEquals(AlwaysShowPublishDatePatch.exactTime(POSTED, Locale.US, TimeZone.getTimeZone("UTC"), false), text);
        assertTrue(text, Pattern.compile("\\b3:04:05\\b").matcher(text).find());
        // Nothing to go on leaves TikTok's own text.
        assertEquals("2d ago", AlwaysShowPublishDatePatch.postTime("2d ago", new Post(0, "not an id")));
        assertEquals("2d ago", AlwaysShowPublishDatePatch.postTime("2d ago", "not a video"));
        assertEquals("2d ago", AlwaysShowPublishDatePatch.postTime("2d ago", null));
    }

    @Test
    public void aStorysHeaderGetsItsTimeToTheMinute() {
        // The same afternoon: the time alone, no seconds, no zone.
        String today = AlwaysShowPublishDatePatch.storyTime(context(), POSTED, POSTED * 1000L + 3_600_000L);
        assertTrue(today, Pattern.compile("^3:04\\s?PM$").matcher(today).matches());
        // The next morning: the day goes in front.
        String yesterday = AlwaysShowPublishDatePatch.storyTime(context(), POSTED, POSTED * 1000L + 12 * 3_600_000L);
        assertTrue(yesterday, yesterday.startsWith("Oct 7"));
        assertTrue(yesterday, Pattern.compile("\\b3:04\\s?PM$").matcher(yesterday).find());

        Settings.PUBLISH_DATE_EXACT_TIME.save(true);
        long recent = System.currentTimeMillis() / 1000L - 600L;
        String header = AlwaysShowPublishDatePatch.postTime("10m ago", new Story(recent));
        assertEquals(AlwaysShowPublishDatePatch.storyTime(context(), recent, System.currentTimeMillis()), header);
        assertFalse(header, header.contains("UTC"));
        // A story TikTok marks only by its type gets the same.
        assertEquals(header, AlwaysShowPublishDatePatch.postTime("10m ago", new Story(recent, false, 40)));
        // A feed video by the same creator keeps the full time.
        assertTrue(AlwaysShowPublishDatePatch.postTime("2d ago", new Post(POSTED, POSTED_ID)).contains("3:04:05"));
        assertTrue(AlwaysShowPublishDatePatch.postTime("10m ago", new Story(recent, false, 0)).contains("UTC"));
        // A renamed story getter leaves feed rows on the full time, not TikTok's own text.
        assertTrue(AlwaysShowPublishDatePatch.postTime("10m ago", new RenamedStoryGetter(recent)).contains("UTC"));
        Settings.PUBLISH_DATE_EXACT_TIME.save(false);
        assertEquals("10m ago", AlwaysShowPublishDatePatch.postTime("10m ago", new Story(recent)));
    }

    @Test
    public void theZoneAndTheShortDateAreTheirOwnChoicesAndKeepTheSeconds() {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        String noZone = AlwaysShowPublishDatePatch.exactTime(POSTED, Locale.US, utc, false, false, false);
        assertTrue(noZone, noZone.startsWith("Oct 7, 2025"));
        assertTrue(noZone, Pattern.compile("\\b3:04:05\\b").matcher(noZone).find());
        assertTrue(noZone, noZone.contains("PM"));
        assertFalse(noZone, noZone.contains("UTC"));

        String shortWithZone = AlwaysShowPublishDatePatch.exactTime(POSTED, Locale.US, utc, false, true, true);
        assertTrue(shortWithZone, shortWithZone.startsWith("10/7/25"));
        assertTrue(shortWithZone, shortWithZone.contains("3:04:05"));
        assertTrue(shortWithZone, shortWithZone.contains("UTC"));

        String shortOnly = AlwaysShowPublishDatePatch.exactTime(POSTED, Locale.US, utc, true, false, true);
        assertTrue(shortOnly, shortOnly.startsWith("10/7/25"));
        assertTrue(shortOnly, shortOnly.contains("15:04:05"));
        assertFalse(shortOnly, shortOnly.contains("PM") || shortOnly.contains("UTC"));

        // The defaults are the row as it always read.
        assertEquals(AlwaysShowPublishDatePatch.exactTime(POSTED, Locale.US, utc, false),
                AlwaysShowPublishDatePatch.exactTime(POSTED, Locale.US, utc, false, true, false));
    }

    @Test
    public void anotherLanguageWritesItsOwnShortDateAndClock() {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        String german = AlwaysShowPublishDatePatch.exactTime(POSTED, Locale.GERMANY, utc, true, false, true);
        assertTrue(german, german.contains("07.10.25"));
        assertFalse(german, german.contains("07.10.2025"));
        assertTrue(german, german.contains("15:04:05"));
        assertFalse(german, german.contains("UTC") || german.contains("PM"));

        String german12 = AlwaysShowPublishDatePatch.exactTime(POSTED, Locale.GERMANY, utc, false, false, false);
        assertTrue(german12, german12.contains("07.10.2025"));
        assertTrue(german12, Pattern.compile("\\b3:04:05\\b").matcher(german12).find());
        assertTrue(german12, german12.contains("PM"));
        assertFalse(german12, german12.contains("UTC"));
    }

    @Test
    public void theSameInstantKeepsItsHourAcrossTheFallBack() {
        // 2025-11-02 in New York: 05:30 UTC is 1:30 EDT, and an hour later 1:30 again, in EST.
        TimeZone newYork = TimeZone.getTimeZone("America/New_York");
        long beforeFallBack = utcSeconds(2025, 11, 2, 5, 30);
        long afterFallBack = utcSeconds(2025, 11, 2, 6, 30);
        String daylight = AlwaysShowPublishDatePatch.exactTime(beforeFallBack, Locale.US, newYork, false, true, false);
        String standard = AlwaysShowPublishDatePatch.exactTime(afterFallBack, Locale.US, newYork, false, true, false);
        assertTrue(daylight, daylight.contains("1:30:00") && daylight.contains("EDT"));
        assertTrue(standard, standard.contains("1:30:00") && standard.contains("EST"));
        // With the zone off both read the same local time: that hour happens twice there.
        assertEquals(AlwaysShowPublishDatePatch.exactTime(beforeFallBack, Locale.US, newYork, false, false, true),
                AlwaysShowPublishDatePatch.exactTime(afterFallBack, Locale.US, newYork, false, false, true));
        assertTrue(AlwaysShowPublishDatePatch.exactTime(beforeFallBack, Locale.US, newYork, true, false, true)
                .startsWith("11/2/25"));
    }

    @Test
    public void theRowFollowsBothOptionsOnlyWhileTheTimeToTheSecondIsOn() {
        assertEquals(Boolean.TRUE, Settings.PUBLISH_DATE_TIME_ZONE.defaultValue);
        assertEquals(Boolean.FALSE, Settings.PUBLISH_DATE_SHORT_DATE.defaultValue);
        Post post = new Post(POSTED, POSTED_ID);
        TimeZone utc = TimeZone.getTimeZone("UTC");
        Settings.PUBLISH_DATE_TIME_ZONE.save(false);
        Settings.PUBLISH_DATE_SHORT_DATE.save(true);
        assertEquals("both options with the time to the second off", "2d ago",
                AlwaysShowPublishDatePatch.postTime("2d ago", post));

        Settings.PUBLISH_DATE_EXACT_TIME.save(true);
        assertEquals(AlwaysShowPublishDatePatch.exactTime(POSTED, Locale.US, utc, false, false, true),
                AlwaysShowPublishDatePatch.postTime("2d ago", post));
        Settings.PUBLISH_DATE_TIME_ZONE.save(true);
        Settings.PUBLISH_DATE_SHORT_DATE.save(false);
        assertEquals(AlwaysShowPublishDatePatch.exactTime(POSTED, Locale.US, utc, false),
                AlwaysShowPublishDatePatch.postTime("2d ago", post));

        Settings.PUBLISH_DATE_TIME_ZONE.save(false);
        setPaused(true);
        assertEquals("2d ago", AlwaysShowPublishDatePatch.postTime("2d ago", post));
    }

    @Test
    public void aPostIdCarriesItsPostingTime() {
        long now = SAME_YEAR_NOW;
        assertEquals(POSTED, AlwaysShowPublishDatePatch.fromId(POSTED_ID, now));
        assertEquals(POSTED, AlwaysShowPublishDatePatch.fromId(" " + POSTED_ID + " ", now));
        // Too early to be a post, from the future, not a number, or nothing.
        assertEquals(0, AlwaysShowPublishDatePatch.fromId("12345", now));
        assertEquals(0, AlwaysShowPublishDatePatch.fromId(String.valueOf((now / 1000 + 2 * 86_400L) << 32), now));
        assertEquals(0, AlwaysShowPublishDatePatch.fromId("7558495812464170616x", now));
        assertEquals(0, AlwaysShowPublishDatePatch.fromId(null, now));
        // 2016-01-01 is the first second that counts.
        assertEquals(0, AlwaysShowPublishDatePatch.fromId(
                String.valueOf((AlwaysShowPublishDatePatch.EARLIEST_POST_SECONDS - 1) << 32), now));
        assertEquals(AlwaysShowPublishDatePatch.EARLIEST_POST_SECONDS, AlwaysShowPublishDatePatch.fromId(
                String.valueOf(AlwaysShowPublishDatePatch.EARLIEST_POST_SECONDS << 32), now));
    }

    @Test
    public void theIdStandsInWhenCreateTimeIsZeroOrMissing() {
        long now = SAME_YEAR_NOW;
        assertEquals(POSTED, AlwaysShowPublishDatePatch.postedAt(new Post(POSTED, "1"), now));
        assertEquals(POSTED, AlwaysShowPublishDatePatch.postedAt(new Post(0, POSTED_ID), now));
        assertEquals(POSTED, AlwaysShowPublishDatePatch.postedAt(new Post(-1, POSTED_ID), now));
        assertEquals(POSTED, AlwaysShowPublishDatePatch.postedAt(new Untimed(POSTED_ID), now));
        // A createTime in milliseconds is still the same second.
        assertEquals(POSTED, AlwaysShowPublishDatePatch.postedAt(new Post(POSTED * 1000L, null), now));
        assertEquals(0, AlwaysShowPublishDatePatch.postedAt(new Post(0, null), now));
        assertEquals(0, AlwaysShowPublishDatePatch.postedAt(new Untimed("abc"), now));
        assertEquals(0, AlwaysShowPublishDatePatch.postedAt("not a video", now));
        assertEquals(0, AlwaysShowPublishDatePatch.postedAt(null, now));
    }

    @Test
    public void theGridDateLeavesTheYearOffOnlyForThisYear() {
        assertEquals("Oct 7", AlwaysShowPublishDatePatch.shortDate(context(), POSTED, SAME_YEAR_NOW));
        assertEquals("Oct 7, 2025", AlwaysShowPublishDatePatch.shortDate(context(), POSTED, NEXT_YEAR_NOW));
    }

    @Test
    public void theGridDateIsTheDayInThePhonesTimeZone() {
        // 15:04 UTC is already 05:04 the next morning at UTC+14.
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
        assertEquals("Oct 8", AlwaysShowPublishDatePatch.shortDate(context(), POSTED, SAME_YEAR_NOW));
    }

    @Test
    public void theSwitchPutsTheDateUnderTheGridCount() {
        Settings.PUBLISH_DATE_ON_GRID.save(true);
        Post post = new Post(POSTED, POSTED_ID);
        String date = AlwaysShowPublishDatePatch.shortDate(context(), POSTED, System.currentTimeMillis());
        assertEquals(date, AlwaysShowPublishDatePatch.gridDate(post));
        assertEquals("12.3K\n" + date, ProfileGridCount.text("12.3K", post));
        // A post with no time to go on keeps the count alone.
        assertEquals("12.3K", ProfileGridCount.text("12.3K", new Post(0, null)));
        assertEquals("12.3K", ProfileGridCount.text("12.3K", null));
        assertNull(ProfileGridCount.text(null, post));
    }

    @Test
    public void theDateGoesUnderTheCountAndTheEngagementRate() {
        SettingsStatus.engagementRateEnabled = true;
        Settings.SHOW_ENGAGEMENT_RATE.save(true);
        Settings.PUBLISH_DATE_ON_GRID.save(true);
        Post post = new Post(POSTED, POSTED_ID);
        post.statistics = new Views();
        String date = AlwaysShowPublishDatePatch.shortDate(context(), POSTED, System.currentTimeMillis());
        assertEquals("12.3K · 4.2%\n" + date, ProfileGridCount.text("12.3K", post));
        Settings.PUBLISH_DATE_ON_GRID.save(false);
        assertEquals("12.3K · 4.2%", ProfileGridCount.text("12.3K", post));
    }

    @Test
    public void withoutThePatchTheGridSwitchDoesNothing() {
        Settings.PUBLISH_DATE_ON_GRID.save(true);
        SettingsStatus.alwaysShowPublishDateEnabled = false;
        Post post = new Post(POSTED, POSTED_ID);
        assertNull(AlwaysShowPublishDatePatch.gridDate(post));
        assertEquals("12.3K", ProfileGridCount.text("12.3K", post));
    }

    @Test
    public void pausedBothOptionsLeaveTikToksTextAlone() {
        Settings.ALWAYS_SHOW_PUBLISH_DATE.save(true);
        Settings.PUBLISH_DATE_EXACT_TIME.save(true);
        Settings.PUBLISH_DATE_ON_GRID.save(true);
        Post post = new Post(POSTED, POSTED_ID);
        setPaused(true);
        assertEquals("2d ago", AlwaysShowPublishDatePatch.postTime("2d ago", post));
        assertNull(AlwaysShowPublishDatePatch.gridDate(post));
        assertEquals("12.3K", ProfileGridCount.text("12.3K", post));
        assertTrue(AlwaysShowPublishDatePatch.showPostTimeForMainFeeds(true));
        setPaused(false);
        assertTrue(Settings.PUBLISH_DATE_EXACT_TIME.get());
        assertTrue(Settings.PUBLISH_DATE_ON_GRID.get());
    }
}
