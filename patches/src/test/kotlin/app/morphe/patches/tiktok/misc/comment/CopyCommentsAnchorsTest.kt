package app.morphe.patches.tiktok.misc.comment

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Copy comments without username rests on, held to every fixture.
 *
 * The comment menu's Copy is the method that logs "copy_comment". On 46.2.3 it joined the name
 * prefix to the text itself and handed the string to a plain-text clipboard helper, the route the
 * patch hooked first. From 46.9.3 it hands the prefix, the text and the text's emoji spans to one
 * static builder that returns the ClipData, and never calls the plain helper (issue #28, second
 * report): the patch blanks the prefix at the top of that builder. This test says which route
 * each fixture's menu takes and that the patch covers it, so a build that moves the menu again
 * fails here rather than copying the username on a phone.
 */
class CopyCommentsAnchorsTest {
    private val clipData = "Landroid/content/ClipData;"
    private val plainHelper = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Landroid/content/Context;", "Lcom/bytedance/bpea/basics/Cert;")
    private val builder = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/util/List;")

    @Test
    fun `the comment menu's Copy reaches a method the patch hooks on every fixture`() {
        val covered = mutableMapOf<String, String>()
        for (apk in Fixtures.apks()) {
            val menus = mutableListOf<Method>()
            val plainHelpers = mutableSetOf<String>()
            val builders = mutableSetOf<String>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            container.dexEntryNames.forEach { entry ->
                container.getEntry(entry)!!.dexFile.classes.forEach { classDef ->
                    classDef.methods.forEach methods@{ method ->
                        val instructions = method.implementation?.instructions?.toList() ?: return@methods
                        val newPlainText = instructions.any { invokes(it, clipData, "newPlainText") }
                        val parameters = method.parameterTypes.map { it.toString() }
                        if (newPlainText && method.returnType == "V" && parameters == plainHelper) {
                            plainHelpers += "${method.definingClass}->${method.name}"
                        }
                        if (newPlainText && AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == clipData &&
                            parameters == builder && instructions.any { loads(it, "copy_label") }
                        ) {
                            builders += "${method.definingClass}->${method.name}"
                        }
                        if (instructions.any { loads(it, "copy_comment") } &&
                            instructions.any { invokes(it, "Lcom/ss/android/ugc/aweme/comment/model/Comment;", "getText") }
                        ) {
                            menus += method
                        }
                    }
                }
            }
            assertTrue("${apk.name}: the plain clipboard helper", plainHelpers.isNotEmpty())
            assertTrue("${apk.name}: more than one ClipData builder: $builders", builders.size <= 1)
            // The comment sheet's menu, and on 46.2.3 also Favorites > Comments, which logs the same event.
            assertTrue("${apk.name}: no Copy logs copy_comment", menus.isNotEmpty())
            val routes = menus.associate { menu ->
                val calls = menu.implementation!!.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                    .map { "${it.definingClass}->${it.name}" }
                val route = when {
                    calls.any { it in builders } -> "builder"
                    calls.any { it in plainHelpers } -> "plain helper"
                    else -> "neither: $calls"
                }
                "${menu.definingClass}->${menu.name}" to route
            }
            assertEquals("${apk.name}: a Copy through a route the patch doesn't hook", emptyMap<String, String>(), routes.filterValues { it.startsWith("neither") })
            covered[apk.name] = routes.values.sorted().joinToString()
        }
        Fixtures.declaredVersions().forEach { version ->
            assertTrue("$version takes the builder route: $covered", covered.entries.any {
                Fixtures.versionOf(java.io.File(it.key)) == version && "builder" in it.value
            })
        }
    }

    private fun invokes(instruction: Instruction, owner: String, name: String): Boolean {
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return reference.definingClass == owner && reference.name == name
    }

    private fun loads(instruction: Instruction, text: String): Boolean =
        (instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO) &&
            ((instruction as ReferenceInstruction).reference as StringReference).string == text
}
