package app.hushfeed.verification;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** AtomicFile.finishWrite can silently fail. Trust only synced bytes read from its committed base. */
final class VerifiedJournal {
    interface Commit { void finish(FileOutputStream output) throws Exception; }

    static void write(FileOutputStream output, File base, byte[] expected, int limit, Commit commit) throws Exception {
        if (expected.length > limit) throw new IOException("journal exceeds byte limit");
        output.write(expected);
        output.getFD().sync();
        commit.finish(output);
        byte[] committed = read(new FileInputStream(base), limit);
        if (!Arrays.equals(expected, committed)) throw new IOException("committed journal did not match snapshot");
    }

    static byte[] read(FileInputStream input, int limit) throws IOException {
        try (FileInputStream stream = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (out.size() + count > limit) throw new IOException("journal exceeds byte limit");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }
}
