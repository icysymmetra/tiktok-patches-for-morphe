package app.morphe

import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import org.junit.Assert.*
import org.junit.Test

/** Product ownership checks for Metra's existing artwork and destinations. */
class MarketingHeroTest {
    private val root = File("..").canonicalFile.takeIf { File(it, "README.md").isFile }
        ?: File(".").canonicalFile

    @Test fun `the README and source icon retain the existing Metra artwork`() {
        val readme = File(root, "README.md").readText()
        assertTrue(readme.trimStart().startsWith("<p align=\"center\">"))
        assertTrue(readme.contains("assets/readme-header.png"))
        assertFalse(readme.contains("assets/readme-hero.png"))
        val expected = HexFormat.of().parseHex("35be990bfa8a1252b8eb8383c8338f95eecbe1a4ef47ec649ad4e5041a22fd38")
        for (name in listOf("patches-bundle.png", "assets/readme-header.png")) {
            assertArrayEquals(name, expected, MessageDigest.getInstance("SHA-256").digest(File(root, name).readBytes()))
        }
    }

    @Test fun `source install and support destinations belong to Metra`() {
        val readme = File(root, "README.md").readText()
        val canonical = "https://morphe.software/add-source?github=icysymmetra%2Ftiktok-patches-for-morphe"
        val links = Regex("""https://morphe\.software/add-source\?github=[^)\s"'<>]+""").findAll(readme).map { it.value }.toList()
        assertTrue(links.isNotEmpty())
        // Both encoded and literal slashes are valid in the query's repository value.
        assertTrue(links.all {
            java.net.URLDecoder.decode(it, "UTF-8") == java.net.URLDecoder.decode(canonical, "UTF-8")
        })
        assertTrue(readme.contains("paste `https://github.com/icysymmetra/tiktok-patches-for-morphe`"))
        assertTrue(readme.contains("https://ko-fi.com/P5P5YOUU7"))
        assertFalse(readme.contains("ko-fi.com/X8K126YVER"))
        val index = JsonParser.parseString(File(root, "patches-bundle.json").readText()).asJsonObject
        assertTrue(index.get("download_url").asString.startsWith("https://github.com/icysymmetra/tiktok-patches-for-morphe/releases/download/"))
    }
}
