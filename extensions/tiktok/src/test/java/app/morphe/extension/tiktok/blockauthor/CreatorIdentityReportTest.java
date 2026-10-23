/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.blockauthor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.os.Looper;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.follow.FollowDiagnostics;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

/**
 * The bug report form asks for the exported report, and the report is attached in public. The
 * block, hide and follow paths all name the creator somewhere: the toast the reader sees, and
 * before this the log line beside it. What the reader sees keeps the name. What leaves the
 * phone does not.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CreatorIdentityReportTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String UID = "6812345678901234567";
    private static final String SEC_UID = "MS4wLjABAAAA_ynkoP2mQ7tX";
    private static final String NAME = "Dana Q";
    private static final VideoAuthor DANA = new VideoAuthor(UID, SEC_UID, NAME, "7012345678901234567");

    private boolean previousDebug;
    private String previousFilters;
    private String previousSalt;

    @Before public void setUp() {
        previousDebug = BaseSettings.DEBUG.get();
        previousFilters = BaseSettings.DEBUG_LOG_FILTERS.get();
        previousSalt = Settings.DIAGNOSTIC_REPORT_SALT.get();
        BaseSettings.DEBUG.save(true);
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        Settings.DIAGNOSTIC_REPORT_SALT.save("test-install-key-that-is-not-exported");
        ReflectionHelpers.setStaticField(FollowDiagnostics.class, "salt", null);
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    @After public void tearDown() {
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
        ReflectionHelpers.setStaticField(FollowDiagnostics.class, "salt", null);
        Settings.DIAGNOSTIC_REPORT_SALT.save(previousSalt);
        BaseSettings.DEBUG_LOG_FILTERS.save(previousFilters);
        BaseSettings.DEBUG.save(previousDebug);
    }

    @Test public void theReferenceNamesNobodyAndStaysTheSameForOneAccount() {
        String reference = DANA.reference();

        assertTrue("a reference reads as a creator, not as a value: " + reference,
                reference.startsWith("creator "));
        assertFalse(reference.contains(NAME));
        assertFalse(reference.contains(UID));
        assertFalse(reference.contains(SEC_UID));
        assertEquals("one account read differently twice", reference, DANA.reference());
        assertNotEquals("two accounts read the same",
                reference, new VideoAuthor("7098765432109876543", null, "Someone", null).reference());
        assertEquals("creator unnamed", new VideoAuthor(null, "", "Nobody", null).reference());
    }

    @Test public void theReferenceIsTheFollowReportsPseudonymForTheSameAccount() {
        String reference = DANA.reference();

        assertTrue("a creator blocked and a creator followed should read as one account: "
                        + reference,
                FollowDiagnostics.pseudonym(UID).startsWith(reference.substring("creator ".length())));
    }

    /**
     * Driven through the methods the app calls, not through hand-written copies of what they
     * say. A test that builds the toast text itself proves the redactor and nothing about the
     * call sites: putting {@code label()} back into any of these log lines would leave it green.
     */
    @Test public void theExportAfterBlockingUnblockingAndFollowingNamesNobody() {
        try (var owner = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            android.app.Activity activity = owner.get();
            Utils.setContext(activity);
            Utils.setActivity(activity);

            // The feed naming the video on screen, which is what writes the author log line.
            CurrentVideoAuthor.update(new Params(new Clip(DANA.awemeId, UID, SEC_UID, NAME)));
            CurrentVideoAuthor.onPlaying(DANA.awemeId);

            BlockAuthorOverlay.reportBlockResult(DANA, BlockAuthorService.Result.REJECTED);
            BlockAuthorOverlay.reportUnblockResult(DANA, BlockAuthorService.Result.CONFIRMED);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            FollowDiagnostics.logCommonFollowRequest(1, 0, 0, 0, UID, SEC_UID, null, null, null, null);

            // The control: the reader was told which account it was.
            assertTrue("the reader was not told who the message is about: "
                            + ShadowToast.getTextOfLatestToast(),
                    String.valueOf(ShadowToast.getTextOfLatestToast()).contains(NAME));

            String report = LogBufferManager.buildExportText();
            assertTrue("the block and unblock messages are not in the report at all: " + report,
                    report.contains("[name omitted]"));
            assertFalse("the creator's name left the phone: " + report, report.contains(NAME));
            assertFalse("the creator's uid left the phone: " + report, report.contains(UID));
            assertFalse("the creator's secUid left the phone: " + report, report.contains(SEC_UID));
            assertFalse("the video id left the phone: " + report, report.contains(DANA.awemeId));
            assertTrue("the author line lost the pseudonym that ties it to the follow line: "
                    + report, report.contains(DANA.reference()));
        } finally {
            Utils.setActivity(null);
            CurrentVideoAuthor.resetForTests();
        }
    }

    /** Stands in for VideoItemParams, read by the same getters the app's own model exposes. */
    public static final class Params {
        public final Clip aweme;
        Params(Clip aweme) { this.aweme = aweme; }
    }

    public static final class Clip {
        public final String aid;
        public final Author author;
        Clip(String aid, String uid, String secUid, String name) {
            this.aid = aid;
            this.author = new Author(uid, secUid, name);
        }
    }

    public static final class Author {
        public final String uid;
        public final String secUid;
        public final String uniqueId;
        Author(String uid, String secUid, String uniqueId) {
            this.uid = uid;
            this.secUid = secUid;
            this.uniqueId = uniqueId;
        }
    }

    /** The mutation control: the isolate pair is what the export finds the name by. */
    @Test public void aLabelWithoutTheIsolatePairWouldLeaveThePhone() {
        String bare = "Showing toast: Blocked " + NAME;
        String marked = "Showing toast: Blocked " + DANA.label();

        assertTrue(app.morphe.extension.shared.diagnostics.DiagnosticRedactor.redact(bare).contains(NAME));
        assertFalse(app.morphe.extension.shared.diagnostics.DiagnosticRedactor.redact(marked).contains(NAME));
    }
}
