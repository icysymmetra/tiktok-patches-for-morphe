package app.hushfeed.verification;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Simulates a platform committer that silently leaves .new or stale/truncated base bytes. */
public final class VerifiedJournalContract {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("hushfeed-journal-contract-");
        Path base = directory.resolve("snapshot.json"), pending = directory.resolve("snapshot.json.new");
        byte[] original = "{\"original_budget\":true}".getBytes(StandardCharsets.UTF_8);
        try {
            VerifiedJournal.write(new FileOutputStream(pending.toFile()), base.toFile(), original, 128,
                    output -> { output.close(); Files.move(pending, base, StandardCopyOption.REPLACE_EXISTING); });
            require(Files.readAllBytes(base).length == original.length, "committed snapshot must be readable");
            for (String defect : new String[]{"missing", "stale", "truncated", "oversized"}) {
                Files.deleteIfExists(base);
                if ("stale".equals(defect)) Files.write(base, "wrong".getBytes(StandardCharsets.UTF_8));
                if ("truncated".equals(defect)) Files.write(base, new byte[]{'{'});
                if ("oversized".equals(defect)) Files.write(base, new byte[129]);
                boolean mutated = false;
                try {
                    VerifiedJournal.write(new FileOutputStream(pending.toFile()), base.toFile(), original, 128,
                            FileOutputStream::close); // Simulate a silently failed rename.
                    mutated = true;
                    throw new AssertionError("uncommitted journal accepted");
                } catch (IOException expected) { }
                require(!mutated, "fixture mutation must wait for exact committed bytes");
            }
            System.out.println("Verified journal commit contract passed");
        } finally {
            Files.deleteIfExists(pending);
            Files.deleteIfExists(base);
            Files.delete(directory);
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
