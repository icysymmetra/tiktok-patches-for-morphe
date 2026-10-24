package app.morphe.patches.tiktok.interaction.blockauthor

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NativeFocusBridgeTest {
    private val owner = "LX/0q3r;"
    private val nativeListener = "LX/0q3s;"
    private val audio = "Landroid/media/AudioManager;"
    private val listener = "Landroid/media/AudioManager${'$'}OnAudioFocusChangeListener;"
    private val extension = "Lapp/morphe/extension/tiktok/wellbeing/SessionPlaybackHold;"

    @Test
    fun `request reports the actual result and listener without moving null or exception targets`() {
        val method = nativeOwnerMethod("LIZIZ", 6, """
            iget-object v0, p0, $owner->LIZ:$audio
            if-nez v0, :ready
            invoke-static {p1}, LX/0UUp;->F3(Landroid/content/Context;)Landroid/content/Context;
            move-result-object v1
            const-string v0, "audio"
            invoke-static {v1, v0}, LX/0UUp;->z2(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/Object;
            move-result-object v0
            check-cast v0, $audio
            iput-object v0, p0, $owner->LIZ:$audio
            :ready
            iget-object v3, p0, $owner->LIZ:$audio
            if-eqz v3, :done
            iget-object v2, p0, $owner->LIZIZ:$nativeListener
            if-eqz v2, :done
            const/4 v1, 3
            const/4 v0, 2
            invoke-static {v3, v2, v1, v0}, LX/0UUp;->j4($audio${listener}II)I
            :done
            return-void
        """, 16)
        val original = method.implementation!!.instructions.toList()

        method.captureNativeFocusRequest()

        val code = method.implementation!!.instructions
        assertEquals(6, method.implementation!!.registerCount)
        assertEquals(original.size + 2, code.size)
        original.forEachIndexed { index, instruction ->
            if (index != 15) assertSame(instruction, code[index + if (index > 15) 2 else 0])
        }
        assertEquals(Opcode.INVOKE_STATIC, code[15].opcode)
        assertCall(code[15] as ReferenceInstruction, "LX/0UUp;", "j4",
            listOf(audio, listener, "I", "I"), "I", listOf(3, 2, 1, 0))
        assertEquals(Opcode.MOVE_RESULT, code[16].opcode)
        assertEquals(0, (code[16] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.INVOKE_STATIC, code[17].opcode)
        assertCall(code[17] as ReferenceInstruction, extension, "onNativeFocusRequestResult",
            listOf("Ljava/lang/Object;", "I"), "V", listOf(2, 0))
        for (index in listOf(10, 12)) {
            assertEquals(code[18].location.codeAddress,
                code[index].location.codeAddress + (code[index] as OffsetInstruction).codeOffset)
        }
        val caught = method.implementation!!.tryBlocks.single()
        assertEquals("Ljava/lang/Exception;", caught.exceptionHandlers.single().exceptionType)
        assertEquals(code[18].location.codeAddress, caught.exceptionHandlers.single().handlerCodeAddress)
        assertEquals(code[18].location.codeAddress, caught.startCodeAddress + caught.codeUnitCount)
    }

    @Test
    fun `focus changes keep the native listener identity and change value`() {
        val method = nativeMethod(nativeListener, "onAudioFocusChange", listOf("I"), 2,
            "return-void")
        val original = method.implementation!!.instructions.single()

        method.captureNativeFocusChange()

        val code = method.implementation!!.instructions
        assertEquals(2, method.implementation!!.registerCount)
        assertEquals(2, code.size)
        assertSame(original, code[1])
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
        val range = code[0] as RegisterRangeInstruction
        assertEquals(0, range.startRegister)
        assertEquals(2, range.registerCount)
        val target = (code[0] as ReferenceInstruction).reference as MethodReference
        assertEquals(extension, target.definingClass)
        assertEquals("onNativeFocusChange", target.name)
        assertEquals(listOf("Ljava/lang/Object;", "I"), target.parameterTypes.map(CharSequence::toString))
        assertEquals("V", target.returnType)
    }

    @Test
    fun `abandon invalidates only its listener before native release and preserves cleanup`() {
        val method = nativeOwnerMethod("LIZ", 4, """
            iget-object v0, p0, $owner->LIZ:$audio
            if-nez v0, :ready
            invoke-static {p1}, LX/0UUp;->F3(Landroid/content/Context;)Landroid/content/Context;
            move-result-object v1
            const-string v0, "audio"
            invoke-static {v1, v0}, LX/0UUp;->z2(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/Object;
            move-result-object v0
            check-cast v0, $audio
            iput-object v0, p0, $owner->LIZ:$audio
            :ready
            iget-object v1, p0, $owner->LIZ:$audio
            if-eqz v1, :clear
            iget-object v0, p0, $owner->LIZIZ:$nativeListener
            if-eqz v0, :clear
            invoke-virtual {v1, v0}, $audio->abandonAudioFocus($listener)I
            :clear
            const/4 v0, 0
            iput-object v0, p0, $owner->LIZ:$audio
            return-void
        """, 16)
        val original = method.implementation!!.instructions.toList()

        method.captureNativeFocusAbandon()

        val code = method.implementation!!.instructions
        assertEquals(4, method.implementation!!.registerCount)
        assertEquals(original.size + 1, code.size)
        original.forEachIndexed { index, instruction ->
            assertSame(instruction, code[index + if (index >= 13) 1 else 0])
        }
        assertEquals(Opcode.INVOKE_STATIC, code[13].opcode)
        assertCall(code[13] as ReferenceInstruction, extension, "onNativeFocusAbandon",
            listOf("Ljava/lang/Object;"), "V", listOf(0))
        assertEquals(Opcode.INVOKE_VIRTUAL, code[14].opcode)
        assertCall(code[14] as ReferenceInstruction, audio, "abandonAudioFocus",
            listOf(listener), "I", listOf(1, 0))
        for (index in listOf(10, 12)) {
            assertEquals(code[15].location.codeAddress,
                code[index].location.codeAddress + (code[index] as OffsetInstruction).codeOffset)
        }
        assertEquals(code[17].location.codeAddress,
            method.implementation!!.tryBlocks.single().exceptionHandlers.single().handlerCodeAddress)
    }

    @Test
    fun `the registers and the wrapper are read off the host call rather than assumed`() {
        // A newer build renames the wrapper and lays the arguments out in other registers. The
        // first version of this bridge asserted v3, v2, v1, v0 and the name LX/0UUp;->j4, which
        // is the 46.2.3 build written into the patch. Everything here differs from that.
        val method = nativeOwnerMethod("anything", 8, """
            iget-object v7, p0, LX/1Gr4;->LIZ:$audio
            if-eqz v7, :done
            iget-object v5, p0, LX/1Gr4;->LIZIZ:LX/1Gr5;
            if-eqz v5, :done
            const/4 v6, 3
            const/4 v4, 2
            invoke-static {v7, v5, v6, v4}, LX/2ZZZ;->q9($audio${listener}II)I
            :done
            return-void
        """, 7)

        method.captureNativeFocusRequest()

        val code = method.implementation!!.instructions
        assertEquals(Opcode.INVOKE_STATIC, code[6].opcode)
        assertCall(code[6] as ReferenceInstruction, "LX/2ZZZ;", "q9",
            listOf(audio, listener, "I", "I"), "I", listOf(7, 5, 6, 4))
        // The answer lands in the last argument's register, which nothing reads again, and
        // the listener handed to the extension is the one the host handed to the platform.
        assertEquals(Opcode.MOVE_RESULT, code[7].opcode)
        assertEquals(4, (code[7] as OneRegisterInstruction).registerA)
        assertCall(code[8] as ReferenceInstruction, extension, "onNativeFocusRequestResult",
            listOf("Ljava/lang/Object;", "I"), "V", listOf(5, 4))
        assertEquals(Opcode.RETURN_VOID, code[9].opcode)
    }

    @Test
    fun `abandon takes its listener from the call it precedes`() {
        val method = nativeOwnerMethod("anything", 9, """
            iget-object v6, p0, LX/1Gr4;->LIZ:$audio
            if-eqz v6, :done
            iget-object v8, p0, LX/1Gr4;->LIZIZ:LX/1Gr5;
            if-eqz v8, :done
            invoke-virtual {v6, v8}, $audio->abandonAudioFocus($listener)I
            :done
            return-void
        """, 5)

        method.captureNativeFocusAbandon()

        val code = method.implementation!!.instructions
        assertCall(code[4] as ReferenceInstruction, extension, "onNativeFocusAbandon",
            listOf("Ljava/lang/Object;"), "V", listOf(8))
        assertCall(code[5] as ReferenceInstruction, audio, "abandonAudioFocus",
            listOf(listener), "I", listOf(6, 8))
    }

    @Test
    fun `a request that is not the last thing its method does is refused`() {
        // The answer register is only free because the host returns straight after the call.
        // A build that reads it afterwards would have the extension's result clobber it, and
        // that has to be a loud failure at patch time rather than a quiet one on a phone.
        val method = nativeOwnerMethod("anything", 6, """
            iget-object v3, p0, LX/1Gr4;->LIZ:$audio
            iget-object v2, p0, LX/1Gr4;->LIZIZ:LX/1Gr5;
            const/4 v1, 3
            const/4 v0, 2
            invoke-static {v3, v2, v1, v0}, LX/2ZZZ;->q9($audio${listener}II)I
            move-result v0
            return-void
        """, 6)
        val error = runCatching { method.captureNativeFocusRequest() }.exceptionOrNull()
        assertEquals(true, error is IllegalStateException)
        assertEquals(true, error!!.message!!.contains("last thing"))
    }

    @Test
    fun `a request that names its registers as a range is refused by its opcode`() {
        // Resolution takes a class for the shape of its calls, not their format. Enough register
        // pressure here and the host emits invoke-static/range, which is not the instruction the
        // registers are read off, and the cast used to happen before anything said so.
        val method = nativeOwnerMethod("anything", 8, """
            iget-object v4, p0, LX/1Gr4;->LIZ:$audio
            iget-object v5, p0, LX/1Gr4;->LIZIZ:LX/1Gr5;
            const/4 v6, 3
            const/4 v7, 2
            invoke-static/range {v4 .. v7}, LX/2ZZZ;->q9($audio${listener}II)I
            return-void
        """, 5)
        val error = runCatching { method.captureNativeFocusRequest() }.exceptionOrNull()
        assertEquals(true, error is IllegalStateException)
        assertEquals(true, error!!.message!!.contains("INVOKE_STATIC_RANGE"))
    }

    @Test
    fun `two focus requests in one method are refused by name`() {
        // The resolver takes a method that contains a request, never one that contains only one,
        // so a normal path and an error path reaching here has to say what it found.
        val method = nativeOwnerMethod("anything", 6, """
            iget-object v3, p0, LX/1Gr4;->LIZ:$audio
            iget-object v2, p0, LX/1Gr4;->LIZIZ:LX/1Gr5;
            const/4 v1, 3
            const/4 v0, 2
            invoke-static {v3, v2, v1, v0}, LX/2ZZZ;->q9($audio${listener}II)I
            invoke-static {v3, v2, v1, v0}, LX/2ZZZ;->q9($audio${listener}II)I
            return-void
        """, 6)
        val error = runCatching { method.captureNativeFocusRequest() }.exceptionOrNull()
        assertEquals(true, error is PatchException)
        assertEquals(true, error!!.message!!.contains("expected one focus request"))
    }

    @Test
    fun `two abandon calls in one method are refused by name`() {
        val method = nativeOwnerMethod("anything", 4, """
            iget-object v1, p0, LX/1Gr4;->LIZ:$audio
            iget-object v0, p0, LX/1Gr4;->LIZIZ:LX/1Gr5;
            invoke-virtual {v1, v0}, $audio->abandonAudioFocus($listener)I
            invoke-virtual {v1, v0}, $audio->abandonAudioFocus($listener)I
            return-void
        """, 4)
        val error = runCatching { method.captureNativeFocusAbandon() }.exceptionOrNull()
        assertEquals(true, error is PatchException)
        assertEquals(true, error!!.message!!.contains("expected one abandonAudioFocus"))
    }

    private fun assertCall(instruction: ReferenceInstruction, type: String, name: String,
                           parameters: List<String>, result: String, registers: List<Int>) {
        val target = instruction.reference as MethodReference
        assertEquals(type, target.definingClass)
        assertEquals(name, target.name)
        assertEquals(parameters, target.parameterTypes.map(CharSequence::toString))
        assertEquals(result, target.returnType)
        val call = instruction as FiveRegisterInstruction
        assertEquals(registers.size, call.registerCount)
        assertEquals(registers, listOf(call.registerC, call.registerD, call.registerE,
            call.registerF, call.registerG).take(call.registerCount))
    }

    private fun nativeOwnerMethod(name: String, registers: Int, body: String,
                                  returnIndex: Int): MutableMethod =
        nativeMethod(owner, name, listOf("Landroid/content/Context;"), registers, body).apply {
            implementation!!.run {
                addCatch("Ljava/lang/Exception;", newLabelForIndex(0),
                    newLabelForIndex(returnIndex), newLabelForIndex(returnIndex))
            }
        }

    private fun nativeMethod(type: String, name: String, parameters: List<String>,
                             registers: Int, body: String): MutableMethod =
        MutableMethod(ImmutableMethod(type, name,
            parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        // The fixture must own its labels as a decoded DEX method does. Plain addInstructions
        // transfers parser instructions without rebinding internal branch targets to this body.
        )).apply { addInstructionsWithLabels(0, body) }
}
