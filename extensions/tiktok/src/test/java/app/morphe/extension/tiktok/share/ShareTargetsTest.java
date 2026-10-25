package app.morphe.extension.tiktok.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.SettingsContextRule;

import com.ss.android.ugc.aweme.share.base.model.ShareChannelInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Apps picked under Add apps to Share via, added to the Share via row: before More, as TikTok's
 * own server-named channel, and only when they're installed, take shared text and aren't shown
 * already.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class ShareTargetsTest {
    private static final String TELEGRAM = "org.telegram.messenger";
    private static final String SIGNAL = "org.thoughtcrime.securesms";
    private static final String WHATSAPP = "com.whatsapp";
    private static final String NO_TEXT = "com.example.camera";

    /** A channel as TikTok's model holds it: a key, and for an app channel a package. */
    public static final class Channel {
        private final String key;
        final String packageName;
        final ShareChannelInfo info;

        Channel(String key, String packageName, ShareChannelInfo info) {
            this.key = key;
            this.packageName = packageName;
            this.info = info;
        }

        public String key() {
            return key;
        }
    }

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Context context;
    private final Map<Object, Object> icons = new HashMap<>();

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        install(TELEGRAM, "Telegram", true);
        install(SIGNAL, "Signal", true);
        install(WHATSAPP, "WhatsApp", true);
        install(NO_TEXT, "Camera", false);
        ShareTargets.nativeForTests = new ShareTargets.Native() {
            @Override public Object newChannel(Object info) {
                ShareChannelInfo channel = (ShareChannelInfo) info;
                return new Channel(channel.channelKey, channel.packageName, channel);
            }

            @Override public String packageOf(Object channel) {
                return channel instanceof Channel ? ((Channel) channel).packageName : null;
            }

            @Override public Object iconMap() {
                return icons;
            }
        };
    }

    @After public void tearDown() {
        ShareTargets.nativeForTests = null;
        ShareTargets.iconMapRefused = false;
        Settings.SHARE_ADDED_APPS.save("");
        Settings.HIDE_SHARE_CHANNELS.save(false);
        Settings.SHARE_HIDDEN_ITEMS.save("");
        HookStatus.clear();
    }

    private void install(String packageName, String label, boolean takesText) {
        PackageInfo info = new PackageInfo();
        info.packageName = packageName;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = packageName;
        info.applicationInfo.nonLocalizedLabel = label;
        shadowOf(context.getPackageManager()).installPackage(info);
        shadowOf(context.getPackageManager()).setApplicationIcon(packageName, new ColorDrawable(Color.BLUE));
        if (!takesText) return;
        ComponentName share = new ComponentName(packageName, packageName + ".ShareActivity");
        shadowOf(context.getPackageManager()).addActivityIfNotPresent(share);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SEND);
        try {
            filter.addDataType("text/plain");
        } catch (IntentFilter.MalformedMimeTypeException impossible) {
            throw new AssertionError(impossible);
        }
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        shadowOf(context.getPackageManager()).addIntentFilterForActivity(share, filter);
    }

    /** The row as the patched sheet builds it: Hushfeed's filter on the model, the picked apps on the finished row. */
    private static List<?> sheet(List<?> row) {
        return ShareTargets.withPicked(ShareModelFilter.channels(row));
    }

    private static List<String> keys(List<?> channels) {
        List<String> keys = new ArrayList<>();
        for (Object channel : channels) keys.add(((Channel) channel).key());
        return keys;
    }

    @Test public void pickedAppsGoInBeforeMoreAsTikToksOwnChannel() {
        List<?> row = Arrays.asList(new Channel("whatsapp", WHATSAPP, null), new Channel("more", null, null));
        Settings.SHARE_ADDED_APPS.save(TELEGRAM + "," + SIGNAL);

        List<?> added = sheet(row);

        assertEquals(Arrays.asList("whatsapp", "hushfeed_app_" + TELEGRAM, "hushfeed_app_" + SIGNAL, "more"), keys(added));
        ShareChannelInfo telegram = ((Channel) added.get(1)).info;
        assertEquals(TELEGRAM, telegram.packageName);
        assertEquals("Telegram", telegram.labelName);
        assertNotNull("a no-target component sends to the package", telegram.targetComponentInfo);
        assertNotNull(icons.get("hushfeed_app_" + TELEGRAM));
        assertNotNull(icons.get("hushfeed_app_" + TELEGRAM + ShareTargets.SQUARE_ICON_SUFFIX));
        assertEquals("TikTok's own list is left as it was", 2, row.size());
    }

    @Test public void appsTikTokShowsOrThatCantTakeTextAreLeftOut() {
        List<?> row = Arrays.asList(new Channel("whatsapp", WHATSAPP, null), new Channel("more", null, null));
        Settings.SHARE_ADDED_APPS.save(WHATSAPP + "," + NO_TEXT + ",com.example.uninstalled");

        assertSame(row, sheet(row));
    }

    @Test public void withoutMoreTheyGoAtTheEnd() {
        List<?> row = Collections.singletonList(new Channel("sms", null, null));
        Settings.SHARE_ADDED_APPS.save(SIGNAL);

        assertEquals(Arrays.asList("sms", "hushfeed_app_" + SIGNAL), keys(sheet(row)));
    }

    @Test public void aHiddenRowStaysEmpty() {
        Settings.SHARE_ADDED_APPS.save(TELEGRAM);
        Settings.HIDE_SHARE_CHANNELS.save(true);

        assertTrue(sheet(Collections.singletonList(new Channel("more", null, null))).isEmpty());
    }

    @Test public void aNameHiddenByHandDoesntTakeAPickedAppAway() {
        List<?> row = Arrays.asList(new Channel("sms", null, null), new Channel("more", null, null));
        Settings.SHARE_HIDDEN_ITEMS.save("sms");
        Settings.SHARE_ADDED_APPS.save(TELEGRAM);

        assertEquals(Arrays.asList("hushfeed_app_" + TELEGRAM, "more"), keys(sheet(row)));
    }

    @Test public void unpatchedNothingIsAdded() {
        ShareTargets.nativeForTests = null;
        List<?> row = Collections.singletonList(new Channel("more", null, null));
        Settings.SHARE_ADDED_APPS.save(TELEGRAM);

        assertSame(row, sheet(row));
    }

    @Test public void aReadOnlyIconMapStillAddsTheApp() {
        ShareTargets.Native writable = ShareTargets.nativeForTests;
        ShareTargets.nativeForTests = new ShareTargets.Native() {
            @Override public Object newChannel(Object info) { return writable.newChannel(info); }
            @Override public String packageOf(Object channel) { return writable.packageOf(channel); }
            @Override public Object iconMap() { return Collections.emptyMap(); }
        };
        Settings.SHARE_ADDED_APPS.save(TELEGRAM);

        List<?> added = sheet(Collections.singletonList(new Channel("more", null, null)));

        assertEquals(Arrays.asList("hushfeed_app_" + TELEGRAM, "more"), keys(added));
    }

    @Test public void anAddedChannelSharesTheLinkAndTikToksOwnAreLeftToTikTok() {
        assertEquals(ShareTargets.LINK_SHARE_MODE, ShareTargets.shareModeOf("hushfeed_app_" + TELEGRAM));
        assertEquals(ShareTargets.NOT_OURS, ShareTargets.shareModeOf("whatsapp"));
        assertEquals(ShareTargets.NOT_OURS, ShareTargets.shareModeOf("more"));
        assertEquals(ShareTargets.NOT_OURS, ShareTargets.shareModeOf(null));
        assertTrue("TikTok's code returns the mode only when it isn't negative", ShareTargets.NOT_OURS < 0);
    }

    @Test public void thePickerListsAppsThatTakeTextByLabel() {
        List<String> labels = new ArrayList<>();
        for (ShareTargets.App app : ShareTargets.installed(context)) labels.add(app.label);

        assertEquals(Arrays.asList("Signal", "Telegram", "WhatsApp"), labels);
    }

    @Test public void thePickedListReadsBackInOrderWithoutRepeats() {
        assertEquals(Arrays.asList(TELEGRAM, SIGNAL), ShareTargets.picked(" " + TELEGRAM + ",\n" + SIGNAL + "," + TELEGRAM + ","));
        assertTrue(ShareTargets.picked(null).isEmpty());
    }
}
