/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.storage;

import android.content.Context;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Where TikTok's storage goes, for the diagnostic export (#70). A reader measured 2.15 GB of user
 * data that stayed after clearing the cache, TikTok's downloads and Forget saved videos, and
 * nothing in a report could say which store held it. This section lists the biggest folders in
 * TikTok's private storage and in its folder under Android/data, going deeper only where a folder
 * is big. Folder names only, never file names, and long numbers masked, since some of TikTok's
 * folders are named after an account.
 *
 * <p>On a full phone the walk takes seconds, so it runs on its own thread when the Diagnostics
 * page opens, and the export shows what it found. Asked on the main thread before the walk is
 * done, the section says so rather than walking there.
 */
public final class StorageReport implements LogBufferManager.ReportSection {
    static final String TITLE = "STORAGE";
    static final long TIME_BUDGET_MS = 8_000L;
    static final int FILE_BUDGET = 500_000;
    private static final long MB = 1024L * 1024L;
    private static final long GB = 1024L * MB;
    /** How deep folders are listed, and what each level needs to be listed. */
    private static final int LISTED_DEPTH = 3;
    private static final long[] SHOWN_FROM = {MB, 5 * MB, 5 * MB};
    private static final long[] OPENED_FROM = {10 * MB, 50 * MB};
    private static final int[] SHOWN_AT_MOST = {15, 6, 4};

    private static final StorageReport INSTANCE = new StorageReport();
    private static final Object LOCK = new Object();
    private static List<String> measured;
    private static boolean measuring;

    private StorageReport() {
    }

    /** Puts the section in the export and measures again. Never throws into the settings page. */
    public static void prepare(Context context) {
        try {
            LogBufferManager.registerReportSection(INSTANCE);
            Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            synchronized (LOCK) {
                if (measuring) return;
                measuring = true;
            }
            boolean started = Utils.runOnOwnThread("hushfeed-storage", () -> {
                List<String> lines;
                try {
                    lines = measure(roots(app), System.nanoTime() / 1_000_000L + TIME_BUDGET_MS, FILE_BUDGET);
                } catch (Throwable failure) {
                    lines = Collections.singletonList("could not be measured: " + failure);
                }
                synchronized (LOCK) {
                    measured = lines;
                    measuring = false;
                    LOCK.notifyAll();
                }
            });
            if (!started) {
                synchronized (LOCK) {
                    measuring = false;
                }
            }
        } catch (Throwable ignored) {
            // A report without the section beats a settings page that fails to open.
        }
    }

    @Override
    public String title() {
        return TITLE;
    }

