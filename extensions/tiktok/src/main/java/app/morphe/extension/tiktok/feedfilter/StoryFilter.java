/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/feedfilter/StoryFilter.java
 */
package app.morphe.extension.tiktok.feedfilter;

import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;

public class StoryFilter implements IFilter {
    @Override
    public boolean getEnabled() {
        return Settings.HIDE_STORY.get();
    }

    @Override
    public boolean getFiltered(Aweme item) {
        return item.getIsTikTokStory();
    }
}

