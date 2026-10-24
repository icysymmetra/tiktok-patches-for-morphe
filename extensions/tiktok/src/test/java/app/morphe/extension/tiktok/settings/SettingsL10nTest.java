package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;
import app.morphe.extension.tiktok.settings.preference.categories.CommentsPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.DebugPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.DownloadsPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.ExtensionPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.FeedFilterPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.FeedNavigationPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.InboxPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.PlaybackPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.ScreenTimePreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.SharePreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.SimSpoofPreferenceCategory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.Rule;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The settings screens in the phone's language. The English text in the code is the key,
 * the generated {@link L10nTranslations} carries each language, and this walks every screen
 * to make sure nothing is left in English once German is selected.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SettingsL10nTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    public static final class TestActivity extends PreferenceActivity {}

    /** The tables the generator wrote, which are what the app carries. */
    private static final Map<String, String> GERMAN = L10nTranslations.of("de");
    private static final Map<String, String> INDONESIAN = L10nTranslations.of("in");

    private static final String[] QUIET_INDEX_COPY = {
            "Make it yours.", "Your feed. Your rules.",
            "Your feed", "Conversations", "Saving and sharing", "Privacy and app", "Metra",
            "About", "Your settings", "About Metra", "Version, changes and licenses",
            "Budgets and reminders",
            "Active", "Paused", "Restart pending", "Metra is active", "Metra is paused",
            "Pause Metra's switches after TikTok restarts. Your settings stay saved. Changes made when you patched, like the app's name or icon, stay.",
            "Metra's switches are paused. Your settings stay saved. Changes made when you patched, like the app's name or icon, stay."
    };

    @Before
    public void clearStatusBeforeMountingSettings() throws Exception {
        setEveryStatus(false);
    }

    @After
    public void resetStatus() throws Exception {
        setEveryStatus(false);
    }

    /**
     * The reflection above only reaches preference titles and summaries. A toast is a string
     * literal at a call site, so nothing checked those until this: the runtime feedback was
     * English on every phone while the settings around it were translated.
     *
     * <p>The scan is over the whole file rather than line by line, and it looks at every
     * literal anywhere inside the call's brackets. A first attempt matched only a quote
     * straight after the opening bracket, and it went green while eleven toasts were still
     * English, because {@code showToastShort(ok ? "a" : "b")} and a call wrapped across two
     * lines both slipped past it.
     *
     * <p>The Feature Gate Lab is included now that its runtime states and failures are localized.
     * The shared extension module is not walked: it is TikTok-independent code, and this table
     * is TikTok's.
     */
    @Test public void theGeneratedTableIsTheOneInTheTables() throws Exception {
        // Every other check here reads L10nTranslations, which is generated. A value edited in a
        // table without rerunning scripts/gen-l10n.py was invisible to all of them, and so was a
        // generated file edited by hand, which its own header forbids.
        //
        // German is a tab table and Indonesian is the comma form Weblate hosts, so both readers
        // are exercised on every run rather than one of them being a claim in the README.
        for (String language : languages()) {
            java.util.Map<String, String> generated = L10nTranslations.of(language);
            java.util.Map<String, String> table = readTable(language);

            java.util.List<String> problems = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, String> row : table.entrySet()) {
                String was = generated.get(row.getKey());
                if (was == null) {
                    problems.add(language + " has a row the generated table does not: " + row.getKey());
                } else if (!was.equals(row.getValue())) {
                    problems.add(language + " differs for " + row.getKey()
                            + ": tsv has " + row.getValue() + ", generated has " + was);
                }
            }
            for (String key : generated.keySet()) {
                if (!table.containsKey(key)) {
                    problems.add(language + " generated a row the table does not have: " + key);
                }
            }
            assertEquals("the generated translations are not the ones in the " + language
                    + " table, so run scripts/gen-l10n.py: " + problems, 0, problems.size());
            assertFalse("the " + language + " table is empty", table.isEmpty());
        }
    }

    @Test public void theTableComparisonCanActuallyFail() throws Exception {
        // The comparison above only means something if a changed value is visible to it.
        java.util.Map<String, String> table = readTable("de");
        String key = table.keySet().iterator().next();
        java.util.Map<String, String> changed = new java.util.LinkedHashMap<>(table);
        changed.put(key, changed.get(key) + " x");
        assertNotEquals("a changed value read the same", table.get(key), changed.get(key));
        assertFalse("the tables compare equal after a change", table.equals(changed));
    }

    @Test public void everyQuietIndexLabelAndPauseBoundaryHasATranslation() throws Exception {
        for (String language : languages()) {
            Map<String, String> source = readTable(language);
            Map<String, String> generated = L10nTranslations.of(language);
            for (String key : QUIET_INDEX_COPY) {
                assertTrue(language + " has no source translation for " + key, source.containsKey(key));
                assertFalse(language + " has a blank translation for " + key, source.get(key).trim().isEmpty());
                assertEquals(language + " did not generate " + key, source.get(key), generated.get(key));
            }
            for (String retired : new String[]{
                    "From the next start TikTok runs as if it weren't patched, so you can tell whether a problem comes from Metra. Your settings stay as they are.",
                    "TikTok runs as if it weren't patched. Your settings stay as they are."}) {
                assertFalse(language + " still carries the claim that Pause restores the original app",
                        source.containsKey(retired));
                assertFalse(language + " still bundles the claim that Pause restores the original app",
                        generated.containsKey(retired));
            }
        }
    }

    /**
     * One language table, in whichever form it is kept.
     *
     * <p>Tab separated, one key then the translation with # for a comment, or the comma form
     * Weblate hosts: a source,target header and one row per entry.
     */
    private static java.util.Map<String, String> readTable(String language) throws Exception {
        java.io.File file = tableFile(language, ".tsv");
        if (file == null) file = tableFile(language, ".csv");
        assertNotNull("no .tsv or .csv table for " + language, file);
        String body = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        // A spreadsheet round trip leaves a byte order mark, which the generator now skips.
        if (body.startsWith("\ufeff")) body = body.substring(1);
        return file.getName().endsWith(".csv")
                ? readCsvRows(language, body) : readTsvRows(language, body);
    }

    private static java.io.File l10nDirectory() {
        java.io.File directory = new java.io.File("src/main/l10n");
        if (!directory.isDirectory()) directory = new java.io.File("extensions/tiktok/src/main/l10n");
        assertTrue("could not find the l10n tables", directory.isDirectory());
        return directory;
    }

    /** Every language with a table, in either form. Named so a new one is covered on sight. */
    private static java.util.List<String> languages() {
        java.util.List<String> found = new java.util.ArrayList<>();
        for (String name : java.util.Objects.requireNonNull(l10nDirectory().list())) {
            if (!name.endsWith(".tsv") && !name.endsWith(".csv")) continue;
            // Lower-cased, because that is what the generator does. A pt-rBR table, which is the
            // spelling L10n builds for a regional tag, otherwise asks for a language the
            // generated class has never heard of and gets null back.
            String language = name.substring(0, name.length() - 4)
                    .toLowerCase(java.util.Locale.ROOT);
            if (!ENGLISH_BASE.equals(language)) found.add(language);
        }
        java.util.Collections.sort(found);
        assertTrue("no language tables at all", found.size() >= 2);
        return found;
    }

    private static java.io.File tableFile(String language, String extension) {
        java.io.File file = new java.io.File(l10nDirectory(), language + extension);
        return file.isFile() ? file : null;
    }

    /** The keys are English text, so the base is a list of source strings, not a translation. */
    private static final String ENGLISH_BASE = "en";

    /** A newline is the two characters \n in a table, the same as the generator reads it. */
    private static String unescapeTableNewlines(String text) {
        return text.replace("\\n", "\n");
    }

    private static java.util.Map<String, String> readTsvRows(String language, String body) {
        java.util.Map<String, String> rows = new java.util.LinkedHashMap<>();
        for (String line : body.split("\\n")) {
            String text = line.replace("\r", "");
            if (text.isEmpty() || text.startsWith("#")) continue;
            int tab = text.indexOf('\t');
            assertTrue("a row with no tab in " + language + ".tsv: " + text, tab > 0);
            rows.put(unescapeTableNewlines(text.substring(0, tab)),
                    unescapeTableNewlines(text.substring(tab + 1)));
        }
        return rows;
    }

    /** Enough of RFC 4180 for these tables: quoted fields, doubled quotes inside them. */
    private static java.util.Map<String, String> readCsvRows(String language, String body) {
        java.util.List<java.util.List<String>> records = new java.util.ArrayList<>();
        java.util.List<String> record = new java.util.ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean atFieldStart = true;
        for (int index = 0; index < body.length(); index++) {
            char character = body.charAt(index);
            if (quoted) {
                if (character != '"') {
                    field.append(character);
                } else if (index + 1 < body.length() && body.charAt(index + 1) == '"') {
                    field.append('"');
                    index++;
                } else {
                    quoted = false;
                }
                continue;
            }
            if (character == '"' && atFieldStart) {
                quoted = true;
                atFieldStart = false;
            } else if (character == ',') {
                record.add(field.toString());
                field.setLength(0);
                atFieldStart = true;
            } else if (character == '\n' || character == '\r') {
                // A bare CR ends a record, the same as Python's csv. CRLF is one terminator,
                // so the LF after a CR is taken with it rather than ending a second, empty one.
                if (character == '\r' && index + 1 < body.length()
                        && body.charAt(index + 1) == '\n') {
                    index++;
                }
                record.add(field.toString());
                field.setLength(0);
                records.add(record);
                record = new java.util.ArrayList<>();
                atFieldStart = true;
            } else {
                field.append(character);
                atFieldStart = false;
            }
        }
        if (field.length() > 0 || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }

        assertFalse("the " + language + " table has no header", records.isEmpty());
        java.util.List<String> header = records.get(0);
        assertTrue("the " + language + " header has to start source,target, not " + header,
                header.size() >= 2
                        && "source".equalsIgnoreCase(header.get(0).trim())
                        && "target".equalsIgnoreCase(header.get(1).trim()));

        java.util.Map<String, String> rows = new java.util.LinkedHashMap<>();
        for (int index = 1; index < records.size(); index++) {
            java.util.List<String> line = records.get(index);
            if (line.isEmpty() || line.get(0).isEmpty() || line.get(0).startsWith("#")) continue;
            assertTrue("a row with no target column in " + language + ".csv: " + line,
                    line.size() >= 2);
            rows.put(unescapeTableNewlines(line.get(0)), unescapeTableNewlines(line.get(1)));
        }
        return rows;
    }

    @Test public void theCsvReaderEndsARowWhereThePythonOneDoes() {
        // No shipped table carries a bare CR, so nothing here would have caught the reader
        // swallowing it and running two rows together. These are the shapes Python's csv module
        // produces, checked against it.
        java.util.Map<String, String> unixEndings =
                readCsvRows("probe", "source,target\na,1\nb,2\n");
        assertEquals(2, unixEndings.size());
        assertEquals("1", unixEndings.get("a"));

        java.util.Map<String, String> windowsEndings =
                readCsvRows("probe", "source,target\r\na,1\r\nb,2\r\n");
        assertEquals("a CRLF was read as two row endings", 2, windowsEndings.size());
        assertEquals("1", windowsEndings.get("a"));

        java.util.Map<String, String> classicMacEndings =
                readCsvRows("probe", "source,target\ra,1\rb,2\r");
        assertEquals("a bare CR did not end a row", 2, classicMacEndings.size());
        assertEquals("1", classicMacEndings.get("a"));

        java.util.Map<String, String> quoted =
                readCsvRows("probe", "source,target\n\"a,b\",\"says \"\"hi\"\"\"\n");
        assertEquals(1, quoted.size());
        assertEquals("says \"hi\"", quoted.get("a,b"));

        java.util.Map<String, String> looseQuote = readCsvRows("probe", "source,target\na,b\"c\n");
        assertEquals("a quote inside an unquoted field opened one", "b\"c",
                looseQuote.get("a"));
    }

    @Test public void theEnglishBaseIsExactlyTheStringsTheTablesCarry() throws Exception {
        // Weblate translates from a monolingual base rather than from a language table. It is
        // generated, so it goes stale the same way L10nTranslations does if nobody reruns the
        // script, and nothing else here would notice. Both directions matter: a string added to
        // a table has to reach it, and a string deleted from every table has to leave it, or
        // translators keep being asked for text nothing shows any more.
        java.util.Map<String, String> base = readTable(ENGLISH_BASE);
        java.util.Set<String> carried = new java.util.TreeSet<>();
        for (String language : languages()) {
            for (String key : readTable(language).keySet()) {
                // A |category row is a translation of a key the base lists, not a source string.
                if (pluralBase(key).equals(key)) carried.add(key);
            }
        }

        java.util.Set<String> missing = new java.util.TreeSet<>(carried);
        missing.removeAll(base.keySet());
        assertEquals(ENGLISH_BASE + ".csv is missing source strings, so run scripts/gen-l10n.py: "
                + missing, 0, missing.size());

        java.util.Set<String> extra = new java.util.TreeSet<>(base.keySet());
        extra.removeAll(carried);
        assertEquals(ENGLISH_BASE + ".csv still lists strings no table carries, so run "
                + "scripts/gen-l10n.py: " + extra, 0, extra.size());

        for (java.util.Map.Entry<String, String> row : base.entrySet()) {
            assertEquals("the base translates a string instead of repeating it",
                    row.getKey(), row.getValue());
        }
    }

    /**
     * The Lab's own words stay in the Lab's technical block. A gate is the thing, an override
     * is what the reader sets, a rule is a saved override. "Flag" and "getter" reached the
     * settings home row, two warnings and the override note before this held them out.
     */
    @Test public void labVocabularyStaysOutOfTheSourceStrings() throws Exception {
        java.util.regex.Pattern jargon = java.util.regex.Pattern.compile(
                "(?i)\\bgetters?\\b|\\bgate flags?\\b|\\bflags\\b|\\boverride boundary\\b"
                        + "|\\bconfiguration object\\b|\\btype-checked\\b");
        List<String> offenders = new ArrayList<>();
        for (String key : readTable(ENGLISH_BASE).keySet()) {
            if (jargon.matcher(key).find()) offenders.add(key);
        }
        assertEquals("Lab vocabulary in a translated string:\n" + String.join("\n", offenders),
                0, offenders.size());
    }

    /**
     * A limit the reader is told about is formatted in from the constant that enforces it, so
     * no key carries the number itself. The example list on the speed row is the one place
     * numbers are words rather than a limit.
     */
    @Test public void noKeyCarriesALimitThatLivesInACodeConstant() throws Exception {
        java.util.regex.Pattern limit = java.util.regex.Pattern.compile(
                "\\bfour seconds\\b|\\b4,?096\\b|\\b10,?000\\b|\\b256 KB\\b|\\b64 KB\\b|\\b1-1000\\b"
                        + "|\\b12 to 48\\b|\\b0\\.5 to 3\\b|\\b8 (?:comma-separated )?speeds\\b");
        List<String> offenders = new ArrayList<>();
        for (String key : readTable(ENGLISH_BASE).keySet()) {
            if (limit.matcher(key).find()) offenders.add(key);
        }
        assertEquals("keys that type a limit the code holds in a constant:\n"
                + String.join("\n", offenders), 0, offenders.size());
    }

    /**
     * A failure says what happened and what to do next, as two sentences with their stops, and
     * says "couldn't" the way the rest of the screens do. Twenty keys opened "Could not", four
     * of them with no stop at all, and eight stopped at the failure with nothing to do about it.
     */
    @Test public void everyFailureMessageSaysWhatHappenedAndWhatToDoNext() throws Exception {
        // A heading over an error row: its next step is the summary under it, not a sentence of
        // its own, so it is the one failure allowed to be a single line.
        Set<String> headings = new LinkedHashSet<>(List.of("Settings couldn't open"));
        java.util.regex.Pattern failure = java.util.regex.Pattern.compile("(?i)\\bcould ?n['’]?o?t\\b");
        java.util.regex.Pattern uncontracted = java.util.regex.Pattern.compile("(?i)\\bcould not\\b");
        java.util.regex.Pattern boundary = java.util.regex.Pattern.compile("[.!?][\"”']?\\s+[A-Za-z0-9\"“%]");
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (String key : readTable(ENGLISH_BASE).keySet()) {
            if (!failure.matcher(key).find() || headings.contains(key)) continue;
            checked++;
            if (uncontracted.matcher(key).find()) offenders.add("says could not: " + key);
            if (!key.endsWith(".")) offenders.add("no full stop: " + key);
            if (!boundary.matcher(key).find()) offenders.add("no next step or outcome: " + key);
        }
        assertTrue("the scan found too few failure messages to mean anything: " + checked, checked > 20);
        assertEquals("failure messages that stop short:\n" + String.join("\n", offenders),
                0, offenders.size());
    }

    @Test public void everyTranslationKeepsTheShapeOfItsKey() {
        // Defects the key-set checks cannot see. A placeholder that changed, was dropped or was
        // invented; a sentence that lost or gained its terminator; a quote pair that does not
        // close. The first pass of this check only looked at numbered placeholders in the key,
        // so a bare "%s" and an invented placeholder both walked past it.
        // Every table, not the two that happened to exist when this was written: Spanish and
        // Brazilian Portuguese were added later and went unchecked, and five Spanish rows ended
        // in a full stop their key does not have.
        java.util.List<String> problems = new java.util.ArrayList<>();
        for (String language : L10nTranslations.LANGUAGES) {
            for (java.util.Map.Entry<String, String> row
                    : L10nTranslations.of(language).entrySet()) {
                // A |category row is one language's extra plural form of the other form, so
                // it is held to that form's shape.
                String key = pluralBase(row.getKey());
                String value = row.getValue();

                // Both directions and both spellings, so a dropped, changed or added
                // placeholder is all the same finding. When every placeholder is numbered,
                // a translation may use them in any order: the set must match, not the order.
                java.util.List<String> wanted = placeholders(key);
                java.util.List<String> given = placeholders(value);
                boolean allNumbered = !wanted.isEmpty()
                        && wanted.stream().allMatch(p -> p.contains("$"));
                boolean mismatch = allNumbered
                        ? !sorted(wanted).equals(sorted(given))
                        : !wanted.equals(given);
                if (mismatch) {
                    problems.add(language + " placeholders " + wanted + " became " + given
                            + " in: " + key);
                }

                // A full stop the key does not have is usually a sentence the translator ended
                // differently. Sometimes it is the abbreviation the language requires: Spanish
                // and Portuguese write maximum as "máx.", and the period belongs to the word.
                // Reading it as a terminator and taking it off produced "mín./máx", one
                // abbreviation with its period and the identical one beside it without.
                boolean abbreviated = terminator(key) == ' ' && terminator(value) == '.'
                        && endsInAnAbbreviation(value);
                if (terminator(key) != terminator(value) && !abbreviated) {
                    problems.add(language + " ends the sentence with '" + terminator(value)
                            + "' where the key ends with '" + terminator(key) + "' in: " + key);
                }

                // German opens low and closes high. Everything else here uses the English pair.
                char open = "de".equals(language) ? '\u201e' : '\u201c';
                char close = "de".equals(language) ? '\u201c' : '\u201d';
                long opened = value.chars().filter(c -> c == open).count();
                long closed = value.chars().filter(c -> c == close).count();
                if (opened != closed) {
                    problems.add(language + " opened " + opened + " quotes and closed " + closed
                            + " in: " + key);
                }
            }
        }
        assertEquals("translations that do not keep the shape of their key: " + problems,
                0, problems.size());
    }

    @Test public void theTranslationShapeCheckCanActuallyFail() {
        // A scan with nothing to find is a scan that proves nothing. These are the four shapes
        // the check exists for, put in front of it on purpose.
        assertNotEquals("a dropped placeholder", placeholders("across %1$d surfaces"),
                placeholders("auf %d Oberflachen"));
        assertEquals("reordered numbered placeholders are valid",
                sorted(placeholders("%1$s and %2$s")),
                sorted(placeholders("%2$s and %1$s")));
        assertEquals("String.format fills numbered positions regardless of order",
                "B then A",
                String.format("%2$s then %1$s", "A", "B"));
        assertNotEquals("a dropped numbered placeholder",
                sorted(placeholders("%1$s and %2$s")),
                sorted(placeholders("%1$s only")));
        assertNotEquals("an invented placeholder", placeholders("no placeholder here"),
                placeholders("keiner %1$s hier"));
        assertNotEquals("a dropped full stop", terminator("Hide the caption."),
                terminator("Beschreibung ausblenden"));
        assertNotEquals("a changed terminator", terminator("Really?"), terminator("Wirklich."));
        // And the one full stop that is not a terminator, against the sentence it must not
        // excuse. The relaxation reads the length of the last word, so a sentence that happens
        // to end in a short word is the case worth pinning.
        assertTrue("an abbreviation was read as a sentence ending",
                endsInAnAbbreviation("Comentarios mín./máx."));
        assertFalse("a real sentence ending was excused as an abbreviation",
                endsInAnAbbreviation("No está en la caché actual."));
        assertFalse("a value with no full stop was called an abbreviation",
                endsInAnAbbreviation("Komentar min/maks"));
    }

    /** Every placeholder in order, numbered or bare, so a change of either kind shows up. */
    private static java.util.List<String> placeholders(String text) {
        java.util.List<String> found = new java.util.ArrayList<>();
        var match = java.util.regex.Pattern.compile("%(?:\\d+\\$)?[a-zA-Z]").matcher(text);
        while (match.find()) found.add(match.group());
        return found;
    }

    private static java.util.List<String> sorted(java.util.List<String> list) {
        java.util.List<String> copy = new java.util.ArrayList<>(list);
        java.util.Collections.sort(copy);
        return copy;
    }

    /**
     * Whether a trailing full stop belongs to an abbreviation rather than to a sentence.
     *
     * <p>Read from the length of the last word: "máx." is three letters and an abbreviation,
     * "the current cache." is not. Four letters is the ceiling, which covers every shortened
     * form in these tables and leaves the shortest real sentence ending alone.
     */
    private static boolean endsInAnAbbreviation(String text) {
        String trimmed = text.trim();
        if (!trimmed.endsWith(".")) return false;
        int at = trimmed.length() - 1;
        int letters = 0;
        while (at > 0 && Character.isLetter(trimmed.charAt(at - 1))) {
            at--;
            letters++;
        }
        return letters > 0 && letters <= 4;
    }

    /** The character a string ends a sentence with, or a space when it ends with none. */
    private static char terminator(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return ' ';
        char last = trimmed.charAt(trimmed.length() - 1);
        return ".!?:\u2026".indexOf(last) >= 0 ? last : ' ';
    }

    @Test public void everyRuntimeToastGoesThroughTheTable() throws Exception {
        java.io.File root = new java.io.File("src/main/java/app/morphe/extension/tiktok");
        if (!root.isDirectory()) root = new java.io.File(
                "extensions/tiktok/src/main/java/app/morphe/extension/tiktok");
        assertTrue("could not find the source tree from " + new java.io.File(".").getAbsolutePath(),
                root.isDirectory());

        java.util.List<String> offenders = new java.util.ArrayList<>();
        java.nio.file.Path base = root.toPath();
        int scanned = 0;
        try (java.util.stream.Stream<java.nio.file.Path> files =
                     java.nio.file.Files.walk(base)) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toList())) {
                String text = new String(java.nio.file.Files.readAllBytes(file),
                        java.nio.charset.StandardCharsets.UTF_8);
                scanned++;
                for (String offence : rawToastsIn(text)) {
                    offenders.add(base.relativize(file) + ":" + offence);
                }
            }
        }

        assertTrue("the scan found no files to read", scanned > 20);
        assertTrue("this text reaches the reader, so it belongs in the translation table. "
                        + "Wrap it in L10n.t, or L10n.f when it carries a value:\n"
                        + String.join("\n", offenders),
                offenders.isEmpty());
    }

    /**
     * A toast of one sentence takes no full stop; a toast of several ends every sentence with
     * one. "Diagnostic data put back." next to "Seen video history put back" was the same pair
     * of toasts punctuated two ways, and nothing held them to one rule.
     */
    @Test public void everyToastFollowsThePunctuationRule() throws Exception {
        java.io.File root = new java.io.File("src/main/java/app/morphe/extension/tiktok");
        if (!root.isDirectory()) root = new java.io.File(
                "extensions/tiktok/src/main/java/app/morphe/extension/tiktok");
        assertTrue(root.isDirectory());
        java.util.List<String> offenders = new java.util.ArrayList<>();
        int toasts = 0;
        java.nio.file.Path base = root.toPath();
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(base)) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toList())) {
                if (file.getFileName().toString().startsWith("L10nTranslations")) continue;
                String text = new String(java.nio.file.Files.readAllBytes(file),
                        java.nio.charset.StandardCharsets.UTF_8);
                for (String message : toastLiteralsIn(text)) {
                    toasts++;
                    String fault = punctuationFault(message);
                    if (fault != null) offenders.add(base.relativize(file) + ": " + fault + "  \"" + message + "\"");
                }
            }
        }
        assertTrue("the scan found too few toasts to mean anything: " + toasts, toasts > 40);
        assertTrue("one sentence takes no full stop, several take one each:\n"
                + String.join("\n", offenders), offenders.isEmpty());
    }

    /** The scan sees every shape a toast call takes, and the rule can fail. */
    @Test public void theToastPunctuationRuleCanActuallyFail() {
        String source = "class T {\n"
                + "  void a() { Utils.showToastShort(L10n.t(getContext(), \"Field edits were not saved.\")); }\n"
                + "  void b() { postToast(refused ? L10n.t(context, \"Refused\")\n"
                + "      : L10n.t(Utils.getContext(), \"That file is too large.\")); }\n"
                + "  void c() { Utils.showToastLong(L10n.f(\"Saved %1$s\", name)); }\n"
                + "  // Utils.showToastShort(L10n.t(context, \"Inside a comment.\"));\n"
                + "}\n";
        assertEquals(java.util.Arrays.asList("Field edits were not saved.", "Refused",
                "That file is too large.", "Saved %1$s"), toastLiteralsIn(source));

        assertNotNull(punctuationFault("Diagnostic data put back."));
        assertNotNull(punctuationFault("Done!"));
        assertNotNull(punctuationFault("Copied 3 lines. 12 were skipped"));
        assertNotNull(punctuationFault("Saved. nothing else changed"));
        assertNull(punctuationFault("Seen videos put back"));
        assertNull(punctuationFault("Couldn't undo the clear. Try again."));
        assertNull(punctuationFault("Enter a whole number, or one like 20K, 1.5M or 2B"));
    }

    /**
     * Every literal handed to L10n.t or L10n.f directly inside a toast call.
     *
     * <p>The context argument may be a name, a call such as {@code getContext()} or a
     * qualified call such as {@code Utils.getContext()}: the first version of this scan took
     * a bare name only, and three Lab toasts written with a call walked past it carrying full
     * stops. Every literal in the call is taken, not the first, because a toast that chooses
     * between two messages with a ternary has two.
     */
    private static java.util.List<String> toastLiteralsIn(String text) {
        byte[] kind = classify(text);
        java.util.List<String> found = new java.util.ArrayList<>();
        // The settings banners (showNotice, showUndo) and the feed's undo banner are toasts in
        // every way that matters to a reader, so they answer to the same rule.
        java.util.regex.Matcher call = java.util.regex.Pattern
                .compile("(?:\\b\\w*[Tt]oast\\w*|Toast\\s*\\.\\s*makeText|\\bshowNotice|\\bshowUndo(?:Banner)?)\\s*\\(")
                .matcher(text);
        while (call.find()) {
            int open = call.end() - 1;
            if (kind[call.start()] != CODE) continue;
            int close = closingBracket(text, kind, open);
            if (close < 0) continue;
            // Every L10n call inside the toast's brackets, and every literal inside each of
            // those calls: t and f carry one, quantity carries the one and the other form.
            java.util.regex.Matcher lookup = java.util.regex.Pattern
                    .compile("L10n\\s*\\.\\s*(?:t|f|quantity)\\s*\\(").matcher(text);
            lookup.region(open, close);
            while (lookup.find()) {
                int lookupOpen = lookup.end() - 1;
                int lookupClose = closingBracket(text, kind, lookupOpen);
                if (lookupClose < 0 || lookupClose > close) continue;
                // arguments, not literalsIn: a message written across lines with a plus is one
                // message, and its halves would each fail the rule on their own.
                found.addAll(arguments(text, kind, lookupOpen, lookupClose));
            }
        }
        return found;
    }

    /** Null when the message follows the rule, otherwise what it gets wrong. */
    static String punctuationFault(String message) {
        String trimmed = message.trim();
        if (trimmed.isEmpty()) return null;
        // A sentence boundary is a terminator followed by a space and the start of the next
        // sentence, whatever it starts with: a capital, a quote, a placeholder, a number or a
        // word left in lower case by mistake.
        boolean several = trimmed.matches("(?s).*[.!?][\"”']?\\s+[A-Za-z0-9\"“%].*");
        boolean ends = trimmed.matches("(?s).*[.!?][\"”']?$");
        if (!several && ends) return "one sentence ends with a stop";
        if (several && !ends) return "several sentences and the last has no stop";
        return null;
    }

    /**
     * Anything that puts words on the screen. Any method with "toast" in its name,
     * however it is spelled, plus the platform call those wrap and the undo banner,
     * which is a toast in every way that matters to a reader.
     *
     * <p>A content description counts too. Nothing shows it on screen, but it is the only
     * words a reader using TalkBack gets for a button drawn as a glyph, and three of the
     * player's overlay buttons reached that reader in English on a German phone.
     *
     * <p>Named this widely on purpose. The first version knew two method names, and a
     * file with a private toast helper of its own walked past it carrying five English
     * messages.
     */
    private static final String SHOWS_TEXT = "(?:\\b\\w*[Tt]oast\\w*|Toast\\s*\\.\\s*makeText|showUndoBanner"
            + "|showActionBanner|showNoticeBanner"
            + "|setContentDescription|setStateDescription|SettingsUi\\s*\\.\\s*text)\\s*\\(";

    /** Marks every character of a source file as code, inside a literal, or inside a comment. */
    private static final byte CODE = 0, LITERAL = 1, COMMENT = 2;

    private static byte[] classify(String text) {
        byte[] kind = new byte[text.length()];
        int at = 0;
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == '/' && at + 1 < text.length() && text.charAt(at + 1) == '/') {
                while (at < text.length() && text.charAt(at) != '\n') kind[at++] = COMMENT;
            } else if (c == '/' && at + 1 < text.length() && text.charAt(at + 1) == '*') {
                int close = text.indexOf("*/", at + 2);
                int stop = close < 0 ? text.length() : close + 2;
                while (at < stop) kind[at++] = COMMENT;
            } else if (c == '"' || c == '\'') {
                kind[at++] = LITERAL;
                while (at < text.length()) {
                    char inside = text.charAt(at);
                    kind[at++] = LITERAL;
                    if (inside == '\\') { if (at < text.length()) kind[at++] = LITERAL; continue; }
                    if (inside == c) break;
                }
            } else {
                kind[at++] = CODE;
            }
        }
        return kind;
    }

    /**
     * Every string shown by a toast in this file that does not go through the table, as
     * "line  text". A literal counts as translated when the brackets it sits directly inside
     * belong to {@code L10n.t} or {@code L10n.f}; anything else, including a ternary and a
     * concatenation, is the reader seeing English.
     */
    private static java.util.List<String> rawToastsIn(String text) {
        byte[] kind = classify(text);
        java.util.List<String> found = new java.util.ArrayList<>();
        java.util.regex.Matcher call = java.util.regex.Pattern
                .compile(SHOWS_TEXT).matcher(text);
        while (call.find()) {
            int open = call.end() - 1;
            if (kind[call.start()] != CODE) continue;
            int close = closingBracket(text, kind, open);
            if (close < 0) continue;
            for (int at = open + 1; at < close; at++) {
                if (kind[at] != LITERAL || text.charAt(at) != '"') continue;
                int literalEnd = at + 1;
                while (literalEnd < close && kind[literalEnd] == LITERAL) literalEnd++;
                // An empty literal says nothing, so there is nothing to translate. Eight of
                // these are the getTitle() == null ? "" : ... idiom on the hand built dialogs.
                boolean empty = literalEnd == at + 2;
                if (!empty && !throughTheTable(text, kind, at)) {
                    found.add(lineOf(text, at) + "  "
                            + text.substring(at, Math.min(literalEnd, at + 70)));
                }
                at = literalEnd;
            }
        }
        return found;
    }

    private static int closingBracket(String text, byte[] kind, int open) {
        int depth = 0;
        for (int at = open; at < text.length(); at++) {
            if (kind[at] != CODE) continue;
            if (text.charAt(at) == '(') depth++;
            else if (text.charAt(at) == ')' && --depth == 0) return at;
        }
        return -1;
    }

    /** The call whose brackets this literal sits directly inside, if it is one of L10n's. */
    private static boolean throughTheTable(String text, byte[] kind, int literalAt) {
        int depth = 0;
        int owner = -1;
        for (int at = literalAt - 1; at >= 0; at--) {
            if (kind[at] != CODE) continue;
            char c = text.charAt(at);
            if (c == ')') depth++;
            else if (c == '(') {
                if (depth == 0) { owner = at; break; }
                depth--;
            }
        }
        if (owner < 0) return false;
        int end = owner;
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
        int start = end;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (Character.isJavaIdentifierPart(c) || c == '.') start--;
            else break;
        }
        String name = text.substring(start, end);
        for (String lookup : new String[]{"L10n.t", "L10n.f", "L10n.quantity"}) {
            if (name.equals(lookup) || name.endsWith("." + lookup)) return true;
        }
        return false;
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int at = 0; at < index; at++) if (text.charAt(at) == '\n') line++;
        return line;
    }

    @Test
    public void everyLanguageTheBundleCarriesHasATable() {
        assertNotNull("German", L10nTranslations.of("de"));
        for (String language : L10nTranslations.LANGUAGES) {
            assertNotNull(language, L10nTranslations.of(language));
        }
        // A language nobody translated into falls back rather than failing.
        assertNull(L10nTranslations.of("xx"));
    }

    @Test
    public void aCountrySpecificLocaleFallsBackToItsLanguage() {
        // The tables are named by language; Austrian German gets the German table.
        assertEquals("Beschreibung ausblenden",
                L10n.t(contextFor("de", "AT"), "Hide the caption"));
        assertEquals("Beschreibung ausblenden",
                L10n.t(contextFor("de", ""), "Hide the caption"));
        // Indonesian arrives as either ISO code depending on the runtime, and both find it.
        assertEquals("Sembunyikan keterangan", L10n.t(contextFor("in", "ID"), "Hide the caption"));
        assertEquals("Sembunyikan keterangan", L10n.t(contextFor("id", "ID"), "Hide the caption"));
        // A language with no table keeps the English.
        assertEquals("Hide the caption", L10n.t(contextFor("fr", "FR"), "Hide the caption"));
    }

    @Test
    public void simplifiedChineseUsesItsScriptTableAndTraditionalChineseKeepsEnglish() {
        Context simplified = contextFor(Locale.forLanguageTag("zh-Hans-CN"));
        assertEquals(List.of("zh-hans-rcn", "zh-hans"), L10n.tags(simplified));
        assertEquals("隐藏文案", L10n.t(simplified, "Hide the caption"));
        assertEquals("信息流过滤器", L10n.t(simplified, "Feed filter"));
        assertEquals("信息流界面", L10n.t(simplified, "Feed screen"));
        assertEquals("信息流标签页", L10n.t(simplified, "Feed tabs"));
        assertEquals("你的信息流", L10n.t(simplified, "Your feed"));
        assertEquals("zh-hans", L10n.tableTag(simplified));
        assertEquals("zh-Hans", L10n.shownLocale(simplified).toLanguageTag());

        Context mainland = contextFor(Locale.forLanguageTag("zh-CN"));
        assertEquals(List.of("zh-hans-rcn", "zh-hans"), L10n.tags(mainland));
        assertEquals("隐藏文案", L10n.t(mainland, "Hide the caption"));

        Context traditional = contextFor(Locale.forLanguageTag("zh-Hant-TW"));
        assertEquals(List.of("zh-hant-rtw", "zh-hant"), L10n.tags(traditional));
        assertEquals("Hide the caption", L10n.t(traditional, "Hide the caption"));
        assertNull(L10n.tableTag(traditional));

        Context taiwan = contextFor(Locale.forLanguageTag("zh-TW"));
        assertEquals(List.of("zh-hant-rtw", "zh-hant"), L10n.tags(taiwan));
        assertEquals("Hide the caption", L10n.t(taiwan, "Hide the caption"));
    }

    @Test
    public void asecondLanguageAnswersWhenTheFirstHasNoTable() {
        // A phone set to French and then German shows German, because Android resolves a
        // string against the whole language list rather than only the first entry.
        Configuration configuration = new Configuration(
                RuntimeEnvironment.getApplication().getResources().getConfiguration());
        configuration.setLocales(new LocaleList(new Locale("fr", "FR"), new Locale("de", "DE")));
        Context context = RuntimeEnvironment.getApplication().createConfigurationContext(configuration);
        assertEquals(List.of("fr-rfr", "fr", "de-rde", "de"), L10n.tags(context));
        assertEquals("Beschreibung ausblenden", L10n.t(context, "Hide the caption"));
        // The order is the phone's: a language with a table earlier in the list wins.
        configuration.setLocales(new LocaleList(new Locale("in", "ID"), new Locale("de", "DE")));
        assertEquals("Sembunyikan keterangan", L10n.t(
                RuntimeEnvironment.getApplication().createConfigurationContext(configuration),
                "Hide the caption"));
    }

    @Test
    public void englishAheadOfAnotherLanguageKeepsTheSettingsEnglish() {
        // English is the text itself. A phone set to English and then German runs TikTok in
        // English, and the settings used to skip past it to the German table.
        Configuration configuration = new Configuration(
                RuntimeEnvironment.getApplication().getResources().getConfiguration());
        configuration.setLocales(new LocaleList(new Locale("en", "US"), new Locale("de", "DE")));
        assertEquals("Hide the caption", L10n.t(
                RuntimeEnvironment.getApplication().createConfigurationContext(configuration),
                "Hide the caption"));
        // A language with no table ahead of English falls through to English and stops there.
        configuration.setLocales(new LocaleList(new Locale("fr", "FR"), new Locale("en", "GB"),
                new Locale("de", "DE")));
        assertEquals("Hide the caption", L10n.t(
                RuntimeEnvironment.getApplication().createConfigurationContext(configuration),
                "Hide the caption"));
    }

    /** A context whose resources report one locale, which is what the lookup reads. */
    private static Context contextFor(String language, String country) {
        return contextFor(new Locale(language, country));
    }

    private static Context contextFor(Locale locale) {
        Configuration configuration = new Configuration(
                RuntimeEnvironment.getApplication().getResources().getConfiguration());
        configuration.setLocale(locale);
        return RuntimeEnvironment.getApplication().createConfigurationContext(configuration);
    }

    @Test
    public void englishStaysEnglishAndUnknownTextPassesThrough() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        assertEquals("Hide the caption", L10n.t("Hide the caption"));
        assertEquals("Not a settings string", L10n.t("Not a settings string"));
        assertEquals("Current: 3 videos", L10n.f("Current: %1$s", L10n.quantity(Utils.getContext(), 3, "%1$s video", "%1$s videos", "3")));
    }

    @Test
    public void theMusicLineSummaryDescribesTheLabelInEveryLocale() throws Exception {
        String summary = "Hide the track name beside the caption.";
        String oldSummary = "Hide the spinning music cover and the track name beside the caption.";
        SettingsStatus.videoOverlaysEnabled = true;
        List<String> locales = new ArrayList<>(languages());
        locales.add(ENGLISH_BASE);
        for (String language : locales) {
            Map<String, String> source = readTable(language);
            String expected = source.get(summary);
            assertNotNull(language + " is missing the label summary", expected);
            assertFalse(language + " still promises to hide the disc", source.containsKey(oldSummary));
            if (!language.equals(ENGLISH_BASE)) {
                assertEquals(language, expected, L10nTranslations.of(language).get(summary));
                assertNotEquals(language + " fell back to English", summary, expected);
            }
            String[] tag = language.split("-r", 2);
            Context context = contextFor(tag[0], tag.length == 1 ? "" : tag[1].toUpperCase(Locale.ROOT));
            try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
                PreferenceScreen screen = controller.get().getPreferenceManager().createPreferenceScreen(context);
                new InterfacePreferenceCategory(context, screen);
                Preference music = screen.findPreference(Settings.HIDE_FEED_MUSIC.key);
                assertNotNull(language + " has no music-line row", music);
                assertEquals(language, expected, music.getSummary().toString());
            }
        }
    }

    @Test
    public void nativeDescriptionAndAuthorSizingHaveIndependentRowsInEveryLocale() throws Exception {
        String[] titles = {"Description text size", "Author text size"};
        String[] summaries = {
                "Use 0 for TikTok's size, or %1$d to %2$d. Sizes the description below the author's name and keeps Android's font scaling.",
                "Use 0 for TikTok's size, or %1$d to %2$d. Sizes the author's name and the post date next to it, and keeps Android's font scaling."};
        String[] keys = {Settings.FEED_DESCRIPTION_TEXT_SIZE.key, Settings.FEED_AUTHOR_TEXT_SIZE.key};
        SettingsStatus.feedTextSizeEnabled = true;
        List<String> locales = new ArrayList<>(languages());
        locales.add(ENGLISH_BASE);
        for (String language : locales) {
            Map<String, String> source = readTable(language);
            for (String key : new String[]{titles[0], titles[1], summaries[0], summaries[1]}) {
                assertNotNull(language + " is missing " + key, source.get(key));
                if (!language.equals(ENGLISH_BASE)) {
                    assertEquals(language, source.get(key), L10nTranslations.of(language).get(key));
                    assertNotEquals(language + " fell back to English", key, source.get(key));
                }
            }
            String[] tag = language.split("-r", 2);
            Context context = contextFor(tag[0], tag.length == 1 ? "" : tag[1].toUpperCase(Locale.ROOT));
            try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
                PreferenceScreen screen = controller.get().getPreferenceManager().createPreferenceScreen(context);
                new InterfacePreferenceCategory(context, screen);
                for (int i = 0; i < keys.length; i++) {
                    Preference row = screen.findPreference(keys[i]);
                    assertNotNull(language + " has no " + titles[i], row);
                    assertEquals(language, L10n.t(context, titles[i]), row.getTitle().toString());
                    assertTrue(language, row.getSummary().toString().startsWith(L10n.f(context, summaries[i],
                            app.morphe.extension.tiktok.feed.FeedTextSize.MIN_TEXT_SIZE,
                            app.morphe.extension.tiktok.feed.FeedTextSize.MAX_TEXT_SIZE)));
                }
                assertNull("Native sizing must not require or enable spoken subtitles",
                        screen.findPreference(Settings.CAPTION_TEXT_SIZE.key));
            }
        }
    }

    @Test
    @Config(sdk = 28, qualifiers = "de")
    public void germanShowsOnARealPreferenceUnderTheGermanLocale() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            TogglePreference toggle = new TogglePreference(activity, "Hide the caption",
                    "Hide the description under the creator's name on the feed.", Settings.HIDE_FEED_CAPTION);
            assertEquals("Beschreibung ausblenden", toggle.getTitle().toString());
            assertEquals("Die Beschreibung unter dem Namen des Creators im Feed ausblenden.",
                    toggle.getSummary().toString());
            assertEquals("Aktuell: 3 Videos", L10n.f(activity, "Current: %1$s", L10n.quantity(activity, 3, "%1$s video", "%1$s videos", "3")));
        }
    }

    @Test
    @Config(sdk = 28, qualifiers = "in-rID")
    public void indonesianShowsOnARealPreferenceUnderTheIndonesianLocale() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            TogglePreference toggle = new TogglePreference(activity, "Hide the caption",
                    "Hide the description under the creator's name on the feed.", Settings.HIDE_FEED_CAPTION);
            assertEquals("Sembunyikan keterangan", toggle.getTitle().toString());
            assertEquals("Sembunyikan deskripsi di bawah nama kreator pada feed.",
                    toggle.getSummary().toString());
            assertEquals("Saat ini: 3 video",
                    L10n.f(activity, "Current: %1$s", L10n.quantity(activity, 3, "%1$s video", "%1$s videos", "3")));
        }
    }

    @Test
    public void everyTableIsKeyedByTheEnglishTextItself() {
        assertEquals("Beschreibung ausblenden", GERMAN.get("Hide the caption"));
        assertEquals("Sembunyikan keterangan", INDONESIAN.get("Hide the caption"));
        for (String language : L10nTranslations.LANGUAGES) {
            Map<String, String> table = L10nTranslations.of(language);
            assertFalse(language, table.isEmpty());
            // Nothing is translated to nothing: an empty value would blank a label.
            for (Map.Entry<String, String> entry : table.entrySet()) {
                assertFalse("empty key in " + language, entry.getKey().isEmpty());
                assertFalse("empty translation of: " + entry.getKey(), entry.getValue().isEmpty());
            }
        }
    }

    @Test
    public void everyTableCoversTheSameEnglish() {
        // A language that is missing entries the others have would show a half English screen.
        // The extra plural rows a language may carry are its own and are left out of the
        // comparison, but each has to belong to a key the language also carries plainly.
        for (String language : L10nTranslations.LANGUAGES) {
            Map<String, String> table = L10nTranslations.of(language);
            Set<String> plain = new LinkedHashSet<>();
            for (String key : table.keySet()) {
                String base = pluralBase(key);
                if (base.equals(key)) {
                    plain.add(key);
                } else {
                    assertTrue(language + " carries " + key + " without its other form " + base,
                            table.containsKey(base));
                }
            }
            assertEquals("keys of " + language, sourceKeys(GERMAN), plain);
        }
    }

    /**
     * A language whose plural rule splits everyday counts more finely than English's two forms
     * carries a row for each extra form, for every count the code words. Russian says
     * 1 результат, 2 результата, 5 результатов and 21 результат: a count with no few row read
     * "2 результатов", and with no one row past 1, "21 результатов".
     */
    @Test
    public void everyCountCarriesTheFormsItsLanguageNeeds() throws Exception {
        Set<String> others = quantityOtherForms();
        assertTrue("the scan found too few counts to mean anything: " + others.size(), others.size() > 20);
        List<String> missing = new ArrayList<>();
        for (String language : L10nTranslations.LANGUAGES) {
            Map<String, String> table = L10nTranslations.of(language);
            android.icu.text.PluralRules rules = android.icu.text.PluralRules.forLocale(
                    L10n.localeOf(language));
            Set<String> needed = new LinkedHashSet<>();
            for (long count = 0; count <= 200; count++) {
                String category = rules.select(count);
                // English's forms cover exactly 1 and everything in "other". A one category
                // that also takes 0 reads fine in the other form; one that takes 21 doesn't.
                if (!category.equals("other") && !(category.equals("one") && count <= 1)) {
                    needed.add(category);
                }
            }
            for (String other : others) {
                for (String category : needed) {
                    if (!table.containsKey(other + "|" + category)) {
                        missing.add(language + ": " + other + "|" + category);
                    }
                }
            }
        }
        assertEquals("count forms missing from the tsv files:\n" + String.join("\n", missing),
                0, missing.size());
    }

    /**
     * The other form of every count the code words: the fourth argument of each L10n.quantity
     * call, and the plural unit of each number row, which NumberInputPreference hands to
     * L10n.quantity itself ("%1$s s" under Seek by needs its few row as much as a sentence
     * does). Read by position, so a one form that isn't a literal can't shift another argument
     * into its place. An other form that isn't written out where the count is worded fails
     * rather than going unchecked; NumberInputPreference's own call is the one exception, and
     * its units are read from the rows that pass them. A unit given once, or with the same one
     * and other form, is a symbol (s, dp, %) that reads the same for every count.
     */
    private static Set<String> quantityOtherForms() throws Exception {
        java.io.File root = new java.io.File("src/main/java/app/morphe/extension/tiktok");
        if (!root.isDirectory()) root = new java.io.File(
                "extensions/tiktok/src/main/java/app/morphe/extension/tiktok");
        assertTrue("could not find the source tree", root.isDirectory());
        java.util.regex.Pattern call = java.util.regex.Pattern.compile("L10n\\s*\\.\\s*quantity\\s*\\(");
        java.util.regex.Pattern row = java.util.regex.Pattern.compile(
                "new\\s+(?:[\\w.]+\\.)?NumberInputPreference\\s*\\(");
        // A subclass's super(...) and NumberInputPreference's own this(...) pass units too.
        java.util.regex.Pattern chained = java.util.regex.Pattern.compile("\\b(?:super|this)\\s*\\(");
        Set<String> others = new LinkedHashSet<>();
        List<String> unread = new ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> files =
                     java.nio.file.Files.walk(root.toPath())) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toList())) {
                String text = new String(java.nio.file.Files.readAllBytes(file),
                        java.nio.charset.StandardCharsets.UTF_8);
                byte[] kind = classify(text);
                String name = file.getFileName().toString();
                boolean numberInput = name.equals("NumberInputPreference.java");
                java.util.regex.Matcher match = call.matcher(text);
                while (match.find()) {
                    if (kind[match.start()] != CODE) continue;
                    List<int[]> arguments = topLevelArguments(text, kind, match.end() - 1);
                    String one = arguments.size() > 3 ? literalArgument(text, kind, arguments.get(2)) : null;
                    String other = arguments.size() > 3 ? literalArgument(text, kind, arguments.get(3)) : null;
                    if (other != null) {
                        if (!other.isEmpty() && !other.equals(one)) others.add(other);
                    } else if (!numberInput) {
                        unread.add(name + ": " + firstLine(text, match.start()));
                    }
                }
                List<java.util.regex.Matcher> rows = new ArrayList<>();
                rows.add(row.matcher(text));
                if (numberInput || text.contains("extends NumberInputPreference")) rows.add(chained.matcher(text));
                for (java.util.regex.Matcher rowMatch : rows) {
                    while (rowMatch.find()) {
                        if (kind[rowMatch.start()] != CODE) continue;
                        List<int[]> arguments = topLevelArguments(text, kind, rowMatch.end() - 1);
                        // (context, title, summary, setting, unit) or (..., setting, one, other).
                        if (arguments.size() != 5 && arguments.size() != 6) continue;
                        String one = literalArgument(text, kind, arguments.get(4));
                        String other = literalArgument(text, kind, arguments.get(arguments.size() - 1));
                        if (other != null) {
                            if (!other.isEmpty() && arguments.size() == 6 && !other.equals(one)) others.add(other);
                        } else if (!numberInput) {
                            unread.add(name + ": " + firstLine(text, rowMatch.start()));
                        }
                    }
                }
            }
        }
        assertEquals("counts worded from something other than a literal, which this can't check:\n"
                + String.join("\n", unread), 0, unread.size());
        return others;
    }

    /** The spans of a call's own arguments, split at its own commas; open is its bracket. */
    private static List<int[]> topLevelArguments(String text, byte[] kind, int open) {
        List<int[]> spans = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        for (int at = open; at < text.length(); at++) {
            if (kind[at] != CODE) continue;
            char c = text.charAt(at);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (--depth == 0) {
                    if (!text.substring(start, at).trim().isEmpty()) spans.add(new int[]{start, at});
                    return spans;
                }
            } else if (c == ',' && depth == 1) {
                spans.add(new int[]{start, at});
                start = at + 1;
            }
        }
        return spans;
    }

    /** An argument made of string literals and pluses alone, as the one string it is; else null. */
    private static String literalArgument(String text, byte[] kind, int[] span) {
        StringBuilder value = new StringBuilder();
        boolean any = false;
        for (int at = span[0]; at < span[1]; at++) {
            char c = text.charAt(at);
            if (kind[at] == COMMENT) continue;
            if (kind[at] == CODE) {
                if (c == '+' || Character.isWhitespace(c)) continue;
                return null;
            }
            if (c != '"') return null;
            int end = at + 1;
            while (end < span[1] && kind[end] == LITERAL) end++;
            int contentEnd = end > at + 1 && text.charAt(end - 1) == '"' ? end - 1 : end;
            value.append(unescape(text.substring(at + 1, contentEnd)));
            any = true;
            at = end - 1;
        }
        return any ? value.toString() : null;
    }

    private static String firstLine(String text, int from) {
        int end = text.indexOf('\n', from);
        return text.substring(from, end < 0 ? text.length() : end).trim();
    }

    /** The positional scan reads an other form wherever it sits, and refuses one it can't read. */
    @Test public void theCountScanReadsTheOtherFormByPosition() {
        String source = "L10n.quantity(context, n, flag ? \"a\" : \"b\", \"%1$d c\" + \"s\", n, x);";
        byte[] kind = classify(source);
        List<int[]> arguments = topLevelArguments(source, kind, source.indexOf('('));
        assertEquals(6, arguments.size());
        assertEquals("%1$d cs", literalArgument(source, kind, arguments.get(3)));
        assertNull(literalArgument(source, kind, arguments.get(2)));
        assertNull(literalArgument(source, kind, arguments.get(1)));
        String nested = "new NumberInputPreference(context, L10n.f(context, \"t %1$d\", 1, 2), \"s\", s, \"%1$s day\", \"%1$s days\")";
        kind = classify(nested);
        arguments = topLevelArguments(nested, kind, nested.indexOf('('));
        assertEquals(6, arguments.size());
        assertEquals("%1$s days", literalArgument(nested, kind, arguments.get(5)));
    }

    /**
     * A key is a whole phrase, never a lone unit or conjunction. "days", "dp", "or" and "view
     * per like" were keys assembled into sentences in code, which fixed the word order and the
     * agreement to English; each is now a phrase with its number or its list in it.
     */
    @Test public void noKeyIsALoneUnitOrConjunction() throws Exception {
        // Words that stand alone on purpose: a state a screen reader speaks for a row.
        Set<String> standalone = new LinkedHashSet<>(List.of("collapsed", "expanded"));
        java.util.regex.Pattern lone = java.util.regex.Pattern.compile(
                "^(?:[a-z]+|[a-z]+ per [a-z ]+)$");
        List<String> offenders = new ArrayList<>();
        for (String key : readTable(ENGLISH_BASE).keySet()) {
            if (lone.matcher(key).matches() && !standalone.contains(key)) offenders.add(key);
        }
        assertEquals("keys that are a lone unit or conjunction:\n" + String.join("\n", offenders),
                0, offenders.size());
    }

    /** The gates read a {@code |category} row as its other form, and nothing else as one. */
    @Test public void aPluralVariantRowBelongsToItsOtherForm() {
        assertEquals("%1$d results", pluralBase("%1$d results|few"));
        assertEquals("%1$d results", pluralBase("%1$d results|many"));
        assertEquals("Filter: %1$s", pluralBase("Filter: %1$s"));
        // Only a CLDR category counts; a bar inside ordinary text is text.
        assertEquals("a|b", pluralBase("a|b"));
        assertEquals("Something|other", pluralBase("Something|other"));
    }

    /** The other form a {@code |category} row belongs to, or the key itself. */
    static String pluralBase(String key) {
        java.util.regex.Matcher variant = java.util.regex.Pattern
                .compile("^(.+)\\|(?:zero|one|two|few|many)$").matcher(key);
        return variant.matches() ? variant.group(1) : key;
    }

    /** A table's keys without the {@code |category} rows: the strings the code says. */
    private static Set<String> sourceKeys(Map<String, String> table) {
        Set<String> keys = new LinkedHashSet<>();
        for (String key : table.keySet()) {
            if (pluralBase(key).equals(key)) keys.add(key);
        }
        return keys;
    }

    @Test
    public void everySettingsStringHasATranslationEntry() throws Exception {
        Set<String> english = new LinkedHashSet<>(sourceKeys(GERMAN));
        Set<String> shown = collectEverything();
        List<String> missing = new ArrayList<>();

        // A restart-gated row's summary is its own sentence plus the shared restart sentence,
        // looked up separately and joined afterwards. Both halves still have to be entries, so
        // the tail is taken off here and checked on its own rather than exempted.
        assertTrue("the restart sentence itself is not in the table",
                english.contains(TogglePreference.RESTART_SENTENCE));

        List<java.util.regex.Pattern> templates = templates(english);
        for (String text : shown) {
            // Whole first. Plenty of summaries were written with the restart sentence in them
            // and are one key including it, so splitting before looking would break those.
            if (english.contains(text)) continue;

            String body = text;
            // Both joins. A summary that is a sentence takes the note after a space; one that
            // is a state line takes it on the line below. Stripping only the first left every
            // tab row exempted as composed by the newline the note itself had just added.
            if (body.endsWith(" " + TogglePreference.RESTART_SENTENCE)
                    || body.endsWith("\n" + TogglePreference.RESTART_SENTENCE)) {
                body = body.substring(0,
                        body.length() - TogglePreference.RESTART_SENTENCE.length() - 1);
            }
            // Text spanning lines is assembled from parts that are entries of their own.
            // Exempting anything merely carrying a digit or a slash let 63 of 619 strings
            // through, including every message with a value in it. Text a placeholder key
            // produces (a limit formatted in from a constant) is accepted only when one of
            // those keys matches it whole, not because it carries a number.
            boolean composed = body.contains("\n") || body.matches("(?s).*%\\d\\$.*");
            if (!composed && !isValueRatherThanProse(body) && !english.contains(body)
                    && !producedByAKey(body, templates)) {
                missing.add(body);
            }
        }
        assertEquals("settings text without a translation entry: " + missing, 0, missing.size());
    }

    /** The keys with placeholders, as patterns for the text they can produce. */
    private static List<java.util.regex.Pattern> templates(Set<String> keys) {
        List<java.util.regex.Pattern> found = new ArrayList<>();
        java.util.regex.Pattern placeholder = java.util.regex.Pattern.compile("%\\d\\$([sd])");
        for (String key : keys) {
            java.util.regex.Matcher match = placeholder.matcher(key);
            if (!match.find()) continue;
            // A key that is nearly all placeholder would accept nearly anything, which would
            // make this check say nothing.
            if (key.replaceAll("%\\d\\$[sd]", "").trim().length() < 8) continue;
            StringBuilder regex = new StringBuilder("(?s)");
            int last = 0;
            do {
                regex.append(java.util.regex.Pattern.quote(key.substring(last, match.start())));
                // A number the way NumberFormat writes it, or any text at all for %s.
                regex.append("d".equals(match.group(1)) ? "-?[\\d,.]+" : ".+?");
                last = match.end();
            } while (match.find());
            regex.append(java.util.regex.Pattern.quote(key.substring(last)));
            found.add(java.util.regex.Pattern.compile(regex.toString()));
        }
        return found;
    }

    private static boolean producedByAKey(String text, List<java.util.regex.Pattern> templates) {
        for (java.util.regex.Pattern template : templates) {
            if (template.matcher(text).matches()) return true;
        }
        return false;
    }

    /**
     * A chosen value shown back to the reader rather than wording of ours. A resolution and a
     * speed are written the same way in every language, and the download folder is a real path
     * on the device. An entry mapping each of these to itself would only pad the table.
     *
     * <p>The SIM preset summary is the exception that is a real gap. It is built at runtime as
     * "country, carrier (mccmnc)", and the country reads differently in German and Indonesian.
     * Translating the preset list is its own task and is on the roadmap; matching it on the
     * carrier code it ends with keeps this check honest about what it lets past rather than
     * widening the rule until it disappears.
     */
    private static boolean isValueRatherThanProse(String text) {
        return text.matches("\\d+p")
                || text.matches("\\d+(\\.\\d+)?x")
                || text.matches("[A-Za-z]+(/[A-Za-z0-9_-]+)+")
                || text.matches(".*\\(\\d{5,6}\\)");
    }

    @Test
    public void theTableCarriesNothingTheScreensNoLongerSay() throws Exception {
        // A wording that drifted leaves its old entry behind, translated and unreachable, and
        // the next reader of the table cannot tell it from one that is still in use.
        Set<String> shown = new LinkedHashSet<>(collectEverything());
        shown.addAll(runtimeStringsInSource());

        Set<String> orphaned = new LinkedHashSet<>();
        for (String language : L10nTranslations.LANGUAGES) {
            for (String english : L10nTranslations.of(language).keySet()) {
                // A |category row lives or dies with the other form it belongs to.
                if (!shown.contains(pluralBase(english))) {
                    orphaned.add(english);
                }
            }
        }

        assertEquals("translation entries nothing shows any more, so remove them from the tsv "
                + "files and rerun scripts/gen-l10n.py: " + orphaned, 0, orphaned.size());
    }

    @Test
    public void everyStringHandedToL10nHasAnEntry() throws Exception {
        // Wrapping a string in L10n.t is what the toast and content-description check looks for,
        // and on its own it proves only the shape of the call. Without this, a new overlay label
        // wrapped correctly but never added to the tsv files passes every check and still
        // reaches the reader in English.
        java.io.File root = new java.io.File("src/main/java/app/morphe/extension/tiktok");
        if (!root.isDirectory()) root = new java.io.File(
                "extensions/tiktok/src/main/java/app/morphe/extension/tiktok");
        assertTrue("could not find the source tree", root.isDirectory());

        java.util.regex.Pattern call = java.util.regex.Pattern.compile("L10n\\s*\\.\\s*(?:t|f|quantity)\\s*\\(");
        List<String> missing = new ArrayList<>();
        int found = 0;
        try (java.util.stream.Stream<java.nio.file.Path> files =
                     java.nio.file.Files.walk(root.toPath())) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toList())) {
                String text = new String(java.nio.file.Files.readAllBytes(file),
                        java.nio.charset.StandardCharsets.UTF_8);
                byte[] kind = classify(text);
                java.util.regex.Matcher match = call.matcher(text);
                while (match.find()) {
                    if (kind[match.start()] != CODE) continue;
                    int close = closingBracket(text, kind, match.end() - 1);
                    if (close < 0) continue;
                    List<String> parts = literalsIn(text, kind, match.end(), close);
                    if (parts.isEmpty()) continue;
                    found++;

                    // One message split across lines is a single key; a choice between two
                    // messages is two keys. Both are written as several literals in one call,
                    // so either reading is accepted and only a part in neither is reported.
                    if (GERMAN.containsKey(String.join("", parts))) continue;
                    for (String part : parts) {
                        if (!GERMAN.containsKey(part)) {
                            missing.add(file.getFileName() + ": " + part);
                        }
                    }
                }
            }
        }

        assertTrue("the scan found no L10n calls to read", found > 50);
        assertEquals("strings handed to L10n with no entry in the tsv files: " + missing,
                0, missing.size());
    }

    /**
     * Every word a settings row is built with is a key, read from the source rather than from
     * the screen.
     *
     * <p>The rendered check two tests up cannot see these. A row's summary is built at runtime
     * from its own wording plus a range line and a current-value line, and the joined text is
     * exempted there as composed, because the lines below the first are numbers formatted from
     * keys of their own. So the wording at the top of six Playback rows, and the unit words
     * beside their numbers, had no entry in any table and reached German, Spanish, Indonesian
     * and Brazilian phones in English under a translated title, with both gates passing.
     *
     * <p>Reading the constructor call instead sidesteps the formatting entirely: the literal
     * handed to the row is the key, before anything is joined to it.
     */
    @Test
    public void everyWordASettingsRowIsBuiltWithHasAnEntry() throws Exception {
        java.io.File categories = new java.io.File(
                "src/main/java/app/morphe/extension/tiktok/settings/preference/categories");
        if (!categories.isDirectory()) categories = new java.io.File("extensions/tiktok/src/main/java"
                + "/app/morphe/extension/tiktok/settings/preference/categories");
        assertTrue("could not find the settings categories", categories.isDirectory());

        java.util.regex.Pattern row = java.util.regex.Pattern.compile(
                "new\\s+(?:[\\w.]+\\.)?(?:TogglePreference|NumberInputPreference"
                        + "|ClockHourPreference|InputTextPreference|RangeValuePreference)\\s*\\(");
        List<String> missing = new ArrayList<>();
        int rows = 0;
        java.io.File[] sources = categories.listFiles((dir, name) -> name.endsWith(".java"));
        assertTrue("no category sources to read", sources != null && sources.length > 5);
        for (java.io.File file : sources) {
            String text = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            byte[] kind = classify(text);
            java.util.regex.Matcher match = row.matcher(text);
            while (match.find()) {
                if (kind[match.start()] != CODE) continue;
                int close = closingBracket(text, kind, match.end() - 1);
                if (close < 0) continue;
                rows++;
                for (String argument : arguments(text, kind, match.end() - 1, close)) {
                    if (argument.isEmpty() || GERMAN.containsKey(argument)) continue;
                    missing.add(file.getName() + ": " + argument);
                }
            }
        }

        assertTrue("the scan found too few settings rows to mean anything: " + rows, rows > 100);
        assertEquals("settings rows built from text with no entry in the tsv files, so it reaches "
                + "a translated phone in English: " + missing, 0, missing.size());
    }

    /**
     * A summary that sends the reader to another switch names that switch, in every language.
     *
     * <p>Three of the four translations of the store-region row told the reader to turn on
     * switches that are on no screen: "SIM-Details überschreiben" where the switch says
     * "SIM-Angaben überschreiben", "Ganti detail SIM" where it says "Timpa detail SIM". Each
     * table is right about its own titles and wrong about the titles quoted inside a summary,
     * which no per-string check can see, because both halves are perfectly good translations.
     */
    @Test public void aSummaryThatNamesAnotherRowNamesItByTheTitleThatRowCarries() throws Exception {
        List<String[]> rows = settingsRowArguments();
        Set<String> titles = new LinkedHashSet<>();
        for (String[] row : rows) {
            // Long enough that finding it inside a sentence means it was quoted on purpose.
            if (row[0] != null && row[0].length() >= 12) titles.add(row[0]);
        }
        assertTrue("the scan found too few row titles to mean anything: " + titles.size(),
                titles.size() > 40);

        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (String language : L10nTranslations.LANGUAGES) {
            Map<String, String> table = L10nTranslations.of(language);
            for (String[] row : rows) {
                String summary = row[1];
                if (summary == null) continue;
                String translatedSummary = table.get(summary);
                if (translatedSummary == null) continue;
                for (String title : titles) {
                    if (title.equals(row[0]) || !summary.contains(title)) continue;
                    // "Hide the status bar" inside a quoted "Hide the status bar in LIVE rooms"
                    // names the longer row, not its own.
                    if (onlyInsideLongerTitle(summary, title, titles)) continue;
                    String translatedTitle = table.get(title);
                    if (translatedTitle == null) continue;
                    checked++;
                    if (!translatedSummary.contains(translatedTitle)) {
                        wrong.add(language + ": a summary sends the reader to \"" + translatedTitle
                                + "\" but says \"" + translatedSummary + "\"");
                    }
                }
            }
        }

        assertTrue("no summary quotes another row's title, so this checked nothing", checked > 0);
        assertEquals("summaries that name a switch by words no switch carries:\n"
                + String.join("\n", wrong), 0, wrong.size());
    }

    /** Whether every place {@code title} shows up in {@code summary} is part of a longer quoted title. */
    private static boolean onlyInsideLongerTitle(String summary, String title, Set<String> titles) {
        for (int at = summary.indexOf(title); at >= 0; at = summary.indexOf(title, at + 1)) {
            boolean covered = false;
            for (String longer : titles) {
                if (longer.length() <= title.length() || !longer.contains(title)) continue;
                for (int start = summary.indexOf(longer); start >= 0; start = summary.indexOf(longer, start + 1)) {
                    if (start <= at && at + title.length() <= start + longer.length()) {
                        covered = true;
                        break;
                    }
                }
                if (covered) break;
            }
            if (!covered) return false;
        }
        return true;
    }

    /**
     * One verb per outcome on the settings rows. Hide is a video or a control kept out of
     * view, remove is data taken out (ads, tracking parameters, the watermark), skip is the
     * feed moving past a video (a blocked sound, the budget). "Skipped", "drop" and "take
     * away" were used for the first of those in seven rows beside 137 that said hide.
     */
    @Test public void rowSummariesSayHideForWhatIsKeptOutOfView() throws Exception {
        List<String[]> rows = settingsRowArguments();
        List<String> offenders = new ArrayList<>();
        for (String[] row : rows) {
            for (String text : row) {
                if (text != null && STRAY_HIDE_VERB.matcher(text).find()) offenders.add(text);
            }
        }
        assertTrue("the scan found too few settings rows to mean anything: " + rows.size(),
                rows.size() > 100);
        assertEquals("rows that say drop, take away or skip where the rest say hide:\n"
                + String.join("\n", offenders), 0, offenders.size());
    }

    /**
     * The verbs the rows used for hiding before they said hide. "Skip" on its own stays: the
     * sound button skips the video playing and the warnings row skips a dialog, which is what
     * skip means. It is a video kept out of the feed that must not be "skipped".
     */
    private static final java.util.regex.Pattern STRAY_HIDE_VERB = java.util.regex.Pattern.compile(
            "\\b(?:[Dd]rop(?:s|ped|ping)?|[Tt]ake[sn]? away|skipped"
                    + "|[Ss]kip (?:feed |recorded )?(?:videos|LIVE|posts))\\b");

    /** The verb rule catches the seven rows it was written for and lets the two real skips by. */
    @Test public void theHideVerbRuleCanActuallyFail() {
        for (String old : new String[]{
                "These accounts are always skipped.",
                "Matching captions are skipped.",
                "Drop the notification saying somebody new followed you before it reaches the drawer.",
                "Take away the streak button in a chat and the reminder message that goes with it.",
                "Skip feed videos with place badges, even when they aren't paid ads.",
                "Skip recorded LIVE broadcasts in the feed.",
                "Skip videos marked as using promotional music.",
                "Skip videos that use a sound blocked with the player's sound button, or named below.",
                "Keep a local record of what you have watched and drop those videos from later feed pages."}) {
            assertTrue(old, STRAY_HIDE_VERB.matcher(old).find());
        }
        for (String kept : new String[]{
                "Block this sound",
                "Skip content warnings",
                "Add a separate button that skips videos using the current sound.",
                "Hide recorded LIVE broadcasts from the feed."}) {
            assertFalse(kept, STRAY_HIDE_VERB.matcher(kept).find());
        }
    }

    /** Every settings row the category sources build, as {@code [title, summary]}. */
    private static List<String[]> settingsRowArguments() throws Exception {
        java.io.File categories = new java.io.File(
                "src/main/java/app/morphe/extension/tiktok/settings/preference/categories");
        if (!categories.isDirectory()) categories = new java.io.File("extensions/tiktok/src/main/java"
                + "/app/morphe/extension/tiktok/settings/preference/categories");
        assertTrue("could not find the settings categories", categories.isDirectory());

        java.util.regex.Pattern row = java.util.regex.Pattern.compile(
                "new\\s+(?:[\\w.]+\\.)?(?:TogglePreference|NumberInputPreference"
                        + "|ClockHourPreference|InputTextPreference|RangeValuePreference)\\s*\\(");
        List<String[]> found = new ArrayList<>();
        java.io.File[] sources = categories.listFiles((dir, name) -> name.endsWith(".java"));
        assertTrue("no category sources to read", sources != null && sources.length > 5);
        for (java.io.File file : sources) {
            String text = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            byte[] kind = classify(text);
            java.util.regex.Matcher match = row.matcher(text);
            while (match.find()) {
                if (kind[match.start()] != CODE) continue;
                int close = closingBracket(text, kind, match.end() - 1);
                if (close < 0) continue;
                List<String> arguments = arguments(text, kind, match.end() - 1, close);
                found.add(new String[]{
                        arguments.size() > 0 ? arguments.get(0) : null,
                        arguments.size() > 1 ? arguments.get(1) : null});
            }
        }
        return found;
    }

    /**
     * One entry per argument of a call. Literals with nothing but a plus and whitespace between
     * them are one string in the source and one key in the table; a new argument starts a new
     * one. {@link #literalsIn} flattens both into a list, which cannot tell a summary written
     * across five lines from five separate words.
     */
    private static List<String> arguments(String text, byte[] kind, int from, int to) {
        List<String> found = new ArrayList<>();
        int previousEnd = -1;
        for (int at = from; at < to; at++) {
            if (kind[at] != LITERAL || text.charAt(at) != '"') continue;
            int end = at + 1;
            while (end < to && kind[end] == LITERAL) end++;
            int contentEnd = end > at + 1 && text.charAt(end - 1) == '"' ? end - 1 : end;
            String piece = unescape(text.substring(at + 1, contentEnd));

            String between = previousEnd < 0 ? null : text.substring(previousEnd, at);
            if (between != null && between.indexOf('+') >= 0
                    && between.replace("+", "").trim().isEmpty()) {
                found.set(found.size() - 1, found.get(found.size() - 1) + piece);
            } else {
                found.add(piece);
            }
            previousEnd = end;
            at = end - 1;
        }
        return found;
    }

    /**
     * The string literals inside one call, in order. A message too long for one line is written
     * as several literals with a plus between them and is one key; a choice between two messages
     * is written the same way and is two keys. The caller decides which reading fits.
     */
    private static List<String> literalsIn(String text, byte[] kind, int from, int to) {
        List<String> parts = new ArrayList<>();
        for (int at = from; at < to; at++) {
            if (kind[at] != LITERAL || text.charAt(at) != '"') continue;
            int end = at + 1;
            while (end < to && kind[end] == LITERAL) end++;
            // The run covers both quotes, so the closing one is not part of the text.
            int contentEnd = end > at + 1 && text.charAt(end - 1) == '"' ? end - 1 : end;
            // A literal being compared against is a value, not words on a screen:
            // "OBJECT".equals(entry.type) sits in an argument that does show text. Only the
            // comparisons, named one by one. Excluding every literal with a dot after it also
            // excused setText("A whole sentence".trim()), and a builder chain wrapped onto the
            // next line with it.
            if (!COMPARED.matcher(text).region(end, text.length()).lookingAt()) {
                parts.add(unescape(text.substring(at + 1, contentEnd)));
            }
            at = end - 1;
        }
        return parts;
    }

    /** What a literal can be asked, directly after its closing quote, without being words. */
    private static final java.util.regex.Pattern COMPARED = java.util.regex.Pattern.compile(
            "\\s*\\.\\s*(equals|equalsIgnoreCase|contentEquals|compareTo|compareToIgnoreCase)"
                    + "\\s*\\(");

    private static String unescape(String literal) {
        StringBuilder plain = new StringBuilder();
        for (int at = 0; at < literal.length(); at++) {
            char c = literal.charAt(at);
            if (c != '\\' || at + 1 >= literal.length()) {
                plain.append(c);
                continue;
            }
            char next = literal.charAt(++at);
            switch (next) {
                case 'n': plain.append('\n'); break;
                case 't': plain.append('\t'); break;
                default: plain.append(next); break;
            }
        }
        return plain.toString();
    }

    /**
     * Every string the extension's own source says, from both trees that reach the table. A
     * message written as several literals with a plus between them counts as its parts and as
     * the whole, the way the compiler joins it.
     *
     * <p>L10nTranslations*.java is skipped on purpose. It is generated from the tsv files and
     * repeats every key back as a literal, so including it left the orphan check unable to fail:
     * a key was "shown" because the generator had written it out again.
     */
    /**
     * Text a hand-built dialog puts on a view, which the checks above cannot see.
     *
     * <p>They reflect preference titles and summaries and they scan toasts and content
     * descriptions. A dialog built by hand calls setText, setHint or setTitle instead, and a
     * string handed to one of those went out in English however many tables it was in: two of
     * these already had German rows and simply bypassed L10n.
     *
     * <p>The rule is about the call rather than the table. Prose reaching one of these methods
     * has to arrive through L10n, so a later table that loses the row fails the check above
     * rather than shipping quietly.
     */
    @Test
    public void noDialogTextIsHandedStraightToAViewInEnglish() throws Exception {
        Map<java.nio.file.Path, String> sources = new LinkedHashMap<>();
        for (java.nio.file.Path file : tikTokSources()) {
            sources.put(file, new String(java.nio.file.Files.readAllBytes(file),
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        List<String> unwrapped = unwrappedProseIn(sources);
        assertTrue("no call to any of these was found, so this proves nothing", callsChecked > 20);
        assertEquals("text handed straight to a view in English, wrap it in L10n.t: "
                + unwrapped, 0, unwrapped.size());
    }

    /** How many showing calls the last scan looked inside. A scan of nothing proves nothing. */
    private static int callsChecked;

    /**
     * Every prose literal that reaches a view in these sources without going through L10n.
     *
     * <p>Separate from the test so a synthetic source can be put in front of the same code.
     * A scan whose only input is a tree that already passes says nothing about what it would
     * catch, and four shapes walked past the first version of it.
     */
    private static List<String> unwrappedProseIn(Map<java.nio.file.Path, String> sources) {
        Set<String> helpers = helpersThatPutTextOnTheScreen(sources);
        java.util.regex.Pattern call = java.util.regex.Pattern.compile(
                VIEW_TEXT_CALLS + helperAlternation(namesIn(helpers)));
        List<String> unwrapped = new ArrayList<>();
        callsChecked = 0;
        for (Map.Entry<java.nio.file.Path, String> source : sources.entrySet()) {
            java.nio.file.Path file = source.getKey();
            String text = source.getValue();
            byte[] kind = classify(text);
            byte[] outside = withoutL10nCalls(text, kind);
            java.util.regex.Matcher match = call.matcher(text);
            while (match.find()) {
                if (kind[match.start()] != CODE) continue;
                int open = match.end() - 1;
                int close = closingBracket(text, kind, open);
                if (close < 0) continue;
                callsChecked++;
                String helper = calledName(text, match.start(), open);
                List<int[]> arguments = argumentsOf(text, kind, open, close);
                for (int index = 0; index < arguments.size(); index++) {
                    // A derived helper shows one of its parameters and passes the rest along.
                    // Reading every literal in its brackets would flag the type name in
                    // addInfo(root, "TikTok's own value", "OBJECT".equals(entry.type) ? ...).
                    if (helper != null && !helpers.contains(helper + "#" + index)) continue;
                    for (String literal : literalsIn(text, outside,
                            arguments.get(index)[0], arguments.get(index)[1])) {
                        if (isProse(literal)) {
                            unwrapped.add(file.getFileName() + ": " + literal);
                        }
                    }
                }
            }
        }
        return unwrapped;
    }

    /**
     * The shapes this check has already been blind to, put in front of it on purpose.
     *
     * <p>Each of these went past it while the real tree was green, so a green run over the
     * real tree is not evidence that the analysis works. The sentence is the same in every
     * case and it must be reported every time.
     */
    @Test
    public void theViewTextCheckCanActuallyFail() {
        String sentence = "A whole sentence of English";
        Map<String, String> shapes = new LinkedHashMap<>();
        shapes.put("straight to setText",
                "class A { void a(android.widget.TextView v) { v.setText(\"" + sentence + "\"); } }");
        shapes.put("through a helper of its own",
                "class A {\n"
                        + "  void a() { show(\"" + sentence + "\"); }\n"
                        + "  void show(String caption) { SettingsUi.text(null, caption, 1, 2, 3); }\n"
                        + "}");
        shapes.put("a method that trims the literal first",
                "class A { void a(android.widget.TextView v) { v.setText(\"" + sentence
                        + "\".trim()); } }");
        shapes.put("a builder chain wrapped onto the next line",
                "class A { void a(android.widget.TextView v) { v.setText(\"" + sentence + "\"\n"
                        + "        .toUpperCase()); } }");
        shapes.put("a helper whose first parameter is a generic type",
                "class A {\n"
                        + "  void a() { counted(null, \"" + sentence + "\"); }\n"
                        + "  void counted(Map<String, Integer> counts, String caption) {\n"
                        + "    SettingsUi.text(null, caption, 1, 2, 3);\n"
                        + "  }\n"
                        + "}");
        shapes.put("a helper whose parameter carries an annotation",
                "class A {\n"
                        + "  void a() { noted(\"" + sentence + "\"); }\n"
                        + "  void noted(@SuppressWarnings(\"x\") String caption) {\n"
                        + "    SettingsUi.text(null, caption, 1, 2, 3);\n"
                        + "  }\n"
                        + "}");

        for (Map.Entry<String, String> shape : shapes.entrySet()) {
            Map<java.nio.file.Path, String> source = new LinkedHashMap<>();
            source.put(java.nio.file.Paths.get("A.java"), shape.getValue());
            List<String> found = unwrappedProseIn(source);
            assertEquals(shape.getKey() + " was not reported",
                    java.util.Collections.singletonList("A.java: " + sentence), found);
        }

        // And the one shape that must stay quiet, because it is a value being compared.
        Map<java.nio.file.Path, String> comparison = new LinkedHashMap<>();
        comparison.put(java.nio.file.Paths.get("B.java"),
                "class B { void a(android.widget.TextView v) {\n"
                        + "  v.setText(\"OBJECT\".equals(type) ? one : two);\n"
                        + "} }");
        assertEquals("a literal being compared against was read as words",
                java.util.Collections.emptyList(), unwrappedProseIn(comparison));
    }

    /**
     * The calls that are a view being handed words, before anything is derived from them.
     *
     * <p>The two named classes are the label builders this bundle draws its own screens with,
     * so a sentence written as {@code FeatureGateLabUi.body(context, "...")} is a sentence on
     * the screen exactly as {@code setText} is.
     */
    private static final String VIEW_TEXT_CALLS =
            "\\.\\s*(setText|setHint|setTitle|setMessage|setContentDescription|setPositiveButton"
                    + "|setNegativeButton|setNeutralButton)\\s*\\(|"
                    + "\\b(FeatureGateLabUi|SettingsUi)\\s*\\.\\s*"
                    + "(text|label|body|header|title|caption)\\s*\\(|"
                    // A menu item is a row a reader reads, and Menu.add takes its words as the
                    // last of four arguments rather than through anything setText-shaped. Six
                    // items in the Feature Gate Lab's overflow were English because of it.
                    + "(?:[Mm]enu\\w*|getMenu\\s*\\(\\s*\\))\\s*\\.\\s*add\\s*\\(";

    /** Never a helper, whatever the block behind the bracket does with a String. */
    private static final Set<String> NOT_A_HELPER = new LinkedHashSet<>(java.util.Arrays.asList(
            "if", "for", "while", "switch", "catch", "synchronized", "t", "f"));

    /**
     * Every method here that hands a String it was given straight to something that shows it,
     * as the method's name, a hash and the position of the parameter that gets shown.
     *
     * <p>The label builders above used to be the whole list, written out by hand, which stops
     * the rule one hop short of wherever the code actually is. FeatureGateLabUi.iconButton
     * hands its description to setContentDescription and FeatureGateDetailFragment.addInfo
     * hands its label to body(), so "Clear search" and twelve rows of the Feature Gate Lab's
     * technical details went out in English with this check reading straight past them. The
     * declarations are walked instead: a method whose String parameter reaches a call already
     * known to show text is one of those calls itself, and that repeats until a pass finds
     * nothing new.
     *
     * <p>Straight is the whole of it. A parameter that arrives at the view through L10n is
     * translated on the way, which is what every preference wrapper here does, so the L10n
     * calls are blanked out before the parameter is looked for. Without that the wrappers
     * themselves count as English and every row of every settings page is a finding.
     */
    private static Set<String> helpersThatPutTextOnTheScreen(
            Map<java.nio.file.Path, String> sources) {
        Map<java.nio.file.Path, byte[]> kinds = new LinkedHashMap<>();
        Map<java.nio.file.Path, byte[]> masked = new LinkedHashMap<>();
        Map<java.nio.file.Path, List<Method>> methods = new LinkedHashMap<>();
        for (Map.Entry<java.nio.file.Path, String> source : sources.entrySet()) {
            byte[] kind = classify(source.getValue());
            kinds.put(source.getKey(), kind);
            masked.put(source.getKey(), withoutL10nCalls(source.getValue(), kind));
            methods.put(source.getKey(), methodsIn(source.getValue(), kind));
        }

        Set<String> helpers = new LinkedHashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            java.util.regex.Pattern shows = java.util.regex.Pattern.compile(
                    VIEW_TEXT_CALLS + helperAlternation(namesIn(helpers)));
            for (Map.Entry<java.nio.file.Path, String> source : sources.entrySet()) {
                String text = source.getValue();
                byte[] kind = kinds.get(source.getKey());
                byte[] outside = masked.get(source.getKey());
                for (Method method : methods.get(source.getKey())) {
                    for (int index : shownParametersOf(text, kind, outside, method, shows,
                            helpers)) {
                        if (helpers.add(method.name + "#" + index)) grew = true;
                    }
                }
            }
        }
        return helpers;
    }

    /** The bare method names of a set of name#index entries. */
    private static Set<String> namesIn(Set<String> helpers) {
        Set<String> names = new LinkedHashSet<>();
        for (String helper : helpers) names.add(helper.substring(0, helper.indexOf('#')));
        return names;
    }

    /** The alternation adding the derived helpers to the pattern, empty when there are none. */
    private static String helperAlternation(Set<String> names) {
        StringBuilder pattern = new StringBuilder();
        for (String name : names) {
            pattern.append("|\\b").append(java.util.regex.Pattern.quote(name)).append("\\s*\\(");
        }
        return pattern.toString();
    }

    /** Which of a method's parameters reach a showing call inside its body without going
     * through L10n, by position in the declaration. */
    private static Set<Integer> shownParametersOf(String text, byte[] kind, byte[] outside,
                                                  Method method, java.util.regex.Pattern shows,
                                                  Set<String> helpers) {
        Set<Integer> shown = new LinkedHashSet<>();
        java.util.regex.Matcher match = shows.matcher(text);
        int at = method.bodyStart;
        while (match.find(at) && match.start() < method.bodyEnd) {
            at = match.end();
            if (kind[match.start()] != CODE) continue;
            int open = match.end() - 1;
            int close = closingBracket(text, kind, open);
            if (close < 0) continue;
            String helper = calledName(text, match.start(), open);
            List<int[]> arguments = argumentsOf(text, kind, open, close);
            for (int index = 0; index < arguments.size(); index++) {
                if (helper != null && !helpers.contains(helper + "#" + index)) continue;
                for (int position : identifiersIn(text, outside,
                        arguments.get(index)[0], arguments.get(index)[1])) {
                    int declared = method.parameterAt(text, position);
                    if (declared >= 0) shown.add(declared);
                }
            }
        }
        return shown;
    }

    /** A method declaration: its name, the parameters it takes in order, and its body. */
    private static final class Method {
        final String name;
        final List<String> parameters;
        final Set<String> stringParameters;
        final int bodyStart;
        final int bodyEnd;

        Method(String name, List<String> parameters, Set<String> stringParameters,
               int bodyStart, int bodyEnd) {
            this.name = name;
            this.parameters = parameters;
            this.stringParameters = stringParameters;
            this.bodyStart = bodyStart;
            this.bodyEnd = bodyEnd;
        }

        /** The position of the String parameter named at this offset, or -1 for anything else. */
        int parameterAt(String text, int position) {
            int end = position;
            while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
            String word = text.substring(position, end);
            return stringParameters.contains(word) ? parameters.indexOf(word) : -1;
        }
    }

    /** The name of the method a matched call belongs to, or null when it is one of the seeds. */
    private static String calledName(String text, int start, int open) {
        String head = text.substring(start, open).trim();
        if (head.startsWith(".") || head.contains(".")) return null;
        return head.matches("\\w+") ? head : null;
    }

    /** Each argument of a call, as the half-open range it occupies. */
    private static List<int[]> argumentsOf(String text, byte[] kind, int open, int close) {
        List<int[]> arguments = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        for (int at = open + 1; at < close; at++) {
            if (kind[at] != CODE) continue;
            char c = text.charAt(at);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                arguments.add(new int[]{start, at});
                start = at + 1;
            }
        }
        arguments.add(new int[]{start, close});
        return arguments;
    }

    /** Where each bare identifier starts in a range, skipping anything L10n already holds. */
    private static List<Integer> identifiersIn(String text, byte[] outside, int from, int to) {
        List<Integer> starts = new ArrayList<>();
        int at = from;
        while (at < to) {
            if (outside[at] != CODE || !Character.isJavaIdentifierStart(text.charAt(at))) {
                at++;
                continue;
            }
            if (at > 0 && (text.charAt(at - 1) == '.'
                    || Character.isJavaIdentifierPart(text.charAt(at - 1)))) {
                at++;
                continue;
            }
            starts.add(at);
            while (at < to && Character.isJavaIdentifierPart(text.charAt(at))) at++;
        }
        return starts;
    }

    // A name and an opening bracket. Where that bracket ends is counted rather than matched,
    // because a parameter list can hold brackets of its own: an annotation with an argument,
    // @SuppressWarnings("x") String caption, made a pattern of "no bracket inside" walk past
    // the declaration entirely, and a helper it never saw is a helper it never derives.
    // A lambda's parameters carry no type, so a lambda has no String parameter by this reading.
    private static final java.util.regex.Pattern DECLARATION = java.util.regex.Pattern.compile(
            "(\\w+)\\s*\\(");

    /** Every method in a file that takes a String or a CharSequence. */
    private static List<Method> methodsIn(String text, byte[] kind) {
        List<Method> methods = new ArrayList<>();
        java.util.regex.Matcher match = DECLARATION.matcher(text);
        while (match.find()) {
            if (kind[match.start()] != CODE) continue;
            String name = match.group(1);
            if (NOT_A_HELPER.contains(name)) continue;
            int open = match.end() - 1;
            int close = closingBracket(text, kind, open);
            if (close < 0) continue;
            int brace = openingBraceAfter(text, kind, close);
            if (brace < 0) continue;
            List<int[]> parts = parameterPartsOf(text, kind, open, close);
            List<String> parameters = parametersOf(text, parts);
            Set<String> strings = stringParametersOf(text, parts);
            // A generic type carries a comma of its own, so Map<String, Integer> counts read
            // as two parameters and every parameter after it was recorded one place too far
            // to the right. The call side is then checked at the wrong argument.
            if (strings.isEmpty()) continue;
            int end = closingBrace(text, kind, brace);
            if (end < 0) continue;
            methods.add(new Method(name, parameters, strings, brace, end));
        }
        return methods;
    }

    /**
     * The brace that opens a method body, when the only thing between it and the bracket is a
     * throws clause. Anything else, a call or a control statement, has no body here.
     */
    private static int openingBraceAfter(String text, byte[] kind, int close) {
        StringBuilder between = new StringBuilder();
        for (int at = close + 1; at < text.length(); at++) {
            if (kind[at] != CODE) return -1;
            char c = text.charAt(at);
            if (c == '{') {
                return between.toString().trim().matches("(?:throws\\s+[\\w.,\\s]+)?") ? at : -1;
            }
            between.append(c);
            if (between.length() > 200) return -1;
        }
        return -1;
    }

    /**
     * A declaration's parameters, as the ranges they occupy.
     *
     * <p>Like {@link #argumentsOf} but counting angle brackets too, which a declaration has and
     * a call site does not: at a call site a {@code <} is a comparison and counting it would
     * run the depth away.
     */
    private static List<int[]> parameterPartsOf(String text, byte[] kind, int open, int close) {
        List<int[]> parts = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        for (int at = open + 1; at < close; at++) {
            if (kind[at] != CODE) continue;
            char c = text.charAt(at);
            if (c == '(' || c == '[' || c == '{' || c == '<') depth++;
            else if (c == ')' || c == ']' || c == '}' || c == '>') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(new int[]{start, at});
                start = at + 1;
            }
        }
        parts.add(new int[]{start, close});
        return parts;
    }

    /** Every parameter name in a declaration's bracket, in order. */
    private static List<String> parametersOf(String text, List<int[]> parts) {
        List<String> names = new ArrayList<>();
        for (int[] part : parts) {
            String[] words = text.substring(part[0], part[1]).trim().split("\\s+");
            names.add(words.length < 2 ? "" : words[words.length - 1]);
        }
        return names;
    }

    /** The names of the String and CharSequence parameters inside a declaration's bracket. */
    private static Set<String> stringParametersOf(String text, List<int[]> parts) {
        Set<String> names = new LinkedHashSet<>();
        for (int[] part : parts) {
            String[] words = text.substring(part[0], part[1]).trim().split("\\s+");
            if (words.length < 2) continue;
            String type = words[words.length - 2];
            String name = words[words.length - 1];
            if (!name.matches("\\w+")) continue;
            if (type.equals("String") || type.equals("CharSequence")
                    || type.equals("String...") || type.equals("CharSequence...")) {
                names.add(name);
            }
        }
        return names;
    }

    /** The brace closing the one at this index, counting only the braces that are code. */
    private static int closingBrace(String text, byte[] kind, int open) {
        int depth = 0;
        for (int at = open; at < text.length(); at++) {
            if (kind[at] != CODE) continue;
            if (text.charAt(at) == '{') depth++;
            else if (text.charAt(at) == '}' && --depth == 0) return at;
        }
        return -1;
    }

    /** Every .java file under the TikTok extension. */
    private static List<java.nio.file.Path> tikTokSources() throws Exception {
        java.io.File root = new java.io.File("src/main/java/app/morphe/extension/tiktok");
        if (!root.isDirectory()) root = new java.io.File(
                "extensions/tiktok/src/main/java/app/morphe/extension/tiktok");
        assertTrue("could not find the source tree", root.isDirectory());
        try (java.util.stream.Stream<java.nio.file.Path> files =
                     java.nio.file.Files.walk(root.toPath())) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toList());
        }
    }

    /**
     * The same classification with every L10n call blanked out.
     *
     * <p>A literal inside L10n.t is the key of a translated string, which is the thing this
     * check wants to see. Only what is left over is text going to a view as it stands.
     */
    private static byte[] withoutL10nCalls(String text, byte[] kind) {
        byte[] outside = kind.clone();
        java.util.regex.Pattern l10n = java.util.regex.Pattern.compile("L10n\\s*\\.\\s*(?:t|f|quantity)\\s*\\(");
        java.util.regex.Matcher match = l10n.matcher(text);
        while (match.find()) {
            if (kind[match.start()] != CODE) continue;
            int close = closingBracket(text, kind, match.end() - 1);
            if (close < 0) continue;
            for (int index = match.start(); index <= close && index < outside.length; index++) {
                outside[index] = COMMENT;
            }
        }
        return outside;
    }

    /**
     * Whether a literal is something a reader reads rather than a value.
     *
     * <p>A resource name, a package name, a format fragment and a path are all strings that
     * mean the same in every language. Prose has a space in it, or is a capitalised word long
     * enough not to be an abbreviation.
     */
    private static boolean isProse(String literal) {
        String trimmed = literal.trim();
        if (trimmed.length() < 4) return false;
        if (trimmed.startsWith("%") || trimmed.contains("://")) return false;
        // A path, a package name or a resource id: no spaces and a separator inside it.
        if (!trimmed.contains(" ")
                && (trimmed.contains(".") || trimmed.contains("/") || trimmed.contains("_"))) {
            return false;
        }
        return trimmed.contains(" ") || Character.isUpperCase(trimmed.charAt(0));
    }

    private static Set<String> runtimeStringsInSource() throws Exception {
        Set<String> literals = new LinkedHashSet<>();
        // Gradle runs the tests with the module directory as the working directory, so the paths
        // are tried from the repository root and from inside extensions/tiktok. Skipping a root
        // that resolves from neither would quietly shrink what counts as shown.
        for (String relative : new String[]{
                "extensions/tiktok/src/main/java",
                "extensions/shared/library/src/main/java"}) {
            java.io.File root = new java.io.File(relative);
            if (!root.isDirectory()) root = new java.io.File("../../" + relative);
            if (!root.isDirectory()) root = new java.io.File(relative.replaceFirst("^extensions/tiktok/", ""));
            assertTrue("could not find " + relative + " from "
                    + new java.io.File(".").getAbsolutePath(), root.isDirectory());
            try (java.util.stream.Stream<java.nio.file.Path> files =
                         java.nio.file.Files.walk(root.toPath())) {
                for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".java"))
                        .collect(java.util.stream.Collectors.toList())) {
                    if (file.getFileName().toString().startsWith("L10nTranslations")) continue;
                    collectStrings(new String(java.nio.file.Files.readAllBytes(file),
                            java.nio.charset.StandardCharsets.UTF_8), literals);
                }
            }
        }
        assertTrue("the scan found no source to read", literals.size() > 100);
        return literals;
    }

    /** Adds each literal, and for a run joined by plus signs the joined message as well. */
    private static void collectStrings(String text, Set<String> into) {
        byte[] kind = classify(text);
        int at = 0;
        while (at < text.length()) {
            if (kind[at] != LITERAL || text.charAt(at) != '"') {
                at++;
                continue;
            }
            List<String> parts = new ArrayList<>();
            while (at < text.length() && kind[at] == LITERAL && text.charAt(at) == '"') {
                int end = at + 1;
                while (end < text.length() && kind[end] == LITERAL) end++;
                int contentEnd = end > at + 1 && text.charAt(end - 1) == '"' ? end - 1 : end;
                parts.add(unescape(text.substring(at + 1, contentEnd)));
                into.add(parts.get(parts.size() - 1));

                int probe = end;
                while (probe < text.length() && kind[probe] == CODE
                        && Character.isWhitespace(text.charAt(probe))) probe++;
                if (probe < text.length() && kind[probe] == CODE && text.charAt(probe) == '+') {
                    probe++;
                    while (probe < text.length() && kind[probe] == CODE
                            && Character.isWhitespace(text.charAt(probe))) probe++;
                    at = probe;
                    continue;
                }
                at = end;
                break;
            }
            if (parts.size() > 1) into.add(String.join("", parts));
        }
    }

    @Test
    @Config(sdk = 28, qualifiers = "de")
    public void nothingOnTheScreensStaysEnglishUnderTheGermanLocale() throws Exception {
        assertNothingStaysEnglish("de", GERMAN, "Beschreibung ausblenden");
    }

    @Test
    @Config(sdk = 28, qualifiers = "in-rID")
    public void nothingOnTheScreensStaysEnglishUnderTheIndonesianLocale() throws Exception {
        assertNothingStaysEnglish("in-rID", INDONESIAN, "Sembunyikan keterangan");
    }

    private void assertNothingStaysEnglish(String qualifier, Map<String, String> table,
                                           String expected) throws Exception {
        // Words the language spells the same way, like Transparent in German, cannot tell
        // the two apart, so only the entries that actually change are evidence.
        Set<String> english = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : table.entrySet()) {
            if (!entry.getValue().equals(entry.getKey())) {
                english.add(entry.getKey());
            }
        }
        Set<String> shown = collectEverything();
        List<String> untranslated = new ArrayList<>();
        for (String text : shown) {
            if (english.contains(text)) {
                untranslated.add(text);
            }
        }
        assertEquals("still English under " + qualifier + ": " + untranslated, 0, untranslated.size());
        assertTrue(shown.contains(expected));
    }

    private Set<String> collectEverything() throws Exception {
        setEveryStatus(true);
        app.morphe.extension.shared.diagnostics.HookStatus.clear();
        Set<String> strings = new LinkedHashSet<>();
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new FeedFilterPreferenceCategory(activity, screen);
            new FeedNavigationPreferenceCategory(activity, screen);
            new InterfacePreferenceCategory(activity, screen);
            new CommentsPreferenceCategory(activity, screen);
            new DownloadsPreferenceCategory(activity, screen);
            new PlaybackPreferenceCategory(activity, screen);
            new ScreenTimePreferenceCategory(activity, screen);
            // "Start today over" says something different once it has been tapped, and that
            // sentence shipped in English on every translated phone because nothing here had
            // ever tapped it. The page is built a second time with the undo armed, and the tap
            // is taken back afterwards: the undo flag is process wide and this method runs once
            // per language, so leaving it armed meant the second language only ever saw the
            // tapped wording and stopped checking the other one.
            app.morphe.extension.tiktok.wellbeing.SessionBudget.clear();
            PreferenceScreen afterStartingOver =
                    activity.getPreferenceManager().createPreferenceScreen(activity);
            new ScreenTimePreferenceCategory(activity, afterStartingOver);
            collect(afterStartingOver, strings);
            app.morphe.extension.tiktok.wellbeing.SessionBudget.undoClear();
            assertFalse("the tap on Start today over was not taken back, so the next language"
                            + " will not see the untapped wording",
                    app.morphe.extension.tiktok.wellbeing.SessionBudget.canUndoClear());
            new InboxPreferenceCategory(activity, screen);
            new SharePreferenceCategory(activity, screen);
            new SimSpoofPreferenceCategory(activity, screen);
            new DebugPreferenceCategory(activity, screen);
            new ExtensionPreferenceCategory(activity, screen);
            // This About action is unconditional and doesn't belong to a patch-gated category.
            screen.addPreference(new app.morphe.extension.tiktok.settings.preference.BuildDetailsPreference(activity));
            collect(screen, strings);
            // Navigation labels are built outside the leaf categories.
            for (String section : new String[]{"Feed filter", "Feed tabs", "Feed screen",
                    "Playback", "Screen time", "Comments", "Downloads", "Share sheet", "Inbox",
                    "Privacy", "Region", "App", "Diagnostics", "Backup and restore", "Settings",
                    "Choose what reaches your feed", "Arrange your feed and bottom tabs",
                    "Captions, gestures and on-screen controls", "Quality, speed and auto-advance",
                    "Daily budgets, reminders and the hold", "Filters, translation and copy options",
                    "Quality, files, subtitles and hand-off", "People, shortcuts and sending controls",
                    "Choose which rows and controls appear", "Tracking, device access and links",
                    "Country and network preferences", "Layout, search, profile and system",
                    "Logging, hook status and reports", "Save, restore, reset and undo your settings"}) {
                strings.add(L10n.t(activity, section));
            }
            for (String text : QUIET_INDEX_COPY) strings.add(L10n.t(activity, text));
            strings.add(L10n.t(activity, "Back up settings"));
            strings.add(L10n.t(activity, "Restore settings"));
            strings.add(L10n.t(activity, "Reset settings"));
            // The wording the backup row actually uses. This list said "Undo last restore" long
            // after the row started saying "or reset" too, which kept a dead tsv row alive.
            strings.add(L10n.t(activity, "Undo last restore or reset"));
            strings.add(L10n.t(activity, "Copy build details"));
            strings.add(L10n.t(activity, "Save build details"));
            strings.add(L10n.t(activity, "Build details copied to the clipboard"));
        }
        return strings;
    }

    private static void collect(Preference preference, Set<String> into) {
        add(preference.getTitle(), into);
        add(preference.getSummary(), into);
        if (preference instanceof ListPreference) {
            ListPreference list = (ListPreference) preference;
            add(list.getDialogTitle(), into);
            if (list.getEntries() != null) {
                for (CharSequence entry : list.getEntries()) {
                    add(entry, into);
                }
            }
        }
        if (preference instanceof PreferenceGroup) {
            PreferenceGroup group = (PreferenceGroup) preference;
            for (int i = 0; i < group.getPreferenceCount(); i++) {
                collect(group.getPreference(i), into);
            }
        }
    }

    private static void add(CharSequence text, Set<String> into) {
        if (text != null && text.length() > 0 && !"%s".contentEquals(text)) {
            into.add(text.toString());
        }
    }

    private static void setEveryStatus(boolean value) throws Exception {
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                field.setBoolean(null, value);
            }
        }
    }
}
