/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** Report a problem fills in the bug form from fixed facts, and never grows past its cap. */
public class ProblemReportTest {
    private static ProblemReport.Facts facts(List<String> switchesOn) {
        return new ProblemReport.Facts("0.72.0", "47.1.4", 2024701040L, "samsung", "SM-S901U",
                "15", 35, switchesOn);
    }

    @Test public void theBodyCarriesVersionsPhoneSwitchesThenTheTwoLinesToFillIn() {
        String body = ProblemReport.body(facts(Arrays.asList("Hide ads", "Hide Shop posts")));

        assertEquals("Metra and TikTok: Version 0.72.0 for TikTok 47.1.4 (version code 2024701040)\n"
                + "Phone: samsung SM-S901U, Android 15 (SDK 35)\n\n"
                + "Metra switches that are on (2):\n- Hide ads\n- Hide Shop posts\n\n"
                + "What happened:\n\nWhat I expected:\n", body);
    }

    @Test public void theLinkOpensTheBugFormWithItsFieldsFilledAndEncoded() throws Exception {
        String url = ProblemReport.url(facts(Arrays.asList("Hide ads", "Comments: Hide likes & replies")));

        assertTrue(url, url.startsWith("https://github.com/icysymmetra/tiktok-patches-for-morphe/issues/new?template=bug_report.yml&title=bug%3A%20&"));
        assertTrue(url, url.contains("&versions=Version%200.72.0%20for%20TikTok%2047.1.4%20%28version%20code%202024701040%29&"));
        assertTrue(url, url.contains("&device=samsung%20SM-S901U%2C%20Android%2015%20%28SDK%2035%29&"));
        // #197, #201: a filled What went wrong box came back untouched every time.
        assertFalse(url, url.contains("bug_description"));
        assertFalse("a raw space, newline or plus slipped into the link",
                url.contains(" ") || url.contains("\n") || url.contains("+"));
        String context = url.substring(url.indexOf("&additional_context=") + "&additional_context=".length());
        assertEquals("Metra switches that are on (2):\n- Hide ads\n- Comments: Hide likes & replies",
                URLDecoder.decode(context, "UTF-8"));
    }

    @Test public void aLongSwitchListIsCutToTheCapAndSaysHowManyMore() throws Exception {
        List<String> many = new ArrayList<>();
        for (int index = 1; index <= 1_500; index++) many.add("A fairly long switch title, number " + index);

        String body = ProblemReport.body(facts(many));

        assertTrue("the body is " + body.length() + " characters", body.length() <= ProblemReport.BODY_LIMIT);
        assertTrue("the lines the reporter fills in were cut", body.endsWith("What happened:\n\nWhat I expected:\n"));
        assertTrue(body.contains("Metra switches that are on (1500):\n- A fairly long switch title, number 1\n"));
        int shown = body.split("\n- A fairly long switch title, number ", -1).length - 1;
        assertTrue("nothing was cut, so this proves nothing", shown < many.size());
        assertTrue("the cut doesn't say how many it left out",
                body.contains("\n- and " + (many.size() - shown) + " more\n"));

        String url = ProblemReport.url(facts(many));
        String context = URLDecoder.decode(url.substring(url.indexOf("&additional_context=")
                + "&additional_context=".length()), "UTF-8");
        assertTrue("the link is " + ProblemReport.signInLength(url) + " characters behind GitHub's sign-in page",
                ProblemReport.signInLength(url) <= ProblemReport.LINK_LIMIT);
        for (String line : context.split("\n")) {
            if (line.startsWith("- A fairly")) assertTrue("the link names a switch the body doesn't: " + line,
                    body.contains(line + "\n"));
        }
        assertTrue("the link's cut doesn't say how many it left out", context.matches("(?s).*\n- and \\d+ more"));
    }

    /**
     * Encoding grows the text, and GitHub answers 414 URI Too Long past about 8,200 characters. A
     * body under its cap made a link GitHub refused, worse for a reporter sent through the sign-in
     * page, which encodes the whole link again. The real switch titles are short, so this list
     * fits the body whole and only the link has to cut it.
     */
    @Test public void aListThatFitsTheBodyIsStillCutForTheLink() throws Exception {
        List<String> titles = new ArrayList<>();
        for (int index = 1; index <= 160; index++) titles.add("Hide TikTok's thing number " + index);

        String body = ProblemReport.body(facts(titles));
        assertFalse("the body cut the list, so this proves nothing", body.contains(" more\n"));

        String url = ProblemReport.url(facts(titles));
        assertTrue("the link is " + ProblemReport.signInLength(url) + " characters behind GitHub's sign-in page",
                ProblemReport.signInLength(url) <= ProblemReport.LINK_LIMIT);
        String context = URLDecoder.decode(url.substring(url.indexOf("&additional_context=")
                + "&additional_context=".length()), "UTF-8");
        assertTrue(context, context.startsWith("Metra switches that are on (160):\n- Hide TikTok's thing number 1\n"));
        assertTrue("the link's cut doesn't say how many it left out", context.matches("(?s).*\n- and \\d+ more"));
    }

