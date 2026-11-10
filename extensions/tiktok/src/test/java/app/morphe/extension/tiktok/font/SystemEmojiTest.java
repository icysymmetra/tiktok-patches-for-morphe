/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.font;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Use system emoji: EmojiCompat is told the device's font has every emoji only while the switch is
 * on. Everywhere else its own glyph check runs, so Google's font fills in what the device lacks.
 * The pause is held for every switch by PauseAnswersUnpatchedTest.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SystemEmojiTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        HookStatus.clear();
        Settings.SYSTEM_EMOJI.resetToDefault();
    }

    @After public void tearDown() {
        Settings.SYSTEM_EMOJI.resetToDefault();
        HookStatus.clear();
    }

    @Test public void theSwitchStartsOffSoTikToksOwnCheckRuns() {
        assertFalse("the switch starts on", Settings.SYSTEM_EMOJI.get());
        assertFalse("the device was handed every emoji with the switch off", SystemEmoji.leaveToDevice());
    }

    @Test public void switchedOnEveryEmojiIsLeftToTheDevice() {
        Settings.SYSTEM_EMOJI.save(true);
        assertTrue("EmojiCompat's own check ran with the switch on", SystemEmoji.leaveToDevice());
    }

    @Test public void switchedBackOffTikToksOwnCheckRunsAgain() {
        Settings.SYSTEM_EMOJI.save(true);
        SystemEmoji.leaveToDevice();
        Settings.SYSTEM_EMOJI.save(false);
        assertFalse(SystemEmoji.leaveToDevice());
    }

    @Test public void theSwitchAsksForARestart() {
        // A span EmojiCompat already made keeps Google's drawing until its text is set again.
        assertTrue(Settings.SYSTEM_EMOJI.rebootApp);
    }

    @Test public void theExportNamesTheHookWhicheverWayTheSwitchIsSet() {
        SystemEmoji.leaveToDevice();
        String off = String.join("\n", HookStatus.report());
        assertTrue(off, off.contains("system emoji"));

        HookStatus.clear();
        Settings.SYSTEM_EMOJI.save(true);
        SystemEmoji.leaveToDevice();
        String on = String.join("\n", HookStatus.report());
        assertTrue(on, on.contains("system emoji"));
    }
}
