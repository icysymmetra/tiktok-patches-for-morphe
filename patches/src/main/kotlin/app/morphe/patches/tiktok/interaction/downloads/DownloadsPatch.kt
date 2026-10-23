/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/interaction/downloads/DownloadsPatch.kt
 */
package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFreeRegister
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getFreeRegisterProvider
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.numberOfParameterRegisters
import app.morphe.patcher.util.numberOfParameterRegistersLogical
import app.morphe.patcher.util.p0Register
import app.morphe.util.cloneMutable
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/download/DownloadsPatch;"
private const val STICKER_EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/download/StickerGallerySaver;"
private const val FILENAME_FORMATTER_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/download/DownloadFilenameFormatter;"
private const val COMMENT_LIVE_PHOTO_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/download/CommentLivePhotoSaver;"
internal const val COMMENT_MODEL_DESCRIPTOR = "Lcom/ss/android/ugc/aweme/comment/model/Comment;"

/** Each invocation captures its folder before consuming the staging name, without shared state. */
context(patchContext: BytecodePatchContext)
internal fun MutableMethod.withDownloadDestination(
    nameParameter: Int,
    pathGetter: String,
    consume: Boolean,
): MutableMethod {
    val cloned = captureDownloadDestination(nameParameter, pathGetter, consume)
    patchContext.mutableClassDefBy(definingClass).methods.apply {
        remove(this@withDownloadDestination)
        add(cloned)
    }
    return cloned
}

internal fun MutableMethod.captureDownloadDestination(
    nameParameter: Int,
    pathGetter: String,
    consume: Boolean,
): MutableMethod {
    val nameRegister = p0Register + nameParameter
    val insertion = numberOfParameterRegistersLogical
    val cloned = cloneMutable(additionalRegisters = numberOfParameterRegisters)
    val pathRegister = cloned.p0Register + nameParameter
    if (pathRegister > 255) throw PatchException("Downloads: destination register exceeds move-result range")
    val dynamicKind = pathGetter == "getMediaDestination"
    val lastParameter = if (dynamicKind) nameParameter + 1 else nameParameter
    val signature = if (dynamicKind) "Ljava/lang/String;Z" else "Ljava/lang/String;"
    val nameGetter = if (consume) "consumeDestinationName" else "resolveDestinationName"
    cloned.addInstructions(insertion, """
        invoke-static/range {p$nameParameter .. p$lastParameter}, $FILENAME_FORMATTER_CLASS_DESCRIPTOR->$pathGetter($signature)Ljava/lang/String;
        move-result-object p$nameParameter
        invoke-static/range {v$nameRegister .. v$nameRegister}, $FILENAME_FORMATTER_CLASS_DESCRIPTOR->$nameGetter(Ljava/lang/String;)Ljava/lang/String;
        move-result-object v$nameRegister
    """)
    return cloned
}

/**
 * Hands the finished download's path and video to the filename formatter, at index 0.
 *
 * <p>The block writes v0 and v1, so the frame needs two locals or the writes land on the
 * parameters. iget-object is format 22c, whose register fields are four bits wide. In a frame
 * with more than fifteen locals p0 sits above v15 and cannot be named there, so it is copied
 * into v1 first (move-object/from16 reaches any register) and both reads go through the copy.
 * The second read overwrites v1 after the first has already used it. No local is live at
 * index 0, so the copy costs the host nothing. A reporter's 46.8.3 had eighteen locals here
 * and the patch used to refuse it (issue #9).
 */
internal fun MutableMethod.injectDownloadNameHook(pathField: FieldReference, awemeField: FieldReference) {
    requireLocals("Downloads", 2)
    val locals = implementation!!.registerCount - numberOfParameterRegisters
    val receiver = if (locals <= 15) "p0" else "v1"
    val copy = if (receiver == "p0") "" else "move-object/from16 v1, p0"
    addInstructions(
        0,
        """
            $copy
            iget-object v0, $receiver, $pathField
            iget-object v1, $receiver, $awemeField
            invoke-static {v0, v1}, $FILENAME_FORMATTER_CLASS_DESCRIPTOR->registerDownloadedMediaName(Ljava/lang/String;Ljava/lang/Object;)V
        """,
    )
}

/**
 * Checks the four instructions the download path redirect is about to delete.
 *
 * <p>They are the DCIM directory name, its append onto the path builder, the "/Camera/" literal
 * and its append. Only the first two were ever looked at, so a build that lays this run out
 * differently would have had four unrelated instructions removed and the patch would have said
 * nothing about it.
 */
