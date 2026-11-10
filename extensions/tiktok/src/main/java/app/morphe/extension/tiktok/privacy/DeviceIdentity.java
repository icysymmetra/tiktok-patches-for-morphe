/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;

/**
 * A new device identity for TikTok: the phone it registers as, made over so its servers see one
 * they haven't met.
 *
 * <p>TikTok's own reset (AppLog.resetAndReInstall) waits out its 30 second timeout and keeps every
 * id, and clearing its data isn't enough either: the server hands the same device id back by the
 * Google advertising id, or by the Android ID when the advertising id is blank (measured on
 * 47.4.3, 2026-10-18). So a new identity is three things at once: TikTok's data wiped, a new
 * Android ID for TikTok's {@code Settings.Secure} read, and an advertising id of its own.
 *
 * <p>The row arms it, and the wipe runs at the next start, first thing in the application's
 * attachBaseContext, before TikTok has opened any of the two dozen files that hold its device id.
 * TikTok's other processes are ended first, and one that starts during the wipe ends itself before
 * TikTok's code runs, so none of them reads an old id or writes one back. Hushfeed's own
 * files stay: its settings, its save records, its seen-video history and anything else named for
 * it.
 *
 * <p>The identity lives in its own preferences file, out of Hushfeed's settings, so an exported
 * backup never carries it to another phone and a settings reset never drops it.
 */
public final class DeviceIdentity {
    /** Kept through TikTok's launch-crash cleanup, which would hand TikTok the phone's own ids. */
    public static final String PREFERENCES = "hushfeed_device_identity";
    static final String ARMED_KEY = "armed";
    static final String ANDROID_ID_KEY = "android_id";
    static final String ADVERTISING_ID_KEY = "advertising_id";
    /** In TikTok's data folder for as long as a wipe runs, under a name the wipe keeps. */
    static final String WIPING_MARKER = "hushfeed_identity_wiping";

    /**
     * The prefixes Hushfeed's own files, preferences and databases go by, which the wipe keeps.
     * The seen-video history's database is the one not named for Hushfeed.
     */
    private static final String[] KEPT_PREFIXES =
            {"morphe", "hushfeed", "metra", "feature-gate-lab", "seen_videos"};
    /** The directories of TikTok's data that may hold a Hushfeed file next to TikTok's own. */
    private static final String[] SHARED_DIRECTORIES =
            {"shared_prefs", "files", "cache", "no_backup", "databases"};
    /** Left alone: the native library link Android owns, and compiled code that holds no data. */
    private static final String[] UNTOUCHED = {"lib", "code_cache"};

    private static final Pattern ANDROID_ID = Pattern.compile("[0-9a-f]{16}");
    private static final Pattern ADVERTISING_ID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** Where the process name comes from. Tests put their own here. */
    interface ProcessName {
        @Nullable
        String current();
    }

    static ProcessName processName = DeviceIdentity::currentProcessName;

    /** How TikTok's processes are ended. Tests put their own here, so nothing in the test JVM ends. */
    interface Processes {
        /** Ends every other process of this app. */
        void endOthers(Context base);

        /** Ends this process. */
        void endSelf();
    }

    static final Processes SYSTEM_PROCESSES = new Processes() {
        @Override
        public void endOthers(Context base) {
            endOtherProcesses(base);
        }

        @Override
        public void endSelf() {
            Process.killProcess(Process.myPid());
        }
    };

    static Processes processes = SYSTEM_PROCESSES;

    @Nullable
    private static volatile String androidId;
    @Nullable
    private static volatile String advertisingId;

    private DeviceIdentity() {
    }

