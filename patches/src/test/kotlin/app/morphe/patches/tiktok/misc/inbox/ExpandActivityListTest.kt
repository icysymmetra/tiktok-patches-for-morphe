package app.morphe.patches.tiktok.misc.inbox

import app.morphe.appendFixtureInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Test

class ExpandActivityListTest {
    @Test
    fun `native field and constructor flags survive when expansion is off`() {
        for (atomic in listOf(false, true)) {
            for (native in listOf(false, true)) {
                assertEquals(native, runFlag(nativeFlag(atomic, native), expand = false))
            }
        }
    }

    @Test
    fun `expansion clears both kinds of collapsed flag`() {
        for (atomic in listOf(false, true)) {
            for (native in listOf(false, true)) {
                assertEquals(false, runFlag(nativeFlag(atomic, native), expand = true))
            }
        }
    }

    private fun nativeFlag(atomic: Boolean, collapsed: Boolean): MutableMethod {
        val method = MutableMethod(
            ImmutableMethod(
                "Lcom/example/Widget;", "onCreate", emptyList(), "V", AccessFlags.PUBLIC.value,
                null, null, ImmutableMethodImplementation(16, emptyList(), null, null),
            ),
        )
        val value = if (collapsed) 1 else 0
        // These are the actual 46.2.3 caller shapes: onCreate writes v3, while the followers
        // constructor passes v13 as its AtomicBoolean's argument and v0 as the receiver.
        if (atomic) {
            method.appendFixtureInstructions(
                """
                    new-instance v0, Ljava/util/concurrent/atomic/AtomicBoolean;
                    const/16 v13, $value
                    invoke-direct {v0, v13}, Ljava/util/concurrent/atomic/AtomicBoolean;-><init>(Z)V
                    return-void
                """,
            )
            method.overrideCollapsedFlag(2, 13)
        } else {
            method.appendFixtureInstructions(
                """
                    const/4 v3, $value
                    iput-boolean v3, p0, Lcom/example/Widget;->collapsed:Z
                    return-void
                """,
            )
            method.overrideCollapsedFlag(1, 3)
        }
        return method
    }

    /** Read the emitted invocation argument and final native consumer, including the high v13. */
    private fun runFlag(method: MutableMethod, expand: Boolean): Boolean {
        val registers = BooleanArray(method.implementation!!.registerCount)
        var result = false
        var consumed: Boolean? = null
        for (instruction in method.implementation!!.instructions) {
            when (instruction.opcode) {
                Opcode.CONST_4, Opcode.CONST_16 ->
                    registers[(instruction as OneRegisterInstruction).registerA] =
                        (instruction as WideLiteralInstruction).wideLiteral != 0L
                Opcode.NEW_INSTANCE -> Unit
                Opcode.INVOKE_STATIC_RANGE -> {
                    val target = (instruction as ReferenceInstruction).reference as MethodReference
                    assertEquals("Lapp/morphe/extension/tiktok/inbox/InboxControls;", target.definingClass)
                    assertEquals("shouldCollapseActivityList", target.name)
                    assertEquals(listOf("Z"), target.parameterTypes.map(CharSequence::toString))
                    assertEquals("Z", target.returnType)
                    val call = instruction as RegisterRangeInstruction
                    assertEquals(1, call.registerCount)
                    result = registers[call.startRegister] && !expand
                }
                Opcode.MOVE_RESULT -> registers[(instruction as OneRegisterInstruction).registerA] = result
                Opcode.IPUT_BOOLEAN -> consumed = registers[(instruction as TwoRegisterInstruction).registerA]
                Opcode.INVOKE_DIRECT -> consumed = registers[(instruction as FiveRegisterInstruction).registerD]
                Opcode.RETURN_VOID -> return checkNotNull(consumed)
                else -> error("Unexpected instruction: ${instruction.opcode}")
            }
        }
        error("Collapsed flag was not consumed")
    }
}
