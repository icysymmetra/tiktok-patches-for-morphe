package app.morphe.extension.tiktok.blockauthor;

import static org.junit.Assert.*;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Method;

/** Account checks run inside the worker, before call creation and before native execution. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class BlockAuthorServiceAccountTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private final Service service = new Service();
    private Object previousService;
    private Method previousMethod;
    private Method execute;

    public static final class Body { public int getStatusCode() { return 0; } }
    public static final class Response { public Body body() { return new Body(); } }
    public static final class Call {
        int executions;
        public Response execute() { executions++; return new Response(); }
    }
    public static final class Service {
        int creations;
        String uid, secUid;
        int blockType, source;
        boolean switchDuringCreation;
        final Call call = new Call();

        public Call block(String uid, String secUid, int blockType, int source) {
            creations++; this.uid = uid; this.secUid = secUid; this.blockType = blockType; this.source = source;
            if (switchDuringCreation) SignedInUser.idForTests = "101";
            return call;
        }
    }

    @Before public void setUp() throws Exception {
        SignedInUser.idForTests = "100";
        previousService = ReflectionHelpers.getStaticField(BlockAuthorService.class, "cachedService");
        previousMethod = ReflectionHelpers.getStaticField(BlockAuthorService.class, "cachedBlockMethod");
        ReflectionHelpers.setStaticField(BlockAuthorService.class, "cachedService", service);
        ReflectionHelpers.setStaticField(BlockAuthorService.class, "cachedBlockMethod",
                Service.class.getMethod("block", String.class, String.class, int.class, int.class));
        execute = BlockAuthorService.class.getDeclaredMethod("execute", VideoAuthor.class, int.class, String.class);
        execute.setAccessible(true);
    }

    @After public void tearDown() {
        SignedInUser.idForTests = null;
        ReflectionHelpers.setStaticField(BlockAuthorService.class, "cachedService", previousService);
        ReflectionHelpers.setStaticField(BlockAuthorService.class, "cachedBlockMethod", previousMethod);
    }

    private BlockAuthorService.Result request(int type, String account) throws Exception {
        return (BlockAuthorService.Result) execute.invoke(null, new VideoAuthor("200", "sec200", "creator", "post"),
                type, account);
    }

    @Test public void queuedAccountSwitchAndSignOutNeverCreateANativeCall() throws Exception {
        for (String current : new String[] {"101", ""}) {
            SignedInUser.idForTests = current;
            assertEquals(BlockAuthorService.Result.UNCONFIRMED, request(1, "100"));
            assertEquals(BlockAuthorService.Result.UNCONFIRMED, request(0, "100"));
        }
        assertEquals(0, service.creations);
        assertEquals(0, service.call.executions);
    }

    @Test public void switchingDuringCallCreationNeverExecutesTheRequest() throws Exception {
        service.switchDuringCreation = true;
        assertEquals(BlockAuthorService.Result.UNCONFIRMED, request(1, "100"));
        assertEquals(1, service.creations);
        assertEquals(0, service.call.executions);
    }

    @Test public void boundBlockAndUndoUseTheExistingNativeParameters() throws Exception {
        assertEquals(BlockAuthorService.Result.CONFIRMED, request(1, "100"));
        assertEquals("200", service.uid);
        assertEquals("sec200", service.secUid);
        assertEquals(1, service.blockType);
        assertEquals(0, service.source);
        assertEquals(BlockAuthorService.Result.CONFIRMED, request(0, "100"));
        assertEquals(0, service.blockType);
        assertEquals(2, service.call.executions);
    }

    @Test public void blankBoundAccountFailsWhileTheExistingUnboundContractRemains() throws Exception {
        assertEquals(BlockAuthorService.Result.UNCONFIRMED, request(1, ""));
        assertEquals(0, service.creations);
        SignedInUser.idForTests = "";
        assertEquals(BlockAuthorService.Result.CONFIRMED, request(1, null));
        assertEquals(1, service.creations);
    }
}
