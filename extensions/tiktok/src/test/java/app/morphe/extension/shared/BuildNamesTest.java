package app.morphe.extension.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.res.Resources;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * A view name written for one TikTok build resolves on that build alone. TikTok hands its short
 * names out again on every build, mostly to other views, so 47.4.3's g8a (the like button) looked
 * up on the next build would find whatever that build calls g8a.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BuildNamesTest {
    private static final String PACKAGE = "com.zhiliaoapp.musically";
    private static final String PINEDRAMA = "com.ss.android.ttmd.video";

    @After
    public void tearDown() {
        BuildNames.setRunningBuildForTests(null);
    }

    @Test
    public void aNameWithNoBuildIsTheSameOnEveryBuild() {
        assertEquals("desc", BuildNames.entryName("desc", "47.4.3"));
        assertEquals("desc", BuildNames.entryName("desc", "47.5.1"));
    }

    @Test
    public void aNameWrittenForOneBuildResolvesOnThatBuildAlone() {
        assertEquals("g8a", BuildNames.entryName("47.4.3:g8a", "47.4.3"));
        assertNull(BuildNames.entryName("47.4.3:g8a", "47.5.1"));
        // A build that only starts or ends the same way is another build.
        assertNull(BuildNames.entryName("47.4.3:g8a", "47.4"));
        assertNull(BuildNames.entryName("47.4.3:g8a", "47.4.31"));
        assertNull(BuildNames.entryName("47.4.3:g8a", "147.4.3"));
    }

    /**
     * PineDrama reports TikTok's version names with a resource table of its own: 76 of the 82
     * view groups name an unrelated view or nothing there. A build's name never resolves in it,
     * even when its version matches; a real name still does (#156).
     */
    @Test
    public void aBuildsNameResolvesInTikTokAlone() {
        BuildNames.setRunningAppForTests(PINEDRAMA, "47.4.3");
        assertNull(BuildNames.entryName("47.4.3:g8a"));
        assertEquals("desc", BuildNames.entryName("desc"));

        BuildNames.setRunningAppForTests(PACKAGE, "47.4.3");
        assertEquals("g8a", BuildNames.entryName("47.4.3:g8a"));
    }

    @Test
    public void theCacheLooksUpOnlyTheRunningBuildsName() {
        Resources base = RuntimeEnvironment.getApplication().getResources();
        int[] asked = new int[1];
        @SuppressWarnings("deprecation")
        Resources resources = new Resources(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration()) {
            @Override
            public int getIdentifier(String name, String defType, String defPackage) {
                asked[0]++;
                if (!"id".equals(defType) || !PACKAGE.equals(defPackage)) return 0;
                if ("g8a".equals(name)) return 0x7f0a2185;
                if ("g6r".equals(name)) return 0x7f0a2170;
                return 0;
            }
        };
        BuildNames.setRunningBuildForTests("47.4.3");
        ResourceIdCache cache = new ResourceIdCache();

        assertEquals(0, cache.resolve(resources, PACKAGE, "47.5.1:g6r", false));
        assertEquals("another build's name never reaches the lookup", 0, asked[0]);
        assertEquals(0x7f0a2185, cache.resolve(resources, PACKAGE, "47.4.3:g8a", false));
        assertEquals(0x7f0a2170, cache.resolve(resources, PACKAGE, "g6r", false));
        assertEquals(2, asked[0]);

        // Remembered either way: a second round asks nothing.
        cache.resolve(resources, PACKAGE, "47.5.1:g6r", false);
        cache.resolve(resources, PACKAGE, "47.4.3:g8a", false);
        assertEquals(2, asked[0]);
    }
}
