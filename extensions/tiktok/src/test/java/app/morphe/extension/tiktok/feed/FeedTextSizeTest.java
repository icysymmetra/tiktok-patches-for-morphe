package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.method.MovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.detail.ui.DetailActivity;
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

/** Drives the author owner's before/after-bind contract without substituting a generic title lookup. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "mdpi")
public class FeedTextSizeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private int previousAuthor;
    private int previousDescription;
    private int previousCaption;

    @Before public void setUp() {
        FeedTextSize.resetForTests();
        PausedProcess.set(false);
        previousAuthor = Settings.FEED_AUTHOR_TEXT_SIZE.savedValue();
        previousDescription = Settings.FEED_DESCRIPTION_TEXT_SIZE.savedValue();
        previousCaption = Settings.CAPTION_TEXT_SIZE.savedValue();
        Settings.FEED_AUTHOR_TEXT_SIZE.save(0);
        Settings.FEED_DESCRIPTION_TEXT_SIZE.save(0);
        Settings.CAPTION_TEXT_SIZE.save(0);
    }

    @After public void reset() {
        FeedTextSize.resetForTests();
        PausedProcess.set(false);
        Settings.FEED_AUTHOR_TEXT_SIZE.save(previousAuthor);
        Settings.FEED_DESCRIPTION_TEXT_SIZE.save(previousDescription);
        Settings.CAPTION_TEXT_SIZE.save(previousCaption);
    }

    @Test public void authorSizeKeepsSpansWeightTruncationAndTapsAndLeavesOtherTextAlone() {
        try (var owner = Robolectric.buildActivity(MainActivity.class).setup()) {
            Activity activity = owner.get();
            LinearLayout root = new LinearLayout(activity);
            TextView author = text(activity, 17, "A creator");
            TextView unrelatedTitle = text(activity, 15, "A comment title");
            TextView description = text(activity, 16, "Description @mention 😊");
            TextView spoken = text(activity, 14, "Spoken subtitles");
            root.addView(author);
            root.addView(unrelatedTitle);
            root.addView(description);
            root.addView(spoken);
            activity.setContentView(root);
            AtomicInteger clicks = new AtomicInteger();
            ClickableSpan link = new ClickableSpan() {
                @Override public void onClick(View view) { clicks.incrementAndGet(); }
            };
            StyleSpan weight = new StyleSpan(Typeface.BOLD);
            SpannableString source = new SpannableString("A long creator name @mention 😊");
            source.setSpan(link, 20, 28, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            source.setSpan(weight, 0, source.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            author.setText(source);
            author.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            Typeface nativeTypeface = author.getTypeface();
            author.setMaxLines(2);
            author.setEllipsize(TextUtils.TruncateAt.END);
            author.setMovementMethod(LinkMovementMethod.getInstance());
            author.setOnClickListener(view -> clicks.incrementAndGet());
            // A movement method swaps in a Spannable copy, so the baseline is taken after it.
            CharSequence nativeText = author.getText();
            MovementMethod nativeMovement = author.getMovementMethod();
            float nativeSize = author.getTextSize();
            FeedTextSize.authorBound(author);
            Settings.FEED_DESCRIPTION_TEXT_SIZE.save(40);
            Settings.CAPTION_TEXT_SIZE.save(48);
            FeedTextSize.applyTo(activity);
            assertEquals("Independent settings must not resize the author", nativeSize, author.getTextSize(), 0);
            Settings.FEED_AUTHOR_TEXT_SIZE.save(24);
            FeedTextSize.applyTo(activity);
            assertEquals(24, author.getTextSize(), 0);
            assertSame(nativeText, author.getText());
            assertSame(nativeTypeface, author.getTypeface());
            assertEquals(2, author.getMaxLines());
            assertSame(TextUtils.TruncateAt.END, author.getEllipsize());
            assertSame(nativeMovement, author.getMovementMethod());
            Spanned spans = (Spanned) author.getText();
            assertSame(link, spans.getSpans(0, spans.length(), ClickableSpan.class)[0]);
            assertSame(weight, spans.getSpans(0, spans.length(), StyleSpan.class)[0]);
            link.onClick(author);
            assertTrue(author.performClick());
            assertEquals(2, clicks.get());
            assertEquals(15, unrelatedTitle.getTextSize(), 0);
            assertEquals(16, description.getTextSize(), 0);
            assertEquals(14, spoken.getTextSize(), 0);
            Settings.FEED_AUTHOR_TEXT_SIZE.save(0);
            FeedTextSize.applyTo(activity);
            assertEquals(nativeSize, author.getTextSize(), 0);
            assertSame(nativeText, author.getText());
        }
    }

    @Test public void recycledAuthorBindingCapturesTheNewNativeSizeEvenWhenItEqualsTheOverride() {
        try (var owner = Robolectric.buildActivity(MainActivity.class).setup()) {
            TextView author = text(owner.get(), 16, "First creator");
            Settings.FEED_AUTHOR_TEXT_SIZE.save(24);
            FeedTextSize.authorBound(author);
            assertEquals(24, author.getTextSize(), 0);
            FeedTextSize.beforeAuthorBind(author);
            assertEquals(16, author.getTextSize(), 0);
            // A native bind that replaces the name but leaves its size must not inherit 24.
            author.setText("Second creator");
            FeedTextSize.authorBound(author);
            Settings.FEED_AUTHOR_TEXT_SIZE.save(0);
            FeedTextSize.authorBound(author);
            assertEquals(16, author.getTextSize(), 0);
            Settings.FEED_AUTHOR_TEXT_SIZE.save(24);
            FeedTextSize.authorBound(author);
            FeedTextSize.beforeAuthorBind(author);
            author.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24);
            author.setText("Third creator, native size 24");
            FeedTextSize.authorBound(author);
            Settings.FEED_AUTHOR_TEXT_SIZE.save(0);
            FeedTextSize.authorBound(author);
            assertEquals("A coincident native size must become the restore baseline", 24, author.getTextSize(), 0);
            Settings.FEED_AUTHOR_TEXT_SIZE.save(32);
            FeedTextSize.authorBound(author);
            author.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19);
            Settings.FEED_AUTHOR_TEXT_SIZE.save(0);
            FeedTextSize.authorBound(author);
            assertEquals("An intervening native write must not be overwritten", 19, author.getTextSize(), 0);
        }
    }

    @Test public void multipleBoundCellsFollowMainAndDetailAndRestoreOnOffOrPause() {
        try (var main = Robolectric.buildActivity(MainActivity.class).setup().visible();
             var detail = Robolectric.buildActivity(DetailActivity.class).create().start()) {
            TextView[] feed = authors(main.get());
            Settings.FEED_AUTHOR_TEXT_SIZE.save(28);
            FeedTextSize.install(main.get());
            idle();
            layout(main.get());
            for (TextView author : feed) assertEquals(28, author.getTextSize(), 0);
            main.pause();
            TextView[] opened = authors(detail.get());
            detail.resume().visible();
            layout(detail.get());
            for (TextView author : opened) assertEquals(28, author.getTextSize(), 0);
            PausedProcess.set(true);
            layout(detail.get());
            assertNative(opened);
            PausedProcess.set(false);
            layout(detail.get());
            for (TextView author : opened) assertEquals(28, author.getTextSize(), 0);
            Settings.FEED_AUTHOR_TEXT_SIZE.save(0);
            layout(detail.get());
            assertNative(opened);
            detail.pause();
            main.resume().visible();
            layout(main.get());
            assertNative(feed);
        }
    }

    @Test public void aBuriedMainActivityResumedForAMomentHandsTheSizesBack() {
        // A theme change recreates TikTok's launcher copy of MainActivity under the feed's: it is
        // resumed for a moment and stopped again while the feed stays in front.
        try (var main = Robolectric.buildActivity(MainActivity.class).create();
             var launcher = Robolectric.buildActivity(MainActivity.class).create()) {
            TextView[] feed = authors(main.get());
            Settings.FEED_AUTHOR_TEXT_SIZE.save(28);
            FeedTextSize.install(main.get());
            main.start().resume().visible();
            layout(main.get());
            for (TextView author : feed) assertEquals(28, author.getTextSize(), 0);
            FeedTextSize.install(launcher.get());
            launcher.start().resume().pause().stop();
            idle();
            // TikTok rebinds the rows at its own size, and the next layout pass sizes them again.
            for (TextView author : feed) author.setTextSize(TypedValue.COMPLEX_UNIT_PX, 16);
            layout(main.get());
            for (TextView author : feed) assertEquals("The feed in front lost its sizes", 28, author.getTextSize(), 0);
        }
    }

    @Test @Config(fontScale = 2f)
    public void authorSizeUsesAndroidFontScalingAndRestoresTheExactNativePixels() {
        try (var owner = Robolectric.buildActivity(MainActivity.class).setup()) {
            TextView author = text(owner.get(), 16, "A creator 😊");
            assertEquals(2f, author.getResources().getConfiguration().fontScale, 0);
            float nativeSize = author.getTextSize();
            assertEquals(32, nativeSize, 0);
            Settings.FEED_AUTHOR_TEXT_SIZE.save(24);
            FeedTextSize.authorBound(author);
            assertEquals(48, author.getTextSize(), 0);
            PausedProcess.set(true);
            FeedTextSize.authorBound(author);
            assertEquals(nativeSize, author.getTextSize(), 0);
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void nativeAuthorMeasurementUsesTheSizeBeforeBindingAndRebindsOnOffAndPause() {
        try (var activity = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            TextView view = text(activity.get(), 17, "Initial name");
            activity.get().setContentView(view);
            final class Owner {
                final Object item = new SpannableString("A very long creator @mention 😊");
                int binds;
                float measuredSize;
                void bind() {
                    FeedTextSize.authorBinding(this, item);
                    measuredSize = view.getPaint().getTextSize();
                    binds++;
                    // Models the native binder's own pre-setText measurement and shortening.
                    view.setText(TextUtils.ellipsize((CharSequence) item, view.getPaint(), 180, TextUtils.TruncateAt.END));
                    FeedTextSize.authorOwnerBound(this);
                }
            }
            Owner owner = new Owner();
            FeedTextSize.nativeForTests = new FeedTextSize.Native() {
                @Override public View descriptionViewOf(Object value) { return null; }
                @Override public TextView authorViewOf(Object value) { return value == owner ? view : null; }
                @Override public void resizeDescriptionBuilder(Object builder, View value) { }
                @Override public void refreshDescription(Object value) { }
                @Override public void refreshAuthor(Object value, Object item) { assertSame(owner.item, item); owner.bind(); }
            };
            owner.bind();
            CharSequence nativeName = view.getText();
            Settings.FEED_AUTHOR_TEXT_SIZE.save(32);
            FeedTextSize.onSettingChanged();
            idle();
            assertEquals(32, owner.measuredSize, 0);
            assertEquals(2, owner.binds);
            assertTrue(view.getText().length() < nativeName.length());
            Settings.FEED_DESCRIPTION_TEXT_SIZE.save(48);
            FeedTextSize.onSettingChanged();
            idle();
            assertEquals("An unrelated size doesn't rebind the name", 2, owner.binds);
            PausedProcess.set(true);
            FeedTextSize.onSettingChanged();
            idle();
            assertEquals(17, owner.measuredSize, 0);
            assertEquals(nativeName.toString(), view.getText().toString());
            PausedProcess.set(false);
            FeedTextSize.onSettingChanged();
            idle();
            assertEquals(32, owner.measuredSize, 0);
            Settings.FEED_AUTHOR_TEXT_SIZE.save(0);
            FeedTextSize.onSettingChanged();
            idle();
            assertEquals(17, owner.measuredSize, 0);
            assertEquals(nativeName.toString(), view.getText().toString());
            assertEquals(5, owner.binds);
        }
    }

    @Test public void aTitleStoredByViewCreationReleasesTheOldTitleBeforeItsFirstBind() {
        try (var activity = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            final class Owner {
                TextView view;
                Object currentItem;
                int refreshes;
            }
            Owner owner = new Owner();
            FeedTextSize.nativeForTests = new FeedTextSize.Native() {
                @Override public View descriptionViewOf(Object value) { return null; }
                @Override public TextView authorViewOf(Object value) { return value == owner ? owner.view : null; }
                @Override public void resizeDescriptionBuilder(Object builder, View value) { }
                @Override public void refreshDescription(Object value) { }
                @Override public void refreshAuthor(Object value, Object item) {
                    owner.refreshes++;
                    owner.view.setText((CharSequence) item);
                }
            };
            TextView first = text(activity.get(), 17, "First creator");
            owner.view = first;
            owner.currentItem = "First creator";
            activity.get().setContentView(first);
            FeedTextSize.authorBinding(owner, owner.currentItem);
            FeedTextSize.authorOwnerBound(owner);
            // onViewCreated stores a new title; nothing has been bound into it yet.
            TextView created = text(activity.get(), 19, "");
            owner.view = created;
            activity.get().setContentView(created);
            FeedTextSize.authorOwnerBound(owner);
            Settings.FEED_AUTHOR_TEXT_SIZE.save(24);
            FeedTextSize.onSettingChanged();
            FeedTextSize.applyTo(activity.get());
            idle();
            assertEquals("The old title's item must not be bound into the new title", 0, owner.refreshes);
            assertEquals("", created.getText().toString());
            assertEquals(24, created.getTextSize(), 0);
            assertEquals("The released title returns to its native size", 17, first.getTextSize(), 0);
        }
    }

    @Test public void aReplacementDescriptionViewKeepsTheSizeItsOwnersLayoutsWereBuiltAt() {
        try (var activity = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            final class Owner {
                View view;
                int refreshes;
            }
            Owner owner = new Owner();
            FeedTextSize.nativeForTests = new FeedTextSize.Native() {
                @Override public View descriptionViewOf(Object value) { return value == owner ? owner.view : null; }
                @Override public TextView authorViewOf(Object value) { return null; }
                @Override public void resizeDescriptionBuilder(Object builder, View value) { }
                @Override public void refreshDescription(Object value) { owner.refreshes++; }
                @Override public void refreshAuthor(Object value, Object item) { }
            };
            View first = text(activity.get(), 16, "Built at 24");
            owner.view = first;
            activity.get().setContentView(first);
            Settings.FEED_DESCRIPTION_TEXT_SIZE.save(24);
            FeedTextSize.descriptionBuilder(owner, new Object());
            // Back to native while that View is detached, so no refresh reaches it.
            activity.get().setContentView(new android.widget.FrameLayout(activity.get()));
            Settings.FEED_DESCRIPTION_TEXT_SIZE.save(0);
            FeedTextSize.onSettingChanged();
            idle();
            assertEquals(0, owner.refreshes);
            View replacement = text(activity.get(), 16, "Reuses the cached layout");
            owner.view = replacement;
            activity.get().setContentView(replacement);
            assertFalse("Native size may use the cache on this dispatch", FeedTextSize.freshDescription(owner));
            FeedTextSize.applyTo(activity.get());
            assertEquals("The layout cached at 24 must be rebuilt at the native size", 1, owner.refreshes);
        }
    }

    @Test public void aRecycledAuthorOwnerCannotBindItsOldItemIntoItsReplacementTitle() {
        try (var activity = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            final class Owner {
                TextView view;
                Object currentItem;
                int refreshes;
                void bind() {
                    FeedTextSize.authorBinding(this, currentItem);
                    view.setText((CharSequence) currentItem);
                    FeedTextSize.authorOwnerBound(this);
                }
            }
            Owner owner = new Owner();
            FeedTextSize.nativeForTests = new FeedTextSize.Native() {
                @Override public View descriptionViewOf(Object value) { return null; }
                @Override public TextView authorViewOf(Object value) { return value == owner ? owner.view : null; }
                @Override public void resizeDescriptionBuilder(Object builder, View value) { }
                @Override public void refreshDescription(Object value) { }
                @Override public void refreshAuthor(Object value, Object item) {
                    assertSame("A stale title must never rebind the replacement owner's old item", owner.currentItem, item);
                    owner.refreshes++;
                    owner.bind();
                }
            };
            TextView first = text(activity.get(), 17, "First creator");
            owner.view = first;
            owner.currentItem = "First creator";
            activity.get().setContentView(first);
            owner.bind();
            TextView replacement = text(activity.get(), 19, "Second creator");
            owner.view = replacement;
            owner.currentItem = "Second creator";
            activity.get().setContentView(replacement);
            owner.bind();
            assertFalse(first.isAttachedToWindow());
            Settings.FEED_AUTHOR_TEXT_SIZE.save(24);
            FeedTextSize.onSettingChanged();
            idle();
            assertEquals(1, owner.refreshes);
            assertEquals("Second creator", replacement.getText().toString());
            assertEquals(24, replacement.getTextSize(), 0);
            assertEquals("The replaced title returns to its native size", 17, first.getTextSize(), 0);
            Settings.FEED_AUTHOR_TEXT_SIZE.save(0);
            FeedTextSize.onSettingChanged();
            idle();
            assertEquals(19, replacement.getTextSize(), 0);
            assertEquals("Second creator", replacement.getText().toString());
        }
    }

    /** The name and the post date beside it, owned by one row the way TikTok's author owner holds them. */
    private static final class AuthorRow {
        TextView name;
        TextView date;
        Object item;
        int binds;
        float measuredDate;

        void bind() {
            FeedTextSize.authorBinding(this, item);
            // Models the native binder measuring the date beside the name before setText.
            measuredDate = date.getPaint().getTextSize();
            binds++;
            name.setText((CharSequence) item);
            FeedTextSize.authorOwnerBound(this);
        }

        FeedTextSize.Native stand() {
            AuthorRow row = this;
            return new FeedTextSize.Native() {
                @Override public View descriptionViewOf(Object value) { return null; }
                @Override public TextView authorViewOf(Object value) { return value == row ? row.name : null; }
                @Override public TextView postDateViewOf(Object value) { return value == row ? row.date : null; }
                @Override public void resizeDescriptionBuilder(Object builder, View value) { }
                @Override public void refreshDescription(Object value) { }
                @Override public void refreshAuthor(Object value, Object bound) { assertSame(row.item, bound); row.bind(); }
            };
        }

        void showIn(Activity activity) {
            LinearLayout root = new LinearLayout(activity);
            root.addView(name);
            root.addView(date);
            activity.setContentView(root);
        }
    }

    @Test public void thePostDateFollowsTheAuthorSizeAndKeepsItsTextSpansAndVisibility() {
        try (var activity = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            AuthorRow row = new AuthorRow();
            row.name = text(activity.get(), 17, "Initial name");
            row.date = text(activity.get(), 17, "");
            SpannableString posted = new SpannableString("2d ago");
            StyleSpan weight = new StyleSpan(Typeface.BOLD);
            posted.setSpan(weight, 0, posted.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            row.date.setText(posted);
            CharSequence nativeDate = row.date.getText();
            row.date.setVisibility(View.VISIBLE);
            row.item = "A creator";
            row.showIn(activity.get());
            FeedTextSize.nativeForTests = row.stand();
            row.bind();
            assertEquals(17, row.date.getTextSize(), 0);

            Settings.FEED_DESCRIPTION_TEXT_SIZE.save(40);
            FeedTextSize.onSettingChanged();
            idle();
            assertEquals("The description size leaves the date alone", 17, row.date.getTextSize(), 0);

            Settings.FEED_AUTHOR_TEXT_SIZE.save(32);
            FeedTextSize.onSettingChanged();
            idle();
            assertEquals(32, row.name.getTextSize(), 0);
            assertEquals(32, row.date.getTextSize(), 0);
            assertEquals("The native measurement sees the date's new size", 32, row.measuredDate, 0);
            assertEquals("Only the name's entry rebinds the row", 2, row.binds);
            assertSame(nativeDate, row.date.getText());
            assertSame(weight, ((Spanned) row.date.getText()).getSpans(0, nativeDate.length(), StyleSpan.class)[0]);
            assertEquals(View.VISIBLE, row.date.getVisibility());

            // TikTok sets the date's font again when its text changes; the next pass sizes it back.
            row.date.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15);
            FeedTextSize.applyTo(activity.get());
            assertEquals(32, row.date.getTextSize(), 0);

            PausedProcess.set(true);
            FeedTextSize.onSettingChanged();
            idle();
            assertEquals("Paused, the date is TikTok's latest size", 15, row.date.getTextSize(), 0);
            assertEquals(17, row.name.getTextSize(), 0);
            PausedProcess.set(false);
            FeedTextSize.onSettingChanged();
            idle();
            assertEquals(32, row.date.getTextSize(), 0);

            Settings.FEED_AUTHOR_TEXT_SIZE.save(0);
            FeedTextSize.onSettingChanged();
            idle();
            assertEquals(15, row.date.getTextSize(), 0);
            assertEquals(17, row.name.getTextSize(), 0);
            assertSame(nativeDate, row.date.getText());
        }
    }

    @Test public void aRecycledRowReleasesItsOldPostDate() {
        try (var activity = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            AuthorRow row = new AuthorRow();
            row.name = text(activity.get(), 17, "First creator");
            row.date = text(activity.get(), 13, "1d ago");
            row.item = "First creator";
            row.showIn(activity.get());
            FeedTextSize.nativeForTests = row.stand();
            Settings.FEED_AUTHOR_TEXT_SIZE.save(24);
            row.bind();
            assertEquals(24, row.date.getTextSize(), 0);
            TextView firstDate = row.date;

            // The owner is reused for another cell: onViewCreated stores a new name and date.
            row.name = text(activity.get(), 19, "");
            row.date = text(activity.get(), 14, "");
            row.showIn(activity.get());
            FeedTextSize.authorOwnerBound(row);
            assertEquals("The released date returns to its native size", 13, firstDate.getTextSize(), 0);
            assertEquals(24, row.date.getTextSize(), 0);

            row.item = "Second creator";
            row.bind();
            Settings.FEED_AUTHOR_TEXT_SIZE.save(30);
            FeedTextSize.onSettingChanged();
            idle();
            assertEquals(30, row.date.getTextSize(), 0);
            assertEquals("A released date is never sized again", 13, firstDate.getTextSize(), 0);
            Settings.FEED_AUTHOR_TEXT_SIZE.save(0);
            FeedTextSize.onSettingChanged();
            idle();
            assertEquals(14, row.date.getTextSize(), 0);
        }
    }

    private static TextView text(Activity activity, int size, String label) {
        TextView text = new TextView(activity);
        text.setText(label);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        return text;
    }

    private static TextView[] authors(Activity activity) {
        LinearLayout root = new LinearLayout(activity);
        TextView[] result = {text(activity, 15, "First"), text(activity, 17, "Second"), text(activity, 19, "Third")};
        for (TextView author : result) {
            root.addView(author);
            FeedTextSize.authorBound(author);
        }
        activity.setContentView(root);
        return result;
    }

    private static void assertNative(TextView[] authors) {
        int[] expected = {15, 17, 19};
        for (int i = 0; i < authors.length; i++) assertEquals(expected[i], authors[i].getTextSize(), 0);
    }

    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private static void layout(Activity activity) {
        activity.findViewById(android.R.id.content).getViewTreeObserver().dispatchOnGlobalLayout();
    }
}
