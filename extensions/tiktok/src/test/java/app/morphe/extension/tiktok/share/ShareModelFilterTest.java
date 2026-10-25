package app.morphe.extension.tiktok.share;

import static org.junit.Assert.*;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class ShareModelFilterTest {
    public static final class Item {
        private final String key;
        private final String label;
        Item(String key) { this(key, null); }
        Item(String key, String label) { this.key = key; this.label = label; }
        public String key() { return key; }
        public String getLabel() { return label; }
    }
    /** Stands in for BaseSharePackage, whose itemType is a public field TikTok never renames. */
    public static final class Package {
        public final String itemType;
        Package(String itemType) { this.itemType = itemType; }
    }
    // Every case here writes a Setting. Run this class first in a sandbox and all three
    // failed, because Setting's static initialiser needs a context and only @After set one.
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @After public void reset() {
        Settings.SHARE_HIDDEN_ITEMS.save("");
        Settings.HIDE_SHARE_CHANNELS.save(false);
        Settings.HIDE_SHARE_ACTIONS.save(false);
        Settings.HIDE_SHARE_CONTACTS.save(false);
        Settings.SHARE_ACTION_CATALOG.save("");
        Settings.SHARE_HIDDEN_ITEMS_PROFILE.save(Settings.SHARE_HIDDEN_ITEMS_FOLLOW_VIDEO);
        Settings.SHARE_HIDDEN_ITEMS_LIVE.save(Settings.SHARE_HIDDEN_ITEMS_FOLLOW_VIDEO);
        ShareModelFilter.surface(new Package("aweme"));
        HookStatus.clear();
    }
    @Test public void filtersBeforeRenderingWithoutChangingTheBuilderOrUnknownItems() {
        Object copy = new Item("copy"), save = new Item("save"), unknown = new Object();
        List<?> input = Arrays.asList(copy, save, unknown);
        assertSame(input, ShareModelFilter.channels(input));
        Settings.SHARE_HIDDEN_ITEMS.save("Copy link, save video");
        assertEquals(List.of(unknown), ShareModelFilter.channels(input));
        assertEquals(3, input.size());
        Settings.SHARE_HIDDEN_ITEMS.save("");
        assertSame(input, ShareModelFilter.channels(input));
    }
    /** With logging on, an export says what each row was handed and what it kept (#120). */
    @Test public void eachRowSaysWhatItWasHandedAndKept() {
        boolean debug = BaseSettings.DEBUG.get();
        try {
            BaseSettings.DEBUG.save(true);
            Settings.SHARE_HIDDEN_ITEMS.save("report");
            ShadowLog.clear();
            List<?> input = Arrays.asList(new Item("report"), new Item("captions"), new Object());
            assertEquals(2, ShareModelFilter.actions(input).size());
            List<?> untouched = input.subList(1, 3);
            assertSame(untouched, ShareModelFilter.channels(untouched));

            List<String> lines = new ArrayList<>();
            for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
                if (item.msg.contains("Share sheet model")) lines.add(item.msg);
            }
            String all = String.join("\n", lines);
            assertTrue(all, all.contains("Share sheet model VIDEO actions: 3 in [report, captions, ?], 2 kept in a new list"));
            assertTrue(all, all.contains("Share sheet model VIDEO channels: 2 in [captions, ?], 2 kept"));
            assertFalse("an untouched row says so: " + all, all.contains("channels: 2 in [captions, ?], 2 kept in a new list"));
        } finally {
            BaseSettings.DEBUG.save(debug);
        }
    }

    @Test public void rowsCanBeHiddenIndependently() {
        List<?> input = List.of(new Item("save"));
        Settings.HIDE_SHARE_ACTIONS.save(true);
        assertTrue(ShareModelFilter.actions(input).isEmpty());
        assertSame(input, ShareModelFilter.channels(input));
        assertTrue(ShareModelFilter.contacts(true));
        Settings.HIDE_SHARE_CONTACTS.save(true);
        assertFalse(ShareModelFilter.contacts(true));
        assertFalse(ShareModelFilter.contacts(false));
    }

    /** A list that behaves the way TikTok's own model does not, to prove the boundary holds. */
    static final class HostileList extends java.util.AbstractList<Object> {
        @Override public Object get(int index) { throw new IllegalStateException("hostile"); }
        @Override public int size() { throw new IllegalStateException("hostile"); }
        @Override public boolean isEmpty() { throw new IllegalStateException("hostile"); }
        @Override public java.util.Iterator<Object> iterator() {
            throw new IllegalStateException("hostile");
        }
    }

    @Test public void aFilterThatThrowsHandsTikTokItsOwnRowsBackAndSaysSo() {
        // These three run inside the share model's constructor. Anything that escapes takes the
        // sheet with it, which upstream saw as a Share button that did nothing and then a crash.
        HookStatus.clear();
        Settings.SHARE_HIDDEN_ITEMS.save("copy");
        List<?> hostile = new HostileList();

        assertSame("a failed channels filter leaves TikTok's own row alone",
                hostile, ShareModelFilter.channels(hostile));
        assertSame("a failed actions filter leaves TikTok's own row alone",
                hostile, ShareModelFilter.actions(hostile));

        List<String> missing = HookStatus.missing(ShareModelFilter.FAMILY);
        assertEquals("the catalogue and both filters are each named once", 3, missing.size());
        assertTrue(missing.toString(), missing.get(0).contains("'channels'"));
        assertTrue(missing.toString(), missing.get(0).contains("IllegalStateException"));
        assertTrue(HookStatus.report().toString(),
                HookStatus.report().get(0).startsWith(ShareModelFilter.FAMILY + ": 0 found, 3 missing"));
    }

    @Test public void actionModelsAreRememberedWithReadableLabelsAndStableKeys() {
        List<?> input = Arrays.asList(
                new Item("copy", "Copy link"),
                new Item("save_video", "Save video")
        );
        assertSame(input, ShareModelFilter.actions(input));
        List<ShareActionCatalog.Entry> catalog = ShareActionCatalog.entries();
        assertEquals(2, catalog.size());
        assertEquals("copy", catalog.get(0).key);
        assertEquals("Copy link", catalog.get(0).label);
        assertEquals("save", catalog.get(1).key);
        assertEquals("Save video", catalog.get(1).label);
    }

    @Test public void profileAndLiveFollowTheVideoListUntilTheirOwnIsSaved() {
        Object copy = new Item("copy"), save = new Item("save");
        List<?> input = Arrays.asList(copy, save);
        Settings.SHARE_HIDDEN_ITEMS.save("copy");

        for (String itemType : new String[] {"aweme", "user", "live"}) {
            ShareModelFilter.surface(new Package(itemType));
            assertEquals(itemType + " follows the video list", List.of(save), ShareModelFilter.actions(input));
        }

        Settings.SHARE_HIDDEN_ITEMS_PROFILE.save("save");
        ShareModelFilter.surface(new Package("user"));
        assertEquals("the profile sheet uses its own list", List.of(copy), ShareModelFilter.actions(input));
        assertEquals("and so does its channels row", List.of(copy), ShareModelFilter.channels(input));
        ShareModelFilter.surface(new Package("live"));
        assertEquals("LIVE still follows the video list", List.of(save), ShareModelFilter.actions(input));
        ShareModelFilter.surface(new Package("aweme"));
        assertEquals("the video sheet is untouched", List.of(save), ShareModelFilter.actions(input));

        Settings.SHARE_HIDDEN_ITEMS_LIVE.save("");
        ShareModelFilter.surface(new Package("live"));
        assertSame("an empty saved LIVE list hides nothing, it does not fall back", input,
                ShareModelFilter.actions(input));
    }

    @Test public void anUnknownKindOrAnUnreadablePackageIsTreatedAsAVideo() {
        Settings.SHARE_HIDDEN_ITEMS.save("copy");
        Settings.SHARE_HIDDEN_ITEMS_PROFILE.save("");
        Settings.SHARE_HIDDEN_ITEMS_LIVE.save("");
        List<?> input = List.of(new Item("copy"));
        for (Object pkg : new Object[] {new Package("aweme_photo"), new Package(null), new Object(), null}) {
            ShareModelFilter.surface(new Package("user"));
            ShareModelFilter.surface(pkg);
            assertEquals(String.valueOf(pkg), ShareSurface.VIDEO, ShareModelFilter.current());
            assertTrue(ShareModelFilter.actions(input).isEmpty());
        }
        ShareModelFilter.surface(new Package(" USER "));
        assertEquals(ShareSurface.PROFILE, ShareModelFilter.current());
    }

    @Test public void theCatalogueRemembersWhichSheetsEachActionWasSeenOn() {
        Settings.SHARE_ACTION_CATALOG.save("copy\tCopy link");
        ShareModelFilter.surface(new Package("user"));
        ShareModelFilter.actions(Arrays.asList(new Item("copy", "Copy link"), new Item("qr_code", "QR code")));
        ShareModelFilter.surface(new Package("live"));
        ShareModelFilter.actions(List.of(new Item("report", "Report")));

        assertEquals("a line written before the split counts as seen on videos",
                List.of("copy"), keys(ShareActionCatalog.entries(ShareSurface.VIDEO)));
        assertEquals(List.of("copy", "qr code"), keys(ShareActionCatalog.entries(ShareSurface.PROFILE)));
        assertEquals(List.of("report"), keys(ShareActionCatalog.entries(ShareSurface.LIVE)));
        assertEquals(3, ShareActionCatalog.entries().size());
    }

    private static List<String> keys(List<ShareActionCatalog.Entry> entries) {
        List<String> keys = new java.util.ArrayList<>();
        for (ShareActionCatalog.Entry entry : entries) keys.add(entry.key);
        return keys;
    }
}
