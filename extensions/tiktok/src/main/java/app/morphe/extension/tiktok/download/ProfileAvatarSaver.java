/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Saves a profile picture from a long press on the avatar.
 *
 * The full size URL comes from the current gesture owner's profile. Older headers use the
 * recorded UserResponse; modern profile responses are converted directly into the owner's User.
 */
@SuppressWarnings("unused")
public final class ProfileAvatarSaver {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    /** One picture at a time, whichever profile, so the queue knows it by one name. */
    private static final String JOB_KEY = "profile picture";

    /**
     * Largest available source first. The inspected native profile supplies Larger at 1080 pixels,
     * Medium at 720, then 300, 168 and the 100-pixel thumbnail.
     */
    private static final String[][] SIZES = {
            {"getAvatarLarger", "avatarLarger"},
            {"getAvatarMedium", "avatarMedium"},
            {"getAvatar300", "avatar300"},
            {"getAvatar168", "avatar168"},
            {"getAvatarThumb", "avatarThumb"},
    };

    private static volatile Object profileUser;

    private ProfileAvatarSaver() {
    }

    /** Called by the native avatar gesture with the component that owns the current profile. */
    public static boolean onAvatarLongPress(Object owner, View avatar) {
        if (!enabled()) return false;
        save(avatar, ownerProfile(owner));
        return true;
    }

    static final String USER_CLASS = "com.ss.android.ugc.aweme.profile.model.User";
    private static final Object NO_GETTER = new Object();
    private static final Map<Class<?>, Object> USER_GETTERS = new ConcurrentHashMap<>();

    /**
     * The profile the avatar's component shows: the viewer's own account on their own profile,
     * the page's account anywhere else. R8 renames that getter on every build ({@code bq} on
     * 46.2.3, {@code ds} on 46.9.3, {@code Qr} on 47.1.4), and asking for one of those names by
     * hand found nothing on 47.1.4, so every hold said to open the profile again (#160). It is
     * found by what it is instead: the component's one no-argument method returning a User.
     */
    static Object ownerProfile(Object owner) {
        if (owner == null) return null;
        Method getter = userGetter(owner.getClass());
        if (getter == null) {
            Logger.printInfo(() -> "No profile getter on " + owner.getClass().getName());
            return null;
        }
        try {
            return getter.invoke(owner);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            Logger.printException(() -> "Could not read the profile behind the avatar", exception);
            return null;
        }
    }

    /**
     * The nearest class that declares a no-argument User getter, and only when it declares
     * exactly one: two would leave it to chance whose picture is saved, so that answers none.
     */
    static Method userGetter(Class<?> type) {
        Object known = USER_GETTERS.get(type);
        if (known == null) {
            known = NO_GETTER;
            for (Class<?> current = type; current != null && current != Object.class;
                 current = current.getSuperclass()) {
                Method found = null;
                int count = 0;
                for (Method method : current.getDeclaredMethods()) {
                    if (method.getParameterTypes().length != 0 || Modifier.isStatic(method.getModifiers())
                            || method.isSynthetic() || !USER_CLASS.equals(method.getReturnType().getName())) {
                        continue;
                    }
                    found = method;
                    count++;
                }
                if (count == 0) continue;
                if (count == 1) {
                    try {
                        found.setAccessible(true);
                        known = found;
                    } catch (RuntimeException ignored) {
                        // An unusable getter is the same as none.
                    }
                }
                break;
            }
            USER_GETTERS.put(type, known);
        }
        return known instanceof Method ? (Method) known : null;
    }

    /**
     * Called with the profile fetch response from inside its own getUser, so the field is read
     * directly: going through the getter would call this again, and again, until the stack ran
     * out. The overflow is swallowed by the reflection helper, so it costs time rather than
     * crashing, which is exactly why it would not have shown up.
     */
    public static void recordProfileResponse(Object response) {
        Object user = Reflect.readField(response, "user");
        if (user != null) profileUser = user;
    }

    /**
     * Called with the profile header's avatar view as it is bound. A view holds one long click
     * listener, so this only takes it while the feature is on, and hands it back on the next
     * bind after the switch goes off: returning false from a listener that is still attached
     * does not give TikTok its gesture back, only removing ours does. A view we never took is
     * never cleared, because that would throw away the app's own listener.
     */
    /** The views whose long press is ours, so only those are handed back. */
    private static final java.util.Map<View, Boolean> TAKEN =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static void attachAvatar(View view) {
        if (view == null) return;
        try {
            if (!enabled()) {
                if (TAKEN.remove(view) != null) view.setOnLongClickListener(null);
                return;
            }
            TAKEN.put(view, Boolean.TRUE);
            view.setOnLongClickListener(anchor -> {
                if (!enabled()) return false;
                save(anchor, profileUser);
                return true;
            });
        } catch (RuntimeException exception) {
            Logger.printException(() -> "Could not attach the profile picture save", exception);
        }
    }

