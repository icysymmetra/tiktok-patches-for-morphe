/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.app.Activity;
import android.content.Context;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.share.ShareActionCatalog;
import app.morphe.extension.tiktok.share.ShareModelFilter;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A save button in the share sheet a story's Share button opens (#93).
 *
 * <p>TikTok builds that sheet the way it builds a video's, from the story's own share package,
 * and the story's configuration leaves Report alone in the actions row. Once the sheet's model
 * has filtered and sorted that row, the patch hands it to {@link #actions}, which puts TikTok's
 * own video save action in front, built for the story the sheet was opened on. TikTok draws it
 * with its own icon and label and closes the sheet after a tap, as it does on a video.
 *
 * <p>The tap never reaches TikTok's download. {@link #click} gives the story to
 * {@link StoryDownloads}, the same save a hold makes, with its progress and notice. TikTok's
 * enable check reads the story's download permission, which would grey the button out, so
 * {@link #owns} answers it for the actions built here and for no others.
 *
 * <p>The story is the one the sheet's package names. The Share button that opened the sheet reads
 * it off its own story's cell, so it is never one of the pages bound beside it.
 */
public final class StoryShareSave {
    /** TikTok's key for its video save action, which this one is. */
    static final String SAVE_KEY = "save";
    private static final int STORY_TYPE = 40;
    private static final int STORY_TYPE_SHARED = 45;

    /** The actions built here, each with the story it was built for. Weak, so a closed sheet's go. */
    private static final List<Built> BUILT = new ArrayList<>();

    private StoryShareSave() {}

    private static final class Built {
        final WeakReference<Object> action;
        final WeakReference<Object> story;

        Built(Object action, Object story) {
            this.action = new WeakReference<>(action);
            this.story = new WeakReference<>(story);
        }
    }

    static boolean enabled() {
        return SettingsStatus.advancedDownloadsEnabled && Settings.SAVE_STORY_FROM_SHARE.get();
    }

    /**
     * Called as the share sheet's model finishes, with the package the sheet was built from and
     * its finished actions row. Hands back the row it was given unless the sheet is a story's
     * and the switch is on, and on any failure, since this runs inside TikTok's constructor.
     */
    public static List<?> actions(Object sharePackage, List<?> actions) {
        try {
            if (actions == null || !enabled()) return actions;
            Object story = Reflect.readField(sharePackage, "aweme");
            if (!isStory(story) || hasSave(actions) || shareSheetHidesSave()) return actions;
            Activity activity = Utils.getVisibleActivity();
            if (activity == null) return actions;
            Object action = newSaveAction(activity, story);
            if (action == null) return actions;
            remember(action, story);
            List<Object> row = new ArrayList<>(actions.size() + 1);
            row.add(action);
            row.addAll(actions);
            HookStatus.bound(StoryDownloads.HOOK_FAMILY, "share sheet save");
            return row;
        } catch (Throwable ex) {
            HookStatus.threw(StoryDownloads.HOOK_FAMILY, "share sheet save", ex);
            Logger.printException(() -> "Could not add the save button to the story's share sheet", ex);
            return actions;
        }
    }

    /** Called first thing in TikTok's save action's enable check: true for an action built here. */
    public static boolean owns(Object action) {
        if (action == null) return false;
        synchronized (BUILT) {
            for (Built built : BUILT) {
                if (built.action.get() == action) return true;
            }
        }
        return false;
    }

    /**
     * Called first thing in TikTok's save action's click, with the action and the sheet's context.
     * An action built here saves its story and TikTok's download never starts. Any other is
     * TikTok's own, and gets false.
     */
    public static boolean click(Object action, Context context) {
        if (!owns(action)) return false;
        try {
            StoryDownloads.saveStory(context, storyOf(action));
        } catch (Throwable ex) {
            HookStatus.threw(StoryDownloads.HOOK_FAMILY, "share sheet save", ex);
            Logger.printException(() -> "Could not save the story from its share sheet", ex);
        }
        return true;
    }

    /** The story an action built here was built for, or null. */
    static Object storyOf(Object action) {
        if (action == null) return null;
        synchronized (BUILT) {
            for (Built built : BUILT) {
                if (built.action.get() == action) return built.story.get();
            }
        }
        return null;
    }

    private static void remember(Object action, Object story) {
        synchronized (BUILT) {
            for (Iterator<Built> each = BUILT.iterator(); each.hasNext(); ) {
                if (each.next().action.get() == null) each.remove();
            }
            BUILT.add(new Built(action, story));
        }
    }

    /** How many built actions are still held, for the tests. */
    static int heldForTests() {
        synchronized (BUILT) {
            return BUILT.size();
        }
    }

    public static boolean isStory(Object value) {
        if (!(value instanceof Aweme)) return false;
        Aweme aweme = (Aweme) value;
        try {
            if (aweme.getIsTikTokStory()) return true;
            int type = aweme.getAwemeType();
            return type == STORY_TYPE || type == STORY_TYPE_SHARED;
        } catch (RuntimeException | LinkageError unreadable) {
            // A build that renamed either getter: the publish date's feed rows ask this too.
            return false;
        }
    }

    /** Whether the row has a save of TikTok's already, so the story's sheet never shows two. */
    static boolean hasSave(List<?> actions) {
        for (Object item : actions) {
            if (SAVE_KEY.equals(ShareActionCatalog.canonical(Reflect.string(item, "key", "key")))) return true;
        }
        return false;
    }

    /**
     * Whether Share sheet tools hides the actions row, or TikTok's save in it, on this sheet. The
     * button stands in for that save, so it stays out where the reader took the save out.
     */
    static boolean shareSheetHidesSave() {
        if (!SettingsStatus.shareSheetEnabled) return false;
        if (Settings.HIDE_SHARE_ACTIONS.get()) return true;
        for (String entry : ShareModelFilter.hiddenItems().split("[,\\n]")) {
            if (!entry.trim().isEmpty() && SAVE_KEY.equals(ShareActionCatalog.canonical(entry))) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------
    // TikTok's save action changes name with every build, so the patch writes its constructor
    // into this bridge's body. What it does without the patch is for the tests.

    /** TikTok's member as the tests stand it in. */
    interface Native {
        Object newSaveAction(Activity activity, Object story);
    }

    static volatile Native nativeForTests;

    /** A new TikTok video save action for the story, from the given activity. */
    static Object newSaveAction(Activity activity, Object story) {
        Native stand = nativeForTests;
        return stand == null ? null : stand.newSaveAction(activity, story);
    }
}
