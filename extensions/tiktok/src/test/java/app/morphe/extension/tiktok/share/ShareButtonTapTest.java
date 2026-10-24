/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import static org.junit.Assert.*;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

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
import org.robolectric.shadows.ShadowToast;

import java.time.Duration;

/** Copy the link with one press on Share: the cell's own video, the sheet one more press away. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class ShareButtonTapTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for TikTok's Aweme. */
    public static final class Post {
        private final String aid;
        private final String shareUrl;

        Post(String aid, String shareUrl) {
            this.aid = aid;
            this.shareUrl = shareUrl;
        }

        public String getAid() { return aid; }
        public String getShareUrl() { return shareUrl; }
    }

    /** Stands in for TikTok's VideoItemParams, the item a feed cell is bound to. */
    public static final class Item {
        private final Object aweme;

        Item(Object aweme) {
            this.aweme = aweme;
        }

        public Object getAweme() { return aweme; }
    }

    private static final Item FIRST = new Item(new Post("7710000000000000001",
            "https://www.tiktok.com/@first/video/7710000000000000001?sender_device=pc&u_code=abc"));
    private static final Item SECOND = new Item(new Post("7710000000000000002",
            "https://www.tiktok.com/@second/video/7710000000000000002?is_from_webapp=1"));

    private ActivityController<android.app.Activity> controller;
    private ClipboardManager clipboard;
    private View button;

    @Before public void setUp() {
        controller = Robolectric.buildActivity(android.app.Activity.class).setup();
        Utils.setContext(controller.get());
        clipboard = (ClipboardManager) controller.get().getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("before", "before"));
        button = new View(controller.get());
        button.setOnClickListener(view -> { });
        SettingsStatus.shareSheetEnabled = true;
        Settings.CUSTOM_SHARE_DOMAIN.save("");
        BaseSettings.SANITIZE_SHARING_LINKS.save(true);
        ShareButtonTap.resetForTests();
        ShadowToast.reset();
    }

    @After public void tearDown() {
        SettingsStatus.shareSheetEnabled = false;
        Settings.SHARE_TAP_COPIES_LINK.resetToDefault();
        Settings.CUSTOM_SHARE_DOMAIN.resetToDefault();
        BaseSettings.SANITIZE_SHARING_LINKS.resetToDefault();
        ShareButtonTap.resetForTests();
        controller.close();
    }

    private String clip() {
        return String.valueOf(clipboard.getPrimaryClip().getItemAt(0).getText());
    }

    @Test public void offThePressIsTikToks() {
        assertFalse(Settings.SHARE_TAP_COPIES_LINK.defaultValue);
        assertFalse(ShareButtonTap.onTap(button, FIRST));
        assertEquals("before", clip());
        assertNull(ShadowToast.getTextOfLatestToast());
    }

    @Test public void aPressCopiesTheCellsVideoAndAnotherRightAfterOpensTheSheet() {
        Settings.SHARE_TAP_COPIES_LINK.save(true);

        assertTrue(ShareButtonTap.onTap(button, FIRST));
        assertEquals("tracking taken off", "https://www.tiktok.com/@first/video/7710000000000000001", clip());
        assertEquals(L10n.t("Link copied"), ShadowToast.getTextOfLatestToast());

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ShareButtonTap.AGAIN_MS - 1));
        assertFalse("the second press opens TikTok's sheet", ShareButtonTap.onTap(button, FIRST));
        assertTrue("and the one after copies again", ShareButtonTap.onTap(button, FIRST));

        assertTrue("another cell's press copies that cell's video", ShareButtonTap.onTap(button, SECOND));
        assertEquals("https://www.tiktok.com/@second/video/7710000000000000002", clip());
    }

    @Test public void aSecondPressAfterAWhileCopiesAgain() {
        Settings.SHARE_TAP_COPIES_LINK.save(true);
        assertTrue(ShareButtonTap.onTap(button, FIRST));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ShareButtonTap.AGAIN_MS));
        assertTrue(ShareButtonTap.onTap(button, FIRST));
    }

    @Test public void aCellWithNoPostLeavesThePressToTikTok() {
        Settings.SHARE_TAP_COPIES_LINK.save(true);
        assertFalse("nothing bound yet", ShareButtonTap.onTap(button, null));
        assertFalse("an item without a post", ShareButtonTap.onTap(button, new Item(null)));
        Item noId = new Item(new Post(null, "https://www.tiktok.com/@x/video/1"));
        assertFalse("a post without an id, whose second press could never open the sheet",
                ShareButtonTap.onTap(button, noId));
        assertEquals("before", clip());
    }

    @Test public void aSavedSwitchWithoutShareSheetToolsDoesNothing() {
        Settings.SHARE_TAP_COPIES_LINK.save(true);
        SettingsStatus.shareSheetEnabled = false;
        assertFalse(ShareButtonTap.onTap(button, FIRST));
        assertEquals("before", clip());
    }

    /** A screen reader says what the press does, and still gets everything TikTok tells it. */
    @Test public void aScreenReaderHearsThatThePressCopiesTheLink() {
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setContentDescription("Share, 1.2K");
                // What a phone's View adds for a clickable view; Robolectric's node leaves it out.
                if (!info.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)) {
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
                }
            }
        });
        ShareButtonTap.bind(button);
        ShareButtonTap.bind(button);
        assertTrue(button.getAccessibilityDelegate() instanceof ShareButtonTap.CopyLabel);

        assertNull("off, the click keeps its own name", clickLabel());
        Settings.SHARE_TAP_COPIES_LINK.save(true);
        assertEquals(L10n.t("Copy the video link"), clickLabel());
        assertEquals("Share, 1.2K", button.createAccessibilityNodeInfo().getContentDescription());
    }

    private CharSequence clickLabel() {
        AccessibilityNodeInfo info = button.createAccessibilityNodeInfo();
        CharSequence label = null;
        int clicks = 0;
        for (AccessibilityNodeInfo.AccessibilityAction action : info.getActionList()) {
            if (action.getId() != AccessibilityNodeInfo.ACTION_CLICK) continue;
            clicks++;
            label = action.getLabel();
        }
        assertEquals("one click action", 1, clicks);
        return label;
    }
}
