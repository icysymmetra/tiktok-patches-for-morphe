package app.morphe.extension.tiktok;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Holds the payload to the Android 17 audit, so the next write or the next address is read
 * before it ships rather than after a phone stops doing what it used to.
 *
 * <p>Patched apps inherit TikTok's target, 36 today. Three of Android 17's changes reach injected
 * code when that becomes 37. A {@code static final} field stops being writable through reflection
 * and JNI. Certificate Transparency is enforced for every host unless a network security config
 * opts a domain out, and this bundle ships no resources at all, so there would be nothing to opt
 * out in. Background audio is silenced without a visible activity or a media foreground service.
 *
 * <p>The audit on 2026-09-15 found nothing to change, which is a claim worth keeping true rather
 * than a conclusion to file away. Both lists below carry the verdict for every row, and the
 * counts are asserted exactly: a new write or a new address fails here, with the reason the old
 * ones were judged safe printed beside it.
 *
 * <p>The S22's own compat list, read on 2026-09-15, carries eleven changes gated at target 37 and
 * names neither the static final change nor the background audio one: those are enforced by the
 * runtime and the audio framework rather than through platform_compat. Three of the eleven could
 * reach this bundle and have their verdicts below, each held by a check: {@code
 * THROW_ERROR_FOR_WRITABLE_DCL} (no dex, jar or native library is loaded by the payload; its
 * classes arrive inside TikTok's own installed APK, which is not writable, and Morphe Manager's
 * loading of the bundle happens in Manager's process under Manager's target), {@code
 * OVERRIDDEN_THREAD_START_METHOD} (no class in the payload extends Thread; every thread is a
 * plain Thread handed a Runnable), and {@code ENABLE_DEFAULT_ENCRYPTED_CLIENT_HELLO} (a refused
 * handshake is an SSLHandshakeException, which the transport does not retry and RemoteMedia
 * names, the same answer as Certificate Transparency; the check for that one is
 * {@code MediaBudgetTest.aRefusedHandshakeIsNotRetried}, beside the transport it is about,
 * because this class reads source text and must not load payload classes into the fork it
 * shares with the Robolectric tests). The other eight are NSD, deprecated
 * RenderScript, deprecated data builder constructors, non-thread-local network matching, the
 * MediaStore trash and unreliable-volume changes, and Wear wallpaper scaling; the payload calls
 * none of those APIs. The device half of this target-37 compatibility audit remains open.
 * Android 37.0 {@code google_apis_ps16k} revision 7 on emulator 37.2.12 now boots reliably,
 * and the current signed build launched and played feed videos there under strict 16 KB page
 * settings. That run checked page-size linkage only; it did not force the four compatibility
 * changes on individually and together. {@code ROADMAP.md} carries that remaining acceptance.
 *
 * <p>Not covered here, because source text cannot show it: what the three patches that emit
 * {@code sput} write into. All three take their field from an instruction already in TikTok's own
 * method and two of them refuse to patch unless the host writes that field itself, which is what
 * says the field is not final in the shipped dex. {@code ResumeVideoAfterScrollPatch},
 * {@code PlaybackSpeedPatch} and {@code DisableTelemetryPatch} are the three, and
 * {@code ProgressStore} carries the check.
 */
public class AndroidTargetChangesGuardTest {
    /** A reflective write, and why Android 17 leaves it alone. */
    private static final class ReviewedWrites {
        final String file;
        final int writes;
        final String verdict;

        ReviewedWrites(String file, int writes, String verdict) {
            this.file = file;
            this.writes = writes;
            this.verdict = verdict;
        }
    }

