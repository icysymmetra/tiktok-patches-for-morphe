package app.morphe.extension.tiktok;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Every test class that touches the settings registry installs a context before its first case.
 *
 * <p>A class that does not poisons {@code BaseSettings} for the rest of the Robolectric sandbox,
 * and the failure lands on the classes that run after it rather than on the one at fault. That
 * makes it an ordering bug: it appears when a new test class changes the order Gradle scans in,
 * a long way from the change that caused it. On 2026-09-08 three added classes turned it into
 * 419 failures in one run.
 *
 * <p>A class passes this guard by declaring {@link SettingsContextRule} or by calling
 * {@code Utils.setContext} inside a method annotated {@code @Before}. A call in {@code @After}
 * does not count: the registry has already been read by then, which is exactly what
 * NumberInputPreferenceTest and ShareModelFilterTest each did.
 *
 * <p>It also holds every class that builds a setting of its own to {@link SettingsRegistryRule},
 * which takes it back out. The bug has the same shape: a planted setting outlives its class, and
 * a class that runs later in the same sandbox is the one that fails.
 */
public class SettingsContextGuardTest {
    /**
     * A read or a write of a setting: Settings.SOME_KEY, BaseSettings.SOME_KEY, or a Setting
     * built by hand.
     */
    private static final Pattern TOUCHES_SETTINGS =
            Pattern.compile("\\b\\w*Settings\\.[A-Z][A-Z0-9_]+|\\bnew\\s+\\w*Setting\\s*\\(");

    private static final Pattern DECLARES_RULE =
            Pattern.compile("@Rule[^;]*\\bnew\\s+SettingsContextRule\\s*\\(", Pattern.DOTALL);

    /** A Setting built by hand, generic or not. It joins the registry for as long as the sandbox lives. */
    private static final Pattern MAKES_A_SETTING =
            Pattern.compile("\\bnew\\s+\\w*Setting\\s*(?:<[^>]*>)?\\s*\\(");

    private static final Pattern DECLARES_REGISTRY_RULE =
            Pattern.compile("@Rule[^;]*\\bnew\\s+SettingsRegistryRule\\s*\\(", Pattern.DOTALL);

    /** Where a {@code @Before} method starts, so its body can be brace matched from there. */
    private static final Pattern BEFORE_METHOD = Pattern.compile("@Before\\b(?!Class)");

    @Test
    public void everyTestClassThatTouchesASettingInstallsAContextFirst() throws IOException {
        List<Path> sources = testSources();
        assertTrue("no test sources were found to check", sources.size() > 50);

        List<String> offenders = new ArrayList<>();
        for (Path source : sources) {
            // This class names the pattern it looks for, in a string, and is plain JUnit: it has
            // no Robolectric sandbox to take an application context from.
            if (source.getFileName().toString().equals("SettingsContextGuardTest.java")) continue;
            // Files.readString is not on the Android bootclasspath this module compiles
            // against, and neither is Stream.toList below.
            String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
            if (!TOUCHES_SETTINGS.matcher(text).find()) continue;
            if (DECLARES_RULE.matcher(text).find()) continue;
            if (installsContextFirst(text)) continue;
            offenders.add(source.getFileName().toString());
        }

        if (!offenders.isEmpty()) {
            fail("These test classes read or write a Setting without installing a context before"
                    + " their first case, which poisons BaseSettings for every class that runs"
                    + " after them in the same sandbox. Add"
                    + " `@Rule public final SettingsContextRule settingsContext = new"
                    + " SettingsContextRule();` to each: " + String.join(", ", offenders));
        }
    }

    /**
     * Every test class that builds a setting takes it back out of the registry when it is done.
     *
     * <p>Left in, a planted setting is listed in every later backup in the same sandbox. Two of
     * them under one key made BudgetRestoreTest's backups incomplete when it happened to run after
     * NumberInputPreferenceTest, and pass when it ran first.
     */
    @Test
    public void everyTestClassThatMakesASettingTakesItBackOut() throws IOException {
        List<String> makers = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (Path source : testSources()) {
            String name = source.getFileName().toString();
            // This class names the pattern, in a string, and builds no setting.
            if (name.equals("SettingsContextGuardTest.java")) continue;
            String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
            if (!MAKES_A_SETTING.matcher(text).find()) continue;
            makers.add(name);
            if (!DECLARES_REGISTRY_RULE.matcher(text).find()) offenders.add(name);
        }

        // OverlayControlsTest builds only the generic form, new Setting<String>(...).
        assertTrue("the guard did not see a generic Setting being built: " + makers,
                makers.contains("OverlayControlsTest.java"));
        assertTrue("the guard did not see a plain Setting being built: " + makers,
                makers.contains("NumberInputPreferenceTest.java"));
        if (!offenders.isEmpty()) {
            fail("These test classes build a Setting and leave it in the registry, where it outlives"
                    + " the class and turns up in every later backup in the same sandbox. Add"
                    + " `@Rule public final SettingsRegistryRule settingsRegistry = new"
                    + " SettingsRegistryRule();` to each: " + String.join(", ", offenders));
        }
    }

