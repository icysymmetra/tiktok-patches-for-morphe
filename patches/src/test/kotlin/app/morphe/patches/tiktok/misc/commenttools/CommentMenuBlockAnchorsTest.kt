package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import app.morphe.patcher.util.getReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Block this commenter in the comment menu (#169) rests on, held to every fixture: one list
 * sink on the panel, the Report row the extension copies its row's shape from, the block glyph
 * the patch reads off TikTok's profile Block action, the sheet the extension closes and the
 * user-blocked event it posts.
 */
class CommentMenuBlockAnchorsTest {
    private val comment = "Lcom/ss/android/ugc/aweme/comment/model/Comment;"
    private val user = "Lcom/ss/android/ugc/aweme/profile/model/User;"
    private val deleteModel = "Lcom/ss/android/ugc/aweme/commentv2/commentlist/viewmodel/CommentDeleteVM;"
    private val dialog = "Landroidx/fragment/app/DialogFragment;"

    @Test
    fun `the comment menu's Block row anchors resolve exactly once on every fixture`() {
        val apks = Fixtures.apks()
        assertTrue("no fixture APKs", apks.isNotEmpty())
        for (apk in apks) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
            val byType = classes.associateBy { it.type }
            fun matches(fingerprint: Fingerprint): List<Pair<ClassDef, Method>> =
                classes.flatMap { classDef -> classDef.methods.filter { fingerprint.takes(it, classDef) }.map { classDef to it } }
            fun one(what: String, fingerprint: Fingerprint): Pair<ClassDef, Method> {
                val found = matches(fingerprint)
                assertEquals("${apk.name}: $what: ${found.map { "${it.second.definingClass}->${it.second.name}" }}", 1, found.size)
                return found.single()
            }

            val (_, sink) = one("the comment menu's list sink", CommentMenuListFingerprint)
            val (_, items) = one("the comment menu's row builder", CommentMenuItemsFingerprint)
            val reportActions = matches(AdReportActionKeyFingerprint).map { it.first }
            assertTrue("${apk.name}: no ad Report share action", reportActions.isNotEmpty())
            val (blockAction, _) = one("the profile Block share action", ProfileBlockActionKeyFingerprint)

            // Throws, naming what it missed, unless exactly one ad Report icon getter carries the
            // menu's Report icon and the Block action answers the same getter.
            val icon = commentMenuBlockIcon(items, reportActions, blockAction)
            assertEquals("${apk.name}: the block glyph is an app resource", 0x7f, icon ushr 24)
            // 47.4.3 asks User.isBlock() and returns 0x7f010062 when blocked.
            assertEquals("${apk.name}: the not-blocked glyph", mapOf("47.4.3" to 0x7f01002c)[Fixtures.versionOf(apk)], icon)
            assertNotEquals("${apk.name}: the block glyph is not Report's", items.reportRowIcon(), icon)

            // The hook: the icon, then the rows through the extension and back into the parameter,
            // ahead of the untouched sink.
            val hooked = MutableMethod(sink)
            val original = hooked.implementation!!.instructions.toList()
            hooked.resolveCommentMenuBlock(icon)()
            val after = hooked.implementation!!.instructions.toList()
            val registers = hooked.implementation!!.registerCount
            assertEquals(registers, sink.implementation!!.registerCount)
            assertEquals(icon, (after[0] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals("$COMMENT_MENU_BLOCK_DESCRIPTOR->setIcon(I)V", after[1].getReference<MethodReference>().toString())
            assertEquals((after[0] as OneRegisterInstruction).registerA, (after[1] as FiveRegisterInstruction).registerC)
            val route = after[2] as RegisterRangeInstruction
            assertEquals(Opcode.INVOKE_STATIC_RANGE, after[2].opcode)
            assertEquals("$COMMENT_MENU_BLOCK_DESCRIPTOR->items(Ljava/lang/Object;Ljava/util/List;)Ljava/util/List;",
                after[2].getReference<MethodReference>().toString())
            assertEquals(registers - 2, route.startRegister)
            assertEquals(2, route.registerCount)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, after[3].opcode)
            assertEquals(registers - 1, (after[3] as OneRegisterInstruction).registerA)
            assertEquals(original.map { it.opcode }, after.drop(4).map { it.opcode })

            // The Report row's class: the six-argument constructor and the fields the extension reads.
            val instructions = items.implementation!!.instructions.toList()
            val reportAt = instructions.indexOfFirst {
                it.opcode == Opcode.CONST_STRING && it.getReference<StringReference>()?.string == "report"
            }
            val rowType = (reportAt downTo 0).map { instructions[it] }
                .first { it.opcode == Opcode.NEW_INSTANCE }.getReference<TypeReference>()!!.type
            val row = byType[rowType]
            assertNotNull("${apk.name}: the Report row's class $rowType", row)
            val constructors = row!!.methods.filter { method ->
                val types = method.parameterTypes.map(CharSequence::toString)
                method.name == "<init>" && types.size == 6 && types[0] == "Ljava/lang/String;" &&
                    types[1] == "Ljava/lang/Integer;" && types[2] == "Ljava/lang/String;" &&
                    types[3] == "Lkotlin/jvm/functions/Function0;" && types[5] == "Lkotlin/jvm/functions/Function1;"
            }
            assertEquals("${apk.name}: $rowType's (key, icon, label, click, style, callback) constructor", 1, constructors.size)
            val styleType = constructors.single().parameterTypes[4].toString()
            val rowFields = instanceFields(row, byType)
            assertEquals("${apk.name}: $rowType's one icon field", 1, rowFields.count { it == "Ljava/lang/Integer;" })
            assertEquals("${apk.name}: $rowType's one style field", 1, rowFields.count { it == styleType })
            val style = byType[styleType]
            assertNotNull("${apk.name}: the row style $styleType", style)
            assertEquals("${apk.name}: $styleType's one int of corner flags", 1, instanceFields(style!!, byType).count { it == "I" })
            assertEquals("${apk.name}: $styleType's one constructor", 1, style.methods.count { it.name == "<init>" })

            // The panel: one Comment for the recycle check, and the sheet the tap closes.
            val panel = byType[COMMENT_MENU_PANEL]!!
            val panelFields = instanceFields(panel, byType)
            assertEquals("${apk.name}: the panel's one Comment field", 1, panelFields.count { it == comment })
            assertTrue("${apk.name}: the panel holds no DialogFragment to close",
                panelFields.any { extends(it, dialog, byType) })

            // The event a block from a profile posts, which the comment list answers.
            val subscribers = byType[deleteModel]!!.methods.filter {
                it.name == "onUserBlockedEvent" && it.parameterTypes.size == 1
            }
            assertEquals("${apk.name}: CommentDeleteVM's onUserBlockedEvent", 1, subscribers.size)
            val event = byType[subscribers.single().parameterTypes.single().toString()]!!
            assertEquals("${apk.name}: ${event.type}'s one User field", 1, instanceFields(event, byType).count { it == user })
            assertTrue("${apk.name}: ${event.type} has no no-argument constructor",
                event.methods.any { it.name == "<init>" && it.parameterTypes.isEmpty() })
            assertTrue("${apk.name}: ${event.type} has no public post()", event.methods.any {
                it.name == "post" && it.parameterTypes.isEmpty() && AccessFlags.PUBLIC.isSet(it.accessFlags)
            })
        }
    }

    /** The types of every instance field of [classDef] and its superclasses inside the app. */
    private fun instanceFields(classDef: ClassDef, byType: Map<String, ClassDef>): List<String> {
        val out = mutableListOf<String>()
        var at: ClassDef? = classDef
        while (at != null) {
            at.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.forEach { out += it.type }
            at = at.superclass?.let(byType::get)
        }
        return out
    }

    private fun extends(type: String, ancestor: String, byType: Map<String, ClassDef>): Boolean {
        var at: String? = type
        repeat(12) {
            if (at == ancestor) return true
            at = at?.let(byType::get)?.superclass ?: return false
        }
        return false
    }
}