    /**
     * Every reflective field write in the payload, with what it writes to.
     *
     * <p>Safe means the receiver is an object, so the field is an instance field whatever its
     * modifiers say: Android 17 changed what a {@code static final} field accepts, and an
     * instance field is not one.
     */
    private static final List<ReviewedWrites> REVIEWED_WRITES = Arrays.asList(
            new ReviewedWrites("comment/CommentTools.java", 1,
                    "safe: writes pollInfo on a comment model instance, and skips static fields "
                            + "where it reads them"),
            new ReviewedWrites("comment/CommentMenuBlock.java", 4,
                    "safe: fields() rejects static fields before all four writes; the receivers "
                            + "are comment-menu instances or objects this class just created"),
            new ReviewedWrites("featuregatelab/StructuredConfigController.java", 3,
                    "safe: writes an instance this class just built with newInstance, and "
                            + "instanceFields() and isEditable() both drop Modifier.isStatic "
                            + "before anything is written"),
            new ReviewedWrites("feed/SensitiveWarnings.java", 2,
                    "safe: writes the mask and the Aweme the host handed it, and already catches "
                            + "a refusal and reports it as a diagnostic row"),
            new ReviewedWrites("feedfilter/FeedItemsFilter.java", 3,
                    "safe: writes the search results' mItems, friendFeedData and, since #12, the "
                            + "Following feed's items field (mItems up to 46.8.3, items from "
                            + "46.9.3), each an instance field on the response the hook was given"));

    /**
     * Addresses written into the payload, and what is done with them.
     *
     * <p>Nothing here is fetched. Every URL the transport opens arrives from TikTok's own model
     * objects, is required to be https, and is re-resolved and re-checked on every redirect hop,
     * so there is no host list in this repository to opt out of Certificate Transparency even if
     * one were wanted. A CT refusal arrives as an SSLHandshakeException, which is not in
     * MediaBudget.isRetryableTransport, so it is not retried, and RemoteMedia names the exception
     * class in the message a reader sees.
     */
    private static final Map<String, String> REVIEWED_ADDRESSES = new LinkedHashMap<>();

    static {
        REVIEWED_ADDRESSES.put("https://www.tiktok.com/@",
                "not fetched: built for an ACTION_SEND extra handed to another installed app");
        REVIEWED_ADDRESSES.put("https://www.tiktok.com/music/x-",
                "not fetched: built for the clipboard");
        REVIEWED_ADDRESSES.put("https://music.youtube.com/search?q=",
                "not fetched: built for an ACTION_VIEW intent handed to the installed YouTube "
                        + "Music app, which opens it as its own search");
        REVIEWED_ADDRESSES.put("https://github.com/icysymmetra/tiktok-patches-for-morphe",
                "not fetched: the source link on the About row, opened in the reader's browser");
        REVIEWED_ADDRESSES.put("https://",
                "not a host: the scheme itself, put in front of an address that arrived without "
                        + "one before a browser is asked to open it, and asked about before a "
                        + "share link is reused");
        REVIEWED_ADDRESSES.put("https://github.com/icysymmetra/tiktok-patches-for-morphe/blob/main/CHANGELOG.md",
                "not fetched: the full release history link, opened in the reader's browser");
        REVIEWED_ADDRESSES.put("https://github.com/SysAdminDoc/hushfeed/blob/main/LICENSE",
                "not fetched: the button beside the notice, opened in the reader's browser");
        REVIEWED_ADDRESSES.put("https://github.com/MorpheApp/morphe-patches",
                "not fetched: printed inside the notice the Licenses row shows");
        REVIEWED_ADDRESSES.put("https://github.com/MorpheApp/morphe-patches-library",
                "not fetched: printed inside the notice the Licenses row shows");
        REVIEWED_ADDRESSES.put("https://www.gnu.org/licenses/gpl-3.0.html",
                "not fetched: printed inside the notice the Licenses row shows");
        REVIEWED_ADDRESSES.put("https://github.com/icysymmetra/tiktok-patches-for-morphe/issues/new",
                "not fetched: the bug form Report a problem fills in, opened in the reader's "
                        + "browser");
        REVIEWED_ADDRESSES.put("https://github.com/login?return_to=",
                "not fetched or opened: only its length is counted, so Report a problem's link "
                        + "still fits once GitHub's sign-in page wraps it");
        REVIEWED_ADDRESSES.put("https://ko-fi.com/P5P5YOUU7",
                "not fetched: the Support Metra row, opened in the reader's browser");
        REVIEWED_ADDRESSES.put("https://(?:(?:vm|vt)\\\\.tiktok\\\\.com/[A-Za-z0-9]+|(?:www\\\\.|m\\\\.)?"
                        + "tiktok\\\\.com/t/[A-Za-z0-9]{4,20})/?",
                "not a host: the pattern that finds a TikTok short link in copied text. The "
                        + "link it finds is the one TikTok put on the clipboard, and it is opened "
                        + "only when isShort takes its host (vm.tiktok.com, vt.tiktok.com or "
                        + "tiktok.com/t/), over https with the platform's own checks");
    }

