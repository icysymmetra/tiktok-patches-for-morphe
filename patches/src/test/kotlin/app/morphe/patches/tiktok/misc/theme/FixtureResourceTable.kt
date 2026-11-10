package app.morphe.patches.tiktok.misc.theme

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Just enough of a fixture's resources.arsc for the AMOLED and Accent color tests: the app
 * package's attr and color names, the colors' default literals, every style's items, and where
 * each drawable's files are.
 */
internal class FixtureResourceTable(apk: File) {
    private val table: ByteBuffer = ZipFile(apk).use { zip ->
        ByteBuffer.wrap(zip.getInputStream(zip.getEntry("resources.arsc")).use { it.readBytes() })
            .order(ByteOrder.LITTLE_ENDIAN)
    }
    val attrNames = HashMap<Int, String>()
    private val attrIds = HashMap<String, Int>()

    /** Color id to name, and name to its literal in the default configuration (#aarrggbb). */
    val colorNames = HashMap<Int, String>()
    val colorValues = HashMap<String, String>()

    /** Drawable name to id, and to the archive path of its file in every configuration that has one. */
    val drawableIds = HashMap<String, Int>()
    val drawableFiles = HashMap<String, MutableList<String>>()

    /** The table's own string pool, where a file resource's value names its path. */
    private var values: StringPool? = null

    /** Every style entry, each configuration's on its own, with its items in the table's order. */
    val styles = ArrayList<Style>()

    /**
     * attr id to every value a style gives it, each config of a style counted on its own: a
     * colour literal or ?attr/name.
     */
    private val styleItems = HashMap<Int, MutableList<String>>()

    class Style(val name: String, val items: List<Item>)

    /** One style item: the attr it sets, the value's type byte and its data. */
    class Item(val attr: Int, val type: Int, val data: Int)

    init {
        forEachChunk(u16(2), table.limit()) { chunk, type ->
            if (type == STRING_POOL) values = StringPool(table, chunk)
            if (type == PACKAGE) readPackage(chunk)
        }
    }

    fun styleValues(attr: String): List<String> = styleItems[attrIds.getValue(attr)].orEmpty()

    /** The names of the colors a style's items point at, in the order of the attrs that point. */
    fun colorReferences(style: Style): List<String> = style.items.sortedBy { it.attr }
        .filter { it.type == TYPE_REFERENCE }
        .mapNotNull { colorNames[it.data] }

    private fun readPackage(start: Int) {
        val headerSize = u16(start + 2)
        val end = start + table.getInt(start + 4)
        val packageId = table.getInt(start + 8)
        if (packageId != 0x7f) return
        val typeNames = StringPool(table, start + table.getInt(start + 268))
        val keys = StringPool(table, start + table.getInt(start + 276))
        val typeIdOffset = if (headerSize >= 288) table.getInt(start + 284) else 0
        fun typeId(name: String) = (0 until typeNames.count).first { typeNames[it] == name } + 1 + typeIdOffset
        val attrType = typeId("attr")
        val styleType = typeId("style")
        val colorType = typeId("color")
        val drawableType = typeId("drawable")
        val chunks = ArrayList<Int>()
        forEachChunk(start + headerSize, end) { chunk, type -> if (type == TYPE) chunks += chunk }
        // Names first: a style's items name their attrs, and point at colors, by id.
        for (chunk in chunks) {
            val typeId = u8(chunk + 8)
            if (typeId != attrType && typeId != colorType && typeId != drawableType) continue
            val defaultConfig = isDefaultConfig(chunk)
            forEachEntry(chunk) { index, entry ->
                val name = keys[keyOf(entry)]
                val id = (packageId shl 24) or (typeId shl 16) or index
                if (typeId == attrType) {
                    attrNames[id] = name
                    attrIds[name] = id
                    return@forEachEntry
                }
                if (typeId == colorType) colorNames[id] = name
                if (typeId == drawableType) drawableIds[name] = id
                if (u16(entry + 2) and COMPLEX != 0) return@forEachEntry
                // A compact entry keeps the value's type in its flags' high byte and the data inline.
                val flags = u16(entry + 2)
                val value = entry + u16(entry)
                val type = if (flags and COMPACT != 0) flags ushr 8 else u8(value + 3)
                val data = table.getInt(if (flags and COMPACT != 0) entry + 4 else value + 4)
                if (typeId == colorType && defaultConfig && type in 0x1c..0x1f) {
                    colorValues[name] = "#" + String.format("%08x", data)
                }
                if (typeId == drawableType && type == TYPE_STRING) {
                    drawableFiles.getOrPut(name) { ArrayList() } += checkNotNull(values) { "no string pool before the package" }[data]
                }
            }
        }
        for (chunk in chunks) {
            if (u8(chunk + 8) != styleType) continue
            forEachEntry(chunk) { _, entry ->
                val flags = u16(entry + 2)
                if (flags and COMPLEX == 0 || flags and COMPACT != 0) return@forEachEntry
                val items = ArrayList<Item>()
                var item = entry + u16(entry)
                repeat(table.getInt(entry + 12)) {
                    val attr = table.getInt(item)
                    val type = u8(item + 7)
                    val data = table.getInt(item + 8)
                    items += Item(attr, type, data)
                    val shown = when (type) {
                        TYPE_ATTRIBUTE -> "?attr/" + (attrNames[data] ?: Integer.toHexString(data))
                        in 0x1c..0x1f -> "#" + String.format("%08x", data)
                        else -> "0x" + Integer.toHexString(type) + ":" + Integer.toHexString(data)
                    }
                    styleItems.getOrPut(attr) { ArrayList() } += shown
                    item += 12
                }
                styles += Style(keys[keyOf(entry)], items)
            }
        }
    }

