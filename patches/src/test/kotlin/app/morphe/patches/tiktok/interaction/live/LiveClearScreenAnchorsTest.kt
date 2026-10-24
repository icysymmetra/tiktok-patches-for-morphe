package app.morphe.patches.tiktok.interaction.live

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #57: Offer clear screen in every LIVE room answers true at the entry of TikTok's server setting
 * for the clear screen menu entry. Each declared build has the one getter, reading
 * live_clear_screen_feature_entry_completion with a spare register for the guard, and the portrait
 * room's panel builder still gates its clear screen entry on it and turns clear screen on and off
 * through IClearScreenService.
 */
class LiveClearScreenAnchorsTest {
    private val clearScreenService = "Lcom/bytedance/android/livesdk/watch/clearscreen/IClearScreenService;"
    private val portraitRoom = "Lcom/bytedance/android/livesdk/chatroom/ui/PortraitAudienceInteractionFragment;"

    @Test
    fun `each declared build has the clear screen entry setting and the room menu that reads it`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            val taken = classes.flatMap { cls -> cls.methods.filter { LiveClearScreenEntryFingerprint.takes(it, cls) } }
            assertEquals("${apk.name}: ${taken.map { "${it.definingClass}->${it.name}" }}", 1, taken.size)
            val getter = taken.single()
            assertTrue("${apk.name}: no spare register for the guard", getter.implementation!!.registerCount >= 1)
            assertTrue(
                "${apk.name}: the getter no longer reads live_clear_screen_feature_entry_completion",
                getter.implementation!!.instructions.any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                        "live_clear_screen_feature_entry_completion"
                },
            )

            val menus = classes.single { it.type == portraitRoom }.methods.filter { method ->
                val calls = method.implementation?.instructions?.toList().orEmpty()
                    .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                calls.any { it.definingClass == LIVE_CLEAR_SCREEN_ENTRY_SETTING && it.name == "getValue" } &&
                    calls.any { it.definingClass == clearScreenService }
            }
            assertEquals("${apk.name}: portrait room menus gated on the clear screen entry", 1, menus.size)
        }
    }
}
