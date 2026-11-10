package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.*;

import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 35})
public class LocationBadgeFilterTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    private boolean hide, filter, tools;
    public static class Anchor {
        String key;
        public String getComponentKey() { return key; }
        Anchor(String key) { this.key = key; }
    }
    public static class TypedAnchor extends Anchor {
        final int type;
        public int getType() { return type; }
        TypedAnchor(String key, int type) { super(key); this.type = type; }
    }
    public static class Video extends Aweme {
        List<?> anchors;
        public List<?> getAnchors() { return anchors; }
        @Override public String getAid() { return "location-contract"; }
        Video(List<?> anchors) { this.anchors = anchors; }
    }
    @Before public void saveSettings() {
        hide = Settings.HIDE_LOCATION_LABELS.get();
        filter = Settings.FILTER_LOCATION_VIDEOS.get();
        tools = Settings.HIDE_CREATION_TAGS.get();
        Settings.HIDE_LOCATION_LABELS.save(false);
        Settings.HIDE_CREATION_TAGS.save(false);
        Settings.FILTER_LOCATION_VIDEOS.save(false);
        FeedItemsFilter.resetDiagnosticsForTests();
        LocationBadgeFilter.resetStripKindsForTests();
        LogBufferManager.clearLogBuffer();
    }
    @After public void restoreSettings() {
        Settings.HIDE_LOCATION_LABELS.save(hide);
        Settings.FILTER_LOCATION_VIDEOS.save(filter);
        Settings.HIDE_CREATION_TAGS.save(tools);
        BaseSettings.DEBUG.resetToDefault();
        FeedItemsFilter.resetDiagnosticsForTests();
        LocationBadgeFilter.resetStripKindsForTests();
        LogBufferManager.clearLogBuffer();
    }

    private static int times(String report, String text) {
        return report.split(Pattern.quote(text), -1).length - 1;
    }

    @Test public void onlyTheExactNativePlaceComponentIsEvidence() {
        for (String key : Arrays.asList(null, "", "poi", "poi_anchor", "anchor_shop", "ANCHOR_POI", "anchor_poi_extra"))
            assertFalse(LocationBadgeFilter.hasBadge(new Video(List.of(new Anchor(key)))));
        assertFalse(LocationBadgeFilter.hasBadge(null));
        assertFalse(LocationBadgeFilter.hasBadge(new Video(null)));
        assertFalse(LocationBadgeFilter.hasBadge(new Video(Arrays.asList(null, new Object()))));
        assertTrue(LocationBadgeFilter.hasBadge(new Video(List.of(new Anchor("anchor_poi")))));
    }

    @Test public void hideOnlyRemovesSingleAndMultiplePlacesWithoutChangingTheVideoModel() {
        Settings.HIDE_LOCATION_LABELS.save(true);
        List<?> anchors = List.of(new Anchor("anchor_poi"), new Anchor("anchor_poi"));
        Video video = new Video(anchors);
        assertTrue(LocationBadgeFilter.visibleAnchors(anchors).isEmpty());
        assertSame(anchors, video.anchors);
        assertEquals(2, video.anchors.size());
        assertTrue(LocationBadgeFilter.hasBadge(video));
        assertFalse(new LocationBadgeFilter().getEnabled());
    }

    @Test public void mixedBadgesKeepOtherTypesAndTheirOrder() {
        Settings.HIDE_LOCATION_LABELS.save(true);
        Anchor effect = new Anchor("anchor_effect"), link = new Anchor("anchor_3rdparty");
        List<?> anchors = Arrays.asList(effect, new Anchor("anchor_poi"), null, link, new Anchor("anchor_poi"));
        List<?> visible = LocationBadgeFilter.visibleAnchors(anchors);
        assertEquals(Arrays.asList(effect, null, link), visible);
        assertEquals(5, anchors.size());
        assertSame(effect, visible.get(0));
        assertSame(link, visible.get(2));
    }

    @Test public void offAndUnknownDataKeepTheirOriginalList() {
        List<?> places = List.of(new Anchor("anchor_poi"));
        assertSame(places, LocationBadgeFilter.visibleAnchors(places));
        Settings.HIDE_LOCATION_LABELS.save(true);
        List<?> unknown = List.of(new Object(), new Anchor(null));
        assertSame(unknown, LocationBadgeFilter.visibleAnchors(unknown));
        assertNull(LocationBadgeFilter.visibleAnchors(null));
        assertTrue(LocationBadgeFilter.visibleAnchors(List.of()).isEmpty());
    }

    @Test public void filteringDoesNotDependOnHidingOrAdRemoval() {
        Settings.FILTER_LOCATION_VIDEOS.save(true);
        assertFalse(Settings.HIDE_LOCATION_LABELS.get());
        LocationBadgeFilter rule = new LocationBadgeFilter();
        assertTrue(rule.getEnabled());
        assertTrue(rule.getFiltered(new Video(List.of(new Anchor("anchor_poi")))));
        assertFalse(rule.getFiltered(new Video(List.of(new Anchor("anchor_effect")))));
    }

    @Test public void eachStripKindIsNamedOnceWithLoggingOnWhateverThePlaceSwitchesSay() {
        BaseSettings.DEBUG.save(true);
        List<?> anchors = Arrays.asList(new TypedAnchor("anchor_movie_tok", 61), new Anchor("anchor_poi"), null,
                new Object(), new TypedAnchor("anchor_movie_tok", 61), new TypedAnchor("anchor_effect", 28));
        assertSame(anchors, LocationBadgeFilter.visibleAnchors(anchors));
        assertSame(anchors, LocationBadgeFilter.visibleAnchors(anchors));

        String report = LogBufferManager.buildExportText();
        assertEquals(report, 1, times(report, "Caption strip served: anchor_movie_tok/61"));
        assertEquals(report, 1, times(report, "Caption strip served: anchor_effect/28"));
        assertEquals(report, 1, times(report, "Caption strip served: anchor_poi"));
        assertEquals(report, 3, times(report, "Caption strip served: "));
    }

    @Test public void aStripKindMetWithLoggingOffIsNamedOnceLoggingIsOn() {
        List<?> anchors = List.of(new TypedAnchor("anchor_sport", 101));
        assertSame(anchors, LocationBadgeFilter.visibleAnchors(anchors));
        assertFalse(LogBufferManager.buildExportText().contains("Caption strip served"));

        BaseSettings.DEBUG.save(true);
        assertSame(anchors, LocationBadgeFilter.visibleAnchors(anchors));
        String report = LogBufferManager.buildExportText();
        assertEquals(report, 1, times(report, "Caption strip served: anchor_sport/101"));
    }

    @Test public void namingStripsLeavesHidingPlacesAsItWas() {
        BaseSettings.DEBUG.save(true);
        Settings.HIDE_LOCATION_LABELS.save(true);
        Anchor effect = new TypedAnchor("anchor_effect", 28);
        List<?> anchors = Arrays.asList(new Anchor("anchor_poi"), effect);

        assertEquals(List.of(effect), LocationBadgeFilter.visibleAnchors(anchors));
        assertEquals(2, anchors.size());
        String report = LogBufferManager.buildExportText();
        assertEquals(report, 1, times(report, "Caption strip served: anchor_poi"));
        assertEquals(report, 1, times(report, "Caption strip served: anchor_effect/28"));
    }

    @Test public void onlyIdentifierKeysAreNamedAndNoMoreThanThirtyTwoKinds() {
        BaseSettings.DEBUG.save(true);
        List<Object> anchors = new ArrayList<>();
        anchors.add(new Anchor("Find Lemon8 recipes"));
        anchors.add(new Anchor("x".repeat(81)));
        anchors.add(new Anchor(""));
        for (int kind = 0; kind < 40; kind++) anchors.add(new TypedAnchor("anchor_kind_" + kind, kind));

        LocationBadgeFilter.visibleAnchors(anchors);
        String report = LogBufferManager.buildExportText();
        assertFalse(report, report.contains("Lemon8"));
        assertEquals(report, 32, times(report, "Caption strip served: "));
        assertEquals(report, 1, times(report, "Caption strip served: anchor_kind_31/31"));
        assertEquals(report, 0, times(report, "Caption strip served: anchor_kind_32/"));
    }

    @Test public void theExportCountsEachStripKindWithLoggingOff() {
        List<?> first = Arrays.asList(new TypedAnchor("anchor_movie_tok", 61), new Anchor("anchor_poi"), null,
                new Object(), new Anchor("Find Lemon8 recipes"));
        assertSame(first, LocationBadgeFilter.visibleAnchors(first));
        List<?> second = List.of(new TypedAnchor("anchor_effect", 28));
        assertSame(second, LocationBadgeFilter.visibleAnchors(second));
        assertTrue(LocationBadgeFilter.visibleAnchors(List.of()).isEmpty());

        String report = String.join("|", FeedFilterCounters.report());
        assertTrue(report, report.contains("CaptionStrip: 3 lists, 6 items, 0 removed. "
                + "Kinds: anchor_effect/28 1, anchor_movie_tok/61 1, anchor_poi 1"));
        assertFalse(report, report.contains("Lemon8"));
        assertFalse(LogBufferManager.buildExportText().contains("Caption strip served"));
    }

    @Test public void theExportCountsThePlacesHidingTookOut() {
        Settings.HIDE_LOCATION_LABELS.save(true);
        Anchor effect = new TypedAnchor("anchor_effect", 28);
        assertEquals(List.of(effect), LocationBadgeFilter.visibleAnchors(
                Arrays.asList(new Anchor("anchor_poi"), effect, new Anchor("anchor_poi"))));

        String report = String.join("|", FeedFilterCounters.report());
        assertTrue(report, report.contains("CaptionStrip: 1 lists, 3 items, 2 removed. Last reason: placeLabel. "
                + "Kinds: anchor_poi 2, anchor_effect/28 1"));
    }

    @Test public void creationTagsAreOffByDefaultAndLeaveEveryStripAlone() {
        assertFalse(Settings.HIDE_CREATION_TAGS.defaultValue);
        List<?> anchors = List.of(new TypedAnchor("anchor_effect", 28), new TypedAnchor("anchor_capcut", 54));
        assertSame(anchors, LocationBadgeFilter.visibleAnchors(anchors));
    }

    @Test public void creationTagsRemoveToolsAndKeepPlacesFilmsAndOtherStripsInOrder() {
        Settings.HIDE_CREATION_TAGS.save(true);
        Anchor place = new TypedAnchor("anchor_poi", 45), film = new TypedAnchor("anchor_movie_tok", 61);
        Anchor drama = new TypedAnchor("anchor_pine_drama", 117), lemon = new Anchor("anchor_lemon");
        List<?> anchors = Arrays.asList(new TypedAnchor("anchor_effect", 28), place, new TypedAnchor("anchor_capcut", 54),
                null, film, new TypedAnchor("anchor_ucg_template", 65), drama, new TypedAnchor("anchor_pugc_template", 78),
                lemon, new Anchor("anchor_ai_style"));
        Video video = new Video(anchors);

        assertEquals(Arrays.asList(place, null, film, drama, lemon), LocationBadgeFilter.visibleAnchors(anchors));
        assertSame(anchors, video.anchors);
        assertEquals(10, anchors.size());
        assertTrue(LocationBadgeFilter.hasBadge(video));
    }

    @Test public void onlyExactToolKeysCount() {
        Settings.HIDE_CREATION_TAGS.save(true);
        List<?> lookalikes = Arrays.asList(new Anchor("effect"), new Anchor("ANCHOR_EFFECT"), new Anchor("anchor_effect_dur"),
                new Anchor("anchor_icon_color_for_effect_and_template"), new Anchor("anchor_template_used_functions"),
                new Anchor(null), new Object());
        assertSame(lookalikes, LocationBadgeFilter.visibleAnchors(lookalikes));
    }

    @Test public void bothSwitchesTogetherCountEachReasonOnItsOwn() {
        Settings.HIDE_LOCATION_LABELS.save(true);
        Settings.HIDE_CREATION_TAGS.save(true);
        Anchor film = new TypedAnchor("anchor_movie_tok", 61);
        assertEquals(List.of(film), LocationBadgeFilter.visibleAnchors(Arrays.asList(new Anchor("anchor_poi"),
                new TypedAnchor("anchor_effect", 28), film, new TypedAnchor("anchor_capcut", 54))));

        String report = String.join("|", FeedFilterCounters.report());
        assertTrue(report, report.contains("CaptionStrip: 1 lists, 4 items, 3 removed. Last reason: creationTag. "));

        // A list's places are counted before its tags, so a place alone shows it isn't put down to
        // the creation switch either.
        assertEquals(List.of(film), LocationBadgeFilter.visibleAnchors(Arrays.asList(film, new Anchor("anchor_poi"))));
        report = String.join("|", FeedFilterCounters.report());
        assertTrue(report, report.contains("CaptionStrip: 2 lists, 6 items, 4 removed. Last reason: placeLabel. "));
    }

    @Test public void placesStayWhenOnlyCreationTagsAreHidden() {
        Settings.HIDE_CREATION_TAGS.save(true);
        Anchor place = new Anchor("anchor_poi");
        assertEquals(List.of(place), LocationBadgeFilter.visibleAnchors(Arrays.asList(place, new Anchor("anchor_template"))));

        String report = String.join("|", FeedFilterCounters.report());
        assertTrue(report, report.contains("CaptionStrip: 1 lists, 2 items, 1 removed. Last reason: creationTag."));
    }
}
