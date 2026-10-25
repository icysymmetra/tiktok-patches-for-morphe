/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.feedfilter.CreatorExceptionsTest.Item;
import app.morphe.extension.tiktok.feedfilter.CreatorExceptionsTest.Sticker;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.FeedItemList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Match text stickers too: Blocked caption words read against the words of a post's text
 * stickers, through the feed path a response takes. The post model is CreatorExceptionsTest's,
 * which answers every getter the filters read with a plain value.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StickerTextFilterTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int TEXT = AdvancedFeedRules.TEXT_STICKER_TYPE;
    private static final int CAPTIONED = AdvancedFeedRules.CAPTIONED_TEXT_STICKER_TYPE;
    /** A sticker type that keeps JSON in textStruct rather than the sticker's words. */
    private static final int ANCHORED = 5;

    @Before
    public void setUp() {
        BaseSettings.DEBUG.save(false);
        Settings.BLOCKED_CAPTION_WORDS.save("");
        Settings.BLOCKED_WORDS_IN_STICKERS.save(false);
        FeedItemsFilter.resetDiagnosticsForTests();
    }

    @After
    public void tearDown() {
        Settings.BLOCKED_CAPTION_WORDS.resetToDefault();
        Settings.BLOCKED_WORDS_IN_STICKERS.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        FeedItemsFilter.resetDiagnosticsForTests();
    }

    private static Item post(String aid, Sticker... stickers) {
        Item item = new Item(aid);
        item.stickers = stickers.length == 0 ? null : Arrays.asList(stickers);
        return item;
    }

    private static List<String> survivors(Item... items) {
        FeedItemList list = new FeedItemList();
        list.items = new ArrayList<>(Arrays.asList(items));
        FeedItemsFilter.filter(list);
        List<String> aids = new ArrayList<>();
        for (Object item : list.items) aids.add(((Aweme) item).getAid());
        return aids;
    }

    @Test
    public void onlyTheWordsOfTextStickersAreRead() {
        Item item = post("a", new Sticker(TEXT, "  first words "), new Sticker(ANCHORED, "{\"anchors\":[]}"),
                null, new Sticker(TEXT, " "), new Sticker(TEXT, null), new Sticker(TEXT, "second"),
                new Sticker(CAPTIONED, "captioned words"));
        assertEquals(List.of("first words", "second", "captioned words"), AdvancedFeedRules.stickerTexts(item));
        assertEquals(List.of(), AdvancedFeedRules.stickerTexts(post("none")));
    }

    @Test
    public void aStickerWithABlockedWordHidesThePostOnlyWithTheSwitchOn() {
        Settings.BLOCKED_CAPTION_WORDS.save("giveaway, \"link\" & \"bio\"");
        assertEquals("the switch is off", List.of("tagged", "linked", "plain", "anchored"), survivors(
                post("tagged", new Sticker(TEXT, "GIVEAWAY tonight")),
                post("linked", new Sticker(TEXT, "link in bio")),
                post("plain", new Sticker(TEXT, "just a dance")),
                post("anchored", new Sticker(ANCHORED, "giveaway"))));

        Settings.BLOCKED_WORDS_IN_STICKERS.save(true);
        assertEquals(List.of("plain", "anchored", "half"), survivors(
                post("tagged", new Sticker(TEXT, "GIVEAWAY tonight")),
                post("linked", new Sticker(TEXT, "just a dance"), new Sticker(TEXT, "link in bio")),
                post("plain", new Sticker(TEXT, "just a dance")),
                post("anchored", new Sticker(ANCHORED, "giveaway")),
                post("half", new Sticker(TEXT, "a link, nothing else"))));
    }

    @Test
    public void theSwitchNeedsBlockedWords() {
        AdvancedFeedRules.StickerTextFilter filter = new AdvancedFeedRules.StickerTextFilter();
        Settings.BLOCKED_WORDS_IN_STICKERS.save(true);
        assertFalse("no words", filter.getEnabled());
        Settings.BLOCKED_CAPTION_WORDS.save("   ");
        assertFalse("blank words", filter.getEnabled());
        Settings.BLOCKED_CAPTION_WORDS.save("giveaway");
        assertTrue(filter.getEnabled());
        Settings.BLOCKED_WORDS_IN_STICKERS.save(false);
        assertFalse("switch off", filter.getEnabled());
    }

    @Test
    public void aHiddenPostIsCountedUnderItsOwnReason() {
        Settings.BLOCKED_CAPTION_WORDS.save("giveaway");
        Settings.BLOCKED_WORDS_IN_STICKERS.save(true);
        assertEquals(List.of("plain"), survivors(post("tagged", new Sticker(TEXT, "giveaway")), post("plain")));
        String report = String.join("\n", FeedFilterCounters.report());
        assertTrue(report, report.contains(" 1 removed") && report.contains("Last reason: StickerTextFilter"));
    }
}
