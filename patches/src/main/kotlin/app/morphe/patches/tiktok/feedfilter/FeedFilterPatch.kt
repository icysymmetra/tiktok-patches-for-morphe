/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/feedfilter/FeedFilterPatch.kt
 */
package app.morphe.patches.tiktok.feedfilter

import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint.method
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/feedfilter/FeedItemsFilter;"
private const val TAKO_AI_FILTER_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/feedfilter/TakoAiFilter;"

@Suppress("unused")
val feedFilterPatch = bytecodePatch(
    name = "Feed filter",
    description = "Hides feed ads, TikTok Shop items, livestreams, stories, photo posts, and videos outside configured view or like ranges.",
    default = true,
) {
    dependsOn(
        sharedExtensionPatch,
    )

    compatibleWith(*AppCompatibilities.tiktok4623())

    execute {
        // Enables the feed filter extension after settings were loaded.
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableFeedFilter()V",
        )

        MainFeedResponseFingerprint.method.let { method ->
            val returnIndices =
                method.implementation!!.instructions.withIndex()
                    .filter { it.value.opcode == Opcode.RETURN_OBJECT }
                    .map { it.index }
                    .toList()

            returnIndices.asReversed().forEach { returnIndex ->
                val register = (method.implementation!!.instructions[returnIndex] as OneRegisterInstruction).registerA

                method.addInstructionsAtControlFlowLabel(
                    returnIndex,
                    "invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS_DESCRIPTOR->filter(Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;)V",
                )
            }
        }

        FollowFeedFingerprint.method.let { method ->
            val returnIndices =
                method.implementation!!.instructions.withIndex()
                    .filter { it.value.opcode == Opcode.RETURN_OBJECT }
                    .map { it.index }
                    .toList()

            returnIndices.asReversed().forEach { returnIndex ->
                val register = (method.implementation!!.instructions[returnIndex] as OneRegisterInstruction).registerA

                method.addInstructionsAtControlFlowLabel(
                    returnIndex,
                    "invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS_DESCRIPTOR->filter(Lcom/ss/android/ugc/aweme/follow/presenter/FollowFeedList;)V",
                )
            }
        }

        FollowFeedListGetItemsFingerprint.method.let { method ->
            val returnIndices = method.implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_OBJECT }
                .map { it.index }

            returnIndices.asReversed().forEach { returnIndex ->
                method.addInstructionsAtControlFlowLabel(
                    returnIndex,
                    "invoke-static/range {p0 .. p0}, $EXTENSION_CLASS_DESCRIPTOR->filterLate(Lcom/ss/android/ugc/aweme/follow/presenter/FollowFeedList;)V",
                )
            }
        }

        FollowFeedPresenterPostProcessFingerprint.method.let { method ->
            val returnIndices = method.implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_VOID }
                .map { it.index }

            returnIndices.asReversed().forEach { returnIndex ->
                method.addInstructionsAtControlFlowLabel(
                    returnIndex,
                    "invoke-static/range {p1 .. p1}, $EXTENSION_CLASS_DESCRIPTOR->filterLateFinal(Lcom/ss/android/ugc/aweme/follow/presenter/FollowFeedList;)V",
                )
            }
        }

        listOf(
            ProfileRefreshResultFingerprint.method,
            ProfileLoadMoreResultFingerprint.method,
            ProfileLoadLatestResultFingerprint.method,
        ).forEach(MutableMethod::filterProfileAdsAfterNativeTransform)

        ProfileDetailAdEventFingerprint.method.filterProfileDetailAdEvent()

        val finalFeedInsertionMethod = FinalFeedInsertionFingerprint.method
        val insertionPayloadType = finalFeedInsertionMethod.parameterTypes.single().toString()
        val insertionPayloadConstructors = mutableClassDefBy(insertionPayloadType).methods.filter { method ->
            method.name == "<init>" &&
                method.parameterTypes.map(CharSequence::toString) == listOf(
                    "I",
                    "Ljava/lang/String;",
                    "Ljava/util/List;",
                ) &&
                method.returnType == "V"
        }
        if (insertionPayloadConstructors.size != 1) {
            throw PatchException(
                "Expected one final feed insertion payload constructor for $insertionPayloadType, " +
                    "found ${insertionPayloadConstructors.size}",
            )
        }
        insertionPayloadConstructors.single().filterLateInsertedAds(insertionPayloadType)

        InsertedFeedItemsFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range {p0 .. p3}, $EXTENSION_CLASS_DESCRIPTOR->filterInsertedFeedItems(Lcom/ss/android/ugc/aweme/feed/panel/BaseListFragmentPanel;ILjava/lang/String;Ljava/util/List;)Ljava/util/List;
                move-result-object p3
            """,
        )

        val cacheChainMethod = CacheChainDeliveryFingerprint.method
        val cacheResultType = cacheChainMethod.parameterTypes.single().toString()
        val cacheResultClass = classDefBy(cacheResultType)
        val cachePayloadFields = cacheResultClass.fields.filter { field ->
            field.type.startsWith("L") &&
                field.type != "Ljava/lang/String;" &&
                field.type != "Ljava/util/List;"
        }
        if (cachePayloadFields.size != 1) {
            throw PatchException(
                "Expected one cache-result payload field in $cacheResultType, " +
                    "found ${cachePayloadFields.size}",
            )
        }
        val cachePayloadField = cachePayloadFields.single()
        val cachedAwemeFields = classDefBy(cachePayloadField.type).fields.filter { field ->
            field.type == "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
        }
        if (cachedAwemeFields.size != 1) {
            throw PatchException(
                "Expected one Aweme field in ${cachePayloadField.type}, " +
                    "found ${cachedAwemeFields.size}",
            )
        }
        val cachedAwemeField = cachedAwemeFields.single()
        val cacheFailureFields = cacheResultClass.fields.filter { it.type == "Z" }
        if (cacheFailureFields.size != 1) {
            throw PatchException(
                "Expected one cache-result failure field in $cacheResultType, " +
                    "found ${cacheFailureFields.size}",
            )
        }
        val cacheFailureField = cacheFailureFields.single()

        cacheChainMethod.filterChainedCacheDelivery(cachePayloadField, cachedAwemeField)
        InsertCacheWhenPlayLagFingerprint.method.filterPlayLagCacheInsertion()

        ReachBottomCacheDeliveryFingerprint.method.let { method ->
            if (method.parameterTypes.single().toString() != cacheResultType) {
                throw PatchException(
                    "Reach-bottom and chained cache callbacks use different result contracts",
                )
            }
            method.filterReachBottomCacheDelivery(
                cachePayloadField,
                cachedAwemeField,
                cacheFailureField,
            )
        }

        ColdStartCachedFeedFingerprint.method.let { method ->
            val instructions = method.implementation!!.instructions
            val cacheStoreIndices = instructions.withIndex()
                .filter {
                    it.value.opcode == Opcode.SPUT_OBJECT &&
                        (it.value as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
                            ?.reference
                            ?.let { reference ->
                                reference is FieldReference &&
                                    reference.type == "Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;"
                            } == true
                }
                .map { it.index }
                .toList()
            check(cacheStoreIndices.size == 4) {
                "Expected four cold-start cached FeedItemList stores, found ${cacheStoreIndices.size}"
            }

            val offlineMarkers = instructions.withIndex()
                .filter { (_, instruction) ->
                    instruction.getReference<FieldReference>()?.let { reference ->
                        instruction.opcode == Opcode.SGET_OBJECT &&
                            reference.name == "OFFLINE_MODE" &&
                            reference.type == reference.definingClass
                    } == true
                }
                .map { it.index }
                .toList()
            if (offlineMarkers.size != 1) {
                throw PatchException(
                    "Expected one OFFLINE_MODE marker in cold-start cache method, " +
                        "found ${offlineMarkers.size}",
                )
            }
            val offlineMarker = offlineMarkers.single()
            val offlineStoreIndices = cacheStoreIndices.filter { it > offlineMarker }
            if (offlineStoreIndices.size != 1) {
                throw PatchException(
                    "Expected one offline FeedItemList store after OFFLINE_MODE, " +
                        "found ${offlineStoreIndices.size}",
                )
            }
            val offlineStoreIndex = offlineStoreIndices.single()

            cacheStoreIndices.asReversed().forEachIndexed { ordinal, storeIndex ->
                val listRegister =
                    (method.implementation!!.instructions[storeIndex] as OneRegisterInstruction).registerA
                val filterMethod = if (storeIndex == offlineStoreIndex) {
                    "filterOfflineFeedList"
                } else {
                    "filterCachedFeedList"
                }
                method.addInstructionsWithLabels(
                    storeIndex,
                    """
                        invoke-static/range {v$listRegister .. v$listRegister}, $EXTENSION_CLASS_DESCRIPTOR->$filterMethod(Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;)Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;
                        move-result-object v$listRegister
                        if-nez v$listRegister, :morphe_keep_cold_cache_$ordinal
                        const/4 v$listRegister, 0x0
                        return v$listRegister
                    """,
                    ExternalLabel(
                        "morphe_keep_cold_cache_$ordinal",
                        method.getInstruction(storeIndex),
                    ),
                )
            }
        }

        TakoAiFeedButtonSetVisibleFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, $TAKO_AI_FILTER_CLASS_DESCRIPTOR->shouldHideFeedButton()Z
                move-result v0
                if-eqz v0, :morphe_keep_feed_tako_visible_state
                const/4 p1, 0x0
                :morphe_keep_feed_tako_visible_state
                nop
            """,
        )

        TakoAiFeedButtonBindFingerprint.method.addInstructions(
            2,
            "invoke-static {p1}, $TAKO_AI_FILTER_CLASS_DESCRIPTOR->hideBoundFeedButtonView(Landroid/view/View;)V",
        )
    }
}

