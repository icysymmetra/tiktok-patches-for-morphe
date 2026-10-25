/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/feedfilter/IFilter.java
 */
package app.morphe.extension.tiktok.feedfilter;

import com.ss.android.ugc.aweme.feed.model.Aweme;

public interface IFilter {
    boolean getEnabled();
    boolean getFiltered(Aweme item);
}

