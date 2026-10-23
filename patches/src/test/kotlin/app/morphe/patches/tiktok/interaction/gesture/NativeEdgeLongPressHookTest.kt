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

class NativeEdgeLongPressHookTest {
    @Test
    fun nativeEligibilityUsesTheDownXAndPreservesEveryOriginalBranchWhenAllowed() {
        val owner = "Lcom/ss/android/ugc/aweme/feed/longvideo/edgespeedup/EdgeSpeedupAssem;"
        // Exact clean 46.2.3 NU1: public final, ten registers, p0=v7, x=v8, y=v9.
        val method = MutableMethod(ImmutableMethod(
            owner, "NU1", listOf("F", "F").map { ImmutableMethodParameter(it, null, null) }, "Z",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(10, emptyList(), null, null),
        )).apply {
            addInstructionsWithLabels(0, """
                iget-boolean v0, p0, $owner->LLLILZJ:Z
                const/4 v6, 0x0
                if-nez v0, :enabled
                return v6
                :enabled
                invoke-virtual { p0 }, $owner->bq()Z
                move-result v0
                if-nez v0, :zone_setup
                return v6
                :zone_setup
                sget-object v5, LX/0ROJ;->LIZ:LX/0ROJ;
                invoke-virtual { p0 }, Lcom/bytedance/assem/arch/reused/ReusedUIAssem;->LJJIJLIJ()Landroid/view/View;
                move-result-object v0
                invoke-virtual { v0 }, Landroid/view/View;->getWidth()I
                move-result v4
                invoke-static {}, LX/0BZD;->LJIIIZ()LX/0BZD;
                move-result-object v3
                const/16 v2, 0x7c00
                const-string v0, "fix_6844100557_early_edge_speed_ready"
                const/4 v1, 0x1
                invoke-virtual { v3, v2, v0, v1, v1 }, LX/0BZD;->LIZJ(ILjava/lang/String;ZZ)Z
                move-result v0
                if-eqz v0, :no_context
                invoke-virtual { p0 }, Lcom/bytedance/assem/arch/reused/ReusedUIAssem;->LJJIJLIJ()Landroid/view/View;
                move-result-object v0
                invoke-virtual { v0 }, Landroid/view/View;->getContext()Landroid/content/Context;
                move-result-object v0
                :zone
                invoke-virtual { v5 }, Ljava/lang/Object;->getClass()Ljava/lang/Class;
                invoke-static { p1, v4, v0 }, LX/0ROJ;->LIZ(FILandroid/content/Context;)LX/0ROC;
                move-result-object v2
                sget-object v0, LX/0ROC;->INVALID_ZONE:LX/0ROC;
                if-ne v2, v0, :commerce
                return v6
                :no_context
                const/4 v0, 0x0
                goto :zone
                :commerce
                invoke-virtual { p0 }, $owner->dq()Lcom/ss/android/ugc/aweme/ability/ICommerceSpeedupAbility;
                move-result-object v0
                if-eqz v0, :share
                invoke-interface { v0, p1, p2 }, Lcom/ss/android/ugc/aweme/ability/ICommerceSpeedupAbility;->pL1(FF)Z
                move-result v0
                if-ne v0, v1, :share
                return v6
                :share
                invoke-static {}, LX/0BHt;->LIZ()Z
                move-result v0
                if-nez v0, :comments
                iget-object v0, p0, $owner->LLLFFI:LX/01xP;
                invoke-interface { v0 }, LX/01xP;->getValue()Ljava/lang/Object;
                move-result-object v0
                check-cast v0, Lcom/ss/android/ugc/aweme/feed/assem/ability/IVideoShareAbility;
                if-eqz v0, :comments
                invoke-interface { v0, p1, p2 }, Lcom/ss/android/ugc/aweme/feed/assem/ability/IVideoShareAbility;->v51(FF)Z
                move-result v0
                if-ne v0, v1, :comments
                return v6
                :comments
                invoke-static {}, LX/0BI6;->LIZ()Z
                move-result v0
                if-eqz v0, :like
                invoke-static {}, LX/0BHt;->LIZ()Z
                move-result v0
                if-nez v0, :like
                iget-object v0, p0, $owner->LLLFZ:LX/01xP;
                invoke-interface { v0 }, LX/01xP;->getValue()Ljava/lang/Object;
                move-result-object v0
                check-cast v0, Lcom/ss/android/ugc/aweme/feed/assem/ability/IVideoCommentAbility;
                if-eqz v0, :like
                invoke-interface { v0, p1, p2 }, Lcom/ss/android/ugc/aweme/feed/assem/ability/IVideoCommentAbility;->c00(FF)Z
                move-result v0
                if-ne v0, v1, :like
                return v6
                :like
                invoke-static {}, LX/0BHt;->LIZ()Z
                move-result v0
                if-nez v0, :favorite
                iget-object v0, p0, $owner->LLLII:LX/01xP;
                invoke-interface { v0 }, LX/01xP;->getValue()Ljava/lang/Object;
                move-result-object v0
                check-cast v0, Lcom/ss/android/ugc/feed/platform/cell/ability/VideoDiggAssemAbility;
                if-eqz v0, :favorite
                invoke-interface { v0, p1, p2 }, Lcom/ss/android/ugc/feed/platform/cell/ability/VideoDiggAssemAbility;->gl0(FF)Z
                move-result v0
                if-ne v0, v1, :favorite
                return v6
                :favorite
                sget-object v0, LX/0BH7;->LIZ:LX/01xP;
                invoke-interface { v0 }, LX/01xP;->getValue()Ljava/lang/Object;
                move-result-object v0
                check-cast v0, Ljava/lang/Number;
                invoke-virtual { v0 }, Ljava/lang/Number;->intValue()I
                move-result v0
                if-eqz v0, :allowed
                iget-object v0, p0, $owner->LLLI:LX/01xP;
                invoke-interface { v0 }, LX/01xP;->getValue()Ljava/lang/Object;
                move-result-object v0
                check-cast v0, Lcom/ss/android/ugc/feed/platform/cell/ability/VideoFavoriteAssemAbility;
                if-eqz v0, :allowed
                invoke-interface { v0, p1, p2 }, Lcom/ss/android/ugc/feed/platform/cell/ability/VideoFavoriteAssemAbility;->xd2(FF)Z
                move-result v0
                if-ne v0, v1, :allowed
                return v6
                :allowed
                return v1
            """)
        }
        val original = method.implementation!!.instructions.toList()
        assertEquals(96, original.size)
        val targets = original.mapIndexedNotNull { index, instruction ->
            if (instruction !is OffsetInstruction) null else {
                val address = original.take(index).sumOf { it.codeUnits } + instruction.codeOffset
                index to original.indices.first { original.take(it).sumOf { ins -> ins.codeUnits } == address }
            }
        }

        method.preserveConfiguredLongPressFromEdgeSpeedup()

        val instructions = method.implementation!!.instructions.toList()
        assertEquals(10, method.implementation!!.registerCount)
        assertEquals(original.size + 4, instructions.size)
        assertEquals(Opcode.INVOKE_STATIC, instructions[0].opcode)
        val call = instructions[0] as FiveRegisterInstruction
        assertEquals(1, call.registerCount)
        assertEquals("p1 is x; p0 is the owner and p2 is y", 8, call.registerC)
        val reference = (instructions[0] as ReferenceInstruction).reference as MethodReference
        assertEquals("Lapp/morphe/extension/tiktok/interaction/GestureActions;", reference.definingClass)
        assertEquals("allowNativeEdgeSpeedup", reference.name)
        assertEquals(listOf("F"), reference.parameterTypes.map(CharSequence::toString))
        assertEquals("Z", reference.returnType)
        assertEquals(Opcode.MOVE_RESULT, instructions[1].opcode)
        assertEquals(0, (instructions[1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_NEZ, instructions[2].opcode)
        assertEquals(0, (instructions[2] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.RETURN, instructions[3].opcode)
        assertEquals(0, (instructions[3] as OneRegisterInstruction).registerA)
        assertEquals(instructions.take(4).sumOf { it.codeUnits },
            instructions.take(2).sumOf { it.codeUnits } + (instructions[2] as OffsetInstruction).codeOffset)
        original.forEachIndexed { index, instruction -> assertSame(instruction, instructions[index + 4]) }
        targets.forEach { (from, to) ->
            assertEquals(instructions.take(to + 4).sumOf { it.codeUnits },
                instructions.take(from + 4).sumOf { it.codeUnits } +
                    (instructions[from + 4] as OffsetInstruction).codeOffset)
        }
    }
}
