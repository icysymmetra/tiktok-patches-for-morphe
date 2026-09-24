package app.morphe.extension.tiktok.feedfilter;

import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import com.ss.android.ugc.aweme.feed.model.Aweme;

public final class AlternateForYouBatchFilter implements IFilter {
    @Override
    public boolean getEnabled() {
        return SettingsStatus.hideFypSlopEnabled
            && Settings.HIDE_ALTERNATE_FOR_YOU_BATCHES.get();
    }

    @Override
    public boolean getFiltered(Aweme item) {
        return "for_you_page_999".equals(item.getItemDistributeSource())
            && item.getRecReasonsStruct() == null;
    }
}
