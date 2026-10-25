/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.notinterested;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import app.morphe.extension.tiktok.settings.L10n;

public final class NotInterested {
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static volatile String lastSubmittedId;
    private NotInterested() {}

    /** Replaced at patch time with TikTok's own service call. */
    public static Object createCall(String id, String requestId, Map<String, String> fields) {
        return null;
    }

    public static void submit() {
        Object video = CurrentVideoAuthor.getAweme();
        String id = Reflect.string(video, "getAid", "aid");
        if (id == null) {
            Utils.showToastShort(L10n.t("No video selected. Open a video first."));
            return;
        }
        if (!IN_FLIGHT.compareAndSet(false, true)) return;
        // The previous worker publishes success before releasing IN_FLIGHT. Check
        // after acquiring it so a tap racing that completion cannot send twice.
        if (id.equals(lastSubmittedId)) {
            IN_FLIGHT.set(false);
            Utils.showToastShort(L10n.t("Already marked as not interested"));
            return;
        }
        Utils.showToastShort(L10n.t("Sending feedback"));
        boolean accepted = Utils.runOnBackgroundThread(() -> {
            boolean success = false;
            try {
                String requestId = Reflect.string(video, "getRequestId", "requestId");
                Map<String, String> fields = new HashMap<>();
                String authorId = Reflect.string(video, "getAuthorUid", "authorUid");
                if (authorId != null) fields.put("author_id", authorId);
                Object music = Reflect.invoke(video, "getMusic");
                String musicId = Reflect.string(music, "getMid", "mid");
                if (musicId != null) fields.put("music_id", musicId);
                Object call = createCall(id, requestId == null ? "" : requestId, fields);
                if (call == null) throw new IllegalStateException("Dislike service is unavailable");
                Method execute = call.getClass().getMethod("execute");
                execute.setAccessible(true);
                success = succeeded(execute.invoke(call));
                if (success) lastSubmittedId = id;
            } catch (Throwable e) {
                Logger.printException(() -> "Not interested request failed", e);
            } finally {
                IN_FLIGHT.set(false);
            }
            final boolean sent = success;
            Utils.runOnMainThread(() -> Utils.showToastShort(L10n.t(sent
                    ? "Marked as not interested"
                    : "TikTok didn't accept the feedback. Try again later.")));
        });

        // The worker is what releases IN_FLIGHT, and a refused task has no worker. Without this
        // the flag stayed set for the life of the process: the reader saw one "Sending feedback"
        // that never resolved, and every later tap returned at the compareAndSet above with
        // nothing on screen at all.
        if (!accepted) {
            IN_FLIGHT.set(false);
            Utils.showToastShort(L10n.t("Couldn't send the feedback. Try again in a moment."));
        }
    }

    static boolean succeeded(Object response) throws IllegalAccessException {
        if (response == null) return false;
        Object body = Reflect.invoke(response, "body");
        if (body == null) {
            for (Field field : response.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object value = field.get(response);
                if (value != null && Reflect.readField(value, "status_code") instanceof Number) {
                    body = value;
                    break;
                }
            }
        }
        Object status = Reflect.property(body, "getStatusCode", "status_code");
        return status instanceof Number && ((Number) status).intValue() == 0;
    }
}
