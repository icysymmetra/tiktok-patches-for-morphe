package app.morphe.patches.tiktok.interaction.cleardisplay

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.junit.Assert.*
import org.junit.Test

class ClearDisplayCompletionTest {
    @Test
    fun `all declared hosts acknowledge only after native cell completion`() = Fixtures.forEachDeclared { apk ->
        val container = Fixtures.dexContainer(apk)
        val classes = container.dexEntryNames.asSequence().flatMap {
            container.getEntry(it)!!.dexFile.classes.asSequence()
        }.associateBy { it.type }
        val cellOwner = classes.values.single { it.type.endsWith("/VideoViewCell;") }
        val originalCell = cellOwner.methods.single { method ->
            method.parameterTypes.map(CharSequence::toString) == listOf("Z", "Z")
                && method.implementation?.instructions?.any {
                    it.getReference<StringReference>()?.string == "event_enter_clear_mode"
                } == true
        }
        assertTrue("cell model getter disappeared", cellOwner.methods.any {
            it.name == "getAweme" && it.parameterTypes.isEmpty()
                && it.returnType == "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
        })
        val originalPanel = classes.values.single { it.type.endsWith("/ClearModePanelComponent;") }
            .methods.single { it.name == "onClearModeEvent" }
        val members = resolveClearDisplayController(originalPanel, classes::get)
        val bridge = clearDisplayPanelBridge(panelBridge(), members)
        val bridgeBody = bridge.implementation!!.instructions.toList()
        val bridgeCalls = bridgeBody.mapNotNull { instruction ->
            instruction.getReference<MethodReference>()?.let { instruction.opcode to it.toString() }
        }
        assertEquals(listOf(
            Opcode.INVOKE_VIRTUAL to members.panelAbility.toString(),
            Opcode.INVOKE_INTERFACE to members.feedController.toString(),
            Opcode.INVOKE_INTERFACE to members.controller.toString(),
            Opcode.INVOKE_STATIC_RANGE to
                "Lapp/morphe/extension/tiktok/cleardisplay/RememberClearDisplayPatch;->beforeNativeApply(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
        ), bridgeCalls)
        assertEquals("null panel, ability and controller must fail closed", 3,
            bridgeBody.count { it.opcode == Opcode.IF_EQZ })
        val delegation = bridgeBody.indexOfFirst {
            it.getReference<MethodReference>()?.name == "beforeNativeApply"
        }
        val controllerArgument = bridgeBody[delegation - 1] as TwoRegisterInstruction
        assertEquals(bridge.implementation!!.registerCount - 1, controllerArgument.registerA)
        assertEquals(0, controllerArgument.registerB)
        val delegationRange = bridgeBody[delegation] as RegisterRangeInstruction
        assertEquals(bridge.implementation!!.registerCount - 3, delegationRange.startRegister)
        assertEquals(3, delegationRange.registerCount)
        try {
            resolveClearDisplayController(originalPanel) {
                if (it == members.controller.definingClass) null else classes[it]
            }
            fail("a missing native controller contract was accepted")
        } catch (expected: PatchException) { assertTrue(expected.message!!.contains("missing")) }
        val cell = MutableMethod(originalCell)
        val panel = MutableMethod(originalPanel)
        val cellBefore = cell.implementation!!.instructions.toList()
        val panelBefore = panel.implementation!!.instructions.toList()
        hookClearDisplayCompletion(panel, cell)

        val cellAfter = cell.implementation!!.instructions.toList()
        val completion = cellAfter.indices.single {
            cellAfter[it].getReference<MethodReference>()?.name == "onNativeApplied"
        }
        assertEquals(cellBefore.size + 1, cellAfter.size)
        assertTrue("completion was placed before the cell's early failure return",
            cellAfter.take(completion).any { it.opcode == Opcode.RETURN_VOID })
        assertTrue(cellAfter.take(completion).any {
            it.getReference<StringReference>()?.string == "exit_clearmode"
        })
        val notification = cellAfter[completion - 1].getReference<MethodReference>()!!
        assertEquals(listOf("Ljava/lang/String;", "Ljava/lang/Object;"),
            notification.parameterTypes.drop(1).map(CharSequence::toString))
        val range = cellAfter[completion] as RegisterRangeInstruction
        assertEquals(cell.implementation!!.registerCount - 3, range.startRegister)
        assertEquals(2, range.registerCount)
        assertEquals(cellBefore.count { it.opcode == Opcode.RETURN_VOID },
            cellAfter.count { it.opcode == Opcode.RETURN_VOID })

        val panelAfter = panel.implementation!!.instructions.toList()
        val calls = panelAfter.indices.filter {
            panelAfter[it].getReference<MethodReference>()?.name == "onNativePanelApply"
        }
        assertEquals("every current-holder branch must be covered", 10, calls.size)
        assertEquals(panelBefore.size + calls.size, panelAfter.size)
        calls.forEach { index ->
            val native = panelAfter[index + 1].getReference<MethodReference>()!!
            assertEquals(originalCell.name, native.name)
            val binding = panelAfter[index] as FiveRegisterInstruction
            val apply = panelAfter[index + 1] as FiveRegisterInstruction
            assertEquals(apply.registerC, binding.registerD)
            assertEquals(3, binding.registerCount)
        }
        // Adjacent cells are refreshed through Cq and must never establish an ACK owner.
        assertEquals(panelBefore.count { it.getReference<MethodReference>()?.name == "Cq" },
            panelAfter.count { it.getReference<MethodReference>()?.name == "Cq" })

        val missingMarker = withoutBody(originalCell)
        try {
            hookClearDisplayCompletion(MutableMethod(originalPanel), missingMarker)
            fail("a missing native completion marker was accepted")
        } catch (expected: PatchException) { assertTrue(expected.message!!.contains("notification")) }
        val missingApply = withoutBody(originalPanel)
        try {
            hookClearDisplayCompletion(missingApply, MutableMethod(originalCell))
            fail("a missing current-holder apply was accepted")
        } catch (expected: PatchException) { assertTrue(expected.message!!.contains("current-holder")) }
    }

    private fun panelBridge() = MutableMethod(ImmutableMethod(
        "Lapp/morphe/extension/tiktok/cleardisplay/RememberClearDisplayPatch;", "onNativePanelApply",
        List(3) { ImmutableMethodParameter("Ljava/lang/Object;", null, null) }, "V",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
        ImmutableMethodImplementation(3, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
    ))

    private fun withoutBody(method: Method) = MutableMethod(ImmutableMethod(
        method.definingClass, method.name, method.parameters, method.returnType, method.accessFlags,
        method.annotations, method.hiddenApiRestrictions,
        ImmutableMethodImplementation(method.implementation!!.registerCount, emptyList(), null, null),
    ))
}
