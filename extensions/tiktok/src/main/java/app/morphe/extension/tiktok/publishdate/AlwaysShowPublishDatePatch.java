/*
 * Thanks to lyyako for the original implementation and help with this patch.
 *
 * Originally adapted for TikTok 43.8.3; ported to TikTok 46.2.3:
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.publishdate;

import android.content.Context;
import android.text.format.DateUtils;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.download.SavedVideoMark;
import app.morphe.extension.tiktok.download.StoryShareSave;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Always show publish date, and its two options: the time to the second, with the time zone, on
 * the creator's row, and each video's date on a profile's grid.
 *
 * <p>Both read the time TikTok sent with the video (Aweme.createTime, Unix seconds). A video
 * without one still carries it in its ID, whose top 32 bits are the posting time in Unix seconds,
 * so that's where it comes from then. Everything is written the way the phone's language and time
 * zone write a date.
 */
public final class AlwaysShowPublishDatePatch {
    /** A post ID's top 32 bits are its posting time in Unix seconds. */
    private static final int ID_TIME_SHIFT = 32;

    /** 2016-01-01 UTC. Nothing on TikTok was posted before it, so an earlier time isn't one. */
    static final long EARLIEST_POST_SECONDS = 1_451_606_400L;

    /** A phone clock a day behind still sees every post as already made. */
    private static final long CLOCK_SLACK_SECONDS = 86_400L;

    /** Above this a createTime is in milliseconds, the way some older models carried it. */
    private static final long MILLISECONDS_FROM = 100_000_000_000L;

    private AlwaysShowPublishDatePatch() {
    }

    public static boolean showPostTimeForMainFeeds(boolean original) {
        return Settings.ALWAYS_SHOW_PUBLISH_DATE.get() ? false : original;
    }

    /**
     * Called where the creator's row has its post time text ready ("2d ago", "10-7"). Returns
     * that text, or the full date and time to the second with the time zone while the switch is on
     * (a story's header gets {@link #storyTime}), with Mark saved videos' check before it for a
     * video saved here.
     */
    public static String postTime(String original, Object item) {
        return SavedVideoMark.rowText(exactOrOriginal(original, item), item);
    }

    private static String exactOrOriginal(String original, Object item) {
        if (!Settings.PUBLISH_DATE_EXACT_TIME.get()) {
            return original;
        }
        try {
            long now = System.currentTimeMillis();
            long seconds = postedAt(item, now);
            if (seconds <= 0) return original;
            Context context = Utils.getContext();
            if (context != null && StoryShareSave.isStory(item)) {
                return storyTime(context, seconds, now);
            }
            // Without a context the language's own clock stands.
            boolean hour24 = context != null
                    ? android.text.format.DateFormat.is24HourFormat(context)
                    : ((SimpleDateFormat) DateFormat.getTimeInstance(DateFormat.LONG)).toPattern().indexOf('H') >= 0;
            return exactTime(seconds, Locale.getDefault(), TimeZone.getDefault(), hour24,
                    Settings.PUBLISH_DATE_TIME_ZONE.get(), Settings.PUBLISH_DATE_SHORT_DATE.get());
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not write the exact posting time", ex);
            return original;
        }
    }

    /**
     * The date a profile grid cell shows under its view count ("Oct 7", or "Oct 7, 2025" from
     * another year), or null when the switch is off or the video has no time.
     */
    public static String gridDate(Object item) {
        if (!SettingsStatus.alwaysShowPublishDateEnabled || !Settings.PUBLISH_DATE_ON_GRID.get()) {
            return null;
        }
        try {
            long now = System.currentTimeMillis();
            long seconds = postedAt(item, now);
            return seconds > 0 ? shortDate(Utils.getContext(), seconds, now) : null;
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not write a grid cell's date", ex);
            return null;
        }
    }

    /** When the item was posted, in Unix seconds: its createTime, else what its ID says, else 0. */
    static long postedAt(Object item, long nowMillis) {
        if (!(item instanceof Aweme)) {
            return 0;
        }
        Aweme aweme = (Aweme) item;
        long created;
        try {
            created = aweme.getCreateTime();
        } catch (Throwable ex) {
            // A build without the getter still has the ID.
            created = 0;
        }
        if (created >= MILLISECONDS_FROM) {
            created /= 1000;
        }
        if (plausible(created, nowMillis)) {
            return created;
        }
        String id;
        try {
            id = aweme.getAid();
        } catch (Throwable ex) {
            return 0;
        }
        return fromId(id, nowMillis);
    }

