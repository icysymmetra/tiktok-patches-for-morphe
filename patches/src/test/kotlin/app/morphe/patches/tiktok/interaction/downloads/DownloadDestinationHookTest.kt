/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.p0Register
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.*
import org.junit.Test

/** Old hook tests did not cover a per-invocation folder surviving a consumed filename. */
class DownloadDestinationHookTest {
    @Test fun `video save and lookup preserve every original argument with small and large frames`() {
        for (locals in listOf(2, 18, 100)) for (consume in listOf(false, true)) {
            exercise(locals, listOf("Landroid/content/Context;", "Ljava/lang/String;"), 1, "getVideoDestination", consume)
        }
    }

    @Test fun `photo save keeps its extra argument and captures the photo folder`() {
        exercise(20, listOf("Landroid/content/Context;", "Ljava/lang/String;", "Ljava/lang/String;"), 1, "getPhotoDestination", true)
    }

    @Test fun `image post copy reads its original kind flag before retaining its folder`() {
        exercise(16, listOf("Landroid/content/Context;", "Ljava/lang/String;", "Ljava/lang/String;", "Z", "Ljava/lang/String;", "I"),
            2, "getMediaDestination", true)
    }

    @Test fun `an unencodable result register stops patching`() {
        val method = host(254, listOf("Landroid/content/Context;", "Ljava/lang/String;"), 1)
        try {
            method.captureDownloadDestination(1, "getVideoDestination", true)
            fail("The destination cannot fit move-result-object")
        } catch (expected: PatchException) {
            assertTrue(expected.message.orEmpty().contains("move-result"))
        }
    }

    private fun exercise(locals: Int, types: List<String>, nameParameter: Int, getter: String, consume: Boolean) {
        val original = host(locals, types, nameParameter)
        val changed = original.captureDownloadDestination(nameParameter, getter, consume)
        for (source in listOf("alice-stage.mp4", "bob-stage.mp4")) {
            val parameters = types.indices.map { index -> when {
                index == nameParameter -> source
                types[index] == "Z" -> true
                types[index] == "I" -> 7
                else -> "original-$index"
            } }
            val registers = mutableMapOf<Int, Any>()
            parameters.forEachIndexed { index, value -> registers[changed.p0Register + index] = value }
            val calls = mutableListOf<String>()
            var result: Any = ""
            var returned: Any? = null
            for (instruction in changed.implementation!!.instructions) {
                when (instruction.opcode) {
                    Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_FROM16 -> {
                        instruction as TwoRegisterInstruction
                        registers[instruction.registerA] = registers.getValue(instruction.registerB)
                    }
                    Opcode.INVOKE_STATIC_RANGE -> {
                        val call = instruction.getReference<MethodReference>()!!
                        instruction as RegisterRangeInstruction
                        val args = (0 until instruction.registerCount).map { registers.getValue(instruction.startRegister + it) }
                        assertEquals("The lookup must use the staging name", source, args[0])
                        calls += call.name
                        result = if (call.name == getter) {
                            if (getter == "getMediaDestination") assertEquals(true, args[1])
                            "Download/${source.substringBefore('-')}"
                        } else {
                            assertEquals(if (consume) "consumeDestinationName" else "resolveDestinationName", call.name)
                            "same.mp4"
                        }
                    }
                    Opcode.MOVE_RESULT_OBJECT -> registers[(instruction as OneRegisterInstruction).registerA] = result
                    Opcode.RETURN_OBJECT -> returned = registers[(instruction as OneRegisterInstruction).registerA]
                    else -> fail("Unexpected instruction ${instruction.opcode}")
                }
            }
            assertEquals(listOf(getter, if (consume) "consumeDestinationName" else "resolveDestinationName"), calls)
            assertEquals("The host receives the formatted filename", "same.mp4", returned)
            assertEquals("Its folder lives outside the host's register frame", "Download/${source.substringBefore('-')}",
                registers[changed.p0Register + nameParameter])
            parameters.forEachIndexed { index, value ->
                if (index != nameParameter) assertEquals("Original argument $index changed", value, registers[original.p0Register + index])
            }
        }
    }

    private fun host(locals: Int, types: List<String>, nameParameter: Int) = MutableMethod(
        ImmutableMethod("LX/DownloadHost;", "save", types.map { ImmutableMethodParameter(it, null, null) },
            "Ljava/lang/String;", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(locals + types.size, emptyList(), null, null)),
    ).apply { addInstructions(0, "return-object p$nameParameter") }
}
