/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** A place badge is an explicit content preference, not proof that a post is a paid ad. */
public final class LocationBadgeFilter implements IFilter {
    private static final String FAMILY = "location badges";
    /** The export's route for the strips above a caption, one list per read of a video's strips. */
    static final String STRIP_SOURCE = "CaptionStrip";
    static final String PLACE_REASON = "placeLabel";
    static final String TOOL_REASON = "creationTag";
    /**
     * Strips that ask the viewer to make something with a tool: an effect, a template, an editing
     * app (CapCut and its siblings) or an AI style (#51). Every key is one TikTok 47.1.4's own code
     * names; anchor_effect, anchor_ucg_template, anchor_pugc_template and anchor_capcut were seen
     * served on 47.0.3 and 47.1.4. Films, dramas, places and other apps' promotions
     * (Lemon8, the photo app) stay, so a tag that links to what the video is about keeps working.
     */
    static final Set<String> CREATION_TOOL_KEYS = Set.of(
            "anchor_effect", "anchor_edit_effect", "anchor_voice_filter_anchor", "anchor_tts_voice",
            "anchor_template", "anchor_ucg_template", "anchor_pugc_template", "anchor_ugc_photo_template",
            "anchor_sgt_template", "anchor_aigt_template", "anchor_tt_capcut_template",
            "anchor_capcut", "anchor_hypic", "anchor_editor_pro", "anchor_auto_cut",
            "anchor_ai_style", "anchor_ai_group_shot", "anchor_ai_portrait", "anchor_ai_remix",
            "anchor_aigc_avatar", "anchor_text_to_image");
    private static final FeedServedKinds STRIP_KINDS = new FeedServedKinds("Caption strip");

    @Override public boolean getEnabled() { return Settings.FILTER_LOCATION_VIDEOS.get(); }

    @Override public boolean getFiltered(Aweme item) { return hasBadge(item); }

    public static boolean hasBadge(Aweme item) {
        Object value = Reflect.required(item, "getAnchors", FAMILY);
        if (!(value instanceof List<?>)) return false;
        try {
            for (Object anchor : (List<?>) value) if (isLocation(anchor)) return true;
        } catch (RuntimeException error) {
            HookStatus.threw(FAMILY, "read anchors", error);
        }
        return false;
    }

    private static boolean isLocation(Object anchor) {
        return isLocationKey(Reflect.required(anchor, "getComponentKey", FAMILY));
    }

    private static boolean isLocationKey(Object key) {
        // This is TikTok's component key, not a caption, place name, locale or broad POI field.
        return "anchor_poi".equals(key);
    }

    /** Called only by the native badge renderer. Never edits Aweme.getAnchors() or its elements. */
    public static List<?> visibleAnchors(List<?> anchors) {
        HookStatus.bound(FAMILY, "native badge list");
        countStrips(anchors);
        boolean places = Settings.HIDE_LOCATION_LABELS.get();
        boolean tools = Settings.HIDE_CREATION_TAGS.get();
        if (!places && !tools || anchors == null || anchors.isEmpty()) return anchors;
        try {
            ArrayList<Object> kept = null;
            int placesHidden = 0;
            int toolsHidden = 0;
            for (int i = 0; i < anchors.size(); i++) {
                Object anchor = anchors.get(i);
                Object key = Reflect.required(anchor, "getComponentKey", FAMILY);
                boolean place = places && isLocationKey(key);
                boolean tool = tools && !place && key instanceof String && CREATION_TOOL_KEYS.contains(key);
                if (place || tool) {
                    if (kept == null) kept = new ArrayList<>(anchors.subList(0, i));
                    if (place) placesHidden++;
                    else toolsHidden++;
                } else if (kept != null) {
                    kept.add(anchor);
                }
            }
            if (kept == null) return anchors;
            if (placesHidden > 0) {
                HookStatus.bound(FAMILY, "location entries hidden");
                FeedFilterCounters.removed(STRIP_SOURCE, placesHidden, PLACE_REASON);
            }
            if (toolsHidden > 0) {
                HookStatus.bound(FAMILY, "creation tags hidden");
                FeedFilterCounters.removed(STRIP_SOURCE, toolsHidden, TOOL_REASON);
            }
            return kept;
        } catch (RuntimeException error) {
            HookStatus.threw(FAMILY, "badge list", error);
            Logger.printException(() -> "Could not hide caption strips", error);
            return anchors;
        }
    }

    /**
     * Counts the strips the renderer is handed, by kind, whatever the logging switch and the
     * place switches say, so an export says which strips a phone is served. Strips that promote
     * something else (a Lemon8 link, a "Find ..." search) reach some phones and not others, so
     * their keys have to come from the phones that get them.
     */
    private static void countStrips(List<?> anchors) {
        if (anchors == null) return;
        try {
            FeedFilterCounters.sawList(STRIP_SOURCE, anchors.size());
            for (Object anchor : anchors) {
                if (anchor == null) continue;
                String kind = FeedServedKinds.kind(optional(anchor, "getComponentKey"), optional(anchor, "getType"));
                if (kind == null) continue;
                FeedFilterCounters.sawKind(STRIP_SOURCE, kind);
                STRIP_KINDS.note(kind);
            }
        } catch (RuntimeException error) {
            HookStatus.threw(FAMILY, "count strip kinds", error);
        }
    }

    /** A no-argument read that is only diagnostic, so a build without it is no hook failure. */
    private static Object optional(Object target, String name) {
        Method method = Reflect.method(target.getClass(), name);
        if (method == null) return null;
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException error) {
            return null;
        }
    }

    /** Forgets the strip kinds already named, between deterministic runtime tests. */
    static void resetStripKindsForTests() {
        STRIP_KINDS.resetForTests();
    }
}
