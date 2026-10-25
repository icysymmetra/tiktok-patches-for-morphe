package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SettingsStatusStartupTest {
    private static final String STATUS_CLASS = "app.morphe.extension.tiktok.settings.SettingsStatus";
    private static final String STATUS_SOURCE =
            "extensions/tiktok/src/main/java/" + STATUS_CLASS.replace('.', '/') + ".java";
    private static final String PATCH_ROOT = "patches/src/main/kotlin/app/morphe/patches/tiktok/interaction/";
    private static final String[] PATCHES = {
            "searchsuggestions/HideSearchSuggestionsPatch.kt", "downloads/AdvancedDownloadsPatch.kt"
    };
    private static final String[] FLAGS = {"hideSearchSuggestionsEnabled", "advancedDownloadsEnabled"};

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void firstRuntimeReadLoadsExactlyTheAppliedPatchesBeforeSettingsOpen() throws Exception {
        Path repository = Paths.get("").toAbsolutePath();
        while (repository != null && !Files.isRegularFile(repository.resolve(STATUS_SOURCE))) {
            repository = repository.getParent();
        }
        assertNotNull("The production status source must be available", repository);
        String source = new String(Files.readAllBytes(repository.resolve(STATUS_SOURCE)), StandardCharsets.UTF_8);
        // Android's compile boot classpath omits javax.tools; this plain JVM test runs on the JDK.
        Object compiler = Class.forName("javax.tools.ToolProvider")
                .getMethod("getSystemJavaCompiler").invoke(null);
        assertNotNull("The caller fixture needs the build JDK", compiler);

        for (int selection = 0; selection < 4; selection++) {
            Set<String> enabled = new LinkedHashSet<>();
            StringBuilder registrations = new StringBuilder();
            for (int patch = 0; patch < PATCHES.length; patch++) {
                if ((selection & (1 << patch)) == 0) continue;
                enabled.add(FLAGS[patch]);
                String patchSource = new String(Files.readAllBytes(repository.resolve(PATCH_ROOT + PATCHES[patch])),
                        StandardCharsets.UTF_8);
                Matcher hook = Pattern.compile("SettingsStatus;->(enable[A-Za-z0-9]+)\\(\\)V")
                        .matcher(patchSource);
                assertTrue("The selected patch must register its real status method", hook.find());
                registrations.append(hook.group(1)).append("();\n");
                assertFalse("Each selected patch has one status registration", hook.find());
            }

            // The APK patcher fills load() with these invoke-static registrations. Keep every
            // production field initializer and caller intact; only translate that injected body
            // to Java so a fresh JVM loader can exercise real class initialization, not set flags.
            Matcher body = Pattern.compile("public static void load\\(\\)\\s*\\{\\s*}").matcher(source);
            assertTrue("The unpatched registry body must remain empty", body.find());
            String patched = body.replaceFirst(Matcher.quoteReplacement(
                    "public static void load() {\n" + registrations + "}"));
            Path directory = temporary.newFolder("selection-" + selection).toPath();
            Path javaFile = directory.resolve("SettingsStatus.java");
            Path classes = Files.createDirectory(directory.resolve("classes"));
            Files.write(javaFile, patched.getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int result = (Integer) Class.forName("javax.tools.Tool")
                    .getMethod("run", InputStream.class, OutputStream.class, OutputStream.class, String[].class)
                    .invoke(compiler, null, errors, errors, new String[]{"-encoding", "UTF-8", "--release", "11",
                            "-d", classes.toString(), javaFile.toString()});
            assertEquals(errors.toString(StandardCharsets.UTF_8.name()), 0, result);

            try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, null)) {
                Class<?> status = Class.forName(STATUS_CLASS, false, loader);
                assertMembership("Cold runtime read", status, enabled);
                status.getMethod("load").invoke(null);
                assertMembership("Later settings open", status, enabled);
            }
        }
    }

    private static void assertMembership(String stage, Class<?> status, Set<String> enabled) throws Exception {
        for (Field field : status.getDeclaredFields()) {
            if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                assertEquals(stage + " must preserve selected membership for " + field.getName(),
                        enabled.contains(field.getName()), field.getBoolean(null));
            }
        }
    }
}
