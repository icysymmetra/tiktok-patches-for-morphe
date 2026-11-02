/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.feed.AuthorRegion;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import com.ss.android.ugc.aweme.profile.model.User;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * What the comment cell's name, reply label and time end up saying. Each hook gets the text
 * TikTok just picked or wrote, the way the cell hands it over on 47.1.4.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommenterDetailsTest {
    /** Stands in for TikTok's comment model: the fields the hooks read, by their real names. */
    public static final class Remark {
        public User user;
        public String replyToUserId;
        public Object commerceInfo;

        Remark(User user) {
            this.user = user;
        }
    }

    /** A time view that counts how often its text is written, not only what it says. */
    private static final class TimeView extends TextView {
        int writes;

        TimeView(Context context) {
            super(context);
        }

        @Override
        public void setText(CharSequence text, BufferType type) {
            super.setText(text, type);
            writes++;
        }

        /** TikTok's own write of the time, which the hook runs right after. */
        void tikTokWrites(String time) {
            setText(time);
            writes = 0;
        }
    }

    private Context context;
    private boolean patched;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        patched = SettingsStatus.commenterDetailsEnabled;
        SettingsStatus.commenterDetailsEnabled = true;
        CommenterDetails.forget();
    }

    @After
    public void tearDown() {
        Settings.SHOW_COMMENTER_HANDLE.save(false);
        Settings.SHOW_COMMENTER_REGION.save(false);
        Settings.COLOR_COMMENTER_NAMES.save(false);
        SettingsStatus.commenterDetailsEnabled = patched;
        CommenterDetails.forget();
    }

    private static User account(String uid, String handle, String nickname, String region) {
        User user = new User();
        user.uid = uid;
        user.uniqueId = handle;
        user.nickname = nickname;
        user.region = region;
        return user;
    }

    private static Remark by(String uid, String handle, String region) {
        return new Remark(account(uid, handle, "Display " + uid, region));
    }

    private TimeView timeSaying(String time) {
        TimeView view = new TimeView(context);
        view.tikTokWrites(time);
        return view;
    }

    @Test
    public void switchesOffLeaveTikTokTextAlone() {
        Remark remark = by("1", "river.otter", "US");
        remark.replyToUserId = "1";
        CharSequence name = "River";
        TimeView time = timeSaying("2d ago");

        assertSame(name, CommenterDetails.commenterName(remark, name));
        assertSame(name, CommenterDetails.replyTargetName(remark, name));
        CommenterDetails.onCommentTime(remark, time);

        assertEquals("2d ago", time.getText().toString());
        assertEquals(0, time.writes);
    }

    /** A name view as TikTok's helper leaves it: its theme color, then the name. */
    private TextView nameIn(int tikTokColor) {
        TextView view = new TextView(context);
        view.setTextColor(tikTokColor);
        view.setText("River");
        return view;
    }

    @Test
    public void eachAccountKeepsOneNameColorAndTheSwitchOffGivesTikToksBack() {
        int lightSheet = 0xFF161823;
        TextView name = nameIn(lightSheet);
        CommenterDetails.onCommenterName(by("1", "river.otter", null), name);
        assertEquals("the switch starts off", lightSheet, name.getCurrentTextColor());

        Settings.COLOR_COMMENTER_NAMES.save(true);
        CommenterDetails.onCommenterName(by("1", "river.otter", null), name);
        int river = name.getCurrentTextColor();
        assertNotEquals(lightSheet, river);
        assertEquals(river, (int) CommenterDetails.nameColor(by("1", "other.handle", null), lightSheet));
        // The handle stands in for a payload without a uid, and nothing at all leaves TikTok's color.
        assertEquals(CommenterDetails.nameColor(new Remark(account(null, "sam", "Sam", null)), lightSheet),
                CommenterDetails.nameColor(new Remark(account("", "sam", "Sam", null)), lightSheet));
        assertNull(CommenterDetails.nameColor(new Remark(account(null, null, "Sam", null)), lightSheet));
        assertNull(CommenterDetails.nameColor(new Remark(null), lightSheet));

        // Eight hues, so twenty accounts can't all land on one.
        java.util.Set<Integer> hues = new java.util.HashSet<>();
        for (int uid = 0; uid < 20; uid++) {
            hues.add(CommenterDetails.nameColor(by(String.valueOf(uid), "a" + uid, null), lightSheet));
        }
        assertTrue(hues.size() > 3);

        // TikTok's dark sheet writes light names: the same account takes the lighter shade there.
        int darkSheet = 0xFFFFFFFF;
        int riverOnDark = CommenterDetails.nameColor(by("1", "river.otter", null), darkSheet);
        assertNotEquals(river, riverOnDark);
        assertTrue("a dark sheet got a dark name", android.graphics.Color.red(riverOnDark)
                + android.graphics.Color.green(riverOnDark) + android.graphics.Color.blue(riverOnDark) > 3 * 128);

        // A recycled cell whose bind wrote no color of its own still has TikTok's under ours.
        CommenterDetails.onCommenterName(by("2", "bob", null), name);
        Settings.COLOR_COMMENTER_NAMES.save(false);
        CommenterDetails.onCommenterName(by("2", "bob", null), name);
        assertEquals(lightSheet, name.getCurrentTextColor());
        name.setTextColor(darkSheet);
        CommenterDetails.onCommenterName(by("2", "bob", null), name);
        assertEquals("a color TikTok wrote was taken back", darkSheet, name.getCurrentTextColor());
    }

    @Test
    public void aNameColorLeavesTheNameTextAsAString() {
        // TikTok's helper casts the name to a String on one path, so the color never rides in it.
        Settings.COLOR_COMMENTER_NAMES.save(true);
        CharSequence name = "River";
        assertSame(name, CommenterDetails.commenterName(by("1", "river.otter", null), name));
        TextView view = nameIn(0xFF161823);
        CommenterDetails.onCommenterName(by("1", "river.otter", null), view);
        assertEquals("River", view.getText().toString());
        assertFalse(view.getText() instanceof android.text.Spanned);
        CommenterDetails.onCommenterName(by("1", "river.otter", null), null);
    }

    @Test
    public void aBuildWithoutThePatchIgnoresSavedSwitches() {
        Settings.SHOW_COMMENTER_HANDLE.save(true);
        Settings.SHOW_COMMENTER_REGION.save(true);
        SettingsStatus.commenterDetailsEnabled = false;
        Remark remark = by("1", "river.otter", "US");
        CharSequence name = "River";
        TimeView time = timeSaying("2d ago");

        assertSame(name, CommenterDetails.commenterName(remark, name));
        CommenterDetails.onCommentTime(remark, time);

        assertEquals("2d ago", time.getText().toString());
        assertEquals(0, time.writes);
    }

    @Test
    public void theHandleReplacesTheDisplayName() {
        Settings.SHOW_COMMENTER_HANDLE.save(true);

        assertEquals("@river.otter", CommenterDetails.commenterName(by("1", "river.otter", null), "River").toString());
        // A payload that keeps the at sign gets one, not two.
        assertEquals("@sam", CommenterDetails.commenterName(by("2", " @sam ", null), "Sam").toString());
    }

    @Test
    public void noHandleOrAnAdKeepsTheName() {
        Settings.SHOW_COMMENTER_HANDLE.save(true);
        CharSequence name = "Brand";

        assertSame(name, CommenterDetails.commenterName(by("1", "  ", null), name));
        assertSame(name, CommenterDetails.commenterName(new Remark(null), name));
        assertSame(name, CommenterDetails.commenterName(null, name));
        Remark ad = by("2", "brand.account", null);
        ad.commerceInfo = new Object();
        assertSame(name, CommenterDetails.commenterName(ad, name));
    }

    @Test
    public void aReplyLabelGetsTheHandleOnceThatPersonsCommentWasDrawn() {
        Settings.SHOW_COMMENTER_HANDLE.save(true);
        Remark reply = by("9", "replier", null);
        reply.replyToUserId = "42";

        // Nothing learned about uid 42 yet, so TikTok's name stays rather than a guess.
        assertSame("Bobby", CommenterDetails.replyTargetName(reply, "Bobby"));

        CommenterDetails.commenterName(by("42", "bob", null), "Bobby");
        assertEquals("@bob", CommenterDetails.replyTargetName(reply, "Bobby").toString());

        reply.replyToUserId = "7";
        assertSame("Someone", CommenterDetails.replyTargetName(reply, "Someone"));
    }

    @Test
    public void aReplyLabelStaysWhenTheHandleSwitchIsOff() {
        Settings.SHOW_COMMENTER_HANDLE.save(true);
        CommenterDetails.commenterName(by("42", "bob", null), "Bobby");
        Settings.SHOW_COMMENTER_HANDLE.save(false);
        Remark reply = by("9", "replier", null);
        reply.replyToUserId = "42";

        assertSame("Bobby", CommenterDetails.replyTargetName(reply, "Bobby"));
    }

    @Test
    public void theRegionFollowsTheTime() {
        Settings.SHOW_COMMENTER_REGION.save(true);
        TimeView time = timeSaying("2d ago");

        CommenterDetails.onCommentTime(by("1", "a", " us "), time);

        assertEquals("2d ago · US", time.getText().toString());
    }

    /** #157: with the flag switch on, the commenter's country shows as its flag. */
    @Test
    public void theRegionCanFollowTheTimeAsAFlag() {
        Settings.SHOW_COMMENTER_REGION.save(true);
        Settings.REGION_AS_FLAG.save(true);
        try {
            TimeView time = timeSaying("2d ago");

            CommenterDetails.onCommentTime(by("1", "a", " ee "), time);

            assertEquals("2d ago · 🇪🇪", time.getText().toString());
        } finally {
            Settings.REGION_AS_FLAG.resetToDefault();
        }
    }

    @Test
    public void theAccountRegionStandsInForAMissingRegion() {
        Settings.SHOW_COMMENTER_REGION.save(true);
        Remark remark = by("1", "a", null);
        remark.user.accountRegion = "de";
        TimeView time = timeSaying("5h ago");

        CommenterDetails.onCommentTime(remark, time);

        assertEquals("5h ago · DE", time.getText().toString());
    }

    @Test
    public void noRegionLeavesTheTimeAlone() {
        Settings.SHOW_COMMENTER_REGION.save(true);
        TimeView time = timeSaying("2d ago");

        CommenterDetails.onCommentTime(by("1", "a", null), time);
        CommenterDetails.onCommentTime(new Remark(null), time);
        CommenterDetails.onCommentTime(null, time);

        assertEquals("2d ago", time.getText().toString());
        assertEquals(0, time.writes);
    }

    @Test
    public void aRecycledCellNeverKeepsTheLastCommentsRegion() {
        Settings.SHOW_COMMENTER_REGION.save(true);
        TimeView time = timeSaying("2d ago");
        CommenterDetails.onCommentTime(by("1", "a", "US"), time);
        assertEquals("2d ago · US", time.getText().toString());

        // The cell is rebound to a comment from an account with no country.
        time.tikTokWrites("5h ago");
        CommenterDetails.onCommentTime(by("2", "b", null), time);
        assertEquals("5h ago", time.getText().toString());

        // And again, to one from another country.
        time.tikTokWrites("1w ago");
        CommenterDetails.onCommentTime(by("3", "c", "GB"), time);
        assertEquals("1w ago · GB", time.getText().toString());
    }

    @Test
    public void aSecondCallOverTheSameWriteDoesNotStackTheRegion() {
        Settings.SHOW_COMMENTER_REGION.save(true);
        TimeView time = timeSaying("2d ago");
        Remark remark = by("1", "a", "US");

        CommenterDetails.onCommentTime(remark, time);
        CommenterDetails.onCommentTime(remark, time);
        assertEquals("2d ago · US", time.getText().toString());
        assertEquals(1, time.writes);

        // Turned off with the stamp still showing: the time goes back to TikTok's own.
        Settings.SHOW_COMMENTER_REGION.save(false);
        CommenterDetails.onCommentTime(remark, time);
        assertEquals("2d ago", time.getText().toString());
    }

    @Test
    public void anEmptyTimeGetsTheRegionOnItsOwn() {
        Settings.SHOW_COMMENTER_REGION.save(true);
        TimeView time = timeSaying("");

        CommenterDetails.onCommentTime(by("1", "a", "FR"), time);

        assertEquals("FR", time.getText().toString());
    }

    @Test
    public void aCommenterWithNoRegionGetsTheCountryTheFeedSawForTheirHandle() {
        Settings.SHOW_COMMENTER_REGION.save(true);
        AuthorRegion.rememberSeen("River.Otter", "us");
        TimeView time = timeSaying("2d ago");

        CommenterDetails.onCommentTime(by("1", "@river.otter", null), time);

        assertEquals("2d ago · US", time.getText().toString());
    }

    @Test
    public void theAccountsOwnRegionBeatsTheFeedsAndARecycledCellNeverShowsAnothersCountry() {
        Settings.SHOW_COMMENTER_REGION.save(true);
        AuthorRegion.rememberSeen("river.otter", "US");
        TimeView time = timeSaying("2d ago");
        CommenterDetails.onCommentTime(by("1", "river.otter", "GB"), time);
        assertEquals("2d ago · GB", time.getText().toString());

        // The cell is rebound to a commenter the feed never showed, then to one with no handle.
        time.tikTokWrites("5h ago");
        CommenterDetails.onCommentTime(by("2", "someone.else", null), time);
        assertEquals("5h ago", time.getText().toString());
        time.tikTokWrites("1w ago");
        CommenterDetails.onCommentTime(by("3", null, null), time);
        assertEquals("1w ago", time.getText().toString());

        // And back to the one the feed did show.
        time.tikTokWrites("1d ago");
        CommenterDetails.onCommentTime(by("4", "river.otter", null), time);
        assertEquals("1d ago · US", time.getText().toString());
    }

    /** TikTok's setter for one bind: the name it picked through the swap, then the view's hook. */
    private static void bindName(Remark remark, CharSequence tikTokName, TextView view) {
        view.setText(CommenterDetails.commenterName(remark, tikTokName));
        CommenterDetails.onCommenterName(remark, view);
    }

    private static void flip(boolean on) {
        Settings.SHOW_COMMENTER_HANDLE.save(on);
        Settings.SHOW_COMMENTER_REGION.save(on);
        Settings.COLOR_COMMENTER_NAMES.save(on);
        CommenterDetails.onSettingChanged();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void aSwitchFlipRedoesTheCellsTikTokWontBindAgain() {
        int grey = 0xFF8A8B91;
        Remark remark = by("1", "river.otter", "US");
        TextView name = nameIn(grey);
        bindName(remark, "River", name);
        // TikTok's verified badge goes after the name as an image span.
        SpannableStringBuilder badged = new SpannableStringBuilder(name.getText()).append(" T");
        badged.setSpan(new ImageSpan(new ColorDrawable(Color.BLUE)), badged.length() - 1, badged.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        name.setText(badged);
        TimeView time = timeSaying("2d ago");
        CommenterDetails.onCommentTime(remark, time);

        // The list is reopened for the same video after the flip, and TikTok writes nothing.
        flip(true);
        assertEquals("@river.otter T", name.getText().toString());
        Spanned shown = (Spanned) name.getText();
        ImageSpan[] badges = shown.getSpans(0, shown.length(), ImageSpan.class);
        assertEquals("the badge stays behind the name", 1, badges.length);
        assertEquals(shown.length() - 1, shown.getSpanStart(badges[0]));
        assertNotEquals(grey, name.getCurrentTextColor());
        assertEquals("2d ago · US", time.getText().toString());

        flip(false);
        assertEquals("River T", name.getText().toString());
        assertEquals(grey, name.getCurrentTextColor());
        assertEquals("2d ago", time.getText().toString());

        // The cell is recycled for another commenter; the flip follows the comment it holds now.
        Remark other = by("2", "lake.heron", "GB");
        bindName(other, "Heron", name);
        flip(true);
        assertEquals("@lake.heron", name.getText().toString());
        assertEquals((int) CommenterDetails.nameColor(other, grey), name.getCurrentTextColor());
    }

    @Test
    public void aSwitchFlipRedoesAReplyLabelTikTokWontBindAgain() {
        // Drawn with handles off, so nothing about uid 42 is known before the flip.
        bindName(by("42", "bob", null), "Bobby", nameIn(0xFF8A8B91));
        Remark reply = by("9", "replier", null);
        reply.replyToUserId = "42";
        TextView label = new TextView(context);
        label.setText(CommenterDetails.replyTargetName(reply, "Bobby"));
        CommenterDetails.onReplyLabel(reply, label);
        assertEquals("Bobby", label.getText().toString());

        // The name above it is redone first and learns the handle the label is named from.
        flip(true);
        assertEquals("@bob", label.getText().toString());

        flip(false);
        assertEquals("Bobby", label.getText().toString());

        // A label whose write didn't go through the swap isn't one this can redo.
        TextView unswapped = new TextView(context);
        unswapped.setText("Bobby");
        CommenterDetails.onReplyLabel(reply, unswapped);
        flip(true);
        assertEquals("Bobby", unswapped.getText().toString());
        assertEquals("@bob", label.getText().toString());
    }

    /**
     * TikTok's helper with a translucent color and emoji in the name: the view takes the opaque
     * color, and the translucent one goes on as spans over the text before the emoji.
     */
    private static void tikTokWritesEmojiName(TextView view, String name, int translucent, int opaque) {
        view.setTextColor(opaque);
        SpannableStringBuilder text = new SpannableStringBuilder(name);
        int emoji = name.indexOf('\uD83D');
        text.setSpan(new ForegroundColorSpan(translucent), 0, emoji, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        view.setText(text);
    }

    private static int shownColorAt(TextView view, int index) {
        CharSequence text = view.getText();
        int color = view.getCurrentTextColor();
        if (text instanceof Spanned) {
            for (ForegroundColorSpan span : ((Spanned) text).getSpans(index, index + 1, ForegroundColorSpan.class)) {
                color = span.getForegroundColor(); // the last one set draws on top
            }
        }
        return color;
    }

    @Test
    public void anEmojiNameTakesOurColorOverTikToksSpansAndGetsThemBack() {
        int translucent = 0x99161823;
        int opaque = 0xFF161823;
        String name = "Maddie Media 💫";
        Remark remark = by("1", "maddie.media", null);
        TextView view = new TextView(context);
        CommenterDetails.commenterName(remark, name);
        tikTokWritesEmojiName(view, name, translucent, opaque);
        CommenterDetails.onCommenterName(remark, view);
        assertEquals(translucent, shownColorAt(view, 0));

        Settings.COLOR_COMMENTER_NAMES.save(true);
        CommenterDetails.onSettingChanged();
        shadowOf(Looper.getMainLooper()).idle();
        int ours = CommenterDetails.nameColor(remark, opaque);
        assertEquals("ours draws over TikTok's span", ours, shownColorAt(view, 0));

        Settings.COLOR_COMMENTER_NAMES.save(false);
        Settings.SHOW_COMMENTER_HANDLE.save(true);
        CommenterDetails.onSettingChanged();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("@maddie.media", view.getText().toString());
        assertEquals("the handle keeps TikTok's color", translucent, shownColorAt(view, 0));

        Settings.SHOW_COMMENTER_HANDLE.save(false);
        CommenterDetails.onSettingChanged();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(name, view.getText().toString());
        assertEquals("TikTok's grey, not its opaque view color", translucent, shownColorAt(view, 0));
        assertEquals(opaque, view.getCurrentTextColor());
        Spanned back = (Spanned) view.getText();
        assertEquals(0, back.getSpans(0, back.length(), CommenterDetails.NameColorSpan.class).length);

        // A fresh bind with the switch on colors the emoji name too.
        Settings.COLOR_COMMENTER_NAMES.save(true);
        CommenterDetails.commenterName(remark, name);
        tikTokWritesEmojiName(view, name, translucent, opaque);
        CommenterDetails.onCommenterName(remark, view);
        assertEquals(ours, shownColorAt(view, 0));
    }

    @Test
    public void aNameTikTokRewroteSinceIsLeftAlone() {
        Remark remark = by("1", "river.otter", "US");
        TextView name = nameIn(0xFF8A8B91);
        bindName(remark, "River", name);
        name.setText("Someone else");

        flip(true);

        assertEquals("Someone else", name.getText().toString());
    }

    @Test
    public void theFeedCacheKeepsOnlyTheLast256CreatorsAndTheMostRecentlyUsedSurvive() {
        AuthorRegion.rememberSeen("keeper", "FR");
        for (int i = 0; i < 300; i++) {
            AuthorRegion.rememberSeen("creator" + i, "US");
            if (i % 50 == 0) assertEquals("FR", AuthorRegion.seenRegion("keeper"));
        }
        assertEquals("FR", AuthorRegion.seenRegion("keeper"));
        assertEquals(null, AuthorRegion.seenRegion("creator0"));
        assertEquals("US", AuthorRegion.seenRegion("creator299"));
    }
}
