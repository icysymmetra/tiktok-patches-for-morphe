package app.morphe.patches.tiktok.interaction.searchautoplay

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stop search autoplay returns at the entry of the search list's autoplay check. Each declared
 * build has exactly one such method, it returns nothing, it already has a return of its own (so
 * an early return is a path TikTok takes), and its frame has the local the guard writes.
 */
class SearchAutoplayCheckAnchorsTest {
    @Test
    fun `each declared build has one guardable search autoplay check`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val taken = mutableListOf<Pair<ClassDef, Method>>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (method.implementation != null && SearchAutoplayCheckFingerprint.takes(method, classDef)) {
                            taken += classDef to method
                        }
                    }
                }
            }
            assertEquals("${apk.name}: ${taken.map { it.first.type }}", 1, taken.size)
            val (classDef, method) = taken.single()
            assertEquals("V", method.returnType)
            assertEquals(listOf("Z"), method.parameterTypes.take(1).map { it.toString() })
            assertTrue("${apk.name}: instance method, p0 is the helper", !AccessFlags.STATIC.isSet(method.accessFlags))
            val returns = method.implementation!!.instructions.count { it.opcode == Opcode.RETURN_VOID }
            assertTrue("${apk.name}: ${classDef.type} has no early return of its own", returns > 1)
            assertTrue(
                "${apk.name}: ${classDef.type} frame has no local for the guard",
                method.implementation!!.registerCount > method.parameterTypes.size + 1,
            )
        }
    }
}
