package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.preference.SwitchPreference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.SharedPrefCategory;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.TikTokActivityHook;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;
import com.bytedance.ies.ugc.aweme.commercialize.compliance.personalization.AdPersonalizationActivity;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en-w480dp-h960dp-night-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@SuppressWarnings("deprecation")
public class CommentSearchSettingsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private SharedPreferences oldPreferences;
    private Field preferencesField;
    private boolean oldStatus, oldSearch, oldBlock;

    @Before public void prepareSettings() throws Exception {
        // Setting survives Robolectric's application store reset between test classes.
        oldPreferences = Setting.preferences.preferences;
        preferencesField = SharedPrefCategory.class.getDeclaredField("preferences");
        preferencesField.setAccessible(true);
        preferencesField.set(Setting.preferences,
                RuntimeEnvironment.getApplication().getSharedPreferences(Setting.preferences.name, 0));
        oldStatus = SettingsStatus.commentToolsEnabled;
        oldSearch = Settings.COMMENT_SEARCH.get();
        oldBlock = Settings.BLOCK_FROM_COMMENT.get();
        SettingsStatus.commentToolsEnabled = true;
        Setting.saveAll(Map.of(Settings.COMMENT_SEARCH, true, Settings.BLOCK_FROM_COMMENT, false));
        CommentSearch.setQuery("");
    }

    @After public void restoreSettings() throws Exception {
        try {
            CommentSearch.setQuery("");
            idle();
            Setting.saveAll(Map.of(Settings.COMMENT_SEARCH, oldSearch, Settings.BLOCK_FROM_COMMENT, oldBlock));
        } finally {
            SettingsStatus.commentToolsEnabled = oldStatus;
            preferencesField.set(Setting.preferences, oldPreferences);
        }
    }

    public static class SettingsActivity extends AdPersonalizationActivity {
        @Override public void onCreate(Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
            assertTrue(TikTokActivityHook.initialize(this));
        }
    }

    @Test public void theActualSwitchRestoresAndRedecoratesTheCachedCommentSheetWithoutRebinding() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout list = new LinearLayout(activity);
            list.setOrientation(LinearLayout.VERTICAL);
            column.addView(list);
            activity.setContentView(column);
            Comment laika = new Comment("one", "Laika in space");
            Comment recipe = new Comment("two", "Great recipe");
            View matching = row(list, 120);
            View body = row(list, 196);
            View replies = row(list, 64);
            View nativeReply = row(list, 0);
            View nativeGone = row(list, 0);
            nativeGone.setVisibility(View.GONE);
            View nativeInvisible = row(list, 73);
            nativeInvisible.setVisibility(View.INVISIBLE);
            CommentTools.registerCommentCell(matching, new CellState(laika));
            CommentTools.registerCommentCell(body, new CellState(recipe));
            CommentSearch.onReplyControlBound(replies, recipe, 0);
            CommentSearch.onReplyControlBound(nativeReply, recipe, 4);
            idle();
            EditText originalBox = box(column);
            assertNotNull("the native-shaped bindings did not install search", originalBox);
            originalBox.setText("laika");
            assertEquals(0, body.getLayoutParams().height);
            assertEquals(0, replies.getLayoutParams().height);

            // Android retains this view tree behind settings. No body or reply rebind follows.
            owner.pause().stop();
            assertTrue("the fixture detached the cached native list", list.isAttachedToWindow());
            toggleFromSettings(false);
            owner.restart().start().resume().visible();
            idle();
            EditText boxWhileOff = box(column);
            int[] heightsWhileOff = heights(body, replies, nativeReply, nativeGone, nativeInvisible, matching);
            int[] visibilityWhileOff = {body.getVisibility(), replies.getVisibility(),
                    nativeGone.getVisibility(), nativeInvisible.getVisibility()};
            String queryWhileOff = CommentSearch.query();

            // Exercise both real changes even on baseline, before checking the captured off state.
            owner.pause().stop();
            toggleFromSettings(true);
            owner.restart().start().resume().visible();
            idle();
            EditText enabledBox = box(column);
            String enabledText = enabledBox == null ? null : enabledBox.getText().toString();
            if (enabledBox != null) enabledBox.setText("laika");

            assertNull("the disabled setting left its search box on the cached sheet", boxWhileOff);
            assertArrayEquals("disabling search must restore only its owned row heights",
                    new int[]{196, 64, 0, 0, 73, 120}, heightsWhileOff);
            assertArrayEquals("disabling search changed native row visibility or kept its rows hidden",
                    new int[]{View.VISIBLE, View.VISIBLE, View.GONE, View.INVISIBLE}, visibilityWhileOff);
            assertEquals("the disabled setting kept the previous filter", "", queryWhileOff);
            assertNotNull("re-enabling search did not decorate the retained list", enabledBox);
            assertNotSame("the old search field was retained across off/on", originalBox, enabledBox);
            assertEquals("re-enabling search restored an old query", "", enabledText);
            assertEquals("the cached sheet received duplicate fields", 3, column.getChildCount());
            assertArrayEquals("the new field did not filter its retained body and reply rows",
                    new int[]{0, 0, 0, 120}, heights(body, replies, nativeReply, matching));
            TextView enabledStatus = column.findViewWithTag(CommentSearch.STATUS_TAG);
            assertNotNull(enabledStatus);
            assertEquals("1 result so far", enabledStatus.getText().toString());
            // The attach and detach listeners have to come back with the setting, because they
            // are what puts a row scrolled back into view into the right state. What they are
            // not is the count: a comment scrolling off screen is not a comment that stopped
            // matching, and the line used to say it was.
            list.removeView(matching);
            idle();
            assertEquals("a comment scrolled off screen was uncounted",
                    "1 result so far", enabledStatus.getText().toString());
            list.addView(matching);
            idle();
            assertEquals("a comment scrolled back was counted twice",
                    "1 result so far", enabledStatus.getText().toString());
            assertArrayEquals("the re-enabled search did not resume row-attach filtering",
                    new int[]{0, 0, 0, 120}, heights(body, replies, nativeReply, matching));
            enabledBox.setText("");
            assertArrayEquals(new int[]{196, 64, 0, 120}, heights(body, replies, nativeReply, matching));
        }
    }

    @Test public void aCachedListReattachRestoresSearchWithoutAnotherNativeBind() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout list = new LinearLayout(activity);
            list.setOrientation(LinearLayout.VERTICAL);
            column.addView(list);
            activity.setContentView(column);
            View row = row(list, 120);
            CommentTools.registerCommentCell(row, new CellState(new Comment("one", "Laika in space")));
            idle();

            EditText original = box(column);
            assertNotNull(original);
            original.setText("laika");
            TextView originalStatus = column.findViewWithTag(CommentSearch.STATUS_TAG);
            assertNotNull(originalStatus);
            assertEquals("1 result so far", originalStatus.getText().toString());

            column.removeView(list);
            idle();
            assertNull("the detached list left its search field behind", box(column));
            assertNull("the detached list left its result status behind",
                    column.findViewWithTag(CommentSearch.STATUS_TAG));

            column.addView(list);
            idle();
            EditText restored = box(column);
            assertNotNull("the cached list reattached without its search field", restored);
            assertNotSame(original, restored);
            assertEquals("a query from before detach followed the cached list", "",
                    restored.getText().toString());
            restored.setText("laika");
            TextView restoredStatus = column.findViewWithTag(CommentSearch.STATUS_TAG);
            assertNotNull("the cached list reattached without its result status", restoredStatus);
            assertEquals("1 result so far", restoredStatus.getText().toString());
        }
    }

    private static void toggleFromSettings(boolean expected) {
        Intent intent = new Intent("morphe_settings").putExtra("morphe", true)
                .putExtra("morphe_settings_section", "COMMENTS");
        try (var owner = Robolectric.buildActivity(SettingsActivity.class, intent).setup().visible()) {
            SettingsActivity activity = owner.get();
            settle(activity);
            TikTokPreferenceFragment page = (TikTokPreferenceFragment) activity.getFragmentManager()
                    .getFragments().stream().filter(fragment -> fragment instanceof TikTokPreferenceFragment)
                    .findFirst().orElseThrow(AssertionError::new);
            assertSame(Setting.preferences.preferences, page.getPreferenceManager().getSharedPreferences());
            ListView list = page.getView().findViewById(android.R.id.list);
            for (int index = 0; index < list.getCount(); index++) {
                Object item = list.getItemAtPosition(index);
                if (!(item instanceof Preference)
                        || !Settings.COMMENT_SEARCH.key.equals(((Preference) item).getKey())) continue;
                SwitchPreference setting = (SwitchPreference) item;
                assertEquals(!expected, setting.isChecked());
                list.setSelection(index);
                settle(activity);
                View view = list.getChildAt(index - list.getFirstVisiblePosition());
                assertNotNull("the actual Comments switch was not laid out", view);
                assertTrue(list.performItemClick(view, index, list.getItemIdAtPosition(index)));
                settle(activity);
                assertEquals(expected, setting.isChecked());
                assertEquals(expected, Settings.COMMENT_SEARCH.get());
                assertEquals(expected, Setting.preferences.preferences.getBoolean(Settings.COMMENT_SEARCH.key, false));
                return;
            }
            fail("the actual Comments page has no search switch");
        }
    }

    private static View row(LinearLayout list, int height) {
        View view = new View(list.getContext());
        list.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        return view;
    }

    private static EditText box(LinearLayout column) {
        for (int index = 0; index < column.getChildCount(); index++) {
            if (column.getChildAt(index) instanceof EditText) return (EditText) column.getChildAt(index);
        }
        return null;
    }

    private static int[] heights(View... rows) {
        int[] heights = new int[rows.length];
        for (int index = 0; index < rows.length; index++) heights[index] = rows[index].getLayoutParams().height;
        return heights;
    }

    private static void settle(Activity activity) {
        activity.getFragmentManager().executePendingTransactions();
        idle();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 480, 960);
        idle();
    }

    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }

    public static final class CellState {
        public final Comment comment;
        CellState(Comment comment) { this.comment = comment; }
    }

    public static final class Comment {
        private final String cid, text;
        Comment(String cid, String text) { this.cid = cid; this.text = text; }
        public String getCid() { return cid; }
        public String getText() { return text; }
        public Object getUser() { return null; }
    }
}