private fun MutableMethod.checkCameraPathRun(fieldIndex: Int) {
    val instructions = implementation!!.instructions.toList()

    fun at(offset: Int) = instructions.getOrNull(fieldIndex + offset) ?: throw PatchException(
        "Downloads: $name ends before the download path run it was going to replace.",
    )

    fun isAppend(offset: Int) = at(offset).opcode == Opcode.INVOKE_VIRTUAL &&
        at(offset).getReference<MethodReference>()?.let {
            it.definingClass == "Ljava/lang/StringBuilder;" && it.name == "append"
        } == true

    if (!isAppend(1) || !isAppend(3)) {
        throw PatchException(
            "Downloads: the download path run in $name is not a pair of StringBuilder appends. " +
                "Found ${at(1).opcode.name} and ${at(3).opcode.name} at $fieldIndex plus 1 and 3.",
        )
    }
    val literal = at(2).takeIf { it.opcode == Opcode.CONST_STRING }
        ?.getReference<StringReference>()?.string
    // Written "/Camera/" at four of the five sites and "/Camera" at the fifth.
    if (literal == null || !literal.trimEnd('/').endsWith("Camera")) {
        throw PatchException(
            "Downloads: the download path run in $name appends ${literal ?: at(2).opcode.name} " +
                "where the camera folder was expected.",
        )
    }
    val builder = (at(1) as FiveRegisterInstruction).registerC
    if ((at(3) as FiveRegisterInstruction).registerC != builder) {
        throw PatchException(
            "Downloads: the two appends in $name are on different builders, so this is not one path.",
        )
    }
}