    /** A type chunk for the default configuration: every field of its ResTable_config is zero. */
    private fun isDefaultConfig(chunk: Int): Boolean {
        val config = chunk + 20
        val size = table.getInt(config)
        return (4 until size).all { table.get(config + it).toInt() == 0 }
    }

    private fun keyOf(entry: Int) = if (u16(entry + 2) and COMPACT != 0) u16(entry) else table.getInt(entry + 4)

    private fun forEachEntry(chunk: Int, each: (index: Int, entry: Int) -> Unit) {
        val flags = u8(chunk + 9)
        val count = table.getInt(chunk + 12)
        val entries = chunk + table.getInt(chunk + 16)
        val offsets = chunk + u16(chunk + 2)
        for (i in 0 until count) {
            val index: Int
            val offset: Int
            when {
                flags and SPARSE != 0 -> { index = u16(offsets + 4 * i); offset = u16(offsets + 4 * i + 2) * 4 }
                flags and OFFSET16 != 0 -> { index = i; offset = u16(offsets + 2 * i).let { if (it == 0xffff) -1 else it * 4 } }
                else -> { index = i; offset = table.getInt(offsets + 4 * i) }
            }
            if (offset == -1) continue
            each(index, entries + offset)
        }
    }

    private fun forEachChunk(from: Int, until: Int, each: (start: Int, type: Int) -> Unit) {
        var at = from
        while (at + 8 <= until) {
            val size = table.getInt(at + 4)
            check(size >= 8 && at + size <= until) { "a chunk at $at runs past its parent" }
            each(at, u16(at))
            at += size
        }
    }

    private fun u8(at: Int) = table.get(at).toInt() and 0xff
    private fun u16(at: Int) = table.getShort(at).toInt() and 0xffff

    private class StringPool(private val table: ByteBuffer, start: Int) {
        val count = table.getInt(start + 8)
        private val utf8 = table.getInt(start + 16) and 0x100 != 0
        private val strings = start + table.getInt(start + 20)
        private val offsets = start + (table.getShort(start + 2).toInt() and 0xffff)

        operator fun get(index: Int): String {
            check(index in 0 until count) { "string $index of $count" }
            fun u8(p: Int) = table.get(p).toInt() and 0xff
            fun u16(p: Int) = table.getShort(p).toInt() and 0xffff
            var at = strings + table.getInt(offsets + 4 * index)
            if (utf8) {
                at += if (u8(at) and 0x80 != 0) 2 else 1
                var length = u8(at++)
                if (length and 0x80 != 0) length = ((length and 0x7f) shl 8) or u8(at++)
                val bytes = ByteArray(length)
                table.get(at, bytes)
                return String(bytes, Charsets.UTF_8)
            }
            var length = u16(at)
            at += 2
            if (length and 0x8000 != 0) {
                length = ((length and 0x7fff) shl 16) or u16(at)
                at += 2
            }
            return buildString { for (i in 0 until length) append(table.getChar(at + 2 * i)) }
        }
    }

    companion object {
        const val STRING_POOL = 0x0001
        const val PACKAGE = 0x0200
        const val TYPE = 0x0201
        const val SPARSE = 0x01
        const val OFFSET16 = 0x02
        const val COMPLEX = 0x0001
        const val COMPACT = 0x0008
        const val TYPE_REFERENCE = 0x01
        const val TYPE_ATTRIBUTE = 0x02
        const val TYPE_STRING = 0x03
    }
}
