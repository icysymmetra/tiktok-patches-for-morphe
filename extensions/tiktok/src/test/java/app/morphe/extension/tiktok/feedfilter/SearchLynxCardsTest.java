/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * The Lynx cards in search results, judged as the card cell binds them. The fakes carry the
 * shapes TikTok 47.0.3 has: the cell is a RecyclerView view holder with an itemView, and the item
 * holds the card's data in one field of type DynamicPatch, whose schema names the template.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SearchLynxCardsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for TikTok's DynamicPatch: the two members read, and nothing a card says. */
    public static class DynamicPatch {
        public String schema;
        public String alaSrc;

        DynamicPatch(String schema, String alaSrc) {
            this.schema = schema;
            this.alaSrc = alaSrc;
        }
    }

    /** The cell's item: its patch sits in an obfuscated field, found by type. */
    public static class Item {
        public final DynamicPatch LLJJIJI;

        Item(DynamicPatch patch) {
            this.LLJJIJI = patch;
        }
    }

    /** The cell: a view holder with its row. */
    public static class Cell {
        public final View itemView;

        Cell(View row) {
            this.itemView = row;
        }
    }

    private static final String DRAMA_SCHEMA = "sslocal://lynxview?channel=search_short_drama_card&bundle=template.js&keyword=short%20drama";
    private static final String OTHER_SCHEMA = "sslocal://lynxview?surl=https%3A%2F%2Fexample.invalid%2Fobj%2Fsearch_music_card%2Ftemplate.js%3Fq%3Dx&keyword=song";

    @Before public void setUp() {
        Settings.HIDE_MINI_DRAMAS.save(false);
        SearchLynxCards.resetForTests();
        FeedItemsFilter.resetDiagnosticsForTests();
    }

    @After public void tearDown() {
        Settings.HIDE_MINI_DRAMAS.resetToDefault();
        Settings.REMOVE_ADS.resetToDefault();
        SearchLynxCards.resetForTests();
        FeedItemsFilter.resetDiagnosticsForTests();
    }

    @Test public void aDramaModuleIsNamedByItsTemplateOrItsSourceType() {
        assertTrue(SearchLynxCards.isDrama(new DynamicPatch(DRAMA_SCHEMA, null)));
        assertTrue(SearchLynxCards.isDrama(new DynamicPatch(null, "Short_Drama_Hub")));
        assertFalse(SearchLynxCards.isDrama(new DynamicPatch(OTHER_SCHEMA, "music")));
        assertFalse(SearchLynxCards.isDrama(new DynamicPatch(null, null)));
        assertFalse(SearchLynxCards.isDrama(null));
        // 47.0.3's own names for the block, and names that only say "drama".
        assertTrue(SearchLynxCards.isDrama(new DynamicPatch(null, "short_drama_general_card")));
        assertTrue(SearchLynxCards.isDrama(new DynamicPatch(
                "sslocal://lynxview?channel=short_drama_search_card_rlynx2", null)));
        assertTrue(SearchLynxCards.isDrama(new DynamicPatch(null, "minidrama_hub")));
        assertFalse(SearchLynxCards.isDrama(new DynamicPatch(null, "tv_drama_hub")));
        assertFalse(SearchLynxCards.isDrama(new DynamicPatch("sslocal://lynxview?channel=melodrama_card", null)));
    }

    /** A row bound again while the list scrolls holds the same card: the export counts it once. */
    @Test public void aCardRowsKeepBindingIsCountedOnce() {
        FeedFilterCounters.clear();
        Settings.HIDE_MINI_DRAMAS.save(true);
        View row = row();
        DynamicPatch drama = new DynamicPatch(DRAMA_SCHEMA, null);
        for (int bind = 0; bind < 3; bind++) {
            SearchLynxCards.onHolderBound(new Cell(row), new Object(), drama);
        }
        assertCollapsed(row);
        assertTrue("the rebinds were counted as three cards: " + lynxLine(),
                lynxLine().startsWith(SearchLynxCards.SOURCE + ": 1 lists, 1 items, 1 removed"));

        SearchLynxCards.onHolderBound(new Cell(row), new Object(), new DynamicPatch(DRAMA_SCHEMA, null));
        assertTrue("a new card in the row was not counted: " + lynxLine(),
                lynxLine().startsWith(SearchLynxCards.SOURCE + ": 2 lists, 2 items, 2 removed"));
    }

    private static String lynxLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(SearchLynxCards.SOURCE + ":")) return line;
        }
        return "no " + SearchLynxCards.SOURCE + " line";
    }

    /** Only the template's name leaves the schema: never the search the other parameters carry. */
    @Test public void theTemplateIsTheChannelOrTheBundleFolderAndNothingElse() {
        assertEquals("search_short_drama_card", SearchLynxCards.lynxTemplate(DRAMA_SCHEMA));
        assertEquals("search_music_card", SearchLynxCards.lynxTemplate(OTHER_SCHEMA));
        assertNull(SearchLynxCards.lynxTemplate("sslocal://lynxview?keyword=short%20drama"));
        assertNull(SearchLynxCards.lynxTemplate(null));
        String kind = SearchLynxCards.kind(new DynamicPatch(DRAMA_SCHEMA, "short_drama"));
        assertEquals("lynx card src short_drama template search_short_drama_card", kind);
        assertFalse("the search reached the card's kind: " + kind, kind.contains("short%20drama") || kind.contains("keyword"));
    }

    /** The type members of 47.4.3's DynamicPatch, with what a hotel card might carry in its maps. */
    public static class TypedPatch {
        public int originType = 7;
        public String tokenType = "poi card\n";
        public String popupType;
        public Boolean isEcom = Boolean.TRUE;
        public java.util.List<Object> awemeList = java.util.Arrays.asList(new Object(), new Object());
        public java.util.Map<String, Object> extraInfo = new java.util.HashMap<>();
        public java.util.Map<String, Object> globalProps = new java.util.HashMap<>();
        public String rawData = "{\"poi_info\":{\"name\":\"Hotel Example\",\"price\":\"$847\"},\"ad_id\":\"123\",\"items\":[1,2,3],\"query\":\"hotels new york\","
                + "\"type\":78,\"doc_type\":\"nearby_poi\",\"nearby_poi\":{\"card_intent_type\":\"hotel booking\",\"local_service_key_category_enum\":4,"
                + "\"poi_info_list\":[{\"poi_id\":\"9\",\"name\":\"Hotel Example\"}]}}";

        TypedPatch() {
            extraInfo.put("search_keyword", "hotels new york");
            globalProps.put("is_ad", "1");
        }
    }

    /** The debug line names a card's type codes and key names, never what the card or the search says. */
    @Test public void theDebugFieldsNameTypesAndKeysButNoValues() {
        String fields = SearchLynxCards.patchFields(new TypedPatch());
        assertTrue(fields, fields.startsWith("originType=7 tokenType=poicard isEcom=true videos=2"));
        assertTrue(fields, fields.contains("extraInfo{search_keyword}"));
        assertTrue(fields, fields.contains("globalProps{is_ad}"));
        assertTrue(fields, fields.contains("rawData{"));
        assertTrue(fields, fields.contains("poi_info{name price}") || fields.contains("poi_info{price name}"));
        assertTrue(fields, fields.contains("items[3]"));
        // One-word type codes show; a code with words in it, like any text, doesn't.
        assertTrue(fields, fields.contains("type=78") && fields.contains("doc_type=nearby_poi"));
        assertTrue(fields, fields.contains("local_service_key_category_enum=4"));
        assertTrue(fields, fields.contains("card_intent_type") && !fields.contains("card_intent_type="));
        assertTrue(fields, fields.contains("poi_info_list[1]{poi_id name}") || fields.contains("poi_info_list[1]{name poi_id}"));
        for (String value : new String[] {"hotels", "Hotel Example", "847", "123", "booking", "poi_id=9"}) {
            assertFalse("a value reached the log: " + fields, fields.contains(value));
        }
        assertEquals("no patch", SearchLynxCards.patchFields(null));
        assertEquals("originType=null", SearchLynxCards.patchFields(new Object()));
        TypedPatch broken = new TypedPatch();
        broken.rawData = "[not an object";
        assertTrue(SearchLynxCards.patchFields(broken).endsWith("rawData not an object"));
    }

    @Test public void withHideMiniDramasOnADramaCardsRowCollapsesAndOtherCardsStay() {
        Settings.HIDE_MINI_DRAMAS.save(true);
        View dramaRow = row();
        View otherRow = row();

        SearchLynxCards.onCardBound(new Cell(dramaRow), new Item(new DynamicPatch(DRAMA_SCHEMA, null)));
        SearchLynxCards.onCardBound(new Cell(otherRow), new Item(new DynamicPatch(OTHER_SCHEMA, null)));

        assertCollapsed(dramaRow);
        assertShowing(otherRow);
        assertTrue("the removal was not counted: " + FeedFilterCounters.report(),
                String.join("\n", FeedFilterCounters.report()).contains(SearchLynxCards.DRAMA_REASON));
    }

    /** The Top results' own holder hands the patch itself, beside the fragment: (holder, fragment, patch). */
    @Test public void theTopResultsHolderIsJudgedByThePatchItBinds() {
        Settings.HIDE_MINI_DRAMAS.save(true);
        View dramaRow = row();
        View otherRow = row();

        SearchLynxCards.onHolderBound(new Cell(dramaRow), new Object(), new DynamicPatch(DRAMA_SCHEMA, null));
        SearchLynxCards.onHolderBound(new Cell(otherRow), new Object(), new DynamicPatch(OTHER_SCHEMA, null));

        assertCollapsed(dramaRow);
        assertShowing(otherRow);
        SearchLynxCards.onHolderBound(null, null, null);
        SearchLynxCards.onHolderBound(new Cell(otherRow), null, new Object());
        assertShowing(otherRow);
    }

    /** A Lynx card's patch with the raw data 47.4.3 serves; the template is the one all three share. */
    public static class RawPatch {
        public String schema = "sslocal://lynxview?channel=fe_search_vertical_card_lynx&bundle=poi_card%2Ftemplate.js";
        public String alaSrc;
        public String rawData;

        RawPatch(String rawData) {
            this.rawData = rawData;
        }
    }

    // The shapes captured on 47.4.3 for "hotels new york", a landmark and a restaurant search,
    // cut down to the keys that matter and with made-up values.
    private static final String HOTEL_DEALS = "{\"type\":78,\"doc_type\":200,\"nearby_poi\":{\"local_service_key_category\":\"Hotel\","
            + "\"poi_info_list\":[{\"poi_name\":\"Hotel Example\",\"ls_merchant_list\":[{\"name\":\"Agoda\"},{\"name\":\"Booking\"}],\"coupon_bar_list\":[{}]}]}}";
    private static final String PLACE_CARD = "{\"type\":107,\"doc_type\":246,\"poi_content_card\":{\"poi_content_card_info_list\":[{\"poi_info\":{}}],"
            + "\"local_service_key_category\":\"Travel\"}}";
    private static final String MUST_EAT = "{\"type\":325,\"doc_type\":105,\"poi_must_eat_ranking_card\":{\"tab_list\":[{\"poi_list\":[{},{}]}]}}";

    @Test public void withRemoveAdsTheHotelDealsBlockCollapsesAndPlaceCardsStay() {
        FeedFilterCounters.clear();
        Settings.REMOVE_ADS.save(true);
        View hotelRow = row();
        View placeRow = row();
        View mustEatRow = row();

        SearchLynxCards.onHolderBound(new Cell(hotelRow), new Object(), new RawPatch(HOTEL_DEALS));
        SearchLynxCards.onHolderBound(new Cell(placeRow), new Object(), new RawPatch(PLACE_CARD));
        SearchLynxCards.onHolderBound(new Cell(mustEatRow), new Object(), new RawPatch(MUST_EAT));

        assertCollapsed(hotelRow);
        assertShowing(placeRow);
        assertShowing(mustEatRow);
        String report = String.join("\n", FeedFilterCounters.report());
        assertTrue(report, report.contains(SearchLynxCards.HOTEL_REASON));
        assertTrue(report, report.contains("hotel deals card"));

        Settings.REMOVE_ADS.save(false);
        SearchLynxCards.onHolderBound(new Cell(hotelRow), new Object(), new RawPatch(HOTEL_DEALS));
        assertShowing(hotelRow);
    }

    /** Hotels with no merchant quoting a price, or a merchant list outside nearby_poi, are not the deals block. */
    @Test public void onlyHotelsWithMerchantPricesAreTheDealsBlock() {
        assertTrue(SearchLynxCards.isHotelDeals(new RawPatch(HOTEL_DEALS)));
        assertFalse(SearchLynxCards.isHotelDeals(new RawPatch(
                "{\"nearby_poi\":{\"poi_info_list\":[{\"poi_name\":\"x\",\"ls_merchant_list\":[]}]}}")));
        assertFalse(SearchLynxCards.isHotelDeals(new RawPatch(
                "{\"poi_content_card\":{\"ls_merchant_list\":[{\"name\":\"Agoda\"}]}}")));
        assertFalse(SearchLynxCards.isHotelDeals(new RawPatch("[\"ls_merchant_list\"")));
        assertFalse(SearchLynxCards.isHotelDeals(new RawPatch(null)));
        assertFalse(SearchLynxCards.isHotelDeals(new RawPatch(PLACE_CARD)));
        assertFalse(SearchLynxCards.isHotelDeals(new DynamicPatch(DRAMA_SCHEMA, null)));
        assertFalse(SearchLynxCards.isHotelDeals(null));
    }

    @Test public void withHideMiniDramasOffTheDramaCardStays() {
        View row = row();
        SearchLynxCards.onCardBound(new Cell(row), new Item(new DynamicPatch(DRAMA_SCHEMA, null)));
        assertShowing(row);
    }

    /**
     * TikTok's own bind runs after the hook and may size the row again, so the row is collapsed
     * again once the bind has returned. The row is attached here, as a list row is once laid out:
     * a view that is not attached holds a posted pass until it is.
     */
    @Test public void aRowTikTokSizesAgainAfterTheHookIsCollapsedOnceTheBindReturns() {
        Settings.HIDE_MINI_DRAMAS.save(true);
        View row = attachedRow();
        SearchLynxCards.onCardBound(new Cell(row), new Item(new DynamicPatch(DRAMA_SCHEMA, null)));
        row.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        row.setVisibility(View.VISIBLE);

        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertCollapsed(row);
    }

    /** A recycled row gets its size back for a card that stays, and the drama's late pass leaves it alone. */
    @Test public void aRowRecycledToAnotherCardComesBackAndStaysBack() {
        Settings.HIDE_MINI_DRAMAS.save(true);
        View row = attachedRow();
        SearchLynxCards.onCardBound(new Cell(row), new Item(new DynamicPatch(DRAMA_SCHEMA, null)));
        SearchLynxCards.onCardBound(new Cell(row), new Item(new DynamicPatch(OTHER_SCHEMA, null)));
        assertShowing(row);

        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertShowing(row);
    }

    @Test public void turningTheSwitchOffGivesTheRowBackOnItsNextBind() {
        Settings.HIDE_MINI_DRAMAS.save(true);
        View row = row();
        Item drama = new Item(new DynamicPatch(DRAMA_SCHEMA, null));
        SearchLynxCards.onCardBound(new Cell(row), drama);
        assertCollapsed(row);

        Settings.HIDE_MINI_DRAMAS.save(false);
        SearchLynxCards.onCardBound(new Cell(row), drama);

        assertShowing(row);
    }

    @Test public void oddShapesNeverThrowIntoTikTokBind() {
        Settings.HIDE_MINI_DRAMAS.save(true);
        SearchLynxCards.onCardBound(null, null);
        SearchLynxCards.onCardBound(new Object(), new Object());
        View row = row();
        SearchLynxCards.onCardBound(new Cell(row), null);
        SearchLynxCards.onCardBound(new Cell(row), new Object());
        assertShowing(row);
    }

    private static View row() {
        View row = new View(RuntimeEnvironment.getApplication());
        row.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** A row in a window, the way a list row is once the list has laid it out. */
    private static View attachedRow() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout list = new FrameLayout(activity);
        activity.setContentView(list);
        View row = new View(activity);
        list.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return row;
    }

    private static void assertCollapsed(View row) {
        assertEquals("the row is still shown", View.GONE, row.getVisibility());
        assertEquals("the row still takes its height", 0, row.getLayoutParams().height);
    }

    private static void assertShowing(View row) {
        assertEquals("the row is hidden", View.VISIBLE, row.getVisibility());
        assertEquals("the row lost its height", ViewGroup.LayoutParams.WRAP_CONTENT, row.getLayoutParams().height);
    }
}
