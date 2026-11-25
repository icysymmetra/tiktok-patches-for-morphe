/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.adskip;

import app.morphe.extension.shared.Logger;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/** The last few scans and skips, shown under the ad list so a miss can be seen on the phone. */
final class AdSkipLog {
    private static final int MAX = 40;
    private static final LinkedList<String> LINES = new LinkedList<>();

    private AdSkipLog() {}

    static void add(String aid, String message) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date())
                + "  " + (aid == null ? "-" : tail(aid)) + "  " + message;
        Logger.printDebug(() -> "Ad skip: " + line);
        synchronized (LINES) {
            LINES.addFirst(line);
            while (LINES.size() > MAX) LINES.removeLast();
        }
    }

    static List<String> lines() {
        synchronized (LINES) {
            return new ArrayList<>(LINES);
        }
    }

    private static String tail(String aid) {
        return aid.length() <= 6 ? aid : "…" + aid.substring(aid.length() - 6);
    }
}
