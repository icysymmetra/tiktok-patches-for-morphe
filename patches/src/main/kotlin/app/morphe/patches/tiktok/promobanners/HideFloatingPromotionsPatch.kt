/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.promobanners

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.moveResultRegisterAfter
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val FEATURE_CONTROLS_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;"
private const val PROFILE_ACTIVITY_ICON_DESCRIPTOR =
    "Lcom/bytedance/touchpoint/api/model/ProfileActivityIcon;"
private const val TUX_ICON_VIEW_DESCRIPTOR = "Lcom/bytedance/tux/icon/TuxIconView;"

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

/** The 47.0.3 binder that creates the purple rewards shortcut beside Add friends. */
internal fun isProfileRewardsIconBinder(method: com.android.tools.smali.dexlib2.iface.Method): Boolean {
    val instructions = method.implementation?.instructions ?: return false
    val hasProfileModel = instructions.any {
        it.getReference<FieldReference>()?.definingClass == PROFILE_ACTIVITY_ICON_DESCRIPTOR
    }
    val hasIconView = instructions.any {
        it.opcode == Opcode.CHECK_CAST &&
            it.getReference<TypeReference>()?.type == TUX_ICON_VIEW_DESCRIPTOR
    }
    val assignsViewId = instructions.any {
        it.getReference<MethodReference>()?.let { call ->
            call.name == "setId" && call.definingClass == "Landroid/view/View;" && call.returnType == "V" &&
                call.parameterTypes.singleOrNull() == "I"
        } == true
    }
    return method.returnType == "Ljava/lang/Object;" && hasProfileModel && hasIconView && assignsViewId
}

internal object ProfileRewardsIconBinderFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    // The filter repeats a reference the custom block requires, so the patcher reads only the
    // classes that make it (#54).
    filters = listOf(fieldAccess(definingClass = PROFILE_ACTIVITY_ICON_DESCRIPTOR)),
    custom = { method, _ -> isProfileRewardsIconBinder(method) },
)

@Suppress("unused")
val hideFloatingPromotionsPatch = bytecodePatch(
    name = "Hide floating promotions",
    description = "Hides the floating promotion badges, coins and timers on the feed, and can hide the rewards button on your profile. Starts off. Turn it on in Metra settings > Interface, and App behavior for the rewards button.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

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
            val resultRegister = method.moveResultRegisterAfter(parseIndex, "Hide floating promotions")
            method.addInstructions(
                parseIndex + 2,
                """
                    invoke-static/range {v$resultRegister .. v$resultRegister}, $FEATURE_CONTROLS_CLASS_DESCRIPTOR->filterNormalPendant(Ljava/lang/Object;)Ljava/lang/Object;
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

        ProfileRewardsIconBinderFingerprint.method.let { method ->
            val iconCastIndex = method.indexOfFirstInstructionOrThrow {
                opcode == Opcode.CHECK_CAST &&
                    getReference<TypeReference>()?.type == TUX_ICON_VIEW_DESCRIPTOR
            }
            val iconRegister = method.getInstruction<OneRegisterInstruction>(iconCastIndex).registerA
            val returnIndex = method.indexOfFirstInstructionOrThrow {
                opcode == Opcode.RETURN_OBJECT
            }
            method.addInstruction(
                returnIndex,
                "invoke-static/range {v$iconRegister .. v$iconRegister}, " +
                    "$FEATURE_CONTROLS_CLASS_DESCRIPTOR->hideProfileRewardsShortcut(Landroid/view/View;)V",
            )
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
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range {v$register .. v$register}, $FEATURE_CONTROLS_CLASS_DESCRIPTOR->filterPromotionalTouchPoint(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v$register
                check-cast v$register, $returnType
            """,
        )
    }
}
