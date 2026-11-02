/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val COVER_ASSEM = "Lcom/ss/android/ugc/aweme/feed/assem/music/VideoMusicCoverAssem;"

/** One of the two lazy reads the disc's turn hangs on, and the MusicDiscSpin method that answers it. */
private class DiscRead(val key: String, val fingerprint: Fingerprint, val answer: String)

private val DISC_READS = listOf(
    DiscRead(MUSIC_ANIMATION_CLOSE_KEY, MusicAnimationCloseFingerprint, "closeSetting"),
    DiscRead(MUSIC_COVER_ROTATION_DURATION_KEY, MusicCoverRotationDurationFingerprint, "rotationSeconds"),
)

/** One method on the disc's way past the close setting: its shape, judged without the fingerprint. */
private class DiscHold(val name: String, val fingerprint: Fingerprint, val shape: (Method) -> Boolean)

private val COVER_HOLD = DiscHold("the cover's anim_opt hold", MusicCoverAnimationHoldFingerprint) { isCoverAnimationHold(it) }
private val PLAY_SUBSCRIBER = DiscHold("the play subscriber", MusicCoverPlaySubscriberFingerprint) {
    it.returnType == "Ljava/lang/Object;" &&
        it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;", "Ljava/lang/Object;") &&
        highFrameRateHoldIndex(it) >= 0
}
private val PLAY_HOOK = DiscHold("the cell's play hook", PlayAnimationTriggerFingerprint) {
    it.definingClass == VIDEO_VIEW_CELL && it.returnType == "V" && it.parameterTypes.isEmpty() &&
        loads(it, START_PLAY_ANIMATION_KEY)
}
private val DEGRADE_REGISTRATION = DiscHold("the degrade check's registration", MusicCoverDegradeRegistrationFingerprint) {
    it.definingClass == MUSIC_COVER_ASSEM && it.name == "onViewCreated" && loads(it, MUSIC_COVER_DEGRADE_KEY)
}
private val LOAD_STRATEGY = DiscHold("the play animation's load strategy", PlayAnimationDowngradeFingerprint) {
    it.name == "getStrategyId" && loads(it, PLAY_ANIMATION_DOWNGRADE_ID)
}
private val DISC_HOLDS = listOf(COVER_HOLD, PLAY_SUBSCRIBER, PLAY_HOOK, DEGRADE_REGISTRATION, LOAD_STRATEGY)

private val ANIMATOR_START = listOf("Ljava/lang/String;", "Landroid/animation/Animator;", "Landroid/view/View;")

private fun loads(method: Method, string: String) =
    method.implementation?.instructions?.any { it.getReference<StringReference>()?.string == string } == true

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

/** The instructions without nops, which dexlib2 adds or takes away to keep a payload aligned. */
private fun Method.counted(): Int = code().count { it.opcode != Opcode.NOP }

/**
 * The disc's way on one build: the two lazy reads, each hold, the check registered under
 * musicCoverDegrade and the load strategy's class, the static that starts the animator through
 * a check, and what the runnable that loads musicCoverDegrade calls.
 */
private class DiscChain(
    val reads: Map<String, Method>,
    val holds: Map<DiscHold, Method>,
    val checkClass: ClassDef,
    val strategyClass: ClassDef,
    val starters: List<Method>,
    val startedUnderKey: List<MethodReference>,
)

/**
 * What the music disc switches hook (#68), held to each declared build: the two lazy values
 * VideoMusicCoverAssem's play subscriber asks before it lets the disc turn, each a no-argument
 * lambda body that reads its key through the app AB int getter and boxes the int. 47.1.4 ships
 * the close setting at 3 and the duration at 0, so the subscriber returns before it starts the
 * turn. The hook goes on the real bodies here, so the int MusicDiscSpin answers is the one TikTok
 * just read, and the boxing that follows keeps the answer for the launch.
 *
 * <p>Answered past those two the S22's disc still stood, so Spin also answers the holds further
 * on: VideoMusicCoverVM's anim_opt hold, the high frame rate hold the play subscriber and the
 * cell's play hook both read, the load strategy's play animation switch the play hook reads, and
 * the enable() of the check registered under musicCoverDegrade. Each is held to the build here,
 * and so is the way they lead to one another.
 */
