/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import app.morphe.extension.shared.Logger;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class MediaFileWriter {
    private MediaFileWriter() {}

    /** What a publish left in the gallery: the name MediaStore kept, and its row when there is one. */
    // java.util.function requires API 24; the injected payload also runs on API 23.
    interface CopyProgress {
        void copied(long bytes);
    }

    static final class Saved {
        final String name;
        /** Null below API 29, where the file goes straight to disk and only the scanner sees it. */
        final Uri uri;
        /** The legacy path, retained so a later already-saved choice can open the file. */
        final File file;

        Saved(String name, Uri uri) {
            this(name, uri, null);
        }

        Saved(String name, Uri uri, File file) {
            this.name = name;
            this.uri = uri;
            this.file = file;
        }
    }

    static String publish(Context context, File source, String name, String mime, String path, boolean video) throws IOException {
        return publishForResult(context, source, name, mime, path, video).name;
    }

    static Saved publishForResult(Context context, File source, String name, String mime, String path, boolean video) throws IOException {
        return publishForResult(context, source, name, mime, path, video, MediaBudget.MAX_TRANSFER_BYTES);
    }

    /** As above, for a file that may be larger than a post's download is allowed to be. */
    static Saved publishForResult(Context context, File source, String name, String mime, String path, boolean video,
            long maxBytes) throws IOException {
        MediaBudget.check(null);
        File destinationDirectory = path == null
                ? Environment.getExternalStorageDirectory()
                : new File(Environment.getExternalStorageDirectory(), path);
        MediaBudget.checkDiskSpace(destinationDirectory, source == null ? -1L : source.length(), null, maxBytes);
        if (Build.VERSION.SDK_INT >= 29) {
            var resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, path);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri collection;
            if ("application/x-subrip".equals(mime) || "text/plain".equals(mime) || "application/json".equals(mime)) {
                collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            } else if (mime.startsWith("audio/")) {
                // Sound goes in the audio collection whatever folder the video chose; the
                // video collection rejects it.
                collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            } else {
                collection = DownloadDestination.collectionUri(path, video);
            }
            Uri uri = MediaCache.insertPending(context, resolver, collection, values);
            // On disk before the copy, so the next start can look the row up should the process
            // die anywhere between here and the save hearing that it landed.
            SaveRecords.Slot slot = SaveRecords.located(uri);
            try {
                try (InputStream input = new FileInputStream(source); OutputStream output = resolver.openOutputStream(uri, "w")) {
                    if (output == null) throw new IOException("Could not open gallery entry");
                    copy(input, output, maxBytes, null,
                            destinationDirectory);
                }
                MediaBudget.check(null);
                // The row went in under a name MediaStore could not collide with, so this is
                // where it takes the one the reader asked for. A rename here is safe: the
                // journal is holding the row's URI, so an interruption is recoverable whatever
                // the row ends up called.
                values.clear();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (resolver.update(uri, values, null, null) != 1) throw new IOException("Could not publish gallery entry");
                // Read after the rename, not before it: MediaStore may still have had to make
                // the reader's name unique, and what is reported has to be what landed.
                String savedName;
                try (var cursor = resolver.query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor == null || !cursor.moveToFirst()) throw new IOException("Could not read saved filename");
                    savedName = cursor.getString(0);
                    if (savedName == null || savedName.isEmpty()) throw new IOException("Saved filename is empty");
                }
                if (savedName.startsWith(MediaCache.TEMPORARY_NAME_PREFIX)) {
                    throw new IOException("Gallery entry kept its placeholder name");
                }
                try {
                    MediaCache.clearPending(context, uri);
                } catch (IOException journalError) {
                    // Reconciliation checks IS_PENDING before deleting a journaled URI, so a
                    // completed row remains safe if this final cleanup write is interrupted.
                    Logger.printException(() -> "Could not clear media publication journal", journalError);
                }
                SaveRecords.published(slot);
                return new Saved(savedName, uri);
            } catch (IOException | RuntimeException exception) {
                boolean deleted = false;
                try {
                    deleted = resolver.delete(uri, null, null) > 0;
                } catch (RuntimeException cleanup) {
                    exception.addSuppressed(cleanup);
                }
                if (deleted) {
                    try {
                        MediaCache.clearPending(context, uri);
                    } catch (IOException journalError) {
                        exception.addSuppressed(journalError);
                    }
                }
                // The save reports this failure itself; the record only drops the file.
                SaveRecords.abandoned(slot);
                throw exception;
            }
        } else {
            File directory = new File(Environment.getExternalStorageDirectory(), path);
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create download folder");
            File target = claim(directory, name);
            // With the size a complete copy has, so the next start can tell it from a cut-off one.
            SaveRecords.Slot slot = SaveRecords.located(target, source.length());
            boolean landed = false;
            try {
                try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(target)) {
                    copy(input, output, maxBytes, null, directory);
                } catch (IOException exception) {
                    if (!target.delete()) exception.addSuppressed(new IOException("Could not remove incomplete download"));
                    throw exception;
                }
                MediaScannerConnection.scanFile(context, new String[]{target.getAbsolutePath()}, new String[]{mime}, null);
                landed = true;
            } finally {
                if (landed) SaveRecords.published(slot);
                else SaveRecords.abandoned(slot);
            }
            return new Saved(target.getName(), null, target);
        }
    }

    /**
     * An empty file in {@code directory} that nothing else is using, created here so two
     * saves cannot pick the same name. A second copy of the same sticker becomes name_2,
     * then name_3, rather than replacing the one already there.
     */
    static File claim(File directory, String name) throws IOException {
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        String extension = dot < 0 ? "" : name.substring(dot);
        File target = new File(directory, name);
        int suffix = 1;
        while (!target.createNewFile()) {
            if (++suffix > 1000) throw new IOException("Too many files with that name");
            target = new File(directory, stem + "_" + suffix + extension);
        }
        return target;
    }

    static long copy(InputStream input, OutputStream output) throws IOException {
        return copy(input, output, Long.MAX_VALUE, null);
    }

    /** Gives encoders the same bounded disk grants used by file copies. */
    static OutputStream withDiskBudget(OutputStream output, File directory) {
        return new FilterOutputStream(output) {
            private long allowance;

            private void reserve() throws IOException {
                MediaBudget.check(null);
                if (allowance == 0) {
                    MediaBudget.checkStreamingDiskSpace(directory, null);
                    allowance = MediaBudget.STREAM_SPACE_CHECK_BYTES;
                }
            }

            @Override public void write(int value) throws IOException {
                reserve();
                out.write(value);
                allowance--;
            }

            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                if (offset < 0 || length < 0 || offset > bytes.length - length) {
                    throw new IndexOutOfBoundsException();
                }
                while (length > 0) {
                    reserve();
                    int count = (int) Math.min(length, allowance);
                    out.write(bytes, offset, count);
                    allowance -= count;
                    offset += count;
                    length -= count;
                }
            }
        };
    }

    /**
     * Copies until the input runs out, or until {@code limit} bytes have gone past. An empty
     * result is an error: a zero byte file in the gallery looks like a save that worked.
     */
    static long copy(InputStream input, OutputStream output, long limit) throws IOException {
        return copy(input, output, limit, null);
    }

    static long copy(InputStream input, OutputStream output, long limit,
            MediaBudget.Deadline deadline) throws IOException {
        return copy(input, output, limit, deadline, null);
    }

    static long copy(InputStream input, OutputStream output, long limit,
            MediaBudget.Deadline deadline, File targetDirectory) throws IOException {
        return copy(input, output, limit, deadline, targetDirectory, null);
    }

    static long copy(InputStream input, OutputStream output, long limit,
            MediaBudget.Deadline deadline, File targetDirectory,
            CopyProgress copied) throws IOException {
        byte[] buffer = new byte[65536];
        long total = 0;
        long spaceAllowance = 0;
        int count;
        while (true) {
            MediaBudget.check(deadline);
            count = input.read(buffer);
            if (count == -1) break;
            if (count > 0) MediaBudget.moved(deadline);
            total += count;
            if (total > limit) {
                throw new IOException("That file is larger than " + (limit >> 20) + " MB");
            }
            if (targetDirectory != null && spaceAllowance < count) {
                MediaBudget.checkStreamingDiskSpace(targetDirectory, deadline);
                spaceAllowance = MediaBudget.STREAM_SPACE_CHECK_BYTES;
            }
            output.write(buffer, 0, count);
            if (copied != null) copied.copied(total);
            if (targetDirectory != null) spaceAllowance -= count;
        }
        if (total == 0) throw new IOException("Download is empty");
        output.flush();
        return total;
    }
}