private fun MutableMethod.filterChainedCacheDelivery(
    cachePayloadField: FieldReference,
    cachedAwemeField: FieldReference,
) {
    val instructions = implementation?.instructions
        ?: throw PatchException("Chained cache delivery method has no implementation")
    val payloadReadIndices = instructions.withIndex()
        .filter { (_, instruction) ->
            instruction.opcode == Opcode.IGET_OBJECT &&
                instruction.getReference<FieldReference>() == cachePayloadField
        }
        .map { it.index }
        .toList()
    if (payloadReadIndices.size != 1) {
        throw PatchException(
            "Expected one cache payload read in chained cache delivery, " +
                "found ${payloadReadIndices.size}",
        )
    }

    val payloadReadIndex = payloadReadIndices.single()
    val payloadRead = getInstruction<TwoRegisterInstruction>(payloadReadIndex)
    val payloadRegister = payloadRead.registerA
    val resultRegister = payloadRead.registerB
    val nextSourceIndex = payloadReadIndex + 2
    if (instructions.getOrNull(payloadReadIndex + 1)?.opcode != Opcode.IF_NEZ) {
        throw PatchException("Chained cache delivery no longer branches on its payload")
    }

    addInstructionsWithLabels(
        payloadReadIndex,
        """
            iget-object v$payloadRegister, v$resultRegister, $cachePayloadField
            if-eqz v$payloadRegister, :morphe_cache_chain_native
            iget-object v$payloadRegister, v$payloadRegister, $cachedAwemeField
            invoke-static/range {v$payloadRegister .. v$payloadRegister}, $EXTENSION_CLASS_DESCRIPTOR->shouldKeepCachedAweme(Lcom/ss/android/ugc/aweme/feed/model/Aweme;)Z
            move-result v$payloadRegister
            if-eqz v$payloadRegister, :morphe_cache_chain_next_source
        """,
        ExternalLabel("morphe_cache_chain_native", getInstruction(payloadReadIndex)),
        ExternalLabel("morphe_cache_chain_next_source", getInstruction(nextSourceIndex)),
    )
}

