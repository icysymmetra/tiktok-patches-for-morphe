package app.morphe.extension.tiktok;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Every Java file the payload ships opens with a notice saying where it came from.
 *
 * <p>CONTRIBUTING requires a {@code Forked from:} URL on source derived from another project, and
 * requires that an existing notice is preserved when a file is modified or moved. Neither is
 * something a reader can check by eye across two hundred files, and on 2026-09-15 four files were
 * found to have been imported with a ReVanced notice and to have lost it since, three of them in
 * this fork. Morphe's own DMCA notice took 702 repositories off GitHub over attribution, so the
 * rule is worth a gate rather than a paragraph.
 *
 * <p>What counts is deliberately loose: any leading comment naming an origin. This is not trying
 * to police the wording, only to stop a file shipping with nothing at all. The one thing it is
 * strict about is that the notice comes first, because a notice below the imports is one a reader
 * opening the file does not see.
 */
public class SourceNoticeTest {
    /**
     * A leading comment carrying one of these is a notice.
     *
     * <p>{@code SPDX-FileCopyrightText} is here because one file states its origin that way, and
     * a rule that would have made somebody delete a real notice to satisfy it is a bad rule. The
     * two URL forms are here for the same reason: the lyyako ports open with a credit and the
     * icysymmetra URL and no keyword at all, which is a better notice than several that would
     * have passed on the word "Copyright" alone. A link to where the code came from is the thing
     * this case is actually asking for.
     */
    private static final List<String> ORIGIN_MARKERS = Arrays.asList(
            "Forked from", "Adapted from", "Copyright", "SPDX-FileCopyrightText",
            "https://github.com/", "https://gitlab.com/");

    /**
     * Below this the scan has stopped finding the tree and the case proves nothing. The two trees
     * held 213 and some files on 2026-09-15; the floor is well under that so ordinary deletions
     * do not trip it.
     */
    private static final int FEWEST_CREDIBLE_SOURCES = 150;

    @Test
    public void everyFileThePayloadShipsSaysWhereItCameFrom() throws IOException {
        List<Path> sources = payloadSources();
        assertTrue("only " + sources.size() + " sources were found, so this case proves nothing",
                sources.size() >= FEWEST_CREDIBLE_SOURCES);

        List<String> bare = new ArrayList<>();
        for (Path source : sources) {
            if (!hasLeadingNotice(read(source))) bare.add(relativeName(source));
        }

        assertEquals("these files ship with no word of where they came from. A file derived from "
                        + "another project needs a Forked from: URL, and one written here needs "
                        + "the Metra notice. If you are moving a file, its old notice moves "
                        + "with it: " + bare,
                List.<String>of(), bare);
    }

    /** Whether the file opens with a comment that names an origin. */
    static boolean hasLeadingNotice(String source) {
        String text = source.replace("\r\n", "\n").stripLeading();
        String comment;
        if (text.startsWith("/*")) {
            int end = text.indexOf("*/");
            comment = end < 0 ? text : text.substring(0, end);
        } else if (text.startsWith("//")) {
            // Consecutive line comments, which is how an SPDX header is usually written.
            StringBuilder run = new StringBuilder();
            for (String line : text.split("\n")) {
                if (!line.stripLeading().startsWith("//")) break;
                run.append(line).append('\n');
            }
            comment = run.toString();
        } else {
            return false;
        }
        return ORIGIN_MARKERS.stream().anyMatch(comment::contains);
    }

    /** Files.readString is not on the Android bootclasspath this module compiles against. */
    private static String read(Path source) throws IOException {
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }

    private static String relativeName(Path source) {
        String name = source.toString().replace('\\', '/');
        int at = name.indexOf("/app/morphe/");
        return at >= 0 ? name.substring(at + "/app/morphe/".length()) : name;
    }

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
