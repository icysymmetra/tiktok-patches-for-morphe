package app.morphe

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every mirror URL in a source notice answers.
 *
 * <p>The notices that cite `github.com/ReVanced/revanced-patches` point at a repository GitHub
 * has blocked under a DMCA notice since 2026-03-24, and CONTRIBUTING keeps those lines as the
 * record of which revision the code came from. The line beside each is the GitLab mirror, which
 * is the one a reader can open. This fetches each distinct mirror once, so the next takedown is
 * found by the suite rather than by a reader, and it is the only test here that needs the
 * network: a machine with none fails it by name rather than passing over it.
 */
class OriginNoticeMirrorsTest {
    @Test
    fun `every mirror cited by a source notice answers 200`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "extensions").isDirectory && File(it, "CONTRIBUTING.md").isFile }
        val mirrors = File(root, "extensions").walkTopDown()
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
            .flatMap { file -> MIRROR.findAll(file.readText()).map { it.value } }
            .toSortedSet()
        assertTrue("no source notice cites a mirror, so the block has nothing beside it", mirrors.isNotEmpty())

        val failures = mirrors.mapNotNull { mirror ->
            val status = runCatching { status(mirror) }.getOrElse { error -> "no answer: ${error.message}" }
            if (status == "200") null else "$status  $mirror"
        }
        assertTrue("mirrors that do not answer 200:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun status(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", "hushfeed-origin-notice-check")
        return try {
            connection.responseCode.toString()
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        val MIRROR = Regex("""https://gitlab\.com/ReVanced/revanced-patches/[^\s"]+""")
    }
}
