/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.language

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Keep the app language calls at the start of the application's onCreate, held to each
 * declared build (#61): TikTok's read of the picked language and its apply, which reads it, and
 * the reword table's switch and current language, which share one field. Each is public and
 * static, since the extension calls them from its own class.
 */
class LanguageAtStartAnchorsTest {
    @Test
    fun `the start-up language calls resolve on each build`() {
        Fixtures.forEachDeclared { apk ->
            val classes = LinkedHashMap<String, ClassDef>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) classes.putIfAbsent(classDef.type, classDef)
            }
            val version = Fixtures.versionOf(apk)
            fun single(name: String, fingerprint: app.morphe.patcher.Fingerprint): Method {
                val taken = classes.values.flatMap { classDef -> classDef.methods.filter { fingerprint.takes(it, classDef) } }
                assertEquals("$version: $name takes ${taken.map { "${it.definingClass}->${it.name}" }}", 1, taken.size)
                val method = taken.single()
                assertTrue("$version: $name isn't public and static",
                    AccessFlags.PUBLIC.isSet(method.accessFlags) && AccessFlags.STATIC.isSet(method.accessFlags))
                return method
            }

            val picked = single("the picked language read", PickedLocaleFingerprint)
            val apply = single("the apply", ApplyPickedLocaleFingerprint)
            assertTrue("$version: the apply no longer reads the picked language",
                apply.calls(picked.definingClass, picked.name, "Ljava/util/Locale;"))

            val loadStrings = single("the reword switch", LoadStringsFingerprint)
            val stringsLocale = single("the reword language", StringsLocaleFingerprint)
            val written = loadStrings.fieldsWith(Opcode.SPUT_OBJECT)
            val read = stringsLocale.fieldsWith(Opcode.SGET_OBJECT)
            assertTrue("$version: the switch writes $written, the reword language reads $read",
                read.isNotEmpty() && written.containsAll(read))

            val host = checkNotNull(classes[HOST_APPLICATION]) { "$version: no $HOST_APPLICATION" }
            val onCreate = host.methods.filter { HostApplicationOnCreateFingerprint.takes(it, host) }
            assertEquals("$version: onCreate methods ${onCreate.map { it.name }}", 1, onCreate.size)
            assertFalse("$version: onCreate is static, so p0 isn't the application",
                AccessFlags.STATIC.isSet(onCreate.single().accessFlags))
        }
    }

    private fun Method.fieldsWith(opcode: Opcode): Set<String> =
        implementation!!.instructions.filter { it.opcode == opcode }
            .mapNotNull { it.getReference<FieldReference>() }
            .filter { it.type == "Ljava/util/Locale;" }
            .map { "${it.definingClass}->${it.name}" }
            .toSet()
}
