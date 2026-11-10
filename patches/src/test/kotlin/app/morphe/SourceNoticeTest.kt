package app.morphe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every Kotlin file in the patch tree opens with a notice saying where it came from.
 *
 * <p>The extension module holds the same rule for its Java. CONTRIBUTING requires a
 * `Forked from:` URL on source derived from another project and requires an existing notice to
 * survive a file being modified or moved, and on 2026-09-15 two files in this tree were found to
 * have been imported with a ReVanced notice and to have lost it since. Morphe's DMCA notice took
 * 702 repositories off GitHub over attribution, so it is a gate rather than a paragraph.
 *
 * <p>The wording is not policed, only its presence and its position: a notice below the imports
 * is one a reader opening the file does not see.
 */
class SourceNoticeTest {
    /**
     * A leading comment carrying one of these is a notice. The URL forms matter: some files open
     * with a credit and an upstream link and no keyword at all, which says where the code came
     * from better than the word "Copyright" on its own does.
     */
    private val originMarkers = listOf(
        "Forked from", "Adapted from", "Copyright", "SPDX-FileCopyrightText",
        "https://github.com/", "https://gitlab.com/",
    )

    /** Below this the scan has stopped finding the tree and the case proves nothing. */
    private val fewestCredibleSources = 90

    @Test
    fun everyPatchSourceSaysWhereItCameFrom() {
        val sources = patchSources()
        assertTrue(
            "only ${sources.size} sources were found, so this case proves nothing",
            sources.size >= fewestCredibleSources,
        )

        val bare = sources.filter { !hasLeadingNotice(it.readText()) }
            .map { it.path.replace('\\', '/').substringAfter("/app/morphe/") }

        assertEquals(
            "these patch sources ship with no word of where they came from. A file derived from " +
                "another project needs a Forked from: URL, and one written here needs the " +
                "Metra notice. If you are moving a file, its old notice moves with it: $bare",
            emptyList<String>(),
            bare,
        )
    }

    /** Whether the file opens with a comment that names an origin. */
    internal fun hasLeadingNotice(source: String): Boolean {
        val text = source.replace("\r\n", "\n").trimStart()
        val comment = when {
            text.startsWith("/*") -> text.substringBefore("*/")
            text.startsWith("//") -> text.lineSequence().takeWhile { it.trimStart().startsWith("//") }
                .joinToString("\n")
            else -> return false
        }
        return originMarkers.any { comment.contains(it) }
    }

    private fun patchSources(): List<File> {
        val root = listOf(File("src/main/kotlin"), File("patches/src/main/kotlin"))
            .firstOrNull { it.isDirectory }
        assertTrue(
            "no patch source tree was found from ${File(".").absolutePath}",
            root != null,
        )
        return root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
    }
}
