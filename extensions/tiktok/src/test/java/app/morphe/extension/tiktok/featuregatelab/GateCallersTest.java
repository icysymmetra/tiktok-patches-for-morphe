package app.morphe.extension.tiktok.featuregatelab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * The stacks below are the ones TikTok builds for a gate read, frame for frame, with the names
 * each build gave its settings plumbing (read off the fixtures with dexlib2):
 *
 * <ul>
 *   <li>app AB class: X.0BYX on 46.2.3, X.02y4 on 47.0.3, X.02yB on 47.1.3;</li>
 *   <li>settings cache: X.0Bb9, X.02ww, X.02x7;</li>
 *   <li>the wrapper the getter with a default hands the cache: X.0BZ5, X.03jr, X.03gg;</li>
 *   <li>the boolean getter's lambda: X.0BZA, X.03jM, X.03gp.</li>
 * </ul>
 *
 * <p>The Lab used to skip 46.2.3's names, which on 47.x belong to an enum, an empty class and
 * unrelated lambdas. None of these names is in the Lab now, and every build answers the same.
 */
public class GateCallersTest {
    private static final String SETTINGS_MANAGER = "com.bytedance.ies.abmock.SettingsManager";
    private static final String HOST = "com.ss.android.ugc.aweme.legoImp.task.SysOptimizerTask";

    private static final String[][] BUILDS = {
            // app AB class, settings cache, default wrapper, boolean lambda
            {"X.0BYX", "X.0Bb9", "X.0BZ5", "X.0BZA"},
            {"X.02y4", "X.02ww", "X.03jr", "X.03jM"},
            {"X.02yB", "X.02x7", "X.03gg", "X.03gp"},
    };

    @Test
    public void aTypedReadThroughSettingsManagerNamesTheCodeThatAskedOnEveryBuild() {
        for (String[] build : BUILDS) {
            StackTraceElement[] frames = stack(
                    build[0] + "#LIZ", build[0] + "#LIZIZ", build[3] + "#LIZ", build[1] + "#LIZJ",
                    SETTINGS_MANAGER + "#LIZ", HOST + "#run", "com.bytedance.lego.init.Runner#execute");
            assertEquals(build[0], HOST + "#run", GateCallers.hostCaller(frames));
            assertFalse(build[0], GateCallers.throughAnotherSettingsManagerGetter(frames));
        }
    }

    @Test
    public void aReadWithADefaultIsToldFromOneWithoutOnEveryBuild() {
        for (String[] build : BUILDS) {
            StackTraceElement[] throughDefault = stack(
                    SETTINGS_MANAGER + "#LJII", build[2] + "#LIZ", build[1] + "#LIZJ",
                    SETTINGS_MANAGER + "#LJIIIIZZ", HOST + "#run");
            assertTrue(build[0], GateCallers.throughAnotherSettingsManagerGetter(throughDefault));
            assertEquals(build[0], HOST + "#run", GateCallers.hostCaller(throughDefault));

            // The hooked getter's own frame is SettingsManager too. Counting it was the bug that
            // kept every read without a default out of the observations.
            StackTraceElement[] direct = stack(
                    SETTINGS_MANAGER + "#LJII", "com.ss.android.ugc.aweme.SettingsMainApiImpl#getSettings",
                    HOST + "#run", "com.bytedance.lego.init.Runner#execute");
            assertFalse(build[0], GateCallers.throughAnotherSettingsManagerGetter(direct));
            assertEquals(build[0], "com.ss.android.ugc.aweme.SettingsMainApiImpl#getSettings",
                    GateCallers.hostCaller(direct));
        }
    }

    @Test
    public void aSettingsManagerFrameFarOutIsAnotherRead() {
        // Past the plumbing's reach, a SettingsManager frame is a read that happened to call
        // host code that reads again, not the getter with a default.
        StackTraceElement[] frames = stack(
                SETTINGS_MANAGER + "#LJII", HOST + "#a", HOST + "#b", HOST + "#c", HOST + "#d",
                SETTINGS_MANAGER + "#LIZ");
        assertFalse(GateCallers.throughAnotherSettingsManagerGetter(frames));
        assertEquals(HOST + "#a", GateCallers.hostCaller(frames));
    }

    @Test
    public void aLazyExperimentHolderIsNamedThroughKotlinsOwnFrames() {
        // TikTok keeps many experiments in a Kotlin lazy. The lambda R8 merged into
        // kotlin.jvm.internal is TikTok's code and is the caller; the Lazy machinery is not.
        StackTraceElement[] lambda = stack(
                "X.02yB#LIZ", "kotlin.jvm.internal.AFwS194S0000000_4#invoke",
                "kotlin.SynchronizedLazyImpl#getValue", "X.0BPv#LIZ", HOST + "#run");
        assertEquals("kotlin.jvm.internal.AFwS194S0000000_4#invoke", GateCallers.hostCaller(lambda));

        StackTraceElement[] plumbing = stack(
                "com.ss.android.vesdk.VEConfigCenter#getValue", "kotlin.SynchronizedLazyImpl#getValue",
                "com.ss.android.ugc.aweme.camera.CameraHost#open");
        assertEquals("com.ss.android.ugc.aweme.camera.CameraHost#open", GateCallers.hostCaller(plumbing));
    }

    @Test
    public void namedGettersAreReadTheSameWay() {
        StackTraceElement[] live = stack(
                "com.bytedance.android.live_settings.SettingsManager#getBooleanValue",
                "com.bytedance.android.live_settings.SettingsManager#getValue",
                "com.bytedance.android.livesdk.LiveRoom#enter");
        assertEquals("com.bytedance.android.livesdk.LiveRoom#enter", GateCallers.hostCaller(live));
        assertTrue(GateCallers.hostCallerWithLine(live).endsWith("(Host.java:7)"));
    }

    @Test
    public void aStackWithNothingOutsideTheLabHasNoCaller() {
        StackTraceElement[] labOnly = {
                new StackTraceElement("java.lang.Thread", "getStackTrace", "Thread.java", 1),
                new StackTraceElement(GateCallers.class.getName(), "hostCaller", "GateCallers.java", 2),
        };
        assertEquals("unknown", GateCallers.hostCaller(labOnly));
        assertFalse(GateCallers.throughAnotherSettingsManagerGetter(labOnly));
    }

    /** The Lab's own frames on top, as Thread.getStackTrace gives them, then the given ones. */
    private static StackTraceElement[] stack(String... frames) {
        List<StackTraceElement> result = new ArrayList<>();
        result.add(new StackTraceElement("dalvik.system.VMStack", "getThreadStackTrace", null, -2));
        result.add(new StackTraceElement("java.lang.Thread", "getStackTrace", "Thread.java", 1));
        result.add(new StackTraceElement(GateCallers.class.getName(), "hostCaller", "GateCallers.java", 2));
        result.add(new StackTraceElement(FeatureGateLabRuntime.class.getName(), "markTriggered",
                "FeatureGateLabRuntime.java", 3));
        for (String frame : frames) {
            int hash = frame.indexOf('#');
            result.add(new StackTraceElement(frame.substring(0, hash), frame.substring(hash + 1), "Host.java", 7));
        }
        return result.toArray(new StackTraceElement[0]);
    }
}
