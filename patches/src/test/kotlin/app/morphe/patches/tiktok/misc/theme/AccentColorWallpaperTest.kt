package app.morphe.patches.tiktok.misc.theme

import app.morphe.Fixtures
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Accent color's Follow the wallpaper choice (#163): the opaque reds become references to colors
 * that are the system accent on Android 12 and up, and the see-through reds stay.
 */
class AccentColorWallpaperTest {
    @Test
    fun `only opaque red accent literals become references, and see-through ones, white and danger stay`() {
        val styles = parse(
            """
            <resources>
              <style name="zl"><item name="ah5">#fffe2c55</item><item name="ah6">#99fe2c55</item><item name="ah9">#21fe2c55</item><item name="agt">#ffff4c3a</item></style>
              <style name="a_c"><item name="ah9">#ffffffff</item><item name="ay6">#ffb50030</item></style>
              <style name="zh"><item name="a0x">?attr/ah5</item></style>
              <style name="zn"><item name="aix">#fe2c55</item></style>
            </resources>
            """.trimIndent(),
        )
        val literals = mutableSetOf<String>()

        val changed = recolorAccentItems(styles, accentTokens("47.4.3"), accentRecolor(true, TIKTOK_ACCENT, literals))

        assertEquals("a see-through red still counts as found", setOf("ah5", "ah6", "ah9", "ay6", "aix"), changed)
        assertEquals(
            listOf(
                "@color/hushfeed_accent_fe2c55", "#99fe2c55", "#21fe2c55", "#ffff4c3a", "#ffffffff",
                "@color/hushfeed_accent_b50030", "?attr/ah5", "@color/hushfeed_accent_fe2c55",
            ),
            values(styles),
        )
        assertEquals(setOf("#fffe2c55", "#ffb50030", "#fe2c55"), literals)
    }

    @Test
    fun `without the wallpaper choice the recolor is the preset shade, as before`() {
        val styles = parse("""<resources><style name="zl"><item name="ah5">#fffe2c55</item></style></resources>""")
        val literals = mutableSetOf<String>()

        recolorAccentItems(styles, accentTokens("47.4.3"), accentRecolor(false, "#2D7FF9", literals))

        assertEquals(listOf("#ff2d7ff9"), values(styles))
        assertTrue(literals.isEmpty())
    }

    @Test
    fun `TikTok's red sits on step 500 and lighter reds go lighter on the ramp, darker reds darker`() {
        assertEquals(500, wallpaperStep("#fffe2c55"))
        assertEquals(500, wallpaperStep("#fe2c55"))
        assertEquals(500, wallpaperStep("#fff1204a"))
        val steps = OPAQUE_REDS.associateWith(::wallpaperStep)
        assertTrue("dark-mode text is lighter than the button", steps.getValue(DARK_TEXT) < 500)
        assertTrue("light-mode text is darker than the button", steps.getValue(LIGHT_TEXT) > 500)
        assertTrue("the darkest red is the darkest step", steps.getValue("#ffb50030") == steps.values.max())
        assertTrue("every step is a real step of the ramp", steps.values.all { step -> WALLPAPER_STEPS.any { it.second == step } })
    }

    /**
     * The white label on a primary button keeps TikTok's 3:1 on every shade, taking the ramp's
     * tone as the L* of the color it stands for, and text on black or white stays readable.
     */
    @Test
    fun `the steps keep white labels and both modes' text readable`() {
        val tone = WALLPAPER_STEPS.associate { (tone, step) -> step to tone }
        fun luminance(step: Int): Double = ((tone.getValue(step) + 16.0) / 116.0).pow(3)
        fun contrast(a: Double, b: Double) = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
        assertTrue("the label on the button", contrast(luminance(wallpaperStep("#fffe2c55")), 1.0) >= 3.0)
        assertTrue("dark-mode text on black", contrast(luminance(wallpaperStep(DARK_TEXT)), 0.0) >= 3.0)
        assertTrue("light-mode text on white", contrast(luminance(wallpaperStep(LIGHT_TEXT)), 1.0) >= 3.0)
    }

    @Test
    fun `opaque means six digits or eight starting with ff`() {
        for (opaque in listOf("#fe2c55", "#FFFE2C55", "#fffe2c55", " #fffe2c55 ")) assertTrue(opaque, isOpaqueLiteral(opaque))
        for (see in listOf("#99fe2c55", "#00fe2c55", "#f0fe2c55")) assertFalse(see, isOpaqueLiteral(see))
        assertEquals("@color/hushfeed_accent_fe2c55", wallpaperReference("#FFFE2C55"))
        assertEquals(null, wallpaperReference("#99fe2c55"))
    }

