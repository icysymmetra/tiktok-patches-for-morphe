package app.morphe.patches.tiktok.interaction.gesture

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NativeCoordinateLongPressHookTest {
    @Test
    fun bothNativeCoordinateCallbacksUseXAndKeepTheirOriginalEventBranches() {
        for (owner in listOf("LX/0QPc;", "LX/0QPd;")) {
            val method = callback(owner)
            val original = method.implementation!!.instructions.toList()
            assertEquals(if (owner == "LX/0QPc;") 24 else 19, original.size)
            val targets = original.mapIndexedNotNull { index, instruction ->
                if (instruction !is OffsetInstruction) null else {
                    val address = original.take(index).sumOf { it.codeUnits } + instruction.codeOffset
                    index to original.indices.first { original.take(it).sumOf { ins -> ins.codeUnits } == address }
                }
            }

            // Current production has no emitter. Its unchanged native body is the baseline.
            val emitter = Class.forName("app.morphe.patches.tiktok.interaction.gesture.LongPressPatchKt")
                .declaredMethods.singleOrNull {
                    it.name == "hookCoordinateLongPress" && it.parameterTypes.contentEquals(arrayOf(MutableMethod::class.java))
                }
            emitter?.apply { isAccessible = true }?.invoke(null, method)

            val instructions = method.implementation!!.instructions.toList()
            assertEquals(owner, original.size + 4, instructions.size)
            assertEquals(6, method.implementation!!.registerCount)
            assertEquals(Opcode.INVOKE_STATIC, instructions[0].opcode)
            val call = instructions[0] as FiveRegisterInstruction
            assertEquals(1, call.registerCount)
            assertEquals("p1 is x=v4; p2 is y=v5", 4, call.registerC)
            val reference = (instructions[0] as ReferenceInstruction).reference as MethodReference
            assertEquals("Lapp/morphe/extension/tiktok/interaction/GestureActions;", reference.definingClass)
            assertEquals("onLongPress", reference.name)
            assertEquals(listOf("F"), reference.parameterTypes.map(CharSequence::toString))
            assertEquals("Z", reference.returnType)
            assertEquals(Opcode.MOVE_RESULT, instructions[1].opcode)
            assertEquals(0, (instructions[1] as OneRegisterInstruction).registerA)
            assertEquals(Opcode.IF_EQZ, instructions[2].opcode)
            assertEquals(0, (instructions[2] as OneRegisterInstruction).registerA)
            assertEquals(Opcode.RETURN_VOID, instructions[3].opcode)
            assertEquals(instructions.take(4).sumOf { it.codeUnits },
                instructions.take(2).sumOf { it.codeUnits } + (instructions[2] as OffsetInstruction).codeOffset)
            original.forEachIndexed { index, instruction -> assertSame(instruction, instructions[index + 4]) }
            targets.forEach { (from, to) ->
                assertEquals(instructions.take(to + 4).sumOf { it.codeUnits },
                    instructions.take(from + 4).sumOf { it.codeUnits } +
                        (instructions[from + 4] as OffsetInstruction).codeOffset)
            }
            val event = instructions.first { instruction ->
                val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                ref?.definingClass == "LX/0SLd;" && ref.name == "<init>"
            } as FiveRegisterInstruction
            assertEquals(listOf(2, 4, 1, 5, 0),
                listOf(event.registerC, event.registerD, event.registerE, event.registerF, event.registerG))
        }
    }

    private fun callback(owner: String): MutableMethod = MutableMethod(ImmutableMethod(
        owner, "LIZ", listOf("F", "F").map { ImmutableMethodParameter(it, null, null) }, "V",
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
        ImmutableMethodImplementation(6, emptyList(), null, null),
    )).apply {
        val identity = if (owner == "LX/0QPc;") """
            iget-object v0, p0, $owner->LIZIZ:Landroid/content/Context;
            if-eqz v0, :no_context
            invoke-virtual { v0 }, Ljava/lang/Object;->hashCode()I
            move-result v0
        """ else "iget v0, p0, $owner->LIZIZ:I"
        val nullContext = if (owner == "LX/0QPc;") """
            :no_context
            const/4 v0, 0x0
            goto :event
        """ else ""
        addInstructionsWithLabels(0, """
            iget-object v0, p0, $owner->LIZ:Lcom/ss/android/ugc/aweme/feed/model/BaseFeedPageParams;
            invoke-virtual { v0 }, Lcom/ss/android/ugc/aweme/feed/model/BaseFeedPageParams;->isPlayListCleanMode()Z
            move-result v0
            if-eqz v0, :eligible
            return-void
            :eligible
            iget-object v0, p0, $owner->LIZ:Lcom/ss/android/ugc/aweme/feed/model/BaseFeedPageParams;
            iget-object v0, v0, Lcom/ss/android/ugc/aweme/feed/model/BaseFeedPageParams;->eventType:Ljava/lang/String;
            if-nez v0, :event_type
            const-string v0, ""
            :event_type
            invoke-static { v0 }, LX/0BC2;->LIZ(Ljava/lang/String;)Z
            move-result v0
            if-eqz v0, :done
            new-instance v2, LX/0SLd;
            iget-object v0, p0, $owner->LIZ:Lcom/ss/android/ugc/aweme/feed/model/BaseFeedPageParams;
            iget v1, v0, Lcom/ss/android/ugc/aweme/feed/model/BaseFeedPageParams;->awemeFromPage:I
            $identity
            :event
            invoke-direct { v2, p1, v1, p2, v0 }, LX/0SLd;-><init>(FIFI)V
            invoke-static { v2 }, LX/08yC;->LIZ(Lcom/ss/android/ugc/governance/eventbus/IEvent;)Lcom/ss/android/ugc/governance/eventbus/IEvent;
            :done
            return-void
            $nullContext
        """)
    }
}
