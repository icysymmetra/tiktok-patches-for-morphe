package app.morphe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every name R8 or aapt2 made up for one TikTok build, written into this repository as though it
 * were the name of something.
 *
 * <p>The ROADMAP's first gate for this was a grep for `LX/xxxx;` class names, and it missed
 * nearly everything of the same kind: method and field names passed as strings (`"LIZ"`,
 * `"LJFF"`), short member names compared by `name ==`, prefixes (`startsWith("LX/")`), merged
 * lambda groups (`LY/...;`), Java binary names (`X.0GSy`) and shortened resource entry names
 * (`o1k`, `a3y`, `kzj`). Each of them is right on 46.2.3 and means something else, or nothing,
 * on the next build, and a patch that applies with one of them in it can do nothing or do the
 * wrong thing without a word.
 *
 * <p>What is here today is recorded in `obfuscated-identities.txt`, one `path|name` per line,
 * so that the list can only shrink: a name that turns up and is not in it fails this test, and
 * so does a line in it that no longer matches anything, so the record stays the truth.
 * Anchoring a patch on what a class is, rather than on what one build called it, is the fix for
 * each line; adding a line is for a name that genuinely has nothing else to go on, and says so
 * in the commit.
 */
class ObfuscatedIdentityTest {
    @Test
    fun `java binary class names are inventoried as exact tokens`() {
        assertEquals(
            setOf("X.0Lze"),
            identitiesIn("""Class.forName("X.0Lze")""", extensionTree = true),
        )
        assertTrue(
            identitiesIn("""Class.forName("LX.0Lze")""", extensionTree = true).isEmpty(),
        )
    }

    @Test
    fun `short resource names containing underscores are inventoried`() {
        assertEquals(
            setOf("p_5"),
            identitiesIn("RESOURCE_NAME = \"p_5\"", extensionTree = true),
        )
    }

