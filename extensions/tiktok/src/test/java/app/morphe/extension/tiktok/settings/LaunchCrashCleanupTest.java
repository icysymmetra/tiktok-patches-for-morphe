/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.diagnostics.JavaCrashCapture;
import app.morphe.extension.tiktok.featuregatelab.FeatureGateLabStore;
import app.morphe.extension.tiktok.privacy.DeviceIdentity;
import app.morphe.extension.tiktok.privacy.BenchmarkRuns;
import app.morphe.extension.tiktok.settings.preference.ReleaseNotes;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Hushfeed's preferences files on the keep-list of TikTok's launch-crash cleanup. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LaunchCrashCleanupTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** TikTok 47.0.3's own list, `X.03JS.LIZIZ`. */
    private static final String[] TIKTOK_KEEPS = {
            "safe_mode_config_sp", "safe_mode_exception_repo", "safe_mode_v2_status",
            "cold_boot_launch_protector", "launch_crash_intercept_sp", "aweme_user",
            "token_shared_preference", "sp_TicketGuardHelper", "com.bytedance.sdk.account_setting",
            "key_language_sp_key"};

    /**
     * A call that opens or names a preferences file: TikTok's cleanup deletes that file unless
     * it's kept. Each call is held to the text of its first argument, so a second file opened
     * from a class already on the list is noticed, and so is a name built from a kept constant
     * ({@code PREFERENCES + "_v2"}), which a count per file let through.
     */
    private static final Pattern OPENS_BY_NAME = Pattern.compile(
            "(?:getSharedPreferences|new SharedPrefCategory|getDefaultSharedPreferences"
                    + "|getPreferences|setSharedPreferencesName)\\s*\\(");

    @Test public void tiktoksListKeepsItsOrderAndGainsHushfeedsFiles() {
        String[] kept = LaunchCrashCleanup.keepHushfeedFiles(TIKTOK_KEEPS.clone());
        assertArrayEquals("TikTok's own names moved or went", TIKTOK_KEEPS,
                Arrays.copyOf(kept, TIKTOK_KEEPS.length));
        assertEquals(Arrays.asList("morphe_prefs", "morphe_feature_gate_lab", "hushfeed-calm-feed-preset",
                        "hushfeed_release_notes", "hushfeed_device_identity", "morphe_optional_components"),
                Arrays.asList(kept).subList(TIKTOK_KEEPS.length, kept.length));
        assertArrayEquals(LaunchCrashCleanup.HUSHFEED_FILES, LaunchCrashCleanup.keepHushfeedFiles(null));
    }

    @Test public void everyPreferencesFileHushfeedOpensIsKept() throws Exception {
        // The first argument of every opening call: a kept constant, or the one field that a
        // kept constant is handed to. Anything else is a file TikTok's cleanup would delete.
        Map<String, List<String>> expected = new TreeMap<>();
        expected.put("diagnostics/JavaCrashCapture.java", Arrays.asList("PREFS_NAME"));
        expected.put("featuregatelab/FeatureGateLabStore.java", Arrays.asList("PREFS_NAME"));
        expected.put("settings/preference/ReleaseNotes.java", Arrays.asList(
                "PREFS_NAME", "PREFS_NAME", "PREFS_NAME"));
        expected.put("settings/CalmFeedPreset.java", Arrays.asList("PREFERENCES"));
        expected.put("privacy/DeviceIdentity.java", Arrays.asList("PREFERENCES", "PREFERENCES"));
        expected.put("privacy/BenchmarkRuns.java", Arrays.asList("STATE_FILE"));
        expected.put("settings/MetraLegacySettings.java", Arrays.asList("Setting.PREFERENCES_NAME"));
        expected.put("translation/DmTranslationPreview.java", Arrays.asList("Setting.preferences.name"));
        expected.put("translation/ReceivedDmTranslationPreview.java", Arrays.asList("Setting.preferences.name"));
        expected.put("shared/settings/EarlySwitch.java", Arrays.asList("Setting.PREFERENCES_NAME"));
        expected.put("shared/settings/Setting.java", Arrays.asList("PREFERENCES_NAME"));
        expected.put("shared/settings/preference/AbstractPreferenceFragment.java", Arrays.asList("Setting.preferences.name"));
        expected.put("shared/settings/preference/SharedPrefCategory.java", Arrays.asList("name"));
        assertEquals("a call opens preferences that TikTok's launch-crash cleanup would delete; "
                        + "add the file's name to LaunchCrashCleanup.HUSHFEED_FILES and the call to this list",
                expected, openersByName());

        List<String> kept = Arrays.asList(LaunchCrashCleanup.HUSHFEED_FILES);
        assertTrue(kept.contains(Setting.preferences.name));
        assertTrue(kept.contains(constant(JavaCrashCapture.class, "PREFS_NAME")));
        assertTrue(kept.contains(FeatureGateLabStore.PREFS_NAME));
        assertTrue(kept.contains(CalmFeedPreset.PREFERENCES));
        assertTrue(kept.contains(ReleaseNotes.PREFS_NAME));
        assertTrue(kept.contains(DeviceIdentity.PREFERENCES));
        assertTrue(kept.contains(BenchmarkRuns.STATE_FILE));
    }

    private static String constant(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    /**
     * Each source that opens preferences by name, with the text of every call's first argument.
     * A call with no argument names no file (the preference manager's own
     * {@code getSharedPreferences()} uses the name set through {@code setSharedPreferencesName})
     * and is left out.
     */
    private static Map<String, List<String>> openersByName() throws IOException {
        Map<String, List<String>> openers = new TreeMap<>();
        for (Path source : payloadSources()) {
            String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
            Matcher matcher = OPENS_BY_NAME.matcher(text);
            List<String> arguments = new ArrayList<>();
            while (matcher.find()) {
                String argument = firstArgument(text, matcher.end());
                if (!argument.isEmpty()) arguments.add(argument);
            }
            if (!arguments.isEmpty()) openers.put(relativeName(source), arguments);
        }
        return openers;
    }

    /** The text between the opening parenthesis at {@code from} and the first comma or closing parenthesis at its depth. */
    private static String firstArgument(String text, int from) {
        int depth = 0;
        boolean quoted = false;
        for (int at = from; at < text.length(); at++) {
            char c = text.charAt(at);
            if (quoted) {
                if (c == '\\') at++;
                else if (c == '"') quoted = false;
                continue;
            }
            if (c == '"') quoted = true;
            else if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) return text.substring(from, at).trim();
                depth--;
            } else if (c == ',' && depth == 0) {
                return text.substring(from, at).trim();
            }
        }
        return text.substring(from).trim();
    }

    private static String relativeName(Path source) {
        String name = source.toString().replace('\\', '/');
        int at = name.indexOf("/app/morphe/extension/tiktok/");
        if (at >= 0) return name.substring(at + "/app/morphe/extension/tiktok/".length());
        at = name.indexOf("/app/morphe/extension/shared/");
        if (at >= 0) return "shared/" + name.substring(at + "/app/morphe/extension/shared/".length());
        return name;
    }

    /** Both trees whose Java ends up in the payload TikTok runs. */
    private static List<Path> payloadSources() throws IOException {
        List<Path> roots = new ArrayList<>();
        for (String candidate : new String[]{
                "src/main/java",
                "extensions/tiktok/src/main/java",
                "../shared/library/src/main/java",
                "extensions/shared/library/src/main/java"}) {
            File directory = new File(candidate);
            if (directory.isDirectory()) roots.add(directory.toPath());
        }
        assertTrue("no payload source tree was found from " + new File(".").getAbsolutePath(),
                roots.size() >= 2);
        List<Path> sources = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> walk = Files.walk(root)) {
                sources.addAll(walk.filter(path -> path.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .collect(Collectors.toList()));
            }
        }
        return sources;
    }
}
