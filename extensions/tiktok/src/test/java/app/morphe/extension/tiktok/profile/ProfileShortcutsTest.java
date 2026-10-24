package app.morphe.extension.tiktok.profile;

import static org.junit.Assert.*;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.profile.platform.base.data.ProfileComponents;
import com.ss.android.ugc.profile.platform.base.data.ProfileUser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class ProfileShortcutsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** TikTok's node with its bizData, a gson JsonObject whose toString is the JSON text. */
    public static final class Node extends ProfileComponents {
        public Object bizData;
    }

    @After public void reset() {
        Settings.HIDDEN_PROFILE_SHORTCUTS.save("");
        Settings.PROFILE_SHORTCUT_PICKS.save("");
        Settings.PROFILE_SHORTCUT_CATALOG.save("");
    }

    private static Node node(String name, String json, Object... children) {
        Node node = new Node();
        node.componentName = name;
        node.bizData = json == null ? null : new Object() {
            @Override public String toString() { return json; }
        };
        node.components = new ArrayList<>(Arrays.asList(children));
        return node;
    }

    private static Node pill(String kind, int id, String title) {
        return node("advanced_feature_" + kind,
                "{\"feature_id\":" + id + ",\"describe\":{\"text\":\"" + title + "\",\"starling_key\":\"k_" + id + "\"}}");
    }

    private static ProfileUser user(ProfileComponents header) {
        ProfileUser user = new ProfileUser();
        user.headerComponents = header;
        return user;
    }

    private static List<String> names(List<?> components) {
        List<String> result = new ArrayList<>();
        for (Object component : components) result.add(((ProfileComponents) component).componentName);
        return result;
    }

    @Test public void hidesTheChosenShortcutsInPlaceByTitleKindOrFeatureId() {
        Node studio = pill("creator_tools", 7, "TikTok Studio");
        Node orders = pill("base_item", 21, "Your orders");
        Node shop = pill("shop", 3, "Shop");
        Node events = pill("base_item", 40, "LIVE events");
        Node row = node("advanced_feature", null, studio, orders, shop, events);
        List<?> original = row.components;
        Node header = node("header", null, node("bio", "{}"), row);

        Settings.HIDDEN_PROFILE_SHORTCUTS.save("tiktok studio, Shop, feature 40");
        ProfileShortcuts.onProfileData(user(header));

        assertSame("the row's own list is changed, so anything holding it agrees", original, row.components);
        assertEquals(List.of("advanced_feature_base_item"), names(row.components));
        assertSame(orders, row.components.get(0));
    }

    @Test public void recordsEveryShortcutSeenEvenWithNothingHidden() {
        Node row = node("advanced_feature", null,
                pill("creator_tools", 7, "TikTok Studio"), pill("base_item", 21, "Your orders"));
        ProfileShortcuts.onProfileData(user(node("header", null, row)));

        assertEquals(2, row.components.size());
        List<ProfileShortcutCatalog.Entry> entries = ProfileShortcutCatalog.entries();
        assertEquals(2, entries.size());
        assertEquals("creator tools", entries.get(0).key);
        assertEquals("TikTok Studio", entries.get(0).label);
        assertEquals("feature 21", entries.get(1).key);
        assertEquals("Your orders", entries.get(1).label);
    }

    @Test public void aRowTikTokCannotChangeIsReplacedInstead() {
        Node studio = pill("creator_tools", 7, "TikTok Studio");
        Node shop = pill("shop", 3, "Shop");
        Node row = node("advanced_feature", null);
        row.components = List.of(studio, shop);

        Settings.HIDDEN_PROFILE_SHORTCUTS.save("creator_tools");
        ProfileShortcuts.onProfileData(user(row));

        assertEquals(List.of("advanced_feature_shop"), names(row.components));
    }

    @Test public void aShortcutWithNoReadableDataIsStillNamedByItsKind() {
        Node broken = node("advanced_feature_wallet", "not json");
        Node bare = node("advanced_feature_qa", null);
        Node row = node("advanced_feature", null, broken, bare, "not a component");

        Settings.HIDDEN_PROFILE_SHORTCUTS.save("wallet");
        ProfileShortcuts.onProfileData(user(row));

        assertEquals(2, row.components.size());
        assertSame(bare, row.components.get(0));
        assertEquals("not a component", row.components.get(1));
    }

    @Test public void checklistPicksAndTypedNamesHideAlike() {
        Node row = node("advanced_feature", null,
                pill("creator_tools", 7, "TikTok Studio"), pill("shop", 3, "Shop"), pill("base_item", 21, "Your orders"));
        Settings.PROFILE_SHORTCUT_PICKS.save("creator tools");
        Settings.HIDDEN_PROFILE_SHORTCUTS.save("Shop");
        ProfileShortcuts.onProfileData(user(row));

        assertEquals(List.of("advanced_feature_base_item"), names(row.components));
    }

    @Test public void aFullCatalogDropsTheOldestShortcutButNeverAHiddenOne() {
        for (int batch = 0; batch < 48; batch += 8) {
            Node row = node("advanced_feature", null);
            for (int index = batch; index < batch + 8; index++) row.components.add(pill("k" + index, 100 + index, "K " + index));
            ProfileShortcuts.onProfileData(user(row));
        }
        assertEquals(48, ProfileShortcutCatalog.entries().size());
        Settings.PROFILE_SHORTCUT_PICKS.save("k0");
        Settings.HIDDEN_PROFILE_SHORTCUTS.save("K 1");

        ProfileShortcuts.onProfileData(user(node("advanced_feature", null, pill("fresh", 900, "Fresh"))));

        List<String> keys = new ArrayList<>();
        for (ProfileShortcutCatalog.Entry entry : ProfileShortcutCatalog.entries()) keys.add(entry.key);
        assertEquals(48, keys.size());
        assertTrue("a picked shortcut stays", keys.contains("k0"));
        assertTrue("a shortcut hidden by its typed name stays", keys.contains("k1"));
        assertFalse("the oldest one not hidden makes room", keys.contains("k2"));
        assertEquals("the new one is listed last", "fresh", keys.get(47));
    }

    @Test public void nothingInTheHeaderEscapesTheHook() {
        ProfileShortcuts.onProfileData(null);
        ProfileShortcuts.onProfileData(new ProfileUser());
        Node row = node("advanced_feature", null);
        row.components = null;
        ProfileShortcuts.onProfileData(user(row));

        ProfileComponents loop = node("header", null);
        loop.components = new ArrayList<>(List.of(loop));
        Settings.HIDDEN_PROFILE_SHORTCUTS.save("shop");
        ProfileShortcuts.onProfileData(user(loop));
        assertEquals(1, loop.components.size());
    }
}
