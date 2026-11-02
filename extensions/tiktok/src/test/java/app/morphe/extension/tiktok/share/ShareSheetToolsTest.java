package app.morphe.extension.tiktok.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.shared.GlobalLayoutHook;
import app.morphe.extension.shared.ResourceIdCache;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ShareSheetToolsTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        ReflectionHelpers.setStaticField(Setting.class, "pausedForProcess", false);
        ShareModelFilter.surface(null);
        Settings.HIDE_SHARE_CONTACTS.save(false);
        Settings.HIDE_SHARE_ACTIONS.save(false);
        Settings.HIDE_SHARE_CHANNELS.save(false);
        ReflectionHelpers.setStaticField(ShareSheetTools.class, "windowGlobal", null);
        ReflectionHelpers.setStaticField(ShareSheetTools.class, "windowViewsReader", null);
        ReflectionHelpers.setStaticField(ShareSheetTools.class, "windowViewsUnavailable", false);
        ReflectionHelpers.setStaticField(ShareSheetTools.class, "applyPosted", false);
        Object cache = ReflectionHelpers.getStaticField(ShareSheetTools.class, "RESOURCE_IDS");
        ((ResourceIdCache) cache).clear();
        Map<String, Integer> ids = ReflectionHelpers.getField(cache, "ids");
        ids.put(context.getPackageName() + ":47.4.3:iq_", 0x7f000201);
        ids.put(context.getPackageName() + ":ibc", 0x7f000101);
        ids.put(context.getPackageName() + ":47.4.3:v47", 0x7f000301);
        ids.put(context.getPackageName() + ":47.4.3:a5x", 0x7f000401);
    }

    @After public void tearDown() {
        ReflectionHelpers.setStaticField(Setting.class, "pausedForProcess", false);
        Settings.HIDE_SHARE_CONTACTS.resetToDefault();
        Settings.HIDE_SHARE_ACTIONS.resetToDefault();
        Settings.HIDE_SHARE_CHANNELS.resetToDefault();
        Settings.SHARE_HIDDEN_ITEMS.resetToDefault();
        Settings.SHARE_HIDDEN_ITEMS_PROFILE.resetToDefault();
        Settings.SHARE_HIDDEN_ITEMS_LIVE.resetToDefault();
        ReflectionHelpers.callStaticMethod(ShareSheetTools.class, "detachPanel");
        ((GlobalLayoutHook) ReflectionHelpers.getStaticField(ShareSheetTools.class, "LAYOUT_HOOK")).detach();
        ((GlobalLayoutHook) ReflectionHelpers.getStaticField(ShareSheetTools.class, "SHEET_LAYOUT_HOOK")).detach();
        ReflectionHelpers.setStaticField(ShareSheetTools.class, "activityReference", new WeakReference<>(null));
        ((ResourceIdCache) ReflectionHelpers.getStaticField(ShareSheetTools.class, "RESOURCE_IDS")).clear();
        ((Map<?, ?>) ReflectionHelpers.getStaticField(ShareSheetTools.class, "ORIGINAL_WIDTHS")).clear();
        ((Map<?, ?>) ReflectionHelpers.getStaticField(ShareSheetTools.class, "HIDDEN_SECTION_CHILDREN")).clear();
        ReflectionHelpers.setStaticField(ShareSheetTools.class, "windowGlobal", null);
        ReflectionHelpers.setStaticField(ShareSheetTools.class, "windowViewsReader", null);
        ReflectionHelpers.setStaticField(ShareSheetTools.class, "windowViewsUnavailable", false);
        ReflectionHelpers.setStaticField(ShareSheetTools.class, "applyPosted", false);
        ShareModelFilter.surface(null);
    }

    @Test public void recycledCellsRestoreTheirOriginalWidthAfterHiding() {
        FrameLayout cell = new FrameLayout(context);
        cell.setLayoutParams(new FrameLayout.LayoutParams(120, 48));

        ShareSheetTools.setCellHidden(cell, true);
        assertEquals(View.GONE, cell.getVisibility());
        assertEquals(0, cell.getLayoutParams().width);

        ShareSheetTools.setCellHidden(cell, false);
        assertEquals(View.VISIBLE, cell.getVisibility());
        assertEquals(120, cell.getLayoutParams().width);

        cell.getLayoutParams().width = 64;
        ShareSheetTools.setCellHidden(cell, true);
        ShareSheetTools.setCellHidden(cell, false);
        assertEquals("the recycled cell returns to its first measured width", 120,
                cell.getLayoutParams().width);
    }

    /**
     * One walk per root finds what findViewById finds: the first view with the id in pre-order,
     * never one inside a nested window root, where findViewById doesn't look.
     */
    @Test public void oneWalkPerRootFindsWhatFindViewByIdFinds() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Activity activity = controller.get();
            FrameLayout root = new FrameLayout(activity);
            FrameLayout branch = new FrameLayout(activity);
            View deepFirst = new View(activity);
            deepFirst.setId(0x7f000501);
            branch.addView(deepFirst);
            View shallowLater = new View(activity);
            shallowLater.setId(0x7f000501);
            root.addView(branch);
            root.addView(shallowLater);
            // A dialog's window root, not shown, so it has no parent yet: the framework marks it
            // a root namespace, and findViewById from above never enters it.
            android.app.Dialog dialog = new android.app.Dialog(activity);
            ViewGroup decor = (ViewGroup) dialog.getWindow().getDecorView();
            View inside = new View(activity);
            inside.setId(0x7f000502);
            decor.addView(inside);
            root.addView(decor);
            View other = new View(activity);
            other.setId(0x7f000503);
            root.addView(other);

            java.util.Set<Integer> ids = java.util.Set.of(0x7f000501, 0x7f000502, 0x7f000503, 0x7f000504);
            java.util.List<Map<Integer, View>> index = ShareSheetTools.indexRoots(java.util.List.of(root), ids);
            assertSame("the first view in pre-order", deepFirst, root.findViewById(0x7f000501));
            assertNull("the control: findViewById skips the nested window root", root.findViewById(0x7f000502));
            for (int id : ids) {
                assertSame(Integer.toHexString(id), root.findViewById(id), index.get(0).get(id));
            }
        }
    }

    @Test public void theHiddenListSplitsOnLineBreaksAsWellAsCommas() {
        assertEquals(java.util.List.of("sam", "whatsapp", "copy link", "repost"),
                ShareSheetTools.entries("Sam\nWhatsApp, Copy link\r\n,,Repost\n"));
        assertTrue(ShareSheetTools.entries(null).isEmpty());
    }

    @Test public void current47ContactsSectionWinsWhenOlderResourceStillResolves() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Activity activity = controller.get();
            FrameLayout root = new FrameLayout(activity);
            FrameLayout currentSection = new FrameLayout(activity);
            currentSection.setId(0x7f000201);
            root.addView(currentSection);
            activity.setContentView(root);

            Settings.HIDE_SHARE_CONTACTS.save(true);
            ReflectionHelpers.setStaticField(ShareSheetTools.class, "activityReference",
                    new WeakReference<>(activity));
            ReflectionHelpers.callStaticMethod(ShareSheetTools.class, "apply");

            assertEquals(View.GONE, currentSection.getVisibility());
        }
    }

    /**
     * The compact long-press sheet ranks its actions row at the top, and TikTok's panel adds
     * every top widget to the Send to frame, so that frame holds the Send to row and the actions
     * row side by side (#120). Hiding Send to must leave Report, Download and the rest showing,
     * still filtered by the hidden list, and turning it off must not show what TikTok hid itself.
     */
    @Test public void hidingSendToKeepsAnActionsRowThatSharesItsFrame() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Activity activity = controller.get();
            FrameLayout root = new FrameLayout(activity);
            FrameLayout section = new FrameLayout(activity);
            section.setId(0x7f000201);
            FrameLayout sendTo = new FrameLayout(activity);
            FrameLayout contacts = new FrameLayout(activity);
            contacts.setId(0x7f000301);
            sendTo.addView(contacts);
            View hiddenByTikTok = new View(activity);
            hiddenByTikTok.setVisibility(View.GONE);
            FrameLayout actionsWidget = new FrameLayout(activity);
            FrameLayout actions = actionRow(activity);
            NativeActionCell report = new NativeActionCell(activity, 137, "Report");
            NativeActionCell copy = new NativeActionCell(activity, 131, "Copy link");
            actions.addView(report);
            actions.addView(copy);
            actionsWidget.addView(actions);
            section.addView(sendTo);
            section.addView(hiddenByTikTok);
            section.addView(actionsWidget);
            root.addView(section);
            activity.setContentView(root);

            Settings.HIDE_SHARE_CONTACTS.save(true);
            Settings.SHARE_HIDDEN_ITEMS.save("copy link");
            ReflectionHelpers.setStaticField(ShareSheetTools.class, "activityReference",
                    new WeakReference<>(activity));
            ReflectionHelpers.callStaticMethod(ShareSheetTools.class, "apply");

            assertEquals("the frame holding the actions row stays up", View.VISIBLE, section.getVisibility());
            assertEquals(View.GONE, sendTo.getVisibility());
            assertEquals(View.VISIBLE, actionsWidget.getVisibility());
            assertEquals(View.VISIBLE, report.getVisibility());
            assertEquals("the hidden list still applies to the kept row", View.GONE, copy.getVisibility());

            Settings.HIDE_SHARE_CONTACTS.save(false);
            ReflectionHelpers.callStaticMethod(ShareSheetTools.class, "apply");

            assertEquals(View.VISIBLE, section.getVisibility());
            assertEquals(View.VISIBLE, sendTo.getVisibility());
            assertEquals("only children hidden here come back", View.GONE, hiddenByTikTok.getVisibility());
            assertEquals(View.VISIBLE, report.getVisibility());
        }
    }

    /** The control: a Send to frame with no other row in it still goes away whole. */
    @Test public void aSendToFrameWithoutAnotherRowIsHiddenWhole() {
        FrameLayout section = new FrameLayout(context);
        View sendTo = new View(context);
        section.addView(sendTo);
        FrameLayout elsewhere = new FrameLayout(context);
        FrameLayout actions = actionRow(context);
        elsewhere.addView(actions);

        ShareSheetTools.setSectionHidden(section, true, actions, null);
        assertEquals(View.GONE, section.getVisibility());
        assertEquals("children are left alone when the frame itself goes", View.VISIBLE, sendTo.getVisibility());

        ShareSheetTools.setSectionHidden(section, false, actions, null);
        assertEquals(View.VISIBLE, section.getVisibility());
    }

    /**
     * 47.0.3 builds the panel in a window of its own, which the activity's layout listener doesn't
     * see being laid out, so a contact bind is what runs the hiding pass there.
     */
    @Test public void aContactBindRunsTheHidingPassOnTheSendToRow() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Activity activity = controller.get();
            FrameLayout root = new FrameLayout(activity);
            FrameLayout contacts = new FrameLayout(activity);
            contacts.setId(0x7f000301);
            FrameLayout alice = new FrameLayout(activity);
            alice.setContentDescription("  Alice  ");
            alice.setLayoutParams(new FrameLayout.LayoutParams(120, 48));
            FrameLayout bob = new FrameLayout(activity);
            bob.setContentDescription("Bob");
            bob.setLayoutParams(new FrameLayout.LayoutParams(120, 48));
            contacts.addView(alice);
            contacts.addView(bob);
            root.addView(contacts);
            activity.setContentView(root);
            ReflectionHelpers.setStaticField(ShareSheetTools.class, "activityReference",
                    new WeakReference<>(activity));
            Settings.SHARE_HIDDEN_ITEMS.save("alice");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("the control: nothing has run the pass yet", View.VISIBLE, alice.getVisibility());

            ShareSheetTools.contactBound();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertEquals("Alice", ShareSheetTools.labelOf(alice));
            assertEquals(View.GONE, alice.getVisibility());
            assertEquals(View.VISIBLE, bob.getVisibility());
        }
    }

    /** The native action delegate publishes the bound child title, not a View description. */
    @Test public void nativeActionTitlesHideWithNullOrBlankRootDescriptions() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Activity activity = controller.get();
            FrameLayout actions = actionRow(activity);
            NativeActionCell report = new NativeActionCell(activity, 137, "  Report  ");
            NativeActionCell translated = new NativeActionCell(activity, 143, "Melden");
            translated.setContentDescription("  ");
            NativeActionCell repost = new NativeActionCell(activity, 129, "Repost");
            NativeActionCell copy = new NativeActionCell(activity, 131, "Copy link");
            int[] clicks = {0};
            copy.setOnClickListener(view -> clicks[0]++);
            actions.addView(report);
            actions.addView(translated);
            actions.addView(repost);
            actions.addView(copy);
            activity.setContentView(actions);
            ReflectionHelpers.setStaticField(ShareSheetTools.class, "activityReference",
                    new WeakReference<>(activity));
            Settings.SHARE_HIDDEN_ITEMS.save("report, melden");

            ReflectionHelpers.callStaticMethod(ShareSheetTools.class, "apply");

            assertNull("reading the native title does not copy it onto the View", report.getContentDescription());
            assertEquals("Report", ShareSheetTools.labelOf(report));
            assertEquals("Melden", ShareSheetTools.labelOf(translated));
            assertEquals(View.GONE, report.getVisibility());
            assertEquals(0, report.getLayoutParams().width);
            assertEquals(View.GONE, translated.getVisibility());
            assertEquals(View.VISIBLE, repost.getVisibility());
            assertEquals(View.VISIBLE, copy.getVisibility());
            assertEquals("reading labels cannot activate a share action", 0, clicks[0]);
            copy.performClick();
            assertEquals("an allowed cell keeps its listener", 1, clicks[0]);
        }
    }

    /**
     * TikTok's small share sheet lost all but its "Share with" row once anything was hidden (#120),
     * on a layout no account here gets. The debug line says which row each hidden cell sat in and
     * its shape, once per hide, and a contact stays unnamed.
     */
    @Test public void eachHiddenCellIsLoggedOnceWithItsRowAndNoContactName() {
        boolean debug = BaseSettings.DEBUG.get();
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            BaseSettings.DEBUG.save(true);
            Activity activity = controller.get();
            FrameLayout root = new FrameLayout(activity);
            FrameLayout contacts = new FrameLayout(activity);
            contacts.setId(0x7f000301);
            FrameLayout alice = new FrameLayout(activity);
            alice.setContentDescription("Alice");
            alice.setLayoutParams(new FrameLayout.LayoutParams(120, 48));
            contacts.addView(alice);
            FrameLayout actions = actionRow(activity);
            actions.addView(new NativeActionCell(activity, 137, "Report"));
            actions.addView(new NativeActionCell(activity, 129, "Repost"));
            root.addView(contacts);
            root.addView(actions);
            activity.setContentView(root);
            ReflectionHelpers.setStaticField(ShareSheetTools.class, "activityReference",
                    new WeakReference<>(activity));
            Settings.SHARE_HIDDEN_ITEMS.save("alice, report");
            ShadowLog.clear();

            ReflectionHelpers.callStaticMethod(ShareSheetTools.class, "apply");
            ReflectionHelpers.callStaticMethod(ShareSheetTools.class, "apply");

            List<String> lines = new ArrayList<>();
            for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
                if (item.msg.contains("Share sheet hides")) lines.add(item.msg);
            }
            assertEquals("one line per hidden cell, not per pass: " + lines, 2, lines.size());
            String all = String.join("\n", lines);
            assertTrue(all, all.contains("actions cell 0 \"Report\" (NativeActionCell, 1 children) in FrameLayout of 2"));
            assertTrue(all, all.contains("contacts cell 0 a person"));
            assertFalse("a contact's name stays out of the log: " + all, all.contains("Alice"));
            assertFalse("a cell left showing isn't logged: " + all, all.contains("Repost"));
        } finally {
            BaseSettings.DEBUG.save(debug);
        }
    }

    @Test public void anExcludedDescendantDoesNotLabelItsContainingGroup() {
        FrameLayout group = new FrameLayout(context);
        group.addView(new NativeActionCell(context, 137, "Report"));

        assertNull("the native vertical row can contain several choices", ShareSheetTools.labelOf(group));
    }

    @Test public void aLateNativeLabelIsHiddenDuringLayoutBeforeAPostedPassCanDrawIt() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Activity activity = controller.get();
            activity.setContentView(new FrameLayout(activity));
            ShareSheetTools.install(activity);
            idle();
            Settings.SHARE_HIDDEN_ITEMS.save("report");
            NativeActionCell cell = new NativeActionCell(activity, 137, "");
            Dialog dialog = showActions(activity, cell);
            try {
                idle();
                assertEquals(View.VISIBLE, cell.getVisibility());
                cell.title.setText("Report");
                dialog.getWindow().getDecorView().getViewTreeObserver().dispatchOnGlobalLayout();
                // Global layout happens before drawing; a queued hiding pass runs after that draw.
                assertEquals("the native title cannot remain visible until the queue runs", View.GONE, cell.getVisibility());
                assertEquals(0, cell.getLayoutParams().width);
            } finally {
                dialog.dismiss();
                idle();
            }
        }
    }

    /** The activity observer cannot deliver a later layout in TikTok's separate panel window. */
    @Test public void latePanelLabelsAndRecycledCellsUseThePanelsOwnLayouts() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Activity activity = controller.get();
            activity.setContentView(new FrameLayout(activity));
            ShareSheetTools.install(activity);
            idle();
            Settings.SHARE_HIDDEN_ITEMS.save("report");
            Settings.HIDE_SHARE_CONTACTS.save(true);
            NativeActionCell cell = new NativeActionCell(activity, 137, "");
            NativeActionCell copy = new NativeActionCell(activity, 131, "Copy link");
            Dialog dialog = showActions(activity, cell, copy);
            try {
                idle();
                assertNull("no contact exists to bootstrap this panel", dialog.findViewById(0x7f000301));
                assertNull(ShareSheetTools.labelOf(cell));
                assertEquals(View.VISIBLE, cell.getVisibility());

                cell.title.setText("Report");
                panelLayout(dialog);
                assertEquals(View.GONE, cell.getVisibility());
                assertEquals(0, cell.getLayoutParams().width);
                assertEquals(View.VISIBLE, copy.getVisibility());
                assertEquals(131, copy.getLayoutParams().width);

                cell.title.setText("Repost");
                cell.getLayoutParams().width = 64;
                panelLayout(dialog);
                assertEquals(View.VISIBLE, cell.getVisibility());
                assertEquals("a native rebind cannot replace the original width", 137,
                        cell.getLayoutParams().width);

                cell.title.setText("Report");
                panelLayout(dialog);
                assertEquals(View.GONE, cell.getVisibility());
                ReflectionHelpers.setStaticField(Setting.class, "pausedForProcess", true);
                panelLayout(dialog);
                assertEquals("Pause restores the row", View.VISIBLE, cell.getVisibility());
                assertEquals(137, cell.getLayoutParams().width);
                assertEquals("Pause keeps the exclusion", "report", Settings.SHARE_HIDDEN_ITEMS.savedValue());

                ReflectionHelpers.setStaticField(Setting.class, "pausedForProcess", false);
                panelLayout(dialog);
                assertEquals(View.GONE, cell.getVisibility());
                Settings.SHARE_HIDDEN_ITEMS.save("");
                panelLayout(dialog);
                assertEquals("clearing the exclusion restores the row", View.VISIBLE, cell.getVisibility());
                assertEquals(137, cell.getLayoutParams().width);
            } finally {
                dialog.dismiss();
            }
        }
    }

    @Test public void theSameNonfocusablePanelObservesLateCellsAfterDismissAndReopen() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Activity activity = controller.get();
            activity.setContentView(new FrameLayout(activity));
            ShareSheetTools.install(activity);
            idle();
            Settings.SHARE_HIDDEN_ITEMS.save("report");
            NativeActionCell cell = new NativeActionCell(activity, 137, "Report");
            Dialog dialog = showActions(activity, cell);
            View decor = dialog.getWindow().getDecorView();
            try {
                idle();
                assertEquals(View.GONE, cell.getVisibility());
                assertTrue((dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0);
                dialog.dismiss();
                idle();
                GlobalLayoutHook sheet = ReflectionHelpers.getStaticField(ShareSheetTools.class, "SHEET_LAYOUT_HOOK");
                assertNull("dismiss releases the panel's layout listener", ReflectionHelpers.getField(sheet, "listener"));

                // No activity layout, contact bind or new Dialog between these phases.
                cell.title.setText("");
                dialog.show();
                idle();
                assertSame(decor, dialog.getWindow().getDecorView());
                ViewTreeObserver reopenedObserver = decor.getViewTreeObserver();
                assertSame(reopenedObserver, ReflectionHelpers.getField(sheet, "observer"));
                assertEquals(View.VISIBLE, cell.getVisibility());
                assertEquals(137, cell.getLayoutParams().width);
                cell.title.setText("Report");
                panelLayout(dialog);
                assertEquals(View.GONE, cell.getVisibility());
                assertEquals(0, cell.getLayoutParams().width);
                cell.title.setText("Copy link");
                panelLayout(dialog);
                assertEquals(View.VISIBLE, cell.getVisibility());
                assertEquals("the reused cell keeps its original width", 137, cell.getLayoutParams().width);
            } finally {
                dialog.dismiss();
            }
        }
    }

    @Test public void aContactFreePanelIsObservedWhenItAttachesPausedOrWithEmptyExclusions() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Activity activity = controller.get();
            activity.setContentView(new FrameLayout(activity));
            ShareSheetTools.install(activity);
            idle();
            Settings.HIDE_SHARE_CONTACTS.save(true);
            // The native row is sufficient even when the hidden WindowManager API is unavailable.
            ReflectionHelpers.setStaticField(ShareSheetTools.class, "windowViewsUnavailable", true);
            for (boolean paused : new boolean[] {true, false}) {
                ReflectionHelpers.setStaticField(Setting.class, "pausedForProcess", paused);
                Settings.SHARE_HIDDEN_ITEMS.save(paused ? "report" : "");
                NativeActionCell cell = new NativeActionCell(activity, 137, "");
                NativeActionCell copy = new NativeActionCell(activity, 131, "Copy link");
                Dialog dialog = showActions(activity, cell, copy);
                try {
                    idle();
                    assertNull(dialog.findViewById(0x7f000301));
                    GlobalLayoutHook sheet = ReflectionHelpers.getStaticField(ShareSheetTools.class, "SHEET_LAYOUT_HOOK");
                    assertSame("inactive settings must still attach to the panel observer",
                            dialog.getWindow().getDecorView().getViewTreeObserver(), ReflectionHelpers.getField(sheet, "observer"));
                    assertEquals(View.VISIBLE, cell.getVisibility());

                    ReflectionHelpers.setStaticField(Setting.class, "pausedForProcess", false);
                    Settings.SHARE_HIDDEN_ITEMS.save("report");
                    cell.title.setText("Report");
                    panelLayout(dialog);
                    assertEquals(View.GONE, cell.getVisibility());
                    assertEquals(0, cell.getLayoutParams().width);
                    assertEquals(View.VISIBLE, copy.getVisibility());
                    assertEquals(131, copy.getLayoutParams().width);
                } finally {
                    dialog.dismiss();
                    idle();
                }
            }
        }
    }

    @Test public void aReopenedPanelObservesItsNewLateBoundActionCells() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Activity activity = controller.get();
            activity.setContentView(new FrameLayout(activity));
            ShareSheetTools.install(activity);
            idle();
            Settings.SHARE_HIDDEN_ITEMS.save("report");
            NativeActionCell first = new NativeActionCell(activity, 137, "Report");
            Dialog original = showActions(activity, first);
            try {
                idle();
                assertEquals(View.GONE, first.getVisibility());
            } finally {
                original.dismiss();
                idle();
            }
            // A new panel must bind itself. Do not bootstrap it from the activity or contacts.
            NativeActionCell reopened = new NativeActionCell(activity, 149, "");
            Dialog next = showActions(activity, reopened);
            try {
                idle();
                assertEquals(View.VISIBLE, reopened.getVisibility());
                reopened.title.setText("Report");
                panelLayout(next);
                assertEquals(View.GONE, reopened.getVisibility());
                assertEquals(0, reopened.getLayoutParams().width);
                reopened.title.setText("Copy link");
                panelLayout(next);
                assertEquals(View.VISIBLE, reopened.getVisibility());
                assertEquals("a new cell owns its own original width", 149, reopened.getLayoutParams().width);
            } finally {
                next.dismiss();
                idle();
            }
        }
    }

    @Test public void theReportedExclusionsHideLateNativeLabelsAndPersistAcrossActivityRestart() {
        String reported = "whatsapp, status, telegram, messenger, facebook, instagram direct, sms, discord, "
                + "email kakaotalk, x, instagram, stories, more, stitch, why this video, gif, im create group, "
                + "duet, report, casting, live photo, create sticker, promote for others fyp, share to story";
        Settings.SHARE_HIDDEN_ITEMS.save(reported);
        Settings.SHARE_HIDDEN_ITEMS_PROFILE.save("@video");
        Settings.SHARE_HIDDEN_ITEMS_LIVE.save("@video");
        for (int restart = 0; restart < 2; restart++) {
            try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
                Activity activity = controller.get();
                activity.setContentView(new FrameLayout(activity));
                ShareSheetTools.install(activity);
                idle();
                NativeActionCell report = new NativeActionCell(activity, 137, "");
                NativeActionCell duet = new NativeActionCell(activity, 139, "");
                NativeActionCell repost = new NativeActionCell(activity, 129, "Repost");
                NativeActionCell copy = new NativeActionCell(activity, 131, "Copy link");
                NativeActionCell email = new NativeActionCell(activity, 133, "Email");
                NativeActionCell kakao = new NativeActionCell(activity, 135, "KakaoTalk");
                int[] clicks = {0};
                copy.setOnClickListener(view -> clicks[0]++);
                Dialog dialog = showActions(activity, report, duet, repost, copy, email, kakao);
                try {
                    idle();
                    assertNull(dialog.findViewById(0x7f000301));
                    assertEquals(View.VISIBLE, report.getVisibility());
                    report.title.setText("Report");
                    duet.title.setText("Duet");
                    panelLayout(dialog);
                    assertEquals(View.GONE, report.getVisibility());
                    assertEquals(View.GONE, duet.getVisibility());
                    assertEquals(View.VISIBLE, repost.getVisibility());
                    assertEquals(View.VISIBLE, copy.getVisibility());
                    assertEquals("the literal missing comma cannot mean two exclusions", View.VISIBLE, email.getVisibility());
                    assertEquals(View.VISIBLE, kakao.getVisibility());
                    copy.performClick();
                    assertEquals(1, clicks[0]);
                    assertEquals(reported, Settings.SHARE_HIDDEN_ITEMS.savedValue());
                    assertEquals("@video", Settings.SHARE_HIDDEN_ITEMS_PROFILE.savedValue());
                    assertEquals("@video", Settings.SHARE_HIDDEN_ITEMS_LIVE.savedValue());
                } finally {
                    dialog.dismiss();
                    idle();
                }
            }
        }
    }

    /** Native acceptance remains open. setText can redraw a measured title without a layout. */
    @Test public void redrawOnlyLateTitleIsFilteredBeforeTheDrawWithoutPostedWork() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Activity activity = controller.get();
            CountingActivityRoot activityRoot = new CountingActivityRoot(activity);
            activityRoot.addView(new View(activity));
            activity.setContentView(activityRoot);
            ShareSheetTools.install(activity);
            idle();
            Settings.SHARE_HIDDEN_ITEMS.save("report");
            Settings.HIDE_SHARE_CONTACTS.save(true);
            ReflectionHelpers.setStaticField(ShareSheetTools.class, "windowViewsUnavailable", true);
            NativeActionCell cell = new NativeActionCell(activity, 137, "");
            NativeActionCell copy = new NativeActionCell(activity, 131, "Copy link");
            int[] clicks = {0};
            copy.setOnClickListener(view -> clicks[0]++);
            Dialog dialog = showActions(activity, cell, copy);
            try {
                idle();
                assertNull("no contact can bootstrap this panel", dialog.findViewById(0x7f000301));
                assertEquals(View.VISIBLE, cell.getVisibility());
                measureTitle(cell);
                ViewTreeObserver observer = dialog.getWindow().getDecorView().getViewTreeObserver();
                int[] layouts = {0}, queuedRuns = {0};
                observer.addOnGlobalLayoutListener(() -> layouts[0]++);
                Utils.runOnMainThread(() -> queuedRuns[0]++);
                activityRoot.childrenRead = 0;

                setRedrawOnlyTitle(cell, "Report");
                observer.dispatchOnPreDraw();

                assertEquals("the exclusion must apply before this draw", View.GONE, cell.getVisibility());
                assertEquals(0, cell.getLayoutParams().width);
                assertEquals("redraw does not imply global layout", 0, layouts[0]);
                assertEquals("filtering cannot wait for posted work", 0, queuedRuns[0]);
                assertEquals("a panel redraw must not traverse the activity", 0, activityRoot.childrenRead);
                assertEquals(View.VISIBLE, copy.getVisibility());
                assertEquals(131, copy.getLayoutParams().width);
                assertEquals("reading the labels cannot activate an action", 0, clicks[0]);
                copy.performClick();
                assertEquals(1, clicks[0]);
            } finally {
                dialog.dismiss();
                idle();
            }
        }
    }

    @Test public void redrawOnlyRecycledAllowedTitleRestoresItsNativeWidthAndListener() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Activity activity = controller.get();
            activity.setContentView(new FrameLayout(activity));
            ShareSheetTools.install(activity);
            idle();
            Settings.SHARE_HIDDEN_ITEMS.save("report");
            NativeActionCell cell = new NativeActionCell(activity, 137, "Report");
            int[] clicks = {0};
            cell.setOnClickListener(view -> clicks[0]++);
            Dialog dialog = showActions(activity, cell);
            try {
                idle();
                assertEquals(View.GONE, cell.getVisibility());
                assertEquals(0, cell.getLayoutParams().width);
                ViewTreeObserver observer = dialog.getWindow().getDecorView().getViewTreeObserver();
                for (String allowed : new String[] {"Repost", "Copy link"}) {
                    measureTitle(cell);
                    setRedrawOnlyTitle(cell, allowed);
                    observer.dispatchOnPreDraw();
                    assertEquals("a recycled allowed action must return before drawing", View.VISIBLE,
                            cell.getVisibility());
                    assertEquals(137, cell.getLayoutParams().width);
                    assertEquals("reading the label cannot activate it", 0, clicks[0]);

                    measureTitle(cell);
                    setRedrawOnlyTitle(cell, "Report");
                    observer.dispatchOnPreDraw();
                    assertEquals(View.GONE, cell.getVisibility());
                    assertEquals(0, cell.getLayoutParams().width);
                }
                measureTitle(cell);
                setRedrawOnlyTitle(cell, "Copy link");
                observer.dispatchOnPreDraw();
                cell.performClick();
                assertEquals("a restored action keeps its native listener", 1, clicks[0]);
            } finally {
                dialog.dismiss();
                idle();
            }
        }
    }

    @Test public void redrawRestoresExcludedCellsWhenOffOrPausedAndResumesFiltering() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Activity activity = controller.get();
            activity.setContentView(new FrameLayout(activity));
            ShareSheetTools.install(activity);
            idle();
            for (boolean paused : new boolean[] {false, true}) {
                Settings.SHARE_HIDDEN_ITEMS.save("report");
                NativeActionCell cell = new NativeActionCell(activity, 137, "Report");
                Dialog dialog = showActions(activity, cell);
                try {
                    idle();
                    assertEquals(View.GONE, cell.getVisibility());
                    ViewTreeObserver observer = dialog.getWindow().getDecorView().getViewTreeObserver();
                    if (paused) ReflectionHelpers.setStaticField(Setting.class, "pausedForProcess", true);
                    else Settings.SHARE_HIDDEN_ITEMS.save("");
                    observer.dispatchOnPreDraw();
                    assertEquals("off and Pause must restore the row before drawing", View.VISIBLE,
                            cell.getVisibility());
                    assertEquals(137, cell.getLayoutParams().width);
                    assertEquals(paused ? "report" : "", Settings.SHARE_HIDDEN_ITEMS.savedValue());

                    ReflectionHelpers.setStaticField(Setting.class, "pausedForProcess", false);
                    Settings.SHARE_HIDDEN_ITEMS.save("report");
                    observer.dispatchOnPreDraw();
                    assertEquals(View.GONE, cell.getVisibility());
                    assertEquals(0, cell.getLayoutParams().width);
                } finally {
                    ReflectionHelpers.setStaticField(Setting.class, "pausedForProcess", false);
                    dialog.dismiss();
                    idle();
                }
            }
        }
    }

    @Test public void redrawObserverDetachesAndRebindsWhenTheSamePanelReopens() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Activity activity = controller.get();
            activity.setContentView(new FrameLayout(activity));
            ShareSheetTools.install(activity);
            idle();
            Settings.SHARE_HIDDEN_ITEMS.save("report");
            NativeActionCell cell = new NativeActionCell(activity, 137, "");
            Dialog dialog = showActions(activity, cell);
            View decor = dialog.getWindow().getDecorView();
            try {
                idle();
                ViewTreeObserver original = decor.getViewTreeObserver();
                dialog.dismiss();
                idle();
                measureTitle(cell);
                setRedrawOnlyTitle(cell, "Report");
                cell.accessibilityReads = 0;
                if (original.isAlive()) original.dispatchOnPreDraw();
                decor.getViewTreeObserver().dispatchOnPreDraw();
                assertEquals("a detached observer must stop reading native labels", 0, cell.accessibilityReads);
                assertEquals("a detached panel must not be filtered", View.VISIBLE, cell.getVisibility());
                assertEquals(137, cell.getLayoutParams().width);

                setRedrawOnlyTitle(cell, "");
                dialog.show();
                idle();
                assertSame(decor, dialog.getWindow().getDecorView());
                assertEquals(View.VISIBLE, cell.getVisibility());
                measureTitle(cell);
                setRedrawOnlyTitle(cell, "Report");
                decor.getViewTreeObserver().dispatchOnPreDraw();
                assertEquals("the reused panel must observe redraw-only titles", View.GONE, cell.getVisibility());
                assertEquals(0, cell.getLayoutParams().width);
                measureTitle(cell);
                setRedrawOnlyTitle(cell, "Copy link");
                decor.getViewTreeObserver().dispatchOnPreDraw();
                assertEquals(View.VISIBLE, cell.getVisibility());
                assertEquals(137, cell.getLayoutParams().width);
            } finally {
                dialog.dismiss();
                idle();
            }
        }
    }

    private static void measureTitle(NativeActionCell cell) {
        cell.title.setLayoutParams(new FrameLayout.LayoutParams(cell.nativeWidth, 48));
        cell.measure(View.MeasureSpec.makeMeasureSpec(cell.nativeWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY));
        cell.layout(0, 0, cell.nativeWidth, 48);
        assertNotNull("the native title must already have a text layout", cell.title.getLayout());
        assertEquals(cell.nativeWidth, cell.title.getWidth());
        assertFalse(cell.title.isLayoutRequested());
        assertFalse(cell.isLayoutRequested());
    }

    private static void setRedrawOnlyTitle(NativeActionCell cell, String text) {
        cell.title.setText(text);
        assertFalse("this text change must only invalidate, not request layout", cell.title.isLayoutRequested());
        assertFalse("a title redraw must not request layout of its cell", cell.isLayoutRequested());
    }

    private static final class CountingActivityRoot extends FrameLayout {
        int childrenRead;
        CountingActivityRoot(Context context) { super(context); }
        @Override public View getChildAt(int index) {
            childrenRead++;
            return super.getChildAt(index);
        }
    }

    private static FrameLayout actionRow(Context context) {
        FrameLayout actions = new FrameLayout(context);
        actions.setId(0x7f000401);
        return actions;
    }

    private static Dialog showActions(Activity activity, NativeActionCell... cells) {
        FrameLayout actions = actionRow(activity);
        for (NativeActionCell cell : cells) actions.addView(cell);
        NativePanel panel = new NativePanel(activity);
        panel.addView(actions);
        Dialog dialog = new Dialog(activity);
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        dialog.setContentView(panel);
        dialog.show();
        return dialog;
    }

    /** The inspected native panel calls the bridge before Android attaches its row children. */
    private static final class NativePanel extends FrameLayout {
        NativePanel(Context context) { super(context); }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            ShareSheetTools.panelBound(findViewById(0x7f000401));
        }
    }

    private static void panelLayout(Dialog dialog) {
        dialog.getWindow().getDecorView().getViewTreeObserver().dispatchOnGlobalLayout();
        idle();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** The holder/delegate shape shared by both native action adapters on every declared host. */
    private static final class NativeActionCell extends FrameLayout {
        final TextView title;
        final int nativeWidth;
        int accessibilityReads;

        NativeActionCell(Context context, int width, String label) {
            super(context);
            nativeWidth = width;
            setLayoutParams(new FrameLayout.LayoutParams(width, 48));
            title = new TextView(context);
            title.setText(label);
            addView(title);
            setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo node) {
                    super.onInitializeAccessibilityNodeInfo(host, node);
                    accessibilityReads++;
                    node.setContentDescription(title.getText());
                    node.setClassName(android.widget.Button.class.getName());
                }
            });
        }
    }

    public static final class TestActivity extends Activity {
    }
}
