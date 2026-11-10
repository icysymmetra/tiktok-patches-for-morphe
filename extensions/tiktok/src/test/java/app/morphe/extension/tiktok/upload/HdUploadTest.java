package app.morphe.extension.tiktok.upload;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.Context;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * Always upload in HD hands TikTok's three readers of its stored HD choice a 1, the value its own
 * switch stores when turned on, and otherwise passes the stored value through: 0 (never touched),
 * 1 (turned on) and 2 (turned off) all reach TikTok as they were.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class HdUploadTest {
    private static final int NEVER_SET = 0;
    private static final int TURNED_ON = 1;
    private static final int TURNED_OFF = 2;

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        SettingsStatus.hdUploadEnabled = true;
    }

    @After public void tearDown() {
        Utils.setContext(context);
        setPaused(false);
        SettingsStatus.hdUploadEnabled = false;
        Settings.ALWAYS_UPLOAD_HD.save(Settings.ALWAYS_UPLOAD_HD.defaultValue);
    }

    @Test public void offByDefaultTikToksChoiceGoesThrough() {
        assertFalse(Settings.ALWAYS_UPLOAD_HD.defaultValue);
        assertEquals(NEVER_SET, HdUpload.userChoice(NEVER_SET));
        assertEquals(TURNED_ON, HdUpload.userChoice(TURNED_ON));
        assertEquals(TURNED_OFF, HdUpload.userChoice(TURNED_OFF));
    }

    @Test public void onEveryStoredChoiceReadsAsTurnedOn() {
        Settings.ALWAYS_UPLOAD_HD.save(true);
        assertEquals(TURNED_ON, HdUpload.userChoice(NEVER_SET));
        assertEquals(TURNED_ON, HdUpload.userChoice(TURNED_ON));
        assertEquals(TURNED_ON, HdUpload.userChoice(TURNED_OFF));
        assertEquals(TURNED_ON, HdUpload.USER_CHOSE_HD);
    }

    @Test public void pausedTikToksChoiceGoesThrough() {
        Settings.ALWAYS_UPLOAD_HD.save(true);
        setPaused(true);
        assertEquals(NEVER_SET, HdUpload.userChoice(NEVER_SET));
        assertEquals(TURNED_OFF, HdUpload.userChoice(TURNED_OFF));
    }

    /** A read before the extension has a context leaves Settings unloaded and TikTok's value alone. */
    @Test public void withoutAContextTikToksChoiceGoesThrough() {
        Settings.ALWAYS_UPLOAD_HD.save(true);
        Utils.setContext(null);
        try {
            assertEquals(NEVER_SET, HdUpload.userChoice(NEVER_SET));
            assertEquals(TURNED_OFF, HdUpload.userChoice(TURNED_OFF));
        } finally {
            Utils.setContext(context);
        }
    }

    /** A switch saved on by an earlier build does nothing after a repatch without the patch. */
    @Test public void aSavedSwitchDoesNothingWithoutItsPatch() {
        Settings.ALWAYS_UPLOAD_HD.save(true);
        SettingsStatus.hdUploadEnabled = false;
        assertEquals(TURNED_OFF, HdUpload.userChoice(TURNED_OFF));
    }

    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ClassParameter.from(boolean.class, value));
    }
}