class MusicDiscSpinAnchorsTest {
    @Test
    fun `each declared build has one boxed read of each key and its fingerprint takes exactly it`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val byShape = DISC_READS.associateWith { mutableListOf<String>() }
            val byFingerprint = DISC_READS.associateWith { mutableListOf<String>() }
            walk(apk) { classDef, method ->
                for (read in DISC_READS) {
                    if (isBoxedAbIntRead(method, read.key)) byShape.getValue(read) += "${classDef.type}->${method.name}"
                    if (read.fingerprint.takes(method, classDef)) byFingerprint.getValue(read) += "${classDef.type}->${method.name}"
                }
            }
            for (read in DISC_READS) {
                assertEquals("$version: ${read.key} is read by ${byShape[read]}", 1, byShape.getValue(read).size)
                assertEquals("$version: ${read.key}'s fingerprint", byShape[read], byFingerprint[read])
            }
        }
    }

    @Test
    fun `both reads meet in one place and the duration also times the cover's turn`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val reads = readsOf(apk)
            val closeRead = reads.getValue(MUSIC_ANIMATION_CLOSE_KEY).definingClass
            val durationRead = reads.getValue(MUSIC_COVER_ROTATION_DURATION_KEY).definingClass

            // Each lazy value is a static field of the class whose initializer builds its lambda.
            val holders = mutableMapOf<String, MutableSet<String>>()
            walk(apk) { classDef, method ->
                if (method.name != "<clinit>") return@walk
                method.implementation?.instructions?.forEach { instruction ->
                    if (instruction.opcode != Opcode.NEW_INSTANCE) return@forEach
                    val type = instruction.getReference<TypeReference>()?.type ?: return@forEach
                    if (type == closeRead || type == durationRead) holders.getOrPut(type) { mutableSetOf() } += classDef.type
                }
            }
            val closeHolder = holders[closeRead].orEmpty().singleOrNull()
            val durationHolder = holders[durationRead].orEmpty().singleOrNull()
            assertTrue("$version: the close value is held by ${holders[closeRead]}", closeHolder != null)
            assertTrue("$version: the duration is held by ${holders[durationRead]}", durationHolder != null)

            val meeting = mutableListOf<Pair<String, Boolean>>()
            val durationReaders = mutableListOf<ClassDef>()
            walk(apk) { classDef, method ->
                if (classDef.type == closeHolder || classDef.type == durationHolder) return@walk
                val code = method.implementation?.instructions ?: return@walk
                var asksClose = false
                var readsDuration = false
                var namesCover = false
                for (instruction in code) {
                    when (instruction.opcode) {
                        Opcode.INVOKE_STATIC -> instruction.getReference<MethodReference>()?.let {
                            if (it.definingClass == closeHolder && it.returnType == "I" && it.parameterTypes.isEmpty()) asksClose = true
                        }
                        Opcode.SGET_OBJECT -> instruction.getReference<FieldReference>()?.let {
                            if (it.definingClass == durationHolder) readsDuration = true
                        }
                        Opcode.CHECK_CAST -> if (instruction.getReference<TypeReference>()?.type == COVER_ASSEM) namesCover = true
                        else -> {}
                    }
                }
                if (asksClose && readsDuration) meeting += "${classDef.type}->${method.name}" to namesCover
                if (readsDuration) durationReaders += classDef
            }
            // The play subscriber: it returns before the turn starts unless the close value lets
            // the disc go or the duration is above 0, and it is handed the cover assem it starts.
            assertEquals("$version: both reads are asked by $meeting", 1, meeting.size)
            assertTrue("$version: ${meeting.single().first} is not handed the cover assem", meeting.single().second)
            // The disc's turn itself reads the duration for its length, and is built on the cover.
            assertTrue(
                "$version: no reader of the duration is built on the cover: ${durationReaders.map { it.type }}",
                durationReaders.any { reader ->
                    reader.methods.any { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf(COVER_ASSEM) }
                },
            )
        }
    }

    @Test
    fun `the answer is asked for on the read's own register just before it is boxed`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val reads = readsOf(apk)
            for (read in DISC_READS) {
                val native = reads.getValue(read.key)
                val before = native.implementation!!.instructions.toList()
                val hooked = MutableMethod(native)
                hooked.answerMusicDiscSetting("Hide video overlays", read.answer)
                val after = hooked.implementation!!.instructions.toList()

                assertEquals("$version: ${read.key} grew by", before.size + 2, after.size)
                val boxing = after.indexOfFirst { it.isIntBoxing() }
                assertTrue("$version: ${read.key} boxes nothing after the hook", boxing >= 3)
                val register = after[boxing].firstRegister()
                // TikTok's read lands in the register, MusicDiscSpin answers into the same one,
                // and the boxing takes the answer.
                assertEquals("$version: ${read.key}", Opcode.MOVE_RESULT, after[boxing - 3].opcode)
                assertEquals("$version: ${read.key}", register, (after[boxing - 3] as OneRegisterInstruction).registerA)
                val call = after[boxing - 2].getReference<MethodReference>()!!
                assertEquals(MUSIC_DISC_SPIN_DESCRIPTOR, call.definingClass)
                assertEquals(read.answer, call.name)
                assertEquals(listOf("I"), call.parameterTypes.map(CharSequence::toString))
                assertEquals("I", call.returnType)
                assertEquals(register, after[boxing - 2].firstRegister())
                assertEquals(Opcode.MOVE_RESULT, after[boxing - 1].opcode)
                assertEquals(register, (after[boxing - 1] as OneRegisterInstruction).registerA)
                // Everything else is TikTok's, in its own order.
                assertEquals(
                    "$version: ${read.key}",
                    before.map { it.opcode },
                    (after.take(boxing - 2) + after.drop(boxing)).map { it.opcode },
                )
            }
        }
    }

    @Test
    fun `only a no-argument body that loads the key, reads an AB int and boxes one int is taken`() {
        val key = MUSIC_ANIMATION_CLOSE_KEY
        fun body(loads: String = key, getter: String = "(IILjava/lang/String;Z)I", boxings: Int = 1) = listOf(
            "invoke-static {}, Lfixture/Ab;->get()Lfixture/Ab;",
            "move-result-object v4",
            "const-string v3, \"$loads\"",
            "const/4 v2, 0x1",
            "const/16 v1, 0x7c00",
            "const/4 v0, 0x3",
            "invoke-virtual {v4, v1, v0, v3, v2}, Lfixture/Ab;->read$getter",
            "move-result v0",
        ) + List(boxings) {
            listOf("invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;", "move-result-object v1")
        }.flatten() + "return-object v1"

        assertTrue(isBoxedAbIntRead(lambda(body()), key))
        // A key of the same stem is found by the fingerprint's string, and the exact load keeps it out.
        assertFalse(isBoxedAbIntRead(lambda(body(loads = "${key}_v2")), key))
        assertFalse(isBoxedAbIntRead(lambda(body(loads = "video_music_cover_visual_opt_rotation_duration")), key))
        // The boolean getter is another setting's read.
        assertFalse(isBoxedAbIntRead(lambda(body(getter = "(ILjava/lang/String;ZZ)Z")), key))
        assertFalse(isBoxedAbIntRead(lambda(body(boxings = 0)), key))
        assertFalse(isBoxedAbIntRead(lambda(body(boxings = 2)), key))
        // The ABMock table that lists the key is void, and a read with an argument is not the lambda.
        assertFalse(isBoxedAbIntRead(lambda(body().dropLast(1) + "return-void", returns = "V"), key))
        assertFalse(isBoxedAbIntRead(lambda(body(), parameters = listOf("I")), key))
        // No code, nothing to read.
        assertFalse(isBoxedAbIntRead(
            ImmutableMethod("Lfixture/Read;", "invoke", emptyList(), "Ljava/lang/Object;", AccessFlags.PUBLIC.value, null, null, null),
            key,
        ))
    }

    @Test
    fun `a body that boxes two ints stops the patch and is left as it was`() {
        val twice = lambda(listOf(
            "const/4 v0, 0x3",
            "invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;",
            "move-result-object v1",
            "invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;",
            "move-result-object v1",
            "return-object v1",
        ))
        val before = twice.implementation!!.instructions.toList()
        assertThrows(PatchException::class.java) { twice.answerMusicDiscSetting("Hide video overlays", "closeSetting") }
        assertEquals(before, twice.implementation!!.instructions.toList())
    }

    @Test
    fun `a boxing above v15 is answered through the range form`() {
        val high = lambda(
            listOf(
                "const/4 v0, 0x3",
                "move/from16 v17, v0",
                "invoke-static/range {v17 .. v17}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;",
                "move-result-object v1",
                "return-object v1",
            ),
            registers = 20,
        )
        high.answerMusicDiscSetting("Hide video overlays", "rotationSeconds")
        val after = high.implementation!!.instructions.toList()
        assertEquals(Opcode.INVOKE_STATIC_RANGE, after[2].opcode)
        assertEquals(17, (after[2] as RegisterRangeInstruction).startRegister)
        assertEquals(1, (after[2] as RegisterRangeInstruction).registerCount)
        assertEquals("rotationSeconds", after[2].getReference<MethodReference>()!!.name)
        assertEquals(Opcode.MOVE_RESULT, after[3].opcode)
        assertEquals(17, (after[3] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, after[4].opcode)
        assertEquals("valueOf", after[4].getReference<MethodReference>()!!.name)
    }

    @Test
    fun `each hold past the close setting is one method on each declared build and its fingerprint takes exactly it`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val byShape = DISC_HOLDS.associateWith { mutableListOf<String>() }
            val byFingerprint = DISC_HOLDS.associateWith { mutableListOf<String>() }
            walk(apk) { classDef, method ->
                for (hold in DISC_HOLDS) {
                    if (hold.shape(method)) byShape.getValue(hold) += "${classDef.type}->${method.name}"
                    if (hold.fingerprint.takes(method, classDef)) byFingerprint.getValue(hold) += "${classDef.type}->${method.name}"
                }
            }
            for (hold in DISC_HOLDS) {
                assertEquals("$version: ${hold.name} is ${byShape[hold]}", 1, byShape.getValue(hold).size)
                assertEquals("$version: ${hold.name}'s fingerprint", byShape[hold], byFingerprint[hold])
            }
        }
    }

    @Test
    fun `the holds lead to one another and the degrade check is what starts the animator`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val chain = chainOf(apk)
            val hold = chain.holds.getValue(COVER_HOLD)
            val subscriber = chain.holds.getValue(PLAY_SUBSCRIBER)
            val trigger = chain.holds.getValue(PLAY_HOOK)

            // The subscriber asks the very hold the fingerprint takes.
            val asked = subscriber.code().mapNotNull { it.getReference<MethodReference>() }
                .filter { it.definingClass == MUSIC_COVER_VM && it.returnType == "Z" && it.parameterTypes.isEmpty() }
                .map { it.name }.distinct()
            assertEquals("$version: the subscriber asks $asked", listOf(hold.name), asked)

            // The cell's play hook reads the subscriber's high frame rate hold and the load strategy's
            // switch, each once and before it hands over the event the subscriber waits for.
            val event = trigger.code().indexOfFirst { it.getReference<StringReference>()?.string == START_PLAY_ANIMATION_KEY }
            val frameRate = subscriber.code()[highFrameRateHoldIndex(subscriber)].getReference<FieldReference>()!!
            val frameRateRead = staticBooleanReadIndex(trigger, frameRate)
            assertTrue("$version: the play hook reads the frame rate hold at $frameRateRead, the event at $event", frameRateRead in 0 until event)
            val switches = chain.strategyClass.methods.filter { playAnimationSwitchIndex(it) >= 0 }
            assertEquals("$version: ${chain.strategyClass.type} switches in ${switches.map { it.name }}", 1, switches.size)
            val off = switches.single().code()[playAnimationSwitchIndex(switches.single())].getReference<FieldReference>()!!
            assertFalse("$version: the switch is the frame rate hold", off.name == frameRate.name && off.definingClass == frameRate.definingClass)
            val offRead = staticBooleanReadIndex(trigger, off)
            assertTrue("$version: the play hook reads the switch at $offRead, the event at $event", offRead in 0 until event)

            // The check onViewCreated registers has one enable(), of the interface whose enable()
            // the starter asks before it starts the animator, and the runnable starts it under the key.
            assertEquals("$version: ${chain.checkClass.type}'s enable()", 1, chain.checkClass.methods.count(::isDegradeEnable))
            assertEquals("$version: the animator is started by ${chain.starters.map { "${it.definingClass}->${it.name}" }}", 1, chain.starters.size)
            val starter = chain.starters.single()
            val enable = starter.code().mapNotNull { it.getReference<MethodReference>() }.first { it.name == "enable" }
            assertTrue(
                "$version: ${chain.checkClass.type} is no ${enable.definingClass}",
                enable.definingClass == chain.checkClass.type || enable.definingClass in chain.checkClass.interfaces,
            )
            assertTrue(
                "$version: nothing starts the animator under $MUSIC_COVER_DEGRADE_KEY: ${chain.startedUnderKey}",
                chain.startedUnderKey.any { it.definingClass == starter.definingClass && it.name == starter.name },
            )
        }
    }

    @Test
    fun `every hook lands on the value it answers in the real methods`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val chain = chainOf(apk)
            val hold = MutableMethod(chain.holds.getValue(COVER_HOLD))
            val subscriber = MutableMethod(chain.holds.getValue(PLAY_SUBSCRIBER))
            val trigger = MutableMethod(chain.holds.getValue(PLAY_HOOK))
            val degrade = MutableMethod(chain.checkClass.methods.single(::isDegradeEnable))
            val switch = chain.strategyClass.methods.single { playAnimationSwitchIndex(it) >= 0 }
            val frameRate = subscriber.code()[highFrameRateHoldIndex(subscriber)].getReference<FieldReference>()!!
            val off = switch.code()[playAnimationSwitchIndex(switch)].getReference<FieldReference>()!!
            val sizes = listOf(hold, subscriber, trigger, degrade).associateWith { it.counted() }

            MusicDiscHooks(
                close = MutableMethod(chain.reads.getValue(MUSIC_ANIMATION_CLOSE_KEY)),
                duration = MutableMethod(chain.reads.getValue(MUSIC_COVER_ROTATION_DURATION_KEY)),
                hold = hold,
                subscriber = subscriber,
                trigger = trigger,
                degrade = degrade,
                playAnimationSwitch = switch,
            ).apply("Hide video overlays")

            // The returns: each answered on its own register, and no branch lands past the answer.
            for ((method, answer) in listOf(hold to "coverAnimationHeld", degrade to "degradeAllows")) {
                val code = method.code()
                val returns = code.withIndex().filter { it.value.opcode == Opcode.RETURN }
                assertTrue("$version: ${method.name} returns nothing", returns.isNotEmpty())
                assertEquals("$version: ${method.name} grew by", sizes.getValue(method) + 2 * returns.size, method.counted())
                for ((at, instruction) in returns) {
                    assertAnswer("$version: ${method.name} at $at", code, at - 2, (instruction as OneRegisterInstruction).registerA, answer)
                }
                val landings = returns.map { it.index }.toSet()
                for (branch in code.filterIsInstance<BuilderOffsetInstruction>()) {
                    assertFalse("$version: a branch in ${method.name} skips the answer", branch.target.location.index in landings)
                }
            }
            // The reads: each answered right after it lands, before anything tests it.
            for ((method, field, answer) in listOf(
                Triple(subscriber, frameRate, "highFrameRateHeld"),
                Triple(trigger, frameRate, "highFrameRateHeld"),
                Triple(trigger, off, "playAnimationSwitchedOff"),
            )) {
                val code = method.code()
                val read = staticBooleanReadIndex(method, field)
                assertTrue("$version: ${method.name} no longer reads ${field.name} once", read >= 0)
                assertAnswer("$version: ${method.name} after ${field.name}", code, read + 1, (code[read] as OneRegisterInstruction).registerA, answer)
            }
            assertEquals("$version: the subscriber grew by", sizes.getValue(subscriber) + 2, subscriber.counted())
            assertEquals("$version: the play hook grew by", sizes.getValue(trigger) + 4, trigger.counted())
        }
    }

    @Test
    fun `a boolean hook with nothing to answer stops the patch and leaves the method as it was`() {
        val void = lambda(listOf("const/4 v0, 0x1", "return-void"), returns = "V")
        val voidBefore = void.implementation!!.instructions.toList()
        assertThrows(PatchException::class.java) { void.answerMusicDiscReturn("Hide video overlays", "degradeAllows") }
        assertEquals(voidBefore, void.implementation!!.instructions.toList())

        val read = lambda(listOf("sget-boolean v0, Lfixture/Hold;->held:Z", "return v0"), returns = "Z")
        val readBefore = read.implementation!!.instructions.toList()
        assertThrows(PatchException::class.java) { read.answerMusicDiscRead("Hide video overlays", 1, "highFrameRateHeld") }
        assertEquals(readBefore, read.implementation!!.instructions.toList())

        read.answerMusicDiscRead("Hide video overlays", 0, "highFrameRateHeld")
        val after = read.implementation!!.instructions.toList()
        assertEquals(4, after.size)
        assertAnswer("the read", after, 1, 0, "highFrameRateHeld")
        assertEquals(Opcode.RETURN, after[3].opcode)
    }

    @Test
    fun `a boolean returned from above v15 is answered through the range form`() {
        val high = lambda(listOf("const/4 v0, 0x1", "move/from16 v17, v0", "return v17"), returns = "Z", registers = 20)
        high.answerMusicDiscReturn("Hide video overlays", "coverAnimationHeld")
        val after = high.implementation!!.instructions.toList()
        assertEquals(Opcode.INVOKE_STATIC_RANGE, after[2].opcode)
        assertEquals(1, (after[2] as RegisterRangeInstruction).registerCount)
        assertAnswer("the high return", after, 2, 17, "coverAnimationHeld")
        assertEquals(Opcode.RETURN, after[4].opcode)
    }

    @Test
    fun `the patch hands every hold to the extension methods that answer them`() {
        val repo = if (File("src/main/kotlin").isDirectory) File("..") else File(".")
        val dir = "patches/src/main/kotlin/app/morphe/patches/tiktok/interaction/videooverlays"
        val hooks = File(repo, "$dir/MusicDiscSpin.kt").readText()
        for (call in listOf(
            "close.answerMusicDiscSetting(patch, \"closeSetting\")",
            "duration.answerMusicDiscSetting(patch, \"rotationSeconds\")",
            "hold.answerMusicDiscReturn(patch, \"coverAnimationHeld\")",
            "subscriber.answerMusicDiscRead(patch, highFrameRateHoldIndex(subscriber), \"highFrameRateHeld\")",
            "trigger.answerMusicDiscRead(patch, staticBooleanReadIndex(trigger, frameRateHold), \"highFrameRateHeld\")",
            "trigger.answerMusicDiscRead(patch, staticBooleanReadIndex(trigger, playAnimationOff), \"playAnimationSwitchedOff\")",
            "degrade.answerMusicDiscReturn(patch, \"degradeAllows\")",
        )) {
            assertTrue("MusicDiscHooks never makes $call", hooks.contains(call))
        }
        val patch = File(repo, "$dir/HideVideoOverlaysPatch.kt").readText()
        assertTrue("the holds are never hooked", patch.contains("musicDisc.apply(PATCH_NAME)"))
        assertTrue("the rows are never shown", patch.contains("SettingsStatus;->enableMusicDiscSpin()V"))
        val extension = File(repo, "extensions/tiktok/src/main/java/app/morphe/extension/tiktok/feed/MusicDiscSpin.java").readText()
        for (signature in listOf(
            "public static int closeSetting(int value)",
            "public static int rotationSeconds(int seconds)",
            "public static boolean coverAnimationHeld(boolean held)",
            "public static boolean highFrameRateHeld(boolean held)",
            "public static boolean playAnimationSwitchedOff(boolean off)",
            "public static boolean degradeAllows(boolean allows)",
        )) {
            assertTrue("the extension has no $signature", extension.contains(signature))
        }
        val status = File(repo, "extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/SettingsStatus.java").readText()
        assertTrue("the status has no switch for the rows", status.contains("public static void enableMusicDiscSpin()"))
    }

    /** A static `(Z)Z` call on MusicDiscSpin at [at] on [register], and its answer back into it. */
    private fun assertAnswer(where: String, code: List<Instruction>, at: Int, register: Int, answer: String) {
        val call = code[at].getReference<MethodReference>()
        assertEquals(where, MUSIC_DISC_SPIN_DESCRIPTOR, call?.definingClass)
        assertEquals(where, answer, call!!.name)
        assertEquals(where, listOf("Z"), call.parameterTypes.map(CharSequence::toString))
        assertEquals(where, "Z", call.returnType)
        assertEquals(where, register, code[at].firstRegister())
        assertEquals(where, Opcode.MOVE_RESULT, code[at + 1].opcode)
        assertEquals(where, register, (code[at + 1] as OneRegisterInstruction).registerA)
    }

    /** The disc's way on one build, found in two walks: the methods first, then the classes they name. */
    private fun chainOf(apk: File): DiscChain {
        val reads = mutableMapOf<String, Method>()
        val holds = mutableMapOf<DiscHold, Method>()
        walk(apk) { _, method ->
            for (read in DISC_READS) {
                if (isBoxedAbIntRead(method, read.key)) assertTrue("${read.key} is read twice", reads.put(read.key, method) == null)
            }
            for (hold in DISC_HOLDS) {
                if (hold.shape(method)) assertTrue("${hold.name} is found twice", holds.put(hold, method) == null)
            }
        }
        val checkType = degradeCheckType(holds.getValue(DEGRADE_REGISTRATION))
        assertTrue("onViewCreated registers nothing under $MUSIC_COVER_DEGRADE_KEY", checkType != null)
        val strategyType = holds.getValue(LOAD_STRATEGY).definingClass

        var checkClass: ClassDef? = null
        var strategyClass: ClassDef? = null
        val starters = mutableListOf<Method>()
        val startedUnderKey = mutableListOf<MethodReference>()
        walk(apk) { classDef, method ->
            if (classDef.type == checkType) checkClass = classDef
            if (classDef.type == strategyType) strategyClass = classDef
            val code = method.code()
            if (method.returnType == "V" && method.parameterTypes.map(CharSequence::toString) == ANIMATOR_START) {
                val enable = code.indexOfFirst { instruction ->
                    instruction.getReference<MethodReference>()?.let {
                        it.name == "enable" && it.returnType == "Z" && it.parameterTypes.isEmpty()
                    } == true
                }
                val start = code.indexOfLast { instruction ->
                    instruction.getReference<MethodReference>()?.let {
                        it.definingClass == "Landroid/animation/Animator;" && it.name == "start"
                    } == true
                }
                if (enable >= 0 && start > enable) starters += method
            }
            if (code.any { it.getReference<StringReference>()?.string == MUSIC_COVER_DEGRADE_KEY }) {
                code.filter { it.opcode == Opcode.INVOKE_STATIC }.mapNotNull { it.getReference<MethodReference>() }
                    .filter { it.parameterTypes.map(CharSequence::toString) == ANIMATOR_START }
                    .forEach { startedUnderKey += it }
            }
        }
        return DiscChain(
            reads, holds,
            checkClass ?: throw AssertionError("$checkType is not in the build"),
            strategyClass ?: throw AssertionError("$strategyType is not in the build"),
            starters, startedUnderKey,
        )
    }

    /** A no-argument lambda body answering Object, made of [lines]. */
    private fun lambda(
        lines: List<String>,
        returns: String = "Ljava/lang/Object;",
        parameters: List<String> = emptyList(),
        registers: Int = 6,
    ) = MutableMethod(
        ImmutableMethod(
            "Lfixture/Read;", "invoke", parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructions(0, lines.joinToString("\n")) }

    private fun Instruction.isIntBoxing() =
        (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) &&
            getReference<MethodReference>()?.let { it.definingClass == "Ljava/lang/Integer;" && it.name == "valueOf" } == true

    /** The one register a one-argument static call is handed, in either form. */
    private fun Instruction.firstRegister(): Int = when (this) {
        is FiveRegisterInstruction -> registerC
        is RegisterRangeInstruction -> startRegister
        else -> throw AssertionError("$opcode hands no register")
    }

    /** Each key's read on one build, by its shape. */
    private fun readsOf(apk: File): Map<String, Method> {
        val found = mutableMapOf<String, Method>()
        walk(apk) { _, method ->
            for (read in DISC_READS) {
                if (isBoxedAbIntRead(method, read.key)) {
                    assertTrue("${read.key} is read twice", found.put(read.key, method) == null)
                }
            }
        }
        return found
    }

    /** Every method of one build, walked once and never held (the dex has millions). */
    private fun walk(apk: File, visit: (ClassDef, Method) -> Unit) {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                for (method in classDef.methods) visit(classDef, method)
            }
        }
    }
}
