/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Lets settings tests seed and read the saved-video record, which is package-private. */
public final class SavedVideoArchiveAccess {
    private SavedVideoArchiveAccess() {
    }

    public static void reset(Context context) {
        SavedVideoArchive.resetForTests();
        context.deleteDatabase(SavedVideoArchive.DATABASE_NAME);
    }

    public static void remember(Context context, String id) throws IOException {
        File file = new File(context.getCacheDir(), id + ".mp4");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[]{1, 2, 3, 4});
        }
        SavedVideoArchive.remember(context, id, new MediaFileWriter.Saved(id + ".mp4", null, file),
                SavedVideoArchive.generation());
    }

    public static boolean isRecorded(Context context, String id) {
        return SavedVideoArchive.find(context, id) != null;
    }
}
