/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.patches.tiktok.misc.optimizer.InitPushTaskFingerprint
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Turn off push notifications rests on, held to each declared TikTok build.
 *
 * The wake lock half rewrites calls by their exact descriptor, so a call the map doesn't name
 * would keep its lock with the switch on, and a call made some other way than invoke-virtual
 * wouldn't be found at all. The counts are pinned so a build that moves its calls is noticed
 * here. The kept tags are matched by their literal text, so each has to still be in the build,
 * and the push setup guard needs a spare register in InitPushTask.run.
 */
class PushShutoffAnchorsTest {
    @Test
    fun `every wake lock call TikTok makes is one the patch takes, on each build`() {
        val expected = mapOf("47.4.3" to 5)
        assertEquals(Fixtures.declaredVersions().toSet(), expected.keys)
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames
                .flatMap { container.getEntry(it)!!.dexFile.classes }
                .filterNot { it.type.startsWith("Lapp/morphe/extension/") }

            val calls = mutableListOf<Pair<Opcode, String>>()
            val literals = mutableSetOf<String>()
            for (instruction in classes.asSequence().flatMap { it.methods }
                .flatMap { it.implementation?.instructions?.asSequence().orEmpty() }) {
                when (val reference = (instruction as? ReferenceInstruction)?.reference) {
                    is MethodReference -> if (reference.isWakeLockCall()) {
                        calls += instruction.opcode to reference.descriptor()
                    }
                    is StringReference -> if (reference.string in LITERALS) literals += reference.string
                }
            }

            assertEquals(
                "$version: wake lock calls made some other way than invoke-virtual",
                emptyList<String>(),
                calls.filter { it.first !in VIRTUAL }.map { "${it.first} ${it.second}" },
            )
            assertEquals(
                "$version: wake lock calls the patch doesn't name",
                emptyList<String>(),
                calls.map { it.second }.filter { it !in WAKE_LOCK_CALLS }.distinct(),
            )
            for (name in listOf("newWakeLock", "acquire", "release")) {
                assertTrue("$version: no $name call to take", calls.any { "->$name(" in it.second })
            }
            assertEquals("$version wake lock calls", expected.getValue(version), calls.size)
            assertEquals("$version: the kept tags' text", LITERALS, literals)

            val pushTask = classes.single { it.type == PUSH_TASK }
            val run = pushTask.methods.single { it.name == "run" && it.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT) }
            assertTrue("$version: the push setup guard's fingerprint takes InitPushTask.run", InitPushTaskFingerprint.takes(run, pushTask))
            // An instance method with one parameter: this and the Context are the last two registers.
            assertTrue("$version: InitPushTask.run has no spare register for the guard", run.implementation!!.registerCount - 2 >= 1)
        }
    }

    @Test
    fun `each wake lock call becomes a static taking the receiver first, returning what the call did`() {
        assertEquals(5, WAKE_LOCK_CALLS.size)
        WAKE_LOCK_CALLS.forEach { (target, replacement) ->
            assertEquals("Lapp/morphe/extension/tiktok/inbox/PushShutoff;", replacement.substringBefore("->"))
            assertEquals(target.substringAfter("->").substringBefore('('), replacement.substringAfter("->").substringBefore('('))
            assertEquals(
                "$target: the receiver, then the call's own parameters",
                target.substringBefore("->") + target.substringAfter('(').substringBefore(')'),
                replacement.substringAfter('(').substringBefore(')'),
            )
            assertEquals(target.substringAfter(')'), replacement.substringAfter(')'))
        }
    }

    private companion object {
        const val POWER_MANAGER = "Landroid/os/PowerManager;"
        const val WAKE_LOCK = "Landroid/os/PowerManager\$WakeLock;"
        const val PUSH_TASK = "Lcom/ss/android/ugc/aweme/legoImp/task/InitPushTask;"
        const val CONTEXT = "Landroid/content/Context;"

        /** PushShutoff.KEPT_TAGS: LIVE's lock whole, and WorkManager's prefix and tag apart. */
        val LITERALS = setOf("Live::AnchorWakeLock", "WorkManager: ", "ProcessorForegroundLck")

        val VIRTUAL = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)

        fun MethodReference.isWakeLockCall() =
            (definingClass == POWER_MANAGER && name == "newWakeLock") ||
                (definingClass == WAKE_LOCK && (name == "acquire" || name == "release"))

        fun MethodReference.descriptor() =
            "$definingClass->$name${parameterTypes.joinToString("", "(", ")")}$returnType"
    }
}