    /** This class itself names a Setting pattern, so the guard has to see its own source. */
    @Test
    public void theGuardCanSeeTheSourcesItChecks() throws IOException {
        List<String> names = new ArrayList<>();
        for (Path source : testSources()) names.add(source.getFileName().toString());

        assertTrue("the guard did not find its own source, so it is scanning the wrong tree",
                names.contains("SettingsContextGuardTest.java"));
        assertTrue("the guard did not find a class it is meant to hold",
                names.contains("ShareModelFilterTest.java"));
    }

    private static List<Path> testSources() throws IOException {
        File root = new File("src/test/java");
        if (!root.isDirectory()) root = new File("extensions/tiktok/src/test/java");
        assertTrue("the test source tree was not found from " + new File(".").getAbsolutePath(),
                root.isDirectory());

        try (Stream<Path> walk = Files.walk(root.toPath())) {
            return walk.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    /**
     * Whether any {@code @Before} method's own body calls {@code setContext}.
     *
     * <p>The body is found by matching braces from the method's opening one, over a source with
     * its comments blanked out. Reading from the annotation to the next one instead, which is
     * what this did first, let a match start at a {@code @Before} written in prose and run on
     * into an unrelated {@code @Test} body: SettingsPagesTest and LogBufferManagerExportTest both
     * passed the guard that way while installing their context inside each test method, which is
     * the shape the guard exists to catch.
     */
    static boolean installsContextFirst(String rawSource) {
        String source = withoutComments(rawSource);
        Matcher annotation = BEFORE_METHOD.matcher(source);
        while (annotation.find()) {
            int open = source.indexOf('{', annotation.end());
            if (open < 0) continue;
            int depth = 0;
            for (int index = open; index < source.length(); index++) {
                char character = source.charAt(index);
                if (character == '{') {
                    depth++;
                } else if (character == '}') {
                    depth--;
                    if (depth == 0) {
                        if (source.substring(open, index).contains("setContext(")) return true;
                        break;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The source with its comments blanked out, so a {@code @Before} or a {@code setContext} that
     * only appears in prose cannot answer for one that is not there. String literals are left
     * alone: none of the patterns here can be spelled inside one by accident, and this class's
     * own source, which does spell them, is skipped above.
     */
    static String withoutComments(String source) {
        StringBuilder result = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            if (source.startsWith("//", index)) {
                int end = source.indexOf('\n', index);
                if (end < 0) end = source.length();
                for (int blank = index; blank < end; blank++) result.append(' ');
                index = end;
            } else if (source.startsWith("/*", index)) {
                int end = source.indexOf("*/", index + 2);
                end = end < 0 ? source.length() : end + 2;
                for (int blank = index; blank < end; blank++) {
                    result.append(source.charAt(blank) == '\n' ? '\n' : ' ');
                }
                index = end;
            } else {
                result.append(source.charAt(index));
                index++;
            }
        }
        return result.toString();
    }

    @Test
    public void aBeforeInACommentDoesNotAnswerForOneThatIsNotThere() {
        // A comment naming the annotation, immediately above a method that does install a
        // context but is not a @Before. Reading from the comment's word to the block after it
        // answers yes for a class whose first case still runs against whatever the last one
        // left. SettingsPagesTest was this shape, in prose a few lines further off.
        String slipped = String.join("\n",
                "class Slipped {",
                "    @Before public void installControls() { flags(); }",
                "    // Unlike the @Before pattern, this one installs per case.",
                "    @Test public void aCase() {",
                "        Utils.setContext(activity);",
                "        Settings.DEFAULT_SPEED_ENABLED.get();",
                "    }",
                "}");
        assertTrue("the guard would not notice a setting here",
                TOUCHES_SETTINGS.matcher(slipped).find());
        assertTrue("a @Before that installs nothing was accepted",
                !installsContextFirst(slipped));

        String held = slipped.replace("public void installControls() { flags(); }",
                "public void installControls() { Utils.setContext(app()); }");
        assertTrue("a @Before that does install a context was rejected",
                installsContextFirst(held));
    }

    @Test
    public void aBaseSettingsReadCountsAsTouchingTheRegistry() {
        // Four test classes name BaseSettings rather than Settings, and the first version of
        // this pattern could not see them: \bSettings finds no boundary inside BaseSettings.
        assertTrue(TOUCHES_SETTINGS.matcher("BaseSettings.DEBUG.get()").find());
        assertTrue(TOUCHES_SETTINGS.matcher("Settings.DEBUG.get()").find());
    }

    /** Keeps the offender pattern honest: it has to match the shapes it is written for. */
    @Test
    public void theSettingPatternMatchesBothShapes() {
        for (String line : List.of(
                "Settings.SHARE_HIDDEN_ITEMS.save(\"\");",
                "new IntegerSetting(\"unit_test\", 3)",
                "new BooleanSetting(\"x\", false)")) {
            Matcher matcher = TOUCHES_SETTINGS.matcher(line);
            assertTrue("the guard would not notice: " + line, matcher.find());
        }

        assertTrue("a lower case field should not read as a setting",
                !TOUCHES_SETTINGS.matcher("Settings.getSomething()").find());
    }
}
