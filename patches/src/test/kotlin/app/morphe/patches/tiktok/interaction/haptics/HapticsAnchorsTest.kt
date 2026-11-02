package app.morphe.patches.tiktok.interaction.haptics

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What "Turn off haptics" rests on, held to each declared TikTok build.
 *
 * The patch rewrites calls by their exact descriptor, so a haptic call that names a View subclass,
 * or a vibrator overload the patch doesn't list, would play on with the switch on. This pins that
 * every View haptic call names View itself, that every framework vibrate call is one the patch
 * rewrites or the patterned form it leaves to alerts, and that both kinds are there to rewrite,
 * along with the long click listeners set on View that the long press buzz follows.
 */
class HapticsAnchorsTest {
    @Test
    fun `every haptic call TikTok makes is one the patch rewrites on each build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val calls = hapticCallsOf(apk)

            val views = calls.filter { it.name == "performHapticFeedback" }
            assertTrue("$version: no View haptic call", views.any { it.descriptor() in VIEW_HAPTICS })
            assertEquals(
                "$version: haptic calls the patch doesn't name",
                emptyList<String>(),
                views.map { it.descriptor() }.filter { it !in VIEW_HAPTICS }.distinct(),
            )

            val vibrations = calls.filter { it.name == "vibrate" }
            assertTrue("$version: no vibrator call", vibrations.any { it.descriptor() in VIBRATIONS })
            assertEquals(
                "$version: vibrator calls the patch doesn't name",
                emptyList<String>(),
                vibrations.map { it.descriptor() }.filter { it !in VIBRATIONS && it != PATTERN }.distinct(),
            )

            val longClicks = calls.filter { it.name == "setOnLongClickListener" }
            assertTrue("$version: no long click listener set on View", longClicks.any { it.descriptor() in LONG_CLICKS })
        }
    }

    private companion object {
        const val PATTERN = "Landroid/os/Vibrator;->vibrate([JI)V"

        val VIRTUAL = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)

        fun MethodReference.descriptor() =
            "$definingClass->$name${parameterTypes.joinToString("", "(", ")")}$returnType"

        fun classesOf(apk: File): List<ClassDef> {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            return container.dexEntryNames.flatMap { entry -> container.getEntry(entry)!!.dexFile.classes }
        }

        /** View haptic calls on any receiver, and framework vibrate calls, outside the extension. */
        fun hapticCallsOf(apk: File): List<MethodReference> = classesOf(apk)
            .filterNot { it.type.startsWith("Lapp/morphe/extension/") }
            .flatMap { owner -> owner.methods }
            .flatMap { method -> method.implementation?.instructions?.toList().orEmpty() }
            .filter { it.opcode in VIRTUAL }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .filter { call ->
                (call.name == "performHapticFeedback" && call.returnType == "Z") ||
                    (call.name == "vibrate" && call.definingClass.startsWith("Landroid/")) ||
                    (call.name == "setOnLongClickListener" && call.definingClass == "Landroid/view/View;")
            }
    }
}
