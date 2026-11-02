/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.featurecontrols;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Which route decided the feed's corner LIVE button, for the diagnostic export. Three things
 * can take it away or keep it: the Hide the LIVE button switch, TikTok hiding it because LIVE has
 * a bottom tab, and TikTok hiding it because LIVE has a top tab. The tab filters answer those two
 * checks when they took the tab away. A report for #28 couldn't say which had run, since none of
 * them recorded anything and a Hook status line only counts; this section counts each outcome.
 */
public final class LiveButtonReport implements LogBufferManager.ReportSection {
    static final String TITLE = "LIVE BUTTON";
    static final String FAMILY = "LIVE button";

    /** Each outcome, as the line the export shows for it. */
    public enum Route {
        SWITCH_HID("Hidden by the Hide the LIVE button switch"),
        TIKTOK_OFF("Turned off by TikTok itself"),
        SHOWN("Left on"),
        BOTTOM_TAB_TAKEN("Kept, because the bottom LIVE tab was taken off"),
        BOTTOM_TAB("Hidden by TikTok, because LIVE has a bottom tab"),
        TOP_TAB_TAKEN("Kept, because the top LIVE tab was taken off"),
        TOP_TAB("Hidden by TikTok, because LIVE has a top tab");

        final String line;

        Route(String line) {
            this.line = line;
        }
    }

    private static final LiveButtonReport INSTANCE = new LiveButtonReport();
    private static final AtomicLongArray COUNTS = new AtomicLongArray(Route.values().length);
    private static volatile boolean installed;

    private LiveButtonReport() {
    }

    /** Counts one decision. Never throws into TikTok's own check. */
    public static void record(Route route) {
        try {
            if (!installed) {
                installed = true;
                LogBufferManager.registerReportSection(INSTANCE);
            }
            COUNTS.incrementAndGet(route.ordinal());
            HookStatus.bound(FAMILY, route.name());
        } catch (Throwable ignored) {
            // A report that counts one decision fewer beats a LIVE button check that throws.
        }
    }

    @Override
    public String title() {
        return TITLE;
    }

    @Override
    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        for (Route route : Route.values()) {
            long count = COUNTS.get(route.ordinal());
            if (count > 0) lines.add(route.line + ": " + count + (count == 1 ? " time" : " times"));
        }
        return lines;
    }

    static LiveButtonReport sectionForTests() {
        return INSTANCE;
    }

    static void resetForTests() {
        for (int i = 0; i < COUNTS.length(); i++) COUNTS.set(i, 0);
    }
}
