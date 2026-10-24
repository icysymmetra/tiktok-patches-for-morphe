package app.morphe.patches.tiktok.misc.theme

import app.morphe.patcher.patch.PatchException
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/** Accent color's palette edit (#84): only the accent reds move, each keeping its alpha and its place. */
class AccentColorItemsTest {
    @Test
    fun `only red accent literals take the color, and white, danger, e7 and references stay`() {
        val styles = parse(
            """
            <resources>
              <style name="zl"><item name="ah5">#fffe2c55</item><item name="ah6">#99fe2c55</item><item name="ah9">#21fe2c55</item><item name="agt">#ffff4c3a</item><item name="al1">#ff00aa5b</item><item name="a40">#fffe2c55</item></style>
              <style name="a_c"><item name="ah9">#ffffffff</item><item name="awh">#ffffffff</item><item name="ay6">#ffb50030</item></style>
              <style name="zh"><item name="a0x">?attr/ah5</item><item name="e7">?attr/a6t</item></style>
              <style name="zn"><item name="aix">#fe2c55</item></style>
            </resources>
            """.trimIndent(),
        )

        val changed = recolorAccentItems(styles, accentTokens("47.4.3"), "#2D7FF9")

        assertEquals(setOf("ah5", "ah6", "ah9", "ay6", "aix"), changed)
        val values = values(styles)
        assertEquals("#ff2d7ff9", values[0])
        assertEquals("the alpha stays", "#99", values[1].take(3))
        assertEquals("the alpha stays", "#21", values[2].take(3))
        assertEquals(
            "danger, e7's green, an unlisted red, white, references and e7 itself stay",
            listOf("#ffff4c3a", "#ff00aa5b", "#fffe2c55", "#ffffffff", "#ffffffff", "?attr/ah5", "?attr/a6t"),
            listOf(values[3], values[4], values[5], values[6], values[7], values[9], values[10]),
        )
        assertTrue("a darker red stays darker: ${values[8]}", hsl(rgbOf(values[8])).l < hsl(0x2D7FF9).l)
        assertEquals("a six-digit literal stays six digits", "#2d7ff9", values[11])
    }

    @Test
    fun `TikTok's own red becomes the color exactly and every shade keeps its distance`() {
        for (preset in ACCENT_PRESETS.values) {
            assertEquals(preset.lowercase(), accentShade("#fe2c55", preset))
            assertEquals("#ff" + preset.drop(1).lowercase(), accentShade("#fffe2c55", preset))
        }
        // TikTok original leaves every 47.1.4 palette literal where it is.
        for (literal in PALETTE_REDS) assertEquals(literal, accentShade(literal, TIKTOK_ACCENT))
        // Dark-mode text is lighter than the button and light-mode text darker, for every preset.
        for (preset in ACCENT_PRESETS.values) {
            val button = hsl(rgbOf(accentShade("#fffe2c55", preset))).l
            assertTrue(preset, hsl(rgbOf(accentShade(DARK_TEXT, preset))).l > button)
            assertTrue(preset, hsl(rgbOf(accentShade(LIGHT_TEXT, preset))).l < button)
        }
    }

    /**
     * TikTok's red carries a white label at 3.7:1, its light-mode text sits on white at 4.5:1 or
     * more and its dark-mode text on black at 7:1 or more. Every preset keeps the label and the
     * text at 3:1 at least, so nothing a preset paints goes unreadable.
     */
    @Test
    fun `every preset keeps white labels and both modes' text readable`() {
        for ((name, preset) in ACCENT_PRESETS) {
            val button = rgbOf(preset)
            assertTrue("$name: white on the button ${contrast(button, 0xffffff)}", contrast(button, 0xffffff) >= 3.0)
            val light = rgbOf(accentShade(LIGHT_TEXT, preset))
            assertTrue("$name: light-mode text on white ${contrast(light, 0xffffff)}", contrast(light, 0xffffff) >= 3.0)
            val dark = rgbOf(accentShade(DARK_TEXT, preset))
            assertTrue("$name: dark-mode text on black ${contrast(dark, 0)}", contrast(dark, 0) >= 3.0)
        }
    }

