package app.morphe.patches.tiktok.interaction.gesture

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Like the video rests on, held to each TikTok build the bundle declares (#136).
 *
 * <p>The heart's assem kept its name, as the comment assem did, and shares its base with it. Its
 * onViewCreated is where the patch registers the heart's view, so the extension can find the
 * heart in the cell of the comment button a gesture picks. The assem implements the heart's
 * ability, whose one no-argument method is TikTok's own long press like: it asks whether the post
 * is liked, and only when it isn't runs the handler a tap on the heart runs, with
 * "long_press_like" where the tap passes "click_like". So the like counts, animates and goes
 * through Confirm likes the way a tap does, and it never takes a like back off. TikTok's own long
 * press like reaches the method through the ability, as the extension does.
 */
class LikePressAnchorsTest {
    @Test
    fun `the heart registers its view from one onViewCreated, beside the comment button's assem`() {
        Fixtures.forEachDeclared { apk ->
            val build = Build(apk)
            val digg = build.byType.getValue(DIGG_CLASS)
            val taken = digg.methods.filter { LikeViewFingerprint.takes(it, digg) }
            assertEquals("the heart's onViewCreated: ${taken.map { it.name }}", 1, taken.size)
            assertEquals(
                "the heart's assem and the comment button's no longer share a base",
                build.byType.getValue(VIDEO_COMMENT_ASSEM).superclass,
                digg.superclass,
            )
        }
    }

    @Test
    fun `the heart's ability has one no-argument press, which likes only a post that isn't liked`() {
        Fixtures.forEachDeclared { apk ->
            val build = Build(apk)
            val digg = build.byType.getValue(DIGG_CLASS)
            assertTrue("the heart's assem no longer implements the ability", LIKE_ABILITY in digg.interfaces)
            val instructions = build.pressInstructions()
            val addresses = instructions.runningFold(0) { address, instruction -> address + instruction.codeUnits }
            val label = instructions.indexOfFirst { it.loads(LONG_PRESS_LIKE) }
            assertTrue("the press no longer says it's a long press like", label >= 0)
            val like = (label + 1 until instructions.size).firstOrNull { instructions[it].isHandler() } ?: -1
            assertTrue("the press no longer runs the heart's handler after its label", like > label)

            // The check comes first, and a branch from it jumps past the like.
            val check = instructions.indexOfFirst { it.asksLiked(build) }
            assertTrue("the press no longer asks whether the post is liked", check in 0 until label)
            val skips = (check until like).any { index ->
                val branch = instructions[index]
                branch is OffsetInstruction && branch.opcode in BRANCHES &&
                    addresses[index] + branch.codeOffset > addresses[like]
            }
            assertTrue("nothing skips the like for a liked post", skips)
        }
    }

    @Test
    fun `a tap on the heart runs the same handler, and TikTok's long press like runs the press`() {
        Fixtures.forEachDeclared { apk ->
            val build = Build(apk)
            val instructions = build.pressInstructions()
            val label = instructions.indexOfFirst { it.loads(LONG_PRESS_LIKE) }
            val handler = instructions.drop(label + 1).firstOrNull { it.isHandler() }
                ?.getReference<MethodReference>()?.name
            assertTrue("the press runs no handler of the heart's", label >= 0 && handler != null)

            val taps = build.methods.filter { (_, method) ->
                method.name == "onClick" && method.parameterTypes.map(CharSequence::toString) == listOf(VIEW) &&
                    method.implementation?.instructions?.toList()?.let { code ->
                        val tap = code.indexOfFirst { it.loads(CLICK_LIKE) }
                        tap >= 0 && code.drop(tap).any { it.isHandler(handler) }
                    } == true
            }.toList()
            assertTrue("no click listener taps the heart through $handler", taps.isNotEmpty())

            val press = build.press()
            val callers = build.methods.filter { (_, method) ->
                method.implementation?.instructions?.any { instruction ->
                    instruction.opcode == Opcode.INVOKE_INTERFACE && instruction.getReference<MethodReference>()?.let {
                        it.definingClass == LIKE_ABILITY && it.name == press.name && it.parameterTypes.isEmpty()
                    } == true
                } == true
            }.map { it.first }.toSet()
            assertTrue(
                "TikTok's long press like no longer reaches the press: ${callers.map { it.type }}",
                callers.any { caller -> caller.type == LONG_PRESS_DIGG || caller.fields.any { it.type == LONG_PRESS_DIGG } },
            )
        }
    }

    @Test
    fun `the patch registers the heart and the extension presses it through the ability`() {
        val repo = if (File("src/main/kotlin").isDirectory) File("..") else File(".")
        val patch = File(repo, "patches/src/main/kotlin/app/morphe/patches/tiktok/interaction/gesture/LongPressPatch.kt").readText()
        assertTrue("the heart's view is not registered", patch.contains("LikeViewFingerprint.method.addInstruction("))
        assertTrue(
            "the registration doesn't hand the extension the assem and its view",
            patch.contains("registerLikeView(Ljava/lang/Object;Landroid/view/View;)V"),
        )
        val extension = File(repo, "extensions/tiktok/src/main/java/app/morphe/extension/tiktok/interaction/GestureActions.java").readText()
        assertTrue("the extension takes the registration", extension.contains("public static void registerLikeView(Object owner, View view)"))
        val ability = LIKE_ABILITY.removePrefix("L").removeSuffix(";").replace('/', '.')
        assertTrue("the extension looks for another ability than $ability", extension.contains("\"$ability\""))
    }

    /** The ability's one method that takes nothing and returns nothing, as the extension picks it. */
    private fun Build.press(): Method {
        val presses = byType.getValue(LIKE_ABILITY).methods.filter { it.parameterTypes.isEmpty() && it.returnType == "V" }
        assertEquals("no-argument methods on the ability: ${presses.map { it.name }}", 1, presses.size)
        return presses.single()
    }

    /** The heart's assem's code for the press, the method the extension invokes on it. */
    private fun Build.pressInstructions(): List<Instruction> {
        val press = press()
        val implementation = byType.getValue(DIGG_CLASS).methods.single {
            it.name == press.name && it.parameterTypes.isEmpty() && it.returnType == "V"
        }
        return implementation.implementation!!.instructions.toList()
    }

    private fun Instruction.loads(string: String) =
        (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
            getReference<StringReference>()?.string == string

    /** A call to the heart's handler, which takes the heart's view and a label saying what pressed it. */
    private fun Instruction.isHandler(name: String? = null) =
        getReference<MethodReference>()?.let {
            it.definingClass == DIGG_CLASS && (name == null || it.name == name) && it.returnType == "V" &&
                it.parameterTypes.map(CharSequence::toString) == listOf(VIEW, STRING)
        } == true

    /** A read of the post's liked flag, made here or in a static helper of the heart's assem. */
    private fun Instruction.asksLiked(build: Build): Boolean {
        val call = getReference<MethodReference>() ?: return false
        if (call.isLikedFlag()) return true
        if (opcode != Opcode.INVOKE_STATIC || call.definingClass != DIGG_CLASS || call.returnType != "Z") return false
        if (call.parameterTypes.map(CharSequence::toString) != listOf(AWEME)) return false
        val helper = build.byType.getValue(DIGG_CLASS).methods.singleOrNull {
            it.name == call.name && it.parameterTypes.map(CharSequence::toString) == listOf(AWEME) && it.returnType == "Z"
        } ?: return false
        return helper.implementation?.instructions?.any { it.getReference<MethodReference>()?.isLikedFlag() == true } == true
    }

    private fun MethodReference.isLikedFlag() =
        definingClass == AWEME && name == "isLike" && parameterTypes.isEmpty() && returnType == "Z"

    /** One fixture's classes by type; methods are walked on each ask, never held (the dex has millions). */
    private class Build(apk: File) {
        val byType = HashMap<String, ClassDef>()

        init {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) byType.putIfAbsent(classDef.type, classDef)
            }
        }

        val methods: Sequence<Pair<ClassDef, Method>>
            get() = byType.values.asSequence().flatMap { classDef -> classDef.methods.asSequence().map { classDef to it } }
    }

    private companion object {
        const val LIKE_ABILITY = "Lcom/ss/android/ugc/feed/platform/cell/ability/VideoDiggAssemAbility;"
        const val LONG_PRESS_DIGG = "Lcom/ss/android/ugc/aweme/feed/assem/digg/LongPressDiggAssem;"
        const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
        const val VIEW = "Landroid/view/View;"
        const val STRING = "Ljava/lang/String;"
        const val LONG_PRESS_LIKE = "long_press_like"
        const val CLICK_LIKE = "click_like"

        /** The conditional branches, the ones that can skip the like for one answer and not the other. */
        val BRANCHES = setOf(
            Opcode.IF_EQ, Opcode.IF_NE, Opcode.IF_LT, Opcode.IF_GE, Opcode.IF_GT, Opcode.IF_LE,
            Opcode.IF_EQZ, Opcode.IF_NEZ, Opcode.IF_LTZ, Opcode.IF_GEZ, Opcode.IF_GTZ, Opcode.IF_LEZ,
        )
    }
}
