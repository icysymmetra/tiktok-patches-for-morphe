package app.morphe.extension.tiktok;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.rules.ExternalResource;
import org.robolectric.RuntimeEnvironment;

/**
 * Takes every setting a test made for itself back out of the settings registry.
 *
 * <p>A new {@code Setting} joins the one static list every setting lives in, and takes its key's
 * lookup, for as long as the Robolectric sandbox lives. That is every later class with the same
 * config, not just the rest of the class that made it. Left there, a setting planted twice under
 * one key made every later backup list the key twice and get refused as incomplete; one planted
 * under a real key answered for that key instead of the real one; and one whose write always
 * fails broke the next restore or reset that walked the whole list. BudgetRestoreTest failed on
 * the first of those in one class order and passed in another.
 *
 * <p>Declare it as a {@code @Rule} in any test class that constructs a setting:
 *
 * <pre>{@code @Rule public final SettingsRegistryRule settingsRegistry = new SettingsRegistryRule();}</pre>
 *
 * <p>{@code SettingsContextGuardTest} holds every test source to that.
 */
public final class SettingsRegistryRule extends ExternalResource {
    private final List<Setting<?>> before = new ArrayList<>();
    private final Map<String, Setting<?>> lookupBefore = new HashMap<>();

    @Override
    protected void before() {
        // Reading the registry initialises Setting, whose static initialiser needs a context.
        Utils.setContext(RuntimeEnvironment.getApplication());
        // The real settings have to be in the registry before the snapshot, or a test that
        // happens to be the first to load them would have all of them taken out afterwards.
        initialise(BaseSettings.class);
        initialise(Settings.class);
        before.clear();
        before.addAll(registry());
        lookupBefore.clear();
        lookupBefore.putAll(lookup());
    }

    @Override
    protected void after() {
        Set<Setting<?>> known = Collections.newSetFromMap(new IdentityHashMap<>());
        known.addAll(before);
        List<Setting<?>> planted = new ArrayList<>();
        for (Setting<?> each : registry()) {
            if (!known.contains(each)) planted.add(each);
        }
        registry().removeIf(each -> !known.contains(each));
        // A planted setting under a real key took the lookup, so the whole map goes back.
        Map<String, Setting<?>> lookup = lookup();
        lookup.clear();
        lookup.putAll(lookupBefore);
        for (Setting<?> each : planted) {
            // A real key's stored value belongs to the real setting.
            if (!lookupBefore.containsKey(each.key)) Setting.preferences.removeKey(each.key);
        }
    }

    private static void initialise(Class<?> holder) {
        try {
            Class.forName(holder.getName(), true, holder.getClassLoader());
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Could not load " + holder.getName(), exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Setting<?>> registry() {
        return (List<Setting<?>>) field("SETTINGS");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Setting<?>> lookup() {
        return (Map<String, Setting<?>>) field("PATH_TO_SETTINGS");
    }

    private static Object field(String name) {
        try {
            Field field = Setting.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not read Setting." + name, exception);
        }
    }
}
