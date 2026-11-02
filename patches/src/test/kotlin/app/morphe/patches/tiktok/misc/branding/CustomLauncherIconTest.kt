package app.morphe.patches.tiktok.misc.branding

import app.morphe.patcher.patch.PatchException
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.io.StringWriter
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * The launcher icon's restyle and its themed layer, on the decoded shape of TikTok's icon.
 *
 * <p>On 47.0.3, 47.1.3 and 47.1.4 the application's icon and round icon are both `@mipmap/c`,
 * whose mipmap-anydpi-v26 configuration is an adaptive icon with a background and a foreground
 * and no themed layer. The background (c24 on 47.0.3, c26 on 47.1.x) is one #161823 square, and
 * the foreground (c25, c27) is seven paths: three cyan and three red slivers and the white note.
 * Read off each fixture with `aapt2 dump xmltree`.
 */
class CustomLauncherIconTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `TikTok colors keeps both layers and adds the white note as the themed layer`() {
        val icon = parse(ADAPTIVE_ICON)
        val foreground = parse(FOREGROUND).documentElement

        assertTrue(restyleAdaptiveIcon(icon, IconLayers(parse(BACKGROUND).documentElement, foreground), IconStyle.TIKTOK))

        val root = icon.documentElement
        assertEquals("@drawable/c26", layer(root, "background").getAttribute("android:drawable"))
        assertEquals("@drawable/c27", layer(root, "foreground").getAttribute("android:drawable"))
        val note = inlineVector(layer(root, "monochrome"))
        assertEquals("108.0dp", note.getAttribute("android:width"))
        assertEquals("108.0", note.getAttribute("android:viewportHeight"))
        assertFalse("the copy declares no namespace of its own", note.hasAttribute("xmlns:android"))
        assertEquals(listOf(WHITE_NOTE to GLYPH_FILL), paths(note))
        // TikTok's own drawable is read, never changed.
        assertEquals(7, foreground.getElementsByTagName("path").length)
    }

    @Test
    fun `each plain style recolors the background and draws only the note`() {
        for ((style, background, glyph) in listOf(
            Triple(IconStyle.WHITE_ON_BLACK, "#ff000000", "#ffffffff"),
            Triple(IconStyle.BLACK_ON_WHITE, "#ffffffff", "#ff000000"),
        )) {
            val icon = parse(ADAPTIVE_ICON)
            assertTrue(restyleAdaptiveIcon(icon, layers(), style))
            val root = icon.documentElement
            val back = layer(root, "background")
            assertFalse(back.hasAttribute("android:drawable"))
            assertEquals(listOf(SQUARE to background), paths(inlineVector(back)))
            val front = layer(root, "foreground")
            assertFalse(front.hasAttribute("android:drawable"))
            assertEquals(listOf(WHITE_NOTE to glyph), paths(inlineVector(front)))
            assertEquals(listOf(WHITE_NOTE to GLYPH_FILL), paths(inlineVector(layer(root, "monochrome"))))
            // c26 declares aapt, so the copy's prefix moves to the root, where it still resolves.
            assertEquals("http://schemas.android.com/aapt", root.getAttribute("xmlns:aapt"))

            // What the patcher writes back parses again, and only the root declares android.
            val written = serialize(icon)
            assertEquals(written, 1, Regex("xmlns:android=").findAll(written).count())
            val reread = parse(written).documentElement
            assertEquals(3, reread.getElementsByTagName("vector").length)
        }
    }

    @Test
    fun `black background keeps TikTok's colored note`() {
        val icon = parse(ADAPTIVE_ICON)
        restyleAdaptiveIcon(icon, layers(), IconStyle.BLACK)
        val root = icon.documentElement
        assertEquals(listOf(SQUARE to "#ff000000"), paths(inlineVector(layer(root, "background"))))
        assertEquals("@drawable/c27", layer(root, "foreground").getAttribute("android:drawable"))
        assertNotNull(layer(root, "monochrome"))
    }

    @Test
    fun `an icon that already has a themed layer keeps it`() {
        val icon = parse(ADAPTIVE_ICON.replace("</adaptive-icon>", "<monochrome android:drawable=\"@drawable/own\"/></adaptive-icon>"))
        assertFalse(restyleAdaptiveIcon(icon, layers(), IconStyle.TIKTOK))
        val themed = icon.documentElement.getElementsByTagName("monochrome")
        assertEquals(1, themed.length)
        assertEquals("@drawable/own", (themed.item(0) as Element).getAttribute("android:drawable"))
    }

    @Test
    fun `a style that can't be drawn is refused before anything changes`() {
        assertEquals(IconStyle.WHITE_ON_BLACK, iconStyle("White on black"))
        assertEquals(IconStyle.BLACK, iconStyle(" black "))
        assertThrows(PatchException::class.java) { iconStyle("purple") }
        assertThrows(PatchException::class.java) { iconStyle(null) }

        val noNote = parse(FOREGROUND.replace("#ffffffff", "#ff25f4ee")).documentElement
        val icon = parse(ADAPTIVE_ICON)
        assertThrows(PatchException::class.java) {
            restyleAdaptiveIcon(icon, IconLayers(parse(BACKGROUND).documentElement, noNote), IconStyle.TIKTOK)
        }
        // A background that isn't a vector can't be recolored, and nothing was touched.
        assertThrows(PatchException::class.java) {
            restyleAdaptiveIcon(icon, IconLayers(null, parse(FOREGROUND).documentElement), IconStyle.BLACK)
        }
        assertEquals(serialize(parse(ADAPTIVE_ICON)), serialize(icon))
    }

    @Test
    fun `the launcher's icons are the application's and its launcher entries'`() {
        val manifest = parse(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.zhiliaoapp.musically">
                <application android:icon="@mipmap/c" android:roundIcon="@mipmap/c" android:label="@string/app_name">
                    <activity-alias android:name="com.ss.android.ugc.aweme.splash.SplashActivity"
                            android:targetActivity="com.ss.android.ugc.aweme.main.MainActivity">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity-alias>
                    <activity-alias android:name="com.ss.android.ugc.aweme.splash.SeasonalIcon" android:icon="@mipmap/e"
                            android:targetActivity="com.ss.android.ugc.aweme.main.MainActivity">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity-alias>
                    <service android:name="com.ss.android.ugc.aweme.tile.PublishTileService" android:icon="@drawable/d78"/>
                </application>
            </manifest>
            """,
        )
        assertEquals(setOf("@mipmap/c", "@mipmap/e"), launcherIconReferences(manifest))
    }

    @Test
    fun `the icon and its layers are found in the decoded resources`() {
        val res = folder.newFolder("res")
        write(res, "mipmap-anydpi-v26/c.xml", ADAPTIVE_ICON)
        write(res, "mipmap-xxhdpi/c.png", "not xml")
        write(res, "drawable/c26.xml", BACKGROUND)
        write(res, "drawable/c27.xml", FOREGROUND)
        write(res, "drawable-night/unrelated.xml", BACKGROUND)

        assertEquals("mipmap" to "c", appResource("@com.zhiliaoapp.musically:mipmap/c"))
        assertNull(appResource("@android:color/black"))
        assertNull(appResource("#ff000000"))
        val adaptive = adaptiveIconFiles(res, "@mipmap/c")
        assertEquals(listOf("mipmap-anydpi-v26"), adaptive.map { it.parentFile.name })
        assertNull(layerVector(res, "@android:color/black"))

        val layers = IconLayers.of(res, readXml(adaptive.single()))
        assertEquals(1, layers.background!!.getElementsByTagName("path").length)
        assertEquals(7, layers.foreground.getElementsByTagName("path").length)

        // A foreground drawn inline is read from the icon itself.
        val inline = parse(ADAPTIVE_ICON.replace("<foreground android:drawable=\"@drawable/c27\"/>", "<foreground>$FOREGROUND_BODY</foreground>"))
        assertEquals(7, IconLayers.of(res, inline).foreground.getElementsByTagName("path").length)
        // A foreground that isn't a vector is refused.
        write(res, "drawable/c27.xml", "<bitmap xmlns:android=\"http://schemas.android.com/apk/res/android\" android:src=\"@drawable/x\"/>")
        assertThrows(PatchException::class.java) { IconLayers.of(res, readXml(adaptive.single())) }
    }

    @Test
    fun `the picture option is optional and empty by default`() {
        val option = customLauncherIconPatch.options["iconPicture"]
        assertNull(option.default)
        assertFalse(option.required)
        assertNull(launcherPicture(null))
        assertNull(launcherPicture("  "))
    }

    @Test
    fun `a picture replaces the note, keeps the style's background and drops the themed layer`() {
        val picture = "@drawable/$PICTURE_NAME"
        val kept = parse(ADAPTIVE_ICON.replace("<foreground android:drawable=\"@drawable/c27\"/>", "<foreground>$FOREGROUND_BODY</foreground>"))
        assertFalse(restyleAdaptiveIcon(kept, layers(), IconStyle.TIKTOK, picture))
        assertEquals("@drawable/c26", layer(kept.documentElement, "background").getAttribute("android:drawable"))
        assertEquals(picture, layer(kept.documentElement, "foreground").getAttribute("android:drawable"))
        assertEquals(0, layer(kept.documentElement, "foreground").childNodes.length)
        assertEquals(0, kept.documentElement.getElementsByTagName("monochrome").length)

        // A plain style only colors what's behind the picture.
        val plain = parse(ADAPTIVE_ICON.replace("</adaptive-icon>", "<monochrome android:drawable=\"@drawable/own\"/></adaptive-icon>"))
        restyleAdaptiveIcon(plain, layers(), IconStyle.WHITE_ON_BLACK, picture)
        val root = plain.documentElement
        assertEquals(listOf(SQUARE to "#ff000000"), paths(inlineVector(layer(root, "background"))))
        assertEquals(picture, layer(root, "foreground").getAttribute("android:drawable"))
        assertEquals("a themed note would show in place of the picture", 0, root.getElementsByTagName("monochrome").length)
        assertEquals(picture, parse(serialize(plain)).documentElement.getElementsByTagName("foreground").let {
            (it.item(0) as Element).getAttribute("android:drawable")
        })

        // The note isn't read when a picture takes its place.
        val noNote = parse(FOREGROUND.replace("#ffffffff", "#ff25f4ee")).documentElement
        restyleAdaptiveIcon(parse(ADAPTIVE_ICON), IconLayers(parse(BACKGROUND).documentElement, noNote), IconStyle.TIKTOK, picture)
    }

    @Test
    fun `a real PNG's size is read off its header`() {
        assertEquals(432 to 432, pngSize(png(432, 432)))
        assertEquals(500 to 300, pngSize(png(500, 300)))
        val bytes = png(16, 16)
        assertEquals(16 to 16, pngSize(bytes + byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `a damaged or cut short PNG is refused`() {
        val bytes = png(16, 16)
        assertNull("cut short", pngSize(bytes.copyOf(bytes.size - 5)))
        assertNull("no end chunk", pngSize(bytes.copyOf(bytes.size - 12)))
        assertNull("not a PNG", pngSize("GIF89a not a png at all".toByteArray()))
        assertNull("empty", pngSize(ByteArray(0)))
        val flipped = bytes.copyOf().also { it[it.size - 20] = (it[it.size - 20].toInt() xor 0x40).toByte() }
        assertNull("checksum", pngSize(flipped))

        val header = ihdr(16, 16, 8, 6)
        val data = chunk("IDAT", deflate(scanlines(16, 16, 8, 6)))
        val end = chunk("IEND", ByteArray(0))
        assertEquals(16 to 16, pngSize(SIGNATURE + header + data + end))
        assertNull("header not first", pngSize(SIGNATURE + data + header + end))
        assertNull("no image data", pngSize(SIGNATURE + header + end))
        assertNull("two headers", pngSize(SIGNATURE + header + header + data + end))
        val huge = SIGNATURE + header.copyOf().also { ByteBuffer.wrap(it).putInt(0, Int.MAX_VALUE) } + data + end
        assertNull("a length past the end", pngSize(huge))
    }

    @Test
    fun `a PNG header the spec doesn't allow is refused`() {
        fun header(depth: Int, color: Int, compression: Int = 0, filter: Int = 0, interlace: Int = 0) =
            chunk("IHDR", ByteBuffer.allocate(13).putInt(16).putInt(16)
                .put(byteArrayOf(depth.toByte(), color.toByte(), compression.toByte(), filter.toByte(), interlace.toByte())).array())
        fun data(depth: Int, color: Int) = chunk("IDAT", deflate(scanlines(16, 16, depth, color)))
        val palette = chunk("PLTE", byteArrayOf(0, 0, 0, -1, -1, -1))
        val end = chunk("IEND", ByteArray(0))

        for ((depth, color) in listOf(1 to 0, 16 to 0, 8 to 2, 16 to 6, 8 to 4)) {
            assertEquals("depth $depth color $color", 16 to 16, pngSize(SIGNATURE + header(depth, color) + data(depth, color) + end))
        }
        assertEquals("interlaced", 16 to 16, pngSize(encode(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), interlaced = true)))
        assertEquals("palette first", 16 to 16, pngSize(SIGNATURE + header(8, 3) + palette + data(8, 3) + end))

        assertNull("16-bit palette", pngSize(SIGNATURE + header(16, 3) + palette + data(8, 3) + end))
        assertNull("4-bit color", pngSize(SIGNATURE + header(4, 2) + data(8, 2) + end))
        assertNull("3-bit gray", pngSize(SIGNATURE + header(3, 0) + data(8, 0) + end))
        assertNull("color type 5", pngSize(SIGNATURE + header(8, 5) + data(8, 6) + end))
        assertNull("compression", pngSize(SIGNATURE + header(8, 6, compression = 1) + data(8, 6) + end))
        assertNull("filter", pngSize(SIGNATURE + header(8, 6, filter = 1) + data(8, 6) + end))
        assertNull("interlace", pngSize(SIGNATURE + header(8, 6, interlace = 2) + data(8, 6) + end))
        assertNull("no palette", pngSize(SIGNATURE + header(8, 3) + data(8, 3) + end))
        assertNull("palette after the data", pngSize(SIGNATURE + header(8, 3) + data(8, 3) + palette + end))
    }

    @Test
    fun `image data that doesn't inflate to the header's scanlines is refused`() {
        val header = ihdr(16, 16, 8, 6)
        val end = chunk("IEND", ByteArray(0))
        fun image(vararg data: ByteArray) = SIGNATURE + header + data.fold(ByteArray(0)) { all, part -> all + chunk("IDAT", part) } + end
        val rows = scanlines(16, 16, 8, 6)
        val whole = deflate(rows)
        assertEquals(16 to 16, pngSize(image(whole)))
        assertEquals("over two chunks", 16 to 16, pngSize(image(whole.copyOfRange(0, 5), whole.copyOfRange(5, whole.size))))
        val everyFilter = rows.copyOf().also { for (row in 0 until 16) it[row * 65] = (row % 5).toByte() }
        assertEquals("filter types 0 to 4", 16 to 16, pngSize(image(deflate(everyFilter))))

        assertNull("no scanlines at all", pngSize(image(deflate(ByteArray(0)))))
        assertNull("a byte short", pngSize(image(deflate(rows.copyOf(rows.size - 1)))))
        assertNull("a byte over", pngSize(image(deflate(rows + byteArrayOf(0)))))
        assertNull("a row over", pngSize(image(deflate(rows + ByteArray(65)))))
        assertNull("rows for a narrower picture", pngSize(image(deflate(scanlines(15, 16, 8, 6)))))
        assertNull("plain rows under an interlaced header", pngSize(SIGNATURE + ihdr(16, 16, 8, 6, interlace = 1) + chunk("IDAT", whole) + end))
        for (filter in listOf(5, -1)) {
            assertNull("filter type $filter", pngSize(image(deflate(rows.copyOf().also { it[65] = filter.toByte() }))))
        }
        assertNull("not zlib", pngSize(image(whole.copyOf().also { it[0] = 0x79 })))
        assertNull("zlib checksum", pngSize(image(whole.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })))
        assertNull("zlib cut short", pngSize(image(whole.copyOf(whole.size - 4))))
        assertNull("bytes after the zlib end", pngSize(image(whole + byteArrayOf(0))))
        val text = chunk("tEXt", "a\u0000b".toByteArray(Charsets.ISO_8859_1))
        assertNull("data in two runs", pngSize(SIGNATURE + header + chunk("IDAT", whole.copyOfRange(0, 5)) + text +
            chunk("IDAT", whole.copyOfRange(5, whole.size)) + end))
    }

    @Test
    fun `a palette the spec doesn't allow is refused`() {
        val end = chunk("IEND", ByteArray(0))
        fun image(depth: Int, color: Int, vararg before: ByteArray) = SIGNATURE + ihdr(16, 16, depth, color) +
            before.fold(ByteArray(0)) { all, part -> all + part } + chunk("IDAT", deflate(scanlines(16, 16, depth, color))) + end
        fun palette(colors: Int) = chunk("PLTE", ByteArray(colors * 3))

        assertEquals("256 colors", 16 to 16, pngSize(image(8, 3, palette(256))))
        assertEquals("two colors at 1 bit", 16 to 16, pngSize(image(1, 3, palette(2))))
        assertEquals("a suggested palette for true color", 16 to 16, pngSize(image(8, 2, palette(16))))

        assertNull("257 colors", pngSize(image(8, 3, palette(257))))
        assertNull("three colors at 1 bit", pngSize(image(1, 3, palette(3))))
        assertNull("no colors", pngSize(image(8, 3, palette(0))))
        assertNull("part of a color", pngSize(image(8, 3, chunk("PLTE", ByteArray(4)))))
        assertNull("two palettes", pngSize(image(8, 3, palette(2), palette(2))))
        assertNull("a gray palette", pngSize(image(8, 0, palette(2))))
        assertNull("a gray and alpha palette", pngSize(image(8, 4, palette(2))))
    }

    @Test
    fun `PNGs a real encoder writes still pass, interlaced too`() {
        // ImageIO's own writer, so the row and Adam7 pass math is checked against an encoder and
        // not against a copy of itself. Odd sizes leave some interlace passes empty.
        val types = listOf(
            BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_BYTE_GRAY,
            BufferedImage.TYPE_USHORT_GRAY, BufferedImage.TYPE_BYTE_INDEXED, BufferedImage.TYPE_BYTE_BINARY,
        )
        for (type in types) for ((width, height) in listOf(1 to 1, 3 to 5, 9 to 9, 37 to 21)) for (interlaced in listOf(false, true)) {
            val image = BufferedImage(width, height, type)
            for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, (x * 7919 + y * 104729) or 0xff000000.toInt())
            val bytes = encode(image, interlaced)
            val case = "type $type, $width by $height, interlaced $interlaced"
            assertEquals(case, if (interlaced) 1 else 0, bytes[28].toInt())
            assertEquals(case, width to height, pngSize(bytes))
        }
    }

    @Test
    fun `a missing, damaged, oblong, small or large picture stops patching with a reason`() {
        fun refusal(path: String): String = assertThrows(PatchException::class.java) { launcherPicture(path) }.message!!
        fun file(name: String, bytes: ByteArray) = folder.newFile(name).apply { writeBytes(bytes) }.path

        assertTrue(refusal(folder.root.resolve("gone.png").path).contains("there's no file at"))
        assertTrue(refusal(folder.root.path).contains("there's no file at"))
        assertTrue(refusal(file("photo.png", "not a png".toByteArray())).contains("isn't a PNG"))
        val blank = SIGNATURE + ihdr(432, 432, 8, 6) + chunk("IDAT", deflate(ByteArray(0))) + chunk("IEND", ByteArray(0))
        assertTrue(refusal(file("blank.png", blank)).contains("isn't a PNG, or it's damaged or cut short"))
        assertTrue(refusal(file("wide.png", png(600, 432))).contains("has to be square, and \"wide.png\" is 600 by 432 pixels"))
        assertTrue(refusal(file("small.png", png(431, 431))).contains("from 432 to 1024 pixels a side, and \"small.png\" is 431"))
        assertTrue(refusal(file("large.png", png(1025, 1025))).contains("is 1025"))
        val big = folder.newFile("big.png").also { RandomAccessFile(it, "rw").use { raf -> raf.setLength(8L * 1024 * 1024 + 1) } }
        assertTrue(refusal(big.path).contains("larger than 8 MB"))
        for (message in listOf(refusal(big.path), refusal(file("tiny.png", png(16, 16))))) {
            assertTrue(message, message.startsWith("Custom launcher icon: ") && message.endsWith(" Nothing was changed."))
        }

        for (side in listOf(MIN_PICTURE_SIDE, 512, MAX_PICTURE_SIDE)) {
            val bytes = png(side, side)
            val picture = launcherPicture(" " + file("icon-$side.png", bytes) + " ")!!
            assertEquals(side, picture.side)
            assertArrayEquals(bytes, picture.bytes)
        }
    }

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also {
        assertTrue(ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", it))
    }.toByteArray()

    private fun encode(image: BufferedImage, interlaced: Boolean): ByteArray = ByteArrayOutputStream().also { out ->
        val writer = ImageIO.getImageWritersByFormatName("png").next()
        try {
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                val param = writer.defaultWriteParam
                param.progressiveMode = if (interlaced) ImageWriteParam.MODE_DEFAULT else ImageWriteParam.MODE_DISABLED
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
    }.toByteArray()

    private fun ihdr(width: Int, height: Int, depth: Int, color: Int, interlace: Int = 0) =
        chunk("IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height)
            .put(byteArrayOf(depth.toByte(), color.toByte(), 0, 0, interlace.toByte())).array())

    /** Plain (not interlaced) scanlines of zeros, filter type 0, for a picture of this size and format. */
    private fun scanlines(width: Int, height: Int, depth: Int, color: Int): ByteArray {
        val channels = mapOf(0 to 1, 2 to 3, 3 to 1, 4 to 2, 6 to 4).getValue(color)
        return ByteArray(height * (1 + (width * channels * depth + 7) / 8))
    }

    private fun deflate(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        DeflaterOutputStream(out).use { it.write(bytes) }
    }.toByteArray()

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val body = type.toByteArray(Charsets.ISO_8859_1) + data
        val crc = CRC32().apply { update(body) }.value.toInt()
        return ByteBuffer.allocate(4).putInt(data.size).array() + body + ByteBuffer.allocate(4).putInt(crc).array()
    }

    private fun layers() = IconLayers(parse(BACKGROUND).documentElement, parse(FOREGROUND).documentElement)

    private fun layer(root: Element, tag: String): Element =
        root.childNodes.elements().single { it.tagName == tag }

    private fun inlineVector(layer: Element): Element = layer.childNodes.elements().single().also {
        assertEquals("vector", it.tagName)
    }

    private fun paths(vector: Element): List<Pair<String, String>> =
        vector.getElementsByTagName("path").elements().map { it.getAttribute("android:pathData") to it.getAttribute("android:fillColor") }

    private fun write(res: File, path: String, text: String) {
        res.resolve(path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun parse(xml: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.trim().byteInputStream())

    private fun serialize(document: Document): String = StringWriter().also {
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(it))
    }.toString()

    private companion object {
        val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        const val SQUARE = "M 0 0 H 108 V 108 H 0 V 0 Z"
        const val WHITE_NOTE = "M61.1,45.3C64.3,47.62 68.1,48.93 72.2,48.9L72.2,42.9L55.7,63.6Z"

        val ADAPTIVE_ICON = """
            <?xml version="1.0" encoding="utf-8"?>
            <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
              <background android:drawable="@drawable/c26"/>
              <foreground android:drawable="@drawable/c27"/>
            </adaptive-icon>
        """.trimIndent()

        val BACKGROUND = """
            <?xml version="1.0" encoding="utf-8"?>
            <vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"
                android:height="108.0dp" android:width="108.0dp" android:viewportWidth="108.0" android:viewportHeight="108.0">
              <path android:fillColor="#ff161823" android:pathData="$SQUARE" android:strokeWidth="1.0" android:fillType="evenOdd"/>
            </vector>
        """.trimIndent()

        val FOREGROUND_BODY = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:height="108.0dp" android:width="108.0dp" android:viewportWidth="108.0" android:viewportHeight="108.0">
              <path android:fillColor="#ff25f4ee" android:pathData="M48.8,48.9L48.8,46.8Z" android:strokeColor="#00000000"/>
              <path android:fillColor="#ff25f4ee" android:pathData="M49.75,69.9C53.26,69.9 55.51,67.07 55.7,63.6Z" android:strokeColor="#00000000"/>
              <path android:fillColor="#ff25f4ee" android:pathData="M72.2,42.9L72.2,41.1Z" android:strokeColor="#00000000"/>
              <path android:fillColor="#fffe2c55" android:pathData="M66.2,39.3C64.53,37.37 63.51,34.85 63.5,32.1Z" android:strokeColor="#00000000"/>
              <path android:fillColor="#fffe2c55" android:pathData="M46.4,54.9C42.75,54.76 39.8,57.72 39.8,61.5Z" android:strokeColor="#00000000"/>
              <path android:fillColor="#fffe2c55" android:pathData="M72.2,48.6C68.18,48.72 64.07,47.42 61.1,45.3Z" android:strokeColor="#00000000"/>
              <path android:fillColor="#ffffffff" android:pathData="$WHITE_NOTE" android:strokeColor="#00000000"/>
            </vector>
        """.trimIndent()

        val FOREGROUND = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n$FOREGROUND_BODY"
    }
}
