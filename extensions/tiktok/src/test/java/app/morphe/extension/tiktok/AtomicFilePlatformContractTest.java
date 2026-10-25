package app.morphe.extension.tiktok;

import static org.junit.Assert.*;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The Android AtomicFile contract must hold on the desktop host before publisher tests run. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AtomicFilePlatformContractTest {
    @Test public void aSecondCommitReplacesTheFirstAndRetiresTheStagedFile() throws Exception {
        File file = new File(RuntimeEnvironment.getApplication().getFilesDir(), "atomic-contract.txt");
        AtomicFile atomic = new AtomicFile(file);
        write(atomic, "first");
        assertEquals("first", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        write(atomic, "second");
        assertEquals("second", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        assertFalse(new File(file + ".new").exists());
    }

    @Test public void rollbackKeepsThePreviousCommittedValue() throws Exception {
        File file = new File(RuntimeEnvironment.getApplication().getFilesDir(), "atomic-rollback.txt");
        AtomicFile atomic = new AtomicFile(file);
        write(atomic, "first");
        FileOutputStream output = atomic.startWrite();
        output.write("aborted".getBytes(StandardCharsets.UTF_8));
        atomic.failWrite(output);
        assertEquals("first", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void anUnreplaceableDestinationStillLeavesAnObservableFailedCommit() throws Exception {
        File target = new File(RuntimeEnvironment.getApplication().getFilesDir(), "nonempty-directory");
        assertTrue(target.mkdir());
        File retained = new File(target, "keep");
        assertTrue(retained.createNewFile());
        write(new AtomicFile(target), "staged");
        assertTrue(target.isDirectory());
        assertTrue(retained.isFile());
        assertTrue(new File(target + ".new").isFile());
    }

    private static void write(AtomicFile atomic, String value) throws Exception {
        FileOutputStream output = atomic.startWrite();
        output.write(value.getBytes(StandardCharsets.UTF_8));
        atomic.finishWrite(output);
    }
}
