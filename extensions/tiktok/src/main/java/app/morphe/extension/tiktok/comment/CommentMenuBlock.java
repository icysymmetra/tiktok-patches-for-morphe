/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import android.app.Activity;
import android.view.ViewGroup;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorMessages;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorService;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.blockauthor.VideoAuthor;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Block this commenter in the menu a long press on a comment opens (#169).
 *
 * <p>TikTok builds that menu as a list of action rows and hands the finished list to the panel's
 * adapter in one place, which the patch routes through {@link #items}. The owned row is built
 * through the same constructor as TikTok's Report row and goes right after it, taking Report's
 * bottom corners when Report closes its group. A tap closes the menu, blocks through the same
 * BlockApi as Block this creator, and then posts TikTok's own user-blocked event: the one a block
 * from a profile posts, and the one the open comment list answers by dropping that account's
 * comments.
 */
public final class CommentMenuBlock {
    /** TikTok's own key for its profile Block action. The menu's click log carries the row's key. */
    static final String KEY = "block_user";
    static final String FAMILY = "comment menu block";
    private static final String COMMENT = "com.ss.android.ugc.aweme.comment.model.Comment";
    private static final String DELETE_VM =
            "com.ss.android.ugc.aweme.commentv2.commentlist.viewmodel.CommentDeleteVM";
    private static final String DIALOG = "androidx.fragment.app.DialogFragment";
    /** The style's corner flags: round the top, round the bottom. */
    private static final int BOTTOM = 0x10;

    private static final List<Built> BUILT = new ArrayList<>();
    private static final Map<String, Pending> IN_FLIGHT = new HashMap<>();
    /** Report styles whose bottom corners went to an owned row, put back before every rebuild. */
    private static final List<Moved> MOVED = new ArrayList<>();
    /** TikTok's block glyph, as its profile Block action draws it. The patch fills it in. */
    private static volatile int icon;

    private CommentMenuBlock() {}

    /** Called by the patch with the icon id it resolved from TikTok's profile Block action. */
    public static void setIcon(int id) {
        icon = id;
    }

    private static boolean enabled() {
        return SettingsStatus.commentToolsEnabled && Settings.BLOCK_FROM_COMMENT_MENU.get();
    }

    // ---- the menu ---------------------------------------------------------------------

    /**
     * The panel's finished rows on their way into its adapter. Owned rows from an earlier pass
     * come out first, so a rebuilt menu never holds two, or one for another comment.
     */
    public static List<?> items(Object panel, List<?> items) {
        if (items == null) return null;
        List<Object> row = new ArrayList<>(items.size() + 1);
        try {
            restoreMovedCorners();
            for (Object item : items) {
                Built built = find(item);
                if (built == null) row.add(item);
                else built.retired = true;
            }
            if (enabled()) {
                Object owned = build(panel, row);
                if (owned != null) HookStatus.bound(FAMILY, "menu row");
            }
            return sameRow(items, row) ? items : row;
        } catch (Throwable failure) {
            row.clear();
            for (Object item : items) {
                Built built = find(item);
                if (built == null) row.add(item);
                else built.retired = true;
            }
            HookStatus.threw(FAMILY, "menu row", failure);
            Logger.printException(() -> "Could not add Block this commenter to the comment menu", failure);
            return sameRow(items, row) ? items : row;
        }
    }

    /** Adds the owned row after Report, or nothing when this comment can't be blocked from here. */
    private static Object build(Object panel, List<Object> row) throws ReflectiveOperationException {
        int report = reportIndex(row);
        if (report < 0 || panel == null) return null;
        Object comment = commentOf(panel);
        Object user = comment == null ? null : Reflect.property(comment, "getUser", "user");
        if (user == null) return null;
        String uid = uid(Reflect.string(user, "getUid", "uid"));
        String account = uid(SignedInUser.id());
        // Signed out, no readable commenter, or your own comment: no row.
        if (uid == null || account == null || account.equals(uid)) return null;

        Object template = row.get(report);
        Shape shape = Shape.of(template.getClass());
        if (shape == null) return null;
        Object reportStyle = shape.style.get(template);
        if (reportStyle == null) return null;
        int corners = shape.corners.getInt(reportStyle);
        Object style = shape.newStyle(reportStyle, corners & BOTTOM);
        Integer glyph = icon != 0 ? Integer.valueOf(icon) : (Integer) shape.icon.get(template);

        VideoAuthor author = new VideoAuthor(uid, Reflect.string(user, "getSecUid", "secUid"),
                Reflect.firstNonBlank(Reflect.string(user, "getUniqueId", "uniqueId"),
                        Reflect.string(user, "getNickname", "nickname")),
                Reflect.string(comment, "getCid", "cid"));
        Built built = new Built(panel, comment, user, author, account);
        Object click = Proxy.newProxyInstance(shape.click.getClassLoader(), new Class<?>[]{shape.click},
                (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "invoke":
                            if (arguments == null || arguments.length == 0) click(built);
                            return null;
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return arguments != null && arguments.length == 1 && proxy == arguments[0];
                        case "toString":
                            return "Block this commenter";
                        default:
                            return null;
                    }
                });
        Object owned = shape.constructor.newInstance(KEY, glyph, L10n.t("Block this commenter"), click,
                style, null);
        built.row = new WeakReference<>(owned);
        remember(built);
        if ((corners & BOTTOM) != 0) {
            shape.corners.setInt(reportStyle, corners & ~BOTTOM);
            synchronized (MOVED) {
                MOVED.add(new Moved(reportStyle, shape.corners));
            }
        }
        row.add(report + 1, owned);
        return owned;
    }

    /** The row whose key is TikTok's "report". */
    private static int reportIndex(List<?> row) {
        for (int i = 0; i < row.size(); i++) {
            Object item = row.get(i);
            if (item == null) continue;
            for (Field field : fields(item.getClass())) {
                if (field.getType() != String.class) continue;
                try {
                    if ("report".equals(field.get(item))) return i;
                } catch (IllegalAccessException ignored) {
                    // Not readable, so not the row we're after.
                }
            }
        }
        return -1;
    }

    /** The panel's one Comment field, whatever it is called on this build. */
    private static Object commentOf(Object panel) {
        Object found = null;
        int count = 0;
        for (Field field : fields(panel.getClass())) {
            if (!COMMENT.equals(field.getType().getName())) continue;
            count++;
            try {
                found = field.get(panel);
            } catch (IllegalAccessException ignored) {
                return null;
            }
        }
        if (count != 1) {
            HookStatus.missingMember(FAMILY, "field", panel.getClass().getName(), "one Comment");
            return null;
        }
        return found;
    }

    private static String uidOf(Object comment) {
        Object user = Reflect.property(comment, "getUser", "user");
        return uid(Reflect.string(user, "getUid", "uid"));
    }

    private static String uid(String value) {
        return value != null && value.matches("[1-9][0-9]*") ? value : null;
    }

    private static boolean sameRow(List<?> before, List<?> after) {
        if (before.size() != after.size()) return false;
        for (int i = 0; i < before.size(); i++) if (before.get(i) != after.get(i)) return false;
        return true;
    }

    private static void restoreMovedCorners() {
        synchronized (MOVED) {
            for (Moved moved : MOVED) {
                Object style = moved.style.get();
                if (style == null) continue;
                try {
                    moved.corners.setInt(style, moved.corners.getInt(style) | BOTTOM);
                } catch (IllegalAccessException ignored) {
                    // The field was made accessible when it was found.
                }
            }
            MOVED.clear();
        }
    }

    /** Every instance field of [type] and its superclasses, made accessible. */
    private static List<Field> fields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    out.add(field);
                } catch (RuntimeException ignored) {
                    // A field the runtime won't open is one we don't need.
                }
            }
        }
        return out;
    }

    /**
     * How this build's menu row is made: its constructor taking key, icon, label, click, style and
     * a second callback, the style type with its one int of corner flags, and the row's fields.
     */
    private static final class Shape {
        final Constructor<?> constructor;
        final Class<?> click;
        final Field style;
        final Field icon;
        final Field corners;
        final Constructor<?> styleConstructor;
        final List<Field> styleFields;

        private Shape(Constructor<?> constructor, Field style, Field icon, Field corners,
                Constructor<?> styleConstructor, List<Field> styleFields) {
            this.constructor = constructor;
            this.click = constructor.getParameterTypes()[3];
            this.style = style;
            this.icon = icon;
            this.corners = corners;
            this.styleConstructor = styleConstructor;
            this.styleFields = styleFields;
        }

        static Shape of(Class<?> row) {
            Constructor<?> constructor = null;
            for (Constructor<?> candidate : row.getDeclaredConstructors()) {
                Class<?>[] types = candidate.getParameterTypes();
                if (types.length == 6 && types[0] == String.class && types[1] == Integer.class
                        && types[2] == String.class && types[3].isInterface() && types[5].isInterface()
                        && !types[4].isPrimitive() && !types[4].isInterface()) {
                    if (constructor != null) return missing(row, "one row constructor");
                    constructor = candidate;
                }
            }
            if (constructor == null) return missing(row, "a row constructor");
            constructor.setAccessible(true);
            Class<?> styleType = constructor.getParameterTypes()[4];
            Field style = single(fields(row), styleType);
            Field icon = single(fields(row), Integer.class);
            List<Field> styleFields = fields(styleType);
            Field corners = single(styleFields, int.class);
            Constructor<?>[] styleConstructors = styleType.getDeclaredConstructors();
            if (style == null || icon == null || corners == null || styleConstructors.length != 1) {
                return missing(row, "the row's style, icon and corner flags");
            }
            styleConstructors[0].setAccessible(true);
            return new Shape(constructor, style, icon, corners, styleConstructors[0], styleFields);
        }

        private static Shape missing(Class<?> row, String what) {
            HookStatus.missingMember(FAMILY, "constructor", row.getName(), what);
            return null;
        }

        private static Field single(List<Field> fields, Class<?> type) {
            Field found = null;
            for (Field field : fields) {
                if (field.getType() != type) continue;
                if (found != null) return null;
                found = field;
            }
            return found;
        }

        /**
         * A style like Report's with [corners]. The style's one constructor takes the corner flags
         * first, then Report's other values by type, and a trailing int of defaults left at zero.
         */
        Object newStyle(Object like, int corners) throws ReflectiveOperationException {
            Class<?>[] types = styleConstructor.getParameterTypes();
            Object[] arguments = new Object[types.length];
            boolean cornersGiven = false;
            for (int i = 0; i < types.length; i++) {
                if (types[i] == int.class) {
                    arguments[i] = cornersGiven ? 0 : corners;
                    cornersGiven = true;
                } else {
                    Field value = single(styleFields, types[i]);
                    arguments[i] = value != null ? value.get(like)
                            : types[i] == boolean.class ? Boolean.FALSE : null;
                }
            }
            Object style = styleConstructor.newInstance(arguments);
            this.corners.setInt(style, corners);
            return style;
        }
    }

    private static final class Moved {
        final WeakReference<Object> style;
        final Field corners;

        Moved(Object style, Field corners) {
            this.style = new WeakReference<>(style);
            this.corners = corners;
        }
    }

    // ---- the tap ----------------------------------------------------------------------

    private static final class Built {
        final WeakReference<Object> panel;
        final WeakReference<Object> comment;
        final Object user;
        final VideoAuthor author;
        final String account;
        WeakReference<Object> row = new WeakReference<>(null);
        volatile boolean retired;

        Built(Object panel, Object comment, Object user, VideoAuthor author, String account) {
            this.panel = new WeakReference<>(panel);
            this.comment = new WeakReference<>(comment);
            this.user = user;
            this.author = author;
            this.account = account;
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

    private static synchronized void remember(Built built) {
        find(null);
        BUILT.add(built);
    }

    private static synchronized Built find(Object row) {
        Built found = null;
        for (Iterator<Built> each = BUILT.iterator(); each.hasNext();) {
            Built built = each.next();
            Object held = built.row.get();
            if (held == null) each.remove();
            else if (held == row) found = built;
        }
        return found;
    }

    /** Whether [row] is an owned Block row. */
    static boolean owns(Object row) {
        return row != null && find(row) != null;
    }

    /** The owned row's tap. Acts only for the comment and account the row was built for. */
    private static void click(Built built) {
        try {
            Object panel = built.panel.get();
            if (built.retired || panel == null || !enabled()) return;
            // A panel reused for another comment is not this row's any more.
            Object comment = commentOf(panel);
            if (comment == null || comment != built.comment.get()
                    || !built.author.uid.equals(uidOf(comment))) return;
            if (!built.account.equals(uid(SignedInUser.id()))) return;
            built.retired = true;
            Activity activity = Utils.getVisibleActivity();
            WeakReference<Activity> from = new WeakReference<>(activity);
            dismiss(panel);
            request(built, false, result -> {
                if (!built.account.equals(SignedInUser.id())) return;
                if (result != BlockAuthorService.Result.CONFIRMED) {
                    notice(BlockAuthorMessages.blockFailure(Utils.getContext(), result, built.author.label()));
                    return;
                }
                removeComments(built.user);
                // An older banner's Undo already follows this block. Don't add another.
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
            Logger.printException(() -> "Could not block the commenter from the comment menu", failure);
            notice(BlockAuthorMessages.blockFailure(Utils.getContext(), BlockAuthorService.Result.UNCONFIRMED,
                    built.author.label()));
        }
    }

    private static void request(Built built, boolean undo, BlockAuthorService.Callback callback) {
        Pending operation = new Pending(built, undo, callback);
        synchronized (IN_FLIGHT) {
            Pending active = IN_FLIGHT.get(operation.key());
            if (active != null) {
                // The banner has already gone. Keep its Undo until the block it follows finishes,
                // rather than consuming the tap without sending it.
                if (undo && !active.undo && active.nextUndo == null) active.nextUndo = operation;
                return;
            }
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
            Logger.printException(() -> "Could not queue the comment menu block request", failure);
        }
    }

    /** Closes the menu's sheet, the way TikTok's own rows do before they act. */
    private static void dismiss(Object panel) {
        try {
            Class<?> dialog = Class.forName(DIALOG, false, panel.getClass().getClassLoader());
            for (Field field : fields(panel.getClass())) {
                if (!dialog.isAssignableFrom(field.getType())) continue;
                Object sheet = field.get(panel);
                if (sheet == null) continue;
                dialog.getMethod("dismissAllowingStateLoss").invoke(sheet);
                return;
            }
        } catch (ClassNotFoundException ignored) {
            // No fragment library, no sheet to close.
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not close the comment menu", failure);
        }
    }

    /**
     * Posts TikTok's user-blocked event with the commenter, as a block from their profile does.
     * The comment list's delete model answers it by taking that account's comments out.
     */
    static void removeComments(Object user) {
        try {
            if (user == null) return;
            Class<?> model = Class.forName(DELETE_VM, false, user.getClass().getClassLoader());
            Class<?> event = null;
            for (Method method : model.getDeclaredMethods()) {
                if (!method.getName().equals("onUserBlockedEvent") || method.getParameterTypes().length != 1) continue;
                if (event != null) {
                    HookStatus.missingMember(FAMILY, "method", DELETE_VM, "one onUserBlockedEvent");
                    return;
                }
                event = method.getParameterTypes()[0];
            }
            if (event == null) {
                HookStatus.missingMember(FAMILY, "method", DELETE_VM, "onUserBlockedEvent");
                return;
            }
            Field holder = null;
            for (Field field : fields(event)) {
                if (!field.getType().isInstance(user) || field.getType() == Object.class) continue;
                if (holder != null) return;
                holder = field;
            }
            if (holder == null) {
                HookStatus.missingMember(FAMILY, "field", event.getName(), "the blocked User");
                return;
            }
            Constructor<?> create = event.getDeclaredConstructor();
            create.setAccessible(true);
            Object posted = create.newInstance();
            holder.set(posted, user);
            event.getMethod("post").invoke(posted);
            HookStatus.bound(FAMILY, "comments removed");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "comments removed", failure);
            Logger.printException(() -> "Could not take the blocked commenter's comments out", failure);
        }
    }

    private static void notice(String message) {
        Feedback stand = feedbackForTests;
        if (stand != null) stand.notice(message);
        else Utils.showToastLong(message);
    }

    interface Transport {
        void request(VideoAuthor author, String account, boolean undo, BlockAuthorService.Callback callback);
    }
    interface Feedback { void undo(String message, Runnable undo); void notice(String message); }
    static volatile Transport transportForTests;
    static volatile Feedback feedbackForTests;
}
