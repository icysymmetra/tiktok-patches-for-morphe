package app.morphe.extension.tiktok.sharesheet;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Filters the "Share via" and action lists TikTok builds on its {@code X.0oVp} panel builder, and
 * toggles "Send to" via the same builder's {@code LJJIIJZLJL} ("supports IM") field. Neither the
 * builder nor its list-item types are on this module's compile classpath, so everything here is
 * reflective.
 * <p>
 * That builder is shared by every "share this thing" surface, and each exposes a different set of
 * actions from the same field, so the share package it carries picks which allow-list applies:
 * video, profile, or live.
 */
public final class ShareSheetFilter {
    private static final String USER_SHARE_PACKAGE_CLASS =
            "com.ss.android.ugc.aweme.share.improve.pkg.UserSharePackage";
    private static final String LIVE_SHARE_PACKAGE_CLASS =
            "com.ss.android.ugc.aweme.share.improve.pkg.LiveSharePackage";

    private ShareSheetFilter() {
    }

    public static void filterChannels(Object builder) {
        filterList(
                builder,
                "LIZ",
                "Share via",
                Settings.SHARE_SHEET_CHANNELS,
                Settings.SHARE_SHEET_CHANNELS_ENABLED,
                Settings.SHARE_SHEET_CHANNELS_OBSERVED,
                ShareChannelOptions::parseEnabledKeys,
                ShareChannelOptions::serializeEnabledKeys
        );
    }

    public static void filterActions(Object builder) {
        String surface = sharePackageClass(builder);
        Logger.printDebug(() -> "Share sheet: panel surface=" + surface);

        if (USER_SHARE_PACKAGE_CLASS.equals(surface)) {
            filterList(
                    builder,
                    "LJFF",
                    "User Actions",
                    Settings.SHARE_SHEET_USER_ACTIONS,
                    Settings.SHARE_SHEET_USER_ACTIONS_ENABLED,
                    Settings.SHARE_SHEET_USER_ACTIONS_OBSERVED,
                    ShareSheetOptions.USER_ACTIONS::parseKeys,
                    ShareSheetOptions.USER_ACTIONS::serializeKeys
            );
            return;
        }

        if (LIVE_SHARE_PACKAGE_CLASS.equals(surface)) {
            filterList(
                    builder,
                    "LJFF",
                    "Live Actions",
                    Settings.SHARE_SHEET_LIVE_ACTIONS,
                    Settings.SHARE_SHEET_LIVE_ACTIONS_ENABLED,
                    Settings.SHARE_SHEET_LIVE_ACTIONS_OBSERVED,
                    ShareSheetOptions.LIVE_ACTIONS::parseKeys,
                    ShareSheetOptions.LIVE_ACTIONS::serializeKeys
            );
            return;
        }

        filterList(
                builder,
                "LJFF",
                "Video Actions",
                Settings.SHARE_SHEET_ACTIONS,
                Settings.SHARE_SHEET_ACTIONS_ENABLED,
                Settings.SHARE_SHEET_ACTIONS_OBSERVED,
                ShareSheetOptions.VIDEO_ACTIONS::parseKeys,
                ShareSheetOptions.VIDEO_ACTIONS::serializeKeys
        );
    }

    public static void applySendToVisibility(Object builder) {
        try {
            if (builder == null || Settings.SHARE_SHEET_SEND_TO.get()) {
                return;
            }

            Field supportImField = findField(builder.getClass(), "LJJIIJZLJL");
            if (supportImField.getType() != boolean.class) {
                Logger.printDebug(() -> "Share sheet Send to: LJJIIJZLJL was not a boolean, actual type="
                        + supportImField.getType());
                return;
            }

            supportImField.setBoolean(builder, false);
        } catch (Throwable t) {
            Logger.printException(() -> "Share sheet: send-to visibility override failed", t);
        }
    }

    /**
     * The panel builder is shared by every "share this thing" surface; the share package it
     * carries is what identifies the surface.
     */
    private static String sharePackageClass(Object builder) {
        try {
            Object sharePackage = findField(builder.getClass(), "LJJIIJ").get(builder);
            return sharePackage == null ? null : sharePackage.getClass().getName();
        } catch (Throwable t) {
            Logger.printException(() -> "Share sheet: could not identify panel surface", t);
            return null;
        }
    }

    private static Field findField(Class<?> startClass, String fieldName) throws NoSuchFieldException {
        for (Class<?> clazz = startClass; clazz != null; clazz = clazz.getSuperclass()) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    @SuppressWarnings("unchecked")
    private static void filterList(
            Object builder,
            String fieldName,
            String label,
            BooleanSetting masterToggle,
            StringSetting enabledSetting,
            StringSetting observedSetting,
            Function<String, Set<String>> parseKeys,
            Function<Set<String>, String> serializeKeys
    ) {
        try {
            if (builder == null) {
                return;
            }

            Field field = builder.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object raw = field.get(builder);
            if (!(raw instanceof List)) {
                Logger.printDebug(() -> "Share sheet " + label + ": field " + fieldName
                        + " was not a List, actual type=" + (raw == null ? "null" : raw.getClass().getName()));
                return;
            }
            List<Object> list = (List<Object>) raw;

            Set<String> previousObserved = parseKeys.apply(observedSetting.get());
            LinkedHashSet<String> observedNow = new LinkedHashSet<>(previousObserved);
            LinkedHashSet<String> newlyObserved = new LinkedHashSet<>();
            List<String> thisPassKeys = new ArrayList<>();
            for (Object item : list) {
                String key = itemKey(item);
                thisPassKeys.add(key == null ? "<unknown:" + item.getClass().getName() + ">" : key);
                if (key != null && observedNow.add(key)) {
                    newlyObserved.add(key);
                }
            }

            Logger.printDebug(() -> "Share sheet " + label + ": keys seen this pass=" + thisPassKeys
                    + (newlyObserved.isEmpty() ? "" : ", newly observed=" + newlyObserved));

            String observedSignature = serializeKeys.apply(observedNow);
            if (!observedSignature.equals(observedSetting.get())) {
                observedSetting.save(observedSignature);
            }

            if (!newlyObserved.isEmpty()) {
                Set<String> enabledKeys = parseKeys.apply(enabledSetting.get());
                if (enabledKeys.addAll(newlyObserved)) {
                    enabledSetting.save(serializeKeys.apply(enabledKeys));
                }
            }

            if (!masterToggle.get()) {
                list.clear();
                return;
            }

            Set<String> enabledKeys = parseKeys.apply(enabledSetting.get());
            Iterator<Object> iterator = list.iterator();
            while (iterator.hasNext()) {
                String key = itemKey(iterator.next());
                if (key == null || !enabledKeys.contains(key)) {
                    iterator.remove();
                }
            }
        } catch (Throwable t) {
            Logger.printException(() -> "Share sheet: filtering failed for field " + fieldName, t);
        }
    }

    private static String itemKey(Object item) {
        if (item == null) {
            return null;
        }
        try {
            Method method = item.getClass().getMethod("key");
            Object result = method.invoke(item);
            if (result instanceof String) {
                String key = ((String) result).trim();
                return key.isEmpty() ? null : key;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
