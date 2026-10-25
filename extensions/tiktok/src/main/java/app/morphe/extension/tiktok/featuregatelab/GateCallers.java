/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.featuregatelab;

/**
 * Reads a gate read's stack by its shape rather than by the names one TikTok build gave its
 * classes.
 *
 * <p>Every call into the Lab comes from code the patch put into a getter, so the first frame
 * outside the Lab is always the hooked getter itself, whatever R8 called its class. From there a
 * read can pass through a few frames of TikTok's own settings plumbing before it reaches the
 * code that asked: SettingsManager's typed getters hand a lambda to its settings cache, which
 * calls the app AB class, and the getter with a default hands the cache a wrapper that calls
 * the getter without one. On 46.2.3, 47.0.3 and 47.1.3 that is two frames between one getter
 * frame and the next, under a different pair of made-up names on each build. This used to skip
 * a list of 46.2.3's names, which on 47.x named an enum, an empty class and unrelated lambdas,
 * so the caller shown was a settings wrapper and the default-path check matched every read.
 */
final class GateCallers {
    static final String SETTINGS_MANAGER = "com.bytedance.ies.abmock.SettingsManager";

    /**
     * The most frames of settings plumbing that may sit between two getter frames of one read.
     * FeatureGateLabFramesTest holds each declared build's chains to this.
     */
    static final int MAX_INNER_FRAMES = 3;

    /** Getters the patch hooks on classes that keep their own names. */
    private static final String[] NAMED_GETTER_CLASSES = {
            SETTINGS_MANAGER,
            "com.bytedance.android.live_settings.SettingsManager",
            "com.ss.android.vesdk.VEConfigCenter",
            "com.ss.android.ugc.aweme.video.simplayer.PlayerSettingServiceImpl",
            "com.ss.android.ugc.tiktok.pns.activitycenter.EnterActivityCenterAction",
    };

    private GateCallers() {
    }

    /** The code that asked for the gate, as class#method, or "unknown". */
    static String hostCaller(StackTraceElement[] frames) {
        StackTraceElement frame = hostFrame(frames);
        return frame == null ? "unknown" : frame.getClassName() + "#" + frame.getMethodName();
    }

    /** The same, with the file and line the frame names. */
    static String hostCallerWithLine(StackTraceElement[] frames) {
        StackTraceElement frame = hostFrame(frames);
        return frame == null ? "unknown" : frame.getClassName() + "#" + frame.getMethodName()
                + "(" + frame.getFileName() + ":" + frame.getLineNumber() + ")";
    }

    /**
     * Whether the hooked getter is SettingsManager's and the read came through another
     * SettingsManager getter a few frames out: the getter with a default reaches the one without
     * through its settings cache and a wrapper, and both are hooked.
     */
    static boolean throughAnotherSettingsManagerGetter(StackTraceElement[] frames) {
        int start = getterIndex(frames);
        if (start < 0 || !SETTINGS_MANAGER.equals(frames[start].getClassName())) return false;
        int last = Math.min(frames.length - 1, start + 1 + MAX_INNER_FRAMES);
        for (int index = start + 1; index <= last; index++) {
            if (SETTINGS_MANAGER.equals(frames[index].getClassName())) return true;
        }
        return false;
    }

    static StackTraceElement hostFrame(StackTraceElement[] frames) {
        int start = getterIndex(frames);
        if (start < 0) return null;
        String hooked = frames[start].getClassName();
        int end = start;
        int inner = 0;
        for (int index = start + 1; index < frames.length; index++) {
            String name = frames[index].getClassName();
            if (name.equals(hooked) || isNamedGetterClass(name)) {
                end = index;
                inner = 0;
            } else if (++inner > MAX_INNER_FRAMES) {
                break;
            }
        }
        for (int index = end + 1; index < frames.length; index++) {
            if (!isKotlinPlumbing(frames[index].getClassName())) return frames[index];
        }
        return null;
    }

    /** The first frame outside the Lab: the getter the patch hooked. */
    private static int getterIndex(StackTraceElement[] frames) {
        for (int index = 0; index < frames.length; index++) {
            if (!isLabFrame(frames[index].getClassName())) return index;
        }
        return -1;
    }

    private static boolean isLabFrame(String name) {
        return name.startsWith("app.morphe.extension.tiktok.featuregatelab.")
                || name.startsWith("java.lang.Thread")
                || name.equals("dalvik.system.VMStack");
    }

    private static boolean isNamedGetterClass(String name) {
        for (String getter : NAMED_GETTER_CLASSES) {
            if (getter.equals(name)) return true;
        }
        return false;
    }

    /**
     * Kotlin's own machinery between a lazy experiment holder and the getter it reads, such as
     * SynchronizedLazyImpl.getValue. Not kotlin.jvm.internal: R8 merges TikTok's own lambdas
     * into classes there, and those are the code that asked.
     */
    private static boolean isKotlinPlumbing(String name) {
        return name.startsWith("kotlin.") && !name.startsWith("kotlin.jvm.internal.");
    }
}
