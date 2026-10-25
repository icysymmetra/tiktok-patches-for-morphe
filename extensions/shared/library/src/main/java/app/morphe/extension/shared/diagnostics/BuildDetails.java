/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.shared.diagnostics;

import android.content.Context;
import app.morphe.extension.shared.Utils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable APK facts. No setting, log or user-entered option supplies these values. */
public final class BuildDetails {
    public static final String ASSET = "hushfeed-build-v1.txt";
    private static final int MAX_BYTES = 4096;
    private static final String[] KEYS = {
            "schema", "bundle_version", "source_commit", "source_clean", "source_start", "source_end",
            "target_package", "target_version", "target_version_code", "patcher_bundle_compat",
            "patcher_applying_engine", "amoled", "amoled_color", "language_packs", "native_locales_retained",
            "tool_p2p_relay", "tool_core_assets", "tool_creation", "tool_live_extras", "version_code_override",
            "resource_patches"
    };
    /** Records written before resource_patches end one line earlier; that key then says unknown. */
    private static final int LEGACY_KEYS = KEYS.length - 1;
    private static final int MAX_RESOURCE_PATCHES = 32;
    private static final String VERSION = "[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?";
    private static final Set<String> LOCALES = new HashSet<>(Arrays.asList((
            "af ar az bg bn ca ceb cs da de el en es et fa fi fil fr ga gu he hi hr hu id in is it iw ja jv "
                    + "kk km kn ko lt lv ml mr ms my nb nl or pa pl pt ro ru sk sl sq sv sw ta te th tr uk ur uz vi zh zu")
            .split(" ")));

    /** How many names the report prints. The count line still says how many there were. */
    private static final int MAX_LISTED_SWITCHES = 400;
    private static final int MAX_SWITCH_NAME = 64;
    /** Null until the extension reports which switches registered, so the report says unknown. */
    private static volatile List<String> registeredSwitches;

    private BuildDetails() {}

    /**
     * The switches the installed extension says the patcher registered, one or more per patch
     * that changed TikTok's code. Names outside letters and digits are dropped, so nothing typed
     * by a person or read from TikTok can reach the report. Null returns the record to unknown.
     */
    public static void setRegisteredSwitches(Collection<String> names) {
        if (names == null) {
            registeredSwitches = null;
            return;
        }
        Set<String> clean = new HashSet<>();
        for (String name : names) {
            if (name != null && name.length() <= MAX_SWITCH_NAME && name.matches("[A-Za-z0-9]+")) clean.add(name);
        }
        List<String> sorted = new ArrayList<>(clean);
        Collections.sort(sorted);
        registeredSwitches = Collections.unmodifiableList(sorted);
    }

