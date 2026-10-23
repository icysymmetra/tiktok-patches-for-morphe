package app.morphe

import com.google.gson.JsonParser
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The patch table in the README against the catalog Morphe Manager actually reads.
 *
 * <p>A patch is chosen by name in Morphe Manager, while its description comes from the generated
 * patch list. The README is the public lookup table before patching, so both fields have to agree
 * with what the Manager presents.
 */
class ReadmePatchNamesTest {
    private data class ReadmeRow(val name: String, val description: String)

    /**
     * Every name a patch declares, and how many patches were found declaring one.
     *
     * <p>The first version of this read the first non-blank line after the factory call and
     * gave up there. Three ordinary shapes walked past it: `description` written before `name`,
     * a KDoc block between the two, and the whole call on one line. The bracket is walked now,
     * from the factory to the parenthesis that closes its argument list, and the count is
     * returned so a factory whose name was not found fails rather than going missing quietly.
     */
    private fun namesIn(source: String): Pair<List<String>, Int> {
        val names = mutableListOf<String>()
        var factories = 0
        var at = 0
        while (true) {
            val factory = FACTORY.find(source, at) ?: break
            at = factory.range.last + 1
            factories++
            val open = factory.range.last
            val close = closingBracket(source, open)
            if (close < 0) continue
            NAME.find(source.substring(open, close))?.let { names.add(it.groupValues[1]) }
        }
        return names to factories
    }

    /**
     * The parenthesis closing the one at [open], skipping literals and comments.
     *
     * <p>Comments have to be skipped, not just literals. A comment in the CAPTCHA patch says
     * "ByteDance's own SDK name", and reading that apostrophe as the start of a character
     * literal swallowed the rest of the file, so the patch appeared to declare no name at all.
     */
    private fun closingBracket(source: String, open: Int): Int {
        var depth = 0
        var at = open
        while (at < source.length) {
            val c = source[at]
            when {
                c == '/' && source.startsWith("//", at) -> {
                    val end = source.indexOf('\n', at)
                    at = if (end < 0) source.length else end
                }
                c == '/' && source.startsWith("/*", at) -> {
                    val end = source.indexOf("*/", at + 2)
                    at = if (end < 0) source.length else end + 1
                }
                c == '"' || c == '\'' -> {
                    at++
                    while (at < source.length) {
                        if (source[at] == '\\') at++ else if (source[at] == c) break
                        at++
                    }
                }
                c == '(' -> depth++
                c == ')' -> if (--depth == 0) return at
            }
            at++
        }
        return -1
    }

    private fun patchSources(): List<String> {
        val root = File("src/main/kotlin").takeIf { it.isDirectory }
            ?: File("patches/src/main/kotlin")
        assertTrue("could not find the patch sources from ${File(".").absolutePath}",
            root.isDirectory)
        return root.walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.toList()
    }

    /** Every patch row in the README, with escaped table pipes restored in its description. */
    private fun readmePatchRows(): List<ReadmeRow> {
        val readme = File("../README.md").takeIf { it.isFile } ?: File("README.md")
        assertTrue("could not find the README from ${File(".").absolutePath}", readme.isFile)
        return readme.readLines().mapNotNull { line ->
            ROW.find(line)?.let { match ->
                ReadmeRow(
                    name = match.groupValues[1],
                    description = match.groupValues[2].trim().replace("\\|", "|"),
                )
            }
        }
    }

    /** Names and descriptions from the generated catalog shipped beside the bundle. */
    private fun shippedPatchDescriptions(): Map<String, String> {
        val catalog = File("../patches-list.json").takeIf { it.isFile }
            ?: File("patches-list.json")
        assertTrue("could not find the patch list from ${File(".").absolutePath}", catalog.isFile)
        val patches = JsonParser.parseString(catalog.readText())
            .asJsonObject.getAsJsonArray("patches")
        return patches.associate { element ->
            val patch = element.asJsonObject
            patch.get("name").asString to patch.get("description").asString.trim()
        }
    }

    @Test
    fun `the README patch table and shipped catalog say the same thing`() {
        var factories = 0
        val declared = mutableSetOf<String>()
        patchSources().forEach { source ->
            val (names, count) = namesIn(source)
            declared += names
            factories += count
            assertEquals("a patch factory declares no name, so nothing can look it up",
                count, names.size)
        }
        val rows = readmePatchRows()
        val listed = rows.map { it.name }.toSet()
        val shipped = shippedPatchDescriptions()
        assertTrue("the scan found no patches", factories > 50)
        assertEquals("the README has a duplicate patch row", rows.size, listed.size)

        assertEquals(
            "the README lists a patch by a name no patch carries, so a reader cannot find it " +
                "in Morphe Manager",
            emptyList<String>(),
            (listed - declared).sorted(),
        )
        assertEquals(
            "a patch ships with no row in the README table",
            emptyList<String>(),
            (declared - listed).sorted(),
        )
        assertEquals(
            "the generated patch list and patch sources name different patches",
            declared.sorted(),
            shipped.keys.sorted(),
        )
        assertEquals(
            "a README description differs from the description shipped to Morphe Manager",
            shipped.toSortedMap(),
            rows.associate { it.name to it.description }.toSortedMap(),
        )
    }