    @Override
    public List<String> lines() {
        synchronized (LOCK) {
            // The file export runs on its own thread and can wait out a walk that's under way.
            if (measuring && Looper.myLooper() != Looper.getMainLooper()) {
                long until = System.nanoTime() / 1_000_000L + TIME_BUDGET_MS + 2_000L;
                while (measuring) {
                    long left = until - System.nanoTime() / 1_000_000L;
                    if (left <= 0) break;
                    try {
                        LOCK.wait(left);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (measured != null) return measured;
            return measuring
                    ? Collections.singletonList("still measuring, export again in a few seconds")
                    : Collections.<String>emptyList();
        }
    }

    /** TikTok's private folder and its folder under Android/data, each with the name the report gives it. */
    static List<Root> roots(Context context) {
        List<Root> roots = new ArrayList<>();
        // Context.getDataDir is API 24, and the payload's floor is 23.
        File files = context.getFilesDir();
        if (files != null && files.getParentFile() != null) roots.add(new Root("private", files.getParentFile()));
        File external = context.getExternalFilesDir(null);
        if (external != null && external.getParentFile() != null) roots.add(new Root("Android/data", external.getParentFile()));
        return roots;
    }

    static final class Root {
        final String label;
        final File dir;

        Root(String label, File dir) {
            this.label = label;
            this.dir = dir;
        }
    }

    /** One folder's total, and its biggest folders while they're still listed. */
    private static final class Folder {
        final String name;
        long bytes;
        final List<Folder> folders = new ArrayList<>();

        Folder(String name) {
            this.name = name;
        }
    }

    private static final class Budget {
        final long deadline;
        final int files;
        int seen;
        boolean spent;

        Budget(long deadline, int files) {
            this.deadline = deadline;
            this.files = files;
        }

        boolean over() {
            if (!spent && (seen >= files || (seen % 256 == 0 && System.nanoTime() / 1_000_000L >= deadline))) {
                spent = true;
            }
            return spent;
        }
    }

    /**
     * The report's lines for {@code roots}, walking until {@code deadline} (System.nanoTime in
     * ms) or {@code fileBudget} entries, whichever comes first.
     */
    static List<String> measure(List<Root> roots, long deadline, int fileBudget) {
        long started = System.nanoTime();
        Budget budget = new Budget(deadline, fileBudget);
        List<String> lines = new ArrayList<>();
        for (Root root : roots) {
            Folder folder = new Folder(root.label);
            File canonical;
            try {
                canonical = root.dir.getCanonicalFile();
            } catch (IOException unreadable) {
                lines.add(root.label + ": could not be read");
                continue;
            }
            folder.bytes = walk(canonical, folder, 0, budget);
            lines.add(root.label + ": " + size(folder.bytes));
            render(folder, 0, "  ", lines);
        }
        long tookMs = (System.nanoTime() - started) / 1_000_000L;
        lines.add(String.format(Locale.US, "%,d entries in %.1f s%s", budget.seen, tookMs / 1000f,
                budget.spent ? ", stopped early so the totals are low" : ""));
        return lines;
    }

    private static long walk(File dir, Folder folder, int depth, Budget budget) {
        String[] names = dir.list();
        if (names == null) return 0L;
        long total = 0L;
        for (String name : names) {
            if (budget.over()) break;
            budget.seen++;
            File child = new File(dir, name);
            if (child.isDirectory()) {
                // A link to a folder elsewhere (TikTok's native libraries) isn't this folder's.
                if (!isOwnFolder(dir, child)) continue;
                Folder sub = depth < LISTED_DEPTH ? new Folder(name) : null;
                long bytes = walk(child, sub, depth + 1, budget);
                if (sub != null && folder != null) {
                    sub.bytes = bytes;
                    folder.folders.add(sub);
                }
                total += bytes;
            } else {
                total += child.length();
            }
        }
        return total;
    }

    private static boolean isOwnFolder(File parent, File child) {
        try {
            return child.getCanonicalFile().equals(new File(parent, child.getName()));
        } catch (IOException unreadable) {
            return false;
        }
    }

    private static void render(Folder folder, int depth, String indent, List<String> lines) {
        if (depth >= SHOWN_FROM.length) return;
        List<Folder> folders = new ArrayList<>(folder.folders);
        Collections.sort(folders, (a, b) -> Long.compare(b.bytes, a.bytes));
        int shown = 0;
        for (Folder sub : folders) {
            if (sub.bytes < SHOWN_FROM[depth] || shown >= SHOWN_AT_MOST[depth]) break;
            shown++;
            lines.add(indent + mask(sub.name) + ": " + size(sub.bytes));
            if (depth < OPENED_FROM.length && sub.bytes >= OPENED_FROM[depth]) {
                render(sub, depth + 1, indent + "  ", lines);
            }
        }
    }

    /** A folder name with account ids, account-looking numbers and long hashes masked, kept short. */
    static String mask(String name) {
        // A long run mixing capitals and digits is an encoded id such as the sec_uid TikTok names
        // its offline mode folder after (MS4wLjABAAAA...). A hash has a letter in it; a run of
        // digits alone is an id, and reads as one.
        String masked = name
                .replaceAll("(?<![A-Za-z0-9_-])(?=[A-Za-z0-9_-]*[A-Z])(?=[A-Za-z0-9_-]*[0-9])"
                        + "[A-Za-z0-9_-]{24,}", "<account>")
                .replaceAll("(?<![0-9a-fA-F])(?=[0-9a-fA-F]{16,})(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]+", "<hash>")
                .replaceAll("[0-9]{6,}", "#");
        return masked.length() > 48 ? masked.substring(0, 45) + "..." : masked;
    }

    static String size(long bytes) {
        if (bytes >= GB) return String.format(Locale.US, "%.2f GB", bytes / (double) GB);
        if (bytes >= MB) return String.format(Locale.US, "%,d MB", bytes / MB);
        return "under 1 MB";
    }

    static void resetForTests() {
        synchronized (LOCK) {
            measured = null;
            measuring = false;
        }
    }
}
