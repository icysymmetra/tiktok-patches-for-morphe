package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.appendFixtureInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DmStickerPreviewHookTest {
    @Test
    fun `only the finished preview call receives the view and template with high parameter registers`() {
        val method = tap()
        val anchor = dmStickerPreviewAnchor(owner(listOf(method)))
        val original = method.implementation!!.instructions.toList()
        method.addDmStickerPreviewHook(anchor.previewCall)
        val code = method.implementation!!.instructions.toList()
        assertEquals(39, method.implementation!!.registerCount)
        assertEquals(original.size + 3, code.size)
        original.take(anchor.previewCall + 1).forEachIndexed { index, instruction -> assertSame(instruction, code[index]) }
        val view = code[anchor.previewCall + 1] as TwoRegisterInstruction
        val template = code[anchor.previewCall + 2] as TwoRegisterInstruction
        assertEquals(Opcode.MOVE_OBJECT_FROM16, code[anchor.previewCall + 1].opcode)
        assertEquals(0, view.registerA)
        assertEquals(35, view.registerB)
        assertEquals(1, template.registerA)
        assertEquals(38, template.registerB)
        val call = code[anchor.previewCall + 3] as FiveRegisterInstruction
        assertEquals(2, call.registerCount)
        assertEquals(0, call.registerC)
        assertEquals(1, call.registerD)
        val reference = code[anchor.previewCall + 3].getReference<MethodReference>()!!
        assertEquals("Lapp/morphe/extension/tiktok/download/StickerGallerySaver;", reference.definingClass)
        assertEquals("openDmStickerPreview", reference.name)
        assertEquals(listOf("Landroid/view/View;", "Ljava/lang/Object;"), reference.parameterTypes.map(CharSequence::toString))
        assertSame(original.last(), code[anchor.previewCall + 4])
        assertEquals(Opcode.RETURN_VOID, code[anchor.previewCall + 4].opcode)
        assertEquals(1, code.count { it.getReference<MethodReference>()?.definingClass?.startsWith("Lapp/morphe/extension/") == true })
    }

    @Test
    fun `renamed tap still resolves but duplicate changed signatures or a nonfinal call fail closed`() {
        assertEquals("renamed", dmStickerPreviewAnchor(owner(listOf(tap("renamed")))).method.name)
        assertThrows(PatchException::class.java) { dmStickerPreviewAnchor(owner(listOf(tap(), tap("second")))) }
        assertThrows(PatchException::class.java) { dmStickerPreviewAnchor(owner(listOf(tap(parameters = listOf("Landroid/view/View;", "J", "Ljava/lang/Object;", DM_STICKER_TEMPLATE))))) }
        assertThrows(PatchException::class.java) { dmStickerPreviewAnchor(owner(listOf(tap(tail = "const/4 v0, 0x0\nreturn-void")))) }
        assertThrows(PatchException::class.java) { dmStickerPreviewAnchor(owner(emptyList())) }
        assertThrows(PatchException::class.java) { dmStickerPreviewAnchor(owner(listOf(tap()), emptyList())) }
    }

    @Test
    fun `a new native preview implementation is refused instead of receiving a second sheet`() {
        assertTrue(isEmptyDmPreview(stub("return-void")))
        assertTrue(isEmptyDmPreview(stub("""
            iget-object v0, p0, LX/Service;->delegate:Ljava/lang/Object;
            invoke-virtual {v0}, Ljava/lang/Object;->getClass()Ljava/lang/Class;
            return-void
        """)))
        assertFalse(isEmptyDmPreview(stub("""
            invoke-virtual {v0}, Landroid/app/Dialog;->show()V
            return-void
        """)))
        assertFalse(isEmptyDmPreview(stub("const/4 v0, 0x0\nreturn-void")))
    }

    @Test
    fun `insufficient locals are rejected before the injection can overwrite a parameter`() {
        val method = tap(registers = 6)
        assertThrows(PatchException::class.java) { method.addDmStickerPreviewHook(0) }
    }

    private fun tap(
        name: String = "vQ",
        parameters: List<String> = listOf("Landroid/view/View;", "LX/Context;", "LX/Message;", DM_STICKER_TEMPLATE),
        tail: String = "return-void",
        registers: Int = 39,
    ) = method(DM_STICKER_HANDLER, name, parameters, registers, """
        const-string v0, "chat_general_sticker_click"
        invoke-interface {v0}, Lcom/ss/android/ugc/aweme/im/sticker/api/IMStickerApi;->getImStickerInputPanelService()LX/Service;
        move-result-object v0
        invoke-interface {v0, v4, v5, v6, v7}, LX/Service;->preview(LX/Context;LX/Preview;LX/Click;Lkotlin/jvm/functions/Function1;)V
        $tail
    """)

    private fun stub(body: String) = method("LX/Service;", "preview", emptyList(), 2, body)

    private fun method(owner: String, name: String, parameters: List<String>, registers: Int, body: String) =
        MutableMethod(ImmutableMethod(owner, name, parameters.map { ImmutableMethodParameter(it, null, null) },
            "V", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null))).apply { appendFixtureInstructions(body) }

    private fun owner(methods: List<Method>, interfaces: List<String> = listOf(DM_STICKER_ABILITY)) =
        ImmutableClassDef(DM_STICKER_HANDLER, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", interfaces,
            null, null, emptyList(), methods)
}
