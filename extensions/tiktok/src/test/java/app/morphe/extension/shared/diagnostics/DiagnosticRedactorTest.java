/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.shared.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.tiktok.blockauthor.VideoAuthor;

import org.junit.Test;

/**
 * A creator's name has no shape a pattern can find, and the toast that carries it is written to
 * the buffer in whichever language the phone speaks. The bundle marks the name instead, with the
 * isolate pair {@code VideoAuthor.label()} wraps it in, and the redactor finds the marks.
 */
public class DiagnosticRedactorTest {

    private static final String FSI = "⁨";
    private static final String PDI = "⁩";

    /** Android's connect failure names the phone's own address, routable on an IPv6 network. */
    @Test public void aConnectFailureLosesBothAddressesAndKeepsItsShape() {
        String line = DiagnosticRedactor.redact("failed to connect to cdn.example/203.0.113.9 (port 443)"
                + " from /2607:fb90:8a2c:1d4e:5c1f:9b2a:77e3:41d0 (port 38754) after 15000ms;"
                + " retry via /fe80::1%wlan0 (port 443)");

        assertEquals("failed to connect to cdn.example/[address omitted] (port 443)"
                + " from /[address omitted] (port 38754) after 15000ms;"
                + " retry via /[address omitted] (port 443)", line);
    }

    @Test public void clockTimesAndVersionsAreNotTakenForAddresses() {
        String line = "10:23:11.269 Metra 0.63.0 for TikTok 47.1.3, Logger::printInfo";

        assertEquals(line, DiagnosticRedactor.redact(line));
    }

    /** A saved file's name glues the video's id to the date with an underscore. */
    @Test public void anIdGluedToAnUnderscoreIsStillAnId() {
        assertEquals("file=dana_2026-09-01_[id omitted].mp4",
                DiagnosticRedactor.redact("file=dana_2026-09-01_7412345678901234567.mp4"));
    }

    @Test public void theUsAndEuServiceHostsAreOmittedToo() {
        assertEquals("Unable to resolve host \"[host omitted]\" and [host omitted]",
                DiagnosticRedactor.redact("Unable to resolve host \"v16m.tiktokcdn-us.com\""
                        + " and api16-normal-useast5.tiktokv.us"));
    }

    @Test public void theToastLineLosesTheNameAndKeepsTheAction() {
        String line = DiagnosticRedactor.redact("Showing toast: Blocked " + FSI + "Dana Q" + PDI);

        assertEquals("Showing toast: Blocked [name omitted]", line);
    }

    @Test public void theNameIsFoundByItsMarksInAnyLanguage() {
        String german = DiagnosticRedactor.redact(
                "Showing toast: " + FSI + "Dana Q" + PDI + " wird nicht mehr angezeigt");

        assertFalse("a name in a translated toast survived: " + german, german.contains("Dana Q"));
        assertTrue("the words around the name were lost: " + german,
                german.contains("[name omitted] wird nicht mehr angezeigt"));
    }

    @Test public void aHandleOrAnIdInsideTheMarksGoesWithThem() {
        String handle = DiagnosticRedactor.redact("Hidden " + FSI + "@dana.q" + PDI + " locally");
        String secUid = DiagnosticRedactor.redact(
                "Unblocked " + FSI + "MS4wLjABAAAA_ynkoP2m" + PDI);

        assertEquals("Hidden [name omitted] locally", handle);
        assertEquals("Unblocked [name omitted]", secUid);
    }

    @Test public void aNameThatLostItsClosingMarkIsCutToTheEndOfItsLine() {
        String text = DiagnosticRedactor.redact("Hidden " + FSI + "Dana Q locally\nnext line");

        assertEquals("Hidden [name omitted]\nnext line", text);
    }

