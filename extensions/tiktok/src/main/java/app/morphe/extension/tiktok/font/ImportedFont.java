/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * The idea of drawing TikTok's text in a font file the reader imports comes from onlynazril's
 * TikTok patches (GPL-3.0). This is Hushfeed's own take: it stands behind Use system font's hook
 * rather than a second one, and keeps the weight the engine asked for.
 */
package app.morphe.extension.tiktok.font;

import android.content.Context;
import android.graphics.Typeface;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The one font file the reader imported for TikTok's text, kept in TikTok's own files.
 *
 * <p>Use an imported font draws TikTok's text in this file through the same hook as Use system
 * font ({@link SystemFont}). The file is copied out of the file app into
 * {@code files/hushfeed_fonts}, so it outlives the picker's permission, and a new import replaces
 * the old one. The face is loaded once a process and kept: the font engine asks for a face every
 * time it builds a text style, and the file only changes through this class, which drops the
 * kept face when it does.
 *
 * <p>An import is checked twice before it can replace anything: its first four bytes have to be
 * a TrueType or OpenType signature, and Android has to build a face from it. Android 8 and 9
 * answer a file they can't read with {@link Typeface#DEFAULT} rather than an error, so that
 * answer counts as a refusal too.
 */
public final class ImportedFont {
    /** The largest font file the import takes. Large CJK fonts run to about 16 MB. */
    public static final long MAX_BYTES = 20L * 1024 * 1024;

    static final String DIRECTORY = "hushfeed_fonts";
    /** Where an import waits while it's checked. A dot name, so it's never taken for the font. */
    static final String INCOMING = ".incoming";
    /** The prefix the earlier font waits under while its replacement moves in. A dot name too. */
    static final String PREVIOUS = ".previous-";
    private static final int NAME_LIMIT = 80;

    /** Why a file was turned down. */
    public enum Reason { TOO_LARGE, NOT_A_FONT }

    /** A file the import turned down, and why. */
    public static final class Rejected extends IOException {
        public final Reason reason;

        Rejected(Reason reason) {
            super("Font file refused: " + reason);
            this.reason = reason;
        }
    }

    /** A checked file waiting to replace the imported font. */
    public static final class Incoming {
        private final File file;
        private final String name;

        private Incoming(File file, String name) {
            this.file = file;
            this.name = name;
        }

        /** The name the font is kept under. */
        public String name() {
            return name;
        }

        /**
         * Makes this the imported font, in place of any other. Returns the name it's kept under.
         *
         * <p>The earlier font is set aside whole before this one moves in, and deleted only once
         * this one is in place. A move that fails puts the earlier font back, and a replacement
         * the process died in is settled the next time the font is looked for ({@link #recover}),
         * so the folder always holds one whole font, old or new.
         */
        public String install() throws IOException {
            File directory = file.getParentFile();
            File target = new File(directory, name);
            synchronized (LOCK) {
                recover(directory);
                List<File> setAside = new ArrayList<>();
                try {
                    for (File old : fontFiles(directory)) {
                        File aside = new File(directory, PREVIOUS + old.getName());
                        if (!moves.rename(old, aside)) {
                            throw new IOException("Could not set the earlier font " + old.getName() + " aside");
                        }
                        setAside.add(aside);
                    }
                    if (!moves.rename(file, target)) {
                        throw new IOException("Could not keep the font as " + target.getName());
                    }
                } catch (IOException failure) {
                    putBack(setAside);
                    throw failure;
                }
                for (File aside : setAside) {
                    if (!moves.delete(aside)) {
                        Logger.printDebug(() -> "Could not delete the earlier font " + aside.getName() + " yet");
                    }
                }
                forget();
            }
            return name;
        }

        /** Throws the checked file away, for an import stopped before it changed anything. */
        public void discard() {
            if (file.exists() && !file.delete()) {
                Logger.printDebug(() -> "Could not delete an unused font import");
            }
        }
    }

    /** The file moves a replacement makes, one at a time. */
    interface Moves {
        boolean rename(File from, File to);

        boolean delete(File file);
    }

    static final Moves FILES = new Moves() {
        @Override public boolean rename(File from, File to) {
            return from.renameTo(to);
        }

        @Override public boolean delete(File file) {
            return file.delete();
        }
    };
    /** {@link #FILES}, except in tests that fail or stop a replacement at a chosen move. */
    static volatile Moves moves = FILES;

    private static final Object LOCK = new Object();
    /** Whether {@link #kept} holds this process's answer, a face or null. */
    private static volatile boolean known;
    private static volatile Typeface kept;

    private ImportedFont() {
    }

    /**
     * The imported face, or null when nothing is imported, the file is gone or Android can't
     * read it. Loaded the first time it's asked for and kept for the process. Never throws: it
     * is asked from inside TikTok's font engine.
     */
    public static Typeface typeface() {
        if (known) return kept;
        try {
            Context context = Utils.getContext();
            // Not known yet, so not kept either: the next ask, with a context, loads it.
            if (context == null) return null;
            synchronized (LOCK) {
                if (!known) {
                    File file = storedFile(context);
                    kept = file == null ? null : load(file);
                    known = true;
                }
                return kept;
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not load the imported font", failure);
            return null;
        }
    }

    /** The imported font's file name, or null when there is none. */
    public static String name(Context context) {
        if (context == null) return null;
        synchronized (LOCK) {
            File file = storedFile(context);
            return file == null ? null : file.getName();
        }
    }

    /**
     * Copies a font out of {@code input} and checks it, without touching the font already
     * imported. {@code displayName} is the name the file app gave it, which the font is kept
     * under once installed. Closes {@code input}.
     *
     * @throws Rejected when the file is too large or isn't a font Android can use.
     */
    public static Incoming receive(Context context, InputStream input, String displayName) throws IOException {
        File directory = directory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            input.close();
            throw new IOException("Could not make " + directory);
        }
        File incoming = new File(directory, INCOMING);
        boolean accepted = false;
        try {
            byte[] head = copy(input, incoming);
            String extension = extensionFor(head);
            if (extension == null || load(incoming) == null) throw new Rejected(Reason.NOT_A_FONT);
            accepted = true;
            return new Incoming(incoming, storedName(displayName, extension));
        } finally {
            if (!accepted && incoming.exists() && !incoming.delete()) {
                Logger.printDebug(() -> "Could not delete a refused font import");
            }
        }
    }

