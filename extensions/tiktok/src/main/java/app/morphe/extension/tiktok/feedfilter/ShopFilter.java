/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/feedfilter/ShopFilter.java
 */
package app.morphe.extension.tiktok.feedfilter;

import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;

public class ShopFilter implements IFilter {
    // Placeholder used by TikTok internal shop ads (see upstream feed filter).
    private static final String SHOP_INFO = "placeholder_product_id";
    /**
     * What a LIVE room that is selling carries, by the model's own names on 47.0.3 and 47.1.3.
     * A seller's permission is left out: an account can hold it with nothing on sale.
     */
    private static final String[] ROOM_GOODS_FLAGS = {
            "hasCommerceGoods", "existedCommerceGoods", "hasTtlsGoods", "existedTtlsGoods"};

    @Override
    public boolean getEnabled() {
        return Settings.HIDE_SHOP.get();
    }

    @Override
    public boolean getFiltered(Aweme item) {
        String shareUrl = item.getShareUrl();
        // Null-safety: only filter when we actually have a URL.
        if (shareUrl != null && shareUrl.contains(SHOP_INFO)) return true;
        return LiveFilter.isLive(item) && sellsInLive(item);
    }

    /**
     * A LIVE selling while it streams (#46). The share-link placeholder above only ever marked
     * TikTok's own shop ads, so a shopping LIVE in the feed was hidden by Hide LIVE videos
     * alone, which takes every LIVE with it.
     */
    static boolean sellsInLive(Aweme item) {
        if (Boolean.TRUE.equals(Reflect.readField(item, "isLiveHasProduct"))) return true;
        for (Object room : LiveFilter.rooms(item)) {
            if (roomSells(room)) return true;
        }
        return false;
    }

    /** One room object: a goods flag, or products counted in its feed commerce card. */
    static boolean roomSells(Object room) {
        for (String flag : ROOM_GOODS_FLAGS) {
            if (Boolean.TRUE.equals(Reflect.readField(room, flag))) return true;
        }
        Object products = Reflect.readField(Reflect.readField(room, "fypCommerceStruct"), "productNum");
        return products instanceof Number && ((Number) products).longValue() > 0;
    }
}

