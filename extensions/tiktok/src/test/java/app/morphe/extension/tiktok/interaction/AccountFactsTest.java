/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.ss.android.ugc.aweme.profile.model.User;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import app.morphe.extension.shared.Utils;

/** Account facts from TikTok's own account model, in a fixed language and time zone. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class AccountFactsTest {
    private static final Locale LOCALE = Locale.US;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    private static String when(long seconds) {
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, LOCALE);
        format.setTimeZone(UTC);
        return format.format(new Date(seconds * 1000L));
    }

    private static List<String> lines(User user) {
        List<String> lines = new ArrayList<>();
        for (AccountFacts.Fact fact : AccountFacts.of(user, LOCALE, UTC)) {
            lines.add(fact.label + "=" + fact.value);
        }
        return lines;
    }

    @Test public void aFullProfileGetsEveryFactInThePhonesWords() {
        User user = new User();
        user._createTime = 1_615_819_200L;
        user.registerTime = 1_500_000_000L;
        user.region = "de";
        user.accountRegion = "DE";
        user.language = "de";
        user.uniqueIdModifyTime = 1_700_000_000_000L;
        user.nickNameModifyTs = 1_690_000_000;
        user.isSecret = true;
        user.hasOpenFavorite = true;

        assertEquals(List.of(
                "Joined=" + when(1_615_819_200L),
                "Region=Germany (DE)",
                "Language=German (de)",
                "Username last changed=" + when(1_700_000_000L),
                "Display name last changed=" + when(1_690_000_000L),
                "Private account=Yes",
                "Liked videos=Public"
        ), lines(user));
    }

    @Test public void whatTheServerLeftOutSaysSo() {
        // secret and hasOpenFavorite are plain booleans, so left out they read false and say nothing.
        assertEquals(List.of(
                "Joined=Not sent",
                "Region=Not sent",
                "Language=Not sent",
                "Username last changed=Not sent",
                "Display name last changed=Not sent"
        ), lines(new User()));
    }

    @Test public void onlyATrueFlagGetsALine() {
        User user = new User();
        user.isSecret = true;
        List<String> lines = lines(user);
        assertTrue(lines.toString(), lines.contains("Private account=Yes"));
        assertFalse(lines.toString(), lines.stream().anyMatch(line -> line.startsWith("Liked videos=")));

        user.isSecret = false;
        user.hasOpenFavorite = true;
        lines = lines(user);
        assertFalse(lines.toString(), lines.stream().anyMatch(line -> line.startsWith("Private account=")));
        assertTrue(lines.toString(), lines.contains("Liked videos=Public"));
    }

    @Test public void theRegisterTimeStandsInAndADifferentAccountRegionGetsItsOwnLine() {
        User user = new User();
        user.registerTime = 1_500_000_000L;
        user.region = "US";
        user.accountRegion = "gb";
        List<String> lines = lines(user);
        assertEquals("Joined=" + when(1_500_000_000L), lines.get(0));
        assertEquals("Region=United States (US)", lines.get(1));
        assertEquals("Account region=United Kingdom (GB)", lines.get(2));
    }

    @Test public void theSheetCopiesAsOneLabelledLineEach() {
        List<AccountFacts.Fact> facts = List.of(
                new AccountFacts.Fact("Region", "Germany (DE)"),
                new AccountFacts.Fact("Private account", "Yes"));
        assertEquals("Region: Germany (DE)\nPrivate account: Yes", AccountFacts.format(facts));
    }

    @Test public void timesInMillisecondsAreReadAsSeconds() {
        assertNull(AccountFacts.seconds(null));
        assertNull(AccountFacts.seconds(0L));
        assertNull(AccountFacts.seconds(-5));
        assertNull(AccountFacts.seconds("1615819200"));
        assertEquals(Long.valueOf(1_615_819_200L), AccountFacts.seconds(1_615_819_200));
        assertEquals(Long.valueOf(1_615_819_200L), AccountFacts.seconds(1_615_819_200_123L));
    }

    @Test public void anUnknownCodeIsShownAsItCame() {
        assertEquals("ZZ", AccountFacts.country("zz", LOCALE));
        assertEquals("Not sent", AccountFacts.country(null, LOCALE));
    }
}
