package app.morphe.extension.tiktok;

import android.util.AtomicFile;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Android's File.renameTo replaces an existing file. The Windows JDK version does not.
 * Keep AtomicFile's real sync, close, rollback and verification logic, changing only this
 * platform seam on Windows. Subclassed File fault fixtures still receive their own renameTo.
 */
@Implements(value = AtomicFile.class, minSdk = 30)
public class AndroidAtomicFileShadow {
    @Implementation(minSdk = 30)
    protected static void rename(File source, File target) {
        if (target.isDirectory() && !target.delete()) {
            Log.e("AtomicFile", "Failed to delete file which is a directory " + target);
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                && source.getClass() == File.class && target.getClass() == File.class) {
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException failure) {
                Log.e("AtomicFile", "Failed to rename " + source + " to " + target, failure);
                return;
            }
        }
        if (!source.renameTo(target)) Log.e("AtomicFile", "Failed to rename " + source + " to " + target);
    }
}
