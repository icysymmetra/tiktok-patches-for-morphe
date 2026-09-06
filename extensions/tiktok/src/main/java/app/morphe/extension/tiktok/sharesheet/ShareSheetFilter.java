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
 * Filters the "Share via" and "Video Actions" lists TikTok builds on its
 * {@code C1476000oVp} panel builder, and toggles "Send to" via the same builder's
 * {@code LJJIIJZLJL} ("supports IM") field. Neither the builder nor its list-item types
 * are on this module's compile classpath, so everything here is reflective.
 */
public final class ShareSheetFilter {

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
                ShareChannelOptions::parseObservedKeys,
                ShareChannelOptions::serializeEnabledKeys
        );
    }

    public static void filterActions(Object builder) {
        filterList(
                builder,
                "LJFF",
                "Video Actions",
                Settings.SHARE_SHEET_ACTIONS,
                Settings.SHARE_SHEET_ACTIONS_ENABLED,
                Settings.SHARE_SHEET_ACTIONS_OBSERVED,
                VideoActionOptions::parseEnabledKeys,
                VideoActionOptions::parseObservedKeys,
                VideoActionOptions::serializeEnabledKeys
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
            Function<String, Set<String>> parseEnabledKeys,
            Function<String, Set<String>> parseObservedKeys,
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

            Set<String> previousObserved = parseObservedKeys.apply(observedSetting.get());
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
                Set<String> enabledKeys = parseEnabledKeys.apply(enabledSetting.get());
                if (enabledKeys.addAll(newlyObserved)) {
                    enabledSetting.save(serializeKeys.apply(enabledKeys));
                }
            }

            if (!masterToggle.get()) {
                list.clear();
                return;
            }

            Set<String> enabledKeys = parseEnabledKeys.apply(enabledSetting.get());
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
