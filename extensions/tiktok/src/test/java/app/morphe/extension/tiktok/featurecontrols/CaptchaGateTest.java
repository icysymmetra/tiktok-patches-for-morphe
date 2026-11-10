/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.featurecontrols;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.shadows.ShadowLog;

/**
 * A risk check is classified by the request it gates. A check that arrives over a follow,
 * like, comment, repost or story action must survive the hide setting, because hiding it is
 * what makes those actions fail with no message.
 *
 * The gate owns a small process-wide queue, so each test clears that state before exercising
 * one decision. The clock remains injectable through the package-private overloads below.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CaptchaGateTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class SignedInAccount {
        public boolean isLogin() { return true; }
    }

    public static final class ThrowingScene {
        public String LJIIJ() { throw new IllegalStateException("unreadable scene"); }
    }

    public static final class WrongTypeScene {
        public int LJIIJ() { return 1; }
    }

    /** Stands in for a retrofit request. The gate reads the path off it reflectively. */
    public static final class Request {
        private final String path;

        public Request(String path) {
            this.path = path;
        }

        public String getPath() {
            return path;
        }
    }

    /** Stands in for the oecverify request, whose LJIIJ getter names the scene. */
    public static final class VerifyRequest {
        private final String scene;

        public VerifyRequest(String scene) {
            this.scene = scene;
        }

        public String LJIIJ() {
            return scene;
        }
    }

    private long now;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        HookStatus.clear();
        CaptchaGate.resetForTests();
        PausedProcess.set(false);
        Settings.HIDE_CAPTCHA_POPUPS.save(true);
        now = CaptchaGate.now();
    }

    @After
    public void tearDown() {
        CaptchaGate.resetForTests();
        PausedProcess.set(false);
        HookStatus.clear();
    }

    /** A clock past every write recorded up to now, whatever ran before this test. */
    private long afterEveryWrite() {
        return now + CaptchaGate.WRITE_WINDOW_MS + 1_000L;
    }

    @Test
    public void everyWriteActionIsRecognisedByItsPath() {
        assertEquals("follow", CaptchaGate.writeActionFor("/aweme/v1/commit/follow/user/"));
        assertEquals("follow", CaptchaGate.writeActionFor("/aweme/v3/f2f/follow/"));
        assertEquals("follow", CaptchaGate.writeActionFor("/aweme/v1/commit/follow/batchuser/"));
        // A story like is a like on the story's own Aweme, so it uses the ordinary digg path.
        assertEquals("like", CaptchaGate.writeActionFor("/aweme/v1/commit/item/digg/"));
        assertEquals("comment", CaptchaGate.writeActionFor("/aweme/v1/comment/publish/"));
        assertEquals("comment", CaptchaGate.writeActionFor("/webcast/room/chat/"));
        assertEquals("repost", CaptchaGate.writeActionFor("/tiktok/v1/upvote/publish/"));
        assertEquals("story", CaptchaGate.writeActionFor("/tiktok/story/maf/mute"));
    }

    @Test
    public void aRepostCommentIsACommentNotARepost() {
        // publish_comment contains upvote/publish, so the order the paths are tested matters.
        assertEquals("comment", CaptchaGate.writeActionFor("/tiktok/v1/upvote/publish_comment/"));
    }

    @Test
    public void browsingPathsGateNothing() {
        assertNull(CaptchaGate.writeActionFor("/aweme/v1/feed/"));
        assertNull(CaptchaGate.writeActionFor("/tiktok/story/view/report/v1"));
        assertNull(CaptchaGate.writeActionFor("/aweme/v2/comment/list/"));
        assertNull(CaptchaGate.writeActionFor("/aweme/v1/user/following/list/"));
        assertNull(CaptchaGate.writeActionFor(null));
    }

    @Test
    public void aCheckOverAWriteIsShownEvenWithTheSettingOn() {
        CaptchaGate.recordRequest(new Request("/aweme/v1/commit/follow/user/"));

        assertEquals("follow", CaptchaGate.pendingWriteAction());
        assertEquals("it gates a follow",
                CaptchaGate.showReason(null, "{\"subtype\":\"slide\"}", now));
    }

    @Test
    public void aBrowsingRequestArmsNothing() {
        // Order independent: whatever an earlier case left pending, a browsing request must
        // not change it. A path wrongly classified as a write would.
        String before = CaptchaGate.pendingWriteAction(now);
        CaptchaGate.recordRequest(new Request("/aweme/v1/feed/"));
        assertEquals(before, CaptchaGate.pendingWriteAction(now));

        CaptchaGate.recordRequest(new Request("/aweme/v2/comment/list/"));
        assertEquals(before, CaptchaGate.pendingWriteAction(now));
    }

    @Test
    public void aCheckWhileBrowsingIsNotAttributedToAWrite() {
        CaptchaGate.recordRequest(new Request("/aweme/v1/feed/"));

        long clock = afterEveryWrite();
        assertNull(CaptchaGate.pendingWriteAction(clock));

        String reason = CaptchaGate.showReason(null, "{\"subtype\":\"slide\"}", clock);
        // Whatever else stops it, a browsing check is never held back by a write.
        assertTrue(String.valueOf(reason), reason == null || !reason.startsWith("it gates"));
    }

    @Test
    public void aWriteStopsGatingChecksOnceItsWindowPasses() {
        CaptchaGate.recordRequest(new Request("/aweme/v1/commit/item/digg/"));

        assertEquals("like", CaptchaGate.pendingWriteAction(now));
        assertEquals("like", CaptchaGate.pendingWriteAction(now + CaptchaGate.WRITE_WINDOW_MS));
        assertNull(CaptchaGate.pendingWriteAction(afterEveryWrite()));
    }

    @Test
    public void theSettingBeingOffIsTheFirstAnswer() {
        Settings.HIDE_CAPTCHA_POPUPS.save(false);
        CaptchaGate.recordRequest(new Request("/aweme/v1/commit/follow/user/"));

        assertEquals("the setting is off", CaptchaGate.showReason(null, null, now));
    }

    @Test
    public void accountVerificationIsNeverHidden() {
        assertEquals("it is account verification",
                CaptchaGate.showReason(null, "{\"scene\":\"/passport/mobile/check\"}", afterEveryWrite()));
    }

    /**
     * The export tells three states apart: the gate is in the build, it has seen a write, and
     * a check has reached it.
     *
     * <p>A report of a comment that went nowhere carried no CAPTCHA line at all, so nobody
     * could say whether Hushfeed had seen the publish request, whether a puzzle had been
     * raised, or whether the gate was even in that build. A family that is simply absent says
     * all three.
     */
    @Test
    public void theExportSaysWhetherTheGateSawAWriteAndACheck() {
        assertFalse("the family is in the export before the settings load",
                String.join(" ", HookStatus.report()).contains("CAPTCHA account state"));

        app.morphe.extension.tiktok.settings.SettingsStatus.enableCaptchaPopupSuppression();
        assertTrue(HookStatus.report().toString(), String.join(" ", HookStatus.report())
                .contains("CAPTCHA account state: 1 found, 0 missing"));

        CaptchaGate.recordRequest(new Request("/aweme/v1/comment/publish/"));
        CaptchaGate.recordRequest(new Request("/aweme/v1/comment/publish/"));
        assertTrue("a write the recorder saw is not in the export: " + HookStatus.report(),
                String.join(" ", HookStatus.report())
                        .contains("CAPTCHA account state: 2 found, 0 missing"));

        // A read is not a write, so it changes nothing.
        CaptchaGate.recordRequest(new Request("/aweme/v1/comment/list/"));
        assertTrue(String.join(" ", HookStatus.report())
                .contains("CAPTCHA account state: 2 found, 0 missing"));

        assertFalse(CaptchaGate.shouldHide(null, "risk slide", "{\"subtype\":\"slide\"}"));
        assertTrue("a check that reached the gate is not in the export: " + HookStatus.report(),
                String.join(" ", HookStatus.report())
                        .contains("CAPTCHA account state: 3 found, 0 missing"));

        // A diagnostic clear drops every entry. The next write puts the family back, with
        // itself, or an export taken after a clear would say this build has no gate.
        HookStatus.clear();
        assertFalse(String.join(" ", HookStatus.report()).contains("CAPTCHA account state"));
        CaptchaGate.recordRequest(new Request("/aweme/v1/comment/publish/"));
        assertTrue("the family did not come back after a clear: " + HookStatus.report(),
                String.join(" ", HookStatus.report())
                        .contains("CAPTCHA account state: 2 found, 0 missing"));
    }

    @Test
    public void aRenamedAccountServiceIsVisibleInHookStatus() {
        assertEquals("no account is signed in",
                CaptchaGate.showReason(null, "{\"subtype\":\"slide\"}", afterEveryWrite()));

        java.util.List<String> missing = HookStatus.missing("CAPTCHA account state");
        assertEquals(missing.toString(), 1, missing.size());
        assertTrue(missing.get(0), missing.get(0).contains("ServiceManager"));
    }

    @Test
    public void aCheckWithAReasonToBeShownIsNotHidden() {
        // shouldHide is what every hook calls. Whenever showReason has an answer, the puzzle
        // has to reach the user; only a null reason may suppress one.
        CaptchaGate.recordRequest(new Request("/aweme/v1/commit/follow/user/"));

        assertFalse(CaptchaGate.shouldHide(null, "risk slide", "{\"subtype\":\"slide\"}"));
        assertFalse(CaptchaGate.shouldHideCaptchaPopup(null, "{\"subtype\":\"slide\"}"));
        assertFalse(CaptchaGate.shouldHideLegacyCaptchaPopup(null, 2148));
        assertFalse(CaptchaGate.shouldHideTuringCaptchaPopup(null, "common_verify"));
    }

    @Test
    public void aVerificationRequestThatNamesNoSceneIsShown() {
        // Without a scene there is no way to tell an account check from a browsing one.
        assertFalse(CaptchaGate.shouldHideOecCaptchaPopup(new VerifyRequest(null)));
        assertFalse(CaptchaGate.shouldHideOecCaptchaPopup(new Object()));
        assertFalse(CaptchaGate.shouldHideTuringDialog(null, null));
    }

    @Test
    public void smsAndTwoFactorChecksAreNeverHidden() {
        assertFalse(CaptchaGate.shouldHideTuringCaptchaPopup(null, "sms"));
        assertFalse(CaptchaGate.shouldHideTuringCaptchaPopup(null, "twice_verify"));
    }

    /**
     * Signed in, with no write of the reader's in its window and nothing naming an account
     * screen, a puzzle was raised while browsing, and every hook but TikTok Shop's hides it
     * while the switch is on. The first hidden one says so on screen.
     */
    @Test
    public void aSignedInBrowsingPuzzleIsHiddenByEveryHook() {
        CaptchaGate.accountServiceForTests = new SignedInAccount();
        String[] details = {"{", "unknown", "slide", "slide_captcha", "common_verify",
                "{\"subtype\":\"slide\"}", "{\"scene\":\"feed\"}", "{\"verify_way\":\"slide\"}"};
        ShadowToast.reset();
        for (String detail : details) {
            ShadowLog.clear();
            assertNull(detail, CaptchaGate.showReason(null, detail, afterEveryWrite()));
            assertTrue(detail, CaptchaGate.shouldHideCaptchaPopup(null, detail));
            assertTrue(detail, CaptchaGate.shouldHideTuringDialog(null, new VerifyRequest(detail)));
            assertTrue(detail, CaptchaGate.shouldHideTuringCaptchaPopup(null, detail));
            assertTrue(ShadowLog.getLogs().stream().anyMatch(log -> log.msg.contains("Hid risk check")));
        }
        // popCaptchaV2's risk info and popCaptcha's error code describe nothing account-shaped
        // when they're missing, the same as onlynazril's rule.
        for (String detail : new String[]{null, "", " "}) {
            assertTrue(CaptchaGate.shouldHideCaptchaPopup(null, detail));
        }
        assertTrue(CaptchaGate.shouldHideLegacyCaptchaPopup(null, 2148));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(CaptchaGate.recentlySuppressedCheckId());
        assertEquals("one notice for the session", 1, ShadowToast.shownToastCount());
    }

    /** Sign-in, SMS and two-step named anywhere in the check, inside JSON or a longer scene. */
    @Test
    public void anAccountWordAnywhereInTheCheckKeepsItVisible() {
        CaptchaGate.accountServiceForTests = new SignedInAccount();
        for (String detail : new String[]{"{\"verify_way\":\"sms\"}", "sms_verify",
                "{\"subtype\":\"mobile_verify\"}", "twice_verify_v2", "{\"scene\":\"passport_bind\"}",
                "email_otp", "{\"scene\":\"Login\"}"}) {
            assertEquals(detail, "it is account verification",
                    CaptchaGate.showReason(null, detail, afterEveryWrite()));
            assertFalse(detail, CaptchaGate.shouldHideCaptchaPopup(null, detail));
            assertFalse(detail, CaptchaGate.shouldHideTuringDialog(null, new VerifyRequest(detail)));
            assertFalse(detail, CaptchaGate.shouldHideTuringCaptchaPopup(null, detail));
        }
        assertNull(CaptchaGate.recentlySuppressedCheckId());
    }

    /** TikTok Shop's verification hands over no screen to judge, so it always shows. */
    @Test
    public void shopVerificationIsAlwaysShown() {
        CaptchaGate.accountServiceForTests = new SignedInAccount();
        for (String scene : new String[]{"feed", "slide", "{\"subtype\":\"slide\"}"}) {
            ShadowLog.clear();
            assertFalse(scene, CaptchaGate.shouldHideOecCaptchaPopup(new VerifyRequest(scene)));
            assertTrue(ShadowLog.getLogs().stream().anyMatch(log ->
                    log.msg.contains("Showing risk check") && log.msg.contains("TikTok Shop")));
        }
        assertNull(CaptchaGate.recentlySuppressedCheckId());
    }

    /**
     * The other actions a hidden puzzle would quietly fail: saving, editing the profile,
     * posting, messaging, gifts, Shop and account changes. Their paths are 47.1.4's. Follow
     * diagnostics shares writeActionFor, so these stay out of it.
     */
    @Test
    public void everyOtherWriteHoldsTheWindow() {
        String[][] cases = {
                {"/aweme/v1/aweme/collect/", "save"},
                {"/aweme/v1/user/music/collect/", "save"},
                {"/aweme/v1/commit/user/", "profile edit"},
                {"/tiktok/privacy/user/settings/update/v2", "profile edit"},
                {"/aweme/v1/create/aweme/", "post"},
                {"/aweme/v2/create/", "post"},
                {"/aweme/v1/aweme/delete/", "post"},
                {"/v1/message/send", "message"},
                {"/v2/conversation/create/", "message"},
                {"/tiktok/v1/gift/send/", "gift"},
                {"/webcast/gift/send/", "gift"},
                {"/api/v1/trade/order/create", "purchase"},
                {"/api/v1/shop/cart/add_item_to_cart", "purchase"},
                {"/payment/v1/pay", "purchase"},
                {"/passport/mobile/send_code/v1/", "sign-in change"},
                {"/passport/user/device_record_status/set/", "sign-in change"},
                {"/passport/user/logout/", "sign-in change"},
                {"/passport/password/change/v2/", "sign-in change"},
                {"/passport/safe/two_step_verification/add_auth_device/v2/", "sign-in change"},
                {"/passport/sotl/app2web/confirm_auth_token/", "sign-in change"},
        };
        for (String[] each : cases) {
            assertEquals(each[0], each[1], CaptchaGate.heldActionFor(each[0]));
            assertNull(each[0], CaptchaGate.writeActionFor(each[0]));
            CaptchaGate.recordRequest(new Request(each[0]));
            assertEquals(each[0], each[1], CaptchaGate.pendingWriteAction());
            assertEquals("it gates a " + each[1], CaptchaGate.showReason(null, "slide", CaptchaGate.now()));
        }
        // The write families still answer through the shared list.
        assertEquals("follow", CaptchaGate.heldActionFor("/aweme/v1/commit/follow/user/"));
    }

    /** Reads and telemetry, including the POSTs that never stop, open nothing. */
    @Test
    public void readsAndTelemetryHoldNoWindow() {
        for (String path : new String[]{"/aweme/v1/feed/", "/aweme/v2/comment/list/",
                "/monitor/collect/", "/service/2/device_sdk/stats_collect/",
                "/aweme/v1/user/collect/count/", "/v1/client/report_metrics",
                "/tiktok/v1/activity_status/report", "/aweme/v1/aweme/listcollection/", null}) {
            assertNull(path, CaptchaGate.heldActionFor(path));
        }
    }

    /**
     * The account calls TikTok sends by itself at every start (47.4.3) open nothing, or the
     * puzzles of a cold start's first 15 seconds would always show.
     */
    @Test
    public void backgroundAccountReadsHoldNoWindow() {
        for (String path : new String[]{"/passport/token/beat/v2/", "/passport/token/beat/",
                "/passport/account/info/v2/", "/passport/user/device_record_status/get/",
                "/passport/app/region/", "/passport/app/region_alert/",
                "/passport/app/store_region/", "/passport/auth/available_ways/",
                "/passport/reauth_settings/state/", "/passport/safe/login_device/list/v2/",
                "/passport/security_checkup/info/", "/passport/user/check_email_registered",
                "/passport/login_name/check/", "/passport/mobile/can_send_voice_code/",
                "/passport/sotl/blobstore/update/", "/passport/sotl/blobstore/get/",
                "/passport/password/has_set/", "/passport/totp/status/",
                "/passport/safe/two_step_verification/get_auth_device_list/",
                "/passport/token/beat/v2/?device_id=1&iid=2"}) {
            assertNull(path, CaptchaGate.heldActionFor(path));
            CaptchaGate.recordRequest(new Request(path));
            assertNull(path, CaptchaGate.pendingWriteAction());
        }
    }

    /** The first write's log line names its path so an export says what opened the window. */
    @Test
    public void theFirstWriteLogNamesItsPathWithoutTheQuery() {
        ShadowLog.clear();
        CaptchaGate.recordRequest(new Request("/passport/mobile/send_code/v1/?device_id=123&iid=456"));
        assertTrue(ShadowLog.getLogs().stream().anyMatch(log ->
                log.msg.contains("first write go out: sign-in change (/passport/mobile/send_code/v1/)")));
        assertFalse(ShadowLog.getLogs().stream().anyMatch(log -> log.msg.contains("device_id")));
    }

    /** The old key's saved true dates from when the row did nothing, so it can't turn this on. */
    @Test
    public void theSwitchHasItsOwnKey() {
        assertEquals("hide_browsing_captcha_popups", Settings.HIDE_CAPTCHA_POPUPS.key);
        assertEquals(Boolean.FALSE, Settings.HIDE_CAPTCHA_POPUPS.defaultValue);
    }

    /** A verification request that names no scene might be the account's own, so it shows. */
    @Test
    public void aSignedInVerificationRequestWithNoReadableSceneIsShown() {
        CaptchaGate.accountServiceForTests = new SignedInAccount();
        for (String scene : new String[]{null, "", " "}) {
            ShadowLog.clear();
            assertFalse(CaptchaGate.shouldHideOecCaptchaPopup(new VerifyRequest(scene)));
            assertFalse(CaptchaGate.shouldHideTuringDialog(null, new VerifyRequest(scene)));
            assertTrue(ShadowLog.getLogs().stream().anyMatch(log ->
                    log.msg.contains("Showing risk check") && log.msg.contains("its scene can't be read")));
        }
        assertNull(CaptchaGate.recentlySuppressedCheckId());
    }

    @Test
    public void unreadableRequestsStillReachTheShownDecisionAndDiagnosticRecord() {
        CaptchaGate.accountServiceForTests = new SignedInAccount();
        Object[] requests = {null, new Object(), new VerifyRequest(null),
                new VerifyRequest(""), new ThrowingScene(), new WrongTypeScene()};
        for (Object request : requests) {
            HookStatus.clear();
            ShadowLog.clear();
            assertFalse(CaptchaGate.shouldHideOecCaptchaPopup(request));
            assertFalse(CaptchaGate.shouldHideTuringDialog(null, request));
            assertTrue(String.join(" ", HookStatus.report())
                    .contains("CAPTCHA account state: 3 found, 0 missing"));
            assertTrue(ShadowLog.getLogs().stream().anyMatch(log ->
                    log.msg.contains("Showing risk check scene unknown")));
            assertNull(CaptchaGate.recentlySuppressedCheckId());
        }
    }

    @Test
    public void signedInAccountAndRecoveryRoutesStayVisible() {
        CaptchaGate.accountServiceForTests = new SignedInAccount();
        for (String detail : new String[]{"login", "passport", "sms", "twice_verify",
                "/passport/mobile/recover", "{\"scene\":\"login\"}"}) {
            assertEquals("it is account verification",
                    CaptchaGate.showReason(null, detail, afterEveryWrite()));
            assertFalse(CaptchaGate.shouldHideCaptchaPopup(null, detail));
            assertFalse(CaptchaGate.shouldHideTuringCaptchaPopup(null, detail));
        }
        try (var owner = Robolectric.buildActivity(android.app.Activity.class).setup()) {
            owner.get().setIntent(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("aweme://account/passport/mobile/recover")));
            assertEquals("it is account verification",
                    CaptchaGate.showReason(owner.get(), "slide", afterEveryWrite()));
            assertFalse(CaptchaGate.shouldHideTuringDialog(owner.get(), new VerifyRequest("slide")));
        }
        assertNull(CaptchaGate.recentlySuppressedCheckId());
    }

    @Test
    public void offAndPauseShowEveryChallengeAndAWriteHoldsItsWindow() {
        CaptchaGate.accountServiceForTests = new SignedInAccount();
        CaptchaGate.recordRequest(new Request("/aweme/v1/commit/item/digg/"));
        assertEquals("it gates a like", CaptchaGate.showReason(null, "slide",
                now + CaptchaGate.WRITE_WINDOW_MS));
        assertNull("once the like's window passes, the puzzle is a browsing one",
                CaptchaGate.showReason(null, "slide", afterEveryWrite()));
        Settings.HIDE_CAPTCHA_POPUPS.save(false);
        assertEquals("the setting is off", CaptchaGate.showReason(null, "slide", now));
        assertFalse(CaptchaGate.shouldHideCaptchaPopup(null, "slide"));
        Settings.HIDE_CAPTCHA_POPUPS.save(true);
        PausedProcess.set(true);
        assertEquals("the setting is off", CaptchaGate.showReason(null, "slide", now));
        assertFalse(CaptchaGate.shouldHideLegacyCaptchaPopup(null, 2148));
        assertNull(CaptchaGate.recentlySuppressedCheckId());
    }

    @Test
    public void aHiddenCheckIsRecordedByItsIdAndSaidOnceOnScreen() {
        ShadowToast.reset();

        CaptchaGate.noteSuppressed(CaptchaGate.checkId("risk", "{\"subtype\":\"slide_captcha\"}"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals("risk slide_captcha", CaptchaGate.recentlySuppressedCheckId());
        int afterFirst = ShadowToast.shownToastCount();
        assertTrue("no toast was shown", afterFirst >= 1);
        assertTrue(String.valueOf(ShadowToast.getTextOfLatestToast()),
                ShadowToast.getTextOfLatestToast().contains("Hide CAPTCHA popups"));

        // The same puzzle repeats on every retry, so only the first one says anything.
        CaptchaGate.noteSuppressed(CaptchaGate.checkId("risk", "{\"subtype\":\"slide_captcha\"}"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(afterFirst, ShadowToast.shownToastCount());
    }

    @Test
    public void aNumberIsNotReadAsTheNextKeysName() {
        // The first quote after the colon belonged to the next key, so the check was named
        // after a field rather than by what it is.
        assertEquals("risk login", CaptchaGate.checkId("risk", "{\"subtype\":3,\"scene\":\"login\"}"));
        assertEquals("risk slide", CaptchaGate.checkId("risk", "{\"subtype\": \"slide\"}"));
        assertEquals("risk {\"subtype\":3}", CaptchaGate.checkId("risk", "{\"subtype\":3}"));
    }

    @Test
    public void anIdFallsBackToTheChecksOwnDescription() {
        assertEquals("service common_verify", CaptchaGate.checkId("service", "common_verify"));
        assertEquals("scene unknown", CaptchaGate.checkId("scene", null));

        String tooLong = new String(new char[200]).replace('\0', 'x');
        assertEquals("risk ".length() + 80, CaptchaGate.checkId("risk", tooLong).length());
    }

    @Test
    public void theWindowCoversTheWholeRoundTrip() {
        // The server raises the check in its answer, so the request has already been sent.
        CaptchaGate.recordRequest(new Request("/aweme/v1/commit/follow/user/"));
        assertNotNull(CaptchaGate.pendingWriteAction());
        assertEquals("follow", CaptchaGate.pendingWriteAction(now + 10_000L));
    }

    @Test
    public void everyAccountWriteKeepsItsChallengeVisible() {
        String[][] writes = {
                {"/aweme/v1/commit/follow/user/", "follow"},
                {"/aweme/v3/f2f/follow/", "follow"},
                {"/aweme/v1/remove/follower/", "follow"},
                {"/aweme/v1/commit/follow/batchuser/", "follow"},
                {"/aweme/v1/commit/item/digg/", "like"},
                {"/aweme/v1/commit/game/item/digg/", "like"},
                {"/aweme/v1/comment/digg/", "like"},
                {"/aweme/v1/unlogged/digg/", "like"},
                {"/aweme/v1/danmaku/digg/", "like"},
                {"/aweme/v1/upvote/digg/", "like"},
                {"/aweme/v1/upvote/comment_digg/", "like"},
                {"/aweme/v1/comment/publish/", "comment"},
                {"/aweme/v1/comment/delete/", "comment"},
                {"/aweme/v1/comment/pin/", "comment"},
                {"/aweme/v1/upvote/publish_comment/", "comment"},
                {"/webcast/room/chat/", "comment"},
                {"/tiktok/v1/upvote/publish/", "repost"},
                {"/tiktok/v1/upvote/batch_publish/", "repost"},
                {"/tiktok/v1/repost/create/", "repost"},
                {"/tiktok/story/maf/mute", "story"},
                {"/tiktok/story/maf/unmute", "story"},
        };

        for (String[] write : writes) {
            CaptchaGate.resetForTests();
            CaptchaGate.recordRequest(new Request(write[0]));
            assertEquals(write[1], CaptchaGate.pendingWriteAction(now));
            assertEquals("it gates a " + write[1],
                    CaptchaGate.showReason(null, "{\"subtype\":\"slide\"}", now));

            // Every native popup route shares this answer, including account security wrappers.
            assertFalse(CaptchaGate.shouldHideCaptchaPopup(null, "{\"subtype\":\"slide\"}"));
            assertFalse(CaptchaGate.shouldHideLegacyCaptchaPopup(null, 2148));
            assertFalse(CaptchaGate.shouldHideOecCaptchaPopup(new VerifyRequest("follow")));
            assertFalse(CaptchaGate.shouldHideTuringDialog(null, new VerifyRequest("follow")));
            assertFalse(CaptchaGate.shouldHideTuringCaptchaPopup(null, "common_verify"));
        }
    }
}
