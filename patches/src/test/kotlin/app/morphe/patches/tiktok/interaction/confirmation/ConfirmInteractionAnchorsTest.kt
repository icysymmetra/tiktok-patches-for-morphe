package app.morphe.patches.tiktok.interaction.confirmation

import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Confirm feed interactions asks before a comment like, a story like and a quick repost,
 * held to every fixture with the patch's own fingerprints.
 *
 * <p>None of the three has a click method of its own on a named class. The comment like is one
 * handler on three controls (the comment list's like view and a photo post's two), the story like
 * is a static method one of TikTok's generated listeners reaches by index, and the quick repost is
 * the repost call of the bar under a video, which its repost button and its guide both make. Each
 * fingerprint has to take exactly one method on every build the patch runs on, or the patcher's
 * pick falls to class order.
 */
class ConfirmInteractionAnchorsTest {
    private val fingerprints = listOf(
        CommentLikeClickFingerprint,
        PhotoCommentLikeClickFingerprint,
        PhotoCommentLikeClickV2Fingerprint,
        StoryLikeClickFingerprint,
        QuickRepostFingerprint,
    )

    @Test
    fun `every confirmation fingerprint takes exactly one method on every fixture`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val taken = fingerprints.associateWith { mutableListOf<String>() }
            for ((classDef, method) in build.methods) {
                for (fingerprint in fingerprints) {
                    if (fingerprint.takes(method, classDef)) taken.getValue(fingerprint) += "${classDef.type}->${method.name}"
                }
            }
            for ((fingerprint, methods) in taken) {
                assertEquals("${apk.name}: ${fingerprint.javaClass.simpleName} takes $methods", 1, methods.size)
            }
        }
    }

    /** The list's like view asks on the way in, and the extension reads the one comment it keeps. */
    @Test
    fun `the comment list's like view keeps exactly one comment`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val (control, _) = build.single(CommentLikeClickFingerprint)
            val comments = build.hierarchy(control).sumOf { owner -> owner.fields.count { it.type == COMMENT_MODEL } }
            assertEquals("${apk.name}: ${control.type} keeps $comments comments", 1, comments)
        }
    }

    /**
     * A photo post's like asks right after it reads its comment, which only holds while nothing
     * before that read calls out or overwrites p0, and the plain invoke reaches only v0 to v15.
     */
    @Test
    fun `a photo post's like reads its comment before it does anything else`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            for (fingerprint in listOf(PhotoCommentLikeClickFingerprint, PhotoCommentLikeClickV2Fingerprint)) {
                val (control, like) = build.single(fingerprint)
                val read = like.firstCommentRead()
                assertTrue("${apk.name}: ${control.type} reads no comment", read >= 0)
                val before = like.implementation!!.instructions.take(read)
                val owner = like.implementation!!.registerCount - like.parameterTypes.size - 1
                assertTrue("${apk.name}: ${control.type} calls out before it reads its comment: ${before.map { it.opcode }}",
                    before.none { it.opcode.name.startsWith("INVOKE") })
                assertTrue("${apk.name}: ${control.type} overwrites p0 before it reads its comment",
                    before.none { (it as? com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction)?.registerA == owner })
                val kept = (like.implementation!!.instructions.elementAt(read) as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction).registerA
                assertTrue("${apk.name}: ${control.type} keeps its comment past v15", kept <= 15)
                assertTrue("${apk.name}: ${control.type}'s p0 is past v15", owner <= 15)
            }
        }
    }

    /**
     * The hook goes in right after the story's video is read and before the like is toggled, and
     * passes the view and that video in a plain invoke, which reaches only v0 to v15.
     */
    @Test
    fun `the story like reads the story's video before it toggles the like`() {
        for (apk in Fixtures.apks()) {
            val (_, click) = Build(apk).single(StoryLikeClickFingerprint)
            val instructions = click.implementation!!.instructions.toList()
            val read = instructions.indexOfFirst { it.call()?.let { call -> call.name == "getAweme" } == true }
            val toggle = instructions.indexOfFirst {
                it.call()?.parameterTypes?.map(CharSequence::toString) == listOf("Lkotlin/jvm/functions/Function2;")
            }
            assertTrue("${apk.name}: no video read", read >= 0)
            assertEquals("${apk.name}: the video read keeps no result", Opcode.MOVE_RESULT_OBJECT, instructions[read + 1].opcode)
            assertTrue("${apk.name}: the like is toggled before the video is read", toggle > read + 1)
            assertTrue("${apk.name}: the view parameter is past v15", click.implementation!!.registerCount - 1 <= 15)
            val video = (instructions[read + 1] as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA
            assertTrue("${apk.name}: the video is kept past v15", video <= 15)
        }
    }

    @Test
    fun `the patch asks at each of the new places`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/interaction/confirmation/ConfirmInteractionsPatch.kt").readText()
        assertTrue("the comment list's like is not hooked", source.contains("CommentLikeClickFingerprint.method.apply"))
        assertTrue("the comment list's like does not ask with its view",
            source.contains("invoke-static/range { p0 .. p0 }, \$TAP_CONFIRMATION->commentLike(Ljava/lang/Object;)Z"))
        assertTrue("a photo post's first like is not hooked", source.contains("PhotoCommentLikeClickFingerprint.method.hookAfterCommentRead()"))
        assertTrue("a photo post's second like is not hooked", source.contains("PhotoCommentLikeClickV2Fingerprint.method.hookAfterCommentRead()"))
        assertTrue("a photo post's like does not ask with the comment it read",
            source.contains("invoke-static { p0, v\$comment }, \$TAP_CONFIRMATION->commentLike(Ljava/lang/Object;Ljava/lang/Object;)Z"))
        assertTrue("the story like is not hooked", source.contains("StoryLikeClickFingerprint.method.hookStoryLike()"))
        assertTrue("the quick repost is not hooked", source.contains("QuickRepostFingerprint.method.apply"))
        assertTrue("the repost the bar's guide sends would go out on the first tap", !source.contains("if-eqz p2, :original"))
    }

    /**
     * The bar under a video reaches its repost call from two places in one click dispatcher, the
     * repost button with the quick flag set and its guide with it clear, and both publish at once.
     * The flag only picks the log label, so the patch asks on every call; the panels that take a
     * note publish on their own and never get here.
     */
    @Test
    fun `the bar's repost call has its two callers in one dispatcher, one with the flag clear`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val (_, repost) = build.single(QuickRepostFingerprint)
            val calls = build.methods.flatMap { (classDef, method) ->
                method.implementation?.instructions?.toList().orEmpty().withIndex()
                    .filter { (_, instruction) ->
                        instruction.call()?.let {
                            it.definingClass == repost.definingClass && it.name == repost.name &&
                                it.parameterTypes.map(CharSequence::toString) == repost.parameterTypes.map(CharSequence::toString)
                        } == true
                    }
                    .map { (index, _) -> Triple(classDef.type, method, index) }
                    .asSequence()
            }.toList()
            assertEquals("${apk.name}: ${calls.map { "${it.first}->${it.second.name}@${it.third}" }}", 2, calls.size)
            assertEquals("${apk.name}: the callers sit in one dispatcher", 1, calls.map { it.first to it.second.name }.toSet().size)
            // The guide's call writes its clear flag right before it. The button's set flag is a
            // constant R8 hoisted to wherever it pleased (a 1, or a -1 it already held), so only
            // the clear one is read here: it is the call the patch must not let through.
            val flags = calls.map { (_, method, index) -> flagPassed(method, index) }
            assertTrue("${apk.name}: no call passes the flag clear: $flags", 0 in flags)
        }
    }

    /** The literal the call at [index] passes as the flag, its third register, or null when it isn't one. */
    private fun flagPassed(method: Method, index: Int): Int? {
        val instructions = method.implementation!!.instructions.toList()
        val call = instructions[index]
        val flag = when (call) {
            is FiveRegisterInstruction -> call.registerE
            is RegisterRangeInstruction -> call.startRegister + 2
            else -> return null
        }
        for (i in index - 1 downTo 0) {
            val instruction = instructions[i]
            if ((instruction as? OneRegisterInstruction)?.registerA != flag || !instruction.opcode.setsRegister()) continue
            return (instruction as? NarrowLiteralInstruction)?.narrowLiteral
        }
        return null
    }

    private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.call(): MethodReference? =
        (this as? ReferenceInstruction)?.reference as? MethodReference

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

        fun single(fingerprint: Fingerprint): Pair<ClassDef, Method> {
            val taken = methods.filter { (classDef, method) -> fingerprint.takes(method, classDef) }.toList()
            assertEquals("${fingerprint.javaClass.simpleName} takes: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            return taken.single()
        }

        /** The class and every superclass the fixture itself holds. */
        fun hierarchy(classDef: ClassDef): List<ClassDef> =
            generateSequence(classDef) { current -> current.superclass?.let(byType::get) }.toList()
    }
}