private fun MutableMethod.filterPlayLagCacheInsertion() {
    addInstructionsWithLabels(
        0,
        """
            invoke-static/range {p1 .. p1}, $EXTENSION_CLASS_DESCRIPTOR->shouldKeepCachedAweme(Lcom/ss/android/ugc/aweme/feed/model/Aweme;)Z
            move-result v0
            if-nez v0, :morphe_keep_play_lag_cache_item
            return-void
        """,
        ExternalLabel("morphe_keep_play_lag_cache_item", getInstruction(0)),
    )
}

private fun MutableMethod.filterReachBottomCacheDelivery(
    cachePayloadField: FieldReference,
    cachedAwemeField: FieldReference,
    cacheFailureField: FieldReference,
) {
    addInstructions(
        0,
        """
            move-object/from16 v0, p1
            iget-object v0, v0, $cachePayloadField
            if-eqz v0, :morphe_keep_reach_bottom_cache_result
            iget-object v0, v0, $cachedAwemeField
            invoke-static/range {v0 .. v0}, $EXTENSION_CLASS_DESCRIPTOR->shouldKeepCachedAweme(Lcom/ss/android/ugc/aweme/feed/model/Aweme;)Z
            move-result v0
            if-nez v0, :morphe_keep_reach_bottom_cache_result
            const/4 v0, 0x1
            move-object/from16 v1, p1
            iput-boolean v0, v1, $cacheFailureField
            :morphe_keep_reach_bottom_cache_result
            nop
        """,
    )
}

