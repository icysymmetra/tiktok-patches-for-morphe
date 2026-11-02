/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */

package app.morphe.extension.tiktok.download;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.RequiresApi;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import com.ss.android.ugc.aweme.base.model.UrlModel;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.WeakHashMap;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@SuppressWarnings("unused")
public final class StickerGallerySaver {
    private static final String ACTION_LABEL = "Save media";
    private static final String HOOK_FAMILY = "sticker saves";

    static {
        // The save button reads no switch, so Pause leaves it working; the export says so.
        HookStatus.runsWhilePaused(HOOK_FAMILY);
    }
    /**
     * Marks the button this file added, so finding it again does not depend on its wording.
     *
     * <p>The label was the check: a sheet already carrying a child reading "Save media" was
     * taken as done. Translating the label alone would have given every German sheet a second
     * button, because the first one no longer said what the check was looking for.
     */
    private static final String SAVE_BUTTON_TAG = "morphe_save_media";
    /** A sticker is a few hundred KB. Anything past this is not one. */
    private static final long MAX_STICKER_BYTES = 24L * 1024 * 1024;
    private static final long MAX_STICKER_PIXELS = 16L * 1024 * 1024;
    private static final long MAX_DM_PREVIEW_PIXELS = 1024L * 1024;
    private static final int MAX_DM_PREVIEW_SIDE = 1024;
    private static final String DM_STICKER_VIEW = "com.ss.android.ugc.aweme.views.RoundingCornerLayout";
    private static final String SMART_IMAGE_VIEW = "com.bytedance.lighten.loader.SmartImageView";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    /** The sticker each bound sheet is currently showing, so a reused sheet saves its own. */
    private static final WeakHashMap<View, StickerAsset> ATTACHED_SHEETS = new WeakHashMap<>();
    private static final WeakHashMap<Object, Object> STICKER_SOURCES = new WeakHashMap<>();
    private static final WeakHashMap<Activity, WeakReference<AlertDialog>> DM_PREVIEWS = new WeakHashMap<>();

    private StickerGallerySaver() {
    }

    public static void registerStickerSource(Object previewModel, Object source) {
        if (previewModel == null || source == null) return;
        synchronized (STICKER_SOURCES) {
            STICKER_SOURCES.put(previewModel, source);
        }
    }

    /**
     * The received sticker's tap, after the host's preview call. On the declared build that
     * call is a stub. This owns its own preview, never a composer cell or the chat's root.
     * The snapshot is only a poster; Save reads and authenticates the complete source media.
     */
    public static void openDmStickerPreview(View tapped, Object template) {
        Bitmap poster = null;
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) return;
            View image = dmStickerImage(tapped);
            if (image == null) return;
            StickerAsset asset = findDmStickerAsset(template);
            if (asset == null) {
                HookStatus.missingMember(HOOK_FAMILY, "model", "DM sticker", "usable sticker source");
                Utils.showToastShort(L10n.t("That sticker is in a format Metra can't read"));
                return;
            }
            Activity activity = tapped == null ? null : activityFrom(tapped.getContext());
            if (image == null || asset == null || activity == null
                    || activity.isFinishing() || activity.isDestroyed()) return;

