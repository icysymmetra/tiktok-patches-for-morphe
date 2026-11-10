package app.morphe.extension.tiktok.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Collections;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The storage section of the diagnostic export (#70): a reader's 2.15 GB of user data couldn't be
 * put down to any store, so the report lists the biggest folders, deeper only where they're big.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class StorageReportTest {
    private static final long MB = 1024L * 1024L;

    @Rule
    public TemporaryFolder files = new TemporaryFolder();

    /** A file of {@code bytes} that takes no room, since only its length is read. */
    private File sized(File dir, String name, long bytes) throws IOException {
        dir.mkdirs();
        File file = new File(dir, name);
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(bytes);
        }
        return file;
    }

    private static long later() {
        return System.nanoTime() / 1_000_000L + 60_000L;
    }

    @Test
    public void theBiggestFoldersAreListedAndOnlyBigOnesAreOpened() throws IOException {
        File root = files.newFolder("data");
        sized(new File(root, "files/big"), "a.bin", 60 * MB);
        sized(new File(root, "files/big/deep"), "b.bin", 6 * MB);
        sized(new File(root, "files/small"), "c.bin", 100 * 1024L);
        sized(new File(root, "cache"), "d.bin", 2 * MB);
        sized(root, "x.bin", MB);

        List<String> lines = StorageReport.measure(
                Collections.singletonList(new StorageReport.Root("private", root)), later(), 1000);

        assertEquals("private: 69 MB", lines.get(0));
        assertEquals("  files: 66 MB", lines.get(1));
        assertEquals("a folder over 10 MB shows what's in it", "    big: 66 MB", lines.get(2));
        assertEquals("one over 50 MB goes a level deeper", "      deep: 6 MB", lines.get(3));
        assertEquals("small folders aren't listed", "  cache: 2 MB", lines.get(4));
        assertEquals(6, lines.size());
        assertTrue(lines.get(5), lines.get(5).startsWith("10 entries in "));
        assertTrue(lines.get(5), !lines.get(5).contains("stopped early"));
    }

    @Test
    public void aWalkOverItsBudgetSaysItsTotalsAreLow() throws IOException {
        File root = files.newFolder("data");
        for (int i = 0; i < 5; i++) sized(root, "f" + i, MB);

        List<String> lines = StorageReport.measure(
                Collections.singletonList(new StorageReport.Root("private", root)), later(), 2);

        assertEquals("private: 2 MB", lines.get(0));
        assertTrue(lines.get(1), lines.get(1).startsWith("2 entries in ")
                && lines.get(1).endsWith("stopped early so the totals are low"));
    }

    @Test
    public void folderNamesKeepNoAccountNumbersOrHashes() {
        assertEquals("im_#", StorageReport.mask("im_7301234567890123"));
        assertEquals("cache_<hash>", StorageReport.mask("cache_9f86d081884c7d659a2feaa0c55ad015"));
        assertEquals("effect", StorageReport.mask("effect"));
        // TikTok names its offline mode folder after the account's sec_uid (S22, 2026-10-14).
        assertEquals("<account>", StorageReport.mask(
                "MS4wLjABAAAA34LLrXri8BcF6OTb5Kl1xRglYyfLR5F1Lq_Hx-9Zk2TzQwErTy"));
        assertEquals("offlineX_us_ttp", StorageReport.mask("offlineX_us_ttp"));
        assertEquals("<hash>_arm64_v8a", StorageReport.mask("9f86d081884c7d659a2feaa0c55ad015_arm64_v8a"));
        assertEquals(48, StorageReport.mask("a_very_long_folder_name_that_goes_on_and_on_for_ever").length());
    }

    @Test
    public void sizesReadInMegabytesUntilAGigabyte() {
        assertEquals("under 1 MB", StorageReport.size(MB - 1));
        assertEquals("1,023 MB", StorageReport.size(1023 * MB));
        assertEquals("2.15 GB", StorageReport.size((long) (2.15 * 1024 * MB)));
    }
}
