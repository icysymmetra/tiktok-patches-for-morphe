package app.morphe.extension.shared.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsRegistryRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A failed disk write must not leave the process claiming a setting was saved. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class SettingPersistenceTest {
    @Rule public final SettingsRegistryRule settingsRegistry = new SettingsRegistryRule();

    private static final class FailingSetting extends Setting<String> {
        FailingSetting(String key) {
            super(key, "before", false, false, null, null);
        }

        @Override protected void load() {
            value = defaultValue;
        }

        @Override protected void setValueFromString(String newValue) {
            value = newValue;
        }

        @Override protected void saveToPreferences() {
            throw new IllegalStateException("injected commit failure");
        }
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    @Test public void failedWriteRestoresTheValueThatIsStillOnDisk() {
        FailingSetting setting = new FailingSetting("persistence_failure_" + System.nanoTime());

        assertFalse("the caller was told a failed write succeeded", setting.save("after"));

        assertEquals("a failed write escaped into the live setting", "before", setting.get());
    }

    @Test public void aFailedWriteCannotRollBackANewerSuccessfulWrite() throws Exception {
        CountDownLatch firstCommitStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstCommit = new CountDownLatch(1);
        AtomicInteger commits = new AtomicInteger();
        Setting<String> setting = new Setting<String>(
                "concurrent_persistence_" + System.nanoTime(), "before", false, false, null, null) {
            @Override protected void load() { value = defaultValue; }
            @Override protected void setValueFromString(String newValue) { value = newValue; }
            @Override protected void saveToPreferences() {
                int attempt = commits.incrementAndGet();
                if (attempt == 1) {
                    firstCommitStarted.countDown();
                    try {
                        assertTrue("the test never released the failed commit",
                                releaseFirstCommit.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    throw new IllegalStateException("injected first commit failure");
                }
            }
        };

        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<Boolean> first = pool.submit(() -> setting.save("first"));
            assertTrue("the first write never reached persistence",
                    firstCommitStarted.await(5, TimeUnit.SECONDS));
            java.util.concurrent.Future<Boolean> second = pool.submit(() -> setting.save("second"));
            assertFalse("the newer write ran inside the older write's rollback window",
                    second.isDone());

            releaseFirstCommit.countDown();
            assertFalse("the injected failure reported success", first.get(5, TimeUnit.SECONDS));
            assertTrue("the later write did not succeed", second.get(5, TimeUnit.SECONDS));
            assertEquals("the failed write erased a later successful value", "second", setting.get());
        } finally {
            releaseFirstCommit.countDown();
            pool.shutdownNow();
        }
    }

    @Test public void aThrownBatchCommitRestoresLiveValuesAsWellAsStorage() throws Exception {
        StringSetting first = new StringSetting("batch_throw_first_" + System.nanoTime(), "before", false, false);
        StringSetting second = new StringSetting("batch_throw_second_" + System.nanoTime(), "before", false, false);
        assertTrue(first.save("stored-first"));
        assertTrue(second.save("stored-second"));
        try (var failure = new app.morphe.extension.tiktok.PreferenceCommitFailure(keys -> true, true)) {
            org.junit.Assert.assertThrows(Exception.class,
                    () -> Setting.saveAll(java.util.Map.of(first, "after-first", second, "after-second")));
            assertEquals("a thrown commit left the first live value changed", "stored-first", first.savedValue());
            assertEquals("a thrown commit left the second live value changed", "stored-second", second.savedValue());
            assertEquals("stored-first", Setting.preferences.preferences.getString(first.key, null));
            assertEquals("stored-second", Setting.preferences.preferences.getString(second.key, null));
        }
    }
}
