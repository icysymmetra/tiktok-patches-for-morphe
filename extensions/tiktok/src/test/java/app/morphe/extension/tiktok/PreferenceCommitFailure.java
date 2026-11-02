package app.morphe.extension.tiktok;

import android.content.SharedPreferences;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.SharedPrefCategory;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** Injects storage failures at the real settings store's commit boundary. */
public final class PreferenceCommitFailure implements AutoCloseable {
    private final Field field;
    private final SharedPreferences original;

    public PreferenceCommitFailure(Predicate<Set<String>> fails, boolean throwsException) throws Exception {
        original = Setting.preferences.preferences;
        field = SharedPrefCategory.class.getDeclaredField("preferences");
        field.setAccessible(true);
        SharedPreferences intercepted = (SharedPreferences) Proxy.newProxyInstance(
                original.getClass().getClassLoader(), new Class[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("edit")) return invoke(method, original, args);
                    SharedPreferences.Editor editor = original.edit();
                    Set<String> keys = new HashSet<>();
                    return Proxy.newProxyInstance(editor.getClass().getClassLoader(),
                            new Class[]{SharedPreferences.Editor.class}, (editorProxy, call, values) -> {
                                if (values != null && values.length > 0 && values[0] instanceof String) {
                                    keys.add((String) values[0]);
                                }
                                if (call.getName().equals("commit") && fails.test(keys)) {
                                    if (throwsException) throw new IllegalStateException("injected commit failure");
                                    return false;
                                }
                                Object result = invoke(call, editor, values);
                                return result instanceof SharedPreferences.Editor ? editorProxy : result;
                            });
                });
        field.set(Setting.preferences, intercepted);
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
    }

    @Override public void close() throws Exception { field.set(Setting.preferences, original); }
}
