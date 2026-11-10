/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import app.morphe.extension.shared.Logger;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * What the comment publish entry saw, and where it went.
 *
 * <p>A comment that will not send looks like nothing at all: the text stays in the box, no
 * toast, nothing in the log. TikTok's publish entry returns without a word from a dozen
 * places before the request is built. The patch calls in here at the entry, before every
 * return and before the hand-off to the request, so a report from a phone where comments
 * never post says whether the send was asked for, what it had in hand, and which exit it took.
 * The comment's text is never written, only its length.
 */
public final class CommentPublishDiagnostics {
    private static final String TAG = "[Morphe TikTok CommentPublish] ";

    /** Longest page description a line carries; a fragment's own text can run on. */
    private static final int PAGE_TEXT_LIMIT = 160;

    /**
     * The publish in flight and the top page it read, for the send fix. The entry runs on the
     * main thread from the send button to the check, so the last one recorded is the one checked.
     */
    private static WeakReference<Object> publishing = new WeakReference<>(null);
    private static WeakReference<Object> topPage = new WeakReference<>(null);

    /**
     * Lets a phone check produce the state the send fix is for, which a phone will not produce
     * on demand. 0 leaves the send alone. 1 treats TikTok's top page as having no screen, so the
     * check runs against the comment panel's. 2 skips the panel as well, which is what TikTok
     * does unpatched: the check stops the send and nothing is shown.
     */
    public static volatile int topScreenTestMode;

    private CommentPublishDiagnostics() {}

    /** Called first thing in the publish entry, with the view model and the publish parameters. */
    public static void onPublishRequested(Object viewModel, Object params) {
        publishing = new WeakReference<>(viewModel);
        topPage = new WeakReference<>(null);
        String state;
        try {
            state = describe(viewModel, params);
        } catch (Throwable failure) {
            state = "state could not be read: " + failure;
        }
        String line = state;
        Logger.printInfo(() -> TAG + "requested with " + line);
    }

    /**
     * Called before each return that leaves the entry without building a request. The
     * number is the instruction index of that return; on a build that splits the entry
     * in two, the second method's exits count from 1000.
     */
    public static void onPublishExit(int exit) {
        Logger.printInfo(() -> TAG + "returned before the request at exit " + exit);
    }

    /** Called just before the entry hands the comment to the request builder. */
    public static void onPublishHandedOff() {
        Logger.printInfo(() -> TAG + "handed to the request");
    }

    /** Called with TikTok's top page as the entry reads it, before it asks the page for its screen. */
    public static void onTopPage(Object page) {
        topPage = new WeakReference<>(page);
    }

    /**
     * The screen TikTok's send check answers on. TikTok takes it from the most recently opened
     * page that is still open, and that can be a panel or a tab that has already lost its
     * screen. The check then stops the send without a word and the comment stays in the box.
     * When that happens this answers with the comment panel's own screen, the one the second
     * half of the entry uses anyway. A screen TikTok did find is passed through untouched.
     */
    public static Object screenForSendCheck(Object topScreen) {
        int mode = topScreenTestMode;
        if (topScreen != null && mode == 0) return topScreen;
        Object page = topPage.get();
        Object panel = mode == 2 ? null : panelScreen(publishing.get());
        String pageText = page == null ? "none" : String.valueOf(page);
        String shown = pageText.length() > PAGE_TEXT_LIMIT ? pageText.substring(0, PAGE_TEXT_LIMIT) : pageText;
        String prefix = mode == 0 ? "" : "test mode " + mode + ": ";
        Logger.printInfo(() -> TAG + prefix + "TikTok's top page has no screen (" + shown + "); "
                + (panel == null
                        ? "the comment panel has none either, so TikTok's check will stop the send"
                        : "checking the send against the comment panel's screen, " + panel.getClass().getSimpleName()));
        return panel;
    }

    /**
     * The comment panel's own screen: the activity of the fragment the publish view model
     * holds. Found by the field's type, since every name on the view model is R8's.
     */
    static Object panelScreen(Object viewModel) {
        if (viewModel == null) return null;
        try {
            for (Class<?> type = viewModel.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || !isFragment(field.getType())) continue;
                    field.setAccessible(true);
                    Object fragment = field.get(viewModel);
                    if (fragment == null) continue;
                    Object screen = fragment.getClass().getMethod("getActivity").invoke(fragment);
                    if (screen != null) return screen;
                }
            }
        } catch (Throwable failure) {
            Logger.printException(() -> TAG + "could not read the comment panel's screen", failure);
        }
        return null;
    }

    /** Whether a field of this type holds an AndroidX fragment, whose class keeps its name in TikTok. */
    private static boolean isFragment(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (current.getName().equals("androidx.fragment.app.Fragment")) return true;
        }
        return false;
    }

    /**
     * The parameters' shape by type, since every field name is R8's: the first text field's
     * length, each list's size in declaration order, whether a gift or a reply target is set,
     * and whether the view model still holds the comment context and its video.
     */
    static String describe(Object viewModel, Object params) throws ReflectiveOperationException {
        StringBuilder state = new StringBuilder();
        int textLength = -1;
        StringBuilder lists = new StringBuilder();
        boolean gift = false;
        boolean reply = false;
        if (params != null) {
            for (Field field : params.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object value = field.get(params);
                Class<?> type = field.getType();
                if (textLength < 0 && CharSequence.class.isAssignableFrom(type)) {
                    textLength = value == null ? 0 : ((CharSequence) value).length();
                } else if (List.class.isAssignableFrom(type)) {
                    if (lists.length() > 0) lists.append(',');
                    lists.append(value == null ? "null" : String.valueOf(((List<?>) value).size()));
                } else if (type.getSimpleName().endsWith("GiftStruct")) {
                    gift |= value != null;
                } else if (type.getSimpleName().equals("Comment")) {
                    reply |= value != null;
                }
            }
        }
        state.append("text=").append(Math.max(textLength, 0)).append(" chars")
                .append(", lists=[").append(lists).append(']')
                .append(", gift=").append(gift ? "yes" : "no")
                .append(", reply=").append(reply ? "yes" : "no");

        Object context = contextOf(viewModel);
        state.append(", context=").append(context == null ? "no" : "yes");
        Object video = null;
        if (context != null) {
            Method common = context.getClass().getMethod("getCommonModel");
            Object commonModel = common.invoke(context);
            if (commonModel != null) {
                Field aweme = commonModel.getClass().getField("aweme");
                video = aweme.get(commonModel);
            }
        }
        state.append(", video=").append(video == null ? "no" : "yes");
        return state.toString();
    }

    /** The view model's own no-argument getter for the comment context, found by its return type. */
    private static Object contextOf(Object viewModel) throws ReflectiveOperationException {
        if (viewModel == null) return null;
        for (Method method : viewModel.getClass().getDeclaredMethods()) {
            if (method.getParameterTypes().length != 0) continue;
            if (!method.getReturnType().getSimpleName().endsWith("CommentContextSource")) continue;
            method.setAccessible(true);
            return method.invoke(viewModel);
        }
        return null;
    }
}
