package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.appendFixtureInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProfileAvatarGestureHookTest {
    private val listener = "LY/ACListenerS198S0100000_24;"
    private val avatarOwner = "Lcom/ss/android/ugc/profile/business/avatar/"

    @Test
    fun `other profile native hold receives its owner and skips stock enlarge only when handled`() {
        assertNativeGesture("onClick\$46", 5, """
            iget-object v1, p0, $listener->l0:Ljava/lang/Object;
            check-cast v1, ${avatarOwner}OtherProfileAvatarAssem;
            const-string v0, "long_press"
            invoke-virtual { v1, v0 }, ${avatarOwner}ProfileAvatarAssem;->kq(Ljava/lang/String;)V
            return-void
        """)
    }

    @Test
    fun `own profile native hold preserves its six register body when saving is off`() {
        assertNativeGesture("onClick\$43", 6, """
            sget-object v3, LX/0lWb;->LIZ:LX/0lWb;
            iget-object v0, p0, $listener->l0:Ljava/lang/Object;
            check-cast v0, ${avatarOwner}MyProfileAvatarAssem;
            invoke-virtual { v0 }, ${avatarOwner}ProfileAvatarAssem;->bq()Lcom/ss/android/ugc/aweme/profile/model/User;
            move-result-object v2
            return-void
        """)
    }

    @Test
    fun `own profile hold on a listener class of its own reads the typed assem off this`() {
        // 46.9.3: no listener group and no cast. The assem sits in a field of its own type on a
        // class of its own, and p0 is `this` rather than the group instance handed to a static.
        assertNativeGesture("onClick", 6, """
            iget-object v0, p0, LX/0NnB;->LL:${avatarOwner}MyProfileAvatarAssem;
            invoke-virtual { v0 }, ${avatarOwner}ProfileAvatarAssem;->ds()Lcom/ss/android/ugc/aweme/profile/model/User;
            move-result-object v2
            return-void
        """, owner = "LX/0NnB;", static = false, fieldName = "LL", fieldType = "${avatarOwner}MyProfileAvatarAssem;")
    }

    private fun assertNativeGesture(
        name: String,
        registers: Int,
        nativeBody: String,
        owner: String = listener,
        static: Boolean = true,
        fieldName: String = "l0",
        fieldType: String = "Ljava/lang/Object;",
    ) {
        val parameters = if (static) listOf(owner, "Landroid/view/View;") else listOf("Landroid/view/View;")
        val method = MutableMethod(ImmutableMethod(
            owner, name,
            parameters.map { ImmutableMethodParameter(it, null, null) },
            "V", AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0) or AccessFlags.FINAL.value,
            null, null, ImmutableMethodImplementation(registers, emptyList(), null, null),
        )).apply { appendFixtureInstructions(nativeBody) }
        val original = method.implementation!!.instructions.toList()

        method.interceptProfileAvatarLongPress(
            ImmutableField(owner, fieldName, fieldType, AccessFlags.PUBLIC.value, null, null, null),
        )

        val implementation = method.implementation!!
        val instructions = implementation.instructions.toList()
        assertEquals(registers, implementation.registerCount)
        assertEquals(original.size + 5, instructions.size)
        assertEquals(Opcode.IGET_OBJECT, instructions[0].opcode)
        val ownerRead = instructions[0] as TwoRegisterInstruction
        assertEquals(0, ownerRead.registerA)
        assertEquals(registers - 2, ownerRead.registerB)
        val field = (instructions[0] as ReferenceInstruction).reference as FieldReference
        assertEquals(owner, field.definingClass)
        assertEquals(fieldName, field.name)
        assertEquals(fieldType, field.type)
        assertEquals(Opcode.INVOKE_STATIC, instructions[1].opcode)
        val call = instructions[1] as FiveRegisterInstruction
        assertEquals(2, call.registerCount)
        assertEquals(0, call.registerC)
        assertEquals(registers - 1, call.registerD)
        val target = (instructions[1] as ReferenceInstruction).reference as MethodReference
        assertEquals("Lapp/morphe/extension/tiktok/download/ProfileAvatarSaver;", target.definingClass)
        assertEquals("onAvatarLongPress", target.name)
        assertEquals(listOf("Ljava/lang/Object;", "Landroid/view/View;"), target.parameterTypes.map(CharSequence::toString))
        assertEquals("Z", target.returnType)
        assertEquals(Opcode.MOVE_RESULT, instructions[2].opcode)
        assertEquals(0, (instructions[2] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, instructions[3].opcode)
        assertEquals(0, (instructions[3] as OneRegisterInstruction).registerA)
        val branchAddress = instructions.take(3).sumOf { it.codeUnits }
        assertEquals(instructions.take(5).sumOf { it.codeUnits },
            branchAddress + (instructions[3] as OffsetInstruction).codeOffset)
        assertEquals(Opcode.RETURN_VOID, instructions[4].opcode)
        original.forEachIndexed { index, instruction -> assertSame(instruction, instructions[index + 5]) }
    }
}
