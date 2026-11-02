/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.shared

import app.morphe.Fixtures
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.tiktok.misc.login.passkey.PASSKEY_SIGN_IN_SWITCH
import app.morphe.patches.tiktok.misc.login.passkey.PasskeyDeviceSupportFingerprint
import app.morphe.patches.tiktok.misc.login.passkey.installPasskeySignInSwitch
import app.morphe.patches.tiktok.misc.optimizer.AnimatedDrawableFactoryFingerprint
import app.morphe.patches.tiktok.misc.optimizer.BACKGROUND_TRAFFIC_SWITCH
import app.morphe.patches.tiktok.misc.optimizer.BufferPreloadGateFingerprint
import app.morphe.patches.tiktok.misc.optimizer.InitPushTaskFingerprint
import app.morphe.patches.tiktok.misc.optimizer.PERFORMANCE_SWITCHES
import app.morphe.patches.tiktok.misc.optimizer.REALTIME_SPLASH_DESCRIPTOR
import app.morphe.patches.tiktok.misc.optimizer.RealTimeSplashTaskFingerprint
import app.morphe.patches.tiktok.misc.optimizer.SPLASH_AD_SWITCH
import app.morphe.patches.tiktok.misc.optimizer.SPLASH_SERVICE_DESCRIPTOR
import app.morphe.patches.tiktok.misc.optimizer.SPLASH_SETTING_DESCRIPTOR
import app.morphe.patches.tiktok.misc.optimizer.SplashPreloadEntryFingerprint
import app.morphe.patches.tiktok.misc.optimizer.SplashPreloadTaskFingerprint
import app.morphe.patches.tiktok.misc.optimizer.TopViewPreloadTaskFingerprint
import app.morphe.patches.tiktok.misc.optimizer.UPDATE_CHECKS_SWITCH
import app.morphe.patches.tiktok.misc.optimizer.UpdateBackgroundTaskFingerprint
import app.morphe.patches.tiktok.misc.optimizer.UpdateBootFinishedTaskFingerprint
import app.morphe.patches.tiktok.misc.optimizer.backendBuilderCall
import app.morphe.patches.tiktok.misc.optimizer.framePreparerGateIndex
import app.morphe.patches.tiktok.misc.optimizer.installAnimatedImageCacheSwitch
import app.morphe.patches.tiktok.misc.optimizer.installSplashAdSwitch
import app.morphe.patches.tiktok.misc.optimizer.installUpdateCheckSwitch
import app.morphe.patches.tiktok.misc.optimizer.limitBackgroundTraffic
import app.morphe.patches.tiktok.misc.translation.COMMENT_TRANSLATE_BUTTON_SWITCH
import app.morphe.patches.tiktok.misc.translation.findCommentTranslateButtonTargets
import app.morphe.patches.tiktok.misc.translation.installCommentTranslateButton
import app.morphe.patches.tiktok.misc.voicecomments.VOICE_COMMENTS_SWITCH
import app.morphe.patches.tiktok.misc.voicecomments.installVoiceCommentSwitch
import app.morphe.patches.tiktok.misc.voicecomments.resolveVoiceCommentPublishGate
import app.morphe.patches.tiktok.privacy.AI_PROFILING_SWITCH
import app.morphe.patches.tiktok.privacy.PitayaLiteStartFingerprint
import app.morphe.patches.tiktok.privacy.PitayaPluginLookupFingerprint
import app.morphe.patches.tiktok.privacy.PitayaRealProviderFingerprint
import app.morphe.patches.tiktok.privacy.installAiProfilingSwitch
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.numberOfParameterRegisters
import app.morphe.patcher.util.numberOfParameterRegistersLogical
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The six patches that used to change TikTok with no switch now ask one first: Skip the splash
 * ad, Limit background traffic, Drop the animated image cache, Skip update checks, Stop on-device
 * AI profiling and Enable voice comments. Skip passkey sign-in came later with its switch already
 * in front, the same way, and so did the two gates of the translate button in Translate comments.
 * This puts their real hooks on each declared build and reads every hooked method back.
 *
 * <p>Each one has to ask its switch before anything else runs, leave with the patch's old answer
 * when the switch says yes, and land on TikTok's own first instruction, with all of TikTok's code
 * still there, when it says no. That last part is what makes the patches safe in the default
 * selection: with the switch off, which is how it starts, TikTok runs as it ships. The question's
 * answer may only land in a local, never on `this` or an argument TikTok's code reads later.
 */
class SwitchedReturnFixturesTest {
    @get:Rule val temporary = TemporaryFolder()

    /** A hooked method as it was before the hook: where to find it again, and what it ran. */
    private class Before(
        val definingClass: String,
        val signature: String,
        val returnType: String,
        val locals: Int,
        val opcodes: List<Opcode>,
        val switch: String,
        val answer: Boolean?,
    )

    private fun signature(method: Method) =
        "${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"

    private fun before(method: Method, switch: String, answer: Boolean? = null) = Before(
        method.definingClass,
        signature(method),
        method.returnType,
        method.implementation!!.registerCount - method.numberOfParameterRegisters,
        method.implementation!!.instructions.map { it.opcode },
        switch,
        answer,
    )

