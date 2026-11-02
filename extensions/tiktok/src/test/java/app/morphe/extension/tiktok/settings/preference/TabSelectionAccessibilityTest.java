/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CheckBox;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The check box in each row is not clickable and the row does the toggling, so everything a
 * screen reader knows about the state has to be put on the row by hand.
 */
@RunWith(RobolectricTestRunner.class)
// getAccessibilityDelegate() is public from API 29, which is how the row is asked what it
// would tell a screen reader.
@Config(sdk = 29)
@SuppressWarnings("deprecation")
public class TabSelectionAccessibilityTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    public static class Host extends android.preference.PreferenceActivity {}

    @Test public void resetToLoadedTicksTheRowsRatherThanSavingAndClosing() throws Exception {
        // It sat left of Cancel and Save and saved immediately, bypassing Save, so a reader who
        // pressed it to see what it did lost the selection they came in with, with no undo. It
        // stages now: the ticks move and nothing is written until Save.
        try (var owner = Robolectric.buildActivity(Host.class).setup()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TabSelectionPreference preference =
                    new TabSelectionPreference(activity, Settings.BOTTOM_NAVIGATION_TABS, true);

            Class<?> rowClass = Class.forName(
                    "app.morphe.extension.tiktok.settings.preference.TabSelectionPreference$OptionRow");
            Constructor<?> make = rowClass.getDeclaredConstructors()[0];
            make.setAccessible(true);
            Object option = newOption(make);

            Set<String> selected = new LinkedHashSet<>();
            Method create = TabSelectionPreference.class.getDeclaredMethod(
                    "createOptionRow", android.content.Context.class, Set.class, rowClass);
            create.setAccessible(true);
            android.widget.LinearLayout container = new android.widget.LinearLayout(activity);
            container.addView((View) create.invoke(preference, activity, selected, option));

            var optionsView = TabSelectionPreference.class.getDeclaredField("optionsView");
            optionsView.setAccessible(true);
            optionsView.set(preference, container);
            var selectedKeys = TabSelectionPreference.class.getDeclaredField("selectedKeys");
            selectedKeys.setAccessible(true);
            selectedKeys.set(preference, selected);

            CheckBox box = firstCheckBox(container);
            assertFalse("the row started ticked", box.isChecked());

            // What the button does: fill the set, then put the rows in step with it.
            selected.add(optionKey(option));
            Method refresh = TabSelectionPreference.class.getDeclaredMethod("refreshRowChecks");
            refresh.setAccessible(true);
            refresh.invoke(preference);

            assertTrue("the rows were not put in step with the selection", box.isChecked());
            assertEquals("the picker saved before anyone pressed Save",
                    Settings.BOTTOM_NAVIGATION_TABS.defaultValue,
                    Settings.BOTTOM_NAVIGATION_TABS.get());
        }
    }

    private static String optionKey(Object option) throws Exception {
        var field = option.getClass().getDeclaredField("key");
        field.setAccessible(true);
        return (String) field.get(option);
    }

    private static CheckBox firstCheckBox(View view) {
        if (view instanceof CheckBox) return (CheckBox) view;
        if (view instanceof android.view.ViewGroup) {
            var group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                CheckBox found = firstCheckBox(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void aTabRowSaysWhetherItIsOnAndSaysSoAgainAfterATap() throws Exception {
        try (var owner = Robolectric.buildActivity(Host.class).setup()) {
            Activity activity = owner.get();
            Utils.setContext(activity);

            TabSelectionPreference preference =
                    new TabSelectionPreference(activity, Settings.BOTTOM_NAVIGATION_TABS, true);

            Class<?> rowClass = Class.forName(
                    "app.morphe.extension.tiktok.settings.preference.TabSelectionPreference$OptionRow");
            Constructor<?> make = rowClass.getDeclaredConstructors()[0];
            make.setAccessible(true);
            Object option = newOption(make);

            Set<String> selected = new LinkedHashSet<>();
            Method create = TabSelectionPreference.class.getDeclaredMethod(
                    "createOptionRow", android.content.Context.class, Set.class, rowClass);
            create.setAccessible(true);
            // The method returns a wrapper holding the row and its divider. The row is the one
            // that takes the tap and the one a screen reader lands on.
            View wrapper = (View) create.invoke(preference, activity, selected, option);
            View row = ((android.view.ViewGroup) wrapper).getChildAt(0);

            AccessibilityNodeInfo before = describe(row);
            assertEquals("the row is not announced as something with two states",
                    CheckBox.class.getName(), String.valueOf(before.getClassName()));
            assertTrue("the row is not announced as checkable", before.isCheckable());
            assertFalse("the row claims to be on before anything was chosen", before.isChecked());

            row.performClick();

            AccessibilityNodeInfo after = describe(row);
            assertTrue("choosing a tab did not change what the row announces", after.isChecked());
        }
    }

    /**
     * What a screen reader would be handed for this row. The delegate is asked directly because
     * the node a detached view builds under Robolectric does not go through it.
     */
    private static AccessibilityNodeInfo describe(View row) {
        View.AccessibilityDelegate delegate = row.getAccessibilityDelegate();
        assertTrue("the row was given no accessibility delegate at all", delegate != null);
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        delegate.onInitializeAccessibilityNodeInfo(row, info);
        return info;
    }

    /** The row record's shape belongs to the preference, so it is built through its constructor. */
    private static Object newOption(Constructor<?> make) throws Exception {
        Class<?>[] types = make.getParameterTypes();
        Object[] values = new Object[types.length];
        for (int at = 0; at < types.length; at++) {
            if (types[at] == String.class) values[at] = at == 0 ? "profile" : "Profile";
            else if (types[at] == boolean.class) values[at] = Boolean.FALSE;
            else if (types[at] == int.class) values[at] = 0;
            else values[at] = null;
        }
        make.setAccessible(true);
        return make.newInstance(values);
    }
}
