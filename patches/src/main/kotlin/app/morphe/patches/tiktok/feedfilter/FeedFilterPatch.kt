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
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/feedfilter/FeedItemsFilter;"
private const val TAKO_AI_FILTER_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/feedfilter/TakoAiFilter;"

private val feedListHooksPatch = bytecodePatch {
    dependsOn(
        sharedExtensionPatch,
    )

    compatibleWith(*AppCompatibilities.tiktok4714())

    execute {
        fun requirePublicClass(type: String) = classDefBy(type).also { classDef ->
            if (!AccessFlags.PUBLIC.isSet(classDef.accessFlags)) {
                throw PatchException("Required feed model class is not public: $type")
            }
        }

        fun requirePublicInstanceMethod(
            definingClass: String,
            name: String,
            parameters: List<String>,
            returnType: String,
        ) {
            val matches = requirePublicClass(definingClass).methods.filter { candidate ->
                candidate.name == name &&
                    candidate.parameterTypes.map(CharSequence::toString) == parameters &&
                    candidate.returnType == returnType
            }
            if (matches.size != 1) {
                throw PatchException(
                    "Expected one $definingClass->$name$parameters$returnType, found ${matches.size}",
                )
            }
            val method = matches.single()
            if (!AccessFlags.PUBLIC.isSet(method.accessFlags) || AccessFlags.STATIC.isSet(method.accessFlags)) {
                throw PatchException("Required feed model method is not a public instance method: $definingClass->$name")
            }
        }

        fun requirePublicInstanceField(definingClass: String, name: String, type: String) {
            val matches = requirePublicClass(definingClass).fields.filter { candidate ->
                candidate.name == name && candidate.type == type
            }
            if (matches.size != 1) {
                throw PatchException(
                    "Expected one $definingClass->$name:$type, found ${matches.size}",
                )
            }
            val field = matches.single()
            if (!AccessFlags.PUBLIC.isSet(field.accessFlags) || AccessFlags.STATIC.isSet(field.accessFlags)) {
                throw PatchException("Required feed model field is not a public instance field: $definingClass->$name")
            }
        }

        val awemeType = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
        val aigcInfoType = "Lcom/ss/android/ugc/aweme/feed/AIGCInfo;"
        val moderationAigcInfoType = "Lcom/ss/android/ugc/aweme/feed/model/ModerationAigcInfo;"
        requirePublicInstanceMethod(awemeType, "getAigcInfo", emptyList(), aigcInfoType)
        requirePublicInstanceMethod(
            awemeType,
            "getModerationAigcInfo",
            emptyList(),
            moderationAigcInfoType,
        )
        requirePublicInstanceMethod(aigcInfoType, "getAIGCLabelType", emptyList(), "I")
        requirePublicInstanceField(aigcInfoType, "createByAI", "Z")
        requirePublicInstanceField(moderationAigcInfoType, "moderationAigcLabelType", "I")
        requirePublicInstanceMethod(awemeType, "getItemDistributeSource", emptyList(), "Ljava/lang/String;")
        requirePublicInstanceMethod(
            awemeType, "getRecReasonsStruct", emptyList(),
            "Lcom/ss/android/ugc/aweme/feed/model/RecReasonsStruct;",
        )
        requirePublicClass("Lcom/ss/android/ugc/aweme/feed/model/RecReasonsStruct;")
        requirePublicInstanceMethod(
            "Lcom/ss/android/ugc/aweme/feed/panel/BaseListFragmentPanel;",
            "getEventType", emptyList(), "Ljava/lang/String;",
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

        FeedItemListGetItemsFingerprint.method.let { method ->
            val returnIndices = method.implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_OBJECT }
                .map { it.index }

            if (returnIndices.size != 4) {
                throw PatchException(
                    "Expected four FeedItemList.getItems object returns, found ${returnIndices.size}",
                )
            }
            returnIndices.asReversed().forEach { returnIndex ->
                val register = method.getInstruction<OneRegisterInstruction>(returnIndex).registerA
                method.addInstructionsAtControlFlowLabel(
                    returnIndex,
                    """
                        invoke-static {p0, v$register}, $EXTENSION_CLASS_DESCRIPTOR->filterFeedItemListOnRead(Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;Ljava/util/List;)Ljava/util/List;
                        move-result-object v$register
                    """,
                )
            }
        }

        SearchMixFeedResponseFingerprint.method.addInstruction(
            0,
            "invoke-static/range {p1 .. p1}, $EXTENSION_CLASS_DESCRIPTOR->filterSearchContent(Lcom/ss/android/ugc/aweme/search/pages/result/topsearch/core/model/SearchMixFeedList;)V",
        )

        FriendsFeedNetworkResponseFingerprint.method.addInstruction(
            0,
            "invoke-static/range {p1 .. p1}, $EXTENSION_CLASS_DESCRIPTOR->filterFriendsContent(Ljava/lang/Object;)V",
        )
        FriendsFeedFinalDeliveryFingerprint.method.filterFriendsFinalDelivery()

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
                val register = method.getInstruction<OneRegisterInstruction>(returnIndex).registerA
                method.addInstructionsAtControlFlowLabel(
                    returnIndex,
                    """
                        invoke-static {p0, v$register}, $EXTENSION_CLASS_DESCRIPTOR->filterLateResult(Lcom/ss/android/ugc/aweme/follow/presenter/FollowFeedList;Ljava/util/List;)Ljava/util/List;
                        move-result-object v$register
                    """,
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

        ProfileNativeListTransformFingerprint.method.filterProfileItemsAtReturns()

        ProfileDetailAdEventFingerprint.method.filterProfileDetailAdEvent()

        val finalFeedInsertionMethod = FinalFeedInsertionFingerprint.method
        val insertionPayloadType = finalFeedInsertionMethod.parameterTypes.single().toString()
        val insertionPayloadConstructors = mutableClassDefBy(insertionPayloadType).methods.filter { method ->
            method.name == "<init>" &&
                method.parameterTypes.map(CharSequence::toString).let { types ->
                    // Parameter order differs per build: (I, String, List) in 46.2.3, (I, List, String) in 47.1.4.
                    types.size == 3 && types.containsAll(listOf("I", "Ljava/lang/String;", "Ljava/util/List;"))
                } &&
                method.returnType == "V"
        }
        if (insertionPayloadConstructors.size != 1) {
            throw PatchException(
                "Expected one final feed insertion payload constructor for $insertionPayloadType, " +
                    "found ${insertionPayloadConstructors.size}",
            )
        }
        insertionPayloadConstructors.single().filterLateInsertedItems(insertionPayloadType)

        val finalInsertionInstructions = finalFeedInsertionMethod.implementation?.instructions
            ?: throw PatchException("Final feed insertion has no implementation")
        val firstPayloadRead = finalInsertionInstructions.withIndex().firstOrNull { (_, instruction) ->
            instruction.opcode == Opcode.IGET_OBJECT &&
                instruction.getReference<FieldReference>()?.let { field ->
                    field.definingClass == insertionPayloadType && field.type == "Ljava/util/List;"
                } == true
        } ?: throw PatchException("Final insertion does not read the payload List")
        if (firstPayloadRead.index != 2 ||
            finalInsertionInstructions.getOrNull(3)?.opcode != Opcode.IF_EQZ ||
            finalInsertionInstructions.getOrNull(4)?.opcode != Opcode.INVOKE_INTERFACE
        ) {
            throw PatchException("Final insertion no longer checks the payload List before use")
        }
        val payloadListField = firstPayloadRead.value.getReference<FieldReference>()!!
        val payloadClass = classDefBy(insertionPayloadType)
        val listFields = payloadClass.fields.filter { it.type == "Ljava/util/List;" }
        val positionFields = payloadClass.fields.filter { it.type == "I" && !AccessFlags.STATIC.isSet(it.accessFlags) }
        val sourceFields = payloadClass.fields.filter { it.type == "Ljava/lang/String;" }
        if (listFields.size != 1 || listFields.single().name != payloadListField.name ||
            positionFields.size != 1 || sourceFields.size != 1
        ) {
            throw PatchException("Final insertion payload constructor fields have changed")
        }
        val positionField = "${insertionPayloadType}->${positionFields.single().name}:I"
        val sourceField = "${insertionPayloadType}->${sourceFields.single().name}:Ljava/lang/String;"
        finalFeedInsertionMethod.addInstructions(
            0,
            """
                iget-object v0, p1, $payloadListField
                invoke-static {p0, v0}, $EXTENSION_CLASS_DESCRIPTOR->filterFinalForYouInsertion(Lcom/ss/android/ugc/aweme/feed/panel/BaseListFragmentPanel;Ljava/util/List;)Ljava/util/List;
                move-result-object v1
                if-eq v0, v1, :morphe_keep_original_insertion_payload
                iget v2, p1, $positionField
                iget-object v3, p1, $sourceField
                new-instance v4, $insertionPayloadType
                invoke-direct {v4, v2, v3, v1}, $insertionPayloadType-><init>(ILjava/lang/String;Ljava/util/List;)V
                move-object p1, v4
                :morphe_keep_original_insertion_payload
                nop
            """,
        )

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
        }.filter { field ->
            // 47.1.4 added a second object field (the insertion plan); only the cache entry holds the Aweme.
            runCatching { classDefBy(field.type) }.getOrNull()?.fields?.count { nested ->
                nested.type == "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
            } == 1
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
        val cacheSuccessFields = cacheResultClass.fields.filter { it.type == "Z" }
        if (cacheSuccessFields.size != 1) {
            throw PatchException(
                "Expected one cache-result success field in $cacheResultType, " +
                    "found ${cacheSuccessFields.size}",
            )
        }
        val cacheSuccessField = cacheSuccessFields.single()

        cacheChainMethod.filterChainedCacheDelivery(cachePayloadField, cachedAwemeField)
        InsertCacheWhenPlayLagFingerprint.method.filterPlayLagCacheInsertion()

        ReachBottomCacheDeliveryFingerprint.method.let { method ->
            if (method.parameterTypes.single().toString() != cacheResultType) {
                throw PatchException(
                    "Reach-bottom and chained cache callbacks use different result contracts",
                )
            }
            // TikTok's immutable result uses true for success. Rejected cache
            // entries must reach its existing failure/cleanup branch, without
            // writing a final field from a different class.
            val sourceFields = cacheResultClass.fields.filter {
                it.type == "Ljava/lang/String;" && !AccessFlags.STATIC.isSet(it.accessFlags)
            }
            val listFields = cacheResultClass.fields.filter {
                it.type == "Ljava/util/List;" && !AccessFlags.STATIC.isSet(it.accessFlags)
            }
            val constructors = cacheResultClass.methods.filter {
                it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf(
                    "Z", cachePayloadField.type, "Ljava/lang/String;", "Ljava/util/List;",
                ) && it.returnType == "V"
            }
            if (!AccessFlags.PUBLIC.isSet(cacheResultClass.accessFlags) ||
                sourceFields.size != 1 || listFields.size != 1 || constructors.size != 1
            ) {
                throw PatchException("Reach-bottom cache result cannot be safely copied")
            }
            val constructor = constructors.single()
            val copiedFields = listOf(
                cacheSuccessField, cachePayloadField, sourceFields.single(), listFields.single(),
            )
            if (cacheResultClass.fields.filterNot { AccessFlags.STATIC.isSet(it.accessFlags) }
                    .toSet() != copiedFields.toSet()
            ) {
                throw PatchException("Reach-bottom cache result has additional state that cannot be copied")
            }
            if (!AccessFlags.PUBLIC.isSet(constructor.accessFlags) || copiedFields.any {
                    !AccessFlags.PUBLIC.isSet(it.accessFlags) || AccessFlags.STATIC.isSet(it.accessFlags)
                }
            ) {
                throw PatchException("Reach-bottom cache result constructor or fields are inaccessible")
            }
            val constructorImplementation = constructor.implementation
                ?: throw PatchException("Reach-bottom cache result constructor has no implementation")
            val thisRegister = constructorImplementation.registerCount - 5
            copiedFields.forEachIndexed { index, field ->
                val expectedOpcode = if (index == 0) Opcode.IPUT_BOOLEAN else Opcode.IPUT_OBJECT
                val store = constructorImplementation.instructions.singleOrNull {
                    it.opcode == expectedOpcode && it.getReference<FieldReference>() == field
                } as? TwoRegisterInstruction
                if (store == null || store.registerB != thisRegister ||
                    store.registerA != thisRegister + index + 1
                ) {
                    throw PatchException("Reach-bottom cache constructor no longer preserves $field")
                }
            }
            method.filterReachBottomCacheDelivery(
                cachePayloadField,
                cachedAwemeField,
                cacheSuccessField,
                sourceFields.single(),
                listFields.single(),
                constructor,
            )
        }

        // Cold-start cache: 46.2.3 hit every cache from one method; 47.1.4 splits it into the local-cache,
        // golden-house and offline-mode methods. Every FeedItemList store is filtered, and stores placed after
        // the OFFLINE_MODE marker of their method use the offline filter.
        val coldStartMethods = listOf(
            ColdStartCachedFeedFingerprint,
            ColdStartGoldenCacheFingerprint,
            ColdStartOfflineCacheFingerprint,
        ).mapNotNull { it.methodOrNull }
            .distinctBy { "${it.definingClass}->${it.name}${it.parameterTypes}" }
        if (coldStartMethods.isEmpty()) {
            throw PatchException("No cold-start cache method was found")
        }

        var coldStoreCount = 0
        var offlineStoreCount = 0
        coldStartMethods.forEach { method ->
            val instructions = method.implementation!!.instructions
            val cacheStoreIndices = instructions.withIndex()
                .filter { (_, instruction) ->
                    instruction.opcode == Opcode.SPUT_OBJECT &&
                        instruction.getReference<FieldReference>()?.type ==
                        "Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;"
                }
                .map { it.index }
                .toList()
            val offlineMarker = instructions.withIndex().firstOrNull { (_, instruction) ->
                instruction.getReference<FieldReference>()?.let { reference ->
                    instruction.opcode == Opcode.SGET_OBJECT &&
                        reference.name == "OFFLINE_MODE" &&
                        reference.type == reference.definingClass
                } == true
            }?.index

            cacheStoreIndices.asReversed().forEachIndexed { ordinal, storeIndex ->
                val listRegister =
                    (method.implementation!!.instructions[storeIndex] as OneRegisterInstruction).registerA
                val isOfflineStore = offlineMarker != null && storeIndex > offlineMarker
                val filterMethod = if (isOfflineStore) "filterOfflineFeedList" else "filterCachedFeedList"
                if (isOfflineStore) offlineStoreCount++ else coldStoreCount++
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
        if (coldStoreCount == 0 || offlineStoreCount == 0) {
            throw PatchException(
                "Expected cold-start cached and offline FeedItemList stores, " +
                    "found $coldStoreCount cached and $offlineStoreCount offline",
            )
        }

    }
}

@Suppress("unused")
val feedFilterPatch = bytecodePatch(
    name = "Feed filter",
    description = "Hides feed ads, TikTok Shop items, livestreams, stories, photo posts, and videos outside configured view or like ranges.",
    default = true,
) {
    dependsOn(feedListHooksPatch, settingsPatch)
    compatibleWith(*AppCompatibilities.tiktok4714())
    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableFeedFilter()V",
        )

        DiscoverBannerResponseFingerprint.method.filterResponseAfterCast(
            "Lcom/ss/android/ugc/aweme/discover/model/BannerList;",
            "filterDiscoverBanners",
        )
        DiscoverTrendingResponseFingerprint.method.filterResponseAfterCast(
            "Lcom/ss/android/ugc/aweme/discover/model/TrendingTopicList;",
            "filterDiscoverTrending",
        )
        DiscoverTrendingPairResponseFingerprint.method.filterResponseAfterCast(
            "Lcom/ss/android/ugc/aweme/discover/model/TrendingTopicList;",
            "filterDiscoverTrending",
        )

        MidAdResponseFingerprint.method.let { method ->
            val returnIndices = method.implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_OBJECT }
                .map { it.index }
                .toList()
            returnIndices.asReversed().forEach { returnIndex ->
                val register = method.getInstruction<OneRegisterInstruction>(returnIndex).registerA
                method.addInstructions(
                    returnIndex,
                    """
                        invoke-static/range {v$register .. v$register}, $EXTENSION_CLASS_DESCRIPTOR->filterMidRollAd(Lcom/ss/android/ugc/aweme/feed/model/Aweme;)Lcom/ss/android/ugc/aweme/feed/model/Aweme;
                        move-result-object v$register
                    """,
                )
            }
        }

        ProfileAdEligibilityFingerprint.method.let { method ->
            val returnIndices = method.implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN }
                .map { it.index }
                .toList()
            returnIndices.asReversed().forEach { returnIndex ->
                val register = method.getInstruction<OneRegisterInstruction>(returnIndex).registerA
                method.addInstructionsAtControlFlowLabel(
                    returnIndex,
                    """
                        invoke-static/range {v$register .. v$register}, $EXTENSION_CLASS_DESCRIPTOR->filterProfileAdEligibility(Z)Z
                        move-result v$register
                    """,
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

@Suppress("unused")
val hideAiContentPatch = bytecodePatch(
    name = "Hide AI content",
    description = "Hides posts marked as AI-generated or AI-modified by TikTok or their creators. Unmarked AI content may still appear.",
    default = true,
) {
    dependsOn(feedListHooksPatch, settingsPatch)
    compatibleWith(*AppCompatibilities.tiktok4714())
    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableHideAiContent()V",
        )
    }
}

@Suppress("unused")
val hideFypSlopPatch = bytecodePatch(
    name = "Hide FYP unpersonalized slop videos",
    description = "Hides certain batches of unpersonalized slop posts that appear in your For You feed.",
    default = true,
) {
    dependsOn(feedListHooksPatch, settingsPatch)
    compatibleWith(*AppCompatibilities.tiktok4714())
    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableHideFypSlop()V",
        )
    }
}

