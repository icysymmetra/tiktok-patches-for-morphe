package app.morphe.patches.tiktok.interaction.live

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #159: LIVE panels open light in a dark TikTok because the LIVE theme provider's dark answer
 * also asks the page, and LIVE room pages always say no. The hook answers that question itself
 * while its switch is on. Each declared build has exactly one such answer: the provider's
 * (Context)Z method that starts with its own dark mode check and then asks the page through
 * LiveHostResource. The guard writes v0, so the answer needs a local.
 */
class LiveDarkThemeAnchorsTest {
    @Test
    fun `each declared build has one LIVE dark theme answer that asks the page after dark mode`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            val found = classes.flatMap { cls -> cls.methods.filter { LiveDarkThemeFingerprint.takes(it, cls) }.map { cls to it } }
            assertEquals("${apk.name}: ${found.map { "${it.second.definingClass}->${it.second.name}" }}", 1, found.size)
            val (provider, answer) = found.single()

            val body = answer.implementation!!
            assertTrue("${apk.name}: no local for the guard", body.registerCount - 2 >= 1)
            val instructions = body.instructions.toList()
            val pageLookup = instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_STATIC &&
                    ((it as ReferenceInstruction).reference as MethodReference).definingClass == LIVE_HOST_RESOURCE
            }
            val pageAnswer = instructions.drop(pageLookup + 1).firstOrNull { it.opcode == Opcode.INVOKE_INTERFACE }
            val pageCall = (pageAnswer as? ReferenceInstruction)?.reference as? MethodReference
            assertEquals("${apk.name}: the page's answer", "Z", pageCall?.returnType)
            assertEquals("${apk.name}: the page's answer takes nothing", 0, pageCall?.parameterTypes?.size)

            // The provider's other (Context)Z answer asks the page too, but not after dark mode.
            val others = provider.methods.filter {
                it != answer && it.returnType == "Z" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;")
            }
            assertTrue("${apk.name}: ${provider.type} has no dark mode check of its own", others.isNotEmpty())
        }
    }
}
