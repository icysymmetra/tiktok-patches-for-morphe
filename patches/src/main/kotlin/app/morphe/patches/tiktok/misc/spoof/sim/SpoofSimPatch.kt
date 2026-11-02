/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/misc/spoof/sim/SpoofSimPatch.kt
 */

package app.morphe.patches.tiktok.misc.spoof.sim

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.methodCall
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import app.morphe.patches.tiktok.shared.requireLocals

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/spoof/sim/SpoofSimPatch;"

private object SimIdentityReadFingerprint : Fingerprint(
    filters = listOf(
        anyInstruction(
            methodCall(definingClass = "Landroid/telephony/TelephonyManager;", returnType = "Ljava/lang/String;"),
            methodCall(definingClass = "Landroid/telephony/CellIdentity", name = "getMccString", returnType = "Ljava/lang/String;"),
            methodCall(definingClass = "Landroid/telephony/CellIdentity", name = "getMncString", returnType = "Ljava/lang/String;"),
        ),
    ),
)

@Suppress("unused")
val simSpoofPatch = bytecodePatch(
    name = "SIM spoof",
    description = "Makes TikTok see the SIM country and carrier you choose. It may not change " +
        "your region, since TikTok also goes by your internet connection and account. Starts off. " +
        "Turn it on in Metra settings > Region spoof.",
    default = true,
) {
    category("Settings")
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        // Checked before the first write: the SIM change task's injection writes v0 and then
        // falls into TikTok's own first instruction, so v0 has to be a local.
        CheckSimChangeTaskFingerprint.method.requireLocals("SIM spoof", 1)

        val replacements = mapOf(
            "getSimCountryIso" to "getCountryIso",
            "getNetworkCountryIso" to "getCountryIso",
            "getSimOperator" to "getOperator",
            "getNetworkOperator" to "getOperator",
            "getSimOperatorName" to "getOperatorName",
            "getNetworkOperatorName" to "getOperatorName",
        )

        val patchesByMethod = linkedMapOf<Method, ArrayDeque<Pair<Int, String>>>()
        SimIdentityReadFingerprint.matchAllOrNull().orEmpty().forEach { match ->
            if (match.originalClassDef.type.startsWith("Lapp/morphe/extension/")) return@forEach
            val method = match.originalMethod
            val implementation = method.implementation ?: return@forEach
                implementation.instructions.forEachIndexed { index, instruction ->
                    if (
                        instruction.opcode != Opcode.INVOKE_VIRTUAL &&
                        instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE
                    ) return@forEachIndexed

                    val methodReference = instruction.getReference<MethodReference>()
                        ?: return@forEachIndexed
                    val replacement = when {
                        methodReference.definingClass == "Landroid/telephony/TelephonyManager;" ->
                            replacements[methodReference.name]
                        methodReference.definingClass.startsWith("Landroid/telephony/CellIdentity") &&
                            methodReference.name == "getMccString" -> "getMcc"
                        methodReference.definingClass.startsWith("Landroid/telephony/CellIdentity") &&
                            methodReference.name == "getMncString" -> "getMnc"
                        else -> null
                    }
                    if (replacement == null || methodReference.returnType != "Ljava/lang/String;") {
                        return@forEachIndexed
                    }
                    if (implementation.instructions.elementAtOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT) {
                        patchesByMethod.getOrPut(method) { ArrayDeque() }.add(index to replacement)
                    }
                }
        }

        patchesByMethod.forEach { (method, patches) ->
            val mutableMethod = mutableClassDefBy(method.definingClass).findMutableMethodOf(method)
            while (patches.isNotEmpty()) {
                val (index, replacement) = patches.removeLast()
                val resultRegister = mutableMethod.getInstruction<OneRegisterInstruction>(index + 1).registerA

                mutableMethod.addInstructions(
                    index + 2,
                    """
                        invoke-static/range { v$resultRegister .. v$resultRegister }, $EXTENSION_CLASS_DESCRIPTOR->$replacement(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$resultRegister
                    """,
                )
            }
        }

        // TikTok's startup SIM check reports a change to its own telemetry: which way the SIM
        // went, how long since the last check, how many SIMs the phone has, and the country and
        // carrier before and after. What decides that something changed is the subscription id,
        // an int the spoof does not touch, so the event fires on a real SIM swap or a change of
        // which SIM carries data. The country and carrier in it read as the preset, but the SIM
        // count does not: it comes from SubscriptionManager rather than TelephonyManager, and
        // the event still says the hardware moved. Nothing else in the app reads what the task
        // stores, so with the preset on it does not run.
        CheckSimChangeTaskFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->shouldSkipSimChangeReport()Z
                    move-result v0
                    if-eqz v0, :run_sim_check
                    return-void
                """,
                ExternalLabel("run_sim_check", getInstruction(0)),
            )
        }

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableSimSpoof()V",
        )
    }
}
