package app.morphe.extension.tiktok.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * What the feed tab and bottom tab option lists do with keys and TikTok's runtime tags. The two
 * classes were copies of each other apart from their tabs, their aliases and the tabs that can't
 * be turned off; these pin that behaviour so sharing the code changes none of it.
 */
public class TabOptionsBehaviourTest {
    @Test public void feedTabsParseSerializeAndNameTheirKeys() {
        assertEquals("HOT,EXPLORE,FOLLOWING,MALL,NEARBY,FRIENDS,LIVE,POPULAR,STEM,SERIES",
                NavigationTabOptions.defaultEnabledKeys());
        String stored = "following, raw:Some_Tab ,bogus,,EXPLORE";
        assertEquals(List.of("FOLLOWING", "RAW:some_tab", "EXPLORE", "HOT"),
                new ArrayList<>(NavigationTabOptions.parseEnabledKeys(stored)));
        assertEquals(List.of("FOLLOWING", "RAW:some_tab", "EXPLORE"),
                new ArrayList<>(NavigationTabOptions.parseObservedKeys(stored)));
        assertEquals(List.of("HOT"), new ArrayList<>(NavigationTabOptions.parseEnabledKeys(null)));
        assertEquals("HOT,EXPLORE,FOLLOWING,RAW:some_tab",
                NavigationTabOptions.serializeEnabledKeys(NavigationTabOptions.parseObservedKeys(stored)));
        assertEquals("HOT", NavigationTabOptions.serializeEnabledKeys(new LinkedHashSet<>()));

        List<String> named = new ArrayList<>();
        for (TabOption option : NavigationTabOptions.optionsForKeys(
                new LinkedHashSet<>(List.of("RAW:some_tab", "FOLLOWING")))) {
            named.add(option.key + "=" + option.label);
        }
        assertEquals(List.of("FOLLOWING=Following", "RAW:some_tab=Some tab"), named);

        assertEquals("MALL", NavigationTabOptions.normalizeRuntimeTag("homepage_shop_mall"));
        assertEquals("NEARBY", NavigationTabOptions.normalizeRuntimeTag(" Local "));
        assertEquals("SERIES", NavigationTabOptions.normalizeRuntimeTag("drama"));
        assertEquals("RAW:weird tab", NavigationTabOptions.normalizeRuntimeTag("  Weird,Tab\n "));
        assertEquals("RAW:me", NavigationTabOptions.normalizeRuntimeTag("me"));
        assertNull(NavigationTabOptions.normalizeRuntimeTag(null));
        assertNull(NavigationTabOptions.normalizeRuntimeTag("   "));
        assertTrue(NavigationTabOptions.isKnownKey("hot"));
        assertFalse(NavigationTabOptions.isKnownKey("RAW:x"));
        assertEquals("Drama and Series", NavigationTabOptions.findOption(" series ").label);
        assertNull(NavigationTabOptions.findOption("HOME"));
    }

    @Test public void bottomTabsKeepHomeAndProfileWhateverIsStored() {
        assertEquals("HOME,FRIENDS,PUBLISH,INBOX,PROFILE,MALL", BottomNavigationTabOptions.defaultEnabledKeys());
        assertEquals(List.of("INBOX", "MALL", "HOME", "PROFILE"),
                new ArrayList<>(BottomNavigationTabOptions.parseEnabledKeys("inbox,mall")));
        assertEquals(List.of("INBOX", "MALL"),
                new ArrayList<>(BottomNavigationTabOptions.parseObservedKeys("inbox,mall,hot")));
        Set<String> observed = BottomNavigationTabOptions.parseObservedKeys("inbox,mall,raw:Now");
        assertEquals("HOME,INBOX,PROFILE,MALL,RAW:now", BottomNavigationTabOptions.serializeEnabledKeys(observed));

        List<String> named = new ArrayList<>();
        for (TabOption option : BottomNavigationTabOptions.optionsForKeys(observed)) {
            named.add(option.key + "=" + option.label);
        }
        assertEquals(List.of("INBOX=Inbox", "MALL=Shop", "RAW:now=Now"), named);

        assertEquals("PROFILE", BottomNavigationTabOptions.normalizeRuntimeTag("me"));
        assertEquals("PUBLISH", BottomNavigationTabOptions.normalizeRuntimeTag("PLUS"));
        assertEquals("INBOX", BottomNavigationTabOptions.normalizeRuntimeTag("message"));
        assertEquals("MALL", BottomNavigationTabOptions.normalizeRuntimeTag("Shop_Mall"));
        assertEquals("HOME", BottomNavigationTabOptions.normalizeRuntimeTag("homepage_home"));
        assertEquals("RAW:hot", BottomNavigationTabOptions.normalizeRuntimeTag("hot"));
        assertTrue(BottomNavigationTabOptions.isRequiredKey("HOME"));
        assertTrue(BottomNavigationTabOptions.isRequiredKey("PROFILE"));
        assertFalse(BottomNavigationTabOptions.isRequiredKey("INBOX"));
        assertFalse(BottomNavigationTabOptions.isRequiredKey("profile"));
        assertEquals("Create", BottomNavigationTabOptions.findOption("publish").label);
    }
}