    @Test
    fun `the presets are TikTok original and five colors, each a solid code`() {
        assertEquals(listOf("TikTok original", "Blue", "Green", "Purple", "Orange", "Teal"), ACCENT_PRESETS.keys.toList())
        assertEquals(TIKTOK_ACCENT, ACCENT_PRESETS.getValue("TikTok original"))
        ACCENT_PRESETS.values.forEach { assertEquals(it, solidAccent(it)) }
    }

    @Test
    fun `a custom color is a solid six digit code, and anything else is refused before any change`() {
        assertEquals("#2D7FF9", checkedAccent("#2d7ff9"))
        assertEquals("#2D7FF9", checkedAccent(" #FF2D7FF9 "))
        assertEquals("#FFAABB", checkedAccent("#ffaabb"))
        for (bad in listOf("#802D7FF9", "#fff", "blue", "", "2D7FF9", "#GGGGGG", "#2D7FF9 ff", null)) {
            try {
                checkedAccent(bad)
                fail("accepted $bad")
            } catch (refusal: PatchException) {
                assertTrue(refusal.message, refusal.message!!.startsWith("Accent color:"))
                assertTrue(refusal.message, refusal.message!!.endsWith("Nothing was changed."))
            }
        }
    }

    @Test
    fun `red literals are told apart from white, green, blue, dark and references`() {
        PALETTE_REDS.forEach { assertTrue(it, isRedLiteral(it)) }
        listOf("#ffffffff", "#ff00aa5b", "#ff2d7ff9", "#ff121212", "#00000000", "?attr/akz", "@color/e7", "#fff")
            .forEach { assertFalse(it, isRedLiteral(it)) }
    }

    /** Like AMOLED's palette, the token names are each build's own, so only a build that was read is patched. */
    @Test
    fun `the tokens are read for exactly the declared builds and any other is refused`() {
        assertEquals(declaredVersions(), ACCENT_TOKENS.keys)
        assertEquals(14, accentTokens("47.4.3").size)
        for (version in listOf("47.1.4", "47.1.3", "47.0.3", "46.9.3", null)) {
            try {
                accentTokens(version)
                fail("accepted $version")
            } catch (refusal: PatchException) {
                assertTrue(refusal.message, refusal.message!!.contains("nothing was changed"))
            }
        }
        assertTrue(unreadAccentRefusal("47.1.4").endsWith("The palette is known for TikTok 47.4.3."))
        assertTrue(incompleteAccentRefusal(setOf("awe", "ah7")).contains("ah7, awe"))
        assertTrue(renamedPathsRefusal(3, "Accent color").startsWith("Accent color: this TikTok APK"))
        assertTrue(renamedPathsRefusal(3).startsWith("AMOLED dark theme: this TikTok APK"))
    }

    private fun rgbOf(literal: String) = literal.trim().removePrefix("#").takeLast(6).toInt(16)

    private fun luminance(rgb: Int): Double {
        fun linear(channel: Int): Double = (channel / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * linear(rgb shr 16 and 0xff) + 0.7152 * linear(rgb shr 8 and 0xff) + 0.0722 * linear(rgb and 0xff)
    }

    private fun contrast(a: Int, b: Int): Double {
        val (light, dark) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    private fun parse(xml: String): Document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(ByteArrayInputStream(xml.toByteArray()))

    private fun values(styles: Document): List<String> {
        val items = styles.getElementsByTagName("item")
        return (0 until items.length).map { (items.item(it) as Element).textContent }
    }

    private companion object {
        /** UITextPrimary in the standard dark palette (aix) and the standard light one (ay6). */
        const val DARK_TEXT = "#ffff576f"
        const val LIGHT_TEXT = "#ffe10543"

        /** Every red literal the 47.1.4 palettes give the accent tokens. */
        val PALETTE_REDS = listOf(
            "#fffe2c55", "#fff1204a", "#ffb50030", "#99fe2c55", "#94f1204a", "#6efe2c55", "#6dfe2c55",
            "#66f1204a", "#4afe2c55", "#49fe2c55", "#45fe2c55", "#40f1204a", "#21fe2c55", "#4ffe2c55",
            "#4af1204a", "#3dfe2c55", "#2efe2c55", "#2bf1204a", "#12fe2c55", "#12f1204a", "#0dfe2c55",
            "#ffff939c", "#ffff576f", "#ffff5767", "#ffe60541", "#ffe10543", "#ffff3b5c",
        )
    }
}
