package app.morphe.patches.tiktok.misc.theme

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AMOLED refuses an APK whose resource paths clash with the names Morphe's resource rebuild
 * uses, because the rebuild then drops files and TikTok crashes at launch. It no longer refuses
 * every merged bundle: Morphe Manager's merge keeps TikTok's paths and loses nothing.
 */
class AmoledMergedBundleTest {
    @Test
    fun `TikTok's own short paths never clash, merged by Manager or shipped whole`() {
        // Manager's merge and the universal APK keep paths like res/b/cfz.xml; the decoder writes
        // res/layout/ceo.xml. No archive folder is a decoded folder, so nothing is looked up.
        val aliases = mapOf("res/b/cfz.xml" to "res/layout/ceo.xml", "res/a/a0.xml" to "res/drawable/ac.xml")
        var lookups = 0
        val clashes = renamedPathCollisions(
            entries = aliases.keys,
            decodedDirectories = setOf("res/layout", "res/drawable"),
            aliasOf = { lookups++; aliases.getValue(it) },
            isDecoded = { true },
        )
        assertEquals(emptyList<String>(), clashes)
        assertEquals(0, lookups)
    }

    @Test
    fun `a file kept at its own path is not a clash`() {
        // A universal APK carries Play's res/xml/splits0.xml, which decodes where it already is.
        val clashes = renamedPathCollisions(
            entries = listOf("res/xml/splits0.xml"),
            decodedDirectories = setOf("res/xml"),
            aliasOf = { it },
            isDecoded = { true },
        )
        assertEquals(emptyList<String>(), clashes)
    }

    @Test
    fun `the desktop merge's layout clashes and is refused`() {
        // Entry ac's file moved to res/drawable/a0.xml, where entry a0's file decodes; entry a0's
        // own file, now at res/drawable/o.xml, decodes to res/drawable/a0.xml.
        val aliases = mapOf(
            "res/drawable/a0.xml" to "res/drawable/ac.xml",
            "res/drawable/o.xml" to "res/drawable/a0.xml",
            "res/layout/zz.xml" to "res/layout/ceo.xml",
        )
        val decoded = aliases.values.toSet()
        val clashes = renamedPathCollisions(
            entries = aliases.keys,
            decodedDirectories = setOf("res/drawable", "res/layout"),
            aliasOf = { aliases.getValue(it) },
            isDecoded = { it in decoded },
        )
        assertEquals(listOf("res/drawable/a0.xml"), clashes)
        val message = renamedPathsRefusal(clashes.size)
        assertTrue(message, message.contains("Nothing was changed"))
        assertTrue(message, message.contains("Morphe Manager"))
        assertTrue(message, message.contains(" 1 of their paths "))
    }

    @Test
    fun `a renamed path with nothing decoded at it is not a clash`() {
        val clashes = renamedPathCollisions(
            entries = listOf("res/drawable/a0.xml"),
            decodedDirectories = setOf("res/drawable"),
            aliasOf = { "res/drawable/ac.xml" },
            isDecoded = { false },
        )
        assertEquals(emptyList<String>(), clashes)
    }

    @Test
    fun `every decoded package directory is searched, the main one included`() {
        val resources = Files.createTempDirectory("amoled-roots").toFile()
        try {
            val main = resources.resolve("package_14").apply { resolve("res/drawable").mkdirs() }
            val other = resources.resolve("package_03").apply { resolve("res/anim").mkdirs() }
            resources.resolve("package_99").mkdirs()
            resources.resolve("stray.json").writeText("{}")
            val roots = decodedPackageRoots(main)
            assertEquals(main, roots.first())
            assertEquals(setOf(main, other), roots.toSet())
            assertEquals(2, roots.size)
        } finally {
            resources.deleteRecursively()
        }
    }

    @Test
    fun `a clash in a second package is found`() {
        val resources = Files.createTempDirectory("amoled-second").toFile()
        try {
            val main = resources.resolve("package_14").apply { resolve("res/layout").mkdirs() }
            val other = resources.resolve("package_03")
            other.resolve("res/anim").mkdirs()
            other.resolve("res/anim/a4.xml").writeText("<set/>")
            val roots = decodedPackageRoots(main)
            val directories = decodedResourceDirectories(roots)
            assertEquals(setOf("res/layout", "res/anim"), directories)
            val clashes = renamedPathCollisions(
                entries = listOf("res/anim/a4.xml"),
                decodedDirectories = directories,
                aliasOf = { "res/anim/ae.xml" },
                isDecoded = { name -> roots.any { it.resolve(name).isFile } },
            )
            assertEquals(listOf("res/anim/a4.xml"), clashes)
        } finally {
            resources.deleteRecursively()
        }
    }
}
