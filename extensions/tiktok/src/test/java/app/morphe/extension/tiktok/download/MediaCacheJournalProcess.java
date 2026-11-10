/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** A separate process with no Android dependencies, used to force a real journal RMW overlap. */
public final class MediaCacheJournalProcess {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("lock journal new-key");
        File lockFile = new File(args[0]);
        File journal = new File(args[1]);
        try (RandomAccessFile file = new RandomAccessFile(lockFile, "rw");
             FileChannel channel = file.getChannel();
             FileLock lock = channel.lock()) {
            // Read before announcing readiness, then keep this snapshot until the other
            // publisher tries its operation. A missing transaction lock loses one writer.
            String before = journal.exists()
                    ? new String(Files.readAllBytes(journal.toPath()), StandardCharsets.UTF_8) : "";
            System.out.println("JOURNAL_LOCKED");
            System.out.flush();
            if (System.in.read() < 0) throw new IllegalStateException("No release command");
            String record = System.currentTimeMillis() + "\t" + args[2] + "\n";
            Files.write(journal.toPath(), (before + record).getBytes(StandardCharsets.UTF_8));
        }
    }
}