    public static String readMetadata(Context context) {
        if (context == null) return "";
        try (InputStream input = context.getAssets().open(ASSET);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_BYTES) return "";
                bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException unavailable) {
            // Older patched APKs have no build asset. They must say unknown, not guess.
            return "";
        }
    }

    public static String report() { return report(Utils.getBuildMetadata()); }

    public static String report(String metadata) {
        return "MORPHE BUILD DETAILS\nschema: 1\n\n[BUILD DETAILS]\n" + section(metadata);
    }

    /** Fixed validated fields preserve hashes exactly instead of passing through ID redaction. */
    public static String section(String metadata) {
        Map<String, String> facts = parse(metadata);
        StringBuilder text = new StringBuilder("build_metadata: ")
                .append(facts.get("schema").equals("1") ? "available" : "unknown").append('\n');
        for (String key : KEYS) {
            if (key.equals("schema")) continue;
            String value = facts.get(key);
            boolean choice = key.equals("amoled") || key.equals("language_packs")
                    || key.startsWith("tool_") || key.equals("version_code_override");
            if (choice) {
                text.append("patch_time_selected.").append(key).append(": ")
                        .append(value.equals("unknown") ? "unknown" : value.equals("not_selected") ? "false" : "true")
                        .append('\n');
            }
            text.append(choice || key.equals("amoled_color") || key.equals("native_locales_retained")
                    || key.equals("resource_patches") ? "patch_time." : "").append(key).append(": ").append(value).append('\n');
        }
        List<String> switches = registeredSwitches;
        text.append("registered_switches_count: ").append(switches == null ? "unknown" : String.valueOf(switches.size()))
                .append("\nregistered_switches: ");
        if (switches == null) {
            text.append("unknown");
        } else if (switches.isEmpty()) {
            text.append("none");
        } else {
            int listed = Math.min(switches.size(), MAX_LISTED_SWITCHES);
            for (int index = 0; index < listed; index++) {
                if (index > 0) text.append(',');
                text.append(switches.get(index));
            }
            if (listed < switches.size()) text.append(",+").append(switches.size() - listed).append(" more");
        }
        return text.append('\n').toString();
    }

    private static Map<String, String> parse(String metadata) {
        Map<String, String> facts = new LinkedHashMap<>();
        if (metadata != null && metadata.length() <= MAX_BYTES) {
            String[] lines = metadata.split("\n", -1);
            int count = lines.length - 1;
            if ((count == KEYS.length || count == LEGACY_KEYS) && lines[count].isEmpty()) {
                for (int index = 0; index < count; index++) {
                    String prefix = KEYS[index] + "=";
                    if (!lines[index].startsWith(prefix)) break;
                    facts.put(KEYS[index], lines[index].substring(prefix.length()));
                }
                if (count == LEGACY_KEYS && facts.size() == LEGACY_KEYS) facts.put("resource_patches", "unknown");
            }
        }
        if (facts.size() == KEYS.length && valid(facts)) return Collections.unmodifiableMap(facts);
        facts.clear();
        for (String key : KEYS) facts.put(key, "unknown");
        return Collections.unmodifiableMap(facts);
    }

    private static boolean valid(Map<String, String> facts) {
        if (!facts.get("schema").equals("1")) return false;
        for (String key : new String[]{"bundle_version", "patcher_bundle_compat", "patcher_applying_engine"}) {
            if (!matches(facts.get(key), VERSION, 80)) return false;
        }
        if (!matches(facts.get("source_commit"), "[0-9A-Fa-f]{40}", 40)
                || !matches(facts.get("source_start"), "[0-9A-Fa-f]{64}", 64)
                || !matches(facts.get("source_end"), "[0-9A-Fa-f]{64}", 64)
                || !oneOf(facts.get("source_clean"), "unknown", "true", "false")) return false;
        if (facts.get("source_clean").equals("true") && (facts.get("source_commit").equals("unknown")
                || facts.get("source_start").equals("unknown")
                || !facts.get("source_start").equals(facts.get("source_end")))) return false;
        if (!validTarget(facts)) return false;
        if (!oneOf(facts.get("amoled"), "unknown", "not_selected", "unverified", "applied")) return false;
        String color = facts.get("amoled_color");
        if (facts.get("amoled").equals("applied")) {
            if (!color.matches("#[A-F0-9]{6}")) return false;
        } else if (!color.equals(facts.get("amoled").equals("not_selected") ? "not_selected" : "unknown")) return false;
        String languages = facts.get("language_packs");
        if (!oneOf(languages, "unknown", "not_selected", "unverified", "kept_all", "stripped", "already_stripped")) return false;
        if (oneOf(languages, "kept_all", "stripped", "already_stripped")) {
            List<String> codes = Arrays.asList(facts.get("native_locales_retained").split(",", -1));
            List<String> sorted = new ArrayList<>(new HashSet<>(codes));
            Collections.sort(sorted);
            if (!codes.equals(sorted) || !codes.contains("en") || !LOCALES.containsAll(codes)
                    || codes.contains("he") != codes.contains("iw") || codes.contains("id") != codes.contains("in")) return false;
        } else if (!facts.get("native_locales_retained").equals("unknown")) return false;
        for (String key : new String[]{"tool_p2p_relay", "tool_core_assets", "tool_creation", "tool_live_extras"}) {
            if (!oneOf(facts.get(key), "unknown", "not_selected", "unverified", "absent", "stripped", "already_stripped")) return false;
        }
        if (!oneOf(facts.get("version_code_override"), "unknown", "not_selected", "unverified", "2147483647")) return false;
        return validResourcePatches(facts.get("resource_patches"));
    }

    /** {@code unknown}, {@code none}, or patch names in letters and digits, sorted and unique. */
    private static boolean validResourcePatches(String value) {
        if (oneOf(value, "unknown", "none")) return true;
        List<String> names = Arrays.asList(value.split(",", -1));
        if (names.size() > MAX_RESOURCE_PATCHES) return false;
        for (String name : names) {
            if (!name.matches("[a-z][A-Za-z0-9]{0,63}")) return false;
        }
        List<String> sorted = new ArrayList<>(new HashSet<>(names));
        Collections.sort(sorted);
        return names.equals(sorted);
    }

    /** The build this Metra bundle declares: TikTok global 47.4.3. */
    private static boolean validTarget(Map<String, String> facts) {
        String name = facts.get("target_package"), version = facts.get("target_version"), code = facts.get("target_version_code");
        if (name.equals("unknown")) return version.equals("unknown") && code.equals("unknown");
        return oneOf(name + "/" + version + "=" + code,
                "com.zhiliaoapp.musically/47.4.3=2024704030");
    }

    private static boolean matches(String value, String pattern, int max) {
        return value.equals("unknown") || value.length() <= max && value.matches(pattern);
    }

    private static boolean oneOf(String value, String... choices) {
        return Arrays.asList(choices).contains(value);
    }
}