    @Test
    fun `dev target and stable release are described separately`() {
        val root = File("..").takeIf { File(it, "README.md").isFile } ?: File(".")
        val readme = File(root, "README.md").readText()
        val index = JsonParser.parseString(File(root, "patches-bundle.json").readText()).asJsonObject
        val version = index.get("version").asString
        val download = index.get("download_url").asString
        assertTrue(download.endsWith("/v$version/patches-$version.mpp"))
        assertTrue(download.startsWith("https://github.com/icysymmetra/tiktok-patches-for-morphe/"))
        assertTrue(readme.contains("global TikTok " + Fixtures.declaredVersions().single()))
        assertTrue(readme.contains("The stable release still targets 46.2.3."))
        assertTrue(readme.contains("Android runtime testing is pending."))
        assertTrue(readme.contains("Morphe Manager 2.0.0 or newer"))
        assertTrue(readme.contains("enable prerelease source updates"))
    }

    @Test
    fun `the introduction distinguishes the development catalog from a release claim`() {
        val root = File("..").takeIf { File(it, "README.md").isFile } ?: File(".")
        val readme = File(root, "README.md").readText()
        assertTrue(readme.contains("The table below describes this dev prerelease."))
        assertTrue(shippedPatchDescriptions().size >= 135)
        val retiredRoute = Regex("""Metra settings\s*>\s*(?:Feed filter|Feed tabs|Feed screen|Privacy|Playback|Screen time|Inbox|App(?! behavior)|Comments(?! and translation)|Region(?! spoof))(?=[., >]|$)""")
        assertTrue("a shipped description points to a retired Metra menu",
            shippedPatchDescriptions().values.none { retiredRoute.containsMatchIn(it) })
        assertTrue(readme.contains("Hushfeed v0.76.0"))
        assertTrue(readme.contains("b68741ca02898c2003751da75df34b44b8943daa"))
    }

    /**
     * The declaration shapes this scan has already been blind to, put in front of it.
     *
     * <p>Every patch here happens to be written the same way, so a green run over the real
     * tree says nothing about what the scan would find in one written differently.
     */
    @Test
    fun `the patch name scan reads every shape a declaration comes in`() {
        val shapes = mapOf(
            "name on the line below" to """
                val a = bytecodePatch(
                    name = "Do a thing",
                    description = "x",
                ) { }
            """.trimIndent(),
            "description written first" to """
                val a = bytecodePatch(
                    description = "x",
                    name = "Do a thing",
                ) { }
            """.trimIndent(),
            "a KDoc block above the name" to """
                val a = bytecodePatch(
                    /** Why this name and not the other. */
                    name = "Do a thing",
                ) { }
            """.trimIndent(),
            "a line comment above the name" to """
                val a = bytecodePatch(
                    // Why this name and not the other.
                    name = "Do a thing",
                ) { }
            """.trimIndent(),
            "an apostrophe in a comment above the name" to """
                val a = bytecodePatch(
                    // ByteDance's own name for it means nothing to a reader.
                    name = "Do a thing",
                ) { }
            """.trimIndent(),
            "the whole call on one line" to """val a = bytecodePatch(name = "Do a thing") { }""",
            "a resource patch" to """
                val a = resourcePatch(
                    name = "Do a thing",
                ) { }
            """.trimIndent(),
        )

        shapes.forEach { (shape, source) ->
            val (names, factories) = namesIn(source)
            assertEquals("$shape: wrong number of factories", 1, factories)
            assertEquals("$shape: the name was not read", listOf("Do a thing"), names)
        }

        // A fingerprint's own name sits inside a different call and must not be collected.
        val (names, factories) = namesIn(
            """
            private object Fp : Fingerprint(
                name = "someHostMethod",
            )
            val a = bytecodePatch(
                name = "Do a thing",
            ) { }
            """.trimIndent(),
        )
        assertEquals(1, factories)
        assertEquals(listOf("Do a thing"), names)
    }

    private companion object {
        val FACTORY = Regex("""\b(?:bytecodePatch|resourcePatch|rawResourcePatch)\s*\(""")
        val NAME = Regex("""(?:^|[(,\s])name\s*=\s*"([^"]+)"""")

        // The table rows are the only lines that open with a pipe and a backticked name. The
        // credits further down name patches in prose, which is not a claim about the table.
        val ROW = Regex("""^\|\s*`([^`]+)`\s*\|\s*(.*?)\s*\|$""")
    }
}
