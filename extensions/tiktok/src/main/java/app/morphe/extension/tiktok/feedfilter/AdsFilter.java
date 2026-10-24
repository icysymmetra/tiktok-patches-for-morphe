/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/feedfilter/AdsFilter.java
 */
package app.morphe.extension.tiktok.feedfilter;

import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import org.json.JSONException;
import org.json.JSONObject;

public class AdsFilter implements IFilter {
    private static final String DISCLOSURE_HOOK_FAMILY = "feed ad disclosures";
    private static final String PANEL_DISCLOSURE = "panel_top_disclosure_label";
    /** The filter report's count of ads caught by a signal other than TikTok's own ad flag. */
    static final String AD_SIGNALS_SOURCE = "AdSignals";

    @Override
    public boolean getEnabled() {
        return Settings.REMOVE_ADS.get();
    }

    @Override
    public boolean getFiltered(Aweme item) {
        return item.isAd()
                || item.isSoftAd()
                || item.getAwemeRawAd() != null
                || item.isWithPromotionalMusic()
                || isPseudoAd(item)
                || isLemon8PromoCard(item)
                || hasCreatorCommissionDisclosure(item)
                || ContentMarkerFilters.hasPaidPartnershipMarker(item);
    }

    /** The CardInsertInfo card type of the Lemon8 install promo card TikTok slots into For You. */
    static final int LEMON8_CARD_TYPE = 9;

    /**
     * The full-screen "Find fresh ideas on Lemon8" card. It is an inserted card, not a video, and
     * none of TikTok's ad flags are set on it. Its handler asks for card type 9 and the server's
     * answer carries the same number, so the card is the item whose CardInsertInfo says 9.
     */
    static boolean isLemon8PromoCard(Aweme item) {
        Object insert = Reflect.property(item, "getCardInsertInfo", "cardInsertInfo");
        if (insert == null) return false;
        Object type = Reflect.property(insert, "getCardType", "cardType");
        boolean lemon = type instanceof Number && ((Number) type).intValue() == LEMON8_CARD_TYPE;
        if (lemon) FeedFilterCounters.sawKind(AD_SIGNALS_SOURCE, "lemon8 promo card");
        return lemon;
    }

    /**
     * A creator's own post that TikTok runs as an ad. isAd and the raw ad stay empty on it, so
     * TikTok's AwemeExtKt.isPseudoAd reads the commerce struct instead, and needs both its
     * isPseudoAd flag and the ad data it carries: either one alone is not an ad to TikTok. This
     * is that same test. The struct itself rides along on most of the feed.
     */
    static boolean isPseudoAd(Aweme item) {
        Object commerce = Reflect.property(item, "getCommerceVideoAuthInfo", "commerceVideoAuthInfo");
        if (commerce == null) return false;
        boolean pseudo = Boolean.TRUE.equals(Reflect.property(commerce, "isPseudoAd", "isPseudoAd"))
                && Reflect.property(commerce, "getPseudoAdData", "pseudoAdData") != null;
        if (pseudo) FeedFilterCounters.sawKind(AD_SIGNALS_SOURCE, "pseudo ad");
        return pseudo;
    }

    /**
     * TikTok builds the visible creator-commission label from this serialized anchor model.
     * Reading its stable JSON keys avoids depending on translated disclosure text or an
     * obfuscated renderer class.
     */
    static boolean hasCreatorCommissionDisclosure(Aweme item) {
        if (hasLocalAllianceDisclosure(item)) return true;
        String extras = anchorsExtras(item);
        if (extras == null) return false;
        if (extras.isEmpty() || !extras.contains("\"" + PANEL_DISCLOSURE + "\"")) return false;

        try {
            JSONObject disclosure = new JSONObject(extras).optJSONObject(PANEL_DISCLOSURE);
            return disclosure != null
                    && (nonBlankString(disclosure.opt("display_text"))
                    || nonBlankString(disclosure.opt("truncatable_text")));
        } catch (JSONException ignored) {
            // Anchor metadata comes from the network. A malformed item is ordinary feed content,
            // not evidence that the hook contract changed.
            return false;
        }
    }

    /**
     * The location-affiliate label uses ContentModel, not shopping's anchorsExtras.
     * PoiCreatorBottomLabelTriggerAssem reads this same named model chain before rendering.
     * showBottomLabel requires allianceItemLabelType == 1 and nonempty label text; a location
     * or an incomplete alliance object alone is not ad evidence. Read at list delivery so
     * the video is removed before the renderer runs, independently of locale or its UI gate.
     */
    private static boolean hasLocalAllianceDisclosure(Aweme item) {
        Object content = Reflect.required(item, "getContentModel", DISCLOSURE_HOOK_FAMILY);
        Object business = Reflect.requiredField(content, "standardBusinessModel", DISCLOSURE_HOOK_FAMILY);
        Object alliance = Reflect.required(business, "getLocalAllianceInfo", DISCLOSURE_HOOK_FAMILY);
        return Boolean.TRUE.equals(Reflect.required(alliance, "showBottomLabel", DISCLOSURE_HOOK_FAMILY));
    }

    /**
     * A cheap signature for ad metadata TikTok can fill after the first delivery callback.
     * FeedItemsFilter uses it to distinguish a true duplicate list from the same Aweme objects
     * after a cache or hydration pass added disclosure data.
     */
    static int evidenceFingerprint(Aweme item) {
        if (item == null) return 0;
        int result = 1;
        try {
            result = 31 * result + (item.isAd() ? 1 : 0);
            result = 31 * result + (item.isSoftAd() ? 1 : 0);
            Object rawAd = item.getAwemeRawAd();
            result = 31 * result + (rawAd == null ? 0 : System.identityHashCode(rawAd));
            result = 31 * result + (item.isWithPromotionalMusic() ? 1 : 0);
        } catch (Throwable ignored) {
            // A host getter that fails must not stop a feed delivery. The serialized disclosure
            // and branded-content checks below can still invalidate the duplicate-list cache.
        }
        String extras = anchorsExtras(item);
        result = 31 * result + (extras == null ? 0 : extras.hashCode());
        result = 31 * result + (hasLocalAllianceDisclosure(item) ? 1 : 0);
        result = 31 * result + (ContentMarkerFilters.hasPaidPartnershipMarker(item) ? 1 : 0);
        return result;
    }

    private static String anchorsExtras(Aweme item) {
        Object value = Reflect.required(item, "getAnchorsExtras", DISCLOSURE_HOOK_FAMILY);
        if (!(value instanceof String)) return null;
        String extras = ((String) value).trim();
        return extras.isEmpty() ? null : extras;
    }

    private static boolean nonBlankString(Object value) {
        return value instanceof String && !((String) value).trim().isEmpty();
    }
}
