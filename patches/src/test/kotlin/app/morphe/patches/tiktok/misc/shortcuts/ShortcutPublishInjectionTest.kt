package app.morphe.patches.tiktok.misc.shortcuts

import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SHORTCUT_MANAGER = "Landroid/content/pm/ShortcutManager;"
private const val EXTENSION = "Lapp/morphe/extension/tiktok/misc/LauncherShortcuts;"

/**
 * What the patch puts in front of the platform call that publishes the launcher's shortcuts.
 *
 * <p>The registers are the whole point. The call names the manager and the list in two different
 * registers and the patch has to pick the second, so every case here uses a manager register and
 * a list register that are not the same number.
 */
class ShortcutPublishInjectionTest {

    @Test
    fun `the list is what goes through the extension, not the manager it is published on`() {
        for ((manager, list) in listOf(0 to 1, 1 to 0, 4 to 9, 15 to 2, 2 to 15)) {
            val method = publishCall(manager, list)
            method.routeShortcutListThroughExtension(0)

            val call = method.implementation!!.instructions.first() as RegisterRangeInstruction
            assertEquals("manager=v$manager list=v$list", list, call.startRegister)
            assertEquals("manager=v$manager list=v$list", 1, call.registerCount)
            assertNotEquals(
                "the manager was handed over as though it were the list",
                manager,
                call.startRegister,
            )
        }
    }

    @Test
    fun `the answer is what the call publishes`() {
        val method = publishCall(manager = 3, list = 6)
        method.routeShortcutListThroughExtension(0)
        val instructions = method.implementation!!.instructions.toList()

        // The call, the register it lands in, and then the host's own publish, in that order.
        assertEquals(Opcode.INVOKE_STATIC_RANGE, instructions[0].opcode)
        assertEquals(
            "$EXTENSION->publish(Ljava/util/List;)Ljava/util/List;",
            (instructions[0] as ReferenceInstruction).reference.toString(),
        )
        assertEquals(Opcode.MOVE_RESULT_OBJECT, instructions[1].opcode)
        assertEquals(6, (instructions[1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.INVOKE_VIRTUAL, instructions[2].opcode)

        // The host's own call is left exactly as it was, still reading the same two registers.
        val published = instructions[2] as FiveRegisterInstruction
        assertEquals(3, published.registerC)
        assertEquals(6, published.registerD)
        assertEquals(
            "$SHORTCUT_MANAGER->setDynamicShortcuts(Ljava/util/List;)Z",
            (instructions[2] as ReferenceInstruction).reference.toString(),
        )
    }

    @Test
    fun `the result register is only read on the instruction after the call`() {
        // smali assembles a move-result-object anywhere; a device refuses to verify one that
        // does not directly follow its call, so the order is worth pinning rather than assuming.
        val method = publishCall(manager = 0, list = 1)
        method.routeShortcutListThroughExtension(0)
        var previous: Opcode? = null
        method.implementation!!.instructions.forEach { instruction ->
            if (instruction.opcode == Opcode.MOVE_RESULT_OBJECT) {
                assertEquals(
                    "move-result-object does not follow the call",
                    Opcode.INVOKE_STATIC_RANGE,
                    previous,
                )
            }
            previous = instruction.opcode
        }
    }

    @Test
    fun `a call that is not first in the method is still the one taken over`() {
        val method = publishCall(manager = 2, list = 5, leadingNops = 3)
        method.routeShortcutListThroughExtension(3)
        val instructions = method.implementation!!.instructions.toList()

        // The three instructions ahead of it are untouched.
        assertTrue(instructions.take(3).all { it.opcode == Opcode.NOP })
        assertEquals(Opcode.INVOKE_STATIC_RANGE, instructions[3].opcode)
        assertEquals(5, (instructions[3] as RegisterRangeInstruction).startRegister)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, instructions[4].opcode)
        assertEquals(Opcode.INVOKE_VIRTUAL, instructions[5].opcode)
    }

    @Test
    fun `only the two calls that put a list in front of the launcher are taken over`() {
        // Taken over.
        assertTrue(publishesShortcuts(publish("setDynamicShortcuts", listOf("Ljava/util/List;"), "Z")))
        assertTrue(publishesShortcuts(publish("addDynamicShortcuts", listOf("Ljava/util/List;"), "Z")))

        // Left alone. updateShortcuts changes entries that are published and adds none;
        // removeDynamicShortcuts is a removal; requestPinShortcut is somebody putting one on
        // their own home screen. Hooking any of these would change something nobody asked about.
        assertFalse(publishesShortcuts(publish("updateShortcuts", listOf("Ljava/util/List;"), "Z")))
        assertFalse(publishesShortcuts(publish("removeDynamicShortcuts", listOf("Ljava/util/List;"), "V")))
        assertFalse(
            publishesShortcuts(
                publish(
                    "requestPinShortcut",
                    listOf("Landroid/content/pm/ShortcutInfo;", "Landroid/content/IntentSender;"),
                    "Z",
                ),
            ),
        )

        // The name alone is not the anchor. Something else called setDynamicShortcuts is not the
        // platform's shortcut manager and is none of this patch's business.
        assertFalse(
            publishesShortcuts(
                publish("setDynamicShortcuts", listOf("Ljava/util/List;"), "Z", owner = "Lcom/example/Other;"),
            ),
        )
    }

    @Test
    fun `the range form of the call is taken over too, and its list register found`() {
        // d8 emits the range form as soon as a register the call needs sits above v15, which is
        // ordinary in a large synthesized method. Matching only the short form would walk past
        // such a call, hook nothing, and report success.
        val range = ImmutableInstruction3rc(
            Opcode.INVOKE_VIRTUAL_RANGE,
            18, 2,
            ImmutableMethodReference(SHORTCUT_MANAGER, "setDynamicShortcuts", listOf("Ljava/util/List;"), "Z"),
        )
        assertTrue(publishesShortcuts(range))
        // The receiver is at the start register and the list is the one after it.
        assertEquals(19, listRegisterOf(range))
    }

    private fun publish(
        name: String,
        parameters: List<String>,
        returns: String,
        owner: String = SHORTCUT_MANAGER,
    ) = ImmutableInstruction35c(
        Opcode.INVOKE_VIRTUAL,
        1 + parameters.size, 0, 1, 2, 0, 0,
        ImmutableMethodReference(owner, name, parameters, returns),
    )

    /** A method holding one `setDynamicShortcuts` call, optionally with padding ahead of it. */
    private fun publishCall(manager: Int, list: Int, leadingNops: Int = 0): MutableMethod {
        val reference = ImmutableMethodReference(
            SHORTCUT_MANAGER,
            "setDynamicShortcuts",
            listOf("Ljava/util/List;"),
            "Z",
        )
        val instructions = buildList {
            repeat(leadingNops) { add(ImmutableInstruction10x(Opcode.NOP)) }
            add(
                ImmutableInstruction35c(
                    Opcode.INVOKE_VIRTUAL,
                    2, manager, list, 0, 0, 0,
                    reference,
                ),
            )
            add(ImmutableInstruction10x(Opcode.RETURN_VOID))
        }
        val registers = maxOf(manager, list) + 1
        return MutableMethod(
            ImmutableMethod(
                "Lcom/example/Shortcuts;",
                "refresh",
                listOf<ImmutableMethodParameter>(),
                "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                ImmutableMethodImplementation(registers, instructions, null, null),
            ),
        )
    }
}