    /** The posting time a post ID carries, in Unix seconds, or 0 when it isn't one. */
    static long fromId(String id, long nowMillis) {
        if (id == null) {
            return 0;
        }
        long value;
        try {
            value = Long.parseLong(id.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
        long seconds = value >>> ID_TIME_SHIFT;
        return plausible(seconds, nowMillis) ? seconds : 0;
    }

    private static boolean plausible(long seconds, long nowMillis) {
        return seconds >= EARLIEST_POST_SECONDS && seconds <= nowMillis / 1000 + CLOCK_SLACK_SECONDS;
    }

    /**
     * The date with the time to the second and the zone, the way the phone's language writes them:
     * "Oct 7, 2025, 3:04:05 PM EDT" in American English, "07.10.2025, 15:04:05 MESZ" in German.
     * The hours follow the phone's own 12 or 24 hour setting, which java.text never reads.
     */
    static String exactTime(long seconds, Locale locale, TimeZone zone, boolean hour24) {
        return exactTime(seconds, locale, zone, hour24, true, false);
    }

    /**
     * The same, with the zone left off or the date written short, both the language's own way
     * rather than cut out of the long text (Discussion #147). Without the zone the time is the
     * language's medium one, which every locale writes with the seconds and without a zone; the
     * short date is the language's short one ("10/7/25", "07.10.25").
     */
    static String exactTime(long seconds, Locale locale, TimeZone zone, boolean hour24, boolean showZone,
            boolean shortDate) {
        // A new instance per call: DateFormat isn't thread safe.
        DateFormat format = DateFormat.getDateTimeInstance(shortDate ? DateFormat.SHORT : DateFormat.MEDIUM,
                showZone ? DateFormat.LONG : DateFormat.MEDIUM, locale);
        if (format instanceof SimpleDateFormat) {
            SimpleDateFormat simple = (SimpleDateFormat) format;
            String pattern = simple.toPattern();
            String clock = hourStyle(pattern, hour24);
            if (!clock.equals(pattern)) simple.applyPattern(clock);
        }
        format.setTimeZone(zone);
        return format.format(new Date(seconds * 1000L));
    }

    /**
     * A date pattern with its hours written on a 24 hour clock ("HH", no AM/PM) or a 12 hour one
     * ("h" with "a" after the seconds). Quoted text is left alone, and a pattern already on the
     * asked clock comes back as it was.
     */
    static String hourStyle(String pattern, boolean hour24) {
        boolean twelve = false, twentyFour = false, marker = false, quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\'') quoted = !quoted;
            else if (!quoted && (c == 'h' || c == 'K')) twelve = true;
            else if (!quoted && (c == 'H' || c == 'k')) twentyFour = true;
            else if (!quoted && isDayPeriod(c)) marker = true;
        }
        if (hour24 ? !twelve && !marker : !twentyFour) return pattern;
        StringBuilder out = new StringBuilder(pattern.length() + 2);
        int afterTime = -1;
        quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
                out.append(c);
                continue;
            }
            if (quoted) {
                out.append(c);
                continue;
            }
            boolean hour = isHour(c);
            if (hour) {
                while (i + 1 < pattern.length() && pattern.charAt(i + 1) == c) i++;
                out.append(hour24 ? "HH" : "h");
            } else if (isDayPeriod(c)) {
                if (!hour24) out.append(c);
                // A marker joined to its hour ("d日 ah:mm") goes alone: the space before it parts
                // the date from the time. Otherwise the space that set it apart goes with it, on
                // whichever side it was.
                else if (i + 1 < pattern.length() && isHour(pattern.charAt(i + 1))) continue;
                else if (trimSpace(out) == 0 && i + 1 < pattern.length() && isSpace(pattern.charAt(i + 1))) i++;
            } else {
                out.append(c);
            }
            if (hour || c == 'm' || c == 's') afterTime = out.length();
        }
        if (!hour24 && !marker && afterTime >= 0) out.insert(afterTime, " a");
        return out.toString();
    }

    private static boolean isHour(char c) {
        return c == 'h' || c == 'K' || c == 'H' || c == 'k';
    }

    /** AM/PM, and the noon and midnight ("b") and flexible ("B", "下午") day periods. */
    private static boolean isDayPeriod(char c) {
        return c == 'a' || c == 'b' || c == 'B';
    }

    private static int trimSpace(StringBuilder text) {
        int removed = 0;
        while (text.length() > 0 && isSpace(text.charAt(text.length() - 1))) {
            text.setLength(text.length() - 1);
            removed++;
        }
        return removed;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == ' ' || c == ' ';
    }

    /**
     * A short date in the phone's language and time zone: the month and day for this year
     * ("Oct 7"), with the year for any other ("Oct 7, 2025").
     */
    static String shortDate(Context context, long seconds, long nowMillis) {
        long millis = seconds * 1000L;
        int flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH
                | (sameYear(millis, nowMillis) ? DateUtils.FORMAT_NO_YEAR : DateUtils.FORMAT_SHOW_YEAR);
        return DateUtils.formatDateTime(context, millis, flags);
    }

    /**
     * A story's posting time to the minute ("3:04 PM", "15:04"), with the day before it ("Oct 7,
     * 3:04 PM") only when it isn't from today. A story is at most a day old, and its header shares
     * one line between the name, this time and the Follow button: the full text pushed the name
     * out (Login2, 47.4.3, 2026-10-18).
     */
    static String storyTime(Context context, long seconds, long nowMillis) {
        long millis = seconds * 1000L;
        int flags = DateUtils.FORMAT_SHOW_TIME | (sameDay(millis, nowMillis) ? 0
                : DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_NO_YEAR);
        return DateUtils.formatDateTime(context, millis, flags);
    }

    private static boolean sameYear(long millis, long nowMillis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        int year = calendar.get(Calendar.YEAR);
        calendar.setTimeInMillis(nowMillis);
        return year == calendar.get(Calendar.YEAR);
    }

    private static boolean sameDay(long millis, long nowMillis) {
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(millis);
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(nowMillis);
        return then.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR);
    }
}
