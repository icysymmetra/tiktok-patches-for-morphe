package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.*;
import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.lang.reflect.Method;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class InboxRowLanguageTest {
    private BooleanSetting[] settings;
    private Object[] models;
    private Method predicate;

    @Before public void setup() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SettingsStatus.inboxFilterEnabled = true;
        settings = new BooleanSetting[]{Settings.HIDE_INBOX_NEW_FOLLOWERS,
                Settings.HIDE_INBOX_ACTIVITY, Settings.HIDE_INBOX_ARCHIVE,
                Settings.HIDE_INBOX_TAKO, Settings.HIDE_INBOX_SHOP};
        models = new Object[]{new Pod(Kind.FOLLOWER), new Pod(Kind.ACTIVITY),
                new Archive(), new Entrance(), new Pod(Kind.SHOP)};
        for (BooleanSetting setting : settings) setting.save(false);
        Settings.HIDE_INBOX_CONVERSATIONS.save(false);
        Settings.HIDE_INBOX_MESSAGE_REQUESTS.save(false);
        Settings.HIDE_INBOX_CUSTOM_TITLES.save("");
        String packageName = RuntimeEnvironment.getApplication().getPackageName();
        InboxFilter.resolveForTests(packageName, "user_name", 102);
        InboxFilter.resolveForTests(packageName, "47.4.3:uyv", 103);
        InboxFilter.resolveForTests(packageName, "47.4.3:brh", 104);
        InboxFilter.resolveForTests(packageName, "47.4.3:w24", 105);
        InboxFilter.resolveForTests(packageName, "47.4.3:l92", 200);
        FeedVisibility.resolveForTests(packageName, "47.4.3:oni", 201);
        predicate = InboxFilter.class.getDeclaredMethod("shouldHideRow", Activity.class, View.class);
        predicate.setAccessible(true);
    }

    @After public void tearDown() {
        SettingsStatus.inboxFilterEnabled = false;
    }

    @Test public void everyCategoryUsesModelIdentityAcrossLanguagesAndIndependentSwitches() throws Exception {
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = owner.get();
            String[][] labels = {{"Neue Follower", "Aktivität", "Archiv", "TikTok Tako", "TikTok Shop"},
                    {"新しいフォロワー", "アクティビティ", "アーカイブ", "Tako", "ショップ"}};
            for (String[] language : labels) {
                for (int enabled = 0; enabled < settings.length; enabled++) {
                    for (int i = 0; i < settings.length; i++) settings[i].save(i == enabled);
                    for (int rowIndex = 0; rowIndex < models.length; rowIndex++) {
                        View row = row(activity, language[rowIndex], false);
                        InboxFilter.onRowBound(new Holder(row), rowIndex, models[rowIndex]);
                        assertEquals(rowIndex == enabled ? View.GONE : View.VISIBLE, row.getVisibility());
                        assertEquals(rowIndex == enabled, predicate.invoke(null, activity, row));
                    }
                }
            }
        }
    }

    @Test public void recycledRowsRestoreHeightAndNeverHideSameNamedConversations() throws Exception {
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = owner.get();
            Settings.HIDE_INBOX_ARCHIVE.save(true);
            View row = row(activity, "Archiv", false);
            Holder holder = new Holder(row);
            InboxFilter.onRowBound(holder, 0, new Archive());
            assertEquals(0, row.getLayoutParams().height);
            InboxFilter.onRowBound(holder, 1, new Object());
            assertEquals(View.VISIBLE, row.getVisibility());
            assertEquals(72, row.getLayoutParams().height);
            assertEquals(false, predicate.invoke(null, activity, row));
            for (BooleanSetting setting : settings) setting.save(true);
            for (String name : new String[]{"Archiv", "Activity", "TikTok Shop", "新しいフォロワー"}) {
                View conversation = row(activity, name, true);
                InboxFilter.onRowBound(new Holder(conversation), 0, new Object());
                assertEquals(View.VISIBLE, conversation.getVisibility());
                assertEquals(false, predicate.invoke(null, activity, conversation));
            }
        }
    }

    @Test public void nextLayoutRestoresAllCategoriesWithoutRebindingOrRebuilding() throws Exception {
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = owner.get();
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(android.graphics.Color.BLACK);
            TextView tab = new TextView(activity);
            tab.setText("Inbox");
            tab.setTextColor(android.graphics.Color.WHITE);
            tab.setTextSize(24);
            tab.setId(201);
            tab.setSelected(true);
            root.addView(tab);
            LinearLayout rows = new LinearLayout(activity);
            rows.setOrientation(LinearLayout.VERTICAL);
            rows.setId(200);
            root.addView(rows);
            activity.setContentView(root);
            String[] labels = {"Neue Follower", "Aktivität", "Archiv", "TikTok Tako", "TikTok Shop"};
            for (int i = 0; i < settings.length; i++) {
                settings[i].save(true);
                View row = row(activity, labels[i], false);
                rows.addView(row);
                InboxFilter.onRowBound(new Holder(row), i, models[i]);
            }
            InboxFilter.install(activity);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            var observer = activity.findViewById(android.R.id.content).getViewTreeObserver();
            observer.dispatchOnGlobalLayout();
            for (int i = 0; i < rows.getChildCount(); i++) {
                assertEquals(View.GONE, rows.getChildAt(i).getVisibility());
                assertEquals(0, rows.getChildAt(i).getLayoutParams().height);
            }
            app.morphe.extension.tiktok.UiCapture.save(root, "inbox-categories-hidden.png");
            for (BooleanSetting setting : settings) setting.save(false);
            observer.dispatchOnGlobalLayout();
            assertEquals(5, rows.getChildCount());
            for (int i = 0; i < rows.getChildCount(); i++) {
                assertEquals(View.VISIBLE, rows.getChildAt(i).getVisibility());
                assertEquals(72, rows.getChildAt(i).getLayoutParams().height);
            }
            app.morphe.extension.tiktok.UiCapture.save(root, "inbox-categories-restored.png");
            Settings.HIDE_INBOX_ARCHIVE.save(true);
            observer.dispatchOnGlobalLayout();
            for (int i = 0; i < rows.getChildCount(); i++) {
                assertEquals(i == 2 ? View.GONE : View.VISIBLE, rows.getChildAt(i).getVisibility());
            }
        }
    }

    @Test public void archiveIdentityPreservesUnknownModels() {
        Object archive = new Archive(), conversation = new Object();
        assertSame(Settings.HIDE_INBOX_ARCHIVE, InboxModelFilter.settingFor(archive));
        assertFalse(InboxModelFilter.settingFor(archive).get());
        Settings.HIDE_INBOX_ARCHIVE.save(true);
        assertTrue(InboxModelFilter.settingFor(archive).get());
        assertNull(InboxModelFilter.settingFor(conversation));
    }

    private View row(Activity activity, String title, boolean conversation) {
        LinearLayout row = new LinearLayout(activity);
        row.setLayoutParams(new ViewGroup.LayoutParams(-1, 72));
        row.setId(conversation ? 105 : 103);
        TextView label = new TextView(activity);
        label.setId(conversation ? 102 : 104);
        label.setText(title);
        label.setTextColor(android.graphics.Color.WHITE);
        row.addView(label);
        return row;
    }

    private enum Kind { FOLLOWER, ACTIVITY, SHOP }
    private static final class Pod {
        public final Kind dataType;
        Pod(Kind kind) { dataType = kind; }
    }
    private static final class Archive { public String itemUniqueId() { return "archive_entrance"; } }
    private static final class Entrance { public final Cell entranceCell = new Cell(); }
    private static final class Cell { public boolean isTako() { return true; } }
    private static final class Holder {
        public final View itemView;
        Holder(View row) { itemView = row; }
    }
}
