package app.morphe.patches.tiktok.misc.translation

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.util.getReference
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/translation/CommentBatchTranslator;"
private const val FILTER_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/translation/TranslationFilter;"

@Suppress("unused")
val commentTranslationPatch = bytecodePatch(
    name = "Translate comments",
    description = "Adds comment translation controls using TikTok's translation system, with selectable language exclusions.",
    default = true,
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok4623())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableCommentTranslation()V",
        )

        BaseCommentCellBindFingerprint.method.apply {
            val instructions = implementation!!.instructions
            val managerMatch = instructions.withIndex().mapNotNull { (index, instruction) ->
                val field = instruction.getReference<FieldReference>()
                    ?: return@mapNotNull null
                if (instruction.opcode != Opcode.IPUT_OBJECT ||
                    field.type != "Lcom/ss/android/ugc/aweme/comment/model/Comment;" ||
                    instruction !is TwoRegisterInstruction
                ) {
                    return@mapNotNull null
                }

                val managerRegister = instruction.registerB
                var matchingWrites = 0
                var lastWriteIndex = index
                val searchEnd = (index + 6).coerceAtMost(instructions.lastIndex)
                for (candidateIndex in (index + 1)..searchEnd) {
                    val candidate = instructions[candidateIndex]
                    val candidateField = candidate.getReference<FieldReference>()
                    if (candidate.opcode == Opcode.IPUT_OBJECT &&
                        candidate is TwoRegisterInstruction &&
                        candidate.registerB == managerRegister &&
                        candidateField?.definingClass == field.definingClass
                    ) {
                        matchingWrites++
                        lastWriteIndex = candidateIndex
                    }
                }

                if (matchingWrites >= 2) lastWriteIndex to managerRegister else null
            }.lastOrNull() ?: throw PatchException(
                "Translate comments: could not locate initialized native comment translation manager.",
            )
            val (managerReadyIndex, managerRegister) = managerMatch

            addInstructions(
                managerReadyIndex + 1,
                """
                    move-object/from16 v0, p0
                    iget-object v0, v0, Landroidx/recyclerview/widget/RecyclerView${'$'}ViewHolder;->itemView:Landroid/view/View;
                    invoke-static {v0, v$managerRegister}, $EXTENSION_CLASS_DESCRIPTOR->registerCommentCell(Landroid/view/View;Ljava/lang/Object;)V
                """,
            )
        }

        CommentListLoadedFingerprint.method.apply {
            val responseReadyIndex = implementation!!.instructions.withIndex()
                .firstOrNull { (_, instruction) ->
                    instruction.getReference<FieldReference>()?.let { reference ->
                        reference.definingClass == "Lcom/ss/android/ugc/aweme/comment/model/CommentItemList;" &&
                            reference.name == "lazySplitItemsParseTask"
                    } == true
                }?.index ?: throw PatchException(
                "Translate comments: could not locate loaded comment list response.",
            )

            addInstruction(
                responseReadyIndex,
                "invoke-static {v0}, $EXTENSION_CLASS_DESCRIPTOR->onCommentListLoaded(Ljava/lang/Object;)V",
            )
        }

        MultiCommentTranslationStartFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range {v16 .. v18}, $EXTENSION_CLASS_DESCRIPTOR->onNativeBatchStart(Ljava/lang/Object;Ljava/lang/Object;Z)V
            """,
        )

        MultiCommentTranslationCompleteFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p0}, $EXTENSION_CLASS_DESCRIPTOR->onNativeBatchComplete(Ljava/lang/Object;)V
            """,
        )

        TranslationServiceCommentLanguageAllowedFingerprint.methodOrNull?.addInstructions(
            0,
            """
                invoke-static {p1}, $FILTER_CLASS_DESCRIPTOR->shouldSkipCommentTranslation(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :skip_comment_lang
                const/4 v0, 0x0
                return v0
                :skip_comment_lang
            """,
        )

        TranslationServiceCommentTranslatableFingerprint.methodOrNull?.addInstructions(
            0,
            """
                invoke-static {p1}, $FILTER_CLASS_DESCRIPTOR->shouldSkipCommentTranslation(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :skip_comment_trans
                const/4 v0, 0x0
                return v0
                :skip_comment_trans
            """,
        )

        TranslationServiceAwemeTranslationRequestFingerprint.methodOrNull?.addInstructions(
            0,
            """
                invoke-static {p2, p3, p4}, $FILTER_CLASS_DESCRIPTOR->shouldSkipAwemeTranslation(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :skip_aweme_trans
                const/4 v0, 0x0
                return-object v0
                :skip_aweme_trans
            """,
        )
    }
}
