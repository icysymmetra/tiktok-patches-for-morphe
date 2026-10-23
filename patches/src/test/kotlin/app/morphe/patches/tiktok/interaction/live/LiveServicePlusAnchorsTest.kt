package app.morphe.patches.tiktok.interaction.live

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #94: LIVE controls runs the For You LIVE preview's Service+ row hide when the row is bound to a
 * LIVE with no Service+ item while it still shows the last one. Each declared build has the row
 * under its real name with one bind that stores the room's preview right before a plain show and
 * a return, and one hide that clears the row's callbacks and hides it. The hide has to be the one
 * TikTok runs as a preview leaves the screen, or the hook would leave the row in a state TikTok
 * never puts it in.
 */
class LiveServicePlusAnchorsTest {
    @Test
    fun `each declared build has the Service+ row bind and hide the hook writes around`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val rows = mutableListOf<ClassDef>()
            for (entry in container.dexEntryNames) {
                container.getEntry(entry)!!.dexFile.classes.filterTo(rows) { it.type == SERVICE_PLUS_ROW }
            }
            assertEquals("${apk.name}: Service+ row classes", 1, rows.size)
            val row = rows.single()

            val bind = servicePlusBind(row)
            assertNotEquals("${apk.name}: the bind's preview and row share a register", bind.row, bind.preview)
            assertEquals("${apk.name}: the row is p0 of a no-argument method",
                bind.method.implementation!!.registerCount - 1, bind.row)
            assertNotEquals("${apk.name}: the hide is the bind", bind.method.name, bind.hide.name)

            // TikTok's own reset, the method a preview leaving the screen runs, calls the hide.
            val callers = row.methods.filter { method ->
                method.implementation?.instructions?.toList().orEmpty().any {
                    it.opcode == Opcode.INVOKE_VIRTUAL &&
                        ((it as ReferenceInstruction).reference as MethodReference).let { call ->
                            call.definingClass == SERVICE_PLUS_ROW && call.name == bind.hide.name &&
                                call.parameterTypes.isEmpty()
                        }
                }
            }
            assertTrue("${apk.name}: nothing in the row runs ${bind.hide.name}", callers.size >= 2)

            // The show right after the store returns without hiding the row when the preview is
            // null, which is the stale row this covers.
            val show = (bind.method.implementation!!.instructions.toList()[bind.store + 1] as ReferenceInstruction)
                .reference as MethodReference
            val showBody = row.methods.single { it.name == show.name && it.parameterTypes.isEmpty() && it.returnType == "V" }
                .implementation!!.instructions.toList()
            assertTrue("${apk.name}: ${show.name} no longer reads the stored preview",
                showBody.any { (it as? ReferenceInstruction)?.reference.toString().contains(SMB_LIVE_PREVIEW) })
        }
    }
}