    /** Where a java.lang.reflect.Field lands in a local, a field or a parameter. */
    private static final Pattern FIELD_NAMED =
            Pattern.compile("\\bField\\s+(\\w+)\\s*[=,;)]");

    private static final Pattern ADDRESS =
            Pattern.compile("\"(https?://[^\"]*)\"");

    @Test
    public void everyReflectiveWriteInThePayloadHasBeenReadAgainstAndroidSeventeen()
            throws IOException {
        List<Path> sources = payloadSources();
        assertTrue("no payload sources were found to check", sources.size() > 100);

        Map<String, Integer> found = new LinkedHashMap<>();
        for (Path source : sources) {
            String text = read(source);
            int writes = countReflectiveWrites(text);
            if (writes > 0) found.put(relativeName(source), writes);
        }

        Map<String, Integer> reviewed = new LinkedHashMap<>();
        for (ReviewedWrites row : REVIEWED_WRITES) reviewed.put(row.file, row.writes);

        assertEquals("the reflective writes in the payload are not the ones that were read "
                        + "against Android 17. Verdicts on the reviewed ones: " + verdicts()
                        + ". A new write needs its own: a write to a static final field stops "
                        + "working when TikTok targets 37",
                reviewed, found);
    }

    /**
     * A regex that matched nothing would pass the case above without reading a line of it, so the
     * count it is asserting is itself asserted here.
     */
    @Test
    public void theWriteCountIsTheOneTheInventoryWasBuiltFrom() throws IOException {
        int total = 0;
        for (Path source : payloadSources()) {
            total += countReflectiveWrites(read(source));
        }
        int reviewed = REVIEWED_WRITES.stream().mapToInt(row -> row.writes).sum();
        assertEquals("the reviewed total no longer matches what the scan finds", reviewed, total);
        // Nine on 2026-09-19; four comment-menu instance writes were added for #169.
        assertEquals("the audit counted thirteen reflective writes on 2026-10-15", 13, total);
    }

    @Test
    public void everyAddressWrittenIntoThePayloadHasBeenReadAgainstCertificateTransparency()
            throws IOException {
        Set<String> found = new HashSet<>();
        for (Path source : payloadSources()) {
            String text = withoutComments(read(source));
            Matcher address = ADDRESS.matcher(text);
            while (address.find()) {
                // A notice is carried as one string literal per line, so the address at the end
                // of a line arrives with the newline escape still on it. Reviewing
                // "https://example/\n" and "https://example/" as two different addresses would
                // be silly, and the second is the one a reader recognises.
                found.add(address.group(1).replaceAll("(?:\\\\n)+$", ""));
            }
        }

        // Both directions. Asserting only that nothing unreviewed turned up would pass just as
        // happily if the scan found nothing at all, which is what a broken pattern looks like.
        assertEquals("the addresses written into the payload are not the ones that were read "
                        + "against Certificate Transparency. Nothing here fetches a hardcoded host "
                        + "today: every URL the transport opens comes from TikTok's own model "
                        + "objects. The bundle ships no resources either, so a host that needed a "
                        + "network security config opt-out would have nowhere to be opted out. "
                        + "Verdicts on the reviewed ones: " + REVIEWED_ADDRESSES,
                new TreeSet<>(REVIEWED_ADDRESSES.keySet()), new TreeSet<>(found));
    }

