package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Looper;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.main.MainActivity;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * Exercises sizing before a native-style builder's line breaking. The host binder/register tests
 * separately verify the resolved fields and sites. Native feed gestures and rendering need devices.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FeedDescriptionSizeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private int previousDescription;
    private int previousAuthor;
    private int previousSubtitles;

    @Before public void setUp() {
        FeedTextSize.resetForTests();
        PausedProcess.set(false);
        previousDescription = Settings.FEED_DESCRIPTION_TEXT_SIZE.savedValue();
        previousAuthor = Settings.FEED_AUTHOR_TEXT_SIZE.savedValue();
        previousSubtitles = Settings.CAPTION_TEXT_SIZE.savedValue();
        Settings.FEED_DESCRIPTION_TEXT_SIZE.save(0);
        Settings.FEED_AUTHOR_TEXT_SIZE.save(0);
        Settings.CAPTION_TEXT_SIZE.save(0);
        FeedTextSize.nativeForTests = new FeedTextSize.Native() {
            @Override public View descriptionViewOf(Object owner) { return ((Owner) owner).view; }
            @Override public TextView authorViewOf(Object owner) { return null; }
            @Override public void resizeDescriptionBuilder(Object builder, View view) {
                Builder input = (Builder) builder;
                if (FeedTextSize.descriptionPaint(input.paint, view)) input.cacheEnabled = false;
            }
            @Override public void refreshDescription(Object owner) { ((Owner) owner).bind(); }
            @Override public void refreshAuthor(Object owner, Object item) { }
        };
    }

    @After public void tearDown() {
        FeedTextSize.resetForTests();
        PausedProcess.set(false);
        Settings.FEED_DESCRIPTION_TEXT_SIZE.save(previousDescription);
        Settings.FEED_AUTHOR_TEXT_SIZE.save(previousAuthor);
        Settings.CAPTION_TEXT_SIZE.save(previousSubtitles);
    }

    @Test public void nativeLineLimitsEllipsisWeightAndClickableSpansSurviveIndependentSizing() {
        try (var activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            Owner owner = attach(activity.get());
            Settings.FEED_AUTHOR_TEXT_SIZE.save(35);
            Settings.CAPTION_TEXT_SIZE.save(48);
            owner.bind();
            assertEquals(16, owner.expanded.getPaint().getTextSize(), 0);
            assertTrue(owner.expandedInput.cacheEnabled);
            Typeface nativeWeight = owner.expanded.getPaint().getTypeface();
            int nativeLines = owner.expanded.getLineCount();
            AtomicInteger clicks = new AtomicInteger();
            owner.view.setOnClickListener(view -> clicks.incrementAndGet());
            ClickableSpan mention = owner.source.getSpans(0, owner.source.length(), ClickableSpan.class)[0];
            StyleSpan bold = owner.source.getSpans(0, owner.source.length(), StyleSpan.class)[0];

            Settings.FEED_DESCRIPTION_TEXT_SIZE.save(26);
            FeedTextSize.applyTo(activity.get());
            assertEquals(26, owner.expanded.getPaint().getTextSize(), 0);
            assertEquals(26, owner.collapsed.getPaint().getTextSize(), 0);
            assertEquals(2, owner.collapsed.getLineCount());
            assertTrue(owner.collapsed.getEllipsisCount(1) > 0);
            assertTrue(owner.expanded.getLineCount() > nativeLines);
            assertFalse(owner.expandedInput.cacheEnabled);
            assertFalse(owner.collapsedInput.cacheEnabled);
            assertSame(nativeWeight, owner.expanded.getPaint().getTypeface());
            assertEquals(0xffaabbcc, owner.expanded.getPaint().linkColor);
            Spanned shown = (Spanned) owner.expanded.getText();
            assertSame(mention, shown.getSpans(0, shown.length(), ClickableSpan.class)[0]);
            assertSame(bold, shown.getSpans(0, shown.length(), StyleSpan.class)[0]);
            mention.onClick(owner.view);
            assertTrue(owner.view.performClick());
            assertEquals(1, clicks.get());
            assertEquals(1, owner.mentionClicks.get());
            assertEquals(14, owner.subtitlePaint.getTextSize(), 0);
            assertEquals(15, owner.unrelated.getTextSize(), 0);

            Settings.FEED_DESCRIPTION_TEXT_SIZE.save(0);
            FeedTextSize.applyTo(activity.get());
            assertEquals(16, owner.expanded.getPaint().getTextSize(), 0);
            assertEquals(nativeLines, owner.expanded.getLineCount());
            assertTrue(owner.expandedInput.cacheEnabled);
            assertSame(nativeWeight, owner.expanded.getPaint().getTypeface());
        }
    }

    @Test public void offPauseAndRecycledBindingsRebuildFromTheCurrentNativeFont() {
        try (var activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            Owner owner = attach(activity.get());
            Settings.FEED_DESCRIPTION_TEXT_SIZE.save(24);
            owner.bind();
            int before = owner.binds;
            FeedTextSize.applyTo(activity.get());
            assertEquals("An unchanged setting must not keep rebinding", before, owner.binds);
            owner.nativeSize = 19;
            owner.source = owner.caption("A recycled description @creator 😊 ");
            owner.bind();
            assertEquals(24, owner.expanded.getPaint().getTextSize(), 0);
            PausedProcess.set(true);
            FeedTextSize.onSettingChanged();
            idle();
            assertEquals(19, owner.expanded.getPaint().getTextSize(), 0);
            assertTrue(owner.expandedInput.cacheEnabled);
            PausedProcess.set(false);
            FeedTextSize.onSettingChanged();
            idle();
            assertEquals(24, owner.expanded.getPaint().getTextSize(), 0);
            Settings.FEED_DESCRIPTION_TEXT_SIZE.save(0);
            FeedTextSize.onSettingChanged();
            idle();
            assertEquals(19, owner.collapsed.getPaint().getTextSize(), 0);
            assertEquals(19, owner.expanded.getPaint().getTextSize(), 0);
        }
    }

    @Test @Config(fontScale = 2f)
    public void enlargedAndroidFontsReachBothInputsBeforeLongEmojiAndMentionLayout() {
        try (var activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            Owner owner = attach(activity.get());
            assertEquals(2f, owner.view.getResources().getConfiguration().fontScale, 0);
            owner.bind();
            assertEquals(32, owner.expanded.getPaint().getTextSize(), 0);
            Settings.FEED_DESCRIPTION_TEXT_SIZE.save(24);
            FeedTextSize.applyTo(activity.get());
            assertEquals(48, owner.expanded.getPaint().getTextSize(), 0);
            assertEquals(48, owner.collapsed.getPaint().getTextSize(), 0);
            assertEquals(2, owner.collapsed.getLineCount());
            assertTrue(owner.collapsed.getEllipsisCount(1) > 0);
            assertTrue(owner.expanded.getLineCount() > 2);
            Settings.FEED_DESCRIPTION_TEXT_SIZE.save(0);
            FeedTextSize.applyTo(activity.get());
            assertEquals(32, owner.expanded.getPaint().getTextSize(), 0);
        }
    }

    @Test public void nativeFittingReappliesOnlyMarkedBuildersAndCachesRebuildOnRestoration() {
        try (var activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            Owner owner = attach(activity.get());
            assertFalse(FeedTextSize.freshDescription(owner));
            owner.bind();
            Settings.FEED_DESCRIPTION_TEXT_SIZE.save(24);
            FeedTextSize.onSettingChanged();
            idle();
            assertTrue(FeedTextSize.freshDescription(owner));
            // Native fitting writes an input between build attempts. The next native entry
            // reapplies the owned size; a builder used by another renderer stays untouched.
            owner.collapsedInput.paint.setTextSize(13);
            FeedTextSize.descriptionBuilding(owner.collapsedInput);
            assertEquals(24, owner.collapsedInput.paint.getTextSize(), 0);
            Builder unrelated = new Builder();
            unrelated.paint.setTextSize(13);
            FeedTextSize.descriptionBuilding(unrelated);
            assertEquals(13, unrelated.paint.getTextSize(), 0);
            assertTrue(unrelated.cacheEnabled);
            Settings.FEED_DESCRIPTION_TEXT_SIZE.save(0);
            FeedTextSize.onSettingChanged();
            idle();
            assertTrue("Off must bypass the controller cache for the restoration build", owner.lastFresh);
            assertFalse("Later zero-size binds retain native caches", FeedTextSize.freshDescription(owner));
            assertEquals(16, owner.expandedInput.paint.getTextSize(), 0);
            assertTrue(owner.expandedInput.cacheEnabled);
        }
    }

    @Test public void replacingTheControllersViewDoesNotRefreshFromItsStaleAttachedView() {
        try (var activity = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            Owner owner = attach(activity.get());
            owner.bind();
            View stale = owner.view;
            int binds = owner.binds;
            // The native view loader changes its field before a replacement cell is bound.
            owner.view = new View(activity.get());
            assertTrue(stale.isAttachedToWindow());
            assertFalse(owner.view.isAttachedToWindow());
            Settings.FEED_DESCRIPTION_TEXT_SIZE.save(24);
            FeedTextSize.onSettingChanged();
            idle();
            assertEquals("The old View no longer belongs to this controller", binds, owner.binds);
            assertEquals(16, owner.expanded.getPaint().getTextSize(), 0);
            activity.get().setContentView(owner.view);
            owner.bind();
            assertEquals(24, owner.expanded.getPaint().getTextSize(), 0);
        }
    }

    @Test public void aReplacementViewOnTheNativeCachePathRemainsTrackedForTheNextSizeChange() {
        try (var activity = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            Owner owner = attach(activity.get());
            owner.bind();
            View stale = owner.view;
            owner.view = new View(activity.get());
            activity.get().setContentView(owner.view);
            assertFalse(stale.isAttachedToWindow());
            // A zero-size dispatch may reuse its native layout without constructing a builder.
            assertFalse(FeedTextSize.freshDescription(owner));
            int binds = owner.binds;
            Settings.FEED_DESCRIPTION_TEXT_SIZE.save(24);
            FeedTextSize.onSettingChanged();
            idle();
            assertEquals(binds + 1, owner.binds);
            assertEquals(24, owner.expanded.getPaint().getTextSize(), 0);
            Settings.FEED_DESCRIPTION_TEXT_SIZE.save(0);
            FeedTextSize.onSettingChanged();
            idle();
            assertEquals(16, owner.expanded.getPaint().getTextSize(), 0);
        }
    }

    private static Owner attach(Activity activity) {
        Owner owner = new Owner(activity);
        LinearLayout root = new LinearLayout(activity);
        root.addView(owner.view);
        root.addView(owner.unrelated);
        activity.setContentView(root);
        return owner;
    }

    private static final class Builder {
        final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        boolean cacheEnabled = true;
    }

    private static final class Owner {
        View view;
        final TextView unrelated;
        final TextPaint subtitlePaint = new TextPaint();
        final AtomicInteger mentionClicks = new AtomicInteger();
        SpannableString source;
        int nativeSize = 16;
        int binds;
        boolean lastFresh;
        Builder collapsedInput;
        Builder expandedInput;
        Layout collapsed;
        Layout expanded;

        Owner(Activity activity) {
            view = new View(activity);
            unrelated = new TextView(activity);
            unrelated.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15);
            subtitlePaint.setTextSize(14);
            source = caption("@creator 😊 Native mentions, emoji and a long description. ");
        }

        SpannableString caption(String start) {
            SpannableString result = new SpannableString(start +
                    "This caption keeps going so the original native two-line limit needs an ellipsis. ".repeat(5));
            int mention = result.toString().indexOf("@creator");
            result.setSpan(new ClickableSpan() {
                @Override public void onClick(View clicked) { mentionClicks.incrementAndGet(); }
            }, mention, mention + 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            result.setSpan(new StyleSpan(Typeface.BOLD), 0, result.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return result;
        }

        void bind() {
            lastFresh = FeedTextSize.freshDescription(this);
            binds++;
            collapsedInput = input();
            collapsed = StaticLayout.Builder.obtain(source, 0, source.length(), collapsedInput.paint, 180)
                    .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setEllipsizedWidth(180).build();
            expandedInput = input();
            expanded = StaticLayout.Builder.obtain(source, 0, source.length(), expandedInput.paint, 180).build();
        }

        Builder input() {
            Builder builder = new Builder();
            builder.paint.setTypeface(Typeface.DEFAULT_BOLD);
            builder.paint.linkColor = 0xffaabbcc;
            builder.paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, nativeSize,
                    view.getResources().getDisplayMetrics()));
            FeedTextSize.descriptionBuilder(this, builder);
            return builder;
        }
    }

    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
}
