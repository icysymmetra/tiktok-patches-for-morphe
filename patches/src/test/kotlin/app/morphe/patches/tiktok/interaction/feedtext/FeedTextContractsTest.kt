package app.morphe.patches.tiktok.interaction.feedtext

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.RegisterLiveness
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.util.getReference
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableExceptionHandler
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.ImmutableTryBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedTextContractsTest {
    private val model = "Lcom/example/Description;"
    private val holder = "Lcom/example/Layouts;"
    private val factory: Method = method("factory", 40, listOf(model, "I", AWEME, "Z", "Z"), holder,
        "const/4 v0, 0x0\nreturn-object v0")

    @Test
    fun `a high builder argument uses two dead low locals without expanding the native frame`() {
        val method = method("factory", 40, listOf(model, "I", AWEME, "Z", "Z"), holder, """
            new-instance v20, Lcom/example/Builder;
            const/16 v21, 0x12
            invoke-virtual/range { v20 .. v21 }, Lcom/example/Builder;->font(I)V
            const/4 v0, 0x0
            return-object v0
        """)
        val first = freeLocal(method, 3, setOf(20))
        val second = freeLocal(method, 3, setOf(20, first))
        method.markDescriptionBuilders(listOf(SizeInput(3, 20, first, second)))
        val code = method.implementation!!.instructions.toList()
        assertEquals(40, method.implementation!!.registerCount)
        assertEquals(Opcode.MOVE_OBJECT_FROM16, code[3].opcode)
        assertEquals(34, (code[3] as TwoRegisterInstruction).registerB)
        assertEquals(20, (code[4] as TwoRegisterInstruction).registerB)
        assertEquals(listOf(first, second), code[5].namedRegisters())
        assertTrue(code[5].namedRegisters().all { it < 16 })
    }

    @Test
    fun `the cache fallback waits until high native parameters have all been copied`() {
        val dispatch = dispatch()
        val bypass = dispatch.cacheBypass(factory)
        assertEquals(8, bypass.index)
        assertEquals(3, bypass.ownerRegister)
        assertFalse(bypass.resultLocal in RegisterLiveness.of(dispatch).liveInto(bypass.index))
        dispatch.bypassDescriptionCache(bypass)
        val code = dispatch.implementation!!.instructions.toList()
        assertEquals("freshDescription", code[8].getReference<MethodReference>()?.name)
        assertEquals(3, (code[8] as RegisterRangeInstruction).startRegister)
        assertEquals(Opcode.IF_NEZ, code[10].opcode)
        assertEquals(40, dispatch.implementation!!.registerCount)
        assertThrows(PatchException::class.java) { dispatch(wrongWidth = true).cacheBypass(factory) }
    }

    @Test
    fun `a fallback that reads a value set on the bypassed path is refused`() {
        fun dispatch(readsLater: Boolean) = method("dispatch", 40,
            listOf(model, "I", "Z", AWEME, "Z", "Z"), "Lkotlin/Pair;", """
                move-object/from16 v4, p1
                iget-object v0, v4, $model->text:Ljava/lang/CharSequence;
                const/4 v9, 0x1
                move/from16 v8, p6
                move/from16 v7, p5
                move-object/from16 v6, p4
                move/from16 v5, p2
                move-object/from16 v3, p0
                if-eqz v0, :fallback
                const/4 v9, 0x2
                :fallback
                invoke-virtual/range { v3 .. v8 }, $factory
                move-result-object v0
                ${if (readsLater) "invoke-static { v9 }, Lcom/example/Native;->use(I)V" else "nop"}
                return-object v0
            """)
        dispatch(readsLater = false).cacheBypass(factory)
        assertThrows(PatchException::class.java) { dispatch(readsLater = true).cacheBypass(factory) }
    }

    @Test
    fun `wide handler reads are never borrowed for the inserted call`() {
        val original = method("bind", 24, listOf(AWEME), "V", """
            invoke-static { v2 }, Lcom/example/Native;->use(Ljava/lang/Object;)V
            goto :done
            move-exception v3
            invoke-static { v0, v1 }, Lcom/example/Native;->wide(J)V
            :done
            const/4 v0, 0x0
            return-void
        """)
        val guarded = MutableMethod(ImmutableMethod(original.definingClass, original.name, original.parameters,
            original.returnType, original.accessFlags, null, null,
            ImmutableMethodImplementation(24, original.implementation!!.instructions,
                listOf(ImmutableTryBlock(0, 3, listOf(ImmutableExceptionHandler("Ljava/lang/Throwable;", 4)))), null)))
        assertTrue(RegisterLiveness.of(guarded).liveInto(0).containsAll(listOf(0, 1, 2)))
        assertEquals(3, freeLocal(guarded, 0))
    }

    @Test
    fun `a host with every low local live fails instead of clobbering its native values`() {
        val method = method("bind", 24, listOf(AWEME), "V", """
            invoke-static/range { v0 .. v15 }, Lcom/example/Native;->all(IIIIIIIIIIIIIIII)V
            return-void
        """)
        assertThrows(PatchException::class.java) { freeLocal(method, 0) }
    }

    @Test
    fun `every author return receives the native size completion hook`() {
        val method = method("bind", 24, listOf(AWEME), "V", """
            if-eqz p1, :empty
            return-void
            :empty
            return-void
        """)
        method.bindAuthorSize()
        val code = method.implementation!!.instructions.toList()
        assertEquals(2, (code.first() as RegisterRangeInstruction).registerCount)
        assertEquals(22, (code.first() as RegisterRangeInstruction).startRegister)
        assertEquals(2, code.count { it.getReference<MethodReference>()?.name == "authorOwnerBound" })
        code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }.forEach {
            assertEquals("authorOwnerBound", code[it - 1].getReference<MethodReference>()?.name)
        }
        assertEquals(24, method.implementation!!.registerCount)
    }

    private fun dispatch(wrongWidth: Boolean = false) = method("dispatch", 40,
        listOf(model, "I", "Z", AWEME, "Z", "Z"), "Lkotlin/Pair;", """
            move-object/from16 v4, p1
            iget-object v0, v4, $model->text:Ljava/lang/CharSequence;
            const/4 v2, 0x1
            move/from16 v8, p6
            move/from16 v7, p5
            move-object/from16 v6, p4
            move/from16 v5, ${if (wrongWidth) "p3" else "p2"}
            move-object/from16 v3, p0
            if-eqz v0, :fallback
            invoke-static { v0 }, Lcom/example/Native;->cached(Ljava/lang/CharSequence;)Lkotlin/Pair;
            move-result-object v0
            return-object v0
            :fallback
            invoke-virtual/range { v3 .. v8 }, $factory
            move-result-object v0
            return-object v0
        """)

    private fun method(name: String, registers: Int, parameters: List<String>, result: String, body: String) = MutableMethod(
        ImmutableMethod("Lcom/example/Controller;", name, parameters.map { ImmutableMethodParameter(it, null, null) },
            result, AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(registers, emptyList(), null, null)),
    ).apply { addInstructionsWithLabels(0, body.trimIndent()) }
}
