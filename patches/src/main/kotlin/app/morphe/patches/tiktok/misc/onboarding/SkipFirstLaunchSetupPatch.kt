/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.onboarding

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstruction
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH_NAME = "Skip first-launch setup"

internal const val FIRST_LAUNCH_SETUP_EXTENSION = "Lapp/morphe/extension/tiktok/misc/FirstLaunchSetup;"

/**
 * TikTok's setup (the "NUJ" flow) asks each step whether it should show through this one static
 * method, which answers Pair(reason, show). Its first check passes over a step named in a deep
 * link's skip list, then a step's own rules, a local rule, the server's strategy and its filters
 * answer in turn. The flow engine logs a no and moves on to the next step. X/1CxW.LIZJ on 47.0.3,
 * X/1Bp3.LIZJ on 47.1.3 and X/1DUo.LIZJ on 47.1.4, each with four registers and the step in p0.
 */
internal object SetupStepDecisionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lkotlin/Pair;",
    parameters = listOf("L"),
    strings = listOf("ignore_by_deeplink", "local_rule", "strategy_config").map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * The step interface the decision reads ids through: its one parameter, which the method itself
 * calls getId() on. Refuses a method that reads the id some other way, since the hook calls the
 * same getId() on the same register.
 */
internal fun setupStepType(decision: Method): String {
    val step = decision.parameterTypes.singleOrNull()?.toString()
        ?: throw PatchException("$PATCH_NAME: ${decision.name} doesn't take one step")
    val body = decision.implementation ?: throw PatchException("$PATCH_NAME: ${decision.name} has no code")
    val self = body.registerCount - 1
    val readsId = body.instructions.any { instruction ->
        instruction.opcode == Opcode.INVOKE_INTERFACE &&
            (instruction as FiveRegisterInstruction).registerC == self &&
            instruction.getReference<MethodReference>()?.let {
                it.definingClass == step && it.name == "getId" && it.parameterTypes.isEmpty() &&
                    it.returnType == "Ljava/lang/String;"
            } == true
    }
    if (!readsId) {
        throw PatchException("$PATCH_NAME: ${decision.definingClass}->${decision.name} doesn't read its step's id with getId()")
    }
    return step
}

/**
 * Answers no for the steps the extension names, before TikTok's own checks run. The reason is
 * "local_rule", one TikTok sends itself, because the flow reports each step's reason with its
 * setup events.
 */
internal fun MutableMethod.skipSetupSteps(step: String) {
    requireLocals(PATCH_NAME, 3)
    addInstructionsWithLabels(
        0,
        """
            invoke-interface/range { p0 .. p0 }, $step->getId()Ljava/lang/String;
            move-result-object v0
            invoke-static { v0 }, $FIRST_LAUNCH_SETUP_EXTENSION->skipStep(Ljava/lang/String;)Z
            move-result v0
            if-eqz v0, :hushfeed_setup_step_shows
            new-instance v0, Lkotlin/Pair;
            const-string v1, "local_rule"
            sget-object v2, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
            invoke-direct { v0, v1, v2 }, Lkotlin/Pair;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V
            return-object v0
            :hushfeed_setup_step_shows
            nop
        """,
    )
}

/**
 * Off in the default selection, since TikTok's setup is a matter of taste. Picked, its switch
 * starts on, because the setup runs before anyone can reach the switch.
 */
@Suppress("unused")
val skipFirstLaunchSetupPatch = bytecodePatch(
    name = "Skip first-launch setup",
    description = "Skips TikTok's setup screens on a fresh install, like the interest picker and the swipe tutorial. Sign-in and age screens still show. Its switch is on once picked, since setup runs before you can reach settings. Turn it off in Metra settings > App behavior.",
    default = false,
) {
    category("Settings")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        val decision = SetupStepDecisionFingerprint.method
        val step = setupStepType(decision)
        decision.requireLocals(PATCH_NAME, 3)
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableFirstLaunchSetup()V",
        )
        decision.skipSetupSteps(step)
    }
}
