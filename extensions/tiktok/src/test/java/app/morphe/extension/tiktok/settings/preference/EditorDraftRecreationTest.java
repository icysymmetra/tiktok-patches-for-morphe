/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcel;
import android.preference.DialogPreference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SettingsRegistryRule;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.function.Supplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/**
 * A rotation, resize or dark mode switch rebuilds the settings screen while an editor's dialog is
 * open. The editor's unsaved picks, list and typing come back in the rebuilt dialog, Cancel still
 * leaves the saved settings as they were, and Save writes what was restored.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class EditorDraftRecreationTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    // The range editor case builds a StringSetting of its own; this takes it back out.
    @Rule public final SettingsRegistryRule settingsRegistry = new SettingsRegistryRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    private ActivityController<TestActivity> owner;
    private TestActivity activity;

    @Before public void setUp() {
        owner = Robolectric.buildActivity(TestActivity.class).setup().visible();
        activity = owner.get();
        Utils.setContext(activity);
    }

    @After public void tearDown() {
        Settings.SHARE_ACTION_CATALOG.save("");
        Settings.SHARE_HIDDEN_ITEMS.save("");
        Settings.SHARE_ADDED_APPS.resetToDefault();
        Settings.PROFILE_SHORTCUT_CATALOG.save("");
        Settings.PROFILE_SHORTCUT_PICKS.save("");
        Settings.POPUP_LABEL_CATALOG.save("");
        Settings.POPUP_LABEL_PICKS.save("");
        Settings.LOCAL_HIDDEN_CREATORS.resetToDefault();
        owner.pause().stop().destroy();
    }

    /** Puts the editor on a screen of its own, as the settings page does, and opens its dialog. */
    private <P extends DialogPreference> AlertDialog open(P editor) {
        attach(editor);
        ReflectionHelpers.callInstanceMethod(editor, "onClick");
        idle();
        AlertDialog dialog = (AlertDialog) editor.getDialog();
        assertNotNull(dialog);
        return dialog;
    }

    private void attach(DialogPreference editor) {
        PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
        screen.addPreference(editor);
    }

    /**
     * What a recreated settings screen does: the open editor's state is saved and goes through a
     * parcel, the old dialog goes away, and a new editor on a new screen restores it.
     */
    private <P extends DialogPreference> AlertDialog recreate(P before, P after) {
        Bundle saved = new Bundle();
        before.saveHierarchyState(saved);
        before.getDialog().dismiss();
        idle();
        attach(after);
        assertNotSame(before, after);
        after.restoreHierarchyState(parcelled(saved));
        idle();
        AlertDialog reopened = (AlertDialog) after.getDialog();
        assertNotNull("the dialog wasn't reopened", reopened);
        assertTrue(reopened.isShowing());
        return reopened;
    }

    private static Bundle parcelled(Bundle state) {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(state);
            parcel.setDataPosition(0);
            return parcel.readBundle(EditorDraftRecreationTest.class.getClassLoader());
        } finally {
            parcel.recycle();
        }
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void settle() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        idle();
    }

    private static <V extends View> V find(AlertDialog dialog, String tag) {
        return dialog.getWindow().getDecorView().findViewWithTag(tag);
    }

    private static void cancel(AlertDialog dialog) {
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idle();
    }

    private static void save(AlertDialog dialog) throws Exception {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle();
    }

    @Test public void shareActionPicksAndSearchComeBackAndOnlySaveWritesThem() throws Exception {
        Settings.SHARE_ACTION_CATALOG.save("copy\tCopy link\nqr code\tQR code");
        Settings.SHARE_HIDDEN_ITEMS.save("copy");
        Supplier<ShareActionChecklistPreference> make = () -> new ShareActionChecklistPreference(activity);

        ShareActionChecklistPreference first = make.get();
        AlertDialog dialog = open(first);
        ((CheckBox) find(dialog, "share_action_copy")).performClick();
        ((CheckBox) find(dialog, "share_action_qr code")).performClick();
        ((EditText) find(dialog, "share_action_search")).setText("qr");

        AlertDialog reopened = recreate(first, make.get());
        assertEquals("the search was lost", "qr", ((EditText) find(reopened, "share_action_search")).getText().toString());
        assertTrue("the new pick was lost", ((CheckBox) find(reopened, "share_action_qr code")).isChecked());
        assertNull("the search no longer filters the rows", find(reopened, "share_action_copy"));
        cancel(reopened);
        assertEquals("Cancel saved the draft", "copy", Settings.SHARE_HIDDEN_ITEMS.savedValue());

        ShareActionChecklistPreference second = make.get();
        dialog = open(second);
        ((CheckBox) find(dialog, "share_action_copy")).performClick();
        ((CheckBox) find(dialog, "share_action_qr code")).performClick();
        reopened = recreate(second, make.get());
        save(reopened);
        assertEquals("qr code", Settings.SHARE_HIDDEN_ITEMS.savedValue());
    }

    @Test public void shareAppPicksAndSearchComeBackOnceTheAppsArrive() throws Exception {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain");
        for (String app : new String[]{"com.example.notes", "com.example.mail"}) {
            ResolveInfo info = new ResolveInfo();
            info.activityInfo = new ActivityInfo();
            info.activityInfo.packageName = app;
            info.activityInfo.name = app + ".Share";
            Shadows.shadowOf(activity.getPackageManager()).addResolveInfoForIntent(send, info);
        }
        Settings.SHARE_ADDED_APPS.save("");

        ShareAppPickerPreference first = new ShareAppPickerPreference(activity);
        AlertDialog dialog = open(first);
        settle();
        ((CheckBox) find(dialog, "share_app_com.example.notes")).performClick();
        ((EditText) find(dialog, "share_app_search")).setText("notes");

        AlertDialog reopened = recreate(first, new ShareAppPickerPreference(activity));
        settle();
        assertEquals("notes", ((EditText) find(reopened, "share_app_search")).getText().toString());
        assertTrue("the pick was lost when the apps came back",
                ((CheckBox) find(reopened, "share_app_com.example.notes")).isChecked());
        assertNull(find(reopened, "share_app_com.example.mail"));
        save(reopened);
        assertEquals("com.example.notes", Settings.SHARE_ADDED_APPS.savedValue());
    }

    @Test public void profileShortcutPicksComeBack() throws Exception {
        Settings.PROFILE_SHORTCUT_CATALOG.save("creator tools\tTikTok Studio\nfeature 21\tYour orders");
        Settings.PROFILE_SHORTCUT_PICKS.save("creator tools");

        ProfileShortcutChecklistPreference first = new ProfileShortcutChecklistPreference(activity);
        AlertDialog dialog = open(first);
        ((CheckBox) find(dialog, "profile_shortcut_creator tools")).performClick();
        ((CheckBox) find(dialog, "profile_shortcut_feature 21")).performClick();

        AlertDialog reopened = recreate(first, new ProfileShortcutChecklistPreference(activity));
        assertFalse(((CheckBox) find(reopened, "profile_shortcut_creator tools")).isChecked());
        assertTrue(((CheckBox) find(reopened, "profile_shortcut_feature 21")).isChecked());
        cancel(reopened);
        assertEquals("creator tools", Settings.PROFILE_SHORTCUT_PICKS.savedValue());
    }

    @Test public void popupLabelPicksComeBack() throws Exception {
        Settings.POPUP_LABEL_CATALOG.save("Follow friends\nUpsell sheet");
        Settings.POPUP_LABEL_PICKS.save("follow friends");

        PopupLabelChecklistPreference first = new PopupLabelChecklistPreference(activity);
        AlertDialog dialog = open(first);
        ((CheckBox) find(dialog, "popup_label_follow friends")).performClick();
        ((CheckBox) find(dialog, "popup_label_upsell sheet")).performClick();

        AlertDialog reopened = recreate(first, new PopupLabelChecklistPreference(activity));
        assertFalse(((CheckBox) find(reopened, "popup_label_follow friends")).isChecked());
        assertTrue(((CheckBox) find(reopened, "popup_label_upsell sheet")).isChecked());
        save(reopened);
        assertEquals("upsell sheet", Settings.POPUP_LABEL_PICKS.savedValue());
    }

    @Test public void hiddenCreatorListFilterAndTypedHandleComeBack() throws Exception {
        Settings.LOCAL_HIDDEN_CREATORS.save("alice");
        Supplier<CreatorListPreference> make = () -> new CreatorListPreference(activity,
                "Locally hidden creators", "", Settings.LOCAL_HIDDEN_CREATORS);

        CreatorListPreference first = make.get();
        AlertDialog dialog = open(first);
        EditText add = find(dialog, "creator_list_add");
        add.setText("bob");
        ((View) find(dialog, "creator_list_add_button")).performClick();
        add.setText("carol");
        ((EditText) find(dialog, "creator_list_search")).setText("bo");

        CreatorListPreference second = make.get();
        AlertDialog reopened = recreate(first, second);
        assertEquals("bo", ((EditText) find(reopened, "creator_list_search")).getText().toString());
        assertEquals("the typed handle was lost", "carol",
                ((EditText) find(reopened, "creator_list_add")).getText().toString());
        assertNotNull("the added creator was lost", find(reopened, "creator_remove_bob"));
        assertNull("the filter no longer applies", find(reopened, "creator_remove_alice"));
        cancel(reopened);
        assertEquals("Cancel saved the draft", "alice", second.getValue());
    }

    @Test public void typedRangeComesBack() throws Exception {
        StringSetting setting = new StringSetting("range_draft_test_views", "0-" + Long.MAX_VALUE);
        Supplier<RangeValuePreference> make = () -> new RangeValuePreference(activity, "Views", "Summary", setting);

        RangeValuePreference first = make.get();
        open(first);
        EditText min = ReflectionHelpers.getField(first, "minField");
        EditText max = ReflectionHelpers.getField(first, "maxField");
        min.setText("20K");
        max.setText("1.5M");

        RangeValuePreference second = make.get();
        AlertDialog reopened = recreate(first, second);
        assertEquals("20K", ((EditText) ReflectionHelpers.getField(second, "minField")).getText().toString());
        assertEquals("1.5M", ((EditText) ReflectionHelpers.getField(second, "maxField")).getText().toString());
        save(reopened);
        assertEquals("20000-1500000", second.getValue());
    }
}
