/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.accountmanagement;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.tiktok.accountmanagement.AccountListSession.BlockMetadata;
import app.morphe.extension.tiktok.accountmanagement.AccountListSession.BlockSource;
import app.morphe.extension.tiktok.accountmanagement.AccountListSession.Failure;
import app.morphe.extension.tiktok.accountmanagement.AccountListSession.Page;
import app.morphe.extension.tiktok.accountmanagement.AccountListSession.Reason;
import app.morphe.extension.tiktok.accountmanagement.AccountListSession.Row;

/** Typed copies of the two response schemas. No model, cache or user is changed. */
final class NativeAccountRows {
    static final String BASE = "com.ss.android.ugc.aweme.base.api.BaseResponse";
    static final String SAVED = "com.ss.android.ugc.aweme.favorites.business.collection.AllFavoriteContentResponse";
    static final String BLOCKED = "com.ss.android.ugc.aweme.setting.model.BlackList";
    static final String AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme";
    static final String USER = "com.ss.android.ugc.aweme.profile.model.User";
    static final String SOURCE = "com.ss.android.ugc.aweme.setting.model.UserBlockSource";
    final Class<?> base, saved, blocked, aweme, user, source;

    NativeAccountRows(ClassLoader loader) throws ClassNotFoundException {
        this(type(loader, BASE), type(loader, SAVED), type(loader, BLOCKED),
                type(loader, AWEME), type(loader, USER), type(loader, SOURCE));
    }

    NativeAccountRows(Class<?> base, Class<?> saved, Class<?> blocked, Class<?> aweme, Class<?> user, Class<?> source) {
        this.base = base; this.saved = saved; this.blocked = blocked;
        this.aweme = aweme; this.user = user; this.source = source;
    }

    Page saved(Object response) throws ReflectiveOperationException {
        confirmed(response, saved);
        List<?> values = list(field(saved, "items", List.class).get(response), false);
        Object cursor = field(saved, "maxCursor", Long.class).get(response);
        Object more = field(saved, "hasMore", Integer.class).get(response);
        if (!(cursor instanceof Long) || (Long) cursor < 0 || !(more instanceof Integer)
                || (Integer) more < 0 || (Integer) more > 1) throw new IllegalArgumentException("Invalid saved-list paging");
        List<Row> rows = new ArrayList<>();
        for (Object value : values) {
            require(value, aweme);
            String id = identifier(method(aweme, "getAid", String.class).invoke(value));
            String title = text(method(aweme, "getDesc", String.class).invoke(value), 8192);
            rows.add(new Row(id, title, ""));
        }
        return new Page(rows, (Long) cursor, (Integer) more == 1, null);
    }

    Page blocked(Object response) throws ReflectiveOperationException {
        confirmed(response, blocked);
        List<?> values = list(field(blocked, "blockList", List.class).get(response), false);
        int cursor = field(blocked, "index", int.class).getInt(response);
        int total = field(blocked, "total", int.class).getInt(response);
        int filtered = field(blocked, "hotsoonFilteredCount", int.class).getInt(response);
        int filteredMore = field(blocked, "hotsoonHasMore", int.class).getInt(response);
        boolean more = field(blocked, "hasMore", boolean.class).getBoolean(response);
        if (cursor < 0 || total < 0 || filtered < 0 || filteredMore < 0 || filteredMore > 1)
            throw new IllegalArgumentException("Invalid blocked-list paging");
        String filteredText = text(field(blocked, "hotsoonText", String.class).get(response), 8192);
        List<Row> rows = new ArrayList<>();
        for (Object value : values) {
            require(value, user);
            String id = identifier(method(user, "getUid", String.class).invoke(value));
            String title = text(method(user, "getNickname", String.class).invoke(value), 512);
            String handle = text(method(user, "getUniqueId", String.class).invoke(value), 512);
            rows.add(new Row(id, title, handle.isEmpty() || handle.startsWith("@") ? handle : "@" + handle));
        }
        List<BlockSource> sources = new ArrayList<>();
        for (Object value : list(field(blocked, "userBlockSources", List.class).get(response), true)) {
            require(value, source);
            sources.add(new BlockSource(identifier(field(source, "userId", String.class).get(value)),
                    field(source, "blockingByType", int.class).getInt(value)));
        }
        return new Page(rows, cursor, more, new BlockMetadata(total, filtered, filteredMore, filteredText, sources));
    }

    private void confirmed(Object response, Class<?> expected) throws ReflectiveOperationException {
        require(response, expected);
        if (!base.isAssignableFrom(expected)) throw new IllegalArgumentException("Changed list response base");
        int status = field(base, "status_code", int.class).getInt(response);
        int error = field(base, "error_code", int.class).getInt(response);
        if (status != 0 || error != 0) throw new Refused(status != 0 ? status : error);
    }

    private static List<?> list(Object value, boolean nullable) {
        if (value == null && nullable) return Collections.emptyList();
        if (!(value instanceof List) || ((List<?>) value).size() > AccountListSession.MAX_PAGE_ROWS)
            throw new IllegalArgumentException("Unsupported list payload");
        return (List<?>) value;
    }

    private static void require(Object value, Class<?> owner) {
        if (value == null || value.getClass() != owner) throw new IllegalArgumentException("Changed list row owner");
    }

    private static String identifier(Object value) {
        if (!(value instanceof String) || ((String) value).isEmpty() || "0".equals(value)
                || ((String) value).length() > 128) throw new IllegalArgumentException("Invalid list identifier");
        return (String) value;
    }

    private static String text(Object value, int maximum) {
        if (value == null) return "";
        if (!(value instanceof String) || ((String) value).length() > maximum)
            throw new IllegalArgumentException("Unsupported list text");
        return (String) value;
    }

    static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    static Field field(Class<?> owner, String name, Class<?> expected) throws NoSuchFieldException {
        return field(owner, name, expected, false);
    }

    static Field field(Class<?> owner, String name, Class<?> expected, boolean isStatic) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != expected || Modifier.isStatic(field.getModifiers()) != isStatic)
            throw new NoSuchFieldException("Changed account-list field schema");
        field.setAccessible(true);
        return field;
    }

    static Method method(Class<?> owner, String name, Class<?> result, Class<?>... parameters) throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameters);
        if (method.getReturnType() != result) throw new NoSuchMethodException("Changed account-list method schema");
        return method;
    }

    static final class Refused extends IllegalArgumentException {
        final Failure failure;
        Refused(int code) { super("Native account-list refusal"); failure = new Failure(Reason.REFUSED, code); }
    }
}
