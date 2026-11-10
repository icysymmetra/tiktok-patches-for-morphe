/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.share;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.settings.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs before the share panel builds its rows. Never mutates the builder's lists.
 *
 * <p>These three run inside the share model's own constructor, so anything that escapes here
 * escapes into TikTok. A reader upstream had the Share button do nothing and then the app stop,
 * which is what that looks like from the outside: the model never finishes building, the sheet
 * never opens, and the next throw on the main thread takes the process with it. So each entry
 * point catches for itself and hands back exactly what TikTok passed in. A share sheet that is
 * missing none of the rows you hid is a much smaller failure than no share sheet at all, and
 * the export names which hook gave up.
 */
public final class ShareModelFilter {
    static final String FAMILY = "share sheet";

    /**
     * The sheet being built now. The patch sets it first thing in the share model's constructor,
     * before the three filters below run, and the layout pass that hides rows by name reads it
     * after. One sheet is open at a time, so the last one built is the one on screen.
     */
    private static volatile ShareSurface current = ShareSurface.VIDEO;

    private ShareModelFilter() {}

    /** Called with the {@code BaseSharePackage} the share model is being built from. */
    public static void surface(Object sharePackage) {
        try {
            current = ShareSurface.of(Reflect.string(sharePackage, "getItemType", "itemType"));
            // The export then says which sheets this process has actually opened.
            HookStatus.bound(FAMILY, "surface " + current.name().toLowerCase(Locale.ROOT));
        } catch (Throwable ex) {
            current = ShareSurface.VIDEO;
            HookStatus.threw(FAMILY, "surface", ex);
            Logger.printException(() -> "Could not tell which share sheet is opening", ex);
        }
    }

    static ShareSurface current() {
        return current;
    }

    /** The setting that holds [surface]'s own list. Videos and every other kind share the original. */
    public static StringSetting hiddenSetting(ShareSurface surface) {
        switch (surface) {
            case PROFILE:
                return Settings.SHARE_HIDDEN_ITEMS_PROFILE;
            case LIVE:
                return Settings.SHARE_HIDDEN_ITEMS_LIVE;
            default:
                return Settings.SHARE_HIDDEN_ITEMS;
        }
    }

    /** What [surface] hides: its own list once saved, the video list until then. */
    public static String hiddenItems(ShareSurface surface) {
        return resolveHidden(hiddenSetting(surface).get(), Settings.SHARE_HIDDEN_ITEMS.get());
    }

    /** The same, from the saved lists: the settings row shows these even while Hushfeed is paused. */
    public static String savedHiddenItems(ShareSurface surface) {
        return resolveHidden(hiddenSetting(surface).savedValue(), Settings.SHARE_HIDDEN_ITEMS.savedValue());
    }

    private static String resolveHidden(String own, String videoList) {
        return own == null || Settings.SHARE_HIDDEN_ITEMS_FOLLOW_VIDEO.equals(own) ? videoList : own;
    }

    /** What the sheet on screen hides. */
    public static String hiddenItems() {
        return hiddenItems(current);
    }

    public static List<?> channels(List<?> items) {
        try {
            return filter("channels", items, Settings.HIDE_SHARE_CHANNELS.get());
        } catch (Throwable ex) {
            HookStatus.threw(FAMILY, "channels", ex);
            Logger.printException(() -> "Could not filter the share channels row", ex);
            return items;
        }
    }

    public static List<?> actions(List<?> items) {
        try {
            ShareActionCatalog.observe(items, current);
        } catch (Throwable ex) {
            HookStatus.threw(FAMILY, "action catalog", ex);
            Logger.printException(() -> "Could not record the share actions", ex);
        }
        try {
            return filter("actions", items, Settings.HIDE_SHARE_ACTIONS.get());
        } catch (Throwable ex) {
            HookStatus.threw(FAMILY, "actions", ex);
            Logger.printException(() -> "Could not filter the share actions row", ex);
            return items;
        }
    }

    public static boolean contacts(boolean original) {
        try {
            return original && !Settings.HIDE_SHARE_CONTACTS.get();
        } catch (Throwable ex) {
            HookStatus.threw(FAMILY, "contacts", ex);
            Logger.printException(() -> "Could not read the Send to row setting", ex);
            return original;
        }
    }

    private static List<?> filter(String row, List<?> items, boolean hideRow) {
        if (items == null) return null;
        String rowHidden = "channels".equals(row) ? Settings.SHARE_HIDDEN_CHANNEL_ITEMS.get()
                : "actions".equals(row) ? Settings.SHARE_HIDDEN_ACTION_ITEMS.get() : "";
        List<?> result = filter(items, hideRow, rowHidden);
        // What each row was handed and kept, by TikTok's own action names. #120's small sheet lost
        // its whole top row with only a few of those hidden, and no export could say whether the
        // row came in short or this let too much go.
        Logger.printDebug(() -> "Share sheet model " + current + " " + row + ": " + items.size() + " in "
                + keys(items) + ", " + result.size() + " kept" + (result == items ? "" : " in a new list"));
        return result;
    }

    private static String keys(List<?> items) {
        List<String> keys = new ArrayList<>(items.size());
        for (Object item : items) {
            String key = Reflect.string(item, "key", "key");
            keys.add(key == null ? "?" : key);
        }
        return keys.toString();
    }

    private static List<?> filter(List<?> items, boolean hideRow) {
        return filter(items, hideRow, "");
    }

    private static List<?> filter(List<?> items, boolean hideRow, String rowHidden) {
        if (hideRow) return new ArrayList<>();
        String hidden = hiddenItems();
        if (!rowHidden.trim().isEmpty()) hidden = hidden.trim().isEmpty() ? rowHidden : hidden + "," + rowHidden;
        if (hidden.trim().isEmpty()) return items;
        List<Object> kept = new ArrayList<>(items.size());
        for (Object item : items) {
            String key = Reflect.string(item, "key", "key");
            boolean hide = false;
            if (key != null) {
                for (String entry : hidden.split("[,\\n]")) {
                    if (!entry.trim().isEmpty()
                            && ShareActionCatalog.canonical(entry).equals(ShareActionCatalog.canonical(key))) {
                        hide = true;
                        break;
                    }
                }
            }
            if (!hide) kept.add(item);
        }
        return kept.size() == items.size() ? items : kept;
    }

}
