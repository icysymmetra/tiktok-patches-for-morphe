/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.resume

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/interaction/ResumeVideoAfterScrollPatch;"

@Suppress("unused")
val resumeVideoAfterScrollPatch = bytecodePatch(
    name = "Resume videos after scrolling",
    description = "Continues supported videos from where playback stopped when returning after a scroll.",
    default = true,
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok4714())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableResumeVideoAfterScroll()V",
        )

        FeedProgressContinueGateFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $EXTENSION_DESCRIPTOR->shouldResumeVideoAfterScroll()Z
                    move-result v0
                    if-eqz v0, :continue_gate
                    const/4 v0, 0x1
                    invoke-static {v0}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
                    move-result-object v0
                    return-object v0
                """,
                ExternalLabel("continue_gate", getInstruction(0)),
            )
        }

        // Resolve the play-progress holder (X.0Lze in TikTok 46.2.3, X.08Vu in 47.1.4) from the progress
        // callback: its last LruCache.put is fed by the holder's lazily created cache and stores the holder's
        // current progress record; the holder's String lookup method reveals the cached aid field.
        val progressMethod = FeedPlayProgressFingerprint.method
        val cachePutIndex = progressMethod.indexOfFirstInstructionReversedOrThrow {
            val reference = getReference<MethodReference>()
            reference?.definingClass == "Landroid/util/LruCache;" &&
                reference.name == "put" &&
                reference.parameterTypes.size == 2
        }
        val cachePut = progressMethod.getInstruction<FiveRegisterInstruction>(cachePutIndex)
        val progressInstructions = progressMethod.implementation!!.instructions
        val progressRecordField = (cachePutIndex - 1 downTo 0).firstNotNullOfOrNull { index ->
            progressInstructions[index].takeIf { it.opcode == Opcode.SPUT_OBJECT }?.getReference<FieldReference>()
        } ?: throw PatchException("Resume videos after scrolling: progress record field was not resolved.")
        val holderType = progressRecordField.definingClass
        val cacheLazyField = (cachePutIndex - 1 downTo 0).firstNotNullOfOrNull { index ->
            progressInstructions[index].takeIf { it.opcode == Opcode.SGET_OBJECT }?.getReference<FieldReference>()
                ?.takeIf { it.definingClass == holderType && it.name != progressRecordField.name }
        } ?: throw PatchException("Resume videos after scrolling: progress cache field was not resolved.")
        val lazyType = cacheLazyField.type
        val progressLookup = classDefBy(holderType).methods.firstOrNull { method ->
            method.parameterTypes == listOf("Ljava/lang/String;") && method.returnType == progressRecordField.type
        } ?: throw PatchException("Resume videos after scrolling: progress lookup was not resolved.")
        val progressAidField = progressLookup.implementation?.instructions?.firstNotNullOfOrNull { instruction ->
            instruction.takeIf { it.opcode == Opcode.SGET_OBJECT }?.getReference<FieldReference>()
                ?.takeIf { it.definingClass == holderType && it.type == "Ljava/lang/String;" }
        } ?: throw PatchException("Resume videos after scrolling: cached aid field was not resolved.")

        FeedPlayCompletedFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $EXTENSION_DESCRIPTOR->shouldResumeVideoAfterScroll()Z
                    move-result v0
                    if-eqz v0, :continue_completion
                    sget-object v0, $cacheLazyField
                    invoke-interface {v0}, $lazyType->getValue()Ljava/lang/Object;
                    move-result-object v0
                    check-cast v0, Landroid/util/LruCache;
                    move-object/from16 v1, p1
                    invoke-virtual {v0, v1}, Landroid/util/LruCache;->remove(Ljava/lang/Object;)Ljava/lang/Object;
                    const/4 v0, 0x0
                    sput-object v0, $progressRecordField
                    sput-object v0, $progressAidField
                """,
                ExternalLabel("continue_completion", getInstruction(0)),
            )
        }

        progressMethod.apply {
            val continueInstruction = getInstruction(cachePutIndex + 1)
            val cacheRegister = cachePut.registerC
            val keyRegister = cachePut.registerD
            val scratchRegister = cachePut.registerE

            addInstructionsWithLabels(
                cachePutIndex + 1,
                """
                    invoke-static/range {p2 .. p5}, $EXTENSION_DESCRIPTOR->shouldClearCompletedProgress(JJ)Z
                    move-result v$scratchRegister
                    if-eqz v$scratchRegister, :continue_progress
                    invoke-virtual {v$cacheRegister, v$keyRegister}, Landroid/util/LruCache;->remove(Ljava/lang/Object;)Ljava/lang/Object;
                    const/4 v$scratchRegister, 0x0
                    sput-object v$scratchRegister, $progressRecordField
                    sput-object v$scratchRegister, $progressAidField
                """,
                ExternalLabel("continue_progress", continueInstruction),
            )
        }
    }
}
