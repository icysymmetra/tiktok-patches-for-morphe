/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/misc/spoof/sim/SpoofSimPatch.kt
 */

package app.morphe.patches.tiktok.misc.spoof.sim

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/spoof/sim/SpoofSimPatch;"

@Suppress("unused")
val simSpoofPatch = bytecodePatch(
    name = "Region spoof",
    description = "Adds in-app controls for changing the region TikTok reads.",
    default = true,
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(*AppCompatibilities.tiktok4623())

    execute {
        val replacements = mapOf(
            "getSimCountryIso" to "getCountryIso",
            "getNetworkCountryIso" to "getCountryIso",
            "getSimOperator" to "getOperator",
            "getNetworkOperator" to "getOperator",
            "getSimOperatorName" to "getOperatorName",
            "getNetworkOperatorName" to "getOperatorName",
        )

        val patchesByMethod = linkedMapOf<Method, ArrayDeque<Pair<Int, String>>>()
        classDefForEach { classDef ->
            for (method in classDef.methods) {
                val implementation = method.implementation ?: continue
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
                        invoke-static { v$resultRegister }, $EXTENSION_CLASS_DESCRIPTOR->$replacement(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$resultRegister
                    """,
                )
            }
        }

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableSimSpoof()V",
        )
    }
}