@Suppress("unused")
val downloadsPatch = bytecodePatch(
    name = "Downloads",
    description = "Saves videos and photos without TikTok's watermark, even when the creator " +
        "turned downloads off. You can also choose the folders and file names. On by default. " +
        "Turn it off in Metra settings > Downloads.",
    default = true,
) {
    category("Downloads")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableDownload()V",
        )

        AclCommonShareFingerprint.method.returnEarly(0)
        AclCommonShare2Fingerprint.method.returnEarly(2)

        // Download videos without watermark.
        AclCommonShare3Fingerprint.method.requireLocals("Downloads", 1)
        AclCommonShare3Fingerprint.method.guardAtEntry(
            "Downloads",
            "invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->shouldRemoveWatermark()Z",
            """
                const/4 v0, 0x1
                return v0
            """,
        )

        AwemeGetVideoFingerprint.method.apply {
            // Every return, not only the last one written. The indices come back highest first,
            // so inserting at each in turn leaves the ones still to come where they were.
            findInstructionIndicesReversedOrThrow { opcode == Opcode.RETURN_OBJECT }.forEach { returnIndex ->
                val register = getInstruction<OneRegisterInstruction>(returnIndex).registerA

                // Range form: a return names its register in eight bits, and the plain invoke
                // can only name the first sixteen.
                addInstructionsAtControlFlowLabel(
                    returnIndex,
                    """
                        invoke-static/range {v$register .. v$register}, $EXTENSION_CLASS_DESCRIPTOR->patchVideoObject(Lcom/ss/android/ugc/aweme/feed/model/Video;)V
                    """,
                )
            }
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

            // The switch answer needs somewhere of its own to live. It used to be read into the
            // x register and x then written back as a literal zero, which is only the same
            // drawing because of what this build happens to do: on 46.2.3 the host translates
            // the canvas first and passes one register holding zero as both x and y
            // (const/4 v0 at 201, drawBitmap {v6, v3, v0, v0, v5} at 202 of LX/0owv;->LIZ).
            // A build that draws at a computed x would have had the watermark moved to the
            // left edge for everyone who keeps it, which is the default.
            val flagReg = findFreeRegister(
                drawBitmapIndex,
                canvasReg,
                bitmapReg,
                xReg,
                yReg,
                paintReg,
            )

            removeInstructions(drawBitmapIndex, 1)

            addInstructionsWithLabels(
                drawBitmapIndex,
                """
                    invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->shouldRemoveWatermark()Z
                    move-result v$flagReg

                    if-nez v$flagReg, :skip_watermark

                    invoke-virtual {v$canvasReg, v$bitmapReg, v$xReg, v$yReg, v$paintReg}, Landroid/graphics/Canvas;->drawBitmap(Landroid/graphics/Bitmap;FFLandroid/graphics/Paint;)V

                    :skip_watermark
                    nop
                """,
            )
        }

        // Add local gallery saving to the comment sticker/image preview sheet.
        resolveStickerPreviewBind().apply {
            // Every way out of the bind, not only the last one written. A build that returns
            // early on any path would have shown a sheet with no save button and said nothing.
            findInstructionIndicesReversedOrThrow { opcode == Opcode.RETURN_VOID }.forEach { returnIndex ->
                addInstructionsAtControlFlowLabel(
                    returnIndex,
                    """
                        invoke-static/range {p0 .. p1}, $STICKER_EXTENSION_CLASS_DESCRIPTOR->attachSaveImageButton(Landroid/view/View;Ljava/lang/Object;)V
                    """,
                )
            }
        }

        // The received DM sticker's exact preview tap. Its declared native service is empty;
        // the extension owns this small local preview and its Save action, never a send action.
        hookDmStickerPreview()

        // The clip of a live photo in a comment (upstream #169). TikTok's own Save photo hands its
        // downloader the still's UrlModel alone, so the clip in livePhotoInfoModel.urlList never
        // lands. Once the save routine has the comment and the tapped photo's index (the index
        // unboxed just before the comment is read), the extension fetches the clip beside the still.
        CommentPhotoSaveFingerprint.method.apply {
            val instructions = implementation!!.instructions.toList()
            val commentCall = instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_VIRTUAL &&
                    it.getReference<MethodReference>()?.returnType == COMMENT_MODEL_DESCRIPTOR
            }
            if (commentCall < 0 || instructions[commentCall + 1].opcode != Opcode.MOVE_RESULT_OBJECT) {
                throw PatchException("Downloads: the comment photo save no longer reads its comment where the clip hook sits")
            }
            val commentRegister = (instructions[commentCall + 1] as OneRegisterInstruction).registerA
            if (commentRegister > 15) {
                throw PatchException("Downloads: the comment photo save keeps its comment past v15")
            }
            // 47.0.3 unboxes the tapped photo's index just before the comment; 46.2.3 saved the
            // first photo with no index at all, and gets the one-argument call.
            val indexCall = instructions.subList(0, commentCall).indexOfLast {
                it.getReference<MethodReference>()?.let { reference ->
                    reference.definingClass == "Ljava/lang/Integer;" && reference.name == "intValue"
                } == true
            }
            val indexRegister = if (indexCall >= 0 && instructions[indexCall + 1].opcode == Opcode.MOVE_RESULT) {
                (instructions[indexCall + 1] as OneRegisterInstruction).registerA
            } else {
                null
            }
            if (indexRegister != null && indexRegister > 15) {
                throw PatchException("Downloads: the comment photo save keeps the photo's index past v15")
            }
            addInstruction(
                commentCall + 2,
                if (indexRegister == null) {
                    "invoke-static {v$commentRegister}, $COMMENT_LIVE_PHOTO_CLASS_DESCRIPTOR->saveClip(Ljava/lang/Object;)V"
                } else {
                    "invoke-static {v$commentRegister, v$indexRegister}, $COMMENT_LIVE_PHOTO_CLASS_DESCRIPTOR->saveClip(Ljava/lang/Object;I)V"
                },
            )
        }

        // Preserve the full StickerItem behind TikTok's reduced preview model for media detection.
        StickerPreviewSourceFingerprint.method.apply {
            val bindCallIndices = implementation!!.instructions.withIndex()
                .filter { (_, instruction) ->
                    instruction.getReference<MethodReference>()?.isStickerPreviewBind() == true
                }
                .map { it.index }
                .toList()

            if (bindCallIndices.isEmpty()) {
                throw app.morphe.patcher.patch.PatchException(
                    "Downloads: the sticker preview source method calls no preview bind.",
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
            val fieldReferences = implementation!!.instructions.map { it.getReference<FieldReference>() }
            val awemeIndex = fieldReferences.indexOfFirst {
                it?.definingClass == definingClass &&
                    it.type == "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
            }
            if (awemeIndex < 0) {
                throw PatchException("Downloads: $name never reads the video it downloaded.")
            }
            val awemeField = fieldReferences[awemeIndex]!!

            // The path is prepared before the video is used, so the one String field of this
            // class read before that point is it. Taking the first String field of any kind
            // happened to be the same field on 46.2.3 and said nothing about the next build;
            // this method also reads a second String field of its own, further down.
            val pathFields = fieldReferences.take(awemeIndex).filterNotNull()
                .filter { it.definingClass == definingClass && it.type == "Ljava/lang/String;" }
                .distinctBy { it.toString() }
            val pathField = pathFields.singleOrNull() ?: throw PatchException(
                "Downloads: $name reads ${pathFields.size} of its own String fields before the " +
                    "video, so which one holds the downloaded path is no longer obvious.",
            )

            injectDownloadNameHook(pathField, awemeField)
        }

        // Change the download path.
        VideoDownloadUriFingerprint.method.withDownloadDestination(1, "getVideoDestination", true).apply {
            findInstructionIndicesReversedOrThrow {
                getReference<FieldReference>().let { ref ->
                    ref?.definingClass == "Landroid/os/Environment;" && ref.name.startsWith("DIRECTORY_")
                }
            }.forEach { fieldIndex ->
                // Before the casts below, or a range form of the append fails as a
                // ClassCastException rather than with the message this exists to give.
                checkCameraPathRun(fieldIndex)
                val pathRegister = getInstruction<OneRegisterInstruction>(fieldIndex).registerA
                val builderRegister = getInstruction<FiveRegisterInstruction>(fieldIndex + 1).registerC

                // Remove 'field load, append, "/Camera/", append' block.
                removeInstructions(fieldIndex, 4)

                addInstructions(
                    fieldIndex,
                    """
                        move-object/from16 v$pathRegister, p1
                        invoke-virtual { v$builderRegister, v$pathRegister }, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                    """,
                )
            }
        }

        PhotoDownloadUriFingerprint.method.withDownloadDestination(1, "getPhotoDestination", true).apply {
            findInstructionIndicesReversedOrThrow {
                getReference<FieldReference>().let { ref ->
                    ref?.definingClass == "Landroid/os/Environment;" && ref.name.startsWith("DIRECTORY_")
                }
            }.forEach { fieldIndex ->
                checkCameraPathRun(fieldIndex)
                val pathRegister = getInstruction<OneRegisterInstruction>(fieldIndex).registerA
                val builderRegister = getInstruction<FiveRegisterInstruction>(fieldIndex + 1).registerC
                removeInstructions(fieldIndex, 4)
                addInstructions(
                    fieldIndex,
                    """
                        move-object/from16 v$pathRegister, p1
                        invoke-virtual { v$builderRegister, v$pathRegister }, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                    """,
                )
            }
        }

        VideoLookupUriFingerprint.method.withDownloadDestination(1, "getVideoDestination", false).apply {
            findInstructionIndicesReversedOrThrow {
                getReference<FieldReference>().let { ref ->
                    ref?.definingClass == "Landroid/os/Environment;" && ref.name.startsWith("DIRECTORY_")
                }
            }.forEach { fieldIndex ->
                checkCameraPathRun(fieldIndex)
                val pathRegister = getInstruction<OneRegisterInstruction>(fieldIndex).registerA
                val builderRegister = getInstruction<FiveRegisterInstruction>(fieldIndex + 1).registerC
                removeInstructions(fieldIndex, 4)
                addInstructions(
                    fieldIndex,
                    """
                        move-object/from16 v$pathRegister, p1
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

        PhotoLookupUriFingerprint.method.withDownloadDestination(1, "getPhotoDestination", false).apply {
            findInstructionIndicesReversedOrThrow {
                getReference<FieldReference>().let { ref ->
                    ref?.definingClass == "Landroid/os/Environment;" && ref.name.startsWith("DIRECTORY_")
                }
            }.forEach { fieldIndex ->
                checkCameraPathRun(fieldIndex)
                val pathRegister = getInstruction<OneRegisterInstruction>(fieldIndex).registerA
                val builderRegister = getInstruction<FiveRegisterInstruction>(fieldIndex + 1).registerC
                removeInstructions(fieldIndex, 4)
                addInstructions(
                    fieldIndex,
                    """
                        move-object/from16 v$pathRegister, p1
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
        ImagePostMediaCopyFingerprint.method.withDownloadDestination(2, "getMediaDestination", true).apply {
            findInstructionIndicesReversedOrThrow {
                getReference<FieldReference>()?.let { reference ->
                    reference.definingClass == "Landroid/os/Environment;" && reference.name == "DIRECTORY_DCIM"
                } == true
            }.forEach { fieldIndex ->
                checkCameraPathRun(fieldIndex)
                val pathRegister = getInstruction<OneRegisterInstruction>(fieldIndex).registerA
                val builderRegister = getInstruction<FiveRegisterInstruction>(fieldIndex + 1).registerC
                removeInstructions(fieldIndex, 4)
                addInstructions(
                    fieldIndex,
                    """
                        move-object/from16 v$pathRegister, p2
                        invoke-virtual {v$builderRegister, v$pathRegister}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                    """,
                )
            }
        }
    }
}
