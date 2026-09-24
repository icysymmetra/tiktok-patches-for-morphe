package app.morphe.extension.tiktok.settings;

public final class Settings {
    public static final BooleanSetting HIDE_ALTERNATE_FOR_YOU_BATCHES = new BooleanSetting();
    public static final class BooleanSetting {
        public boolean enabled;
        public boolean get() { return enabled; }
    }
}
