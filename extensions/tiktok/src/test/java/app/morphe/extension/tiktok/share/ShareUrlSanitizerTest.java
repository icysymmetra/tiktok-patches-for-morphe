package app.morphe.extension.tiktok.share;

import static org.junit.Assert.assertEquals;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.Rule;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** What a shared or copied link looks like once the host and the tracking have been dealt with. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ShareUrlSanitizerTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void reset() {
        // Back to the defaults, not to off: a saved value stays for the whole test JVM, and the
        // short-link tests after this one take the link's tracking off with the default.
        Settings.CUSTOM_SHARE_DOMAIN.resetToDefault();
        BaseSettings.SANITIZE_SHARING_LINKS.resetToDefault();
    }

    @Test
    public void theHostIsSwappedAndTheTrackingIsTakenOffTogether() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.CUSTOM_SHARE_DOMAIN.save("vxtiktok.com");
        BaseSettings.SANITIZE_SHARING_LINKS.save(true);
        assertEquals("https://vxtiktok.com/@dancer/video/7712345",
                ShareUrlSanitizer.rewriteShareUrl(
                        "https://www.tiktok.com/@dancer/video/7712345?is_from_webapp=1&sender_device=pc"));

        // Each half also works on its own.
        BaseSettings.SANITIZE_SHARING_LINKS.save(false);
        assertEquals("https://vxtiktok.com/@dancer/video/7712345?is_from_webapp=1",
                ShareUrlSanitizer.rewriteShareUrl(
                        "https://www.tiktok.com/@dancer/video/7712345?is_from_webapp=1"));
        Settings.CUSTOM_SHARE_DOMAIN.save("");
        BaseSettings.SANITIZE_SHARING_LINKS.save(true);
        assertEquals("https://www.tiktok.com/@dancer/video/7712345",
                ShareUrlSanitizer.rewriteShareUrl(
                        "https://www.tiktok.com/@dancer/video/7712345?is_from_webapp=1"));
    }

    @Test
    public void everyShortLinkHostIsSwappedAndNothingElseIs() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.CUSTOM_SHARE_DOMAIN.save("vxtiktok.com");
        for (String host : new String[]{"tiktok.com", "www.tiktok.com", "m.tiktok.com",
                "vm.tiktok.com", "vt.tiktok.com", "VM.TikTok.com"}) {
            assertEquals("https://vxtiktok.com/ZSabc",
                    ShareUrlSanitizer.withCustomDomain("https://" + host + "/ZSabc"));
        }
        // A host that only looks like one is left alone, and so is anything else entirely.
        assertEquals("https://tiktok.com.evil.example/ZSabc",
                ShareUrlSanitizer.withCustomDomain("https://tiktok.com.evil.example/ZSabc"));
        assertEquals("https://nottiktok.com/ZSabc",
                ShareUrlSanitizer.withCustomDomain("https://nottiktok.com/ZSabc"));
        assertEquals("https://example.com/watch?v=1",
                ShareUrlSanitizer.withCustomDomain("https://example.com/watch?v=1"));
        // A bare host with nothing after it still has its host replaced.
        assertEquals("https://vxtiktok.com", ShareUrlSanitizer.withCustomDomain("https://tiktok.com"));
        assertEquals("https://vxtiktok.com?a=1", ShareUrlSanitizer.withCustomDomain("https://tiktok.com?a=1"));
        // Nothing to work with is not a crash.
        assertEquals(null, ShareUrlSanitizer.withCustomDomain(null));
        assertEquals("tiktok.com/ZSabc", ShareUrlSanitizer.withCustomDomain("tiktok.com/ZSabc"));
    }

    @Test
    public void whatCountsAsAHostInTheSetting() {
        // Typed however people type it.
        assertEquals("vxtiktok.com", ShareUrlSanitizer.domain("vxtiktok.com"));
        assertEquals("vxtiktok.com", ShareUrlSanitizer.domain("  vxtiktok.com  "));
        assertEquals("vxtiktok.com", ShareUrlSanitizer.domain("https://vxtiktok.com"));
        assertEquals("vxtiktok.com", ShareUrlSanitizer.domain("https://vxtiktok.com/"));
        assertEquals("vxtiktok.com", ShareUrlSanitizer.domain("http://vxtiktok.com/some/path"));
        // Anything that is not a host would make the link unusable, so it is ignored instead.
        assertEquals("", ShareUrlSanitizer.domain(""));
        assertEquals("", ShareUrlSanitizer.domain("   "));
        assertEquals("", ShareUrlSanitizer.domain(null));
        assertEquals("", ShareUrlSanitizer.domain("not a host"));
        assertEquals("", ShareUrlSanitizer.domain("localhost"));
        assertEquals("", ShareUrlSanitizer.domain("vxtiktok.com:8443"));
        // A query or a fragment in the box would push the rest of the link out of the path and
        // leave the front page of the host behind, so neither is a host.
        assertEquals("", ShareUrlSanitizer.domain("vxtiktok.com?a=1"));
        assertEquals("", ShareUrlSanitizer.domain("vxtiktok.com#x"));
        assertEquals("", ShareUrlSanitizer.domain("vx\ttiktok.com"));
        assertEquals("", ShareUrlSanitizer.domain("vx@tiktok.com"));
    }

    @Test
    public void anUnusableSettingLeavesTheLinkAsItWas() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        for (String unusable : new String[]{"not a host", "vxtiktok.com?a=1", "vxtiktok.com#x", ""}) {
            Settings.CUSTOM_SHARE_DOMAIN.save(unusable);
            assertEquals("https://www.tiktok.com/@dancer/video/7712345",
                    ShareUrlSanitizer.withCustomDomain("https://www.tiktok.com/@dancer/video/7712345"));
        }
    }
}
