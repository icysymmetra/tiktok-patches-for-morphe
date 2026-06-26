package app.morphe.extension.tiktok.suggestedaccounts;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BaseSettings;

@SuppressWarnings("unused")
public class HideSuggestedAccounts {
    private static volatile Boolean lastLoggedState;

    public static boolean enabled() {
        boolean state = BaseSettings.HIDE_SUGGESTED_ACCOUNTS.get();
        if (BaseSettings.DEBUG.get() && (lastLoggedState == null || lastLoggedState != state)) {
            lastLoggedState = state;
            Logger.printInfo(() -> "[Morphe HideSuggestedAccounts] enabled=" + state);
        }
        return state;
    }
}

