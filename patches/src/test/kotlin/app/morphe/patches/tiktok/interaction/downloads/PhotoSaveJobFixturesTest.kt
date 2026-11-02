package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.numberOfParameterRegisters
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The photo save job Original Photo Mode hooks, held to every retained fixture through the
 * fingerprint's own predicate ([isPhotoSaveJob]). On 47.0.3 a photo post's Download never reaches
 * the video download start the older hook sits on, so with the switch on nothing of it ran (the
 * S22, 2026-09-23); every photo save runs this job instead. Exactly one method has its shape on
 * each build, and on 47.0.3 it is the one that logs the job it starts.
 */
class PhotoSaveJobFixturesTest {
    @Test
    fun `every fixture has exactly one photo save job`() {
        for (apk in Fixtures.apks()) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val jobs = container.dexEntryNames.flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes.flatMap { classDef ->
                    classDef.methods.filter { it.isPhotoSaveJob(classDef) }
                }
            }
            assertEquals("${apk.name}: photo save jobs ${jobs.map { "${it.definingClass}->${it.name}" }}", 1, jobs.size)
            val job = jobs.single()
            assertTrue("${apk.name}: the job has a body to hook", job.implementation != null)
            if (Fixtures.versionOf(apk) in Fixtures.declaredVersions()) {
                val strings = job.implementation!!.instructions.mapNotNull {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
                }
                assertTrue("${apk.name}: the job logs the save it starts, got $strings", "initializeJob aid=" in strings)
            }
        }
    }

    /** The image job above does not see Download video's separate native conversion entry. */
    @Test
    fun `each declared conversion is intercepted before native state with only the Aweme argument`() {
        val owners = mapOf("47.4.3" to "LX/0Cs0;")
        // The method-id entry call's name is the build's own (47.0.3 obfuscated it), although its owner/proto hold.
        val frameEntries = mapOf("47.4.3" to "push")
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val matches = container.dexEntryNames.asSequence().flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes.asSequence().flatMap { owner ->
                    owner.methods.asSequence().filter { PhotoVideoConversionFingerprint.takes(it, owner) }
                }
            }.toList()
            assertEquals("conversion entries ${matches.map { "${it.definingClass}->${it.name}" }}", 1, matches.size)
            val native = matches.single()
            assertEquals(owners.getValue(Fixtures.versionOf(apk)), native.definingClass)
            assertEquals("LIZJ", native.name)
            assertTrue(AccessFlags.PUBLIC.isSet(native.accessFlags) && AccessFlags.STATIC.isSet(native.accessFlags))
            assertEquals(listOf("Lcom/ss/android/ugc/aweme/feed/model/Aweme;", "Ljava/lang/String;",
                "Lkotlin/jvm/functions/Function1;"), native.parameterTypes.map(CharSequence::toString))
            assertEquals("V", native.returnType)

            val method = MutableMethod(native)
            val original = method.implementation!!.instructions.toList()
            val registers = method.implementation!!.registerCount
            assertEquals("the captured native frame", 14, registers)
            val push = original.indexOfFirst {
                it.getReference<MethodReference>()?.let { ref ->
                    ref.definingClass == "Lcom/bytedance/pumbaa/utility/method_id/MethodIDManager;" &&
                        ref.name == frameEntries.getValue(Fixtures.versionOf(apk)) &&
                        ref.parameterTypes.map(CharSequence::toString) == listOf("I") && ref.returnType == "Z"
                } == true
            }
            assertEquals("the native method-id frame starts at entry", 1, push)
            assertTrue("native conversion owns its active flag", original.any {
                it.opcode == Opcode.SPUT_BOOLEAN && it.getReference<FieldReference>()?.let { field ->
                    field.definingClass == native.definingClass && field.name == "LIZJ" && field.type == "Z"
                } == true
            })
            assertTrue("native conversion publishes video progress", original.any {
                it.getReference<StringReference>()?.string == "video_download_status"
            })
            assertTrue("the callback is a native video result", original.any {
                it.getReference<MethodReference>()?.let { ref ->
                    ref.definingClass == "Lkotlin/jvm/functions/Function1;" && ref.name == "invoke"
                } == true
            })

            method.interceptPhotoVideoConversion()
            val hooked = method.implementation!!.instructions.toList()
            assertEquals(registers, method.implementation!!.registerCount)
            assertEquals(original.size + 4, hooked.size)
            assertEquals(Opcode.INVOKE_STATIC_RANGE, hooked[0].opcode)
            val call = hooked[0] as RegisterRangeInstruction
            assertEquals(1, call.registerCount)
            assertEquals("p0 is the Aweme, not the String or callback", registers - method.numberOfParameterRegisters,
                call.startRegister)
            val target = hooked[0].getReference<MethodReference>()!!
            assertEquals("Lapp/morphe/extension/tiktok/download/OriginalPhotos;", target.definingClass)
            assertEquals("startImageAsVideo", target.name)
            assertEquals(listOf("Ljava/lang/Object;"), target.parameterTypes.map(CharSequence::toString))
            assertEquals("Z", target.returnType)
            assertEquals(Opcode.MOVE_RESULT, hooked[1].opcode)
            assertEquals(0, (hooked[1] as OneRegisterInstruction).registerA)
            assertEquals(Opcode.IF_EQZ, hooked[2].opcode)
            assertEquals(0, (hooked[2] as OneRegisterInstruction).registerA)
            assertEquals(Opcode.RETURN_VOID, hooked[3].opcode)
            val branchAddress = hooked.take(2).sumOf { it.codeUnits }
            assertEquals("off/live choices enter the original first instruction, before the method-id frame",
                hooked.take(4).sumOf { it.codeUnits }, branchAddress + (hooked[2] as OffsetInstruction).codeOffset)
            original.forEachIndexed { index, instruction -> assertSame(instruction, hooked[index + 4]) }
        }
    }
}
