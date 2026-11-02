package app.morphe.patches.tiktok.interaction.feedmute

import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.patches.tiktok.interaction.blockauthor.PlayerPlayFingerprint
import app.morphe.patches.tiktok.interaction.blockauthor.awemeParameterRegister
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Mute feed videos hooks, held to each declared build: each fingerprint takes exactly one
 * method, the members the extension's bridges call resolve and are public (the same resolution
 * the patch runs), the engine's play() opens with TTVideoEngine's own, and every guarded method
 * has a local for the answer.
 */
class FeedMuteAnchorsTest {
    @Test
    fun `the feed engine, its mute and the player session's focus resolve on each build`() {
        Fixtures.forEachDeclared { apk ->
            val classes = HashMap<String, ClassDef>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) classes.putIfAbsent(classDef.type, classDef)
            }
            val version = Fixtures.versionOf(apk)
            fun single(fingerprint: Fingerprint, label: String): Method {
                val taken = classes.values.flatMap { classDef ->
                    classDef.methods.filter { fingerprint.takes(it, classDef) }
                }
                assertEquals("$version: $label takes ${taken.map { "${it.definingClass}->${it.name}" }}", 1, taken.size)
                return taken.single()
            }

            val play = single(FeedEnginePlayFingerprint, "FeedEnginePlayFingerprint")
            val setIsMute = single(EngineSetIsMuteFingerprint, "EngineSetIsMuteFingerprint")
            val abandon = single(SimAudioSessionAbandonFingerprint, "SimAudioSessionAbandonFingerprint")
            val request = single(SimAudioFocusRequestFingerprint, "SimAudioFocusRequestFingerprint")
            val controllerPlay = single(PlayerPlayFingerprint, "PlayerPlayFingerprint")
            assertEquals("$version: the controller play's Aweme register", 1, controllerPlay.awemeParameterRegister())

            val first = play.implementation!!.instructions.first()
            assertEquals("$version: play() doesn't open with the super call", Opcode.INVOKE_SUPER, first.opcode)
            assertEquals("$TT_VIDEO_ENGINE->play()V", (first as ReferenceInstruction).reference.toString())

            val members = resolveFeedMuteMembers(play, setIsMute, abandon, request) { classes[it] }
            val session = classes.getValue(members.session)
            val sessionRequest = session.methods.single { it.name == members.sessionRequest && it.parameterTypes.isEmpty() }
            val body = sessionRequest.implementation!!
            assertTrue("$version: ${members.session}->${members.sessionRequest}() has no local for the guard",
                body.registerCount - 1 >= 1)
            assertTrue("$version: the engine's play() is on the engine the members name",
                play.definingClass == members.engine)
        }
    }
}