    @Test
    public void noPayloadClassSubclassesThreadOrOverridesStart() throws IOException {
        // OVERRIDDEN_THREAD_START_METHOD changes what a Thread subclass that overrides start()
        // gets from the runtime. Every thread in the payload is a plain Thread given a Runnable,
        // so there is no subclass, named or anonymous, whose start() could be the overridden one.
        // (Static start(...) helpers on ordinary classes are not what the change is about.)
        Pattern subclass = Pattern.compile("\\bextends\\s+Thread\\b|\\bnew\\s+Thread\\s*\\([^)]*\\)\\s*\\{");
        List<String> subclasses = new ArrayList<>();
        for (Path source : payloadSources()) {
            if (subclass.matcher(withoutComments(read(source))).find()) {
                subclasses.add(relativeName(source));
            }
        }
        assertEquals("a Thread subclass, or a start() override, reached the payload and needs a "
                + "verdict against OVERRIDDEN_THREAD_START_METHOD", new ArrayList<String>(), subclasses);
    }

    @Test
    public void nothingInThePayloadLoadsCodeFromAPath() throws IOException {
        // THROW_ERROR_FOR_WRITABLE_DCL makes loading a dex, jar or native library out of a
        // writable location an error. The payload loads nothing: its classes are in the APK.
        Pattern loader = Pattern.compile(
                "\\b(DexClassLoader|PathClassLoader|InMemoryDexClassLoader|DelegateLastClassLoader|"
                        + "dalvik\\.system\\.DexFile|System\\.load(Library)?|Runtime\\.getRuntime\\(\\)\\.load)\\b");
        List<String> loaders = new ArrayList<>();
        for (Path source : payloadSources()) {
            Matcher found = loader.matcher(withoutComments(read(source)));
            while (found.find()) loaders.add(relativeName(source) + ": " + found.group());
        }
        assertEquals("the payload started loading code, which needs a verdict against "
                + "THROW_ERROR_FOR_WRITABLE_DCL", new ArrayList<String>(), loaders);
    }

    /** Files.readString is not on the Android bootclasspath this module compiles against. */
    private static String read(Path source) throws IOException {
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }

    /** Writes through a java.lang.reflect.Field, which is what Android 17 changed. */
    static int countReflectiveWrites(String rawSource) {
        String source = withoutComments(rawSource);
        Set<String> fields = new HashSet<>();
        Matcher named = FIELD_NAMED.matcher(source);
        while (named.find()) fields.add(named.group(1));
        if (fields.isEmpty()) return 0;

        int writes = 0;
        for (String field : fields) {
            Matcher write = Pattern.compile(
                    "\\b" + Pattern.quote(field) + "\\.set(?:Boolean|Byte|Char|Double|Float|Int"
                            + "|Long|Short)?\\s*\\(").matcher(source);
            while (write.find()) writes++;
        }
        return writes;
    }

