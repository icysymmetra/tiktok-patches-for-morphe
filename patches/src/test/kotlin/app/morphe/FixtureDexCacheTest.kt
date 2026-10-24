/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe

import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.MultiDexContainer
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.File
import java.lang.ref.SoftReference
import java.util.Collections
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FixtureDexCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `unchanged bytes and canonical aliases reuse the decoded container`() {
        val file = apk(mapOf("classes.dex" to dex()))
        val first = Fixtures.dexContainer(file)
        assertSame(first, Fixtures.dexContainer(file))
        assertSame(first, Fixtures.dexContainer(File(file.parentFile, "./${file.name}")))
    }

    @Test
    fun `same path size and timestamp cannot hide changed fixture bytes`() {
        val file = apk(mapOf("classes.dex" to dex(value = 0)))
        val first = Fixtures.dexContainer(file)
        val size = file.length()
        val stamp = file.lastModified()
        apk(mapOf("classes.dex" to dex(value = 1)), file)
        assertEquals(size, file.length())
        assertTrue(file.setLastModified(stamp))
        val changed = Fixtures.dexContainer(file)
        assertNotSame(first, changed)
        assertEquals(0, value(first))
        assertEquals(1, value(changed))
        assertSame(changed, Fixtures.dexContainer(file))
    }

    @Test
    fun `opcode configurations do not share incompatible containers`() {
        val file = apk(mapOf("classes.dex" to dex()))
        val first = Fixtures.dexContainer(file, Opcodes.forApi(20))
        assertNotSame(first, Fixtures.dexContainer(file, Opcodes.forApi(28)))
        assertSame(first, Fixtures.dexContainer(file, Opcodes.forApi(20)))
        assertNotSame(first, Fixtures.dexContainer(file, null))
    }

    @Test
    fun `nested and concatenated entries retain every class the pinned reader sees`() {
        val nested = "modules/feature/classes.dex"
        val file = apk(mapOf(
            "classes.dex" to dex("LRoot;"),
            // D8 9.2.4, container experiment, API 36, startup profile LFirst;.
            nested to javaClass.getResourceAsStream("/fixtures/concatenated.dex")!!.use { it.readBytes() },
        ))
        val raw = DexFileFactory.loadDexContainer(file, Opcodes.getDefault())
        val cached = Fixtures.dexContainer(file)
        // The Patcher 2.0 dexlib names both entries of a concatenated container
        // explicitly, starting at zero. Keep the reader identity and contents pinned.
        assertEquals(listOf("classes.dex", "$nested/0", "$nested/1"), raw.dexEntryNames)
        assertEquals(raw.dexEntryNames, cached.dexEntryNames)
        assertEquals(setOf("LFirst;"), cached.getEntry("$nested/0")!!.dexFile.classes.map { it.type }.toSet())
        assertEquals(setOf("LSecond;"), cached.getEntry("$nested/1")!!.dexFile.classes.map { it.type }.toSet())
        val types = cached.dexEntryNames.flatMap { cached.getEntry(it)!!.dexFile.classes.map { owner -> owner.type } }
        assertEquals(setOf("LRoot;", "LFirst;", "LSecond;"), types.toSet())
        assertEquals(3, types.size)
        assertSame(cached, Fixtures.dexContainer(file))
    }

    @Test
    fun `mutable readers cannot pollute another reader or the cached original`() {
        val file = apk(mapOf("classes.dex" to dex()))
        val container = Fixtures.dexContainer(file)
        val owner = container.getEntry("classes.dex")!!.dexFile.classes.single()
        val first = MutableClass(owner)
        val second = MutableClass(owner)
        first.methods.single().returnEarly(true)
        assertEquals(1, (first.methods.single().implementation!!.instructions.first() as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(0, (second.methods.single().implementation!!.instructions.first() as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(0, value(container))
        assertSame(container, Fixtures.dexContainer(file))
        assertEquals(0, value(Fixtures.dexContainer(file)))
    }

    @Test
    fun `simultaneous readers reuse one decoding of the same input`() {
        val file = apk(mapOf("classes.dex" to dex()))
        val containers = Collections.synchronizedList(mutableListOf<MultiDexContainer<out DexBackedDexFile>>())
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val readers = List(8) {
            Thread { containers.add(Fixtures.dexContainer(file)) }.apply {
                uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> errors.add(error) }
            }
        }
        readers.forEach(Thread::start)
        readers.forEach {
            it.join(30_000)
            assertFalse("a fixture reader did not finish", it.isAlive)
        }
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(readers.size, containers.size)
        containers.forEach { assertSame(containers.first(), it) }
    }

    @Test
    fun `a broken replacement never returns stale data and a valid retry works`() {
        val file = apk(mapOf("classes.dex" to dex()))
        val first = Fixtures.dexContainer(file)
        file.writeBytes(ByteArray(file.length().toInt()))
        assertThrows(DexFileFactory.UnsupportedFileTypeException::class.java) { Fixtures.dexContainer(file) }
        apk(mapOf("classes.dex" to dex(value = 1)), file)
        val retry = Fixtures.dexContainer(file)
        assertNotSame(first, retry)
        assertEquals(1, value(retry))
    }

    @Test
    fun `a reclaimed container is decoded again without losing fixture contents`() {
        val file = apk(mapOf("classes.dex" to dex(value = 1)))
        val first = Fixtures.dexContainer(file)
        val cache = Fixtures.javaClass.getDeclaredField("cache").apply { isAccessible = true }
            .get(Fixtures) as Map<*, *>
        val reference = cache.values.map { entry ->
            entry!!.javaClass.declaredFields.single { SoftReference::class.java.isAssignableFrom(it.type) }
                .apply { isAccessible = true }.get(entry) as SoftReference<*>
        }.single { it.get() === first }
        reference.clear()
        val decoded = Fixtures.dexContainer(file)
        assertNotSame(first, decoded)
        assertEquals(1, value(first))
        assertEquals(1, value(decoded))
        assertSame(decoded, Fixtures.dexContainer(file))
    }

    private fun value(container: MultiDexContainer<out DexBackedDexFile>): Int =
        (container.getEntry("classes.dex")!!.dexFile.classes.single().methods.single()
            .implementation!!.instructions.first() as NarrowLiteralInstruction).narrowLiteral

    private fun dex(type: String = "LFixture;", value: Int = 0): ByteArray {
        val method = ImmutableMethod(type, "gate", emptyList(), "Z",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, emptySet(), emptySet(),
            ImmutableMethodImplementation(1, listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 0, value),
                ImmutableInstruction11x(Opcode.RETURN, 0),
            ), emptyList(), emptyList()))
        val owner = ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;",
            emptyList(), null, emptySet(), emptyList(), listOf(method))
        val store = MemoryDataStore()
        try {
            DexPool(Opcodes.getDefault()).apply { internClass(owner) }.writeTo(store)
            return store.data
        } finally {
            store.close()
        }
    }

    private fun apk(entries: Map<String, ByteArray>, file: File = temporary.newFile()): File {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    crc = CRC32().apply { update(bytes) }.value
                    time = 0
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }
}
