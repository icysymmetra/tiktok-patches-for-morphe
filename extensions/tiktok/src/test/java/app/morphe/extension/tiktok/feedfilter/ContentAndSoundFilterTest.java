package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.*;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import java.util.List;
import java.util.Locale;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ContentAndSoundFilterTest {
    // Built in setup(), not in a field initializer: a field initializer runs before the first
    // @Before, so naming a Setting here loads the settings classes with no context behind them.
    // SharedPrefCategory then throws, and an ExceptionInInitializerError is permanent for the
    // whole fork, so every later class that touches a setting fails too. This class used to get
    // away with it only when some other class happened to run first and leave a context set.
    private IFilter[] markers;
    private BooleanSetting[] switches;

    @Before public void setup() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        markers = new IFilter[]{new ContentMarkerFilters.AiGeneratedFilter(),
                new ContentMarkerFilters.PaidPartnershipFilter(), new ContentMarkerFilters.SeriesFilter(),
                new ContentMarkerFilters.PlaylistFilter(), new ContentMarkerFilters.VerifiedFilter(),
                new ContentMarkerFilters.DramaFilter()};
        switches = new BooleanSetting[]{Settings.HIDE_AI_GENERATED, Settings.HIDE_PAID_PARTNERSHIP,
                Settings.HIDE_SERIES, Settings.HIDE_PLAYLIST_VIDEOS, Settings.HIDE_VERIFIED,
                Settings.HIDE_MINI_DRAMAS};
        for (BooleanSetting setting : switches) setting.save(false);
        Settings.HIDE_BLOCKED_SOUNDS.save(false);
        Settings.BLOCKED_SOUND_IDS.save("");
        Settings.BLOCKED_SOUND_NAMES.save("");
    }

    @Test public void eachMarkerHasAnIndependentSwitchAndMissingDataDoesNotMatch() {
        for (int selected = 0; selected < switches.length; selected++) {
            for (int i = 0; i < switches.length; i++) switches[i].save(i == selected);
            for (int i = 0; i < markers.length; i++) {
                assertEquals(i == selected, markers[i].getEnabled());
                assertFalse(markers[i].getFiltered(null));
                assertFalse(markers[i].getFiltered(new Aweme()));
                assertFalse(markers[i].getFiltered(new Item()));
            }
        }
    }

    @Test public void aiLabelsAndModerationMarkersMatchWithoutRejectingUnmarkedStructs() {
        Item item = new Item();
        item.aigcInfo = new Label(0);
        assertFalse(markers[0].getFiltered(item));
        item.aigcInfo = new Label(1);
        assertTrue(markers[0].getFiltered(item));
        item.aigcInfo = null;
        item.moderationAigcInfo = new Moderation(0, 0);
        assertFalse("a moderation struct with every field at zero is not an AI label",
                markers[0].getFiltered(item));
        item.moderationAigcInfo = new Moderation(2, 0);
        assertTrue(markers[0].getFiltered(item));
        item.moderationAigcInfo = new Moderation(0, 1);
        assertTrue(markers[0].getFiltered(item));
    }

    /**
     * The signals kveld9 and ReVanced read besides the visible label, each on its own, and the
     * same structs left empty the way TikTok attaches them to ordinary posts.
     */
    @Test public void everyAiSignalMatchesAloneAndItsEmptyStructDoesNot() {
        Item ordinary = new Item();
        ordinary.aigcInfo = new Aigc(0, false);
        ordinary.moderationAigcInfo = new Moderation(0, 0);
        ordinary.c2paInfo = new C2pa("", " ", null);
        ordinary.aiAliveInfo = new Alive("", null);
        ordinary.aiRemixInfo = new Task(" ", "");
        ordinary.aiPortraitInfo = new Task(null, null);
        ordinary.anchors = List.of(new Anchor("anchor_shop"), new Anchor(null));
        ordinary.textExtra = List.of(new Tag("aitutorial"), new Tag("fyp"));
        ordinary.desc = "Trying the new #airfryer #aiden_fan and #a i";
        assertNull(ContentMarkerFilters.aiSignal(ordinary));
        assertFalse(markers[0].getFiltered(ordinary));

        Object[][] cases = {
                {"created by AI", (java.util.function.Consumer<Item>) i -> i.aigcInfo = new Aigc(0, true)},
                {"moderation", (java.util.function.Consumer<Item>) i -> {
                    Moderation guided = new Moderation(0, 0);
                    guided.creatorGuidanceStatus = 1;
                    i.moderationAigcInfo = guided;
                }},
                {"moderation", (java.util.function.Consumer<Item>) i -> {
                    Moderation segment = new Moderation(0, 0);
                    segment.moderationCreatorSegment = "short_drama_AIGC";
                    i.moderationAigcInfo = segment;
                }},
                {"content credentials", (java.util.function.Consumer<Item>) i -> i.c2paInfo = new C2pa("", "", "openai")},
                {"content credentials", (java.util.function.Consumer<Item>) i -> i.c2paInfo = new C2pa("dalle", null, null)},
                {"AI Alive", (java.util.function.Consumer<Item>) i -> i.aiAliveInfo = new Alive("ai_alive_v2", null)},
                {"AI Alive", (java.util.function.Consumer<Item>) i -> i.aiAliveInfo = new Alive(null, "Make it move")},
                {"AI remix", (java.util.function.Consumer<Item>) i -> i.aiRemixInfo = new Task("7421", null)},
                {"AI portrait", (java.util.function.Consumer<Item>) i -> i.aiPortraitInfo = new Task(null, "p-9")},
                {"AI effect anchor", (java.util.function.Consumer<Item>) i -> i.anchors = List.of(new Anchor("anchor_shop"), new Anchor("anchor_aigc_avatar"))},
                {"hashtag", (java.util.function.Consumer<Item>) i -> i.textExtra = List.of(new Tag("fyp"), new Tag("AIGenerated"))},
                {"hashtag", (java.util.function.Consumer<Item>) i -> i.desc = "sunset over the bay #fyp #AI"},
        };
        Settings.HIDE_AI_GENERATED.save(true);
        for (Object[] row : cases) {
            Item item = new Item();
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<Item> mark = (java.util.function.Consumer<Item>) row[1];
            mark.accept(item);
            assertEquals(row[0], ContentMarkerFilters.aiSignal(item));
            assertTrue(markers[0].getFiltered(item));
        }
        assertTrue("each match is counted under its signal", String.join("\n",
                app.morphe.extension.shared.diagnostics.FeedFilterCounters.report())
                .contains(ContentMarkerFilters.AI_SIGNALS_SOURCE + ": "));
    }

    @Test public void partnershipRequiresAccountsOrAnActualCommerceSignal() {
        Item item = new Item();
        item.brandContentAccounts = List.of();
        item.commerceVideoAuthInfo = new Commerce(false, 0L, 0L, null);
        item.commercialVideoInfo = "  ";
        assertFalse(markers[1].getFiltered(item));
        item.brandContentAccounts = List.of("brand");
        assertTrue(markers[1].getFiltered(item));
        item.brandContentAccounts = List.of();
        item.commerceVideoAuthInfo = new Commerce(true, 0L, 0L, null);
        assertTrue(markers[1].getFiltered(item));
        item.commerceVideoAuthInfo = new Commerce(false, 0L, 0L, null);
        item.commercialVideoInfo = "paid partnership";
        assertTrue(markers[1].getFiltered(item));
    }

    /** TikTok 47.0.3 renders Paid partnership from AwemeCommerceStruct, not isCommerce. */
    @Test public void partnershipReadsEveryBrandedContentSignalUsedBy4703() {
        Item item = new Item();
        item.commerceVideoAuthInfo = new Commerce(false, 1L, 0L, null);
        assertTrue("brandedContentType drives the visible Paid partnership label",
                markers[1].getFiltered(item));

        item.commerceVideoAuthInfo = new Commerce(false, 0L, 2L, null);
        assertTrue("brand organic content carries the same disclosure treatment",
                markers[1].getFiltered(item));

        item.commerceVideoAuthInfo = new Commerce(false, 0L, 0L, "Sponsored by Acme");
        assertTrue("the localized BO/BC label is a branded-content marker",
                markers[1].getFiltered(item));

        item.commerceVideoAuthInfo = new Commerce(false, 0L, 0L, "  ");
        assertFalse("an attached commerce struct with default fields is ordinary content",
                markers[1].getFiltered(item));
    }

    @Test public void seriesAndPlaylistDoNotShareTheirSignals() {
        Item item = new Item();
        item.isPaidContent = true;
        assertTrue(markers[2].getFiltered(item));
        assertFalse(markers[3].getFiltered(item));
        item.isPaidContent = false;
        item.mPaidContentInfo = new PaidContent(4471L, "Night Shift", "1", false);
        assertTrue(markers[2].getFiltered(item));
        item.mPaidContentInfo = null;
        item.mixInfo = new Mix("mix-1", null);
        assertFalse(markers[2].getFiltered(item));
        assertTrue(markers[3].getFiltered(item));

        item.mixInfo = null;
        item.playlist_info = new PlaylistInfo("playlist-47");
        assertTrue("TikTok 47 uses playlist_info for the visible playlist bar",
                markers[3].getFiltered(item));
        item.playlist_info = new PlaylistInfo("  ");
        assertFalse("an empty playlist_info is not a playlist", markers[3].getFiltered(item));
    }

    /**
     * Issue #5: with Hide series on, nine of ten videos in a For You batch were removed as
     * SeriesFilter and the feed never loaded. TikTok hangs a PaidContentInfo on ordinary
     * recommended videos with its fields at defaults, so the struct being there meant nothing.
     * The same shape was in the playlist and AI filters.
     */
    @Test public void anEmptyStructIsNotAMarkerOnAnyOfTheThreeFiltersThatUsedPresence() {
        Item ordinary = new Item();
        ordinary.mPaidContentInfo = new PaidContent();
        ordinary.mixInfo = new Mix(null, null);
        ordinary.moderationAigcInfo = new Moderation(0, 0);
        assertFalse("an empty paid content struct removed the whole feed in issue #5",
                markers[2].getFiltered(ordinary));
        assertFalse("an empty mix struct is not a playlist", markers[3].getFiltered(ordinary));
        assertFalse("an empty moderation struct is not an AI label", markers[0].getFiltered(ordinary));

        // Blank is the same as absent: the strings TikTok sends are empty, not null.
        ordinary.mPaidContentInfo = new PaidContent(0L, "  ", " ", false);
        ordinary.mixInfo = new Mix("", "   ");
        assertFalse(markers[2].getFiltered(ordinary));
        assertFalse(markers[3].getFiltered(ordinary));

        // Each real signal on its own still matches.
        Item series = new Item();
        series.mPaidContentInfo = new PaidContent(9182736L, null, null, false);
        assertTrue("a paid collection id is a series", markers[2].getFiltered(series));
        series.mPaidContentInfo = new PaidContent(0L, "Night Shift", null, false);
        assertTrue("a collection name is a series", markers[2].getFiltered(series));
        series.mPaidContentInfo = new PaidContent(0L, null, "3", false);
        assertTrue("an episode number is a series", markers[2].getFiltered(series));
        series.mPaidContentInfo = new PaidContent(0L, null, null, true);
        assertTrue("a collection intro is a series", markers[2].getFiltered(series));

        Item playlist = new Item();
        playlist.mixInfo = new Mix(null, "Part 3 of my trip");
        assertTrue("a named mix is a playlist", markers[3].getFiltered(playlist));
    }

    /**
     * Issue #20: with Hide series on, every profile grid came up empty. TikTok 47.0.3 sends
     * profile posts with a PaidContentInfo whose only filled field is episode_num "0", read off
     * an ordinary video on the S22. A zero episode is the default, not an episode.
     */
    @Test public void anEpisodeNumberOfZeroIsTheDefaultAndNotASeries() {
        Item profilePost = new Item();
        profilePost.mPaidContentInfo = new PaidContent(0L, "", "0", false);
        assertFalse("episode \"0\" emptied every profile grid in issue #20",
                markers[2].getFiltered(profilePost));
        for (String notAnEpisode : new String[] {" 0 ", "00", "-1", "0.0", "episode"}) {
            profilePost.mPaidContentInfo = new PaidContent(0L, "", notAnEpisode, false);
            assertFalse(notAnEpisode, markers[2].getFiltered(profilePost));
        }
        profilePost.mPaidContentInfo = new PaidContent(0L, "", " 12 ", false);
        assertTrue("a positive episode number is still a series", markers[2].getFiltered(profilePost));
    }

    @Test public void verifiedAccountsMatchNumericCustomAndEnterpriseMarkers() {
        Item item = new Item();
        Author author = new Author();
        item.author = author;
        assertFalse(markers[4].getFiltered(item));
        author.verificationType = 1;
        assertTrue(markers[4].getFiltered(item));
        author.verificationType = 0;
        author.customVerify = " creator ";
        assertTrue(markers[4].getFiltered(item));
        author.customVerify = " ";
        author.enterpriseVerifyReason = "business";
        assertTrue(markers[4].getFiltered(item));
        author.enterpriseVerifyReason = " ";
        assertFalse(markers[4].getFiltered(item));
    }

    @Test public void soundIdsAreExactAndNamesUseLocaleIndependentPhrases() {
        SoundFilter filter = new SoundFilter();
        Item item = new Item();
        Music music = new Music();
        item.music = music;
        Settings.HIDE_BLOCKED_SOUNDS.save(true);
        assertFalse(filter.getEnabled());
        Settings.BLOCKED_SOUND_IDS.save("123, 456");
        assertTrue(filter.getEnabled());
        music.mid = "1234";
        assertFalse(filter.getFiltered(item));
        music.mid = " 123 ";
        assertTrue(filter.getFiltered(item));
        music.mid = null;
        music.id = 456;
        assertTrue(filter.getFiltered(item));
        music.id = 0;
        music.title = "Original SOUND by somebody";
        Settings.BLOCKED_SOUND_NAMES.save(",, original sound, , unrelated");
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertTrue(filter.getFiltered(item));
        } finally { Locale.setDefault(previous); }
        music.title = "something else";
        assertFalse(filter.getFiltered(item));
        Settings.HIDE_BLOCKED_SOUNDS.save(false);
        assertFalse(filter.getEnabled());
        assertFalse(filter.getFiltered(null));
        assertFalse(filter.getFiltered(new Aweme()));
    }

    @Test public void getterAndFieldMusicShapesAgreeAndEditsTakeEffectOnNextRead() {
        SoundFilter filter = new SoundFilter();
        Aweme getterItem = new Aweme() { public Object getMusic() { return new Object() {
            public String getMusicId() { return "789"; }
            public String getMusicName() { return "Song"; }
        }; } };
        Settings.BLOCKED_SOUND_IDS.save("789");
        assertTrue(filter.getFiltered(getterItem));
        Settings.BLOCKED_SOUND_IDS.save("78");
        assertFalse(filter.getFiltered(getterItem));
        Settings.BLOCKED_SOUND_NAMES.save("SONG");
        assertTrue(filter.getFiltered(getterItem));
        Settings.BLOCKED_SOUND_NAMES.save(" ");
        assertFalse(filter.getFiltered(getterItem));
    }

    @Test public void soundIdentityUsesGetterThenFieldFallbacksAndExactEntries() {
        GetterMusic getter = new GetterMusic();
        assertEquals("789", SoundIdentity.idOf(getter));
        assertEquals("Song", SoundIdentity.nameOf(getter));
        assertEquals("Artist", SoundIdentity.authorOf(getter));

        Music fields = new Music();
        fields.id = 456;
        fields.title = "Field title";
        assertEquals("456", SoundIdentity.idOf(fields));
        assertEquals("Field title", SoundIdentity.nameOf(fields));
        assertEquals(List.of("one", "two"), SoundIdentity.entries(" one, ,two,, "));
        assertTrue(SoundIdentity.containsEntry("one,TWO", "two"));
        assertEquals("one,two", SoundIdentity.withEntry("one", "two"));
        assertEquals("one", SoundIdentity.withoutEntry("one,TWO", "two"));
    }

    private static final class Item extends Aweme {
        public Object aigcInfo, moderationAigcInfo, brandContentAccounts, commerceVideoAuthInfo;
        public String commercialVideoInfo;
        public boolean isPaidContent;
        public Object mPaidContentInfo, mixInfo, playlist_info, author, music;
        public Object c2paInfo, aiAliveInfo, aiRemixInfo, aiPortraitInfo, aiTheaterInfo, aiChatEditorInfo;
        public List<Object> anchors, textExtra;
        public String desc;
    }
    /** TikTok's AIGCInfo: the label type has a getter, createByAI is a field. */
    private static final class Aigc {
        private final int labelType;
        final boolean createByAI;
        Aigc(int labelType, boolean createByAI) { this.labelType = labelType; this.createByAI = createByAI; }
        public int getAIGCLabelType() { return labelType; }
    }
    /** TikTok's C2PAInfo, whose sources are plain fields. */
    private static final class C2pa {
        final String aigcSrc, firstAigcSrc, lastAigcSrc;
        C2pa(String aigcSrc, String firstAigcSrc, String lastAigcSrc) {
            this.aigcSrc = aigcSrc; this.firstAigcSrc = firstAigcSrc; this.lastAigcSrc = lastAigcSrc;
        }
    }
    private static final class Alive {
        private final String modelKey, modelPrompt;
        Alive(String modelKey, String modelPrompt) { this.modelKey = modelKey; this.modelPrompt = modelPrompt; }
        public String getModelKey() { return modelKey; }
        public String getModelPrompt() { return modelPrompt; }
    }
    /** AIRemixInfo, AIPortraitInfo and the other AI effect structs. */
    private static final class Task {
        private final String taskId, promptId;
        Task(String taskId, String promptId) { this.taskId = taskId; this.promptId = promptId; }
        public String getTaskId() { return taskId; }
        public String getPromptId() { return promptId; }
    }
    private static final class Anchor {
        private final String componentKey;
        Anchor(String componentKey) { this.componentKey = componentKey; }
        public String getComponentKey() { return componentKey; }
    }
    private static final class Tag {
        private final String name;
        Tag(String name) { this.name = name; }
        public String getHashTagName() { return name; }
    }
    private static final class Label {
        private final int value;
        Label(int value) { this.value = value; }
        public int getAIGCLabelType() { return value; }
    }
    private static final class Commerce {
        private final boolean value;
        public final long brandedContentType;
        public final long brandOrganicType;
        public final String ecSearchBoBcLabelText;

        Commerce(boolean value, long brandedContentType, long brandOrganicType, String label) {
            this.value = value;
            this.brandedContentType = brandedContentType;
            this.brandOrganicType = brandOrganicType;
            this.ecSearchBoBcLabelText = label;
        }

        public boolean isCommerce() { return value; }
        public boolean isBrandedContent() { return brandedContentType > 0L; }
        public boolean isBrandOrganicContent() { return brandOrganicType > 0L; }
        public long getBrandedContentType() { return brandedContentType; }
        public String getEcSearchBoBcLabelText() { return ecSearchBoBcLabelText; }
    }
    /** TikTok's PaidContentInfo: attached to ordinary videos with everything at its default. */
    private static final class PaidContent {
        long paidCollectionId;
        String collectionName;
        String episodeNumber;
        boolean isPaidCollectionIntro;

        PaidContent() {
        }

        PaidContent(long paidCollectionId, String collectionName, String episodeNumber, boolean intro) {
            this.paidCollectionId = paidCollectionId;
            this.collectionName = collectionName;
            this.episodeNumber = episodeNumber;
            this.isPaidCollectionIntro = intro;
        }
    }

    /** TikTok's MixStruct, which is only a playlist when it has an identity. */
    private static final class Mix {
        String mixId;
        String mixName;

        Mix(String mixId, String mixName) {
            this.mixId = mixId;
            this.mixName = mixName;
        }
    }

    /** TikTok 47's PlayListInfo, which backs the native playlist bar. */
    private static final class PlaylistInfo {
        String mixId;

        PlaylistInfo(String mixId) {
            this.mixId = mixId;
        }
    }

    /** TikTok's ModerationAigcInfo, whose label types are zero on an unlabelled video. */
    private static final class Moderation {
        int moderationAigcLabelType;
        int moderationUserLabelStatus;
        int creatorGuidanceStatus;
        String moderationCreatorSegment;

        Moderation(int labelType, int userLabelStatus) {
            this.moderationAigcLabelType = labelType;
            this.moderationUserLabelStatus = userLabelStatus;
        }
    }

    private static final class Author {
        public int verificationType;
        public String customVerify, enterpriseVerifyReason;
    }
    private static final class Music {
        public String mid, title;
        public long id;
    }
    private static final class GetterMusic {
        public String getMusicId() { return "789"; }
        public String getMusicName() { return "Song"; }
        public String getAuthorName() { return "Artist"; }
    }
}