    /**
     * Blanks comments so a write named in prose, or the repository link every file header
     * carries, is not read as code. String literals are left alone, because the address scan
     * above is looking for exactly those. Lengths are preserved so nothing else shifts.
     */
    static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);

            // Step over a literal whole. Every address in this payload has a "//" in it, so a
            // scanner that does not know where a string starts reads "https://www.tiktok.com/@"
            // as a line comment and blanks the rest of the line. That is not a hypothetical: the
            // address case passed on an empty result until it was made to assert both directions.
            if (current == '"' || current == '\'') {
                char quote = current;
                out.append(current);
                index++;
                while (index < source.length()) {
                    char inside = source.charAt(index);
                    out.append(inside);
                    index++;
                    if (inside == '\\' && index < source.length()) {
                        out.append(source.charAt(index));
                        index++;
                        continue;
                    }
                    if (inside == quote || inside == '\n') break;
                }
                continue;
            }

            if (current == '/' && index + 1 < source.length()) {
                char next = source.charAt(index + 1);
                if (next == '/') {
                    while (index < source.length() && source.charAt(index) != '\n') {
                        out.append(' ');
                        index++;
                    }
                    continue;
                }
                if (next == '*') {
                    int end = source.indexOf("*/", index + 2);
                    int stop = end < 0 ? source.length() : end + 2;
                    for (int blank = index; blank < stop; blank++) {
                        out.append(source.charAt(blank) == '\n' ? '\n' : ' ');
                    }
                    index = stop;
                    continue;
                }
            }
            out.append(current);
            index++;
        }
        return out.toString();
    }

    private static String verdicts() {
        return REVIEWED_WRITES.stream()
                .map(row -> row.file + " (" + row.verdict + ")")
                .collect(Collectors.joining("; "));
    }

    private static String relativeName(Path source) {
        String name = source.toString().replace('\\', '/');
        int at = name.indexOf("/app/morphe/extension/tiktok/");
        if (at >= 0) return name.substring(at + "/app/morphe/extension/tiktok/".length());
        at = name.indexOf("/app/morphe/extension/shared/");
        if (at >= 0) return "shared/" + name.substring(at + "/app/morphe/extension/shared/".length());
        return name;
    }

    /**
     * Android's java.util.regex is ICU, which throws on flags the JVM these tests run on accepts.
     * A Pattern built with UNICODE_CHARACTER_CLASS in a static field crashed the settings screen
     * on a phone while every Robolectric test passed. ICU's \d and \w are Unicode already; spell
     * a script-wide class as \p{Nd} or \p{L} instead of asking for the flag.
     */
    @Test
    public void noPatternAsksForAFlagAndroidRefuses() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : payloadSources()) {
            String text = read(source);
            for (String flag : new String[]{"UNICODE_CHARACTER_CLASS", "CANON_EQ"}) {
                if (text.contains("Pattern." + flag)) offenders.add(relativeName(source) + " uses Pattern." + flag);
            }
        }
        assertTrue("Android's regex refuses these flags:\n" + String.join("\n", offenders), offenders.isEmpty());
    }

    /**
     * java.util.function arrived at API 24 and the payload runs on API 23 without core library
     * desugaring. D8 backports static methods such as List.copyOf but never a type, and lint's
     * NewApi check let a Consumer local through into the shipped dex, where every video save on
     * Android 6 would have thrown NoClassDefFoundError. Declare a small interface instead.
     */
    @Test
    public void noPayloadTypeComesFromJavaUtilFunction() throws IOException {
        java.util.regex.Pattern use = java.util.regex.Pattern.compile("java\\.util\\.function\\.[A-Z*]");
        List<String> offenders = new ArrayList<>();
        for (Path source : payloadSources()) {
            if (use.matcher(read(source)).find()) offenders.add(relativeName(source));
        }
        assertTrue("java.util.function is API 24, the payload's floor is 23:\n" + String.join("\n", offenders),
                offenders.isEmpty());
    }
    /** Both trees whose Java ends up in the payload TikTok runs. */
    private static List<Path> payloadSources() throws IOException {
        List<Path> roots = new ArrayList<>();
        for (String candidate : new String[]{
                "src/main/java",
                "extensions/tiktok/src/main/java",
                "../shared/library/src/main/java",
                "extensions/shared/library/src/main/java"}) {
            java.io.File directory = new java.io.File(candidate);
            if (directory.isDirectory()) roots.add(directory.toPath());
        }
        assertTrue("no payload source tree was found from " + new java.io.File(".").getAbsolutePath(),
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
