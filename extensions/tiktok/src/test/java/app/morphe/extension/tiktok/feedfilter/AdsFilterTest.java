/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.*;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeRawAd;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Structured ad evidence that TikTok keeps outside its existing Boolean and raw-ad fields. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AdsFilterTest {
    private final AdsFilter filter = new AdsFilter();

    public static class Video extends Aweme {
        String anchorsExtras;
        boolean ad;
        boolean softAd;
        boolean promotionalMusic;
        AwemeRawAd rawAd;
        Object commerceVideoAuthInfo;
        Object contentModel;
        String aid;

        @Override public boolean isAd() { return ad; }
        @Override public boolean isSoftAd() { return softAd; }
        @Override public AwemeRawAd getAwemeRawAd() { return rawAd; }
        @Override public boolean isWithPromotionalMusic() { return promotionalMusic; }
        @Override public String getAid() { return aid; }
        public String getAnchorsExtras() { return anchorsExtras; }
        public Object getCommerceVideoAuthInfo() { return commerceVideoAuthInfo; }
        public Object getContentModel() { return contentModel; }
    }

    public static class Content {
        public Object standardBusinessModel;
        Content(Object business) { standardBusinessModel = business; }
    }

    public static class Business {
        private final Object alliance;
        Business(Object alliance) { this.alliance = alliance; }
        public Object getLocalAllianceInfo() { return alliance; }
    }

    /** Matches LocalAllianceInfo.showBottomLabel in every retained TikTok fixture. */
    public static class Alliance {
        final Integer type;
        final String label;
        Alliance(Integer type, String label) { this.type = type; this.label = label; }
        public boolean showBottomLabel() {
            return Integer.valueOf(1).equals(type) && label != null && !label.isEmpty();
        }
    }

    /** AwemeCommerceStruct's two pseudo-ad members, named as on every retained fixture. */
    public static class Commerce {
        boolean pseudoAd;
        Object pseudoAdData;
        public boolean isPseudoAd() { return pseudoAd; }
        public Object getPseudoAdData() { return pseudoAdData; }
    }

    /** Simulates a future host model where the required serialized anchor getter moved. */
    public static class VideoWithoutAnchors extends Aweme {
        @Override public boolean isAd() { return false; }
        @Override public boolean isSoftAd() { return false; }
        @Override public AwemeRawAd getAwemeRawAd() { return null; }
        @Override public boolean isWithPromotionalMusic() { return false; }
        public Object getContentModel() { return null; }
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    @After public void tearDown() {
        HookStatus.clear();
        FeedFilterCounters.clear();
    }

    @Test public void displayTextCommissionDisclosureIsAnAd() {
        Video video = video("{\"panel_top_disclosure_label\":"
                + "{\"display_text\":\"Creator earns commission\"}}");

        assertTrue(filter.getFiltered(video));
        assertTrue(HookStatus.missing("feed ad disclosures").isEmpty());
    }

    @Test public void truncatableCommissionDisclosureIsAnAdInAnyLanguage() {
        Video video = video("{\"panel_top_disclosure_label\":"
                + "{\"display_text\":\"  \","
                + "\"truncatable_text\":\"El creador recibe una comisión\"}}");

        assertTrue(filter.getFiltered(video));
    }

    @Test public void ordinaryAnchorMetadataIsKept() {
        assertFalse(filter.getFiltered(video("{\"product_cnt\":1,\"anchor_type\":4}")));
    }

    @Test public void locationAffiliateCommissionIsFilteredBeforeAnyLabelRenders() {
        Video video = video(null);
        video.contentModel = new Content(new Business(new Alliance(1, "Creator earns commission")));

        assertTrue(filter.getFiltered(video));
        assertTrue(HookStatus.missing("feed ad disclosures").isEmpty());
    }

    @Test public void locationAffiliateDisclosureDoesNotDependOnEnglishText() {
        Video video = video(null);
        video.contentModel = new Content(new Business(new Alliance(1, "يحصل المنشئ على عمولة")));

        assertTrue(filter.getFiltered(video));
    }

    @Test public void ordinaryLocationsAndIncompleteAllianceMetadataAreKept() {
        Video video = video(null);
        for (Object alliance : new Object[] {null, new Alliance(null, "label"),
                new Alliance(0, "label"), new Alliance(2, "label"),
                new Alliance(1, null), new Alliance(1, "")}) {
            video.contentModel = new Content(new Business(alliance));
            assertFalse(filter.getFiltered(video));
        }
        video.contentModel = new Content(null);
        assertFalse(filter.getFiltered(video));
    }

    @Test public void locationDisclosureHydrationInvalidatesTheFeedSignature() {
        Video video = video(null);
        int before = AdsFilter.evidenceFingerprint(video);
        video.contentModel = new Content(new Business(new Alliance(1, "Creator earns commission")));

        assertNotEquals(before, AdsFilter.evidenceFingerprint(video));
    }

    @Test public void aBrokenLocationContractFailsOpen() {
        Video video = video(null);
        video.contentModel = new Content(new Business(new Object() {
            public boolean showBottomLabel() { throw new IllegalStateException("host failure"); }
        }));

        assertFalse(filter.getFiltered(video));
    }

    @Test public void aBlankDisclosureIsNotAdEvidence() {
        assertFalse(filter.getFiltered(video("{\"panel_top_disclosure_label\":"
                + "{\"display_text\":\" \",\"truncatable_text\":\"\"}}")));
        assertFalse(filter.getFiltered(video("{\"panel_top_disclosure_label\":{}}")));
    }

    @Test public void malformedOrWrongTypeDisclosureIsKept() {
        assertFalse(filter.getFiltered(video("{not-json")));
        assertFalse(filter.getFiltered(video("{\"panel_top_disclosure_label\":true}")));
        assertFalse(filter.getFiltered(video("{\"panel_top_disclosure_label\":"
                + "{\"display_text\":42}}")));
    }

    @Test public void existingAdSignalsStillFilterWithoutAnchorMetadata() {
        Video ad = video(null);
        ad.ad = true;
        Video softAd = video(null);
        softAd.softAd = true;
        Video rawAd = video(null);
        rawAd.rawAd = new AwemeRawAd();
        Video promotional = video(null);
        promotional.promotionalMusic = true;

        assertTrue(filter.getFiltered(ad));
        assertTrue(filter.getFiltered(softAd));
        assertTrue(filter.getFiltered(rawAd));
        assertTrue(filter.getFiltered(promotional));
    }

    @Test public void brandedContentIsIncludedInRemoveFeedAds() {
        Video branded = video(null);
        branded.commerceVideoAuthInfo = new Object() {
            public long getBrandedContentType() { return 1L; }
        };

        assertTrue(filter.getFiltered(branded));
    }

    @Test public void aCreatorPostTikTokRunsAsAnAdIsAnAdAndIsCounted() {
        Video pseudo = video(null);
        Commerce both = new Commerce();
        both.pseudoAd = true;
        both.pseudoAdData = new AwemeRawAd();
        pseudo.commerceVideoAuthInfo = both;

        assertTrue(filter.getFiltered(pseudo));
        assertTrue(FeedFilterCounters.report().toString(),
                FeedFilterCounters.report().contains("AdSignals: 0 lists, 0 items, 0 removed. Kinds: pseudo ad 1"));
    }

    /** TikTok's isPseudoAd returns false unless the flag and the ad data are both there. */
    @Test public void theFlagOrTheAdDataAloneIsNotAPseudoAd() {
        Video ordinary = video(null);
        ordinary.commerceVideoAuthInfo = new Commerce();
        Video flagged = video(null);
        Commerce flag = new Commerce();
        flag.pseudoAd = true;
        flagged.commerceVideoAuthInfo = flag;
        Video carrying = video(null);
        Commerce data = new Commerce();
        data.pseudoAdData = new AwemeRawAd();
        carrying.commerceVideoAuthInfo = data;

        assertFalse(filter.getFiltered(ordinary));
        assertFalse(filter.getFiltered(flagged));
        assertFalse(filter.getFiltered(carrying));
        assertTrue(FeedFilterCounters.report().isEmpty());
    }

    @Test public void missingAnchorGetterFailsOpenAndNamesTheContract() {
        VideoWithoutAnchors video = new VideoWithoutAnchors();

        assertFalse(filter.getFiltered(video));
        assertEquals(java.util.Collections.singletonList(
                        "method " + VideoWithoutAnchors.class.getName() + "#getAnchorsExtras"),
                HookStatus.missing("feed ad disclosures"));
    }

    private static Video video(String anchorsExtras) {
        Video video = new Video();
        video.anchorsExtras = anchorsExtras;
        return video;
    }
}