    /**
     * Runs first in the host application's attachBaseContext, in every process: wipes TikTok's
     * data for a new identity when one is armed and this is the main process, then keeps the
     * identity at hand for the reads that follow, which come before Hushfeed's settings exist.
     */
    public static void start(Context base) {
        try {
            SharedPreferences saved = base.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
            if (saved.getBoolean(ARMED_KEY, false)) {
                if (isMainProcess(base)) {
                    renew(base, saved);
                } else if (wipingMarker(base).exists()) {
                    // The main process is wiping. This one would read TikTok's old ids before they
                    // go and could write them back after, so it ends before TikTok's code runs.
                    Logger.printInfo(() -> "Device identity: ending a process that started during the wipe");
                    processes.endSelf();
                    return;
                }
            }
            remember(saved);
        } catch (Throwable unreadable) {
            // This runs before TikTok's own start, where anything thrown would crash it every time.
            Logger.printException(() -> "Device identity: could not start", unreadable);
        }
    }

    /** The Android ID this identity gives TikTok, or null when TikTok gets the phone's own. */
    @Nullable
    public static String androidId() {
        return androidId;
    }

    /** The advertising id this identity gives TikTok, or null when TikTok gets the phone's own. */
    @Nullable
    public static String advertisingId() {
        return advertisingId;
    }

    /** Whether the next start wipes TikTok's data for a new identity. */
    public static boolean isArmed(Context context) {
        return preferences(context).getBoolean(ARMED_KEY, false);
    }

    /** Arms or disarms the wipe. False when the choice couldn't be saved. */
    public static boolean setArmed(Context context, boolean armed) {
        return preferences(context).edit().putBoolean(ARMED_KEY, armed).commit();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static void remember(SharedPreferences saved) {
        String android = saved.getString(ANDROID_ID_KEY, null);
        String advertising = saved.getString(ADVERTISING_ID_KEY, null);
        androidId = android != null && ANDROID_ID.matcher(android).matches() ? android : null;
        advertisingId = advertising != null && ADVERTISING_ID.matcher(advertising).matches() ? advertising : null;
    }

    /**
     * Saves the new ids, ends TikTok's other processes and wipes its data. The ids are saved
     * before anything ends, so a process Android starts again meanwhile never finds the wipe
     * armed with no ids to answer with, and the marker makes any that start during the wipe end
     * themselves. The armed flag clears last, so a start that dies halfway wipes again on the next
     * one, with new ids again.
     */
    private static void renew(Context base, SharedPreferences saved) {
        long started = System.currentTimeMillis();
        File marker = wipingMarker(base);
        try {
            if (!marker.createNewFile() && !marker.exists()) {
                Logger.printInfo(() -> "Device identity: no marker for the wipe");
            }
        } catch (IOException unwritable) {
            Logger.printException(() -> "Device identity: no marker for the wipe", unwritable);
        }
        SecureRandom random = new SecureRandom();
        boolean committed = saved.edit()
                .putString(ANDROID_ID_KEY, newAndroidId(random))
                .putString(ADVERTISING_ID_KEY, UUID.randomUUID().toString())
                .commit();
        processes.endOthers(base);
        int removed = 0;
        File credentialData = new File(base.getApplicationInfo().dataDir);
        removed += wipe(credentialData);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                File deviceData = base.createDeviceProtectedStorageContext().getDataDir();
                if (!sameFile(deviceData, credentialData)) removed += wipe(deviceData);
            } catch (RuntimeException unavailable) {
                Logger.printException(() -> "Device identity: no device storage to wipe", unavailable);
            }
        }
        File external = base.getExternalFilesDir(null);
        if (external != null && external.getParentFile() != null) removed += wipe(external.getParentFile());