    /**
     * A list that fits the link whole goes in whole, however close to the limit it is, also when
     * its last titles have no spaces and so encode shorter than the "- and 1 more" a cut would
     * put in their place. The cut is searched for by length, and a cut that came out longer than
     * the whole list could stop that search short of it.
     */
    @Test public void aListThatJustFitsTheLinkGoesInWhole() throws Exception {
        String empty = ProblemReport.url(facts(new ArrayList<>()));
        String start = empty.substring(0, empty.indexOf("&additional_context=") + "&additional_context=".length());
        int tried = 0;
        for (int longOnes = 100; longOnes <= 160; longOnes++) {
            for (int pad = 0; pad < 10; pad++) {
                List<String> titles = new ArrayList<>();
                // A first title of a different length moves where the whole list ends.
                titles.add("Hide" + "x".repeat(pad));
                for (int index = 1; index <= longOnes; index++) titles.add("Hide TikTok's thing number " + index);
                // Short titles until one more would pass the limit, so the whole list fits by
                // less than one short line.
                for (int index = 1; wholeLinkLength(start, titles) <= ProblemReport.LINK_LIMIT; index++) {
                    titles.add("Mute" + index);
                }
                titles.remove(titles.size() - 1);
                if (wholeLinkLength(start, titles) > ProblemReport.LINK_LIMIT) continue;
                if (ProblemReport.body(facts(titles)).contains(" more\n")) continue;
                tried++;

                String url = ProblemReport.url(facts(titles));
                String context = URLDecoder.decode(url.substring(url.indexOf("&additional_context=")
                        + "&additional_context=".length()), "UTF-8");
                assertEquals("with " + longOnes + " long titles and pad " + pad + " the list that fits was cut",
                        ProblemReport.switches(facts(titles), Integer.MAX_VALUE), context);
            }
        }
        assertTrue("only " + tried + " lists fit the link, so this proves little", tried >= 100);
    }

    private static int wholeLinkLength(String start, List<String> titles) {
        return ProblemReport.signInLength(start + ProblemReport.encode(
                ProblemReport.switches(facts(titles), Integer.MAX_VALUE)));
    }

    /** The S22's 24 switches, 2026-10-13: a short list goes into the link whole. */
    @Test public void aShortListGoesIntoTheLinkWhole() throws Exception {
        List<String> titles = new ArrayList<>();
        for (int index = 1; index <= 24; index++) titles.add("Show the progress bar thumbnail " + index);

        String url = ProblemReport.url(facts(titles));
        String context = URLDecoder.decode(url.substring(url.indexOf("&additional_context=")
                + "&additional_context=".length()), "UTF-8");
        assertTrue(context, context.endsWith("\n- Show the progress bar thumbnail 24"));
    }

    /** The update block's version code is the highest there is, which tells a reader nothing. */
    @Test public void theRaisedVersionCodeSaysStoreUpdatesAreBlockedInstead() {
        String body = ProblemReport.body(new ProblemReport.Facts("0.72.0", "47.1.4", Integer.MAX_VALUE,
                "samsung", "SM-S908U1", "16", 36, null));

        assertTrue(body, body.startsWith(
                "Metra and TikTok: Version 0.72.0 for TikTok 47.1.4 (store updates blocked)\n"));
        assertFalse(body, body.contains(String.valueOf(Integer.MAX_VALUE)));
    }

    @Test public void noSwitchesOnSaysSoAndMissingFactsReadUnknown() {
        String body = ProblemReport.body(new ProblemReport.Facts("", null, -1, "Google", "Pixel 8",
                "16", 36, null));

        assertTrue(body, body.startsWith("Metra and TikTok: Version unknown for TikTok unknown\n"
                + "Phone: Google Pixel 8, Android 16 (SDK 36)\n\n"
                + "Metra switches that are on: none\n\n"));
    }
}