    @Test
    fun `the tables give each red back below Android 12 and the system step on it`() {
        val (plain, system) = wallpaperColorTables(listOf("#fffe2c55", "#fe2c55", "#ffb50030", "#ffff576f", "#99fe2c55"))

        val below = colors(parse(plain))
        val above = colors(parse(system))
        assertEquals("one color per red, the see-through one left out", listOf("hushfeed_accent_b50030", "hushfeed_accent_fe2c55", "hushfeed_accent_ff576f"), below.keys.toList())
        assertEquals(below.keys, above.keys)
        assertEquals("#ffb50030", below.getValue("hushfeed_accent_b50030"))
        assertEquals("@android:color/system_accent1_500", above.getValue("hushfeed_accent_fe2c55"))
        assertEquals("@android:color/system_accent1_${wallpaperStep("#ffb50030")}", above.getValue("hushfeed_accent_b50030"))
        assertTrue(above.values.all { it.matches(Regex("@android:color/system_accent1_(10|50|100|200|300|400|500|600|700|800|900)")) })
    }

    @Test
    fun `an existing table at either path is refused before anything is written`() {
        val res = File.createTempFile("res", "").apply { delete(); mkdirs() }
        try {
            val (plain, system) = wallpaperColorPaths(res)
            assertEquals("values/colors.xml", plain.relativeTo(res).invariantSeparatorsPath)
            assertEquals("values-v31/colors.xml", system.relativeTo(res).invariantSeparatorsPath)
            plain.parentFile.mkdirs()
            plain.writeText("<resources>\n    <color name=\"white\">#ffffffff</color>\n</resources>\n")
            writeWallpaperColors(plain to system, listOf("#fffe2c55"))
            val joined = plain.readText()
            assertTrue("the entry went in beside the APK's own: $joined", joined.contains("<color name=\"white\">#ffffffff</color>\n    <color name=\"hushfeed_accent_fe2c55\">#fffe2c55</color>\n</resources>"))
            assertEquals("one closing tag", 1, Regex("</resources>").findAll(joined).count())
            assertTrue(system.readText().contains("system_accent1_500"))
            assertTrue("a new file for v31 is a whole document", system.readText().startsWith("<?xml"))
            try {
                wallpaperColorPaths(res)
                fail("accepted a second table")
            } catch (refusal: app.morphe.patcher.patch.PatchException) {
                assertTrue(refusal.message, refusal.message!!.startsWith("Accent color:"))
                assertTrue(refusal.message, refusal.message!!.endsWith("Nothing was changed."))
            }
        } finally {
            res.deleteRecursively()
        }
    }

    @Test
    fun `the patch offers the choice as a switch that starts off`() {
        val option = accentColorPatch.options["followWallpaper"]
        assertNotNull(option)
        assertEquals(false, option!!.default)
        assertTrue(accentColorPatch.description!!.contains("wallpaper"))
    }

    /**
     * On every declared build each opaque red the accent tokens hold maps to a step, and the
     * color a style item would get is a name that the two tables define.
     */
    @Test
    fun `every opaque palette red of each declared build is covered by the tables`() {
        Fixtures.forEachDeclared { apk ->
            val table = FixtureResourceTable(apk)
            val reds = accentTokens(Fixtures.versionOf(apk)).flatMap { table.styleValues(it) }
                .filter { isRedLiteral(it) && isOpaqueLiteral(it) }.toSet()
            assertTrue("${apk.name}: opaque reds in the accent palettes", reds.isNotEmpty())
            val (plain, system) = wallpaperColorTables(reds)
            val names = reds.map(::wallpaperColorName).toSet()
            assertEquals(names, colors(parse(plain)).keys)
            assertEquals(names, colors(parse(system)).keys)
        }
    }

    private fun parse(xml: String): Document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(ByteArrayInputStream(xml.toByteArray()))

    private fun values(styles: Document): List<String> {
        val items = styles.getElementsByTagName("item")
        return (0 until items.length).map { (items.item(it) as Element).textContent }
    }

    private fun colors(table: Document): Map<String, String> {
        val colors = table.getElementsByTagName("color")
        return (0 until colors.length).associate {
            val color = colors.item(it) as Element
            color.getAttribute("name") to color.textContent
        }
    }

    private companion object {
        const val DARK_TEXT = "#ffff576f"
        const val LIGHT_TEXT = "#ffe10543"

        /** The opaque reds of the 47.1.4 palettes. */
        val OPAQUE_REDS = listOf(
            "#fffe2c55", "#fff1204a", "#ffb50030", "#ffff939c", "#ffff576f", "#ffff5767", "#ffe60541", "#ffe10543", "#ffff3b5c",
        )
    }
}
