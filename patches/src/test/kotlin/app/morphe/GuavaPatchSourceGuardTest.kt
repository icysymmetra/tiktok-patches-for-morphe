package app.morphe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundle runs on Manager's Guava, which can be an older version than the one the build
 * compiles against (33.5.0 vs 33.7.2 at the time of writing). A patch that calls a method
 * added in a newer Guava would compile and pass every test here, then crash at runtime on
 * Manager's copy. This test makes that gap visible before it ships.
 */
class GuavaPatchSourceGuardTest {
    @Test
    fun patchSourcesDoNotImportGuavaDirectly() {
        // Gradle runs this from patches/, an IDE often from the repository root. It used to look
        // only for patches/src/main and return when that was missing, which under Gradle was
        // always: the check passed without reading a file.
        val root = checkNotNull(listOf(File("src/main"), File("patches/src/main")).firstOrNull { it.isDirectory }) {
            "found no patch sources to check from ${File(".").absoluteFile}"
        }
        val checked = root.walk().count { it.extension == "kt" || it.extension == "java" }
        assertTrue("found only $checked patch sources under $root", checked > 100)
        val violations = root.walk()
            .filter { it.extension == "kt" || it.extension == "java" }
            .flatMap { file ->
                file.readLines()
                    .filter { it.trimStart().startsWith("import com.google.common") }
                    .map { "${file.relativeTo(root)}: ${it.trim()}" }
            }
            .toList()
        assertTrue(
            "Patch sources import Guava directly. The bundle runs on Manager's Guava, " +
                "which may be an older version than the compile classpath. Use the standard " +
                "library or the patcher's own API instead.\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }
}
