package app.morphe.patches.tiktok.interaction.blockauthor

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Keep a paused video paused answers, held to every fixture.
 *
 * <p>TikTok plays the video on screen again as the app comes back through PlayerController's play
 * method, from the feed panel's resume and again from the video's new surface (both seen on the
 * S22 with 47.0.3). The patch asks at the start of that method, and a play it turns down answers
 * the way the method's own first check does while casting: an empty string, straight back.
 */
class KeepPausedAnchorsTest {
    @Test
    fun `the play method is one method on every fixture and turns a casting play down with an empty string`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val taken = build.methods.filter { (classDef, method) -> PlayerPlayFingerprint.takes(method, classDef) }.toList()
            assertEquals("${apk.name}: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            val play = taken.single().second
            val registers = play.implementation!!.registerCount
            assertFalse("${apk.name}: the play method is static, so p0 is not the controller",
                AccessFlags.STATIC.isSet(play.accessFlags))
            assertEquals("${apk.name}: the Aweme parameter", PLAYED_AWEME, play.parameterAtRegister(play.awemeParameterRegister()))
            assertTrue("${apk.name}: no local register for the answer", registers - play.inRegisters() >= 1)

            val head = play.implementation!!.instructions.take(12)
            val casting = head.indexOfFirst { it.call()?.name == "blockByCasting" }
            assertTrue("${apk.name}: the play no longer checks casting first: ${head.map { it.opcode }}", casting >= 0)
            val empty = head.filter { it.opcode == Opcode.CONST_STRING && it.string() == "" }
                .map { (it as OneRegisterInstruction).registerA }.toSet()
            val answered = head.drop(casting).any { it.opcode == Opcode.RETURN_OBJECT && (it as OneRegisterInstruction).registerA in empty }
            assertTrue("${apk.name}: a casting play is no longer answered with an empty string", answered)

            val callers = build.methods.filter { (_, method) -> method.calls(play) }.map { it.second.name }.toSet()
            assertTrue("${apk.name}: the play method has callers $callers", callers.size >= 2)
        }
    }

    @Test
    fun `the patch asks before the play and answers a refused one as casting does`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/interaction/blockauthor/BlockAuthorPatch.kt").readText()
        assertTrue("the play is not hooked", source.contains("PlayerPlayFingerprint.method.apply"))
        assertTrue("the hook does not hand over the video",
            source.contains("invoke-static/range { \$aweme .. \$aweme }, Lapp/morphe/extension/tiktok/playback/KeepPaused;->refusePlay(Ljava/lang/Object;)Z"))
        assertTrue("a refused play would still play or answer something else",
            source.contains("if-eqz v0, :play\n                    const-string v0, \"\"\n                    return-object v0"))
    }

    @Test
    fun `the Aweme's register counts this only on an instance method, and a wide parameter twice`() {
        assertEquals("a static method starts at p0", 1, method(true, "I", PLAYED_AWEME, "Z").awemeParameterRegister())
        assertEquals("an instance method's p0 is this", 2, method(false, "I", PLAYED_AWEME, "Z").awemeParameterRegister())
        assertEquals("a long takes two registers", 3, method(false, "J", PLAYED_AWEME).awemeParameterRegister())
        assertEquals("the Aweme first", 1, method(false, PLAYED_AWEME, "I", "Z", "Z").awemeParameterRegister())
        val static = method(true, "D", PLAYED_AWEME)
        assertEquals(PLAYED_AWEME, static.parameterAtRegister(static.awemeParameterRegister()))
    }

    private fun method(static: Boolean, vararg parameters: String): Method = ImmutableMethod(
        "LX/Player;", "play", parameters.map { ImmutableMethodParameter(it, null, null) }, "Ljava/lang/String;",
        AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null, null,
    )

    /** The type of the parameter that starts at p-register [register], as dexlib2 lays them out. */
    private fun Method.parameterAtRegister(register: Int): String? {
        var at = if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
        for (type in parameterTypes.map(CharSequence::toString)) {
            if (at == register) return type
            at += if (type == "J" || type == "D") 2 else 1
        }
        return null
    }

    /** The registers the method's parameters take, `this` included. */
    private fun Method.inRegisters(): Int = (if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1) +
        parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }

    private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.call(): MethodReference? =
        (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.string(): String? =
        ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    private fun Method.calls(target: Method): Boolean = implementation?.instructions?.any { instruction ->
        instruction.call()?.let {
            it.definingClass == target.definingClass && it.name == target.name &&
                it.parameterTypes.map(Any::toString) == target.parameterTypes.map(Any::toString)
        } == true
    } == true

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
}