private fun MutableMethod.filterLateInsertedAds(payloadType: String) {
    val listStoreIndices = implementation?.instructions?.withIndex()
        ?.filter { (_, instruction) ->
            instruction.opcode == Opcode.IPUT_OBJECT &&
                instruction.getReference<FieldReference>()?.let { reference ->
                    reference.definingClass == payloadType &&
                        reference.type == "Ljava/util/List;"
                } == true
        }
        ?.map { it.index }
        ?.toList()
        ?: throw PatchException("Final feed insertion payload constructor has no implementation")
    if (listStoreIndices.size != 1) {
        throw PatchException(
            "Expected one List field store in final feed insertion payload constructor, " +
                "found ${listStoreIndices.size}",
        )
    }

    addInstructions(
        listStoreIndices.single(),
        """
            invoke-static/range {p2 .. p3}, $EXTENSION_CLASS_DESCRIPTOR->filterLateInsertedAds(Ljava/lang/String;Ljava/util/List;)Ljava/util/List;
            move-result-object p3
        """,
    )
}

private fun MutableMethod.filterProfileAdsAfterNativeTransform() {
    val instructions = implementation?.instructions
        ?: throw PatchException("Profile video result method has no implementation")
    val transformIndices = instructions.withIndex()
        .filter { (index, instruction) ->
            instruction.getReference<MethodReference>()?.let { reference ->
                reference.definingClass == definingClass &&
                    reference.parameterTypes == listOf("Ljava/util/List;") &&
                    reference.returnType == "Ljava/util/List;" &&
                    instructions.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
            } == true
        }
        .map { it.index }
        .toList()
    if (transformIndices.size != 1) {
        throw PatchException(
            "Expected one native profile list transform in $definingClass->$name, " +
                "found ${transformIndices.size}",
        )
    }

    val resultIndex = transformIndices.single() + 1
    val resultRegister = getInstruction<OneRegisterInstruction>(resultIndex).registerA
    addInstructions(
        resultIndex + 1,
        """
            invoke-static/range {v$resultRegister .. v$resultRegister}, $EXTENSION_CLASS_DESCRIPTOR->filterProfileAds(Ljava/util/List;)Ljava/util/List;
            move-result-object v$resultRegister
        """,
    )
}

private fun MutableMethod.filterProfileDetailAdEvent() {
    val pagerUpdateIndices = implementation?.instructions?.withIndex()
        ?.filter { (_, instruction) ->
            instruction.getReference<MethodReference>()?.let { reference ->
                reference.definingClass ==
                    "Lcom/ss/android/ugc/aweme/detail/platform/IDetailPageAbility;" &&
                    reference.parameterTypes == listOf("Ljava/util/List;") &&
                    reference.returnType == "V"
            } == true
        }
        ?.map { it.index }
        ?.toList()
        ?: throw PatchException("Profile detail ad event method has no implementation")
    if (pagerUpdateIndices.size != 1) {
        throw PatchException(
            "Expected one profile detail pager list update in $definingClass->$name, " +
                "found ${pagerUpdateIndices.size}",
        )
    }

    val pagerUpdateIndex = pagerUpdateIndices.single()
    val pagerUpdate = getInstruction<FiveRegisterInstruction>(pagerUpdateIndex)
    if (pagerUpdate.registerCount != 2) {
        throw PatchException(
            "Expected profile detail pager update to use receiver and list registers, " +
                "found ${pagerUpdate.registerCount}",
        )
    }

    val listRegister = pagerUpdate.registerD
    addInstructionsAtControlFlowLabel(
        pagerUpdateIndex,
        """
            invoke-static/range {v$listRegister .. v$listRegister}, $EXTENSION_CLASS_DESCRIPTOR->filterProfileAds(Ljava/util/List;)Ljava/util/List;
            move-result-object v$listRegister
        """,
    )
}
