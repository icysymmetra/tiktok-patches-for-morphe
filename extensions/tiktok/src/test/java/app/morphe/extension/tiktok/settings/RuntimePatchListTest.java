package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The switch list Build details shows is read back from the patcher's registrations, nothing else. */
public class RuntimePatchListTest {
    private static final String PATCH_ROOT = "patches/src/main/kotlin/app/morphe/patches/tiktok";
    private static final Pattern REGISTRATION = Pattern.compile("registerForBuildDetails\\(\"(enable[A-Za-z0-9]+)\"\\)");
    /** What follows a registration: execute's closing brace, then the patch's. Nothing runs after it. */
    private static final String END_OF_EXECUTE = "\n    }\n}\n";
    private static final Pattern PATCH_START = Pattern.compile(
            "(?m)^(?:(?:internal|private|public) )?val \\w+ = (bytecodePatch|resourcePatch|rawResourcePatch)\\(");
    /** A top-level patch declaration ends at the first closing brace in column 0. */
    private static final String END_OF_PATCH = "\n}\n";
    private static final Pattern PATCH_NAME = Pattern.compile("\\bname = \"([^\"]+)\"");
    private static final Pattern ANY_REGISTRATION =
            Pattern.compile("SettingsStatus;->|SETTINGS_STATUS->enable|registerForBuildDetails\\(\"");
    /**
     * A resource patch has its own choice field, or adds its name to resource_patches, on a line
     * that isn't commented out.
     */
    private static final Pattern RESOURCE_RECORD = Pattern.compile(
            "(?m)^(?![ \\t]*//).*(?:buildChoicePatch\\(BuildChoice\\.|BuildDetails\\.applied\\()");
    private static final Pattern RESOURCE_APPLIED = Pattern.compile(
            "(?m)^([ \\t]*)BuildDetails\\.applied\\(get\\(BUILD_DETAILS_ASSET\\), \"([^\"]*)\"\\)");
    /** BuildDetailsPatch.kt's resourcePatchName, which applied() requires. */
    private static final Pattern RESOURCE_NAME = Pattern.compile("[a-z][A-Za-z0-9]{0,63}");

