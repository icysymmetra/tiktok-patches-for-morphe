package app.morphe.patches.tiktok.misc.theme

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import app.morphe.patcher.patch.PatchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/** The sheet fills live in style literals, not in colors.xml, and only the dark ones move. */
class AmoledStyleItemsTest {
    @Test
    fun `dark sheet literals take the colour and the light and translucent ones stay`() {
        val styles = parse(
            """
            <resources>
              <style name="a9z"><item name="agm">#ff282828</item><item name="c3">#FF252525</item></style>
              <style name="zb"><item name="agm">#ff1e1e1e</item><item name="c3">#ffffffff</item></style>
              <style name="z6"><item name="dy">#1effffff</item><item name="agm">?attr/aut</item></style>
              <style name="a_0"><item name="agm">#1d2026</item></style>
              <style name="zf"><item name="aic">#ff1e1e1e</item><item name="axl">#ffffffff</item></style>
              <style name="zl"><item name="aii">#ff1e1e1e</item><item name="aik">#ff2c2c2c</item><item name="agp">#ff1e1e1e</item></style>
              <style name="zd"><item name="bx">#ff121212</item></style>
              <style name="zo"><item name="bx">#ffffffff</item><item name="axr">#fff5f5f5</item><item name="avy">#ffffffff</item></style>
              <style name="a_d"><item name="agp">#1a1e24</item></style>
            </resources>
            """.trimIndent(),
        )

        val changed = rewriteDarkStyleItems(styles, surfaceStyleItems("47.4.3"), "#000000")

        assertEquals(setOf("agm", "c3", "aic", "aii", "bx", "agp"), changed)
        assertEquals(
            listOf(
                "#000000", "#000000", "#000000", "#ffffffff", "#1effffff", "?attr/aut", "#000000", "#000000", "#ffffffff",
                // The long-press panel's fill goes black and its cards (aik) keep their gray. The
                // settings pages' cards (agp) go black in every dark theme, and light stays white.
                "#000000", "#ff2c2c2c", "#000000", "#000000", "#ffffffff", "#fff5f5f5", "#ffffffff", "#000000",
            ),
            values(styles),
        )
    }

    @Test
    fun `nothing dark to rewrite is reported as nothing changed`() {
        val styles = parse("""<resources><style name="zb"><item name="c3">#ffffffff</item></style></resources>""")
        assertTrue(rewriteDarkStyleItems(styles, surfaceStyleItems("47.4.3"), "#000000").isEmpty())
    }

    /**
     * The patch rewrites every sheet item and refuses a build whose palette it hasn't read, so the
     * palette has to be read for exactly the declared builds: a declared build without one would
     * be refused, and an undeclared one with one would get sheet names nobody checked. On 46.7.3
     * to 46.9.3 aia was UISheetGrouped3's dark value, another tier (the review of a02d0d67).
     */
    @Test
    fun `the palette is read for exactly the declared builds`() {
        assertEquals(setOf("47.4.3"), declaredVersions())
        assertEquals(declaredVersions(), DARK_BACKGROUND_COLORS.keys)
        assertEquals(declaredVersions(), SURFACE_STYLE_ITEMS.keys)
    }

    /** Each build moves the grays to new names, and an older build's names are an accent and overlays. */
    @Test
    fun `the palette is each build's own and a build never read is refused`() {
        assertEquals(setOf("a42", "a44", "a45", "a47", "a4e"), darkBackgroundColors("47.4.3"))
        declaredVersions().forEach { assertTrue("no palette for declared $it", darkBackgroundColors(it).size == 5) }
        listOf("46.9.3", "47.0.3", "47.1.3", "47.1.4").forEach { version ->
            assertRefused { darkBackgroundColors(version) }
            assertRefused { surfaceStyleItems(version) }
        }
        assertRefused { darkBackgroundColors(null) }
        assertRefused { surfaceStyleItems(null) }
        val refusal = unreadPaletteRefusal("47.1.4")
        assertTrue(refusal, refusal.contains("TikTok 47.1.4") && refusal.contains("nothing was changed"))
        assertTrue(refusal, refusal.endsWith("The palette is known for TikTok 47.4.3."))
        assertTrue(unreadPaletteRefusal(null).contains("this TikTok build"))
    }

    private fun assertRefused(check: () -> Unit) {
        try {
            check()
        } catch (refusal: PatchException) {
            return
        }
        fail("the check accepted what it should have refused")
    }

    @Test
    fun `a dark opaque literal is six or eight hex digits with every channel under 40`() {
        assertTrue(isDarkOpaqueLiteral("#282828"))
        assertTrue(isDarkOpaqueLiteral("#FF161823"))
        assertTrue(isDarkOpaqueLiteral(" #ff3f3f3f "))
        assertFalse(isDarkOpaqueLiteral("#404040"))
        assertFalse(isDarkOpaqueLiteral("#80000000"))
        assertFalse(isDarkOpaqueLiteral("#fff"))
        assertFalse(isDarkOpaqueLiteral("@color/a40"))
        assertFalse(isDarkOpaqueLiteral("?attr/agk"))
    }

    private fun parse(xml: String): Document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(ByteArrayInputStream(xml.toByteArray()))

    private fun values(styles: Document): List<String> {
        val items = styles.getElementsByTagName("item")
        return (0 until items.length).map { (items.item(it) as Element).textContent }
    }
}