    /**
     * A display name is whatever the account chose, so it can carry a line break or a copy of
     * the marks themselves. Either one used to split the run and leave the rest of the name in
     * the report. The label takes both out, so what arrives here is one run.
     */
    @Test public void aNameCarryingABreakOrTheMarksThemselvesIsStillOneRun() {
        String broken = new VideoAuthor(null, null, "Dana\nSecretHandle", null).label();
        String nested = new VideoAuthor(null, null, "A" + PDI + "B" + FSI + "C", null).label();

        assertEquals("Showing toast: Blocked [name omitted]",
                DiagnosticRedactor.redact("Showing toast: Blocked " + broken));
        assertEquals("Showing toast: Blocked [name omitted]",
                DiagnosticRedactor.redact("Showing toast: Blocked " + nested));
        assertFalse("half the name survived the break: " + broken,
                DiagnosticRedactor.redact(broken).contains("SecretHandle"));
        assertFalse("the middle of the name survived its own marks: " + nested,
                DiagnosticRedactor.redact(nested).contains("B"));
    }

    /**
     * The control for the pair above. Without the flattening the same two names leak, which is
     * what makes the flattening the thing being tested rather than the pattern.
     */
    @Test public void theSameTwoNamesLeakWhenTheyAreNotFlattenedFirst() {
        String rawBreak = FSI + "Dana\nSecretHandle" + PDI;
        String rawNested = FSI + "A" + PDI + "B" + FSI + "C" + PDI;

        assertTrue(DiagnosticRedactor.redact(rawBreak).contains("SecretHandle"));
        assertTrue(DiagnosticRedactor.redact(rawNested).contains("B"));
    }

    @Test public void anAccountWithNothingToNameIsNotHiddenAtAll() {
        // "this creator" names nobody, so hiding it says less than the truth.
        assertEquals("Blocked this creator",
                DiagnosticRedactor.redact("Blocked "
                        + new VideoAuthor(null, null, null, null).label()));
    }

    @Test public void aHandleOnItsOwnGoesWhileAnEmailAndAnObjectHashStay() {
        String text = DiagnosticRedactor.redact(
                "@dana.q wrote to dana@example.com from Foo@1a2b3c and mentioned @dana_q2");

        assertEquals("[handle omitted] wrote to dana@example.com from Foo@1a2b3c"
                + " and mentioned [handle omitted]", text);
    }

    @Test public void theCreatorReferenceLineKeepsThePseudonymAndDropsTheVideoId() {
        String line = DiagnosticRedactor.redact(
                "Current video: creator 3f9a2c1b0e7d aweme=7012345678901234567");

        assertTrue("the pseudonym is what makes two lines comparable: " + line,
                line.contains("creator 3f9a2c1b0e7d"));
        assertFalse("the video id names what was watched: " + line,
                line.contains("7012345678901234567"));
    }

    /** The mutation control: a line with no name, handle or id is returned as it came. */
    @Test public void aLineWithNothingToHideIsLeftAlone() {
        String line = "Sending block_type=1 for creator 3f9a2c1b0e7d";

        assertEquals(line, DiagnosticRedactor.redact(line));
    }

    @Test public void quotedJsonCredentialsAndNamedIdsAreRemoved() {
        String text = DiagnosticRedactor.redact(
                "{\"sessionid\":\"JSON_SESSION_SENTINEL\","
                        + "\"nested\":{\"access_token\":\"JSON_TOKEN_SENTINEL\"},"
                        + "\"device_id\":\"JSON_DEVICE_SENTINEL\","
                        + "\"aweme_id\":\"JSON_POST_SENTINEL\",\"status\":\"ready\"}");

        for (String secret : new String[]{"JSON_SESSION_SENTINEL", "JSON_TOKEN_SENTINEL",
                "JSON_DEVICE_SENTINEL", "JSON_POST_SENTINEL"}) {
            assertFalse("A structured value survived: " + secret, text.contains(secret));
        }
        assertTrue("Useful non-sensitive fields disappeared", text.contains("ready"));
    }

    @Test public void escapedJsonAndSpacesInsideQuotedValuesDoNotExposeCredentials() {
        String escaped = DiagnosticRedactor.redact(
                "payload={\\\"sessionid\\\":\\\"ESCAPED_SENTINEL\\\"}");
        String quoted = DiagnosticRedactor.redact(
                "{\"password\":\"FIRST_SENTINEL SECOND_SENTINEL\",\"retry\":2}");

        assertFalse(escaped.contains("ESCAPED_SENTINEL"));
        assertFalse(quoted.contains("FIRST_SENTINEL"));
        assertFalse(quoted.contains("SECOND_SENTINEL"));
        assertTrue(quoted.contains("retry"));
    }

