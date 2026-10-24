package app.morphe.patches.tiktok.interaction.live

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LIVE controls returns at the entry of the feed LIVE preview's countdown starter. Each declared
 * build has exactly one such method, in the real-named guide view model; it returns nothing, it
 * already has early returns of its own (so a return at entry is a path TikTok takes), and its
 * frame has the local the guard writes. It must also still be the method that arms both
 * countdowns, or a guard on it would leave the room opening by itself with no failure anywhere.
 */
class LivePreviewAutoEnterAnchorsTest {
    private val guideManager = "Lcom/ss/android/ugc/aweme/feed/util/LivePreviewEnterRoomGuideManager;"

    @Test
    fun `each declared build has one guardable LIVE preview countdown starter`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val taken = mutableListOf<Pair<ClassDef, Method>>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (method.implementation != null && LivePreviewAutoEnterFingerprint.takes(method, classDef)) {
                            taken += classDef to method
                        }
                    }
                }
            }
            assertEquals("${apk.name}: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            val (classDef, method) = taken.single()
            assertEquals(LIVE_PREVIEW_GUIDE_VM, classDef.type)
            assertEquals("V", method.returnType)
            assertTrue("${apk.name}: takes no parameters", method.parameterTypes.isEmpty())
            assertTrue("${apk.name}: instance method, p0 is the view model", !AccessFlags.STATIC.isSet(method.accessFlags))

            val instructions = method.implementation!!.instructions.toList()
            val returns = instructions.count { it.opcode == Opcode.RETURN_VOID }
            assertTrue("${apk.name}: ${method.name} has no early return of its own", returns > 1)
            assertTrue(
                "${apk.name}: ${method.name} frame has no local for the guard",
                method.implementation!!.registerCount > method.parameterTypes.size + 1,
            )

            // The countdown it starts: the smart one through the guide manager and the fixed one
            // through the request delay, which it reaches through a sibling taking a long.
            val references = instructions.mapNotNull { (it as? ReferenceInstruction)?.reference }
            assertTrue(
                "${apk.name}: ${method.name} no longer hands the smart countdown to the guide manager",
                references.any { it is FieldReference && it.definingClass == guideManager } &&
                    references.any { it is MethodReference && it.definingClass == guideManager && it.name == "runAsync" },
            )
            val delayed = references.filterIsInstance<MethodReference>()
                .filter { it.definingClass == LIVE_PREVIEW_GUIDE_VM && it.parameterTypes.map(CharSequence::toString) == listOf("J") }
            assertTrue("${apk.name}: ${method.name} no longer arms the fixed countdown", delayed.isNotEmpty())
            val fixed = classDef.methods.filter { candidate ->
                delayed.any { it.name == candidate.name && candidate.parameterTypes.map(CharSequence::toString) == listOf("J") }
            }
            assertTrue(
                "${apk.name}: the fixed countdown no longer reads live_preview_page_auto_entering_request_delay",
                fixed.any { candidate ->
                    candidate.implementation?.instructions?.toList().orEmpty().any {
                        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                            "live_preview_page_auto_entering_request_delay"
                    }
                },
            )
        }
    }
}
