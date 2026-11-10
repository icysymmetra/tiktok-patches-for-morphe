package app.morphe

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every native library an optimizer patch empties has to be a leaf: nothing left in the APK
 * may name it in its DT_NEEDED list.
 *
 * <p>An emptied .so still exists, at zero bytes, and the linker refuses it with "file offset
 * for the library >= file size: 0 >= 0" when another library depends on it. TikTok's Librarian
 * turns that refusal into an uncaught error. On 2026-09-17 the asset strip emptied
 * libbytemonitor, which libbytebench needs, which the whole video editor loads, and the Create
 * tab killed the app on the S22. This reads the fixture's ELF headers the way the linker does.
 * Without the fixture on the machine it is skipped rather than passed.
 */
class StrippedLibrariesAreLeavesTest {
    @Test
    fun `no emptied library is needed by one that stays`() {
        val apks = fixtures()
        val stripped = strippedLibraryNames()
        // Nine since the feature dexes stopped being emptied (#125); a scan that misreads the strips finds none.
        assertTrue("the scan found too few emptied libraries to mean anything: $stripped", stripped.size > 5)

        // Every retained build, not only the declared target: the release receipt patches all
        // of them, and a library that is a leaf on 46.2.3 may have gained a dependent since.
        val offenders = apks.flatMap { apk ->
            val graph = neededGraph(apk)
            assertTrue("${apk.name} carries too few arm64 libraries to be TikTok", graph.size > 100)
            stripped.flatMap { name ->
                graph.filter { (lib, needed) -> lib !in stripped && name in needed }
                    .keys.sorted().map { "${apk.name}: $name is needed by $it" }
            }
        }
        assertEquals(
            "an emptied library is still linked against, so the library that needs it fails to load",
            emptyList<String>(),
            offenders,
        )
    }

    /** The arm64 libraries named by the optimizer patches, read off their sources. */
    private fun strippedLibraryNames(): Set<String> {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val optimizer = File(root, "app/morphe/patches/tiktok/misc/optimizer")
        assertTrue("could not find the optimizer patches from ${File(".").absolutePath}", optimizer.isDirectory)
        val literal = Regex(""""lib/arm64-v8a/([^"]+\.so)"""")
        return optimizer.walkTopDown().filter { it.extension == "kt" }
            .flatMap { file -> literal.findAll(file.readText()).map { it.groupValues[1] } }
            .toSet()
    }

    /** Every TikTok APK in the fixture directory, the declared target and the retained newer builds. */
    private fun fixtures(): List<File> = Fixtures.apks()

    /** Library name to the names it declares as DT_NEEDED, for every arm64 library in the APK. */
    private fun neededGraph(apk: File): Map<String, List<String>> {
        val graph = mutableMapOf<String, List<String>>()
        ZipFile(apk).use { zip ->
            for (entry in zip.entries()) {
                if (!entry.name.startsWith("lib/arm64-v8a/") || !entry.name.endsWith(".so")) continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                graph[entry.name.substringAfterLast('/')] = neededOf(bytes)
            }
        }
        return graph
    }

    /** DT_NEEDED entries of a 64-bit little-endian ELF, or nothing for anything else. */
    private fun neededOf(data: ByteArray): List<String> {
        if (data.size < 64 || data[0] != 0x7F.toByte() || data[1] != 'E'.code.toByte() || data[4] != 2.toByte()) {
            return emptyList()
        }
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val sectionsAt = buffer.getLong(0x28)
        val sectionSize = buffer.getShort(0x3A).toInt() and 0xFFFF
        val sectionCount = buffer.getShort(0x3C).toInt() and 0xFFFF
        class Section(val type: Int, val offset: Long, val size: Long, val link: Int)
        val sections = (0 until sectionCount).map { index ->
            val at = (sectionsAt + index.toLong() * sectionSize).toInt()
            Section(buffer.getInt(at + 4), buffer.getLong(at + 24), buffer.getLong(at + 32), buffer.getInt(at + 40))
        }
        val dynamic = sections.firstOrNull { it.type == 6 } ?: return emptyList()
        val strings = sections.getOrNull(dynamic.link) ?: return emptyList()
        val names = mutableListOf<String>()
        var at = dynamic.offset.toInt()
        val end = (dynamic.offset + dynamic.size).toInt()
        while (at + 16 <= end) {
            val tag = buffer.getLong(at)
            val value = buffer.getLong(at + 8)
            if (tag == 0L) break
            if (tag == 1L) {
                val start = (strings.offset + value).toInt()
                var stop = start
                while (stop < data.size && data[stop] != 0.toByte()) stop++
                names += String(data, start, stop - start, Charsets.US_ASCII)
            }
            at += 16
        }
        return names
    }
}