    /**
     * Checks the supplied profile against the handle on screen. Legacy headers can reappear
     * from cache after another profile was recorded, so their saved source needs this check.
     *
     * @return false only when a handle is on screen and it is somebody else's.
     */
    static boolean matchesProfileOnScreen(View avatar, Object user) {
        String handle = Reflect.string(user, "getUniqueId", "uniqueId");
        if (handle == null || handle.trim().isEmpty()) return true;
        View root = avatar;
        for (int step = 0; step < 8 && root.getParent() instanceof View; step++) {
            root = (View) root.getParent();
        }
        Boolean shown = findHandle(root, "@" + handle.trim());
        return shown == null || shown;
    }

    /** null when no handle is on screen at all, true when this one is, false when another is. */
    private static Boolean findHandle(View view, String wanted) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            String value = text == null ? "" : text.toString().trim();
            if (value.startsWith("@") && !value.contains(" ")) return wanted.equals(value);
            return null;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        Boolean answer = null;
        for (int index = 0; index < group.getChildCount(); index++) {
            Boolean found = findHandle(group.getChildAt(index), wanted);
            if (Boolean.TRUE.equals(found)) return true;
            if (found != null) answer = false;
        }
        return answer;
    }

    /** What the last legacy profile fetch carried. */
    static Object recordedProfileUser() {
        return profileUser;
    }

    static boolean enabled() {
        return SettingsStatus.advancedDownloadsEnabled && Settings.SAVE_PROFILE_PICTURE.get();
    }

    /** The addresses for the largest avatar the profile carries, best first. */
    static List<String> avatarUrls(Object user) {
        for (String[] size : SIZES) {
            Object address = Reflect.property(user, size[0], size[1]);
            List<String> found = VideoDownloads.urls(address);
            if (!found.isEmpty()) return found;
        }
        return Collections.emptyList();
    }

    /** The file the picture is saved as, named after the account it belongs to. */
    static String avatarName(Object user) {
        String handle = Reflect.string(user, "getUniqueId", "uniqueId");
        if (handle == null || handle.trim().isEmpty()) {
            handle = Reflect.string(user, "getNickname", "nickname");
        }
        if (handle == null || handle.trim().isEmpty()) handle = "profile";
        return DownloadFilenameFormatter.formatProfilePictureName(handle);
    }

    static void save(View avatar, Object user) {
        if (avatar == null) return;
        Context context = avatar.getContext();
        if (context == null) return;
        if (user == null || !matchesProfileOnScreen(avatar, user)) {
            Utils.showToastShort(L10n.t("Open the profile again and try once more"));
            return;
        }
        List<String> urls = avatarUrls(user);
        if (urls.isEmpty()) {
            Utils.showToastShort(L10n.t("This profile picture isn't available to save"));
            return;
        }
        final List<String> urlSnapshot = List.copyOf(urls);
        if (android.os.Build.VERSION.SDK_INT < 29
                && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Utils.showToastLong(L10n.t("Allow storage for TikTok in Android settings to save profile pictures"));
            return;
        }

        Context app = context.getApplicationContext();
        String name = avatarName(user);
        String path = DownloadsPatch.getPhotoDownloadPath();
        if (!RUNNING.compareAndSet(false, true)) {
            Utils.showToastShort(MediaJobScheduler.busyMessage(JOB_KEY));
            return;
        }
        MediaJobScheduler.Job job = MediaJobScheduler.submit("profile picture", JOB_KEY, () -> {
            File temp = null;
            try {
                MediaBudget.checkDiskSpace(app.getCacheDir(), -1L);
                temp = MediaCache.createTempFile(app, "profile-picture-", ".tmp");
                String extension = RemoteMedia.fetch(urlSnapshot, temp, RemoteMedia.Kind.IMAGE);
                String mime = "jpg".equals(extension) ? "image/jpeg" : "image/" + extension;
                String saved = name.substring(0, name.lastIndexOf('.') + 1) + extension;
                MediaFileWriter.Saved landed = MediaFileWriter.publishForResult(app, temp, saved, mime, path, false);
                SaveNotice.saved(L10n.f("Profile picture saved to %1$s", path), landed);
            } catch (IOException | RuntimeException exception) {
                Logger.printException(() -> "Profile picture download failed", exception);
                Utils.showToastLong(L10n.t("The profile picture couldn't be saved. Try again."));
            } finally {
                if (temp != null && !MediaCache.delete(temp)) {
                    Logger.printInfo(() -> "Could not remove profile picture temporary file");
                }
            }
        }, () -> RUNNING.set(false));
        String saying = L10n.t("Saving the profile picture");
        MediaJobScheduler.acknowledge(job, saying, saying);
    }
}
