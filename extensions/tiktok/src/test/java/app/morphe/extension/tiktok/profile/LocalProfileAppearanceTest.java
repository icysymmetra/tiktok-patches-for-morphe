/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import static app.morphe.extension.tiktok.profile.LocalProfileAppearance.*;
import static org.junit.Assert.*;

import android.view.View;
import android.graphics.drawable.ColorDrawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ClickableSpan;
import android.text.style.ImageSpan;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class LocalProfileAppearanceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        PausedProcess.set(false);
        SignedInUser.idForTests = "account-a";
        SettingsStatus.followStatusEnabled = true;
        Settings.LOCAL_PROFILE_VERIFIED_BADGE.save(false);
        Settings.HIDE_OWN_PROFILE_RESTRICTION_NOTICE.save(false);
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        SignedInUser.idForTests = null;
        SettingsStatus.followStatusEnabled = false;
        Settings.LOCAL_PROFILE_VERIFIED_BADGE.save(false);
        Settings.HIDE_OWN_PROFILE_RESTRICTION_NOTICE.save(false);
    }

    @Test public void bothDisplayControlsDefaultOffAndRequireRestart() {
        assertEquals(Boolean.FALSE, Settings.LOCAL_PROFILE_VERIFIED_BADGE.defaultValue);
        assertEquals(Boolean.FALSE, Settings.HIDE_OWN_PROFILE_RESTRICTION_NOTICE.defaultValue);
        assertTrue(Settings.LOCAL_PROFILE_VERIFIED_BADGE.rebootApp);
        assertTrue(Settings.HIDE_OWN_PROFILE_RESTRICTION_NOTICE.rebootApp);
        assertSame(Boolean.FALSE, legacyBadge(Boolean.FALSE, "account-a", LEGACY_NICKNAME));
        assertNull(resource(null, "account-a", NICKNAME));
        View root = view();
        assertEquals(View.VISIBLE, noticeVisibility(View.VISIBLE, root, root, "account-a", RESTRICTION_NOTICE));
    }

    @Test public void onlyAnExplicitKnownOwnNicknameReceivesTheNativeBlueBadge() {
        Settings.LOCAL_PROFILE_VERIFIED_BADGE.save(true);
        assertSame(Boolean.TRUE, legacyBadge(Boolean.FALSE, "account-a", LEGACY_NICKNAME));
        assertEquals(Integer.valueOf(VERIFIED_BADGE), resource(null, "account-a", NICKNAME));
        for (String uid : new String[]{null, "", "0", "account-b", " account-a", "account-a\n"}) {
            assertSame(Boolean.FALSE, legacyBadge(Boolean.FALSE, uid, LEGACY_NICKNAME));
            assertNull(resource(null, uid, NICKNAME));
        }
        for (String component : new String[]{null, "", RESTRICTION_NOTICE, "user_account_state_control_media"}) {
            assertSame(Boolean.FALSE, legacyBadge(Boolean.FALSE, "account-a", component));
            assertNull(resource(null, "account-a", component));
        }
    }

    @Test public void nativeVerifiedUnknownAndExistingResourceValuesKeepTheirIdentity() {
        Settings.LOCAL_PROFILE_VERIFIED_BADGE.save(true);
        assertNull(legacyBadge(null, "account-a", LEGACY_NICKNAME));
        assertSame(Boolean.TRUE, legacyBadge(Boolean.TRUE, "account-a", LEGACY_NICKNAME));
        for (Integer nativeBadge : new Integer[]{0, -1, 0x7f010123, VERIFIED_BADGE}) {
            assertSame(nativeBadge, resource(nativeBadge, "account-a", NICKNAME));
            assertSame(nativeBadge, resource(nativeBadge, null, null));
        }
    }

    @Test public void pausePatchAbsenceAndSignOutAlwaysUseNativePresentation() {
        Settings.LOCAL_PROFILE_VERIFIED_BADGE.save(true);
        Settings.HIDE_OWN_PROFILE_RESTRICTION_NOTICE.save(true);
        PausedProcess.set(true);
        assertNative();
        PausedProcess.set(false);
        SettingsStatus.followStatusEnabled = false;
        assertNative();
        SettingsStatus.followStatusEnabled = true;
        for (String uid : new String[]{"", "0", " ", "account\n", new String(new char[129]).replace('\0', 'a')}) {
            SignedInUser.idForTests = uid;
            assertNative();
        }
    }

    @Test public void aDeferredRenderRechecksOriginalNativeValueAfterPauseAccountSwitchAndDisable() {
        Settings.LOCAL_PROFILE_VERIFIED_BADGE.save(true);
        Integer capturedNativeBadge = null;
        assertEquals(Integer.valueOf(VERIFIED_BADGE), resource(capturedNativeBadge, "account-a", NICKNAME));
        PausedProcess.set(true);
        assertNull(resource(capturedNativeBadge, "account-a", NICKNAME));
        PausedProcess.set(false);
        SignedInUser.idForTests = "account-b";
        assertNull(resource(capturedNativeBadge, "account-a", NICKNAME));
        assertEquals(Integer.valueOf(VERIFIED_BADGE), resource(capturedNativeBadge, "account-b", NICKNAME));
        Settings.LOCAL_PROFILE_VERIFIED_BADGE.save(false);
        assertNull(resource(capturedNativeBadge, "account-b", NICKNAME));
        assertNull(capturedNativeBadge);
    }

    @Test public void theNoticeToggleOnlyChangesVisibleOnItsExactOwnRoot() {
        Settings.HIDE_OWN_PROFILE_RESTRICTION_NOTICE.save(true);
        View root = view();
        assertEquals(View.GONE, noticeVisibility(View.VISIBLE, root, root, "account-a", RESTRICTION_NOTICE));
        for (int visibility : new int[]{View.GONE, View.INVISIBLE, -1, 17}) {
            assertEquals(visibility, noticeVisibility(visibility, root, root, "account-a", RESTRICTION_NOTICE));
        }
        assertEquals(View.VISIBLE, noticeVisibility(View.VISIBLE, view(), root, "account-a", RESTRICTION_NOTICE));
        assertEquals(View.VISIBLE, noticeVisibility(View.VISIBLE, null, null, "account-a", RESTRICTION_NOTICE));
        assertEquals(View.VISIBLE, noticeVisibility(View.VISIBLE, root, null, "account-a", RESTRICTION_NOTICE));
        for (String uid : new String[]{null, "", "0", "account-b"}) {
            assertEquals(View.VISIBLE, noticeVisibility(View.VISIBLE, root, root, uid, RESTRICTION_NOTICE));
        }
        for (String component : new String[]{null, "", NICKNAME, "user_account_state_control_media"}) {
            assertEquals(View.VISIBLE, noticeVisibility(View.VISIBLE, root, root, "account-a", component));
        }
    }

    @Test public void badgeAndNoticeControlsAreIndependent() {
        Settings.LOCAL_PROFILE_VERIFIED_BADGE.save(true);
        View root = view();
        assertEquals(View.VISIBLE, noticeVisibility(View.VISIBLE, root, root, "account-a", RESTRICTION_NOTICE));
        Settings.LOCAL_PROFILE_VERIFIED_BADGE.save(false);
        Settings.HIDE_OWN_PROFILE_RESTRICTION_NOTICE.save(true);
        assertNull(resource(null, "account-a", NICKNAME));
        assertEquals(View.GONE, noticeVisibility(View.VISIBLE, root, root, "account-a", RESTRICTION_NOTICE));
    }

    @Test public void theNativeAppealListenerSurvivesHidingAndTheNextNativeBind() {
        Settings.HIDE_OWN_PROFILE_RESTRICTION_NOTICE.save(true);
        View root = view();
        AtomicInteger appeals = new AtomicInteger();
        root.setOnClickListener(ignored -> appeals.incrementAndGet());
        root.setVisibility(noticeVisibility(View.VISIBLE, root, root, "account-a", RESTRICTION_NOTICE));
        assertEquals(View.GONE, root.getVisibility());
        assertTrue(root.hasOnClickListeners());
        Settings.HIDE_OWN_PROFILE_RESTRICTION_NOTICE.save(false);
        root.setVisibility(noticeVisibility(View.VISIBLE, root, root, "account-a", RESTRICTION_NOTICE));
        assertEquals(View.VISIBLE, root.getVisibility());
        assertTrue(root.performClick());
        assertEquals(1, appeals.get());
    }

    @Test public void anUnknownNativeResourceWithVerificationMetadataNeverGetsAReplacement() {
        Settings.LOCAL_PROFILE_VERIFIED_BADGE.save(true);
        for (String component : new String[]{NICKNAME, NAVBAR_NICKNAME}) {
            assertNull(badgeResource(null, false, "account-a", component));
            assertEquals(Integer.valueOf(VERIFIED_BADGE), badgeResource(null, true, "account-a", component));
        }
    }

    @Test public void aReboundOwnerCannotReplayAnotherAccountsOrAnOlderBindingsCapturedName() {
        Settings.LOCAL_PROFILE_VERIFIED_BADGE.save(true);
        Object owner = new Object(), oldRepaint = new Object(), newRepaint = new Object();
        beginNicknameBind(owner, "account-a", NICKNAME);
        captureNicknameRepaint(oldRepaint, owner);
        assertTrue(currentNicknameRepaint(oldRepaint, owner, "account-a", NICKNAME));
        SignedInUser.idForTests = "account-b";
        beginNicknameBind(owner, "account-b", NICKNAME);
        captureNicknameRepaint(newRepaint, owner);
        assertFalse(currentNicknameRepaint(oldRepaint, owner, "account-b", NICKNAME));
        assertTrue(currentNicknameRepaint(newRepaint, owner, "account-b", NICKNAME));
        beginNicknameBind(owner, "account-b", NICKNAME);
        assertFalse(currentNicknameRepaint(newRepaint, owner, "account-b", NICKNAME));
    }

    @Test public void repaintOwnershipIsPerOwnerAndComponentAndPreservesUnknownNativeIdentity() {
        Object owner = new Object(), other = new Object(), repaint = new Object();
        beginNicknameBind(owner, null, NAVBAR_NICKNAME);
        captureNicknameRepaint(repaint, owner);
        assertTrue(currentNicknameRepaint(repaint, owner, null, NAVBAR_NICKNAME));
        assertFalse(currentNicknameRepaint(repaint, owner, "account-a", NAVBAR_NICKNAME));
        assertFalse(currentNicknameRepaint(repaint, owner, null, NICKNAME));
        assertFalse(currentNicknameRepaint(repaint, other, null, NAVBAR_NICKNAME));
        assertFalse(currentNicknameRepaint(new Object(), owner, null, NAVBAR_NICKNAME));
    }

    @Test public void addNameKeepsNativeTextTagAndClickSpansAndNeverAccumulatesBadges() {
        Settings.LOCAL_PROFILE_VERIFIED_BADGE.save(true);
        for (String component : new String[]{NICKNAME, NAVBAR_NICKNAME}) {
            Object owner = new Object();
            SpannableStringBuilder original = new SpannableStringBuilder("+ Add name");
            ClickableSpan click = new ClickableSpan() { @Override public void onClick(View ignored) {} };
            Object tag = new Object();
            original.setSpan(click, 0, original.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            original.setSpan(tag, 2, original.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            ImageSpan badge = new ImageSpan(new ColorDrawable());
            SpannableStringBuilder local = badgeText(owner, original, badge, "account-a", component);
            assertEquals("+ Add name\u00a0\ufffc", local.toString());
            assertEquals("+ Add name", original.toString());
            assertEquals(0, local.getSpanStart(click));
            assertEquals(2, local.getSpanStart(tag));
            assertEquals(1, local.getSpans(0, local.length(), ImageSpan.class).length);
            assertEquals(0, original.getSpans(0, original.length(), ImageSpan.class).length);
            assertTrue(hasLocalNicknameText(owner));
            SpannableStringBuilder repeated = badgeText(owner, original, new ImageSpan(new ColorDrawable()), "account-a", component);
            assertEquals(local.toString(), repeated.toString());
            assertEquals(1, repeated.getSpans(0, repeated.length(), ImageSpan.class).length);
        }
    }

    @Test public void addNameReturnsTheSameNativeSpannableOnPauseAccountChangeAndNativeFactoryFailure() {
        Settings.LOCAL_PROFILE_VERIFIED_BADGE.save(true);
        Object owner = new Object();
        SpannableStringBuilder nativeText = new SpannableStringBuilder("native");
        ImageSpan badge = new ImageSpan(new ColorDrawable());
        assertNotSame(nativeText, badgeText(owner, nativeText, badge, "account-a", NAVBAR_NICKNAME));
        PausedProcess.set(true);
        assertSame(nativeText, badgeText(owner, nativeText, badge, "account-a", NAVBAR_NICKNAME));
        assertFalse(hasLocalNicknameText(owner));
        PausedProcess.set(false);
        SignedInUser.idForTests = "account-b";
        assertSame(nativeText, badgeText(owner, nativeText, badge, "account-a", NICKNAME));
        assertSame(nativeText, badgeText(owner, nativeText, null, "account-b", NICKNAME));
        assertSame(nativeText, badgeText(owner, nativeText, new Object(), "account-b", NICKNAME));
        Settings.LOCAL_PROFILE_VERIFIED_BADGE.save(false);
        assertSame(nativeText, badgeText(owner, nativeText, badge, "account-b", NICKNAME));
    }

    @Test public void addNameRechecksTheAccountAfterCopyingNativeSpans() {
        Settings.LOCAL_PROFILE_VERIFIED_BADGE.save(true);
        Object owner = new Object();
        SpannableStringBuilder nativeText = new SpannableStringBuilder("native") {
            @Override public <T> T[] getSpans(int start, int end, Class<T> type) {
                SignedInUser.idForTests = "account-b";
                return super.getSpans(start, end, type);
            }
        };
        assertSame(nativeText, badgeText(owner, nativeText, new ImageSpan(new ColorDrawable()), "account-a", NICKNAME));
        assertFalse(hasLocalNicknameText(owner));
    }

    private void assertNative() {
        assertSame(Boolean.FALSE, legacyBadge(Boolean.FALSE, "account-a", LEGACY_NICKNAME));
        assertNull(resource(null, "account-a", NICKNAME));
        View root = view();
        assertEquals(View.VISIBLE, noticeVisibility(View.VISIBLE, root, root, "account-a", RESTRICTION_NOTICE));
    }

    private static Integer resource(Integer nativeBadge, String viewedUid, String component) {
        return badgeResource(nativeBadge, true, viewedUid, component);
    }

    private static View view() { return new View(RuntimeEnvironment.getApplication()); }
}
