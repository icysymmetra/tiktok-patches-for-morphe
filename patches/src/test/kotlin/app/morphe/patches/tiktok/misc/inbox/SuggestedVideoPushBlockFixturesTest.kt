package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FILTER = "Lapp/morphe/extension/tiktok/inbox/SuggestedVideoPushBlock;"
private const val PUSH_SERVICE = "Lcom/ss/android/ugc/awemepushlib/interaction/PushService;"

/** Every notification TikTok posts goes through the suggested video filter, on every declared host. */
class SuggestedVideoPushBlockFixturesTest {
    @Test
    fun `each declared host posts its 22 notifications through the filter, the push handler among them`() {
        val expected = mapOf("47.4.3" to 22)
        assertEquals(Fixtures.declaredVersions().toSet(), expected.keys)
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .toList()
            val targets = notifyingMethods(classes)
            val calls = targets.sumOf { (_, method) -> method.implementation!!.instructions.count(::isNotifyCall) }
            assertEquals("$version notify calls", expected.getValue(version), calls)
            assertTrue("$version: the push handler posts one",
                targets.any { (classDef, _) -> classDef.type == PUSH_HANDLER })
            assertEquals("$version calls routed", calls, targets.sumOf { (_, method) -> assertRouted(method) })

            // Notification controls finds the push handler by its notify call, and the two patches run in
            // either order, so it has to find the handler once this patch has rerouted that call too.
            val (handlerClass, handler) = targets.single { (classDef, _) -> classDef.type == PUSH_HANDLER }
            assertTrue("$version: Notification controls takes the push handler", PushNotifyFingerprint.takes(handler, handlerClass))
            val rerouted = MutableMethod(handler).apply { postThroughSuggestedVideoFilter() }
            assertTrue("$version: Notification controls takes it once its notify call is rerouted",
                PushNotifyFingerprint.takes(rerouted, handlerClass))

            // The filter compares against the channel's base id, so PushService has to still list it.
            val pushChannels = classes.single { it.type == PUSH_SERVICE }.methods.single { it.name == "<init>" }
                .implementation!!.instructions.mapNotNull { it.getReference<StringReference>()?.string }
            assertTrue("$version: PushService lists ${pushChannels.filter { it.endsWith("_push") }}",
                "recommend_video_push" in pushChannels)
        }
    }

    @Test
    fun `only NotificationManager's two notify calls are taken, and Hushfeed's own code is left alone`() {
        val notifying = method("LX/Poster;", """
            invoke-virtual {v0, v1, v2, v3}, Landroid/app/NotificationManager;->notify(Ljava/lang/String;ILandroid/app/Notification;)V
            invoke-virtual {v0, v2, v3}, Landroid/app/NotificationManager;->notify(ILandroid/app/Notification;)V
            invoke-virtual {v0}, Ljava/lang/Object;->notify()V
            invoke-virtual {v0, v1}, Landroid/app/NotificationManager;->cancel(Ljava/lang/String;)V
            return-void
        """)
        assertEquals(2, notifying.implementation!!.instructions.count(::isNotifyCall))
        val ownCode = method("Lapp/morphe/extension/tiktok/inbox/SuggestedVideoPushBlock;", """
            invoke-virtual {v0, v2, v3}, Landroid/app/NotificationManager;->notify(ILandroid/app/Notification;)V
            return-void
        """)
        val found = notifyingMethods(listOf(classOf(notifying), classOf(ownCode)))
        assertEquals(listOf("LX/Poster;"), found.map { it.first.type })
    }

    @Test
    fun `a range call keeps its registers`() {
        val range = method("LX/Poster;", """
            invoke-virtual/range {v0 .. v3}, Landroid/app/NotificationManager;->notify(Ljava/lang/String;ILandroid/app/Notification;)V
            return-void
        """)
        assertEquals(1, assertRouted(range))
    }

    /** Each notify call becomes the filter's, taking the same registers, and nothing else moves. Returns how many moved. */
    private fun assertRouted(native: Method): Int {
        val mutable = MutableMethod(native)
        val before = mutable.implementation!!.instructions.toList()
        mutable.postThroughSuggestedVideoFilter()
        val after = mutable.implementation!!.instructions.toList()
        assertEquals(before.size, after.size)
        var routed = 0
        before.indices.forEach { index ->
            if (!isNotifyCall(before[index])) {
                assertSame(before[index], after[index])
                return@forEach
            }
            routed++
            val call = after[index]
            val reference = call.getReference<MethodReference>()!!
            assertEquals(FILTER, reference.definingClass)
            assertEquals("notify", reference.name)
            val native = before[index].getReference<MethodReference>()!!
            assertEquals(listOf("Landroid/app/NotificationManager;") + native.parameterTypes.map(CharSequence::toString),
                reference.parameterTypes.map(CharSequence::toString))
            assertEquals(registers(before[index]), registers(call))
            assertEquals(if (before[index].opcode == Opcode.INVOKE_VIRTUAL) Opcode.INVOKE_STATIC else Opcode.INVOKE_STATIC_RANGE,
                call.opcode)
            assertFalse(isNotifyCall(call))
        }
        return routed
    }

    private fun registers(instruction: Instruction): List<Int> = when (instruction) {
        is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
            instruction.registerF, instruction.registerG).take(instruction.registerCount)
        is RegisterRangeInstruction -> (0 until instruction.registerCount).map { instruction.startRegister + it }
        else -> error("not an invoke: ${instruction.opcode}")
    }

    private fun classOf(method: Method) = ImmutableClassDef(
        method.definingClass, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(method),
    )

    private fun method(owner: String, body: String): Method = MutableMethod(ImmutableMethod(
        owner, "post", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null, null, ImmutableMethodImplementation(4, emptyList(), null, null),
    )).apply { addInstructions(0, body) }
}
