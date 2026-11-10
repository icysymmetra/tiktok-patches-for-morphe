package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.base.model.UrlModel;
import java.util.List;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class QualitySelectorReentryTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @After public void reset() { Settings.DOWNLOAD_VIDEO_QUALITY.resetToDefault(); }

    /** Mirrors AdvancedDownloadsPatch's getter entry and original backing-field return. */
    public static final class PatchedVideo {
        public final List<AdvancedDownloadsTest.Gear> bitRateList = List.of(
                new AdvancedDownloadsTest.Gear("normal_720", 500, "https://example.com/720.mp4").codec(0),
                new AdvancedDownloadsTest.Gear("normal_1080", 900, "https://example.com/1080.mp4").codec(2));
        public UrlModel downloadNoWatermarkAddr =
                new AdvancedDownloadsTest.Address("https://example.com/clean.mp4", 900).frame(1080, 1920);
        int getterCalls;
        public List<AdvancedDownloadsTest.Gear> getRawBitRate() { return bitRateList; }
        public boolean hasDashBitrate() { return false; }
        public UrlModel getDownloadNoWatermarkAddr() {
            // Bound the pre-fix cycle so the regression fails without exhausting the VM stack.
            if (++getterCalls > 3) return downloadNoWatermarkAddr;
            UrlModel selected = QualitySelector.download(this);
            return selected == null ? downloadNoWatermarkAddr : selected;
        }
    }

    @Test public void highestQualityReadsTheNativeAddressWithoutReenteringItsHook() {
        Settings.DOWNLOAD_VIDEO_QUALITY.save("highest");
        PatchedVideo video = new PatchedVideo();
        assertSame(video.downloadNoWatermarkAddr, video.getDownloadNoWatermarkAddr());
        assertEquals("selection must not call the getter that invoked it", 1, video.getterCalls);
    }

    @Test public void cappedQualityPreservesTheTallerNativeFileWithoutReentry() {
        Settings.DOWNLOAD_VIDEO_QUALITY.save("1080");
        PatchedVideo video = new PatchedVideo();
        assertSame(video.downloadNoWatermarkAddr, video.getDownloadNoWatermarkAddr());
        assertEquals(1, video.getterCalls);
    }

    @Test public void unknownNativeAddressKeepsThePlayableRenditionWithoutReentry() {
        Settings.DOWNLOAD_VIDEO_QUALITY.save("highest");
        PatchedVideo video = new PatchedVideo();
        video.downloadNoWatermarkAddr = null;
        assertSame(video.bitRateList.get(0).playAddr, video.getDownloadNoWatermarkAddr());
        assertEquals(1, video.getterCalls);
    }
}