        boolean disarmed = saved.edit().putBoolean(ARMED_KEY, false).commit();
        if (!marker.delete() && marker.exists()) {
            Logger.printInfo(() -> "Device identity: the wipe's marker stayed");
        }
        int count = removed;
        long took = System.currentTimeMillis() - started;
        Logger.printInfo(() -> "Device identity: wiped " + count + " files in " + took
                + " ms for a new identity, saved " + committed + ", disarmed " + disarmed);
    }

    private static File wipingMarker(Context base) {
        return new File(base.getApplicationInfo().dataDir, WIPING_MARKER);
    }

    /** A random Android ID, 16 lowercase hex digits like the one Android hands an app. */
    static String newAndroidId(SecureRandom random) {
        long value;
        do {
            value = random.nextLong();
        } while (value == 0);
        return String.format(Locale.US, "%016x", value);
    }

    /**
     * Deletes what [root] holds, Hushfeed's own entries aside: everything in it except the native
     * library link and compiled code, and in the five directories Hushfeed shares with TikTok,
     * everything not named for Hushfeed. Links are removed, never followed. Returns the number of
     * files removed.
     */
    static int wipe(File root) {
        File[] entries = root.listFiles();
        if (entries == null) return 0;
        int removed = 0;
        for (File entry : entries) {
            String name = entry.getName();
            if (contains(UNTOUCHED, name)) continue;
            if (isKept(name)) continue;
            if (contains(SHARED_DIRECTORIES, name) && entry.isDirectory() && !isLink(entry)) {
                File[] children = entry.listFiles();
                if (children == null) continue;
                for (File child : children) {
                    if (!isKept(child.getName())) removed += delete(child);
                }
            } else {
                removed += delete(entry);
            }
        }
        return removed;
    }

    static boolean isKept(String name) {
        for (String prefix : KEPT_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private static int delete(File file) {
        int removed = 0;
        if (file.isDirectory() && !isLink(file)) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) removed += delete(child);
            }
        }
        if (file.delete()) removed++;
        return removed;
    }

    /**
     * Whether [file] is a symbolic link: asked directly on Android 8 and later, and before that by
     * where its canonical path leads.
     */
    static boolean isLink(File file) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return java.nio.file.Files.isSymbolicLink(file.toPath());
        }
        try {
            File parent = file.getParentFile();
            if (parent == null) return false;
            File inPlace = new File(parent.getCanonicalFile(), file.getName());
            return !inPlace.getCanonicalFile().equals(inPlace.getAbsoluteFile());
        } catch (IOException unreadable) {
            // Unknown: treat it as a link, so nothing is followed through it.
            return true;
        }
    }

    private static boolean sameFile(File one, File other) {
        try {
            return one.getCanonicalFile().equals(other.getCanonicalFile());
        } catch (IOException unreadable) {
            return true;
        }
    }

    private static boolean contains(String[] names, String name) {
        for (String candidate : names) {
            if (candidate.equals(name)) return true;
        }
        return false;
    }

    /**
     * Ends every other process of this app, so the push and mini-app processes can't write the old
     * device id back while the files go. Android only lets an app end its own processes.
     */
    private static void endOtherProcesses(Context base) {
        try {
            ActivityManager manager = (ActivityManager) base.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return;
            List<ActivityManager.RunningAppProcessInfo> running = manager.getRunningAppProcesses();
            if (running == null) return;
            int self = Process.myPid();
            int uid = Process.myUid();
            for (ActivityManager.RunningAppProcessInfo process : running) {
                if (process.pid != self && process.uid == uid) {
                    String name = process.processName;
                    Logger.printInfo(() -> "Device identity: ending " + name + " before the wipe");
                    Process.killProcess(process.pid);
                }
            }
        } catch (RuntimeException unavailable) {
            Logger.printException(() -> "Device identity: could not end TikTok's other processes", unavailable);
        }
    }

    private static boolean isMainProcess(Context base) {
        String name = processName.current();
        return name != null && name.equals(base.getPackageName());
    }

    @Nullable
    static String currentProcessName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName();
        try (FileInputStream in = new FileInputStream("/proc/self/cmdline")) {
            byte[] buffer = new byte[256];
            int read = in.read(buffer);
            if (read <= 0) return null;
            int end = 0;
            while (end < read && buffer[end] != 0) end++;
            return new String(buffer, 0, end, "UTF-8");
        } catch (IOException unreadable) {
            return null;
        }
    }

    static void resetForTests() {
        processName = DeviceIdentity::currentProcessName;
        processes = SYSTEM_PROCESSES;
        androidId = null;
        advertisingId = null;
    }
}
