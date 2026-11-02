package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Looper;
import android.view.View;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class StoryOwnershipTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.SAVE_STORY.save(true);
        ShadowToast.reset();
    }

    @After public void tearDown() {
        Settings.SAVE_STORY.save(false);
        SettingsStatus.advancedDownloadsEnabled = false;
    }

    @Test public void boundStoryOwnerDoesNotKeepItsViewAlive() throws Exception {
        PlayArea component = new PlayArea();
        Object story = new Object();
        StoryDownloads.recordStory(component, 0, story);
        StoryDownloads.attachPlayArea(component, component.view);
        assertSame("the live owner's story was not bound", story, StoryDownloads.storyFor(component.view));

        Map<View, ?> owners = ReflectionHelpers.getStaticField(StoryDownloads.class, "OWNERS");
        Object ownerEntry = owners.get(component.view);
        assertNotNull("attachPlayArea never recorded the view's owner", ownerEntry);
        assertTrue("the fixture must model the native component owning its view",
                stronglyReaches(component, component.view));
        assertFalse("the static owner value keeps its own weak View key alive",
                stronglyReaches(ownerEntry, component.view));

        // Model an owner that has gone away while Android still carries the view. Clearing
        // the weak link is deterministic; the long-press path must handle its missing owner.
        ((Reference<?>) ownerEntry).clear();
        assertNull(StoryDownloads.storyFor(component.view));
        assertTrue(component.view.performLongClick());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Open the story again and try once more", ShadowToast.getTextOfLatestToast());
        assertEquals(0, MediaJobScheduler.queuedJobs());
    }

    @Test public void liveOwnersAndReboundViewsKeepTheirOwnStories() {
        PlayArea showing = new PlayArea();
        PlayArea besideIt = new PlayArea();
        Object first = new Object(), second = new Object(), rebound = new Object();
        StoryDownloads.recordStory(showing, 0, first);
        StoryDownloads.attachPlayArea(showing, showing.view);
        StoryDownloads.recordStory(besideIt, 1, second);
        StoryDownloads.attachPlayArea(besideIt, besideIt.view);
        assertSame(first, StoryDownloads.storyFor(showing.view));
        assertSame(second, StoryDownloads.storyFor(besideIt.view));

        StoryDownloads.recordStory(showing, 2, rebound);
        assertSame(rebound, StoryDownloads.storyFor(showing.view));
        StoryDownloads.attachPlayArea(besideIt, showing.view);
        assertSame("a recycled view kept its previous component", second,
                StoryDownloads.storyFor(showing.view));
        assertSame(second, StoryDownloads.storyFor(besideIt.view));

        Settings.SAVE_STORY.save(false);
        StoryDownloads.attachPlayArea(besideIt, showing.view);
        assertNull("disabling left the old owner behind", StoryDownloads.storyFor(showing.view));
    }

    /** ReusedUIContentAssem holds the play-area view in its LLJJIJI field. */
    private static final class PlayArea {
        final View view = new View(RuntimeEnvironment.getApplication());
    }

    /** Follow strong fields, stopping before the JVM's weak/soft referent field. */
    private static boolean stronglyReaches(Object root, Object target) throws IllegalAccessException {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        ArrayDeque<Object> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Object current = pending.remove();
            if (current == target) return true;
            if (seen.put(current, Boolean.TRUE) != null || current instanceof Context
                    || current instanceof Class<?> || current instanceof ClassLoader
                    || current instanceof String) continue;
            for (Class<?> type = current.getClass(); type != null && type != Object.class
                    && type != Reference.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                    field.setAccessible(true);
                    Object child = field.get(current);
                    if (child != null) pending.add(child);
                }
            }
        }
        return false;
    }
}
