package app.morphe.extension.tiktok.blockauthor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * A reader that returns null because TikTok renamed the member looks the same as one that
 * returned null on purpose, and the callers treat both as "no". These pin that a member the
 * caller has no fallback for is named in the report once, and that a member which is present
 * and simply returns null says nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class ReflectMissingMemberTest {
    private String previousFilters;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        previousFilters = BaseSettings.DEBUG_LOG_FILTERS.get();
        BaseSettings.DEBUG_LOG_FILTERS.save("errors");
        LogBufferManager.clearLogBuffer();
        clearRegistry();
    }

    @After public void tearDown() {
        // The filter outlives the test: the settings layer caches values across Robolectric
        // resets, and a later class in the same fork read the Diagnostics summary as
        // "Includes these events: Errors", which no translation table carries.
        BaseSettings.DEBUG_LOG_FILTERS.save(previousFilters);
        LogBufferManager.clearLogBuffer();
        clearRegistry();
    }

    /** A card from a build that still answers the question. */
    public static final class KnownCard {
        public Boolean isAdOrContainAd() { return Boolean.TRUE; }
    }

    /** A card from a build that answers it with nothing to report. */
    public static final class QuietCard {
        public Boolean isAdOrContainAd() { return null; }
    }

    /** A card from a build that renamed the member away. */
    public static final class RenamedCard {
        public Boolean somethingElse() { return Boolean.TRUE; }
    }

    /** A future request shape whose public path moved onto an interface. */
    public interface DefaultPathRequest {
        default String getPath() { return "/aweme/v1/commit/follow/user/"; }
    }

    public static final class InterfaceRequest implements DefaultPathRequest {}

    @Test public void aMemberThisBuildLacksIsNamedInTheReport() {
        assertNull(Reflect.required(new RenamedCard(), "isAdOrContainAd"));

        List<String> missing = Reflect.missingMembers();
        assertEquals("Expected exactly one missing member, got " + missing, 1, missing.size());
        assertTrue(missing.get(0), missing.get(0).contains("RenamedCard#isAdOrContainAd"));

        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("isAdOrContainAd"));
    }

    @Test public void theSameMissingMemberIsReportedOnlyOnce() {
        for (int attempt = 0; attempt < 5; attempt++) {
            Reflect.required(new RenamedCard(), "isAdOrContainAd");
        }

        assertEquals(1, Reflect.missingMembers().size());
    }

    @Test public void aMemberThatIsPresentIsNeverReported() {
        assertEquals(Boolean.TRUE, Reflect.required(new KnownCard(), "isAdOrContainAd"));
        // Present, and answering null is a real answer rather than a missing hook.
        assertNull(Reflect.required(new QuietCard(), "isAdOrContainAd"));

        assertEquals("A member that is there was reported: " + Reflect.missingMembers(),
                0, Reflect.missingMembers().size());
    }

    @Test public void aPublicInterfaceDefaultIsFoundAfterTheDeclaredWalk() {
        assertEquals("/aweme/v1/commit/follow/user/",
                Reflect.invoke(new InterfaceRequest(), "getPath"));
        assertNull("Object methods stay outside this model lookup",
                Reflect.method(InterfaceRequest.class, "toString"));
        assertEquals("An inherited default was reported missing: " + Reflect.missingMembers(),
                0, Reflect.missingMembers().size());
    }

    @Test public void theOrdinaryReadersStaySilentAboutTheShapeTheyDidNotFind() {
        // property tries a getter and then a field and expects one of them to miss, so those
        // misses must not reach the report or it fills with names that never resolve.
        Reflect.property(new KnownCard(), "getUniqueId", "uniqueId");
        Reflect.string(new KnownCard(), "getNickname", "nickname");
        Reflect.invoke(new KnownCard(), "getAuthor");
        Reflect.readField(new KnownCard(), "author");

        assertEquals("An expected miss was reported: " + Reflect.missingMembers(),
                0, Reflect.missingMembers().size());
    }

    private static void clearRegistry() {
        HookStatus.clear();
    }
}
