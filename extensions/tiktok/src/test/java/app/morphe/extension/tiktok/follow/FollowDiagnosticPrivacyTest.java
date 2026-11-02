package app.morphe.extension.tiktok.follow;

import static org.junit.Assert.*;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.settings.Settings;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FollowDiagnosticPrivacyTest {
    private String previousSalt;
    private boolean previousDebug;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        previousSalt = Settings.DIAGNOSTIC_REPORT_SALT.get();
        previousDebug = BaseSettings.DEBUG.get();
        Settings.DIAGNOSTIC_REPORT_SALT.save("test-install-key-that-is-not-exported");
        ReflectionHelpers.setStaticField(FollowDiagnostics.class, "salt", null);
        BaseSettings.DEBUG.save(true);
        FollowDiagnostics.resetForTests();
    }

    @After public void tearDown() {
        Settings.DIAGNOSTIC_REPORT_SALT.save(previousSalt);
        ReflectionHelpers.setStaticField(FollowDiagnostics.class, "salt", null);
        BaseSettings.DEBUG.save(previousDebug);
        FollowDiagnostics.resetForTests();
    }

    @Test public void oneKnownAccountDoesNotPredictAnotherAccountsReportPseudonym() {
        String knownId = "6812345678901234567";
        String otherId = "7098765432109876543";
        // With salt-prefixed FNV, every known suffix byte can be undone. No knowledge of the
        // private salt is needed to recover the intermediate state and test candidate accounts.
        int state = new BigInteger(requestPseudonym(knownId), 16).intValue();
        int inverse = BigInteger.valueOf(0x01000193L)
                .modInverse(BigInteger.ONE.shiftLeft(32)).intValue();
        for (int index = knownId.length() - 1; index >= 0; index--) {
            state *= inverse;
            state ^= knownId.charAt(index);
        }
        for (int index = 0; index < otherId.length(); index++) {
            state ^= otherId.charAt(index);
            state *= 0x01000193;
        }
        assertNotEquals("a known account revealed how to recognize every other account",
                state, new BigInteger(requestPseudonym(otherId), 16).intValue());
    }

    @Test public void theRequestStoresAKeyedDigestOfTheWholeIdentifier() throws Exception {
        String id = "6812345678901234567";
        Mac oracle = Mac.getInstance("HmacSHA256");
        oracle.init(new SecretKeySpec(Settings.DIAGNOSTIC_REPORT_SALT.get()
                .getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = java.util.HexFormat.of().formatHex(oracle.doFinal(id.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, requestPseudonym(id));
        assertEquals(expected, requestPseudonym(id));
        assertNotEquals(expected, requestPseudonym(id + "0"));
    }

    private static String requestPseudonym(String id) {
        FollowDiagnostics.logCommonFollowRequest(1, 0, 0, 0, id, null, null, null, null, null);
        FollowDiagnostics.FollowRequestContext context =
                ReflectionHelpers.getStaticField(FollowDiagnostics.class, "recentDirectContext");
        assertNotNull("the injected request hook did not capture a diagnostic context", context);
        assertEquals("the request reused an earlier context",
                FollowDiagnostics.eventCountForTests(), context.id);
        assertFalse(context.summary().contains(id));
        return context.uidHash;
    }
}
