package app.morphe.extension.tiktok.follow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Looper;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import com.ss.android.ugc.profile.platform.base.data.ProfileCommonInfo;
import com.ss.android.ugc.profile.platform.base.data.ProfileUser;
import com.ss.android.ugc.profile.platform.base.data.UserProfileInfo;
import com.ss.android.ugc.profile.platform.base.data.UserRelationInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowToast;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FollowProfileReadbackTest {
    private static final String TARGET_UID = "target-account-uid";
    private static final String TARGET_SEC_UID = "target-account-sec-uid";
    private boolean previousDebug;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        previousDebug = BaseSettings.DEBUG.get();
        BaseSettings.DEBUG.save(true);
        FollowDiagnostics.resetForTests();
        ShadowLog.clear();
        ShadowToast.reset();
    }

    @After public void tearDown() {
        BaseSettings.DEBUG.save(previousDebug);
        FollowDiagnostics.resetForTests();
        ShadowLog.clear();
        ShadowToast.reset();
    }

    @Test public void profileReadbackReportsTheTargetsOwnDifferentRelation() {
        acceptedCommonFollow();
        // The observed device response arrived after the 30-second broad readback window.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));

        String readback = readback(profile(TARGET_UID, TARGET_SEC_UID, 0));
        assertTrue(readback, readback.contains("targetMatch=true"));
        assertTrue(readback, readback.contains("readbackOutcome=observed_0_expected_1"));
        assertFalse(readback, readback.contains(TARGET_UID));
        assertFalse(readback, readback.contains(TARGET_SEC_UID));
        assertEquals("a different readback is not a refused-write verdict", 0,
                ShadowToast.shownToastCount());
    }

    @Test public void matchingProfileReadbackConfirmsItsOwnRelationByEitherIdentity() {
        acceptedCommonFollow();
        for (ProfileUser profile : new ProfileUser[]{
                profile(TARGET_UID, null, 1), profile(null, TARGET_SEC_UID, 1)}) {
            String readback = readback(profile);
            assertTrue(readback, readback.contains("targetMatch=true"));
            assertTrue(readback, readback.contains("readbackOutcome=confirmed_1"));
        }
    }

    @Test public void aProfileWithoutItsOwnRelationCannotBorrowAnotherProfilesState() {
        acceptedCommonFollow();
        String readback = readback(List.of(
                profile(TARGET_UID, TARGET_SEC_UID, null),
                profile("other-uid", "other-sec-uid", 1)));
        assertTrue(readback, readback.contains("targetMatch=true"));
        assertTrue(readback, readback.contains("readbackOutcome=target_found_state_unclear"));
        assertFalse(readback, readback.contains("readbackOutcome=confirmed_1"));
    }

    @Test public void mixedUserReadbacksKeepEachFollowStateWithItsOwnIdentity() {
        acceptedCommonFollow();
        String readback = readback(List.of(
                new UserRow(TARGET_UID, 0), new UserRow("other-uid", 1)));
        assertTrue(readback, readback.contains("targetMatch=true"));
        assertTrue(readback, readback.contains("readbackOutcome=observed_0_expected_1"));

        readback = readback(List.of(
                new UserRow(TARGET_UID, null), new UserRow("other-uid", 1)));
        assertTrue(readback, readback.contains("readbackOutcome=target_found_state_unclear"));
    }

    @Test public void anotherProfilesRelationDoesNotBecomeTheRequestedTargetsState() {
        acceptedCommonFollow();
        String readback = readback(profile("other-uid", "other-sec-uid", 0));
        assertTrue(readback, readback.contains("targetMatch=false"));
        assertTrue(readback, readback.contains("readbackOutcome=target_mismatch"));
        assertFalse(readback, readback.contains("readbackOutcome=observed_"));
        assertFalse(readback, readback.contains("readbackOutcome=confirmed_"));
    }

    private static void acceptedCommonFollow() {
        FollowDiagnostics.logCommonFollowRequest(
                1, 0, 3, 14, TARGET_UID, TARGET_SEC_UID, null, null, null, null);
        Request request = new Request("/aweme/v1/commit/follow/user/");
        FollowStatus body = new FollowStatus();
        FollowDiagnostics.logNetworkRequest(request);
        FollowDiagnostics.logParsedResponse(request, new ParsedResponse(body));
        FollowDiagnostics.logFollowResult(body);
        List<String> responses = messages("parsed response");
        assertEquals(1, responses.size());
        assertTrue(responses.get(0), responses.get(0).contains(" id=2 "));
        assertTrue(responses.get(0), responses.get(0).contains("outcome=server_accept_1"));
    }

    private static String readback(Object body) {
        ShadowLog.clear();
        FollowDiagnostics.logParsedResponse(
                new Request("/tiktok/user/profile/other/v1"), new ParsedResponse(body));
        List<String> responses = messages("readback response");
        assertEquals(1, responses.size());
        String response = responses.get(0);
        assertTrue(response, response.contains("afterId=2 target=id=2 "));
        return response;
    }

    private static List<String> messages(String event) {
        List<String> responses = new ArrayList<>();
        for (ShadowLog.LogItem log : ShadowLog.getLogs()) {
            if (log.msg.startsWith("[Morphe TikTok FollowProbe] " + event + " ")) {
                responses.add(log.msg);
            }
        }
        return responses;
    }

    private static ProfileUser profile(String uid, String secUid, Integer followStatus) {
        ProfileUser result = new ProfileUser();
        result.common = new ProfileCommonInfo();
        result.common.userProfileInfo = new UserProfileInfo();
        result.common.userProfileInfo.uid = uid;
        result.common.userProfileInfo.secUid = secUid;
        if (followStatus != null) {
            result.common.userRelationInfo = new UserRelationInfo();
            result.common.userRelationInfo.followStatus = followStatus;
            result.common.userRelationInfo.followerStatus = 0;
        }
        return result;
    }

    public static final class Request {
        private final String path;
        Request(String path) { this.path = path; }
        public String getPath() { return path; }
    }

    public static final class ParsedResponse {
        public final Object LIZIZ;
        ParsedResponse(Object body) { LIZIZ = body; }
        public boolean LIZJ() { return true; }
        public int LIZ() { return 200; }
    }

    public static final class FollowStatus {
        public int status_code;
        public int getFollowStatus() { return 1; }
        public boolean isFollowSuccess() { return true; }
    }

    public static final class UserRow {
        private final String uid;
        private final Integer followStatus;
        UserRow(String uid, Integer followStatus) {
            this.uid = uid;
            this.followStatus = followStatus;
        }
        public String getUid() { return uid; }
        public Integer getFollowStatus() { return followStatus; }
    }
}