private fun MutableMethod.filterResponseAfterCast(targetType: String, extensionMethod: String) {
    val castIndices = implementation?.instructions?.withIndex()
        ?.filter { (_, instruction) ->
            instruction.opcode == Opcode.CHECK_CAST &&
                instruction.getReference<TypeReference>()?.type == targetType
        }
        ?.map { it.index }
        ?.toList()
        ?: throw PatchException("Response mapper has no implementation")
    if (castIndices.size != 1) {
        throw PatchException(
            "Expected one $targetType cast in $definingClass->$name, found ${castIndices.size}",
        )
    }

    val castIndex = castIndices.single()
    val register = getInstruction<OneRegisterInstruction>(castIndex).registerA
    addInstruction(
        castIndex + 1,
        "invoke-static/range {v$register .. v$register}, $EXTENSION_CLASS_DESCRIPTOR->$extensionMethod($targetType)V",
    )
}

/** Resolves the instruction index a branch instruction jumps to. */
private fun MutableMethod.indexOfBranchTarget(branchIndex: Int): Int {
    val instructions = implementation?.instructions?.toList()
        ?: throw PatchException("Branch resolution on a method without implementation")
    val offsets = IntArray(instructions.size)
    var offset = 0
    instructions.forEachIndexed { index, instruction ->
        offsets[index] = offset
        offset += instruction.codeUnits
    }
    val branch = instructions[branchIndex] as? OffsetInstruction
        ?: throw PatchException("Instruction $branchIndex is not a branch")
    val targetOffset = offsets[branchIndex] + branch.codeOffset
    return offsets.indexOf(targetOffset).takeIf { it >= 0 }
        ?: throw PatchException("Branch target at code offset $targetOffset was not found")
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
    // 46.2.3 branches on a non-null payload (if-nez -> deliver, fall through -> next source);
    // 47.1.4 branches on a null payload (if-eqz -> next source, fall through -> deliver).
    val nextSourceIndex = when (instructions.getOrNull(payloadReadIndex + 1)?.opcode) {
        Opcode.IF_NEZ -> payloadReadIndex + 2
        Opcode.IF_EQZ -> indexOfBranchTarget(payloadReadIndex + 1)
        else -> throw PatchException("Chained cache delivery no longer branches on its payload")
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
    cacheSuccessField: FieldReference,
    cacheSourceField: FieldReference,
    cacheListField: FieldReference,
    cacheConstructor: MethodReference,
) {
    val localRegisters = (implementation?.registerCount ?: 0) - parameterTypes.size - 1
    if (AccessFlags.STATIC.isSet(accessFlags) || localRegisters < 5) {
        throw PatchException("Reach-bottom cache callback needs five local registers")
    }
    addInstructions(
        0,
        """
            move-object/from16 v0, p1
            iget-boolean v1, v0, $cacheSuccessField
            if-eqz v1, :morphe_keep_reach_bottom_cache_result
            iget-object v2, v0, $cachePayloadField
            if-eqz v2, :morphe_keep_reach_bottom_cache_result
            iget-object v1, v2, $cachedAwemeField
            invoke-static/range {v1 .. v1}, $EXTENSION_CLASS_DESCRIPTOR->shouldKeepCachedAweme(Lcom/ss/android/ugc/aweme/feed/model/Aweme;)Z
            move-result v1
            if-nez v1, :morphe_keep_reach_bottom_cache_result
            iget-object v3, v0, $cacheSourceField
            iget-object v4, v0, $cacheListField
            new-instance v0, ${cachePayloadField.definingClass}
            const/4 v1, 0x0
            invoke-direct {v0, v1, v2, v3, v4}, $cacheConstructor
            move-object/16 p1, v0
            :morphe_keep_reach_bottom_cache_result
            nop
        """,
    )
}

private fun MutableMethod.filterLateInsertedItems(payloadType: String) {
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

    // Non-static constructor: p0 is `this`, so parameter i lives in p(i + 1).
    val parameterTypeNames = parameterTypes.map(CharSequence::toString)
    val sourceRegister = "p${parameterTypeNames.indexOf("Ljava/lang/String;") + 1}"
    val listRegister = "p${parameterTypeNames.indexOf("Ljava/util/List;") + 1}"

    addInstructions(
        listStoreIndices.single(),
        """
            invoke-static {$sourceRegister, $listRegister}, $EXTENSION_CLASS_DESCRIPTOR->filterLateInsertedItems(Ljava/lang/String;Ljava/util/List;)Ljava/util/List;
            move-result-object $listRegister
        """,
    )
}

private fun MutableMethod.filterProfileItemsAtReturns() {
    val instructions = implementation?.instructions
        ?: throw PatchException("Profile native list transform has no implementation")
    val returnIndices = instructions.withIndex()
        .filter { (_, instruction) -> instruction.opcode == Opcode.RETURN_OBJECT }
        .map { (index, _) -> index }
        .toList()
    if (returnIndices.isEmpty()) {
        throw PatchException(
            "Expected profile native list transform to return at least one List",
        )
    }

    returnIndices.asReversed().forEach { returnIndex ->
        val returnRegister = getInstruction<OneRegisterInstruction>(returnIndex).registerA
        addInstructionsAtControlFlowLabel(
            returnIndex,
            """
                invoke-static/range {v$returnRegister .. v$returnRegister}, $EXTENSION_CLASS_DESCRIPTOR->filterProfileItems(Ljava/util/List;)Ljava/util/List;
                move-result-object v$returnRegister
            """,
        )
    }
}

private fun MutableMethod.filterFriendsFinalDelivery() {
    val instructions = implementation?.instructions
        ?: throw PatchException("Friends final-delivery method has no implementation")
    val callbackIndices = instructions.withIndex()
        .filter { (_, instruction) ->
            instruction.getReference<MethodReference>()?.let { reference ->
                reference.parameterTypes == listOf("I", "Z", "Z", "Ljava/util/List;") &&
                    reference.returnType == "V"
            } == true
        }
        .map { (index, _) -> index }
        .toList()
    if (callbackIndices.size != 1) {
        throw PatchException(
            "Expected one Friends final list callback, found ${callbackIndices.size}",
        )
    }

    val callbackIndex = callbackIndices.single()
    val finalListReadIndices = instructions.withIndex()
        .filter { (index, instruction) ->
            index < callbackIndex && instruction.getReference<FieldReference>()?.let { reference ->
                instruction.opcode == Opcode.IGET_OBJECT &&
                    reference.definingClass ==
                        "Lcom/ss/android/ugc/aweme/friendstab/api/FriendsFeedResponse;" &&
                    reference.name == "friendFeedData" &&
                    reference.type == "Ljava/util/List;"
            } == true
        }
        .map { (index, _) -> index }
        .toList()
    if (finalListReadIndices.isEmpty()) {
        throw PatchException("Friends final callback has no preceding friendFeedData read")
    }

    val finalListReadIndex = finalListReadIndices.last()
    val finalListRead = getInstruction<TwoRegisterInstruction>(finalListReadIndex)
    val responseRegister = finalListRead.registerB
    addInstructionsAtControlFlowLabel(
        finalListReadIndex,
        "invoke-static/range {v$responseRegister .. v$responseRegister}, $EXTENSION_CLASS_DESCRIPTOR->filterFriendsContent(Ljava/lang/Object;)V",
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
            invoke-static/range {v$listRegister .. v$listRegister}, $EXTENSION_CLASS_DESCRIPTOR->filterProfileItems(Ljava/util/List;)Ljava/util/List;
            move-result-object v$listRegister
        """,
    )
}