    /** Removes the imported font. True when there's none left afterwards. */
    public static boolean remove(Context context) {
        synchronized (LOCK) {
            boolean gone = true;
            File directory = directory(context);
            // A font set aside by a cut-short replacement goes first, or the next look would put it
            // back. One that won't go keeps the font in use where it is, so Remove fails as a whole.
            for (File file : setAside(directory)) gone &= moves.delete(file);
            if (gone) {
                for (File file : fontFiles(directory)) gone &= moves.delete(file);
            }
            forget();
            return gone;
        }
    }

    /** Drops the kept face, so the next ask reads the file again. Tests use it between cases. */
    static void forget() {
        synchronized (LOCK) {
            kept = null;
            known = false;
        }
    }

    private static File directory(Context context) {
        return new File(context.getFilesDir(), DIRECTORY);
    }

    /** The imported font, the newest if a crash ever left two. Called holding {@link #LOCK}. */
    private static File storedFile(Context context) {
        File directory = directory(context);
        recover(directory);
        File newest = null;
        for (File file : fontFiles(directory)) {
            if (newest == null || file.lastModified() > newest.lastModified()) newest = file;
        }
        return newest;
    }

    /**
     * Settles a replacement the process died in. With a font in place, the one set aside for it
     * is deleted; with none, the set-aside font is put back. Called holding {@link #LOCK}.
     */
    static void recover(File directory) {
        File[] aside = setAside(directory);
        if (aside.length == 0) return;
        if (fontFiles(directory).length == 0) {
            putBack(Arrays.asList(aside));
            return;
        }
        for (File file : aside) {
            if (!moves.delete(file)) Logger.printDebug(() -> "Could not delete the earlier font " + file.getName());
        }
    }

    /** Moves fonts set aside back under their own names. */
    private static void putBack(List<File> aside) {
        for (File file : aside) {
            File back = new File(file.getParentFile(), file.getName().substring(PREVIOUS.length()));
            if (!moves.rename(file, back)) {
                Logger.printInfo(() -> "Could not put the earlier font " + back.getName() + " back yet");
            }
        }
    }

    private static File[] fontFiles(File directory) {
        File[] files = directory.listFiles(file -> file.isFile() && !file.getName().startsWith("."));
        return files == null ? new File[0] : files;
    }

    private static File[] setAside(File directory) {
        File[] files = directory.listFiles(file -> file.isFile() && file.getName().startsWith(PREVIOUS));
        return files == null ? new File[0] : files;
    }

    /** Copies at most {@link #MAX_BYTES}, and hands back the first four bytes. */
    private static byte[] copy(InputStream input, File target) throws IOException {
        byte[] head = new byte[4];
        int headLength = 0;
        long total = 0;
        try (InputStream in = input; OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BYTES) throw new Rejected(Reason.TOO_LARGE);
                for (int index = 0; index < read && headLength < head.length; index++) {
                    head[headLength++] = buffer[index];
                }
                out.write(buffer, 0, read);
            }
        }
        return headLength == head.length ? head : null;
    }

    /**
     * The extension a font with this signature is kept under, or null for anything else: the
     * TrueType version (0x00010000 or "true"), "OTTO" for OpenType with CFF outlines, and
     * "ttcf" for a collection, whose first face Android uses. WOFF files ("wOFF") aren't fonts
     * Android can load.
     */
    static String extensionFor(byte[] head) {
        if (head == null || head.length < 4) return null;
        String tag = new String(head, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (head[0] == 0 && head[1] == 1 && head[2] == 0 && head[3] == 0) return ".ttf";
        switch (tag) {
            case "true": return ".ttf";
            case "OTTO": return ".otf";
            case "ttcf": return ".ttc";
            default: return null;
        }
    }

    /**
     * A safe file name from the file app's: letters, digits, spaces, dots, dashes and
     * underscores, ending in the extension the signature calls for.
     */
    static String storedName(String displayName, String extension) {
        String base = displayName == null ? "" : displayName.trim();
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            String given = base.substring(dot).toLowerCase(Locale.ROOT);
            if (given.equals(".ttf") || given.equals(".otf") || given.equals(".ttc")) base = base.substring(0, dot);
        }
        base = base.replaceAll("[^\\p{L}\\p{N} ._-]", "_").replaceAll("^[ .]+", "").trim();
        if (base.isEmpty()) base = "font";
        if (base.length() > NAME_LIMIT) base = base.substring(0, NAME_LIMIT).trim();
        return base + extension;
    }

    /** A face from the file, or null when Android can't make one of it. */
    private static Typeface load(File file) {
        if (!file.isFile() || file.length() < 4) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] head = new byte[4];
            if (in.read(head) != head.length || extensionFor(head) == null) return null;
        } catch (IOException unreadable) {
            return null;
        }
        try {
            Typeface face = Typeface.createFromFile(file);
            return face == null || face == Typeface.DEFAULT ? null : face;
        } catch (RuntimeException unreadable) {
            Logger.printInfo(() -> "Android can't read the font " + file.getName() + ": " + unreadable);
            return null;
        }
    }
}
