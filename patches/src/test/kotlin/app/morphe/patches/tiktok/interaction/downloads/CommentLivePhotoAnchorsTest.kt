package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.Fixtures
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The seam the live photo clip hook sits on, held to what the patch assumes on every retained
 * fixture: one method in the app asks for storage under the comment photo save policy, it takes
 * nothing and returns nothing, it reads the comment through a call returning the real-named
 * Comment into a low register, and the comment image model keeps the live-photo field and the
 * clip's address list. The tapped photo's index, unboxed from an Integer just before the comment
 * on the declared target, is held there; an older build saved the first photo with no index and
 * gets the one-argument call, which the test reports rather than fails.
 */
class CommentLivePhotoAnchorsTest {
    private val policy = "bpea-comment_photo_save_request_storage"
    private val imageStruct = "Lcom/ss/android/ugc/aweme/comment/model/CommentImageStruct;"
    private val liveModel = "Lcom/ss/android/ugc/aweme/comment/model/CommentLivePhotoInfoModel;"

    @Test
    fun `every retained fixture saves a comment photo through one routine the clip hook can sit in`() {
        val apks = Fixtures.apks()
        assertTrue("fixtures: ${apks.map { it.name }}", apks.containsAll(Fixtures.declared()))
        val declared = Fixtures.declaredVersions()
        apks.forEach { apk ->
            val app = load(apk)
            val holders = app.values.flatMap { def -> def.methods.filter { it.holdsPolicy() }.map { def to it } }
            assertEquals("${apk.name}: methods asking for storage under $policy", 1, holders.size)
            val (_, method) = holders.single()
            assertEquals("${apk.name}: the save routine's return type", "V", method.returnType)
            assertTrue("${apk.name}: the save routine takes nothing", method.parameterTypes.isEmpty())

            val instructions = method.implementation!!.instructions.toList()
            val commentCall = instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_VIRTUAL &&
                    it.getReference<MethodReference>()?.returnType == COMMENT_MODEL_DESCRIPTOR
            }
            assertTrue("${apk.name}: the routine reads its comment", commentCall >= 0)
            assertEquals("${apk.name}: the comment lands in a register", Opcode.MOVE_RESULT_OBJECT, instructions[commentCall + 1].opcode)
            val commentRegister = (instructions[commentCall + 1] as OneRegisterInstruction).registerA
            assertTrue("${apk.name}: the comment sits in v$commentRegister, past a plain invoke", commentRegister < 16)

            val indexCall = instructions.subList(0, commentCall).indexOfLast {
                it.getReference<MethodReference>()?.let { reference ->
                    reference.definingClass == "Ljava/lang/Integer;" && reference.name == "intValue"
                } == true
            }
            val declaredTarget = Fixtures.versionOf(apk) in declared
            if (indexCall < 0) {
                assertTrue("${apk.name}: a declared target unboxes the tapped photo's index before the comment", !declaredTarget)
                println("${apk.name}: saves the first photo with no index (the one-argument call)")
            } else {
                assertEquals("${apk.name}: the index lands in a register", Opcode.MOVE_RESULT, instructions[indexCall + 1].opcode)
                assertTrue("${apk.name}: the index is read right before the comment", commentCall - indexCall <= 4)
                val indexRegister = (instructions[indexCall + 1] as OneRegisterInstruction).registerA
                assertTrue("${apk.name}: the index sits in v$indexRegister, past a plain invoke", indexRegister < 16)
                assertTrue("${apk.name}: the comment and the index sit in different registers", commentRegister != indexRegister)
                // The hook reads the index register right after the comment lands, so nothing
                // between the unboxing and the comment call may write that register.
                val rewritten = instructions.subList(indexCall + 2, commentCall).filter {
                    it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == indexRegister
                }
                assertTrue("${apk.name}: v$indexRegister is rewritten before the comment call: $rewritten", rewritten.isEmpty())
            }

            // The three reads the extension makes, each pinned: a renamed one reads as "no
            // clip" on every photo, so the fixture has to say when a name moves.
            val commentModel = app[COMMENT_MODEL_DESCRIPTOR] ?: error("${apk.name}: no $COMMENT_MODEL_DESCRIPTOR")
            assertEquals("${apk.name}: the image list getter on the comment", 1,
                commentModel.methods.count { it.name == "getImageList" && it.returnType == "Ljava/util/List;" && it.parameterTypes.isEmpty() })
            val image = app[imageStruct] ?: error("${apk.name}: no $imageStruct")
            assertEquals("${apk.name}: the live-photo field on the comment image", 1,
                image.fields.count { it.name == "livePhotoInfoModel" && it.type == liveModel })
            val live = app[liveModel] ?: error("${apk.name}: no $liveModel")
            assertEquals("${apk.name}: the clip's address list on the live-photo model", 1,
                live.fields.count { it.name == "urlList" && it.type == "Ljava/util/List;" })
        }
    }

    private fun Method.holdsPolicy(): Boolean =
        implementation?.instructions?.any { it.getReference<StringReference>()?.string == policy } == true

    private fun load(apk: File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classes = HashMap<String, ClassDef>()
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach { classes.putIfAbsent(it.type, it) }
        }
        return classes
    }
}
