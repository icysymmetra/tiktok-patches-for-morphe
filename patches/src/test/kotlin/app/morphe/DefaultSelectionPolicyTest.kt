package app.morphe

import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Morphe Manager's simple mode picks every patch in the default selection, so that is where a
 * patch belongs unless there is a reason a reader should choose it on purpose.
 *
 * <p>A patch in the default selection has to leave TikTok as it ships until the reader turns a
 * switch on, and including it can't break installing, updating, signing in or the region. The
 * switches that already started on when 0.69.0 recommended their patches (Remove feed ads, Show
 * the progress bar and a few more) keep doing so, and their README rows say "On by default". The
 * rest are named here with the reason they stay out, so leaving a new patch out of the default
 * is a decision someone wrote down rather than an accident of its declaration.
 */
class DefaultSelectionPolicyTest {
    private val keptOut = mapOf(
        "Accent color" to "rewrites TikTok's accent color resources while patching, with no switch to take it back",
        "AMOLED dark theme" to "rewrites TikTok's color resources while patching, with no switch to take it back",
        "Block P2P video relay" to "takes the relay's files out of the APK while patching",
        "Change app name" to "changes the name Android shows for the app",
        "Custom launcher icon" to "changes the launcher icon",
        "Feature Gate Recorder" to "a developer tool for reading TikTok's feature gates",
        "Hide Play Store update offer" to "raises the version code, so a lower-coded build won't install over it",
        "Hide the risk control CAPTCHA" to "sits on TikTok's account verification path",
        "Look like the store app" to "answers TikTok's signature and installer checks as the store app",
        "Network request report" to "a research tool that counts every API request, with no switch",
        "Region spoof" to "rewrites every locale and timezone read, sign-in included, and its store region switch is experimental",
        "Remove LIVE extras" to "takes files out of the APK while patching",
        "Remove card scanner assets" to "takes files out of the APK while patching",
        "Remove creation tools" to "takes files out of the APK while patching",
        "Remove unused language packs" to "takes files out of the APK while patching",
        "Run beside the store app" to "registers a cloned package under TikTok's own name",
        "Skip first-launch setup" to "only helps with its switch on, since setup runs before settings can be reached",
        "Trust user certificates" to "lets any user-installed certificate read TikTok's traffic, with no switch",
    )

    private fun shippedPatches() = run {
        val catalog = File("../patches-list.json").takeIf { it.isFile } ?: File("patches-list.json")
        assertTrue("could not find the patch list from ${File(".").absolutePath}", catalog.isFile)
        JsonParser.parseString(catalog.readText()).asJsonObject.getAsJsonArray("patches")
            .map { it.asJsonObject }
    }

    @Test
    fun `every patch outside the default selection has a written reason`() {
        assertTrue("a reason can't be blank", keptOut.values.none { it.isBlank() })
        val patches = shippedPatches()
        val outside = patches.filter { !it.get("use").asBoolean }.map { it.get("name").asString }
        assertEquals(
            "a patch left out of the default selection needs a reason here, and one put back needs its entry removed",
            keptOut.keys.sorted(), outside.sorted(),
        )
    }

    /** The development Patches section states the catalog's selection once. */
    @Test
    fun `the README counts simple mode's selection from the catalog`() {
        val readme = File("../README.md").takeIf { it.isFile } ?: File("README.md")
        assertTrue("could not find the README from ${File(".").absolutePath}", readme.isFile)
        val counts = Regex("""Simple mode in Morphe Manager selects (\d+)""").findAll(readme.readText())
            .map { it.groupValues[1].toInt() }.toList()
        assertEquals("the development Patches section gives the count once", 1, counts.size)
        val picked = shippedPatches().count { it.get("use").asBoolean }
        assertEquals("simple mode's count in the README", listOf(picked), counts)
    }

    /** The catalog is generated, so the declarations are checked too, in case it is stale. */
    @Test
    fun `the declarations agree with the catalog`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        assertTrue("could not find the patch sources from ${File(".").absolutePath}", root.isDirectory)
        val header = Regex(
            """(?:bytecodePatch|resourcePatch|rawResourcePatch)\(\s*\n(?:\s*//[^\n]*\n)*\s*name = "([^"]+)",(.*?)\n\) \{""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val declared = root.walkTopDown().filter { it.extension == "kt" }
            .flatMap { file -> header.findAll(file.readText()).map { it.groupValues[1] to it.groupValues[2] } }
            .toList()
        assertEquals("every shipped patch is found among the declarations",
            shippedPatches().map { it.get("name").asString }.sorted(), declared.map { it.first }.sorted())
        val outside = declared.filter { (_, rest) -> "default = false," in rest }.map { it.first }
        assertEquals("regenerate the catalog: :patches:generatePatchesList", keptOut.keys.sorted(), outside.sorted())
    }
}
