/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.shared.settings;

import android.content.Context;

/**
 * Hands {@link EarlySwitch} the application a test chooses, for a test outside this package. In
 * the app it comes from ActivityThread, so the source stays package-private there.
 */
public final class EarlyApplication {
    private EarlyApplication() {
    }

    public static void set(Context application) {
        EarlySwitch.source = () -> application;
    }

    public static void reset() {
        EarlySwitch.resetForTests();
    }
}
