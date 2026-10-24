package app.morphe.patches.tiktok.interaction.sharesheet

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The inspected panel attach event and both native inflation paths on every declared host. */
class SharePanelFixturesTest {
    @Test
    fun `every declared native panel safely reports its action row in both layouts`() {
        val expected = mapOf(
            "47.4.3" to ("LX/0ILc;" to 0x7f0a013a),
        )
        assertEquals(Fixtures.declaredVersions().toSet(), expected.keys)
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
            val native = resolveSharePanel(classes)
            val (owner, id) = expected.getValue(version)
            assertEquals(owner, native.definingClass)
            assertEquals("onAttachedToWindow", native.name)
            assertEquals(20, native.implementation!!.registerCount)
            assertEquals(listOf(SharePanelRow(30, 5), SharePanelRow(92, 0)), native.sharePanelRows())
            assertEquals(listOf(id), native.implementation!!.instructions.filterIsInstance<NarrowLiteralInstruction>()
                .map { it.narrowLiteral }.filter { it in SHARE_ACTION_IDS })

            val mutable = MutableMethod(native)
            val before = mutable.implementation!!.instructions.toList()
            val targets = before.filterIsInstance<OffsetInstruction>().map { branch ->
                val source = before.single { it === branch }
                branch to before.single { it.location.codeAddress == source.location.codeAddress + branch.codeOffset }
            }
            mutable.notifyPanelBound()
            val after = mutable.implementation!!.instructions.toList()
            val calls = after.filter { it.getReference<MethodReference>()?.name == "panelBound" }
            assertEquals(listOf(5, 0), calls.map { (it as RegisterRangeInstruction).startRegister })
            assertEquals(before.size + 2, after.size)
            assertEquals(20, mutable.implementation!!.registerCount)
            targets.forEach { (branch, target) ->
                val source = after.single { it === branch }
                assertSame(target, after.single { it.location.codeAddress == source.location.codeAddress + branch.codeOffset })
            }
        }
    }
}
