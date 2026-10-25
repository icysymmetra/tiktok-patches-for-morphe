/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.ViewGroup;
import android.widget.ImageView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorMessages;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorService;
import app.morphe.extension.tiktok.blockauthor.BlockGlyphDrawable;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.blockauthor.VideoAuthor;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native share actions bound to the post and account that opened their sheet (#153). */
public final class ShareBlockAuthor {
    static final String KEY = "hushfeed_block_author";
    private static final String POST_PACKAGE = "com.ss.android.ugc.aweme.share.improve.pkg.AwemeSharePackage";
    private static final List<Built> BUILT = new ArrayList<>();
    private static final Map<String, Pending> IN_FLIGHT = new HashMap<>();
    private static final Map<String, WeakReference<Creator>> CREATORS = new HashMap<>();

    private ShareBlockAuthor() {}

    /** Shared while any sheet, callback or Undo still owns this account/creator pair. */
    private static final class Creator {
        volatile boolean blocked;
    }

    private static synchronized Creator creator(String account, String uid) {
        for (Iterator<WeakReference<Creator>> each = CREATORS.values().iterator(); each.hasNext();) {
            if (each.next().get() == null) each.remove();
        }
        String key = account + ":" + uid;
        WeakReference<Creator> reference = CREATORS.get(key);
        Creator creator = reference == null ? null : reference.get();
        if (creator == null) {
            creator = new Creator();
            CREATORS.put(key, new WeakReference<>(creator));
        }
        return creator;
    }

    private static final class Post {
        final Object aweme;
        final VideoAuthor author;

        Post(Object aweme, VideoAuthor author) {
            this.aweme = aweme;
            this.author = author;
        }
    }

    private static final class Built {
        final WeakReference<Object> action;
        final WeakReference<Object> sharePackage;
        final WeakReference<Object> aweme;
        final VideoAuthor author;
        final String account;
        final Creator creator;
        volatile boolean retired;

        Built(Object action, Object sharePackage, Post post, String account) {
            this.action = new WeakReference<>(action);
            this.sharePackage = new WeakReference<>(sharePackage);
            this.aweme = new WeakReference<>(post.aweme);
            this.author = post.author;
            this.account = account;
            this.creator = creator(account, post.author.uid);
        }

        boolean matches(Object from, Post post, String actor) {
            return !retired && post != null && sharePackage.get() == from && aweme.get() == post.aweme
                    && account.equals(actor) && author.uid.equals(post.author.uid)
                    && Objects.equals(author.secUid, post.author.secUid)
                    && Objects.equals(author.awemeId, post.author.awemeId);
        }
    }

    private static final class Pending {
        final Built built;
        final boolean undo;
        final BlockAuthorService.Callback callback;
        final AtomicBoolean completed = new AtomicBoolean();
        Pending nextUndo;

        Pending(Built built, boolean undo, BlockAuthorService.Callback callback) {
            this.built = built;
            this.undo = undo;
            this.callback = callback;
        }

        String key() { return built.account + ":" + built.author.uid; }
    }

    private static boolean enabled() {
        return SettingsStatus.shareSheetEnabled && Settings.SHARE_BLOCK_AUTHOR.get();
    }

    /** Finished native row. Remove stale owned actions even when disabled or on another surface. */
    public static List<?> actions(Object sharePackage, List<?> actions) {
        if (actions == null) return null;
        List<Object> row = new ArrayList<>(actions.size() + 1);
        Built reusable = null;
        try {
            Post post = enabled() ? post(sharePackage) : null;
            String account = uid(SignedInUser.id());
            boolean eligible = post != null && account != null && !account.equals(post.author.uid);
            if (eligible) ShareActionCatalog.observe(Collections.singletonList(new CatalogEntry()), ShareSurface.VIDEO);
            eligible &= !hidden();
            for (Object item : actions) {
                Built built = find(item);
                if (built == null) row.add(item);
                else if (eligible && reusable == null && built.matches(sharePackage, post, account)) reusable = built;
                else if (built != reusable) built.retired = true;
            }
            int report = reportIndex(row);
            if (eligible && report >= 0) {
                Object action = reusable == null ? null : reusable.action.get();
                if (action == null) {
                    Activity activity = Utils.getVisibleActivity();
                    if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                        action = newAction(activity, post.aweme);
                        if (action != null) remember(new Built(action, sharePackage, post, account));
                    }
                }
                if (action != null) {
                    row.add(report + 1, action);
                    HookStatus.bound(ShareModelFilter.FAMILY, "block author");
                }
            } else if (reusable != null) reusable.retired = true;
            return sameRow(actions, row) ? actions : row;
        } catch (Throwable failure) {
            // A failed rebuild must not keep an action for an earlier package or account.
            for (Object item : actions) {
                Built built = find(item);
                if (built != null) built.retired = true;
            }
            row.clear();
            for (Object item : actions) if (!owns(item)) row.add(item);
            HookStatus.threw(ShareModelFilter.FAMILY, "block author", failure);
            Logger.printException(() -> "Could not add the share-sheet block action", failure);
            return sameRow(actions, row) ? actions : row;
        }
    }

    private static boolean sameRow(List<?> before, List<?> after) {
        if (before.size() != after.size()) return false;
        for (int i = 0; i < before.size(); i++) if (before.get(i) != after.get(i)) return false;
        return true;
    }

    private static int reportIndex(List<?> row) {
        for (int i = 0; i < row.size(); i++) {
            if ("report".equals(Reflect.string(row.get(i), "key", "key"))) return i;
        }
        return -1;
    }

    private static boolean hidden() {
        if (Settings.HIDE_SHARE_ACTIONS.get()) return true;
        String label = ShareActionCatalog.canonical(L10n.t("Block this creator"));
        for (String entry : ShareModelFilter.hiddenItems(ShareSurface.VIDEO).split("[,\\n]")) {
            String key = ShareActionCatalog.canonical(entry);
            if (key.equals(ShareActionCatalog.canonical(KEY)) || key.equals("block this creator")
                    || key.equals(label)) return true;
        }
        return false;
    }

    private static Post post(Object sharePackage) {
        if (sharePackage == null || !POST_PACKAGE.equals(sharePackage.getClass().getName())
                || !"aweme".equals(Reflect.string(sharePackage, "getItemType", "itemType"))) return null;
        Object aweme = Reflect.readField(sharePackage, "aweme");
        if (!(aweme instanceof Aweme)) return null;
        Object user = Reflect.invoke(aweme, "getAuthor");
        String uid = uid(Reflect.string(user, "getUid", "uid"));
        if (uid == null) return null;
        String secUid = Reflect.string(user, "getSecUid", "secUid");
        String name = Reflect.firstNonBlank(Reflect.string(user, "getUniqueId", "uniqueId"),
                Reflect.string(user, "getNickname", "nickname"));
        return new Post(aweme, new VideoAuthor(uid, secUid, name, Reflect.string(aweme, "getAid", "aid")));
    }

    private static String uid(String value) {
        return value != null && value.matches("[1-9][0-9]*") ? value : null;
    }

    private static synchronized Built find(Object action) {
        Built found = null;
        for (Iterator<Built> each = BUILT.iterator(); each.hasNext();) {
            Built built = each.next();
            Object held = built.action.get();
            if (held == null) each.remove();
            else if (held == action) found = built;
        }
        return found;
    }

    private static synchronized void remember(Built built) {
        find(null);
        BUILT.add(built);
    }

    public static boolean owns(Object action) { return action != null && find(action) != null; }
    public static String key(Object action) { return owns(action) ? KEY : null; }
    public static String label(Object action) { return owns(action) ? L10n.t("Block this creator") : null; }

    /** Called after the native drawable store on every bind, including already-attached cells. */
    public static Drawable icon(Object action) {
        if (!owns(action)) return null;
        Context context = Utils.getVisibleActivity();
        if (context == null) context = Utils.getContext();
        return new BlockGlyphDrawable(SettingsUi.textPrimaryOn(SettingsUi.isDarkContext(context)),
                SettingsUi.dp(context, 2));
    }

    public static void bindIcon(Object action, ImageView image) {
        if (!owns(action)) return;
        Drawable icon = NativeShareIcon.restyle(image.getDrawable(), BlockGlyphDrawable::new);
        if (icon == null) icon = icon(action);
        if (icon != null) image.setImageDrawable(icon);
    }

    /** Only owned carriers are consumed. Never fall through to their original Report behavior. */
    public static boolean click(Object action, Context context, Object sharePackage) {
        Built built = find(action);
        if (built == null) return false;
        try {
            if (!enabled() || hidden() || built.creator.blocked
                    || !built.matches(sharePackage, post(sharePackage), SignedInUser.id())) return true;
            Activity activity = context instanceof Activity ? (Activity) context : Utils.getVisibleActivity();
            WeakReference<Activity> from = new WeakReference<>(activity);
            request(built, false, result -> {
                if (!built.account.equals(SignedInUser.id())) return;
                if (result != BlockAuthorService.Result.CONFIRMED) {
                    notice(BlockAuthorMessages.blockFailure(Utils.getContext(), result, built.author.label()));
                    return;
                }
                // An older banner's Undo already follows this block. Don't replace it with another.
                synchronized (IN_FLIGHT) {
                    Pending pending = IN_FLIGHT.get(built.account + ":" + built.author.uid);
                    if (pending != null && pending.undo) return;
                }
                AtomicBoolean undone = new AtomicBoolean();
                Runnable undo = () -> {
                    if (!built.account.equals(SignedInUser.id()) || !undone.compareAndSet(false, true)) return;
                    request(built, true, outcome -> {
                        if (built.account.equals(SignedInUser.id())) notice(BlockAuthorMessages.unblockResult(
                                Utils.getContext(), outcome, built.author.label()));
                    });
                };
                String message = L10n.f("Blocked %1$s", built.author.label());
                Feedback stand = feedbackForTests;
                if (stand != null) stand.undo(message, undo);
                else {
                    Activity owner = from.get();
                    ViewGroup root = owner == null || owner.isFinishing() || owner.isDestroyed()
                            ? null : owner.findViewById(android.R.id.content);
                    BlockAuthorOverlay.showUndoBanner(root, message, undo);
                }
            });
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not block the share package's author", failure);
            notice(BlockAuthorMessages.blockFailure(Utils.getContext(), BlockAuthorService.Result.UNCONFIRMED,
                    built.author.label()));
        }
        return true;
    }

    private static void request(Built built, boolean undo, BlockAuthorService.Callback callback) {
        Pending operation = new Pending(built, undo, callback);
        synchronized (IN_FLIGHT) {
            Pending active = IN_FLIGHT.get(operation.key());
            if (active != null) {
                // The banner has already removed itself. Preserve its opposite operation until
                // the current block finishes, rather than consuming Undo without sending it.
                if (undo && !active.undo && active.nextUndo == null) active.nextUndo = operation;
                return;
            }
            if (!undo && built.creator.blocked) return;
            IN_FLIGHT.put(operation.key(), operation);
        }
        dispatch(operation);
    }

    private static void dispatch(Pending operation) {
        Built built = operation.built;
        BlockAuthorService.Callback complete = result -> {
            if (!operation.completed.compareAndSet(false, true)) return;
            Pending next;
            synchronized (IN_FLIGHT) {
                if (result == BlockAuthorService.Result.CONFIRMED) built.creator.blocked = !operation.undo;
                next = operation.nextUndo;
                if (next == null) IN_FLIGHT.remove(operation.key());
                else IN_FLIGHT.put(operation.key(), next);
            }
            try { operation.callback.onResult(result); }
            finally { if (next != null) dispatch(next); }
        };
        if (!built.account.equals(SignedInUser.id())) {
            complete.onResult(BlockAuthorService.Result.UNCONFIRMED);
            return;
        }
        try {
            Transport stand = transportForTests;
            if (stand != null) stand.request(built.author, built.account, operation.undo, complete);
            else if (operation.undo) BlockAuthorService.unblock(built.author, built.account, complete);
            else BlockAuthorService.block(built.author, built.account, complete);
        } catch (RuntimeException failure) {
            complete.onResult(BlockAuthorService.Result.UNCONFIRMED);
            Logger.printException(() -> "Could not queue the share-sheet block request", failure);
        }
    }

    private static void notice(String message) {
        Feedback stand = feedbackForTests;
        if (stand != null) stand.notice(message);
        else Utils.showToastLong(message);
    }

    /** Catalog discovery uses the same localized label without guessing obfuscated getter names. */
    private static final class CatalogEntry {
        public String key() { return KEY; }
        public String getLabel() { return L10n.t("Block this creator"); }
    }

    interface Native { Object newAction(Activity activity, Object aweme); }
    interface Transport {
        void request(VideoAuthor author, String account, boolean undo, BlockAuthorService.Callback callback);
    }
    interface Feedback { void undo(String message, Runnable undo); void notice(String message); }
    static volatile Native nativeForTests;
    static volatile Transport transportForTests;
    static volatile Feedback feedbackForTests;

    /** A native Report carrier from the shared bridge the patch fills in. */
    static Object newAction(Activity activity, Object aweme) {
        Native stand = nativeForTests;
        return stand == null ? OwnedShareActions.newAction(activity, aweme) : stand.newAction(activity, aweme);
    }
}
