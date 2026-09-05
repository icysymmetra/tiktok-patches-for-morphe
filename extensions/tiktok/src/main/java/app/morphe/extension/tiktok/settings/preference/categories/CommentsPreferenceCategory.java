package app.morphe.extension.tiktok.settings.preference.categories;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.NumberInputPreference;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;

@SuppressWarnings("deprecation")
public class CommentsPreferenceCategory extends ConditionalPreferenceCategory {
    public CommentsPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Comments and translation");
    }

    @Override
    public boolean getSettingsStatus() {
        return SettingsStatus.commentTranslationEnabled
                || SettingsStatus.hideCommentQuickReactionsEnabled
                || SettingsStatus.copyCommentsWithoutUsernameEnabled
                || SettingsStatus.foldableSplitViewEnabled;
    }

    @Override
    public void addPreferences(Context context) {
        if (SettingsStatus.commentTranslationEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Auto translate comments",
                    "Automatically translates loaded comment batches using TikTok's translation system.",
                    Settings.COMMENT_BATCH_TRANSLATION
            ));
        }
        if (SettingsStatus.hideCommentQuickReactionsEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide quick comment reactions",
                    "Hide TikTok's exposed quick emoji row in supported comment inputs.",
                    Settings.HIDE_COMMENT_QUICK_REACTIONS
            ));
        }
        if (SettingsStatus.copyCommentsWithoutUsernameEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Copy comments without username",
                    "Copy only the comment text when using TikTok's copy comment action.",
                    Settings.COPY_COMMENTS_WITHOUT_USERNAME
            ));
        }
        if (SettingsStatus.foldableSplitViewEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Force split video/comment view",
                    "Show comments beside the video instead of as a bottom sheet once the screen is at least "
                            + "as wide as the threshold below. Intended for foldables TikTok doesn't already "
                            + "treat as tablet-class. Requires restart.",
                    Settings.FOLDABLE_SPLIT_VIEW
            ));
            addPreference(new NumberInputPreference(
                    context,
                    "Split view width threshold (dp)",
                    "Minimum screen width, in dp, before the split view is forced. Export a diagnostic report "
                            + "after opening a video's comments to see your device's measured width. "
                            + "Requires restart.",
                    Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP,
                    200,
                    1200
            ));
        }
    }
}