    @Test public void authenticationAndCookieHeadersLoseTheirEntireValues() {
        String text = DiagnosticRedactor.redact(
                "Authorization: Bearer AUTH_SENTINEL\r\n"
                        + "Cookie: locale=en; arbitrary=COOKIE_SENTINEL\r\n"
                        + "status: ready\n");

        assertFalse(text.contains("AUTH_SENTINEL"));
        assertFalse(text.contains("COOKIE_SENTINEL"));
        assertTrue("The following diagnostic line was lost", text.contains("status: ready"));
    }

    @Test public void plaintextCredentialTokensDoNotLeaveSuffixesAfterBrackets() {
        for (String value : new String[]{"prefix]PLAIN_SENTINEL", "prefix}PLAIN_SENTINEL",
                "[opaque]PLAIN_SENTINEL", "{opaque}PLAIN_SENTINEL", "[omitted]PLAIN_SENTINEL"}) {
            String text = DiagnosticRedactor.redact("sessionid=" + value + " phase=ready");
            assertFalse("An opaque credential was split at a bracket: " + text,
                    text.contains("PLAIN_SENTINEL"));
            assertTrue(text.contains("phase=ready"));
        }
        String once = DiagnosticRedactor.redact("sessionid=ordinary-token phase=ready");
        assertEquals("Repeated sink redaction should preserve its own marker",
                once, DiagnosticRedactor.redact(once));
    }

    @Test public void headersCannotUseStructuredPrefixesToLeaveCredentialTails() {
        for (String header : new String[]{"Authorization: [omitted] HEADER_SENTINEL",
                "Authorization: {scheme=Bearer} HEADER_SENTINEL",
                "Cookie: \"already-masked\"; arbitrary=HEADER_SENTINEL"}) {
            String text = DiagnosticRedactor.redact(header + "\nstatus: ready");
            assertFalse("A header kept a suffix after a structured prefix: " + text,
                    text.contains("HEADER_SENTINEL"));
            assertTrue("The next diagnostic line was lost", text.contains("status: ready"));
        }
        String json = DiagnosticRedactor.redact(
                "{\"Authorization\":\"Bearer JSON_HEADER_SENTINEL\",\"status\":\"ready\"}");
        assertFalse(json.contains("JSON_HEADER_SENTINEL"));
        assertTrue("A JSON field is not a whole HTTP header line", json.contains("ready"));
    }

    @Test public void anUnterminatedQuotedCredentialStillHidesItsRemainingValue() {
        String text = DiagnosticRedactor.redact(
                "phase=decode\n{\"sessionid\":\"BROKEN_SENTINEL SECOND_SENTINEL");

        assertFalse(text.contains("BROKEN_SENTINEL"));
        assertFalse(text.contains("SECOND_SENTINEL"));
        assertTrue(text.contains("phase=decode"));
    }

    @Test(timeout = 5000) public void aLongDottedHostIsRedactedWithoutRecursiveMatching() {
        String text = DiagnosticRedactor.redact("version=47.1.3\n"
                + "a.".repeat(4096) + "tiktokv.com/path?sessionid=DOTTED_SENTINEL\n"
                + "completed diagnostic step");

        assertFalse(text.contains("tiktokv.com"));
        assertFalse(text.contains("DOTTED_SENTINEL"));
        assertTrue(text.contains("version=47.1.3"));
        assertTrue(text.contains("completed diagnostic step"));
    }

    @Test(timeout = 5000) public void oversizedDiagnosticInputIsBoundedAndSaysItWasTruncated() {
        String input = "version=47.1.3\nsessionid=LARGE_SENTINEL\n"
                + "ordinary diagnostic detail\n".repeat(80_000);
        String text = DiagnosticRedactor.redact(input);

        assertTrue("A single diagnostic input retained more than the whole buffer's ceiling",
                text.length() <= 250_000);
        assertTrue("The reader cannot tell context was omitted",
                text.toLowerCase(java.util.Locale.ROOT).contains("truncated"));
        assertTrue(text.contains("version=47.1.3"));
        assertFalse(text.contains("LARGE_SENTINEL"));
    }
}
