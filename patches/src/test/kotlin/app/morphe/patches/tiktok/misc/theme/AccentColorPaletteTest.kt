package app.morphe.patches.tiktok.misc.theme

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which colors Accent color recolors, held to each declared build's fixture (#84).
 *
 * <p>TikTok's TUX token table (one obfuscated method, a chain of `"Name".equals(token)` checks
 * that each return an attr id) names the semantic attrs its views read. UIShapePrimary to
 * UIShapePrimary5, UITextPrimary and UITextPrimaryDisplay are the accent: the themes point each at
 * a palette token, one for the dark themes and one for the light ones, and the palette styles set
 * those tokens to TikTok's red at several strengths. The patch rewrites the palette literals, so
 * every semantic attr that points at one changes with it, and only these seven may point there.
 */
class AccentColorPaletteTest {
    @Test
    fun `each declared build's accent tokens are what the token table's accent attrs point at`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val table = FixtureResourceTable(apk)
            val named = tokenTable(apk)
            val tokens = ACCENT_TOKENS.getValue(version)
            val accentAttrs = mutableSetOf<String>()
            for ((token, palette) in tokens) {
                val attr = table.attrNames[named[token] ?: error("$version: the token table has no $token")]
                    ?: error("$version: $token's attr isn't in the resource table")
                accentAttrs += attr
                // A few themes send UIShapePrimary and UITextPrimaryDisplay to e7, their own green.
                assertEquals(
                    "$version: what the themes point $token ($attr) at",
                    setOf("?attr/${palette.first}", "?attr/${palette.second}"),
                    table.styleValues(attr).toSet() - "?attr/e7",
                )
            }

            val recolored = accentTokens(version)
            for (name in recolored) {
                val values = table.styleValues(name)
                assertTrue("$version: $name has no red to recolor: $values", values.any(::isRedLiteral))
                assertTrue(
                    "$version: $name holds a value that is neither red nor white: $values",
                    values.all { isRedLiteral(it) || it.equals("#ffffffff", ignoreCase = true) },
                )
            }

            // Only the seven accent attrs point at the recolored tokens.
            val pointing = table.styles.flatMap { style ->
                style.items.filter { it.type == FixtureResourceTable.TYPE_ATTRIBUTE && table.attrNames[it.data] in recolored }
                    .map { table.attrNames[it.attr] ?: Integer.toHexString(it.attr) }
            }.toSet()
            assertEquals("$version: attrs that point at an accent token", accentAttrs, pointing)