    @Test
    public void registeredNamesEveryRaisedSwitchAndNothingElse() throws Exception {
        Map<Field, Boolean> saved = new LinkedHashMap<>();
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() == boolean.class && Modifier.isStatic(modifiers) && Modifier.isPublic(modifiers)) {
                saved.put(field, field.getBoolean(null));
            }
        }
        try {
            for (Field field : saved.keySet()) field.setBoolean(null, false);
            assertEquals(Collections.emptyList(), SettingsStatus.registered());

            SettingsStatus.enableNetworkRequestReport();
            SettingsStatus.enableCommentSendFix();
            SettingsStatus.enableGhostMode();
            assertEquals(Arrays.asList("commentSendFix", "ghostMode", "networkRequestReport"), sorted(SettingsStatus.registered()));

            for (Field field : saved.keySet()) field.setBoolean(null, true);
            List<String> all = SettingsStatus.registered();
            assertEquals("every public switch ends in Enabled and is listed", saved.size(), all.size());
            assertTrue(all.contains("featureGateRecorder"));
        } finally {
            for (Map.Entry<Field, Boolean> entry : saved.entrySet()) entry.getKey().setBoolean(null, entry.getValue());
        }
    }

    @Test
    public void everyPatchThatChangesTikToksCodeRegisters() throws Exception {
        Set<String> silent = new TreeSet<>();
        for (Path file : patchSources()) silent.addAll(silentPatches(read(file)));
        assertEquals("a named patch that never records itself is missing from Build details",
                Collections.emptySet(), silent);
    }

    @Test
    public void aResourcePatchCountsOnlyWithItsChoiceOrItsName() {
        String quiet = "val quietPatch = resourcePatch(\n    name = \"Quiet\",\n) {\n    execute {\n    }\n}\n";
        assertEquals(Collections.singleton("Quiet"), silentPatches(quiet));
        // A bytecode registration doesn't stand in for a resource patch's record.
        assertEquals(Collections.singleton("Quiet"), silentPatches(quiet.replace("    execute {\n",
                "    execute {\n        registerForBuildDetails(\"enableQuiet\")\n")));
        assertEquals(Collections.emptySet(), silentPatches(quiet.replace("    execute {\n",
                "    execute {\n        BuildDetails.applied(get(BUILD_DETAILS_ASSET), \"quiet\")\n")));
        assertEquals(Collections.emptySet(), silentPatches(quiet.replace(") {\n",
                ") {\n    dependsOn(buildChoicePatch(BuildChoice.AMOLED))\n")));
        // A record that's commented out writes nothing.
        assertEquals(Collections.singleton("Quiet"), silentPatches(quiet.replace("    execute {\n",
                "    execute {\n        // BuildDetails.applied(get(BUILD_DETAILS_ASSET), \"quiet\")\n")));
    }

    @Test
    public void theNamePatternMatchesWhatAppliedAccepts() {
        assertTrue(RESOURCE_NAME.matcher("changeAppName").matches());
        assertFalse(RESOURCE_NAME.matcher("ChangeAppName").matches());
        assertFalse(RESOURCE_NAME.matcher("change_app_name").matches());
        assertFalse(RESOURCE_NAME.matcher("a" + "b".repeat(64)).matches());
    }

    @Test
    public void everyResourcePatchNamesItselfAndRecordsLast() throws Exception {
        Set<String> applied = new TreeSet<>();
        for (Path file : patchSources()) {
            String source = read(file);
            Matcher call = RESOURCE_APPLIED.matcher(source);
            while (call.find()) {
                String name = call.group(2);
                assertTrue(file + " records " + name + ", a name applied() refuses",
                        RESOURCE_NAME.matcher(name).matches());
                assertTrue(file + " records " + name + " for its own patch",
                        source.contains("val " + name + "Patch = resourcePatch(")
                                || source.contains("val " + name + "Patch = rawResourcePatch("));
                assertTrue(file + " already records " + name, applied.add(name));
                assertTrue(file + " records " + name + " as the last statement of execute, at its indent",
                        call.group(1).equals("        ") && source.startsWith(END_OF_EXECUTE, call.end()));
            }
        }
        assertEquals(new TreeSet<>(Arrays.asList("accentColor", "changeAppName", "customLauncherIcon", "trustUserCertificates")),
                applied);
    }

    @Test
    public void aRegistrationOnlyInCodeAfterThePatchDoesNotCount() {
        String source = "val quietPatch = bytecodePatch(\n    name = \"Quiet\",\n) {\n    execute {\n    }\n}\n\n"
                + "private fun helper() {\n    \"SettingsStatus;->enableQuiet()V\"\n}\n";
        assertEquals(Collections.singleton("Quiet"), silentPatches(source));
    }

    @Test
    public void anInternalPatchEndsTheBlockBeforeIt() {
        String source = "val quietPatch = bytecodePatch(\n    name = \"Quiet\",\n) {\n    execute {\n    }\n"
                + "internal val helperPatch = bytecodePatch(\n) {\n    execute {\n"
                + "        registerForBuildDetails(\"enableHelper\")\n    }\n}\n";
        assertEquals(Collections.singleton("Quiet"), silentPatches(source));
    }

    /**
     * Named patches in one Kotlin source whose own declaration never records itself: a bytecode
     * patch registers a switch, a resource patch records its choice or its name.
     */
    private static Set<String> silentPatches(String source) {
        Matcher start = PATCH_START.matcher(source);
        List<Integer> starts = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        while (start.find()) {
            starts.add(start.start());
            kinds.add(start.group(1));
        }
        Set<String> silent = new TreeSet<>();
        for (int index = 0; index < starts.size(); index++) {
            Pattern record = kinds.get(index).equals("bytecodePatch") ? ANY_REGISTRATION : RESOURCE_RECORD;
            int end = index + 1 < starts.size() ? starts.get(index + 1) : source.length();
            int close = source.indexOf(END_OF_PATCH, starts.get(index));
            if (close >= 0) end = Math.min(end, close + END_OF_PATCH.length());
            String block = source.substring(starts.get(index), end);
            Matcher name = PATCH_NAME.matcher(block.substring(0, Math.min(400, block.length())));
            // An unnamed patch is a dependency another one pulls in, never a Manager row.
            if (name.find() && !record.matcher(block).find()) silent.add(name.group(1));
        }
        return silent;
    }

    @Test
    public void everyRowlessRegistrationNamesARealSwitchAndRunsLast() throws Exception {
        Set<String> registered = new TreeSet<>();
        for (Path file : patchSources()) {
            String source = read(file);
            Matcher call = REGISTRATION.matcher(source);
            while (call.find()) {
                String method = call.group(1);
                SettingsStatus.class.getMethod(method);
                String flag = Character.toLowerCase(method.charAt(6)) + method.substring(7) + "Enabled";
                assertEquals(file + " registers a switch SettingsStatus has", boolean.class,
                        SettingsStatus.class.getField(flag).getType());
                assertTrue(file + " already registers " + method, registered.add(method));
                assertTrue(file + " registers " + method + " as the last statement of execute, at its indent",
                        source.startsWith(END_OF_EXECUTE, call.end())
                                && source.substring(source.lastIndexOf('\n', call.start()) + 1, call.start())
                                        .equals("        "));
            }
        }
        assertEquals(new TreeSet<>(Arrays.asList("enableCommentPublishDiagnostics", "enableCommentSendFix",
                "enableFeatureGateLab", "enableFollowDiagnostics", "enableGoogleLoginFix",
                "enableNetworkRequestReport", "enableNoLoginRequirement", "enableNoScreenCaptureDetection",
                "enableRepostDiagnostics", "enableRiskControlCaptcha", "enableRunBesideStoreApp",
                "enableSettings")), registered);
    }

    private static List<Path> patchSources() throws Exception {
        Path repository = Paths.get("").toAbsolutePath();
        while (repository != null && !Files.isDirectory(repository.resolve(PATCH_ROOT))) {
            repository = repository.getParent();
        }
        assertNotNull("The patch sources must be available", repository);
        try (Stream<Path> files = Files.walk(repository.resolve(PATCH_ROOT))) {
            return files.filter(path -> path.toString().endsWith(".kt")).sorted().collect(Collectors.toList());
        }
    }

    private static String read(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static List<String> sorted(List<String> names) {
        List<String> copy = new ArrayList<>(names);
        Collections.sort(copy);
        return copy;
    }
}
