package app.hushfeed.verification.api23;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import app.morphe.extension.shared.BuildNames;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.captions.CaptionStyle;
import app.morphe.extension.tiktok.feedfilter.FeedItemsFilter;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.FeedItemList;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;

/** Calls shipped payload members. Compile-only vendor stubs are never packaged. */
final class PayloadCases {
    interface Receipt { void pass(String name, String evidence) throws Exception; }
    private static int originalSize;
    private static String originalBackground;
    private static boolean originalOffline, originalFallback, captured;

    static void run(Context context, Receipt receipt) throws Exception {
        Utils.setContext(context);
        require(!Setting.isPaused(), "isolated helper payload is paused");
        originalSize = Settings.CAPTION_TEXT_SIZE.get();
        originalBackground = Settings.CAPTION_BACKGROUND.get();
        originalOffline = Settings.HIDE_OFFLINE_VIDEOS.get();
        originalFallback = Settings.FILTER_OFFLINE_FALLBACK_VIDEOS.get();
        captured = true;
        // Only the helper's resource names model a host version; the payload DEX stays intact.
        BuildNames.setRunningBuildForTests("47.4.3");
        Settings.CAPTION_TEXT_SIZE.save(0);
        Settings.CAPTION_BACKGROUND.save("default");
        TextPaint paint = new TextPaint();
        float sourceSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16, context.getResources().getDisplayMetrics());
        paint.setTextSize(sourceSize);
        String text = "conditions";
        Layout original = new StaticLayout(text, paint, (int) Math.ceil(Layout.getDesiredWidth(text, paint)),
                Layout.Alignment.ALIGN_CENTER, 1, 0, true);
        require(CaptionStyle.layout(original) == original && CaptionStyle.layout(null) == null, "disabled caption changed its layout");
        receipt.pass("caption-disabled", "original Layout identity and null preserved");
        Settings.CAPTION_TEXT_SIZE.save(28);
        Layout styled = CaptionStyle.layout(original);
        float expected = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 28, context.getResources().getDisplayMetrics());
        require(styled != original && styled.getPaint() != paint && Math.abs(styled.getPaint().getTextSize() - expected) < .01f
                && original.getPaint().getTextSize() == sourceSize && styled.getText().toString().equals(text)
                && styled.getLineCount() == 1, "native caption layout did not resize without splitting or mutating the source");
        receipt.pass("caption-layout", "native StaticLayout lines=" + styled.getLineCount() + " width=" + styled.getWidth()
                + " beforePx=" + sourceSize + " afterPx=" + styled.getPaint().getTextSize());
        FrameLayout container = new FrameLayout(context), strip = new FrameLayout(context);
        TextView view = new TextView(context);
        strip.setId(context.getResources().getIdentifier("dm5", "id", context.getPackageName()));
        view.setId(context.getResources().getIdentifier("dmb", "id", context.getPackageName()));
        require(strip.getId() != 0 && view.getId() != 0, "helper native resource IDs are missing");
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sourceSize);
        strip.setBackgroundColor(Color.BLUE); strip.addView(view); container.addView(strip);
        Method apply = CaptionStyle.class.getDeclaredMethod("apply", View.class); apply.setAccessible(true);
        Settings.CAPTION_BACKGROUND.save("black"); apply.invoke(null, container); apply.invoke(null, container);
        require(Math.abs(view.getTextSize() - expected) < .01f && ((ColorDrawable) strip.getBackground()).getColor() == Color.BLACK,
                "actual native caption views did not resize and recolor");
        receipt.pass("caption-native-views", "real TextView/FrameLayout, helper dmb/dm5 resources, repeated apply");
        Settings.CAPTION_TEXT_SIZE.save(0); Settings.CAPTION_BACKGROUND.save("default");
        apply.invoke(null, container); apply.invoke(null, container);
        require(view.getTextSize() == sourceSize && ((ColorDrawable) strip.getBackground()).getColor() == Color.BLUE
                && CaptionStyle.layout(original) == original, "caption disable did not restore exact native size/background");
        receipt.pass("caption-restored", "actual WeakHashMap lookup/removal and native size/background restoration");
        FeedItemList empty = new FeedItemList(); empty.items = Collections.emptyList();
        require(FeedItemsFilter.filterCachedFeedList(empty) == null && empty.items.isEmpty(), "empty native vendor batch was not rejected");
        receipt.pass("empty-cached-batch", "exact vendor FeedItemList plus immutable empty List returns null");
        FeedItemList offline = new FeedItemList(); offline.items = new ArrayList<>(); offline.items.add(new Object());
        Object originalItems = offline.items;
        Settings.HIDE_OFFLINE_VIDEOS.save(false); Settings.FILTER_OFFLINE_FALLBACK_VIDEOS.save(false);
        require(FeedItemsFilter.filterOfflineFeedList(offline) == offline && offline.items == originalItems && offline.items.size() == 1,
                "offline off control changed the batch");
        receipt.pass("offline-disabled", "exact vendor model preserves one explicit synthetic marker with controls off");
        Settings.HIDE_OFFLINE_VIDEOS.save(true);
        require(FeedItemsFilter.filterOfflineFeedList(offline) == null && offline.items != originalItems && offline.items.isEmpty(),
                "hide offline did not replace the list and reject the emptied batch");
        receipt.pass("offline-emptied", "production hide-offline path replaces a one-marker List with empty ArrayList and returns null");
        FeedItemsFilter.filterOnRead(empty);
        require(!HookStatus.anyMissing(), "payload member lookup reported a missing native member");
        receipt.pass("feed-hook-status", HookBindings.require(HookStatus.snapshot()));
    }

    static boolean restore() throws Throwable {
        if (!captured) return true;
        Throwable failure = restoreOne(() -> Settings.CAPTION_TEXT_SIZE.save(originalSize), null);
        failure = restoreOne(() -> Settings.CAPTION_BACKGROUND.save(originalBackground), failure);
        failure = restoreOne(() -> Settings.HIDE_OFFLINE_VIDEOS.save(originalOffline), failure);
        failure = restoreOne(() -> Settings.FILTER_OFFLINE_FALLBACK_VIDEOS.save(originalFallback), failure);
        failure = restoreOne(() -> BuildNames.setRunningBuildForTests(null), failure);
        if (failure != null) throw failure;
        captured = false;
        return Settings.CAPTION_TEXT_SIZE.get() == originalSize && Settings.CAPTION_BACKGROUND.get().equals(originalBackground)
                && Settings.HIDE_OFFLINE_VIDEOS.get() == originalOffline && Settings.FILTER_OFFLINE_FALLBACK_VIDEOS.get() == originalFallback;
    }
    private static Throwable restoreOne(Runnable action, Throwable failure) {
        try { action.run(); }
        catch (Throwable error) { if (failure == null) return error; failure.addSuppressed(error); }
        return failure;
    }
    private static void require(boolean valid, String failure) { if (!valid) throw new AssertionError(failure); }
    private PayloadCases() { }
}
