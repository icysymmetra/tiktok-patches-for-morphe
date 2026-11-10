/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.translation

import app.morphe.appendFixtureInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceivedDmTranslationHooksTest {
    @Test fun `only the completed received native menu gets an appended result`() {
        val method = menu()
        val anchor = method.receivedMenuResult()
        val original = method.implementation!!.instructions.toList()
        method.appendReceivedMenu(anchor)
        val code = method.implementation!!.instructions.toList()
        original.take(anchor.returned).forEachIndexed { index, instruction -> assertSame(instruction, code[index]) }
        val call = code[anchor.returned].getReference<MethodReference>()!!
        assertEquals(RECEIVED_PREVIEW, call.definingClass)
        assertEquals("appendAction", call.name)
        assertEquals(listOf("Ljava/util/List;", "Ljava/lang/Object;", "Ljava/lang/Object;"), call.parameterTypes.map(CharSequence::toString))
        assertEquals(Opcode.MOVE_RESULT_OBJECT, code[anchor.returned + 1].opcode)
        assertEquals(anchor.register, (code[anchor.returned + 1] as OneRegisterInstruction).registerA)
        assertSame(original[anchor.returned], code[anchor.returned + 2])
        assertSame(original[original.lastIndex - 1], code[code.lastIndex - 1])
        assertSame(original.last(), code.last())
        assertEquals(1, code.count { it.getReference<MethodReference>()?.definingClass == RECEIVED_PREVIEW })
    }

    @Test fun `sent general duplicate or changed native menu results are refused`() {
        assertThrows(PatchException::class.java) { menu(owner = "${RECEIVED_CONTENT}TextTranslateSendCell\$createAssemAttached2Cell\$1;").receivedMenuResult() }
        assertThrows(PatchException::class.java) { menu(owner = "LX/Other;").receivedMenuResult() }
        assertThrows(PatchException::class.java) { menu(registers = 40).receivedMenuResult() }
        assertThrows(PatchException::class.java) { menu(result = "return-object v1").receivedMenuResult() }
        assertThrows(PatchException::class.java) { menu(result = "const/4 v1, 0x0\nreturn-object v0").receivedMenuResult() }
        assertThrows(PatchException::class.java) { menu(prefix = nativeMenu).receivedMenuResult() }
    }

    @Test fun `high bind parameters are preserved and early unfinished return never installs an owner`() {
        val method = bind()
        val original = method.implementation!!.instructions.toList()
        assertEquals(2, method.receivedBindReturns().size)
        method.captureReceivedBinding()
        val code = method.implementation!!.instructions.toList()
        assertEquals(original.size + 3, code.size)
        assertEquals("unbound", code.first().getReference<MethodReference>()!!.name)
        val retire = code.first() as RegisterRangeInstruction
        assertEquals(37, retire.startRegister)
        assertEquals(1, retire.registerCount)
        val captured = code.withIndex().filter { it.value.getReference<MethodReference>()?.name == "bound" }
        assertEquals(2, captured.size)
        captured.forEach { (index, instruction) ->
            val range = instruction as RegisterRangeInstruction
            assertEquals(37, range.startRegister)
            assertEquals(2, range.registerCount)
            assertEquals(Opcode.RETURN_VOID, code[index + 1].opcode)
        }
        val early = code.indexOfFirst { it.opcode == Opcode.RETURN_VOID }
        assertTrue(code[early - 1].getReference<MethodReference>()?.name != "bound")
        assertEquals(40, method.implementation!!.registerCount)
        assertEquals(1, code.count { it.getReference<MethodReference>()?.let { call -> call.definingClass == RECEIVED_ASSEM && call.name == "cq" } == true })
    }

    @Test fun `overwritten native receiver or model is rejected before any instruction changes`() {
        for (overwrite in listOf("const/16 p0, 0x0", "const/16 p1, 0x0")) {
            val method = bind(overwrite)
            val original = method.implementation!!.instructions.toList()
            assertThrows(PatchException::class.java) { method.captureReceivedBinding() }
            assertEquals(original, method.implementation!!.instructions.toList())
        }
    }

    private fun menu(owner: String = RECEIVED_MENU, registers: Int = 13, result: String = "return-object v0", prefix: String = "") =
        method(owner, "kq", listOf("LX/0BUG;"), "Ljava/util/List;", registers, """
            if-eqz p1, :empty
            $prefix
            $nativeMenu
            $result
            :empty
            const/4 v0, 0x0
            return-object v0
        """)

    private fun bind(overwrite: String = "") = method(RECEIVED_ASSEM, "Tp", listOf("LX/0dfc;", "Z"), "V", 40, """
        invoke-virtual/range {p0 .. p0}, $RECEIVED_ASSEM->mq()LX/0dfB;
        move-result-object v0
        if-nez v0, :ready
        return-void
        :ready
        invoke-virtual/range {p0 .. p0}, $RECEIVED_ASSEM->cq()V
        $overwrite
        if-eqz p2, :original
        return-void
        :original
        return-void
    """)

    private fun method(owner: String, name: String, parameters: List<String>, result: String, registers: Int, body: String) =
        MutableMethod(ImmutableMethod(owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, result,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null))).apply { appendFixtureInstructions(body) }

    private companion object {
        const val nativeMenu = """
            invoke-static/range {v1 .. v8}, LX/0dd4;->LIZ(LX/0BUG;LX/0deu;Landroid/content/Context;Landroidx/fragment/app/FragmentManager;Lcom/ss/android/ugc/aweme/im/sdk/chat/feature/IMMessageListTranslationViewModel;Lcom/ss/android/ugc/aweme/im/messagelist/api/select/IMSelectTextAbility;LX/0dKt;Ljava/lang/CharSequence;)Ljava/util/List;
            move-result-object v0
        """
    }
}
