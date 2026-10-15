/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.promobanners

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val FEATURE_CONTROLS_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;"

private object TouchPointPendantParserFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/util/HashMap;", "Ljava/util/List;", "Z"),
    custom = { method, _ ->
        val referencedTypes = method.implementation?.instructions
            ?.mapNotNull { it.getReference<TypeReference>()?.type }
            ?.toSet()
            ?: emptySet()
        "Lcom/bytedance/touchpoint/api/model/NormalPendant;" in referencedTypes &&
            "Lcom/bytedance/touchpoint/data/parser/notify/PendantViewModel;" in referencedTypes
    },
)

@Suppress("unused")
val hideFloatingPromotionsPatch = bytecodePatch(
    name = "Hide floating promotions",
    description = "Removes floating promotional badges, coin icons, and timer banners from the Home feed.",
    default = true,
) {
    dependsOn(sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok4714())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enablePromotionalBanners()V",
        )

        TouchPointPendantParserFingerprint.method.let { method ->
            val parseIndex = method.indexOfFirstInstructionOrThrow {
                getReference<MethodReference>()?.let { reference ->
                    reference.parameterTypes == listOf("Ljava/lang/String;", "Ljava/lang/Class;") &&
                        reference.returnType == "Ljava/lang/Object;"
                } == true
            }
            val resultRegister = method.getInstruction<OneRegisterInstruction>(parseIndex + 1).registerA
            method.addInstructions(
                parseIndex + 2,
                """
                    invoke-static {v$resultRegister}, $FEATURE_CONTROLS_CLASS_DESCRIPTOR->filterNormalPendant(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$resultRegister
                """,
            )

            val managerTypes = method.implementation!!.instructions
                .mapNotNull { it.getReference<FieldReference>() }
                .filter { it.type == "Ljava/util/HashMap;" }
                .map { it.definingClass }
                .toSet()
            val managerType = method.implementation!!.instructions
                .mapNotNull { it.getReference<MethodReference>() }
                .firstOrNull { reference ->
                    reference.parameterTypes.isEmpty() && reference.returnType in managerTypes
                }
                ?.returnType
                ?: throw PatchException("TouchPoint promotional manager reference not found")
            val manager = mutableClassDefBy(managerType)
            val modelGetter = manager.methods.singleOrNull { candidate ->
                candidate.parameterTypes == listOf("I") &&
                    candidate.returnType.startsWith("L") &&
                    !candidate.returnType.startsWith("Ljava/")
            } ?: throw PatchException("TouchPoint promotional model getter not found")

            val launchPlanGetter = manager.methods.singleOrNull { candidate ->
                candidate.parameterTypes == listOf("I", "I") &&
                    candidate.returnType == modelGetter.returnType
            } ?: throw PatchException("TouchPoint promotional launch-plan getter not found")

            modelGetter.filterPromotionalTouchPoint()
            launchPlanGetter.filterPromotionalTouchPoint()
        }
    }
}

private fun MutableMethod.filterPromotionalTouchPoint() {
    val returnIndices = implementation?.instructions
        ?.withIndex()
        ?.filter { (_, instruction) -> instruction.opcode == Opcode.RETURN_OBJECT }
        ?.map { (index, instruction) ->
            index to (instruction as OneRegisterInstruction).registerA
        }
        ?.toList()
        ?: throw PatchException("TouchPoint promotional getter has no implementation")

    returnIndices.asReversed().forEach { (index, register) ->
        addInstructions(
            index,
            """
                invoke-static {v$register}, $FEATURE_CONTROLS_CLASS_DESCRIPTOR->filterPromotionalTouchPoint(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v$register
                check-cast v$register, $returnType
            """,
        )
    }
}