    /** A view id written for one build (`"47.0.3:g6r"`) is still that build's made-up name. */
    @Test
    fun `short resource names written for one build are inventoried`() {
        assertEquals(
            setOf("g6r", "g85", "i_l"),
            identitiesIn("""LIKE_IDS = {"47.0.3:g6r", "47.1.3:g85", "47.1.3:i_l"};""", extensionTree = true),
        )
        assertEquals(setOf("a5u"), identitiesIn("""val x = "47.1.3:a5u"""", extensionTree = false))
    }

    @Test
    fun `no name one build made up is written down that the record does not already hold`() {
        val found = scan()
        val recorded = recorded()
        val added = found - recorded
        val gone = recorded - found
        assertTrue(
            "New names a single build made up, written as identities. Anchor on what the thing " +
                "is instead, or record the line with a reason:\n" + added.joinToString("\n"),
            added.isEmpty(),
        )
        assertTrue(
            "Recorded names that are no longer in the code. Delete these lines so the record " +
                "keeps saying what is left:\n" + gone.joinToString("\n"),
            gone.isEmpty(),
        )
    }

    /**
     * The view ids in this record are the ones `RuntimeViewIdAnchorsTest` holds against the target
     * build through `view-id-anchors.txt`. A short name the extension resolves that the table
     * doesn't list would still resolve on the phone, as whatever view the new build gave it.
     */
    @Test
    fun `every view id recorded for the extension is in the anchor table`() {
        val text = checkNotNull(javaClass.getResourceAsStream("/view-id-anchors.txt")) {
            "view-id-anchors.txt is missing from the test resources"
        }.bufferedReader().readText()
        val anchored = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .flatMap { line ->
                val fields = line.split('|')
                fields[2].split(',').map { "$EXTENSION/${fields[0]}|${it.substringAfter(':')}" }
            }.toSet()
        val viewIds = recorded().filter { it.startsWith("$EXTENSION/") }.filterNot { line ->
            val name = line.substringAfter('|')
            MEMBER_NAME.matches(name) || JAVA_BINARY_TYPE.matches(name) || '/' in name
        }
        assertTrue("the record holds no view id for the extension, so this compares nothing", viewIds.isNotEmpty())
        val unanchored = viewIds - anchored
        assertTrue(
            "Recorded view ids the anchor table doesn't list. Look them up through a constant and " +
                "give it a line in view-id-anchors.txt:\n" + unanchored.joinToString("\n"),
            unanchored.isEmpty(),
        )
    }

    private fun recorded(): Set<String> {
        val text = javaClass.getResourceAsStream("/obfuscated-identities.txt")
            ?.bufferedReader()?.readText().orEmpty()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
    }

    private fun scan(): Set<String> {
        val repo = if (File("src/main/kotlin").isDirectory) File("..") else File(".")
        val patches = File(repo, "patches/src/main/kotlin")
        val extension = File(repo, "extensions/tiktok/src/main/java")
        assertTrue("could not find the sources from ${File(".").absolutePath}", patches.isDirectory && extension.isDirectory)

        val found = sortedSetOf<String>()
        for ((root, extensionTree) in listOf(patches to false, extension to true)) {
            root.walkTopDown().filter { it.extension == "kt" || it.extension == "java" }.forEach { file ->
                val path = file.relativeTo(repo).invariantSeparatorsPath
                file.readLines().forEach { raw ->
                    val line = raw.trim()
                    if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) return@forEach
                    identitiesIn(line, extensionTree).forEach { found.add("$path|$it") }
                }
            }
        }
        return found
    }

    private fun identitiesIn(line: String, extensionTree: Boolean): Set<String> {
        val names = mutableSetOf<String>()
        OBFUSCATED_TYPE.findAll(line).forEach { names.add(it.value) }
        JAVA_BINARY_TYPE.findAll(line).forEach { names.add(it.value) }
        MEMBER_LITERAL.findAll(line).forEach { names.add(it.groupValues[1]) }
        PREFIX_LITERAL.findAll(line).forEach { names.add(it.groupValues[1]) }
        SHORT_MEMBER_COMPARED.findAll(line).forEach { names.add(it.groupValues[1]) }
        SHORT_RESOURCE.findAll(line).forEach { names.add(it.groupValues[1]) }
        if (extensionTree && RESOURCE_LOOKUP.containsMatchIn(line)) {
            RESOURCE_NAME.findAll(line).forEach { names.add(it.groupValues[1]) }
        }
        return names - REAL_NAMES
    }

    private companion object {
        const val EXTENSION = "extensions/tiktok/src/main/java/app/morphe/extension/tiktok"

        /** A whole name of the shape [MEMBER_LITERAL] finds: `LIZ`, `LJFF`, `LL`. */
        val MEMBER_NAME = Regex("""L[IJLZF]{1,19}""")

        /** A class R8 named for one build: `LX/0sIr;`, or a merged lambda group `LY/...;`. */
        val OBFUSCATED_TYPE = Regex("""L[XY]/[0-9A-Za-z_$]+;""")

        /** The Java binary spelling of an R8 class name: `X.0GSy`. */
        val JAVA_BINARY_TYPE = Regex("""(?<![0-9A-Za-z_$])X\.[0-9A-Za-z]{4}(?![0-9A-Za-z_$])""")

        /** A member name of the shape R8 gives TikTok's: `"LIZ"`, `"LJFF"`, `"LLJJIJIIJIL"`. */
        val MEMBER_LITERAL = Regex(""""(L[IJLZF]{1,19})"""")

        /** A prefix standing in for such names: `"LX/"`, `Lkotlin/jvm/internal/AwS...`. */
        val PREFIX_LITERAL = Regex(""""(LX/|LY/|Lkotlin/jvm/internal/A[A-Za-z]{1,3}S)""")

        /** A short name held against a member's: `name == "XN"`, `it.name == "bq"`. */
        val SHORT_MEMBER_COMPARED = Regex("""name\s*==\s*"([A-Za-z][A-Za-z0-9]{0,2})"""")

        /**
         * A shortened resource entry name of the letter-digit kind: `"a3y"`, `"o1k"`, `"ht9"`, also
         * when it is written for one build, `"47.1.3:a5u"`.
         */
        val SHORT_RESOURCE = Regex(""""(?:\d+(?:\.\d+)+:)?([a-z][0-9][a-z0-9]{1,2}|[a-z]{2}[0-9])"""")

        /** A line that looks a view or resource up by name, in the extension. */
        val RESOURCE_LOOKUP = Regex("""_IDS?\b|RESOURCE_NAME|getIdentifier\(|\.resolve\(|IDS\b|viewId\(|idOf\(""")

        /** A two or three character name on such a line: `"kzj"`, `"fb"`, `"p_5"`, `"47.1.3:i_l"`. */
        val RESOURCE_NAME = Regex(""""(?:\d+(?:\.\d+)+:)?([a-z][a-z0-9_]{1,2})"""")

        /**
         * Names that match a shape above and are nobody's invention: framework and interface
         * members compared by name, the resource types handed to a lookup, and file extensions.
         */
        val REAL_NAMES = setOf("get", "put", "run", "key", "id", "raw", "mp3", "mp4", "m4a", "m4v", "jpg", "png")
    }
}