            // Warnings keep their red, and e7 keeps its green.
            val danger = table.attrNames.getValue(named.getValue("UIShapeDanger"))
            val dangerTokens = table.styleValues(danger).map { it.removePrefix("?attr/") }.toSet()
            assertTrue("$version: UIShapeDanger points at $dangerTokens", dangerTokens.isNotEmpty())
            assertTrue("$version: a danger token is recolored", dangerTokens.none { it in recolored })
            assertTrue("$version: e7 is recolored", "e7" !in recolored)
        }
    }

    @Test
    fun `each declared build's red icons are the feed avatar's follow plus and turn the accent`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val table = FixtureResourceTable(apk)
            val avatarLiterals = classLiterals(apk, "Lcom/ss/android/ugc/aweme/feed/assem/avatar/FeedAvatarDefaultAssem;")
            for (name in accentIcons(version)) {
                val id = table.drawableIds[name] ?: error("$version: no drawable $name")
                assertTrue("$version: the feed avatar never sets $name", id in avatarLiterals)
                val paths = table.drawableFiles[name].orEmpty()
                assertTrue("$version: $name's files are $paths", paths.isNotEmpty() && paths.all { it.endsWith(".png") })
                ZipFile(apk).use { zip ->
                    for (path in paths) {
                        val original = zip.getInputStream(zip.getEntry(path)).use { it.readBytes() }
                        val recolored = recolorRedPalette(original, "#2D7FF9") ?: error("$version: $path has no red palette")
                        assertEquals("$version: $path changes size", original.size, recolored.size)
                        val before = ImageIO.read(ByteArrayInputStream(original))
                        val after = ImageIO.read(ByteArrayInputStream(recolored))
                        var reds = 0
                        for (x in 0 until before.width) for (y in 0 until before.height) {
                            val was = before.getRGB(x, y)
                            val now = after.getRGB(x, y)
                            assertEquals("$version: $path alpha at $x,$y", was ushr 24, now ushr 24)
                            if (was ushr 24 == 0) continue
                            if (isRedLiteral(String.format("#%06x", was and 0xffffff))) reds++
                            assertTrue(
                                "$version: $path keeps red at $x,$y: ${Integer.toHexString(now)}",
                                !isRedLiteral(String.format("#%06x", now and 0xffffff)),
                            )
                            if (was and 0xffffff == 0xffffff) assertEquals("$version: $path white at $x,$y", 0xffffff, now and 0xffffff)
                        }
                        assertTrue("$version: $path had only $reds red pixels", reds > before.width * before.height / 2)
                        // The circle's fill above the plus is the accent itself.
                        assertEquals(0x2D7FF9, after.getRGB(before.width / 2, before.height / 8) and 0xffffff)
                    }
                }
            }
        }
    }

    /**
     * #163: with Follow the wallpaper the follow plus stayed TikTok's red, since a picture can't take
     * a color the phone picks later. It splits into a white layer and a red one the phone tints, and
     * the tinted layer drawn over the white one is the same picture the fixed colors make.
     */
    @Test
    fun `each declared build's follow plus splits into layers that draw the tinted plus`() {
        val tint = 0x2D7FF9
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val table = FixtureResourceTable(apk)
            for (name in accentIcons(version)) {
                // The layers come back as a layer list, which a Drawable loader takes and BitmapFactory doesn't.
                val id = table.drawableIds[name] ?: error("$version: no drawable $name")
                val decoders = methodsLoading(apk, id).filter { method ->
                    method.implementation!!.instructions.any {
                        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == "Landroid/graphics/BitmapFactory;"
                    }
                }
                assertTrue("$version: $name is decoded as a bitmap by $decoders", decoders.isEmpty())
                ZipFile(apk).use { zip ->
                    for (path in table.drawableFiles[name].orEmpty()) {
                        val original = zip.getInputStream(zip.getEntry(path)).use { it.readBytes() }
                        val (white, red) = splitRedPalette(original) ?: error("$version: $path didn't split")
                        val expected = ImageIO.read(ByteArrayInputStream(recolorRedPalette(original, "#2D7FF9")!!))
                        val under = ImageIO.read(ByteArrayInputStream(white))
                        val over = ImageIO.read(ByteArrayInputStream(red))
                        assertEquals("$version: $path layer width", expected.width, under.width)
                        assertEquals("$version: $path layer height", expected.height, over.height)
                        for (x in 0 until expected.width) for (y in 0 until expected.height) {
                            val want = expected.getRGB(x, y)
                            val b = under.getRGB(x, y)
                            val a = over.getRGB(x, y)
                            val top = (a ushr 24) / 255.0
                            val bottom = (b ushr 24) / 255.0
                            val alpha = top + bottom * (1 - top)
                            assertEquals("$version: $path alpha at $x,$y", (want ushr 24).toDouble(), alpha * 255, 2.0)
                            if (want ushr 24 < 32) continue
                            for (shift in listOf(16, 8, 0)) {
                                val drawn = ((tint shr shift and 0xff) * top + (b shr shift and 0xff) * bottom * (1 - top)) / alpha
                                assertEquals("$version: $path channel $shift at $x,$y", (want shr shift and 0xff).toDouble(), drawn, 3.0)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `the wallpaper follow plus keeps its name and tints its red layer`() {
        val xml = wallpaperIconDrawable("bty", "@color/hushfeed_accent_fe2c55")
        val (white, tint) = wallpaperIconLayers("bty")
        assertTrue(xml, "@drawable/$white" in xml)
        assertTrue(xml, "android:src=\"@drawable/$tint\" android:tint=\"@color/hushfeed_accent_fe2c55\"" in xml)
        assertTrue(xml, xml.indexOf(white) < xml.indexOf(tint))
        assertEquals("@color/hushfeed_accent_fe2c55", wallpaperReference(TIKTOK_ACCENT))
    }

    /** The methods anywhere in the app that load [literal]. */
    private fun methodsLoading(apk: File, literal: Int): List<com.android.tools.smali.dexlib2.iface.Method> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        return container.dexEntryNames.asSequence()
            .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
            .flatMap { it.methods.asSequence() }
            .filter { method ->
                method.implementation?.instructions?.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == literal } == true
            }.toList()
    }

    /** Every 32-bit literal the methods of [descriptor] load. */
    private fun classLiterals(apk: File, descriptor: String): Set<Int> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val owner = container.dexEntryNames.asSequence()
            .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
            .firstOrNull { it.type == descriptor } ?: error("no $descriptor")
        return owner.methods.flatMap { method ->
            method.implementation?.instructions?.mapNotNull { (it as? NarrowLiteralInstruction)?.narrowLiteral }.orEmpty()
        }.toSet()
    }

    /** The token table's name to attr id pairs: each name's string, then the id it returns. */
    private fun tokenTable(apk: File): Map<String, Int> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val methods = container.dexEntryNames.asSequence()
            .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
            .flatMap { it.methods.asSequence() }
            .filter { it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;") && it.returnType == "Ljava/lang/Integer;" }
            .filter { method ->
                method.implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "UIShapePrimary5"
                } == true
            }.toList()
        assertEquals("the token tables: ${methods.map { "${it.definingClass}->${it.name}" }}", 1, methods.size)
        val instructions = methods.single().implementation!!.instructions.toList()
        val names = HashMap<String, Int>()
        for ((index, instruction) in instructions.withIndex()) {
            val name = ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string ?: continue
            val id = instructions.subList(index + 1, minOf(index + 6, instructions.size))
                .takeWhile { (it as? ReferenceInstruction)?.reference !is StringReference }
                .firstNotNullOfOrNull { (it as? NarrowLiteralInstruction)?.narrowLiteral?.takeIf { value -> value ushr 24 == 0x7f } }
            if (id != null) names[name] = id
        }
        return names
    }
}