    /** The splash gates the patch reviews: every public final no-argument boolean on the three classes. */
    private fun BytecodePatchContext.splashGates(): List<Method> =
        listOf(SPLASH_SETTING_DESCRIPTOR, REALTIME_SPLASH_DESCRIPTOR, SPLASH_SERVICE_DESCRIPTOR).flatMap { type ->
            classDefBy(type).methods.filter { method ->
                method.returnType == "Z" && method.parameterTypes.isEmpty() &&
                    AccessFlags.PUBLIC.isSet(method.accessFlags) && AccessFlags.FINAL.isSet(method.accessFlags)
            }
        }

    @Test
    fun `every switched hook asks its switch first and keeps TikTok's code behind it on every declared build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            var checked = 0
            var expected = -1
            var cacheChecked = false
            val probe = bytecodePatch(name = "switched return probe") {
                execute {
                    val translateButton = findCommentTranslateButtonTargets(this::classDefByOrNull, this::classDefForEach)
                    val hooks = buildList {
                        listOf(
                            SplashPreloadTaskFingerprint, SplashPreloadEntryFingerprint,
                            TopViewPreloadTaskFingerprint, RealTimeSplashTaskFingerprint,
                        ).forEach { add(before(it.method, SPLASH_AD_SWITCH)) }
                        splashGates().forEach { add(before(it, SPLASH_AD_SWITCH, false)) }
                        add(before(BufferPreloadGateFingerprint.method, BACKGROUND_TRAFFIC_SWITCH, false))
                        add(before(InitPushTaskFingerprint.method, BACKGROUND_TRAFFIC_SWITCH))
                        add(before(UpdateBackgroundTaskFingerprint.method, UPDATE_CHECKS_SWITCH))
                        add(before(UpdateBootFinishedTaskFingerprint.method, UPDATE_CHECKS_SWITCH))
                        add(before(PitayaPluginLookupFingerprint.method, AI_PROFILING_SWITCH))
                        add(before(PitayaRealProviderFingerprint.method, AI_PROFILING_SWITCH))
                        add(before(PitayaLiteStartFingerprint.method, AI_PROFILING_SWITCH))
                        add(before(resolveVoiceCommentPublishGate(), VOICE_COMMENTS_SWITCH, true))
                        add(before(PasskeyDeviceSupportFingerprint.method, PASSKEY_SIGN_IN_SWITCH, false))
                        add(before(translateButton.enabledRead, COMMENT_TRANSLATE_BUTTON_SWITCH, true))
                        add(before(translateButton.styleAnswer, COMMENT_TRANSLATE_BUTTON_SWITCH, true))
                    }
                    // Four tasks, the reviewed gates (two or three on the splash service), the
                    // buffer gate, push setup, two update tasks, three Pitaya doors, the voice gate,
                    // the passkey support check and the translate button's two gates.
                    assertTrue("$version: ${splashGates().size} splash gates", splashGates().size in 5..6)
                    expected = hooks.size

                    // The animated image cache passes the preparer count through rather than
                    // returning, and leaves the factory's caching strategy alone (#130).
                    val factory = AnimatedDrawableFactoryFingerprint.method
                    val factoryBefore = factory.implementation!!.instructions.map { it.opcode }
                    val call = factory.backendBuilderCall()!!
                    fun builder() = mutableClassDefBy(factory.definingClass).methods.single {
                        it.name == call.name && it.parameterTypes.map(CharSequence::toString) ==
                            call.parameterTypes.map(CharSequence::toString) && it.returnType == call.returnType
                    }
                    val gate = builder().framePreparerGateIndex()!!
                    val gateRegister = (builder().implementation!!.instructions.toList()[gate] as OneRegisterInstruction).registerA
                    val builderBefore = builder().implementation!!.instructions.map { it.opcode }

                    installSplashAdSwitch()
                    limitBackgroundTraffic(skipPushSetup = true)
                    installAnimatedImageCacheSwitch()
                    installUpdateCheckSwitch()
                    installAiProfilingSwitch()
                    installVoiceCommentSwitch()
                    installPasskeySignInSwitch()
                    installCommentTranslateButton()

                    for (hook in hooks) {
                        val now = mutableClassDefBy(hook.definingClass).methods.filter { signature(it) == hook.signature }
                        assertEquals("$version: ${hook.definingClass}->${hook.signature} is still one method", 1, now.size)
                        assertSwitched("$version: ${hook.definingClass}->${hook.signature}", hook, now.single())
                        checked++
                    }

                    assertEquals("$version: the animated drawable factory is untouched", factoryBefore,
                        factory.implementation!!.instructions.map { it.opcode })
                    assertPassedThrough("$version: frame preparer gate", builder().implementation!!.instructions.toList(),
                        gate, gateRegister, "framesToPrepare", builderBefore)
                    cacheChecked = true
                }
            }
            listOf(
                SplashPreloadTaskFingerprint, SplashPreloadEntryFingerprint, TopViewPreloadTaskFingerprint,
                RealTimeSplashTaskFingerprint, BufferPreloadGateFingerprint, InitPushTaskFingerprint,
                UpdateBackgroundTaskFingerprint, UpdateBootFinishedTaskFingerprint,
                PitayaPluginLookupFingerprint, PitayaRealProviderFingerprint, PitayaLiteStartFingerprint,
                AnimatedDrawableFactoryFingerprint, PasskeyDeviceSupportFingerprint,
            ).forEach { it.clearMatch() }
            Patcher(PatcherConfig(apk, temporary.newFolder())).use { patcher ->
                patcher += setOf(probe)
                runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
            }
            assertTrue("$version: only $expected hooks were found", expected >= 20)
            assertEquals("$version: every hook was read back", expected, checked)
            assertTrue("$version: the animated image cache was read back", cacheChecked)
        }
    }

    private fun assertSwitched(where: String, hook: Before, method: Method) {
        val body = method.implementation!!.instructions.toList()
        val ask = body.indexOfFirst { it.opcode == Opcode.INVOKE_STATIC }
        assertTrue("$where: never asks its switch", ask >= 0)

        if (hook.locals >= 1) {
            assertEquals("$where: something runs before the question", 0, ask)
        } else {
            // A frame with no local is cloned with room for its parameters, and the copies back
            // down are all that may run first.
            assertEquals("$where: parameter copies before the question", method.numberOfParameterRegistersLogical, ask)
            assertTrue("$where: runs something other than parameter copies first", body.take(ask).all {
                it.opcode in setOf(Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_FROM16, Opcode.MOVE_WIDE_FROM16)
            })
        }

        val reference = body[ask].getReference<MethodReference>()!!
        assertEquals("$where: asks the wrong switch", hook.switch,
            "${reference.definingClass}->${reference.name}(${reference.parameterTypes.joinToString("")})${reference.returnType}")
        assertEquals("$where: keeps the answer", Opcode.MOVE_RESULT, body[ask + 1].opcode)
        val answer = (body[ask + 1] as OneRegisterInstruction).registerA
        val firstParameter = method.implementation!!.registerCount - method.numberOfParameterRegisters
        if (ask == 0) assertTrue("$where: the answer lands on a parameter (v$answer)", answer < firstParameter)
        val test = body[ask + 2]
        assertEquals("$where: tests the answer", Opcode.IF_EQZ, test.opcode)
        assertEquals("$where: tests another register", answer, (test as OneRegisterInstruction).registerA)

        val leave = when (hook.returnType) {
            "V" -> listOf(Opcode.RETURN_VOID)
            "Z" -> listOf(Opcode.CONST_4, Opcode.RETURN)
            else -> listOf(Opcode.CONST_4, Opcode.RETURN_OBJECT)
        }
        // Above v15 the constant is written with const/16, the same value either way.
        val written = body.subList(ask + 3, ask + 3 + leave.size).map {
            if (it.opcode == Opcode.CONST_16) Opcode.CONST_4 else it.opcode
        }
        assertEquals("$where: what it does with the switch on", leave, written)
        if (hook.returnType != "V") {
            val value = (body[ask + 3] as NarrowLiteralInstruction).narrowLiteral
            val expected = if (hook.answer == true) 1 else 0
            assertEquals("$where: the answer it gives with the switch on", expected, value)
        }

        // With the switch off the branch lands on TikTok's own first instruction, and everything
        // TikTok wrote is still there in order. Nops are left out: the in-place guard puts its
        // label on one, and the alignment nops before a switch or array payload come and go as
        // the method is rebuilt.
        val addresses = body.runningFold(0) { at, instruction -> at + instruction.codeUnits }
        val landing = addresses.indexOf(addresses[ask + 2] + (test as OffsetInstruction).codeOffset)
        assertTrue("$where: the branch lands nowhere", landing > ask + 2)
        val rest = body.drop(landing).map { it.opcode }.filter { it != Opcode.NOP }
        assertEquals("$where: TikTok's own code behind the switch", hook.opcodes.filter { it != Opcode.NOP }, rest)
    }

    /** The int in [register] goes through [bridge] at [index], and the rest of the method is TikTok's. */
    private fun assertPassedThrough(
        where: String,
        body: List<Instruction>,
        index: Int,
        register: Int,
        bridge: String,
        before: List<Opcode>,
    ) {
        val call = body[index]
        assertEquals("$where: a one-register range call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
        call as RegisterRangeInstruction
        assertEquals("$where: hands over its own register", register, call.startRegister)
        assertEquals(1, call.registerCount)
        val reference = call.getReference<MethodReference>()!!
        assertEquals("$where: the bridge", "$PERFORMANCE_SWITCHES->$bridge(I)I",
            "${reference.definingClass}->${reference.name}(${reference.parameterTypes.joinToString("")})${reference.returnType}")
        assertEquals(Opcode.MOVE_RESULT, body[index + 1].opcode)
        assertEquals("$where: takes the answer back", register, (body[index + 1] as OneRegisterInstruction).registerA)
        assertEquals("$where: TikTok's own code around it", before,
            body.take(index).map { it.opcode } + body.drop(index + 2).map { it.opcode })
    }
}
