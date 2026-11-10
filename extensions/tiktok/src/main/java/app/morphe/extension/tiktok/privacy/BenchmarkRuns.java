/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Keeps TikTok's ByteBench phone benchmark from starting (#64).
 *
 * <p>When TikTok's servers hand it a benchmark task, it binds ByteBench's collection service,
 * which the manifest runs in a process of its own, {@code :bm}. That process held about 194 MB
 * on the reporter's phone. On 47.1.4 the starter binds once and gives up when the bind fails,
 * so a disabled component is all it takes: nothing retries it. The manifest puts one other
 * service in {@code :bm}, {@code com.benchmark.BenchmarkService}, but 47.0.3 to 47.1.4 carry no
 * code for it and nothing names it. The ByteBench-named strategies
 * in 47.1.4 (camera, editor, effects and LIVE settings) come from TikTok's servers, and none of
 * them is the feed's player.
 *
 * <p>PackageManager keeps a component's state across restarts and updates, so the switch is
 * put into effect both ways: on records its prior enabled/default state before disabling it,
 * and off (or Pause) restores that state only while Metra still owns the disable. A process
 * that is already running keeps going until TikTok restarts. A
 * build without this optional patch still reconciles state owned by Metra through shared startup.
 */
public final class BenchmarkRuns {
    static final String SERVICE = "com.benchmark.collection.service.ByteBenchService";
    public static final String STATE_FILE = "morphe_optional_components";
    static final String ORIGINAL_STATE = "bytebench_original_state";

    /** The latest choice asked for. Each queued task applies this, not the value it was queued with. */
    private static volatile Boolean requested;

    private BenchmarkRuns() {
    }

    /** From TikTok's main activity, so a change made anywhere is in force from this start. */
    public static void onAppOpened(Activity activity) {
        request(activity.getApplicationContext(), SettingsStatus.resourceGovernorEnabled && Settings.STOP_BENCHMARK_RUNS.get());
    }

    /**
     * From the settings row. The listener runs before the change is saved, so the new value
     * comes in rather than being read back.
     */
    public static void settingsChanged(Context context, boolean stop) {
        request(context.getApplicationContext(), SettingsStatus.resourceGovernorEnabled && stop && !Setting.isPaused());
    }

    /**
     * Two quick flips run on two pool threads at once, and whichever finished last used to win,
     * so on then off could leave the service disabled under a switch that reads off. Every task
     * applies the latest request under one lock instead, so the last to run agrees with the switch.
     */
    private static void request(Context app, boolean stop) {
        requested = stop;
        Utils.runOnBackgroundThread(() -> {
            synchronized (BenchmarkRuns.class) {
                Boolean latest = requested;
                if (latest != null) apply(app, latest);
            }
        });
    }

    static int wantedState(boolean stop) {
        return stop ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
    }

    static void apply(Context context, boolean stop) {
        try {
            PackageManager packages = context.getPackageManager();
            ComponentName service = new ComponentName(context.getPackageName(), SERVICE);
            packages.getServiceInfo(service, PackageManager.GET_DISABLED_COMPONENTS);
            SharedPreferences state = context.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE);
            int current = packages.getComponentEnabledSetting(service);
            if (stop) {
                // Do not adopt any component that another actor already disabled.
                if (current != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                        && current != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return;
                // If another actor re-enabled it since our last disable, remember that new state.
                if (!state.edit().putInt(ORIGINAL_STATE, current).commit()) return;
                packages.setComponentEnabledSetting(service, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
            } else if (state.contains(ORIGINAL_STATE)) {
                int previous = state.getInt(ORIGINAL_STATE, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
                if (previous != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                        && previous != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    Logger.printInfo(() -> "Benchmark runs: refusing to restore an invalid saved component state");
                    return;
                }
                if (current == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    packages.setComponentEnabledSetting(service, previous, PackageManager.DONT_KILL_APP);
                }
                // An independent external change wins; repeated recovery converges on it.
                state.edit().remove(ORIGINAL_STATE).commit();
            }
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException | SecurityException missing) {
            // A build without the service in its manifest has nothing to switch.
            Logger.printInfo(() -> "Benchmark runs: no benchmark service in the manifest");
        }
    }
}
