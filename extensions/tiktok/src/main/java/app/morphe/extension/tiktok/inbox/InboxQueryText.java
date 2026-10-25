/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.inbox;

import app.morphe.extension.tiktok.blockauthor.Reflect;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The reviewed notification text schema, without actions, URLs, identifiers or service calls. */
final class InboxQueryText {
    private static final int MAX_TEXT = 16_384, MAX_PARTS = 128;
    private static final Pattern LINK = Pattern.compile("\\[\\[text_link_([0-9])]]");
    static final LoadedInboxQuery.Matcher MATCHER = new LoadedInboxQuery.Matcher() {
        @Override public boolean notification(Object model) {
            return model != null && model.getClass().getName().equals("X.1EH0")
                    && template(Reflect.readField(model, "LL")) != null;
        }
        @Override public boolean matches(Object model, String query) {
            Object notice = Reflect.readField(model, "LL");
            if (notice == null) throw new IllegalStateException("Missing native notification model");
            return textOfNotice(notice).contains(query);
        }
    };

    private InboxQueryText() {}

    static String textOfNotice(Object notice) {
        Object template = template(notice);
        if (template == null) return "";
        StringBuilder text = new StringBuilder();
        Object content = Reflect.readField(template, "content");
        if (!(content instanceof String) || ((String) content).isEmpty()) content = Reflect.readField(template, "event");
        Object links = Reflect.readField(template, "contentTextLinkConfigs");
        String body = content instanceof String ? (String) content : "";
        if (body.length() > MAX_TEXT) throw new IllegalStateException("Native notification text exceeds the query bound");
        Matcher placeholders = LINK.matcher(body);
        StringBuffer expanded = new StringBuffer();
        while (placeholders.find()) {
            int index = Integer.parseInt(placeholders.group(1)) - 1;
            Object link = links instanceof List && index >= 0 && index < ((List<?>) links).size()
                    && index < MAX_PARTS ? ((List<?>) links).get(index) : null;
            Object replacement = Reflect.readField(link, "text");
            if (replacement instanceof String && ((String) replacement).length() > MAX_TEXT - expanded.length()) {
                throw new IllegalStateException("Native notification link text exceeds the query bound");
            }
            placeholders.appendReplacement(expanded, Matcher.quoteReplacement(replacement instanceof String ? (String) replacement : ""));
        }
        placeholders.appendTail(expanded);
        add(text, expanded.toString());
        add(text, Reflect.readField(template, "quoteContent"));
        Object title = Reflect.readField(template, "titleTemplate");
        add(text, Reflect.readField(title, "title"));
        Object users = Reflect.readField(title, "fromUsers");
        if (users instanceof List) {
            bounded((List<?>) users);
            int count = 0;
            for (Object user : (List<?>) users) {
                if (count++ == MAX_PARTS) break;
                add(text, Reflect.invoke(user, "getNickname"));
                add(text, Reflect.invoke(user, "getUniqueId"));
            }
        }
        Object pairs = Reflect.readField(template, "textPair");
        if (pairs instanceof List) {
            bounded((List<?>) pairs);
            int count = 0;
            for (Object pair : (List<?>) pairs) {
                if (count++ == MAX_PARTS) break;
                struct(text, Reflect.readField(pair, "title"));
                struct(text, Reflect.readField(pair, "content"));
            }
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static void struct(StringBuilder text, Object value) {
        add(text, Reflect.readField(Reflect.readField(value, "content"), "text"));
        Object configs = Reflect.readField(value, "textConfigs");
        if (configs instanceof List) {
            bounded((List<?>) configs);
            int count = 0;
            for (Object config : (List<?>) configs) {
                if (count++ == MAX_PARTS) break;
                add(text, Reflect.readField(config, "text"));
            }
        }
    }
    private static void add(StringBuilder result, Object value) {
        if (!(value instanceof String)) return;
        String text = (String) value;
        if (text.length() + 1 > MAX_TEXT - result.length()) throw new IllegalStateException("Native notification text exceeds the query bound");
        result.append('\n').append(text);
    }
    private static Object template(Object notice) { return Reflect.readField(Reflect.readField(notice, "templateNotice"), "uiTemplate"); }
    private static void bounded(List<?> parts) {
        if (parts.size() > MAX_PARTS) throw new IllegalStateException("Native notification text parts exceed the query bound");
    }
}