            // Only the image inside the verified small sticker bubble is drawn. Never walk up
            // to a larger parent, and never retain the native view after this synchronous draw.
            poster = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
            image.draw(new Canvas(poster));
            showDmStickerPreview(activity, poster, asset);
            poster = null; // The dialog now owns the bitmap, including dismissal cleanup.
        } catch (RuntimeException failure) {
            Logger.printInfo(() -> "Could not open sticker preview");
            Utils.showToastShort(L10n.t("The sticker couldn't be saved. Try again."));
        } finally {
            if (poster != null) poster.recycle();
        }
    }

    private static Activity activityFrom(Context context) {
        for (int depth = 0; context != null && depth < 10; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) return null;
            context = next;
        }
        return null;
    }

    /** No parent fallback: the only accepted view is the exact native message sticker bubble. */
    static View dmStickerImage(View tapped) {
        if (tapped == null || !DM_STICKER_VIEW.equals(tapped.getClass().getName())
                || !boundedDmPreview(tapped)) return null;
        List<View> images = new ArrayList<>();
        int[] visited = {0};
        collectDmImages(tapped, images, 0, visited);
        if (visited[0] > 32 || images.size() != 1) return null;
        View image = images.get(0);
        return boundedDmPreview(image) && image.getWidth() <= tapped.getWidth()
                && image.getHeight() <= tapped.getHeight() ? image : null;
    }

    private static boolean boundedDmPreview(View view) {
        int width = view.getWidth(), height = view.getHeight();
        int limit = Math.min(MAX_DM_PREVIEW_SIDE, SettingsUi.dp(view.getContext(), 320));
        return width > 0 && height > 0 && width <= limit && height <= limit
                && (long) width * height <= MAX_DM_PREVIEW_PIXELS;
    }

    private static void collectDmImages(View view, List<View> images, int depth, int[] visited) {
        if (++visited[0] > 32 || depth > 4) {
            visited[0] = 33;
            return;
        }
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if (SMART_IMAGE_VIEW.equals(type.getName())) {
                images.add(view);
                return;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && visited[0] <= 32; i++) {
                collectDmImages(group.getChildAt(i), images, depth + 1, visited);
            }
        }
    }

    static AlertDialog showDmStickerPreview(Activity activity, Bitmap poster, StickerAsset asset) {
        WeakReference<AlertDialog> previous = DM_PREVIEWS.remove(activity);
        AlertDialog prior = previous == null ? null : previous.get();
        if (prior != null) prior.dismiss();
        SettingsUi.syncDarkMode(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int inset = SettingsUi.dp(activity, 24);
        content.setPadding(inset, 0, inset, inset);
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageBitmap(poster);
        image.setContentDescription(L10n.t(activity, "Sticker"));
        content.addView(image, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.min(SettingsUi.dp(activity, 240),
                        activity.getResources().getDisplayMetrics().heightPixels / 3)));
        ViewGroup actions = SettingsUi.actionRow(activity);
        TextView close = new TextView(activity);
        close.setText(L10n.t(activity, "Close"));
        SettingsUi.styleTextAction(close, false);
        synchronized (ATTACHED_SHEETS) {
            ATTACHED_SHEETS.put(content, asset);
        }
        TextView save = createActionButton(close, content);
        save.setSingleLine(false);
        save.setEllipsize(null);
        SettingsUi.styleTextAction(save, true);
        actions.addView(close);
        actions.addView(save);
        content.addView(actions);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(L10n.t(activity, "Sticker")).setView(content).create();
        close.setOnClickListener(ignored -> dialog.dismiss());
        dialog.setOnShowListener(ignored -> {
            SettingsUi.styleStandardAlertDialog(dialog);
            SettingsUi.styleTextAction(close, false);
            SettingsUi.styleTextAction(save, true);
        });
        dialog.setOnDismissListener(ignored -> {
            synchronized (ATTACHED_SHEETS) {
                ATTACHED_SHEETS.remove(content);
            }
            WeakReference<AlertDialog> current = DM_PREVIEWS.get(activity);
            if (current != null && current.get() == dialog) DM_PREVIEWS.remove(activity);
            image.setImageDrawable(null);
            poster.recycle();
        });
        dialog.show();
        DM_PREVIEWS.put(activity, new WeakReference<>(dialog));
        HookStatus.bound(HOOK_FAMILY, "DM sticker preview");
        return dialog;
    }

    /** Only named model members, copied now so recycling the native message cannot change Save. */
    static StickerAsset findDmStickerAsset(Object template) {
        Object image = readNamedField(template, "imageComponent");
        if (image == null || !hasNamedField(image.getClass(), "decryptKey")) return null;
        List<String> urls = usableUrlList(readNamedField(image, "urls"));
        if (urls.isEmpty()) return null;
        Object rawKey = readNamedField(image, "decryptKey");
        if (rawKey != null && !(rawKey instanceof String)) return null;
        byte[] key = null;
        if (rawKey != null && !((String) rawKey).isEmpty()) {
            key = dmStickerKey((String) rawKey);
            if (key == null) return null;
        }
        return new StickerAsset(urls, false, true, key);
    }

    static byte[] dmStickerKey(String hex) {
        if (hex == null || (hex.length() != 32 && hex.length() != 48 && hex.length() != 64)) return null;
        byte[] key = new byte[hex.length() / 2];
        for (int i = 0; i < key.length; i++) {
            if (hex.charAt(i * 2) > 127 || hex.charAt(i * 2 + 1) > 127) return null;
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) return null;
            key[i] = (byte) ((high << 4) | low);
        }
        return key;
    }

    public static void attachSaveImageButton(View sheetView, Object sheetModel) {
        try {
            if (sheetView == null || sheetModel == null) return;

            StickerAsset asset = findStickerAsset(sheetModel);
            if (asset == null) {
                // Forget what the sheet was showing. Leaving the last one there would let the
                // button save a sticker the reader has already moved on from.
                synchronized (ATTACHED_SHEETS) {
                    ATTACHED_SHEETS.remove(sheetView);
                }
                debugLog("[Morphe Stickers] no usable sticker URL");
                return;
            }

            // The sheet can receive its actions after a bind or replace their parent. Refresh
            // its sticker first, then check the current action row for our button below.
            synchronized (ATTACHED_SHEETS) {
                ATTACHED_SHEETS.put(sheetView, asset);
            }

            List<View> actionButtons = findActionButtons(sheetView);
            ViewGroup actionParent = findCommonParent(actionButtons);
            if (actionParent == null || actionButtons.size() < 2) {
                // Said in the export, not only in the debug log: this is how issue #23 went
                // unnoticed, a sheet with no Save media button and nothing anywhere saying why.
                HookStatus.missingMember(HOOK_FAMILY, "view", sheetView.getClass().getName(),
                        "two action buttons of one type");
                debugLog("[Morphe Stickers] action parent unavailable buttons=" + actionButtons.size());
                return;
            }
            HookStatus.bound(HOOK_FAMILY, "sheet action buttons");
            if (hasSaveImageButton(actionParent)) return;

            View template = lastChildOf(actionParent, actionButtons);
            int insertIndex = actionParent.indexOfChild(template) + 1;
            if (insertIndex <= 0) {
                debugLog("[Morphe Stickers] action buttons are not direct children");
                return;
            }

            TextView saveImageButton = createActionButton(template, sheetView);
            ViewGroup.LayoutParams layoutParams = cloneLayoutParams(template.getLayoutParams());
            actionParent.addView(saveImageButton, insertIndex, layoutParams);

            debugLog("[Morphe Stickers] attached Save sticker button animated=" + asset.animated
                    + " url=" + summarizeUrl(asset.url)
                    + " parent=" + actionParent.getClass().getName());
        } catch (Throwable ex) {
            if (BaseSettings.DEBUG.get()) {
                Logger.printException(() -> "[Morphe Stickers] attachSaveImageButton failure", ex);
            }
        }
    }

    private static TextView createActionButton(View template, View sheetView) {
        Context context = template.getContext();
        TextView button = new TextView(context);
        button.setText(L10n.t(button.getContext(), ACTION_LABEL));
        button.setTag(SAVE_BUTTON_TAG);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        // Read the sticker when the button is pressed, not when it was built: the sheet the
        // button lives in gets bound again for the next sticker the reader opens.
        button.setOnClickListener(view -> {
            StickerAsset showing;
            synchronized (ATTACHED_SHEETS) {
                showing = ATTACHED_SHEETS.get(sheetView);
            }
            if (showing == null || (showing.privateMedia && !sheetView.isShown())) {
                debugLog("[Morphe Stickers] the sheet no longer names a sticker");
                return;
            }
            saveStickerFromButton(view, showing);
        });

        if (template instanceof TextView) {
            TextView textTemplate = (TextView) template;
            button.setTextColor(textTemplate.getTextColors());
            button.setTextSize(0, textTemplate.getTextSize());
            button.setTypeface(textTemplate.getTypeface(), textTemplate.getTypeface() == null ? Typeface.NORMAL : textTemplate.getTypeface().getStyle());
            button.setIncludeFontPadding(textTemplate.getIncludeFontPadding());
            button.setMinHeight(textTemplate.getMinHeight());
            button.setMinWidth(textTemplate.getMinWidth());
            button.setPadding(
                    textTemplate.getPaddingLeft(),
                    textTemplate.getPaddingTop(),
                    textTemplate.getPaddingRight(),
                    textTemplate.getPaddingBottom()
            );
        } else {
            boolean dark = SettingsUi.isDarkContext(template.getContext());
            int textColor = SettingsUi.textPrimaryOn(dark);
            button.setTextColor(SettingsUi.enabledTextColors(textColor));
            button.setTextSize(SettingsUi.TEXT_TITLE);
            int paddingHorizontal = SettingsUi.dp(context, 16);
            int paddingVertical = SettingsUi.dp(context, 10);
            button.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical);
        }

        Drawable background = template.getBackground();
        if (background != null && background.getConstantState() != null) {
            button.setBackground(withHostPress(background, button.getCurrentTextColor()));
        } else {
            boolean dark = SettingsUi.isDarkContext(template.getContext());
            button.setBackground(SettingsUi.overlayAction(context, SettingsUi.RADIUS_CONTROL,
                    SettingsUi.textPrimaryOn(dark)));
        }

        button.setEnabled(template.isEnabled());
        button.setClickable(true);
        button.setFocusable(true);
        button.setAlpha(template.getAlpha());
        // A clickable TextView announces as text, so this was offered to a screen reader as a
        // label rather than as something to press, and the same helper the settings screen's
        // hand built actions use now gives it the role, the action and its enabled state.
        SettingsUi.markAsButton(button);
        // The template is one of TikTok's own row labels and can be shorter than a finger. The
        // minimum is a floor, not a height, so a large font scale still grows the button rather
        // than clipping it.
        int minimum = SettingsUi.dp(context, 48);
        button.setMinHeight(Math.max(button.getMinHeight(), minimum));
        button.setMinWidth(Math.max(button.getMinWidth(), minimum));
        return button;
    }

    /** The wash TikTok lays over a pressed sheet button: 0x21 of 0xff, about 13 percent. */
    static final int HOST_PRESS_WASH_ALPHA = 0x21;

    /**
     * A copy of TikTok's button background that also shows TikTok's press.
     *
     * <p>TikTok's sheet buttons lighten while held, a flat wash of the text colour over the whole
     * pill (#fe2c55 to #fe476b on Save, measured on 47.1.3), but not through their background:
     * that is a GradientDrawable of one colour, so a plain copy never showed a press at all.
     * Focus is left to the platform's default highlight, which is what TikTok's own buttons get.
     * A background that already has states of its own is copied as it is.
     */
    static Drawable withHostPress(Drawable background, int tone) {
        Drawable.ConstantState state = background.getConstantState();
        Drawable surface = state.newDrawable().mutate();
        if (surface.isStateful()) return surface;
        Drawable wash = state.newDrawable().mutate();
        // A tint, not a colour filter: a StateListDrawable mutates what it is given, and a
        // mutated LayerDrawable rebuilds its layers from their constant state, which carries
        // the tint and drops a filter set on the instance.
        wash.setTintList(ColorStateList.valueOf((tone & 0x00ffffff) | (HOST_PRESS_WASH_ALPHA << 24)));
        wash.setTintMode(PorterDuff.Mode.SRC_IN);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                new LayerDrawable(new Drawable[]{state.newDrawable().mutate(), wash}));
        states.addState(new int[0], surface);
        return states;
    }

    private static void saveStickerFromButton(View button, StickerAsset asset) {
        Context context = button.getContext().getApplicationContext();
        SettingsUi.setBusy(button, true,
                L10n.t(button.getContext(), "Saving"));

        // A submitted job can wait behind eight others, then run up to the two minute deadline.
        // Capturing the button would hold the sheet's Activity for that whole window after the
        // sheet itself was gone. Every other view this file keeps hold of is already weak.
        // The button comes back when the job is over whichever way it ends: saved, failed,
        // cancelled while it waited, or refused by a full queue.
        WeakReference<View> anchor = new WeakReference<>(button);
        MediaJobScheduler.Job job = MediaJobScheduler.submit(
                "sticker", stickerJobKey(asset), stickerSaveWork(context, asset), handBackLater(anchor));
        String saying = L10n.t("Saving the sticker");
        MediaJobScheduler.acknowledge(job, saying, saying);
    }

    /** The work one sticker save does. It holds no view: the button is handed back by the job's end. */
    static Runnable stickerSaveWork(Context context, StickerAsset asset) {
        return () -> {
            SaveResult result = saveSticker(context, asset);
            MAIN_HANDLER.post(() -> {
                Utils.showToastShort(result.message);
                if (result.success) {
                    debugLog("[Morphe Stickers] saved sticker path=" + result.path);
                } else if (BaseSettings.DEBUG.get()) {
                    Logger.printInfo(() -> "[Morphe Stickers] save failed reason=" + result.message
                            + " source=" + diagnosticSource(asset));
                }
            });
        };
    }

    static String stickerJobKey(StickerAsset asset) {
        return asset.privateMedia ? null : "sticker " + asset.url;
    }

    static String diagnosticSource(StickerAsset asset) {
        return asset.privateMedia ? "private sticker" : summarizeUrl(asset.url);
    }

    /** Hands the button back on the main thread, once the save's job is over. */
    static Runnable handBackLater(WeakReference<View> anchor) {
        return () -> MAIN_HANDLER.post(() -> handBack(anchor));
    }

    /** Re-enables the Save button, unless the sheet that owned it has already gone. */
    private static void handBack(WeakReference<View> anchor) {
        View button = anchor.get();
        if (button != null) SettingsUi.setBusy(button, false, null);
    }

    private static SaveResult saveSticker(Context context, StickerAsset asset) {
        MediaBudget.Deadline deadline = MediaBudget.deadline();
        IOException failure = new IOException("No sticker URL succeeded");
        for (String url : asset.urls) {
            File source = null;
            File decoded = null;
            try {
                source = MediaCache.createTempFile(context, "sticker-source-", ".tmp");
                String contentType = downloadSticker(url, source, context, deadline);
                File media = source;
                if (asset.decryptKey != null) {
                    decoded = MediaCache.createTempFile(context, "sticker-decoded-", ".tmp");
                    decryptDmSticker(source, decoded, asset.decryptKey, deadline);
                    media = decoded;
                }
                SaveResult result = saveDownloadedSticker(context, asset, url, media, contentType);
                if (!result.success) throw new IOException(result.message);
                return result;
            } catch (MediaBudget.StopException refusal) {
                if (asset.privateMedia) Logger.printInfo(() -> "Private sticker save stopped (" + refusal.reason + ")");
                else Logger.printException(() -> "Sticker save stopped", refusal);
                if (refusal.reason == MediaBudget.StopException.Reason.SERVER_WAIT) {
                    return SaveResult.failure(L10n.t("The server asked us to wait. Try again later."));
                }
                if (refusal.reason == MediaBudget.StopException.Reason.CANCELLED) {
                    return SaveResult.failure(L10n.t("Save cancelled. Nothing was saved."));
                }
                return SaveResult.failure(L10n.t("The sticker couldn't be saved. Try again."));
            } catch (Throwable error) {
                if (asset.privateMedia) {
                    // Transport causes can contain a private URL. Keep only its error class,
                    // never the cause chain, key, native ID or any part of the message model.
                    failure.addSuppressed(privateStickerFailure(error));
                } else {
                    failure.addSuppressed(new IOException(
                            "Sticker mirror failed (" + error.getClass().getSimpleName() + "): "
                                    + summarizeUrl(url), error));
                }
            } finally {
                if (decoded != null && !MediaCache.delete(decoded)) {
                    Logger.printInfo(() -> "Could not remove decoded sticker source file");
                }
                if (source != null && !MediaCache.delete(source)) {
                    Logger.printInfo(() -> "Could not remove sticker source file");
                }
            }
        }
        if (BaseSettings.DEBUG.get()) {
            Logger.printException(() -> "[Morphe Stickers] saveSticker failure", failure);
        }
        return SaveResult.failure(L10n.t("The sticker couldn't be saved. Try again."));
    }

    static IOException privateStickerFailure(Throwable error) {
        return new IOException("Private sticker mirror failed (" + error.getClass().getSimpleName() + ")");
    }

    /** Native ImageEncryptUtils contract: hex AES key, 12-byte nonce, authenticated GCM body. */
    static void decryptDmSticker(File source, File decoded, byte[] key, MediaBudget.Deadline deadline)
            throws IOException, GeneralSecurityException {
        if (key == null || (key.length != 16 && key.length != 24 && key.length != 32)
                || source.length() <= 28 || source.length() > MAX_STICKER_BYTES) {
            MediaCache.deletePartial(decoded);
            throw new IOException("Invalid encrypted sticker");
        }
        MediaBudget.checkDiskSpace(decoded.getParentFile(), source.length(), deadline);
        boolean authenticated = false;
        try (InputStream input = new FileInputStream(source)) {
            byte[] nonce = new byte[12];
            int used = 0;
            while (used < nonce.length) {
                MediaBudget.check(deadline);
                int count = input.read(nonce, used, nonce.length - used);
                if (count < 0) throw new IOException("Truncated encrypted sticker");
                used += count;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            try (OutputStream output = new FileOutputStream(decoded)) {
                byte[] buffer = new byte[65536];
                long encrypted = nonce.length, written = 0;
                while (true) {
                    MediaBudget.check(deadline);
                    int count = input.read(buffer);
                    if (count < 0) break;
                    encrypted += count;
                    if (encrypted > MAX_STICKER_BYTES) throw new IOException("Invalid encrypted sticker");
                    written = writeDmPlaintext(output, cipher.update(buffer, 0, count), written, decoded, deadline);
                }
                // Providers may emit plaintext from update before checking the tag. It stays
                // in private cache until this explicit final authentication succeeds.
                written = writeDmPlaintext(output, cipher.doFinal(), written, decoded, deadline);
                if (written == 0) throw new IOException("Empty decrypted sticker");
                output.flush();
            }
            authenticated = true;
        } finally {
            if (!authenticated) MediaCache.deletePartial(decoded);
        }
    }

    private static long writeDmPlaintext(OutputStream output, byte[] bytes, long written,
            File decoded, MediaBudget.Deadline deadline) throws IOException {
        if (bytes == null || bytes.length == 0) return written;
        if (bytes.length > MAX_STICKER_BYTES - written) throw new IOException("Invalid decrypted sticker");
        for (int offset = 0; offset < bytes.length;) {
            MediaBudget.check(deadline);
            MediaBudget.checkStreamingDiskSpace(decoded.getParentFile(), deadline);
            int count = Math.min(65536, bytes.length - offset);
            output.write(bytes, offset, count);
            offset += count;
        }
        return written + bytes.length;
    }

    private static String downloadSticker(
            String url,
            File target,
            Context context,
            MediaBudget.Deadline deadline
    ) throws IOException {
        return downloadSticker(url, target, context, deadline, MediaTransport.DEFAULT);
    }

    static String downloadSticker(
            String url,
            File target,
            Context context,
            MediaBudget.Deadline deadline,
            MediaTransport.Client transport
    ) throws IOException {
        IOException failure = new IOException("Sticker URL failed");
        for (int attempt = 0; attempt < MediaBudget.MAX_ATTEMPTS_PER_MIRROR; attempt++) {
            try (MediaTransport.Response response = transport.open(
                    url, deadline, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS,
                    "TikTok 46.2.3 Morphe", false)) {
                MediaBudget.check(deadline);
                int responseCode = response.statusCode;
                if (MediaBudget.isTransientStatus(responseCode)
                        || (responseCode >= 300 && response.header("Retry-After") != null)) {
                    String retryAfter = response.header("Retry-After");
                    response.close();
                    MediaBudget.waitBeforeRetry(retryAfter, attempt, deadline);
                    if (MediaBudget.isTransientStatus(responseCode)
                            && attempt + 1 < MediaBudget.MAX_ATTEMPTS_PER_MIRROR) continue;
                }
                if (responseCode < 200 || responseCode >= 300) {
                    throw new IOException("Sticker server returned " + responseCode);
                }
                long declaredLength = contentLength(response.header("Content-Length"));
                MediaBudget.checkTransferLength(declaredLength);
                MediaBudget.checkDiskSpace(context.getCacheDir(), declaredLength, deadline);
                try (InputStream input = response.inputStream();
                     OutputStream output = new FileOutputStream(target)) {
                    MediaFileWriter.copy(input, output, MAX_STICKER_BYTES, deadline,
                            target.getParentFile());
                }
                return response.contentType();
            } catch (IOException | RuntimeException error) {
                boolean cleaned = MediaCache.deletePartial(target);
                if (error instanceof MediaBudget.StopException) {
                    if (!cleaned) error.addSuppressed(new IOException("Could not remove partial sticker output"));
                    throw (MediaBudget.StopException) error;
                }
                boolean retryable = MediaBudget.isRetryableTransport(error);
                if (cleaned && retryable && attempt + 1 < MediaBudget.MAX_ATTEMPTS_PER_MIRROR) {
                    MediaBudget.waitBeforeRetry(null, attempt, deadline);
                    continue;
                }
                failure.addSuppressed(new IOException(
                        "Sticker attempt failed (" + error.getClass().getSimpleName() + "): "
                                + summarizeUrl(url), error));
                if (!cleaned) failure.addSuppressed(
                        new IOException("Could not remove partial sticker output"));
                break;
            }
        }
        throw failure;
    }

    private static long contentLength(String header) {
        if (header == null) return -1L;
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static SaveResult saveDownloadedSticker(
            Context context,
            StickerAsset asset,
            String url,
            File source,
            String contentType
    ) throws Exception {
        MediaBudget.check(null);
        try (BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(source))) {
            // DM metadata can name encrypted bytes as an image. Only authenticated source
            // headers decide the format, never a URL suffix or the server's content type.
            MediaFormat format = detectMediaFormat(asset.privateMedia ? null : contentType,
                    asset.privateMedia ? null : url, inputStream, asset.animated);
            String mediaId = asset.privateMedia ? java.util.UUID.randomUUID().toString().replace("-", "")
                    : Integer.toUnsignedString(url.hashCode(), 16);
            String displayName = DownloadFilenameFormatter.formatCommentMediaName(format.extension, mediaId);

            if (!format.convertToPng) {
                if (format.convertToMp4 || format.convertToGif) {
                    byte[] animatedWebp = readFully(inputStream);
                    try {
                        if (format.convertToGif) {
                            return saveConvertedSticker(context, animatedWebp, displayName,
                                    "image/gif", false, "GIF");
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            Uri uri = saveAnimatedWebpMp4WithMediaStore(context, animatedWebp, displayName);
                            return SaveResult.success(savedPath(context, uri, displayName, true), uri.toString(), "MP4");
                        }

                        File outputFile = saveAnimatedWebpMp4WithLegacyStorage(context, animatedWebp, displayName);
                        return SaveResult.success(outputFile.getAbsolutePath(), outputFile.getAbsolutePath(), "MP4");
                    } catch (Throwable ex) {
                        // Better the sticker in the format it arrived in than nothing at all.
                        debugLog("[Morphe Stickers] conversion failed, keeping the WebP: " + ex);
                        MediaFormat sourceFormat = MediaFormat.webp();
                        String webpName = DownloadFilenameFormatter.formatCommentMediaName(
                                sourceFormat.extension, mediaId);
                        try (InputStream bytes = new java.io.ByteArrayInputStream(animatedWebp)) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                Uri uri = saveStreamWithMediaStore(context, bytes, webpName,
                                        sourceFormat, animatedWebp.length);
                                return SaveResult.success(savedPath(context, uri, webpName, false),
                                        uri.toString(), sourceFormat.label);
                            }
                            File saved = saveStreamWithLegacyStorage(context, bytes, webpName,
                                    sourceFormat.mimeType, animatedWebp.length);
                            return SaveResult.success(saved.getAbsolutePath(), saved.getAbsolutePath(),
                                    sourceFormat.label);
                        }
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Uri uri = saveStreamWithMediaStore(context, inputStream, displayName,
                            format, source.length());
                    return SaveResult.success(savedPath(context, uri, displayName, format.video),
                            uri.toString(), format.label);
                }

                File outputFile = saveStreamWithLegacyStorage(context, inputStream, displayName,
                        format.mimeType, source.length());
                return SaveResult.success(outputFile.getAbsolutePath(), outputFile.getAbsolutePath(), format.label);
            }

            Bitmap bitmap = decodeStaticSticker(source);
            if (bitmap == null) return SaveResult.failure(
                    L10n.t("That sticker is in a format Metra can't read"));

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Uri uri = saveBitmapWithMediaStore(context, bitmap, displayName);
                    return SaveResult.success(savedPath(context, uri, displayName, false), uri.toString(), "PNG");
                }

                File outputFile = saveBitmapWithLegacyStorage(context, bitmap, displayName);
                return SaveResult.success(outputFile.getAbsolutePath(), outputFile.getAbsolutePath(), "PNG");
            } finally {
                bitmap.recycle();
            }
        }
    }

    private static Bitmap decodeStaticSticker(File source) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                || (long) bounds.outWidth * bounds.outHeight > MAX_STICKER_PIXELS) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        return BitmapFactory.decodeFile(source.getAbsolutePath(), options);
    }

    private interface PendingWriter {
        void write(ContentResolver resolver, Uri uri, File directory) throws Exception;
    }

    private interface FileWriter {
        void write(File file) throws Exception;
    }

    /** Publishes one pending MediaStore row and removes it again when writing fails. */
    private static Uri savePendingWithMediaStore(
            Context context,
            String displayName,
            String mimeType,
            boolean video,
            long outputBytes,
            PendingWriter writer
    ) throws Exception {
        MediaBudget.check(null);
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        String relativePath = stickerRelativePath(video);
        File directory = new File(Environment.getExternalStorageDirectory(), relativePath);
        MediaBudget.checkDiskSpace(directory, outputBytes);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = MediaCache.insertPending(
                context, resolver, DownloadDestination.collectionUri(relativePath, video), values);
        SaveRecords.Slot slot = SaveRecords.located(uri);
        try {
            writer.write(resolver, uri, directory);
            MediaBudget.checkDiskSpace(directory, 0);
            ContentValues complete = new ContentValues();
            // The row went in under a placeholder MediaStore could not collide with, so the
            // publish is where it takes the name the reader asked for.
            complete.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            complete.put(MediaStore.MediaColumns.IS_PENDING, 0);
            completePending(context, resolver, uri, complete);
            SaveRecords.published(slot);
            return uri;
        } catch (Throwable ex) {
            discardPending(context, resolver, uri, ex);
            SaveRecords.abandoned(slot);
            throw ex;
        }
    }

    /** Claims a unique legacy file, scans it after writing and removes only that file on failure. */
    private static File saveWithLegacyStorage(
            Context context,
            String displayName,
            String mimeType,
            boolean video,
            long outputBytes,
            FileWriter writer
    ) throws Exception {
        MediaBudget.check(null);
        File directory = new File(Environment.getExternalStorageDirectory(), stickerRelativePath(video));
        MediaBudget.checkDiskSpace(directory, outputBytes);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create " + directory);
        }

        File outputFile = MediaFileWriter.claim(directory, displayName);
        // A converted sticker's size isn't known until it's written, so none is recorded.
        SaveRecords.Slot slot = SaveRecords.located(outputFile, -1L);
        try {
            writer.write(outputFile);
            MediaBudget.checkDiskSpace(directory, 0);
        } catch (Throwable ex) {
            if (outputFile.exists() && !outputFile.delete()) {
                debugLog("[Morphe Stickers] could not remove partial file=" + outputFile.getAbsolutePath());
            }
            SaveRecords.abandoned(slot);
            throw ex;
        }
        MediaScannerConnection.scanFile(context, new String[]{outputFile.getAbsolutePath()},
                new String[]{mimeType}, null);
        SaveRecords.published(slot);
        return outputFile;
    }

    private static Uri saveBitmapWithMediaStore(Context context, Bitmap bitmap, String displayName) throws Exception {
        return savePendingWithMediaStore(context, displayName, "image/png", false, -1L,
                (resolver, uri, directory) -> {
                    try (OutputStream outputStream = resolver.openOutputStream(uri)) {
                        if (outputStream == null) {
                            throw new IllegalStateException("MediaStore output stream returned null");
                        }
                        writePng(bitmap, MediaFileWriter.withDiskBudget(outputStream, directory));
                    }
                });
    }

    private static Uri saveStreamWithMediaStore(
            Context context,
            InputStream inputStream,
            String displayName,
            MediaFormat format,
            long outputBytes
    ) throws Exception {
        return savePendingWithMediaStore(context, displayName, format.mimeType, format.video, outputBytes,
                (resolver, uri, directory) -> {
                    try (OutputStream outputStream = resolver.openOutputStream(uri)) {
                        if (outputStream == null) {
                            throw new IllegalStateException("MediaStore output stream returned null");
                        }
                        copy(inputStream, MediaFileWriter.withDiskBudget(outputStream, directory));
                    }
                });
    }

    private static File saveBitmapWithLegacyStorage(Context context, Bitmap bitmap, String displayName) throws Exception {
        return saveWithLegacyStorage(context, displayName, "image/png", false, -1L,
                outputFile -> {
                    try (OutputStream outputStream = new FileOutputStream(outputFile)) {
                        writePng(bitmap, MediaFileWriter.withDiskBudget(outputStream, outputFile.getParentFile()));
                    }
                });
    }

    private static File saveStreamWithLegacyStorage(
            Context context,
            InputStream inputStream,
            String displayName,
            String mimeType,
            long outputBytes
    ) throws Exception {
        boolean video = mimeType != null && mimeType.startsWith("video/");
        return saveWithLegacyStorage(context, displayName, mimeType, video, outputBytes,
                outputFile -> {
                    try (OutputStream outputStream = new FileOutputStream(outputFile)) {
                        copy(inputStream, MediaFileWriter.withDiskBudget(outputStream, outputFile.getParentFile()));
                    }
                });
    }

    /** Runs a converter into the gallery, and cleans up after itself when it throws. */
    private static SaveResult saveConvertedSticker(
            Context context,
            byte[] animatedWebp,
            String displayName,
            String mimeType,
            boolean video,
            String label
    ) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri uri = savePendingWithMediaStore(context, displayName, mimeType, video, -1L,
                    (resolver, pendingUri, directory) -> {
                        try (OutputStream output = resolver.openOutputStream(pendingUri)) {
                            if (output == null) {
                                throw new IllegalStateException("MediaStore output stream returned null");
                            }
                            AnimatedWebpGifConverter.convert(animatedWebp,
                                    MediaFileWriter.withDiskBudget(output, directory));
                        }
                    });
            return SaveResult.success(savedPath(context, uri, displayName, video), uri.toString(), label);
        }

        File outputFile = saveWithLegacyStorage(context, displayName, mimeType, video, -1L,
                file -> {
                    try (OutputStream output = new FileOutputStream(file)) {
                        AnimatedWebpGifConverter.convert(animatedWebp,
                                MediaFileWriter.withDiskBudget(output, file.getParentFile()));
                    }
                });
        return SaveResult.success(outputFile.getAbsolutePath(), outputFile.getAbsolutePath(), label);
    }

    // Carries the converter's own API 26 floor up to the call site, which is behind a Q check.
    @RequiresApi(26)
    private static Uri saveAnimatedWebpMp4WithMediaStore(
            Context context,
            byte[] animatedWebp,
            String displayName
    ) throws Exception {
        return savePendingWithMediaStore(context, displayName, "video/mp4", true, -1L,
                (resolver, uri, directory) -> {
                    try (ParcelFileDescriptor output = resolver.openFileDescriptor(uri, "w")) {
                        if (output == null) {
                            throw new IllegalStateException("MediaStore file descriptor returned null");
                        }
                        AnimatedWebpMp4Converter.convert(animatedWebp, output.getFileDescriptor(), directory);
                    }
                });
    }

    private static File saveAnimatedWebpMp4WithLegacyStorage(
            Context context,
            byte[] animatedWebp,
            String displayName
    ) throws Exception {
        return saveWithLegacyStorage(context, displayName, "video/mp4", true, -1L,
                outputFile -> AnimatedWebpMp4Converter.convert(animatedWebp, outputFile.getAbsolutePath()));
    }

    private static String stickerRelativePath(boolean video) {
        String path = DownloadDestination.resolve(
                Settings.DOWNLOAD_STICKER_PATH.get(),
                DownloadDestination.Kind.STICKER
        );
        return path;
    }

    private static String displayPath(String displayName, boolean video) {
        return stickerRelativePath(video) + "/" + displayName;
    }

    /**
     * Where a sticker actually landed.
     *
     * <p>The display name handed to MediaStore is a request. Saving the same sticker twice gets
     * the second one a suffix of the provider's choosing, and telling the reader the name that
     * was asked for points them at a file that is not the one just written. Falls back to the
     * requested name, because a path that is probably right beats no path at all.
     */
    private static String savedPath(Context context, Uri uri, String requestedName, boolean video) {
        String name = requestedName;
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String saved = cursor.getString(0);
                if (saved != null && !saved.isEmpty()) name = saved;
            }
        } catch (RuntimeException unreadable) {
            debugLog("[Morphe Stickers] could not read the saved name back: " + unreadable);
        }
        return displayPath(name, video);
    }

    private static void writePng(Bitmap bitmap, OutputStream outputStream) throws Exception {
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
            throw new IllegalStateException("PNG encoding failed");
        }
        outputStream.flush();
    }

    private static void completePending(
            Context context,
            ContentResolver resolver,
            Uri uri,
            ContentValues values
    ) throws IOException {
        // The row went in under a placeholder, so a publish that does not name the file leaves
        // one called hushfeed-pending-1a2b3c.webp in the gallery. Nothing here reads the name
        // back afterwards, so this is where that gets caught.
        if (values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) == null) {
            throw new IOException("Sticker publish did not name the file");
        }
        if (resolver.update(uri, values, null, null) != 1) {
            throw new IOException("Could not publish sticker");
        }
        try {
            MediaCache.clearPending(context, uri);
        } catch (IOException error) {
            // Reconciliation checks IS_PENDING before deleting a journaled URI, so a completed
            // row remains safe if this final cleanup write is interrupted.
            Logger.printException(() -> "Could not clear media publication journal", error);
        }
    }

    private static void discardPending(
            Context context,
            ContentResolver resolver,
            Uri uri,
            Throwable failure
    ) {
        boolean deleted = false;
        try {
            deleted = resolver.delete(uri, null, null) > 0;
        } catch (Throwable cleanup) {
            failure.addSuppressed(cleanup);
        }
        if (deleted) {
            try {
                MediaCache.clearPending(context, uri);
            } catch (IOException journalError) {
                failure.addSuppressed(journalError);
            }
        }
    }

    private static void copy(InputStream inputStream, OutputStream outputStream) throws Exception {
        MediaFileWriter.copy(inputStream, outputStream, MAX_STICKER_BYTES);
    }

    /**
     * The whole sticker in memory, which is what the animated converters need. The cap is the
     * reason this is safe to do: the URL is server supplied and a body with no end to it would
     * otherwise be read until the process runs out of heap.
     */
    private static byte[] readFully(InputStream inputStream) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(inputStream, output);
        return output.toByteArray();
    }

    private static MediaFormat detectMediaFormat(
            String contentType,
            String url,
            BufferedInputStream inputStream,
            boolean animated
    ) throws Exception {
        byte[] header = peekHeader(inputStream);
        String mimeType = normalizedContentType(contentType);
        String extension = extensionFromUrl(url);

        if (isMp4(header) || "video/mp4".equals(mimeType) || "m4v".equals(extension)) {
            return MediaFormat.passthrough("video/mp4", "mp4", true, "MP4");
        }
        if (isWebm(header) || "video/webm".equals(mimeType) || "webm".equals(extension)) {
            return MediaFormat.passthrough("video/webm", "webm", true, "WebM");
        }
        if (mimeType != null && mimeType.startsWith("video/")) {
            String videoExtension = extension == null ? "mp4" : extension;
            return MediaFormat.passthrough(
                    mimeType,
                    videoExtension,
                    true,
                    videoExtension.toUpperCase(java.util.Locale.ROOT)
            );
        }
        if (isGif(header) || "image/gif".equals(mimeType) || "gif".equals(extension)) {
            return MediaFormat.passthrough("image/gif", "gif", false, "GIF");
        }
        if (isWebp(header) || "image/webp".equals(mimeType) || "webp".equals(extension)) {
            // The model calls anything whose type merely contains "webp" animated, so the
            // VP8X animation flag decides it whenever the header was long enough to carry one.
            boolean readableHeader = header.length >= 21 && isWebp(header);
            if (readableHeader ? isAnimatedWebp(header) : animated) {
                return MediaFormat.animated(Settings.DOWNLOAD_STICKER_FORMAT.get());
            }
            return MediaFormat.png();
        }
        if (isPng(header) || isJpeg(header) || (mimeType != null && mimeType.startsWith("image/"))) {
            return MediaFormat.png();
        }
        if (animated && mimeType != null) {
            String safeExtension = extension == null ? "bin" : extension;
            return MediaFormat.passthrough(
                    mimeType,
                    safeExtension,
                    false,
                    safeExtension.toUpperCase(java.util.Locale.ROOT)
            );
        }
        throw new IllegalStateException("Unsupported sticker media type");
    }

    private static byte[] peekHeader(BufferedInputStream inputStream) throws Exception {
        inputStream.mark(32);
        byte[] buffer = new byte[32];
        int count = inputStream.read(buffer);
        inputStream.reset();
        if (count == buffer.length) return buffer;
        byte[] result = new byte[Math.max(count, 0)];
        if (count > 0) System.arraycopy(buffer, 0, result, 0, count);
        return result;
    }

    private static String normalizedContentType(String contentType) {
        if (contentType == null) return null;
        int separator = contentType.indexOf(';');
        // Locale.ROOT: a Turkish phone lowercases "IMAGE/GIF" to "ımage/gıf", which matches nothing.
        String value = (separator >= 0 ? contentType.substring(0, separator) : contentType).trim()
                .toLowerCase(java.util.Locale.ROOT);
        return value.isEmpty() || "application/octet-stream".equals(value) ? null : value;
    }

    private static boolean isPng(byte[] header) {
        return header.length >= 8 && (header[0] & 0xff) == 0x89 && header[1] == 'P'
                && header[2] == 'N' && header[3] == 'G';
    }

    private static boolean isJpeg(byte[] header) {
        return header.length >= 3 && (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8
                && (header[2] & 0xff) == 0xff;
    }

    private static boolean isGif(byte[] header) {
        return header.length >= 6 && header[0] == 'G' && header[1] == 'I' && header[2] == 'F'
                && header[3] == '8' && (header[4] == '7' || header[4] == '9') && header[5] == 'a';
    }

    private static boolean isWebp(byte[] header) {
        return header.length >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F'
                && header[3] == 'F' && header[8] == 'W' && header[9] == 'E' && header[10] == 'B'
                && header[11] == 'P';
    }

    private static boolean isAnimatedWebp(byte[] header) {
        return header.length >= 21 && isWebp(header) && header[12] == 'V' && header[13] == 'P'
                && header[14] == '8' && header[15] == 'X' && (header[20] & 0x02) != 0;
    }

    private static boolean isMp4(byte[] header) {
        return header.length >= 8 && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p';
    }

    private static boolean isWebm(byte[] header) {
        return header.length >= 4 && (header[0] & 0xff) == 0x1a && (header[1] & 0xff) == 0x45
                && (header[2] & 0xff) == 0xdf && (header[3] & 0xff) == 0xa3;
    }

    private static StickerAsset findStickerAsset(Object model) {
        Object source;
        synchronized (STICKER_SOURCES) {
            source = STICKER_SOURCES.get(model);
        }
        StickerAsset sourceAsset = findSourceStickerAsset(source);
        if (sourceAsset != null) {
            HookStatus.bound(HOOK_FAMILY, source.getClass().getName() + "#stickerBase");
            return sourceAsset;
        }

        UrlModel urlModel = findUrlModel(model);
        List<String> urls = usableUrls(urlModel);
        if (urls.isEmpty()) {
            if (source != null && !hasStickerItemMembers(source)) {
                HookStatus.missingMember(
                        HOOK_FAMILY,
                        "source sticker",
                        source.getClass().getName(),
                        "stickerBase or currentImage()");
            }
            return null;
        }
        // The preview model says nothing reliable about motion: its first flag is false for
        // static and animated set stickers alike. The downloaded file's header decides.
        return new StickerAsset(urls, false);
    }

    /**
     * The sticker behind the sheet, read off TikTok's StickerItem, the source the patch
     * registers. Its members keep their names on every build, unlike the classes that convert
     * it, which R8 renames each time (StickerSourceFixturesTest holds each declared build to
     * the names read here).
     *
     * <p>The sheet shows stickerBase.image: TikTok builds its preview from that image, with the
     * thumbnail as the low resolution. currentImage() is the variant a sender picked, else that
     * same image, and the thumbnail is a smaller still. Each is tried only when the one before
     * it has no HTTPS address.
     */
    private static StickerAsset findSourceStickerAsset(Object source) {
        if (source == null) return null;

        Object base = readNamedField(source, "stickerBase");
        Boolean movesByType = movesByType(readNamedField(base, "stickerType"));
        Object[] images = {
                readNamedField(base, "image"),
                invokeNoArg(source, "currentImage"),
                readNamedField(base, "thumbnail"),
        };
        for (int i = 0; i < images.length; i++) {
            List<String> urls = usableUrlList(readNamedField(images[i], "urlList"));
            if (urls.isEmpty()) continue;

            Object imageTypeValue = readNamedField(images[i], "imageType");
            String imageType = imageTypeValue == null
                    ? ""
                    : imageTypeValue.toString().toLowerCase(java.util.Locale.ROOT);
            boolean thumbnail = i == images.length - 1;
            boolean animated;
            if (thumbnail) {
                animated = false;
            } else if (movesByType != null) {
                animated = movesByType;
            } else {
                animated = imageType.contains("anim") || imageType.contains("webp") || imageType.contains("gif");
            }
            debugLog("[Morphe Stickers] selected StickerItem " + (thumbnail ? "thumbnail " : "image ")
                    + summarizeUrl(urls.get(0)) + " type=" + imageType + " animated=" + animated);
            return new StickerAsset(urls, animated);
        }
        return null;
    }

    // StickerBase.stickerType: the numbers TikTok's sticker type enum hands out through getType(),
    // under the names that enum gives them. Only the enum's own class name changes per build.
    static final int TYPE_STATIC = 1;
    static final int TYPE_ANIMATED = 2;
    static final int TYPE_VIDEO_STICKER_STATIC = 3;
    static final int TYPE_VIDEO_STICKER_ANIMATED = 4;
    static final int TYPE_AIMOJI_STICKER_STATIC = 5;
    static final int TYPE_GIPHY = 6;
    static final int TYPE_THIRD_PARTY_GIPHY = 7;
    static final int TYPE_THIRD_PARTY_TENOR = 8;
    static final int TYPE_PHOTO_COMMENT_STICKER = 15;

    /**
     * Whether TikTok itself treats a sticker of this type as moving: its converters hand the
     * static types a static URL and the animated ones an animated URL, and GIPHY and Tenor
     * stickers are GIFs. Null for a type they don't sort, where the image type is the hint.
     */
    static Boolean movesByType(Object stickerType) {
        if (!(stickerType instanceof Integer)) return null;
        switch ((Integer) stickerType) {
            case TYPE_ANIMATED:
            case TYPE_VIDEO_STICKER_ANIMATED:
            case TYPE_GIPHY:
            case TYPE_THIRD_PARTY_GIPHY:
            case TYPE_THIRD_PARTY_TENOR:
                return Boolean.TRUE;
            case TYPE_STATIC:
            case TYPE_VIDEO_STICKER_STATIC:
            case TYPE_AIMOJI_STICKER_STATIC:
            case TYPE_PHOTO_COMMENT_STICKER:
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    /**
     * Whether the source still looks like a StickerItem. Checked only after the source and the
     * preview both failed, so a sticker with no HTTPS address is not reported as a renamed model.
     */
    private static boolean hasStickerItemMembers(Object source) {
        return hasNamedField(source.getClass(), "stickerBase")
                || hasNoArgMethod(source.getClass(), "currentImage");
    }

    private static boolean hasNamedField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                current.getDeclaredField(name);
                return true;
            } catch (NoSuchFieldException ignored) {
                // Keep climbing.
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    private static boolean hasNoArgMethod(Class<?> type, String name) {
        try {
            type.getMethod(name);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static List<String> usableUrlList(Object value) {
        if (!(value instanceof List<?>)) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (item == null) continue;
            // Over TLS only. These bytes are fetched and handed to a native WebP decoder, so a
            // cleartext mirror is an unauthenticated body reaching a parser written in C.
            String url = item.toString().trim();
            if (MediaTransport.hasAllowedShape(url)) result.add(url);
        }
        return result.isEmpty() ? Collections.emptyList() : List.copyOf(result);
    }

    private static Object invokeNoArg(Object instance, String methodName) {
        if (instance == null) return null;
        try {
            java.lang.reflect.Method method = instance.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(instance);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object readNamedField(Object instance, String fieldName) {
        if (instance == null) return null;
        Class<?> current = instance.getClass();
        while (current != null) {
            try {
                java.lang.reflect.Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(instance);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static UrlModel findUrlModel(Object model) {
        if (model == null) return null;

        Class<?> current = model.getClass();
        while (current != null) {
            java.lang.reflect.Field[] fields = current.getDeclaredFields();
            for (java.lang.reflect.Field field : fields) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(model);
                    if (value instanceof UrlModel) {
                        return (UrlModel) value;
                    }
                } catch (Throwable ignored) {
                    // Keep probing other fields.
                }
            }
            current = current.getSuperclass();
        }

        return null;
    }

    private static String extensionFromUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null) return null;
            int dotIndex = path.lastIndexOf('.');
            if (dotIndex < 0 || dotIndex == path.length() - 1) return null;
            String extension = path.substring(dotIndex + 1).toLowerCase(java.util.Locale.ROOT);
            return extension.length() <= 5 ? extension : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<String> usableUrls(UrlModel model) {
        if (model == null) return Collections.emptyList();
        try {
            return usableUrlList(model.getUrlList());
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    /**
     * The sheet's own action buttons, found by the sheet's shape.
     *
     * <p>TikTok's button is a TextView subclass whose name changes with every build: X.0GSy on
     * 46.2.3, X.02Lg on 47.0.3, where X.0GSy is an unrelated class. Naming it is what left
     * 47.0.3 with no Save media button (issue #23). What holds on all five retained builds is
     * that the row keeps its two actions in fields of one shared type, while its caption is the
     * only field of its own type. So the actions are the largest set of attached TextView fields
     * declared with the same type, and a lone TextView is never one of them.
     */
    static List<View> findActionButtons(View sheetView) {
        java.util.Map<Class<?>, List<View>> byType = new java.util.LinkedHashMap<>();
        for (Class<?> type = sheetView.getClass();
             type != null && !type.getName().startsWith("android."); type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        || !TextView.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(sheetView);
                    if (!(value instanceof View) || ((View) value).getParent() == null) continue;
                    List<View> sameType = byType.get(field.getType());
                    if (sameType == null) byType.put(field.getType(), sameType = new ArrayList<>());
                    if (!sameType.contains(value)) sameType.add((View) value);
                } catch (Throwable unreadable) {
                    // A field this build will not hand over is not an action button.
                }
            }
        }
        List<View> actions = Collections.emptyList();
        for (List<View> sameType : byType.values()) {
            if (sameType.size() >= 2 && sameType.size() > actions.size()) actions = sameType;
        }
        return actions;
    }

    /** The action that sits last in its parent, whatever order the fields were declared in. */
    private static View lastChildOf(ViewGroup parent, List<View> views) {
        View last = views.get(views.size() - 1);
        for (View view : views) {
            if (parent.indexOfChild(view) > parent.indexOfChild(last)) last = view;
        }
        return last;
    }

    private static ViewGroup findCommonParent(List<View> views) {
        if (views.size() < 2) return null;

        View first = views.get(0);
        View second = views.get(1);
        ViewParent parent = first.getParent();
        while (parent != null) {
            if (isAncestor(parent, second)) {
                return parent instanceof ViewGroup ? (ViewGroup) parent : null;
            }
            parent = parent.getParent();
        }

        return null;
    }

    private static boolean isAncestor(ViewParent candidate, View view) {
        ViewParent parent = view.getParent();
        while (parent != null) {
            if (parent == candidate) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private static boolean hasSaveImageButton(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (SAVE_BUTTON_TAG.equals(parent.getChildAt(i).getTag())) return true;
        }
        return false;
    }

    private static ViewGroup.LayoutParams cloneLayoutParams(ViewGroup.LayoutParams params) {
        if (params instanceof LinearLayout.LayoutParams) {
            return new LinearLayout.LayoutParams((LinearLayout.LayoutParams) params);
        }
        if (params instanceof ViewGroup.MarginLayoutParams) {
            return new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) params);
        }
        if (params != null) {
            return new ViewGroup.LayoutParams(params);
        }
        return new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static String summarizeUrl(String url) {
        if (url == null || url.trim().isEmpty()) return "null";

        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path != null && path.length() > 96) {
                path = path.substring(0, 96) + "...";
            }
            return uri.getHost() + path;
        } catch (Throwable ignored) {
            int queryIndex = url.indexOf('?');
            String withoutQuery = queryIndex >= 0 ? url.substring(0, queryIndex) : url;
            return withoutQuery.length() <= 96 ? withoutQuery : withoutQuery.substring(0, 96) + "...";
        }
    }

    private static void debugLog(String message) {
        if (BaseSettings.DEBUG.get()) {
            Logger.printInfo(() -> message);
        }
    }

    /** Package-private with {@link #stickerSaveWork}, which a test builds directly. */
    static final class StickerAsset {
        final String url;
        final List<String> urls;
        final boolean animated;
        final boolean privateMedia;
        final byte[] decryptKey;

        StickerAsset(String url, boolean animated) {
            this(Collections.singletonList(url), animated);
        }

        StickerAsset(List<String> urls, boolean animated) {
            this(urls, animated, false, null);
        }

        StickerAsset(List<String> urls, boolean animated, boolean privateMedia, byte[] decryptKey) {
            this.urls = urls == null || urls.isEmpty()
                    ? Collections.emptyList() : List.copyOf(urls);
            this.url = this.urls.isEmpty() ? "" : this.urls.get(0);
            this.animated = animated;
            this.privateMedia = privateMedia;
            this.decryptKey = decryptKey == null ? null : Arrays.copyOf(decryptKey, decryptKey.length);
        }
    }

    static final class MediaFormat {
        final String mimeType;
        final String extension;
        final boolean video;
        final boolean convertToPng;
        final boolean convertToMp4;
        final boolean convertToGif;
        final String label;

        private MediaFormat(
                String mimeType,
                String extension,
                boolean video,
                boolean convertToPng,
                boolean convertToMp4,
                boolean convertToGif,
                String label
        ) {
            this.mimeType = mimeType;
            this.extension = extension;
            this.video = video;
            this.convertToPng = convertToPng;
            this.convertToMp4 = convertToMp4;
            this.convertToGif = convertToGif;
            this.label = label;
        }

        static MediaFormat png() {
            return new MediaFormat("image/png", "png", false, true, false, false, "PNG");
        }

        static MediaFormat mp4() {
            return new MediaFormat("video/mp4", "mp4", true, false, true, false, "MP4");
        }

        static MediaFormat gif() {
            return new MediaFormat("image/gif", "gif", false, false, false, true, "GIF");
        }

        static MediaFormat webp() {
            return passthrough("image/webp", "webp", false, "WebP");
        }

        /**
         * What an animated sticker is written as. WebP is the file TikTok sent, byte for byte,
         * which is also what a messaging app that takes animated stickers wants; the other two
         * are converted here. Anything unrecognised keeps the old behaviour.
         */
        static MediaFormat animated(String choice) {
            if ("gif".equals(choice)) return gif();
            if ("webp".equals(choice)) return webp();
            return mp4();
        }

        static MediaFormat passthrough(String mimeType, String extension, boolean video, String label) {
            return new MediaFormat(mimeType, extension, video, false, false, false, label);
        }
    }

    private static final class SaveResult {
        final boolean success;
        final String message;
        final String path;

        private SaveResult(boolean success, String message, String path) {
            this.success = success;
            this.message = message;
            this.path = path;
        }

        static SaveResult success(String path, String uri, String format) {
            // The four other savers say "saved to DCIM/TikTok/..."; this said "saved as MP4"
            // and kept the path for the debug log. The folder is what a reader needs, not the
            // format, because the format is obvious from the file and the folder is not.
            String folder = path;
            int slash = folder.lastIndexOf('/');
            if (slash > 0) folder = folder.substring(0, slash);
            return new SaveResult(true, L10n.f("Sticker saved to %1$s", folder),
                    path + " (" + uri + ")");
        }

        static SaveResult failure(String message) {
            return new SaveResult(false, message, null);
        }
    }
}
