/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.shared.settings;

/**
 * Puts the process into and out of the paused state for a test outside this package. Only
 * {@link HushfeedPause} may do that in the app, so the switch stays package-private there.
 */
public final class PausedProcess {
    private PausedProcess() {
    }

    public static void set(boolean paused) {
        Setting.setPausedForProcess(paused);
    }
}
