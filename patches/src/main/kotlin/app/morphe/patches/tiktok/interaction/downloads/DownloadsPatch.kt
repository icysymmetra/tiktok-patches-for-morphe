/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/interaction/downloads/DownloadsPatch.kt
 */
package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.getReference
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/download/DownloadsPatch;"
private const val STICKER_EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/download/StickerGallerySaver;"
private const val FILENAME_FORMATTER_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/download/DownloadFilenameFormatter;"

@Suppress("unused")
val downloadsPatch = bytecodePatch(
    name = "Downloads",
    description =
        "Adds watermark-free downloads, video quality selection, comment sticker saving, " +
            "configurable folders, and filename templates.",
    default = true,
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok4623())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableDownload()V",
        )

        AclCommonShareFingerprint.method.returnEarly(0)
        AclCommonShare2Fingerprint.method.returnEarly(2)

        // Download videos without watermark.
        AclCommonShare3Fingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->shouldRemoveWatermark()Z
                move-result v0
                if-eqz v0, :noremovewatermark
                const/4 v0, 0x1
                return v0
                :noremovewatermark
                nop
            """,
        )

        AwemeGetVideoFingerprint.method.apply {
            val returnIndex = findInstructionIndicesReversedOrThrow { opcode == Opcode.RETURN_OBJECT }.first()
            val register = getInstruction<OneRegisterInstruction>(returnIndex).registerA

            addInstructions(
                returnIndex,
                """
                    invoke-static {v$register}, $EXTENSION_CLASS_DESCRIPTOR->patchVideoObject(Lcom/ss/android/ugc/aweme/feed/model/Video;)V
                """,
            )
        }

        // Download images without TikTok's drawn watermark.
        CommentImageWatermarkFingerprint.method.apply {
            val drawBitmapIndex = findInstructionIndicesReversedOrThrow {
                opcode.name == "invoke-virtual" &&
                    this is ReferenceInstruction &&
                    reference.toString().contains("->drawBitmap(Landroid/graphics/Bitmap;FFLandroid/graphics/Paint;)V")
            }.first()

            val drawInstr = getInstruction<FiveRegisterInstruction>(drawBitmapIndex)
            val canvasReg = drawInstr.registerC
            val bitmapReg = drawInstr.registerD
            val xReg = drawInstr.registerE
            val yReg = drawInstr.registerF
            val paintReg = drawInstr.registerG

            removeInstructions(drawBitmapIndex, 1)

            addInstructionsWithLabels(
                drawBitmapIndex,
                """
                    invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->shouldRemoveWatermark()Z
                    move-result v$xReg

                    if-nez v$xReg, :skip_watermark

                    const/4 v$xReg, 0x0
                    invoke-virtual {v$canvasReg, v$bitmapReg, v$xReg, v$yReg, v$paintReg}, Landroid/graphics/Canvas;->drawBitmap(Landroid/graphics/Bitmap;FFLandroid/graphics/Paint;)V

                    :skip_watermark
                    nop
                """,
            )
        }

        // Add local gallery saving to the comment sticker/image preview sheet.
        StickerPreviewBinderFingerprint.method.apply {
            val returnIndex = findInstructionIndicesReversedOrThrow { opcode == Opcode.RETURN_VOID }.first()
            addInstructions(
                returnIndex,
                """
                    invoke-static/range {p0 .. p1}, $STICKER_EXTENSION_CLASS_DESCRIPTOR->attachSaveImageButton(Landroid/view/View;Ljava/lang/Object;)V
                """,
            )
        }

        // Preserve the full StickerItem behind TikTok's reduced preview model for media detection.
        StickerPreviewSourceFingerprint.method.apply {
            val bindCallIndices = implementation!!.instructions.withIndex()
                .filter { (_, instruction) ->
                    instruction.getReference<MethodReference>()?.let { reference ->
                        reference.definingClass == "LX/0ULN;" &&
                            reference.name == "LIZ" &&
                            reference.parameterTypes.firstOrNull() == "LX/0ULM;"
                    } == true
                }
                .map { it.index }
                .toList()

            if (bindCallIndices.isEmpty()) {
                throw app.morphe.patcher.patch.PatchException(
                    "Downloads: could not find 46.2.3 sticker preview bind calls.",
                )
            }

            bindCallIndices.asReversed().forEach { bindCallIndex ->
                val bindInstruction = implementation!!.instructions[bindCallIndex]
                val previewRegister = when (bindInstruction) {
                    is FiveRegisterInstruction -> bindInstruction.registerD
                    is RegisterRangeInstruction -> bindInstruction.startRegister + 1
                    else -> throw app.morphe.patcher.patch.PatchException(
                        "Downloads: unsupported sticker preview bind instruction.",
                    )
                }
                val registerProvider = getFreeRegisterProvider(bindCallIndex, 2, previewRegister)
                val previewTempRegister = registerProvider.getFreeRegister()
                val sourceTempRegister = registerProvider.getFreeRegister()

                if (previewTempRegister > 15 || sourceTempRegister > 15) {
                    throw app.morphe.patcher.patch.PatchException(
                        "Downloads: could not allocate low registers for sticker source association.",
                    )
                }

                addInstructions(
                    bindCallIndex,
                    """
                        move-object/from16 v$previewTempRegister, v$previewRegister
                        move-object/from16 v$sourceTempRegister, p2
                        invoke-static {v$previewTempRegister, v$sourceTempRegister}, $STICKER_EXTENSION_CLASS_DESCRIPTOR->registerStickerSource(Ljava/lang/Object;Ljava/lang/Object;)V
                    """,
                )
            }
        }

        // Prepare the public filename without renaming TikTok's private staging file.
        DownloadSuccessCoroutineFingerprint.method.apply {
            val fieldReferences = implementation!!.instructions.mapNotNull {
                it.getReference<FieldReference>()
            }
            val pathField = fieldReferences.first {
                it.definingClass == definingClass && it.type == "Ljava/lang/String;"
            }
            val awemeField = fieldReferences.first {
                it.definingClass == definingClass &&
                    it.type == "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
            }

            addInstructions(
                0,
                """
                    iget-object v0, p0, $pathField
                    iget-object v1, p0, $awemeField
                    invoke-static {v0, v1}, $FILENAME_FORMATTER_CLASS_DESCRIPTOR->registerDownloadedMediaName(Ljava/lang/String;Ljava/lang/Object;)V
                """,
            )
        }

        // Change the download path.
        VideoDownloadUriFingerprint.method.apply {
            addInstructions(
                0,
                """
                    invoke-static/range {p1 .. p1}, $FILENAME_FORMATTER_CLASS_DESCRIPTOR->consumeDestinationName(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object p1
                """,
            )
            findInstructionIndicesReversedOrThrow {
                getReference<FieldReference>().let { ref ->
                    ref?.definingClass == "Landroid/os/Environment;" && ref.name.startsWith("DIRECTORY_")
                }
            }.forEach { fieldIndex ->
                val pathRegister = getInstruction<OneRegisterInstruction>(fieldIndex).registerA
                val builderRegister = getInstruction<FiveRegisterInstruction>(fieldIndex + 1).registerC

                // Remove 'field load â†’ append â†’ "/Camera/" â†’ append' block.
                removeInstructions(fieldIndex, 4)

                addInstructions(
                    fieldIndex,
                    """
                        invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->getVideoDownloadPath()Ljava/lang/String;
                        move-result-object v$pathRegister
                        invoke-virtual { v$builderRegister, v$pathRegister }, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                    """,
                )
            }
        }

        PhotoDownloadUriFingerprint.method.apply {
            addInstructions(
                0,
                """
                    invoke-static/range {p1 .. p1}, $FILENAME_FORMATTER_CLASS_DESCRIPTOR->consumeDestinationName(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object p1
                """,
            )
            findInstructionIndicesReversedOrThrow {
                getReference<FieldReference>().let { ref ->
                    ref?.definingClass == "Landroid/os/Environment;" && ref.name.startsWith("DIRECTORY_")
                }
            }.forEach { fieldIndex ->
                val pathRegister = getInstruction<OneRegisterInstruction>(fieldIndex).registerA
                val builderRegister = getInstruction<FiveRegisterInstruction>(fieldIndex + 1).registerC
                removeInstructions(fieldIndex, 4)
                addInstructions(
                    fieldIndex,
                    """
                        invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->getPhotoDownloadPath()Ljava/lang/String;
                        move-result-object v$pathRegister
                        invoke-virtual { v$builderRegister, v$pathRegister }, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                    """,
                )
            }
        }

        VideoLookupUriFingerprint.method.apply {
            addInstructions(
                0,
                """
                    invoke-static/range {p1 .. p1}, $FILENAME_FORMATTER_CLASS_DESCRIPTOR->resolveDestinationName(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object p1
                """,
            )
            findInstructionIndicesReversedOrThrow {
                getReference<FieldReference>().let { ref ->
                    ref?.definingClass == "Landroid/os/Environment;" && ref.name.startsWith("DIRECTORY_")
                }
            }.forEach { fieldIndex ->
                val pathRegister = getInstruction<OneRegisterInstruction>(fieldIndex).registerA
                val builderRegister = getInstruction<FiveRegisterInstruction>(fieldIndex + 1).registerC
                removeInstructions(fieldIndex, 4)
                addInstructions(
                    fieldIndex,
                    """
                        invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->getVideoDownloadPath()Ljava/lang/String;
                        move-result-object v$pathRegister
                        invoke-virtual { v$builderRegister, v$pathRegister }, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                    """,
                )
            }
            val collectionIndex = findInstructionIndicesReversedOrThrow {
                getReference<MethodReference>()?.let { reference ->
                    reference.definingClass == "Landroid/provider/MediaStore\$Video\$Media;" && reference.name == "getContentUri"
                } == true
            }.first()
            replaceInstruction(collectionIndex, "invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->getVideoCollectionUri()Landroid/net/Uri;")
        }

        PhotoLookupUriFingerprint.method.apply {
            addInstructions(
                0,
                """
                    invoke-static/range {p1 .. p1}, $FILENAME_FORMATTER_CLASS_DESCRIPTOR->resolveDestinationName(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object p1
                """,
            )
            findInstructionIndicesReversedOrThrow {
                getReference<FieldReference>().let { ref ->
                    ref?.definingClass == "Landroid/os/Environment;" && ref.name.startsWith("DIRECTORY_")
                }
            }.forEach { fieldIndex ->
                val pathRegister = getInstruction<OneRegisterInstruction>(fieldIndex).registerA
                val builderRegister = getInstruction<FiveRegisterInstruction>(fieldIndex + 1).registerC
                removeInstructions(fieldIndex, 4)
                addInstructions(
                    fieldIndex,
                    """
                        invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->getPhotoDownloadPath()Ljava/lang/String;
                        move-result-object v$pathRegister
                        invoke-virtual { v$builderRegister, v$pathRegister }, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                    """,
                )
            }
            val collectionIndex = findInstructionIndicesReversedOrThrow {
                getReference<MethodReference>()?.let { reference ->
                    reference.definingClass == "Landroid/provider/MediaStore\$Images\$Media;" && reference.name == "getContentUri"
                } == true
            }.first()
            replaceInstruction(collectionIndex, "invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->getPhotoCollectionUri()Landroid/net/Uri;")
        }

        VideoMediaStoreInsertFingerprint.method.apply {
            val collectionIndex = findInstructionIndicesReversedOrThrow {
                getReference<MethodReference>()?.let { reference ->
                    reference.definingClass == "Landroid/provider/MediaStore\$Video\$Media;" && reference.name == "getContentUri"
                } == true
            }.first()
            replaceInstruction(collectionIndex, "invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->getVideoCollectionUri()Landroid/net/Uri;")
        }

        PhotoMediaStoreInsertFingerprint.method.apply {
            val collectionIndex = findInstructionIndicesReversedOrThrow {
                getReference<MethodReference>()?.let { reference ->
                    reference.definingClass == "Landroid/provider/MediaStore\$Images\$Media;" && reference.name == "getContentUri"
                } == true
            }.first()
            replaceInstruction(collectionIndex, "invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->getPhotoCollectionUri()Landroid/net/Uri;")
        }

        // Image posts use a direct media-copy helper instead of the ordinary photo wrapper.
        ImagePostMediaCopyFingerprint.method.apply {
            addInstructions(
                0,
                """
                    invoke-static/range {p2 .. p2}, $FILENAME_FORMATTER_CLASS_DESCRIPTOR->consumeDestinationName(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object p2
                """,
            )
            findInstructionIndicesReversedOrThrow {
                getReference<FieldReference>()?.let { reference ->
                    reference.definingClass == "Landroid/os/Environment;" && reference.name == "DIRECTORY_DCIM"
                } == true
            }.forEach { fieldIndex ->
                val pathRegister = getInstruction<OneRegisterInstruction>(fieldIndex).registerA
                val builderRegister = getInstruction<FiveRegisterInstruction>(fieldIndex + 1).registerC
                removeInstructions(fieldIndex, 4)
                addInstructions(
                    fieldIndex,
                    """
                        invoke-static/range {p3 .. p3}, $EXTENSION_CLASS_DESCRIPTOR->getMediaDownloadPath(Z)Ljava/lang/String;
                        move-result-object v$pathRegister
                        invoke-virtual {v$builderRegister, v$pathRegister}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                    """,
                )
            }
        }
    }
}

