/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/feedfilter/FeedFilterPatch.kt
 */
package app.morphe.patches.tiktok.feedfilter

import app.morphe.patcher.Fingerprint
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.MainActivityOnCreateFingerprint
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.navigation.MainNewIntentFingerprint
import app.morphe.patches.tiktok.misc.navigation.writes
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.patches.tiktok.privacy.invokeSitesOf
import app.morphe.patches.tiktok.shared.callThroughLocals
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patches.tiktok.shared.objectIn
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.util.getFreeRegisterProvider
import app.morphe.patcher.util.getReference
import app.morphe.util.implementationOrPatchException
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/feedfilter/FeedItemsFilter;"
private const val PROFILE_DETAIL_PANEL_DESCRIPTOR =
    "Lcom/ss/android/ugc/aweme/detail/panel/ProfileDetailFragmentPanel;"
private const val TAKO_AI_FILTER_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/feedfilter/TakoAiFilter;"
private const val CARD_FILTERS_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/feedfilter/CardFilters;"
private const val SEARCH_LYNX_CARDS_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/feedfilter/SearchLynxCards;"
private const val LIVE_FEED_FILTER_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/feedfilter/LiveFeedFilter;"
private const val FEED_ITEM_LIST_DESCRIPTOR = "Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;"
private const val FILTERED_COUNT_PILL_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/feedfilter/FilteredCountPill;"
private const val LINKED_VIDEO_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/feedfilter/LinkedVideo;"

@Suppress("unused")
val feedFilterPatch = bytecodePatch(
    name = "Feed filter",
    description = "Hides ads in the feed, and lets you hide other things you don't want to see, like LIVEs, Shop posts, AI videos or videos with words, creators or sounds you pick. Hiding ads is on by default. Everything else starts off in Metra settings > Feed controls.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch,
        sharedExtensionPatch,
    )

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        // Enables the feed filter extension after settings were loaded.
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableFeedFilter()V",
        )

        // Show how many were filtered: the label follows the main activity from its creation and
        // goes up as it resumes, so the switch alone decides whether anything is drawn.
        MainActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $FILTERED_COUNT_PILL_CLASS_DESCRIPTOR->install(Landroid/app/Activity;)V",
        )

        // The video a link from outside TikTok opens gets past the rules (#117). A cold start
        // carries the link in the activity's intent, and the running app gets it in onNewIntent.
        MainActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p1 }, $LINKED_VIDEO_CLASS_DESCRIPTOR->onCreate(Landroid/app/Activity;Landroid/os/Bundle;)V",
        )
        MainNewIntentFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p1 .. p1 }, $LINKED_VIDEO_CLASS_DESCRIPTOR->onNewIntent(Landroid/content/Intent;)V",
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

        // The cold-start TopView is the one community leak route no list filter reaches. The
        // feed fetch hands the response's preload ads to the splash ad service before
        // fetchFeedList returns, which is where filter(FeedItemList) runs, so upstream PR #166's
        // reset of preloadAds at filter time reached nothing on 47.0.3. The list is rerouted
        // through the extension on its way into the service: with Remove ads on the service gets
        // an empty list, and the route is counted on a TopViewPreload line either way. The
        // install marker at entry puts the family in the export on a cold start that was served
        // no TopView, the way the mid-roll marker does. The marker goes in first, so the handoff
        // index below is read from the shifted body.
        FeedApiFetchFingerprint.method.apply {
            addInstruction(0, "invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->topViewPreloadInstalled()V")
            // One hook, at the read of preloadAds, which every non-null response reaches and
            // which comes before TikTok's own "nothing to preload" check. The list is counted
            // and, with Remove ads on, replaced by an empty one right there, so that check then
            // takes TikTok down the path it takes on every fetch served no ads: past the
            // stamping loop, the handoff and the preload task. Emptying at the handoff instead
            // would have handed the service an empty list it never sees in stock TikTok and run
            // its task on the result, a path nothing had ever executed. The emptied list is also
            // written back into the field, so the later readers of preloadAds (the list's clone,
            // the commerce preload) see it too. The field keeps its name and is read once; the
            // handoff is still required of the fetch so the thin request beside it cannot match.
            val (readIndex, read) = implementationOrPatchException("Feed filter").instructions.withIndex()
                .filter { it.value.isTopViewPreloadRead() }
                .singleOrPatchException("Feed filter: the feed fetch's one read of preloadAds")
            val listRegister = (read as TwoRegisterInstruction).registerA
            val holderRegister = read.registerB
            // The write-back is an iput-object, whose registers are four bits wide.
            if (listRegister > 15 || holderRegister > 15) {
                throw PatchException(
                    "Feed filter: preloadAds is read into v$listRegister from v$holderRegister, past what " +
                        "the write-back can name.",
                )
            }
            addInstructions(
                readIndex + 1,
                """
                    invoke-static/range {v$listRegister .. v$listRegister}, $EXTENSION_CLASS_DESCRIPTOR->dropTopViewPreload(Ljava/util/List;)Ljava/util/List;
                    move-result-object v$listRegister
                    iput-object v$listRegister, v$holderRegister, $FEED_ITEM_LIST_DESCRIPTOR->preloadAds:Ljava/util/List;
                """,
            )
        }

        // Some 47.0.3 main-feed lists are restored or filled after fetchFeedList has returned.
        // Every consumer still crosses this real-named getter. The extension wrapper catches
        // every Throwable and leaves the original list alone on failure, so this late safety
        // net cannot break TikTok's model read.
        FeedItemListGetItemsFingerprint.method.addInstruction(
            0,
            "invoke-static/range {p0 .. p0}, " +
                "$EXTENSION_CLASS_DESCRIPTOR->filterOnRead(Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;)V",
        )

        // A profile's list is read before its model stamps dataUserId on it (the advance request
        // and the feed author preload read getItems first), so it is marked where it is parsed.
        // Unmarked, the feed's preferences emptied someone else's profile page by page, and each
        // empty page made TikTok load the next.
        ProfileApiExecuteFingerprint.method.let { method ->
            val returnIndices = method.implementationOrPatchException("Feed filter").instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_OBJECT }
                .map { it.index }
                .toList()
            if (returnIndices.isEmpty()) {
                throw PatchException("Feed filter: apiExecuteGetJSONObject returns nothing to mark")
            }
            returnIndices.asReversed().forEach { returnIndex ->
                val register = (method.getInstruction(returnIndex) as OneRegisterInstruction).registerA
                method.addInstructionsAtControlFlowLabel(
                    returnIndex,
                    "invoke-static/range {v$register .. v$register}, " +
                        "$EXTENSION_CLASS_DESCRIPTOR->markProfileResponse(Ljava/lang/Object;)V",
                )
            }
        }

        // A list the reader opens from a grid, a collection for one, is copied into the profile
        // model's own FeedItemList, which no profile parse saw and which has no profile uid. Marked
        // as the model first reads it back after the copy, it gets a profile list's rules, and the
        // feed's rules no longer take saved videos out of the pager the reader tapped into (#135).
        OpenedListFillFingerprint.method.let { method ->
            val instructions = method.implementationOrPatchException("Feed filter").instructions.toList()
            val copyIndex = instructions.indexOfFirst { it.isModelSetItems() }
            val readIndex = instructions.withIndex()
                .firstOrNull { it.index > copyIndex && it.value.isModelDataRead() }?.index
                ?: throw PatchException("Feed filter: the opened list's copy is never read back")
            val register = (instructions[readIndex] as OneRegisterInstruction).registerA
            method.addInstruction(
                readIndex + 1,
                "invoke-static/range {v$register .. v$register}, " +
                    "$EXTENSION_CLASS_DESCRIPTOR->markOpenedList(Ljava/lang/Object;)V",
            )
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

        // The mid-roll splice runs after every list above has been filtered and puts an ad in
        // a video's place in the pager adapter directly, which is how issue #2's ads reached a
        // profile pager whose list carried nothing but organic videos. The ad is the second
        // parameter; the guard leaves before the adapter is touched, and the video stays.
        MidAdReplaceFingerprint.method.guardAtEntry(
            "Feed filter",
            "invoke-static {p1}, $EXTENSION_CLASS_DESCRIPTOR->dropMidAd(Lcom/ss/android/ugc/aweme/feed/model/Aweme;)Z",
            "return-void",
        )
        MidAdComponentCreateFingerprint.method.addInstructions(
            0,
            "invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->midAdInstalled()V",
        )

        // Issue #2 as it stood on 0.40.0. A creator's video pager asks a commerce endpoint of
        // its own for ads and splices the answer between the creator's videos, so those ads are
        // in none of the lists above and the mid-roll splice never sees them either. TikTok first
        // asks itself whether this profile should get ads; that answer is filtered at every
        // return, and a no means the request is never sent.
        ProfileAdEligibilityFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow { opcode == Opcode.RETURN }.forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA
                addInstructionsAtControlFlowLabel(
                    index,
                    """
                        invoke-static/range {v$register .. v$register}, $EXTENSION_CLASS_DESCRIPTOR->allowProfileAdRequest(Z)Z
                        move-result v$register
                    """,
                )
            }
        }
        // And every read of the response's ad list, wherever it is (the request, the coroutine
        // that inserts the ads, the ad measurement), for a request that gets out some other way.
        filterProfileAdResponseReads()

        // The search grids are not Aweme lists, so their cards are filtered on the parsed
        // response instead, before the forty places that read them get a look.
        SearchResultRequestIdFingerprint.method.addInstructions(
            0,
            "invoke-static/range {p0 .. p0}, $EXTENSION_CLASS_DESCRIPTOR->filterSearchAds(Ljava/lang/Object;)V",
        )
        // The server-drawn Lynx cards in search, TikTok's Short Drama block among them, are built
        // from a results chunk's patches and never pass through the list above, so each one is
        // judged where its row binds it: the Top results adapter hands a card to one of two
        // holders, each as (this, fragment, patch), and the other lists' Lynx cell binds
        // (this cell, item). All three are required on a build the patch is declared for, where
        // a missing one would let the block back in without a word; a build the patch is forced
        // onto keeps the rest of the feed filter without them.
        val declaredBuild = packageMetadata.versionName in declaredVersions()
        fun lynxBind(fingerprint: Fingerprint) = if (declaredBuild) fingerprint.method else fingerprint.methodOrNull
        val holderBound = "invoke-static/range {p0 .. p2}, $SEARCH_LYNX_CARDS_CLASS_DESCRIPTOR->onHolderBound(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
        lynxBind(SearchLynxHolderBindFingerprint)?.addInstructions(0, holderBound)
        lynxBind(SearchDynamicHolderBindFingerprint)?.addInstructions(0, holderBound)
        lynxBind(SearchLynxCardBindFingerprint)?.addInstructions(
            0,
            "invoke-static/range {p0 .. p1}, $SEARCH_LYNX_CARDS_CLASS_DESCRIPTOR->onCardBound(Ljava/lang/Object;Ljava/lang/Object;)V",
        )

        // The LIVE feed you swipe through (issue #57). Its rooms come a page at a time from
        // /webcast/feed/, and every page goes through one handler that turns the items into rooms
        // and adds them to the pager, so the page is swapped for its filtered copy at the top of
        // it. The list is p1 in a frame of 41 to 44 registers, hence the range form. Required on a
        // declared build like the search binds above; elsewhere the LIVE rows stay hidden.
        val livePage = if (declaredBuild) LiveDrawRoomPageFingerprint.method else LiveDrawRoomPageFingerprint.methodOrNull
        livePage?.addInstructions(
            0,
            """
                invoke-static/range { p1 .. p1 }, $LIVE_FEED_FILTER_CLASS_DESCRIPTOR->filter(Ljava/util/List;)Ljava/util/List;
                move-result-object p1
            """,
        )
        if (livePage != null) {
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, $LIVE_FEED_FILTER_CLASS_DESCRIPTOR->installed()V",
            )
        }

        // The Friends tab is a separate feed with its own response type, so none of the
        // hooks above ever see it, and every consumer reads its list straight off the field.
        // The getter and the success callback carry a response whose fields are filled in;
        // the constructor only catches the ones the app builds itself, because gson writes
        // the fields after calling it.
        FriendsFeedAwemeListFingerprint.method.addInstruction(
            0,
            "invoke-static/range {p0 .. p0}, " +
                "$EXTENSION_CLASS_DESCRIPTOR->filterFriendsFeed(Ljava/lang/Object;)V",
        )

        FriendsFeedSuccessFingerprint.method.addInstruction(
            0,
            "invoke-static/range {p1 .. p1}, " +
                "$EXTENSION_CLASS_DESCRIPTOR->filterFriendsFeed(Ljava/lang/Object;)V",
        )

        FriendsFeedResponseFingerprint.method.apply {
            val returns = implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_VOID }
                .map { it.index }
                .toList()
            check(returns.isNotEmpty()) {
                "Feed filter: the Friends feed response constructor does not return."
            }
            returns.asReversed().forEach { index ->
                addInstructionsAtControlFlowLabel(
                    index,
                    "invoke-static/range {p0 .. p0}, " +
                        "$EXTENSION_CLASS_DESCRIPTOR->filterFriendsFeed(Ljava/lang/Object;)V",
                )
            }
        }

        val repostFeedData = if (declaredBuild) RepostFeedDataFingerprint.method else RepostFeedDataFingerprint.methodOrNull
        repostFeedData?.addInstruction(
            0,
            "invoke-static/range {p0 .. p0}, " +
                "$EXTENSION_CLASS_DESCRIPTOR->filterRepostFeed(Ljava/lang/Object;)V",
        )

        // Opening a video from a profile hands the list to the detail pager once, which the
        // event above covers. Scrolling past that video refills the pager through the profile
        // detail panel's own two delivery methods, and those never saw the profile filter.
        val profileDetailDeliveries = mutableClassDefBy(PROFILE_DETAIL_PANEL_DESCRIPTOR).methods
            .filter { candidate ->
                candidate.returnType == "V" &&
                    candidate.parameterTypes.map(CharSequence::toString) ==
                    listOf("Ljava/util/List;", "Z")
            }
        if (profileDetailDeliveries.size != 2) {
            throw PatchException(
                "Expected two list deliveries on the profile detail panel, " +
                    "found ${profileDetailDeliveries.size}",
            )
        }
        profileDetailDeliveries.forEach { delivery ->
            delivery.addInstructions(
                0,
                """
                    invoke-static/range {p1 .. p1}, $EXTENSION_CLASS_DESCRIPTOR->filterProfileAds(Ljava/util/List;)Ljava/util/List;
                    move-result-object p1
                """,
            )
        }

        val finalFeedInsertionMethod = FinalFeedInsertionFingerprint.method
        val insertionPayloadType = finalFeedInsertionMethod.parameterTypes.single().toString()
        // By the set of parameters, not their order. The payload takes an int, a feed key and a
        // list on every build, and 46.7.3 moved the list ahead of the key, which is R8's choice
        // and not TikTok's: the static factory beside it still takes them the old way round.
        val insertionPayloadConstructors = mutableClassDefBy(insertionPayloadType).methods
            .filter(Method::isInsertionPayloadConstructor)
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

        // The result data class keeps this identity across the old and rebuilt cache stacks.
        // Its obfuscated descriptor does not.
        val cacheResultType = CacheResultClassFingerprint.originalClassDef.type
        val cacheResultClass = classDefBy(cacheResultType)
        val cachePayloads = cacheResultClass.fields.mapNotNull { field ->
            if (!field.type.startsWith("L")) return@mapNotNull null
            val payloadClass = classDefByOrNull(field.type) ?: return@mapNotNull null
            val awemeFields = payloadClass.fields.filter { candidate ->
                candidate.type == "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
            }
            if (awemeFields.size == 1) field to awemeFields.single() else null
        }
        if (cachePayloads.size != 1) {
            throw PatchException(
                "Expected one cache-result field whose value holds an Aweme in $cacheResultType, " +
                    "found ${cachePayloads.size}",
            )
        }
        val (cachePayloadField, cachedAwemeField) = cachePayloads.single()
        val cacheSuccessFields = cacheResultClass.fields.filter { it.type == "Z" }
        if (cacheSuccessFields.size != 1) {
            throw PatchException(
                "Expected one cache-result success field in $cacheResultType, " +
                    "found ${cacheSuccessFields.size}",
            )
        }
        val cacheSuccessField = cacheSuccessFields.single()
        val rejectedCopy = rejectedCacheCopy(cacheResultClass, cacheSuccessField)
        mutableClassDefBy(cacheResultType).methods.add(rejectedCopy)

        val legacyCacheChain = CacheChainDeliveryFingerprint.methodOrNull
        val cacheNormalizer = CacheResultNormalizerFingerprint.methodOrNull
        if ((legacyCacheChain == null) == (cacheNormalizer == null)) {
            throw PatchException(
                "Expected exactly one cache delivery strategy, found legacy=" +
                    "${legacyCacheChain != null}, normalizer=${cacheNormalizer != null}",
            )
        }
        when {
            cacheNormalizer != null -> {
                if (cacheNormalizer.parameterTypes.single().toString() != cacheResultType) {
                    throw PatchException(
                        "Cache normalizer and CacheLoadResult toString use different result contracts",
                    )
                }
                val emptyCopy = rejectedCacheCopy(cacheResultClass, cacheSuccessField, cachePayloadField)
                mutableClassDefBy(cacheResultType).methods.add(emptyCopy)
                installImmutableCacheNormalizer(cacheNormalizer, cachePayloadField, cachedAwemeField, emptyCopy)
            }
            legacyCacheChain != null -> {
                if (legacyCacheChain.parameterTypes.single().toString() != cacheResultType) {
                    throw PatchException(
                        "Legacy cache callback and CacheLoadResult toString use different result contracts",
                    )
                }
                legacyCacheChain.filterChainedCacheDelivery(cachePayloadField, cachedAwemeField)
            }
        }

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
                rejectedCopy,
            )
        }

        // The golden cache method, the offline cache method and the orchestrator that runs them
        // both. The golden hit-cache step is a method of its own with one store, and the two parse
        // stores sit in an orchestrator that calls the golden and the offline method in turn, five
        // stores in all. (Up to 47.0.3 the golden method ran the cold start itself.)
        val golden = ColdStartGoldenCacheFingerprint.method
        val offline = ColdStartOfflineCacheFingerprint.method
        val orchestrators = findColdStartOrchestrators(golden, offline)
        if (orchestrators.size != 1) {
            throw PatchException(
                "${orchestrators.size} methods call the golden and the offline cold-start method " +
                    "where one orchestrator was expected",
            )
        }
        val coldStartMethods = (listOf(golden, offline) + orchestrators).distinctBy { method ->
            "${method.definingClass}->${method.name}${method.parameterTypes}${method.returnType}"
        }
        coldStartMethods.forEach { method ->
            if (method.returnType != "Z") {
                throw PatchException(
                    "Cold-start cache method ${method.definingClass}->${method.name} returns " +
                        "${method.returnType}, not the hit flag the filter answers with",
                )
            }
        }
        val coldStartStores = coldStartMethods.map { method ->
            method to method.implementation!!.instructions.withIndex()
                .filter { (_, instruction) ->
                    instruction.opcode == Opcode.SPUT_OBJECT &&
                        instruction.getReference<FieldReference>()?.type ==
                        "Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;"
                }
                .map { it.index }
                .toList()
        }
        val cacheStoreCount = coldStartStores.sumOf { (_, indices) -> indices.size }
        check(cacheStoreCount == COLD_START_STORES) {
            "Expected $COLD_START_STORES cold-start cached FeedItemList stores, found $cacheStoreCount"
        }

        val offlineMarkers = coldStartMethods.flatMap { method ->
            method.implementation!!.instructions.withIndex().mapNotNull { (index, instruction) ->
                instruction.getReference<FieldReference>()?.let { reference ->
                    if (
                        instruction.opcode == Opcode.SGET_OBJECT &&
                        reference.name == "OFFLINE_MODE" &&
                        reference.type == reference.definingClass
                    ) {
                        method to index
                    } else {
                        null
                    }
                }
            }
        }
        if (offlineMarkers.size != 1) {
            throw PatchException(
                "Expected one OFFLINE_MODE marker across cold-start cache methods, " +
                    "found ${offlineMarkers.size}",
            )
        }
        val (offlineMethod, offlineMarker) = offlineMarkers.single()
        val offlineStoreIndices = coldStartStores.single { (method, _) -> method === offlineMethod }
            .second.filter { it > offlineMarker }
        if (offlineStoreIndices.size != 1) {
            throw PatchException(
                "Expected one offline FeedItemList store after OFFLINE_MODE, " +
                    "found ${offlineStoreIndices.size}",
            )
        }
        val offlineStoreIndex = offlineStoreIndices.single()

        coldStartStores.forEachIndexed { methodOrdinal, (method, cacheStoreIndices) ->
            cacheStoreIndices.asReversed().forEachIndexed { ordinal, storeIndex ->
                val listRegister =
                    (method.implementation!!.instructions[storeIndex] as OneRegisterInstruction).registerA
                val filterMethod = if (method === offlineMethod && storeIndex == offlineStoreIndex) {
                    "filterOfflineFeedList"
                } else {
                    "filterCachedFeedList"
                }
                method.addInstructionsWithLabels(
                    storeIndex,
                    """
                        invoke-static/range {v$listRegister .. v$listRegister}, $EXTENSION_CLASS_DESCRIPTOR->$filterMethod(Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;)Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;
                        move-result-object v$listRegister
                        if-nez v$listRegister, :morphe_keep_cold_cache_${methodOrdinal}_$ordinal
                        const/4 v$listRegister, 0x0
                        return v$listRegister
                    """,
                    ExternalLabel(
                        "morphe_keep_cold_cache_${methodOrdinal}_$ordinal",
                        method.getInstruction(storeIndex),
                    ),
                )
            }
        }

        TakoAiFeedButtonSetVisibleFingerprint.method.requireLocals("Feed filter", 1)
        TakoAiFeedButtonSetVisibleFingerprint.method.guardAtEntry(
            "Feed filter",
            "invoke-static {}, $TAKO_AI_FILTER_CLASS_DESCRIPTOR->shouldHideFeedButton()Z",
            "const/4 p1, 0x0",
        )

        // The "Ask" strip under the caption is a slot component bound per video, and it is not
        // the floating button the two hooks above cover (issue #6). Asked at the top of its bind:
        // with the switch on the slot's view is hidden and the bind never fills it.
        TakoAskBarBindFingerprint.method.guardAtEntry(
            "Feed filter",
            "invoke-static {}, $TAKO_AI_FILTER_CLASS_DESCRIPTOR->shouldHideAskBar()Z",
            """
                invoke-virtual {p0}, Lcom/bytedance/assem/arch/reused/ReusedUISlotAssem;->getContentView()Landroid/view/View;
                move-result-object v0
                invoke-static {v0}, $TAKO_AI_FILTER_CLASS_DESCRIPTOR->hideAskBar(Landroid/view/View;)V
                return-void
            """,
        )

        // The trigger component for the same slot. On some accounts TikTok draws the ask bar
        // through this trigger instead of (or alongside) the slot, so a reporter's phone showed
        // the bar while the slot hook never fired at all. The trigger's Sp (on 46.2.3; mr, yr,
        // Kr on later builds) gets a content view and registers a callback that makes it visible.
        // Returning before any of that runs is enough: nothing fills the strip and nothing makes
        // it visible. The obfuscated view-getter name changes on every build, so calling it from
        // the guard would need a name that matches only one; returning early avoids that.
        TakoAskBarTriggerBindFingerprint.method.guardAtEntry(
            "Feed filter",
            "invoke-static {}, $TAKO_AI_FILTER_CLASS_DESCRIPTOR->shouldHideAskBar()Z",
            "return-void",
        )

        // The feed-level Tako trigger lives in the tikbot package, separate from the detail-page
        // one above. A reporter's export showed no detail-page hook firing while the bar still
        // appeared, because their account draws it through this component instead. The roof
        // variant covers the same slot from the "roof" layout position. Same guard on all three.
        TakoFeedTriggerBindFingerprint.method.guardAtEntry(
            "Feed filter",
            "invoke-static {}, $TAKO_AI_FILTER_CLASS_DESCRIPTOR->shouldHideAskBar()Z",
            "return-void",
        )
        TakoFeedTriggerRoofBindFingerprint.method.guardAtEntry(
            "Feed filter",
            "invoke-static {}, $TAKO_AI_FILTER_CLASS_DESCRIPTOR->shouldHideAskBar()Z",
            "return-void",
        )

        // The Tako entrance on the search page (issue #22), a lone bubble or a Voice and Ask Tako
        // pill depending on the account. Each inflates its ViewStub through one method, and the
        // base class already treats a null answer as "no entrance", so that is the answer given.
        listOf(TakoSearchBubbleInflateFingerprint, TakoSearchPillInflateFingerprint).forEach {
            it.method.guardAtEntry(
                "Feed filter",
                "invoke-static {}, $TAKO_AI_FILTER_CLASS_DESCRIPTOR->shouldHideSearchEntrance()Z",
                """
                    const/4 v0, 0x0
                    return-object v0
                """,
            )
        }

        // The search results tab strip is served as a list of dynamic tab infos whose only
        // consumer is the strip's view model, reading through two real-named getters that each
        // open with one read of the field. The list is filtered at both reads, the guard for a
        // build that serves an Ask Tako tab as data; on 47.0.3 none does (the pill below is a
        // view of its own), and the extension records the keys it meets.
        listOf(
            SearchDynamicTabListGetTabListFingerprint,
            SearchDynamicTabListGetSearchTabListFingerprint,
        ).forEach { fingerprint ->
            fingerprint.method.apply {
                val (index, read) = implementationOrPatchException("Feed filter").instructions.withIndex()
                    .filter { it.value.isSearchTabListRead() }
                    .singleOrPatchException("Feed filter: one read of the search tab list in $name")
                val register = (read as TwoRegisterInstruction).registerA
                addInstructions(
                    index + 1,
                    """
                        invoke-static/range {v$register .. v$register}, $TAKO_AI_FILTER_CLASS_DESCRIPTOR->filterSearchTabs(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$register
                    """,
                )
            }
        }

        // The Ask Tako pill at the head of every search results page, the one Tako surface the
        // switch left standing (issue #21's screenshots). It is not a served tab but a view the
        // fragment inflates with the strip and styles before it returns, so the extension is
        // handed the fragment's view at every return and hides the pill's column by the real id
        // name its text view keeps.
        SearchContainerFragmentOnViewCreatedFingerprint.method.let { method ->
            val returnIndices = method.implementationOrPatchException("Feed filter").instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_VOID }
                .map { it.index }
            if (returnIndices.isEmpty()) {
                throw PatchException("Feed filter: SearchContainerFragment.onViewCreated has no return to hook")
            }
            returnIndices.asReversed().forEach { returnIndex ->
                method.addInstructionsAtControlFlowLabel(
                    returnIndex,
                    "invoke-static/range {p1 .. p1}, $TAKO_AI_FILTER_CLASS_DESCRIPTOR->hideSearchTabEntrance(Landroid/view/View;)V",
                )
            }
        }

        // The "People also ask Tako" card in the search results, a native card of Tako questions
        // in a row of its own that none of the hooks above reach (47.4.3). Its slot component
        // finds the card's parts in onViewCreated and never touches the card's own visibility, so
        // the extension is handed the view at every return and hides the card and its row.
        // Required on a declared build like the search binds; a forced build goes without it.
        val takoSugCard = if (declaredBuild) {
            SearchTakoSugCardCreatedFingerprint.method
        } else {
            SearchTakoSugCardCreatedFingerprint.methodOrNull
        }
        takoSugCard?.hideTakoSugCardAtReturns(required = declaredBuild)

        // The Tako bar inside the comments sheet, the "related words" strip above the comment
        // list. It is a server-driven top bar component of biz type SEARCH_TAKO (the service in
        // the Tako package) or SEARCH_TAKO_BG (the commentv2 bridge to the same Tako service).
        // The header resolver asks the service canShow before it builds the component and treats
        // false as a business condition not met, so false is an answer it already handles. The
        // bridge base's canShow serves nine bridges (ads, shop, POI, search and the rest), so
        // that guard hands the service over and the extension answers only for the Tako one;
        // the class-name check below keeps that comparison honest against the build.
        val takoBridge = TakoCommentTopBarBridgeFingerprint.originalClassDef
        if (takoBridge.superclass != COMMENT_TOP_BAR_BRIDGE_BASE) {
            throw PatchException(
                "Feed filter: ${takoBridge.type} extends ${takoBridge.superclass}, not the bridge " +
                    "base whose canShow is guarded, so the comments Tako bar would be left standing.",
            )
        }
        TakoCommentTopBarCanShowFingerprint.method.guardAtEntry(
            "Feed filter",
            "invoke-static {}, $TAKO_AI_FILTER_CLASS_DESCRIPTOR->shouldHideCommentTopBar()Z",
            """
                const/4 v0, 0x0
                return v0
            """,
        )
        CommentTopBarBridgeCanShowFingerprint.method.guardAtEntry(
            "Feed filter",
            "invoke-static {p0}, $TAKO_AI_FILTER_CLASS_DESCRIPTOR->shouldHideBridgedCommentTopBar(Ljava/lang/Object;)Z",
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        // Issue #162. A caption tap (or "more") on some videos opens TikTok's search panel, with
        // "Discover more with Tako" and an "Ask Tako" box, instead of expanding the caption. The
        // caption's expand asks one gate before it hands the tap to that panel and expands in
        // place on a no, the path a video with nothing to search takes. The gate's answer goes
        // through the Tako filter between its move and the branch; the fixture test holds that
        // nothing jumps onto the branch past it.
        CaptionSearchPanelFingerprint.method.apply {
            val move = captionSearchPanelGate() ?: throw PatchException(
                "Feed filter: $definingClass->$name no longer asks one gate before the caption's search panel.",
            )
            val register = getInstruction<OneRegisterInstruction>(move).registerA
            addInstructions(
                move + 1,
                """
                    invoke-static/range {v$register .. v$register}, $TAKO_AI_FILTER_CLASS_DESCRIPTOR->allowCaptionSearchPanel(Z)Z
                    move-result v$register
                """,
            )
        }

        // Issue #162, the other half. On a video with smart search info the caption builder always
        // draws its "more" tail, the one that leads into the search panel, even when the caption
        // fits, so with the panel skipped a tap on "more" did nothing. Whether a caption counts as
        // a smart search one is the third argument of this helper (the video has smart search
        // info), which it returns when the video can show the entrance at all. Answering that
        // argument at the helper's entry turns the caption into the one a video without smart
        // search info gets: "more" only where the text is cut, and an expand in place.
        SmartSearchCaptionFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range {p2 .. p2}, $TAKO_AI_FILTER_CLASS_DESCRIPTOR->allowSmartSearchCaption(Z)Z
                move-result p2
            """,
        )

        // The "Ask · topic" bar issue #6's reporter still saw on 0.40.0 is none of the Tako
        // components above. It is one of TikTok's common bottom banners, keyed bottom_banner_tako
        // and drawn by the same banner view as the "Search · topic" bar, and every banner reaches
        // a feed cell through this getter. Its answer goes through the Tako filter on the way out.
        AwemeBannersFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow { opcode == Opcode.RETURN_OBJECT }.forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA
                // The two-register form, because the call takes the video as well as its list. A
                // getter has two registers in all, but a build that grows it is refused here
                // rather than handed a register the instruction cannot name.
                val receiver = implementation!!.registerCount - 1
                if (register > 15 || receiver > 15) {
                    throw PatchException(
                        "Feed filter: Aweme.getBanners holds its list in v$register and itself in " +
                            "v$receiver, past what invoke-static can name.",
                    )
                }
                addInstructionsAtControlFlowLabel(
                    index,
                    """
                        invoke-static {p0, v$register}, $TAKO_AI_FILTER_CLASS_DESCRIPTOR->filterBanners(Lcom/ss/android/ugc/aweme/feed/model/Aweme;Ljava/util/List;)Ljava/util/List;
                        move-result-object v$register
                    """,
                )
            }
        }

        TakoAiFeedButtonBindFingerprint.method.apply {
            // After the base class has laid the view out, which is what index 2 meant on 46.2.3
            // and what it stops meaning the moment anything is added above it.
            val superIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_SUPER &&
                    getReference<MethodReference>()?.name == "onViewCreated"
            }
            addInstructions(
                superIndex + 1,
                "invoke-static/range {p1 .. p1}, " +
                    "$TAKO_AI_FILTER_CLASS_DESCRIPTOR->hideBoundFeedButtonView(Landroid/view/View;)V",
            )
        }

        // The Tako bubble at the bottom right of a feed video (discussion #115), drawn by one of
        // two subclasses of a shared entrance and none of the components above. Each bind only
        // tells the entrance's view model whether to show, so the guard returns before it does.
        // Each onViewCreated launches the coroutine that applies that answer once the base has
        // laid the view out, so the view is hidden right there and the coroutine never starts.
        listOf(
            TakoRightBottomFixedEntranceBindFingerprint,
            TakoRightBottomInCellEntranceBindFingerprint,
        ).forEach {
            it.method.guardAtEntry(
                "Feed filter",
                "invoke-static {}, $TAKO_AI_FILTER_CLASS_DESCRIPTOR->shouldHideRightBottomEntrance()Z",
                "return-void",
            )
        }
        listOf(
            TakoRightBottomFixedEntranceCreatedFingerprint,
            TakoRightBottomInCellEntranceCreatedFingerprint,
        ).forEachIndexed { ordinal, fingerprint ->
            fingerprint.method.apply {
                requireLocals("Feed filter", 1)
                val superIndex = indexOfFirstInstructionOrThrow {
                    opcode == Opcode.INVOKE_SUPER &&
                        getReference<MethodReference>()?.name == "onViewCreated"
                }
                addInstructionsWithLabels(
                    superIndex + 1,
                    """
                        invoke-static/range {p1 .. p1}, $TAKO_AI_FILTER_CLASS_DESCRIPTOR->hideRightBottomEntrance(Landroid/view/View;)Z
                        move-result v0
                        if-eqz v0, :morphe_tako_entrance_kept_$ordinal
                        return-void
                    """,
                    ExternalLabel("morphe_tako_entrance_kept_$ordinal", getInstruction(superIndex + 1)),
                )
            }
        }

        // The share prompt that pops up after a like, asking the reader to share the video
        // with friends. Upstream #22. The method name changes on every build (O, J, H, D)
        // but the string "share_guide" and the parameter shape are stable. Returning early
        // is enough: nothing about the prompt is shown if the method never runs.
        ShareGuideFingerprint.method.guardAtEntry(
            "Feed filter",
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/Settings;->shouldHideShareGuide()Z",
            "return-void",
        )

        // Things TikTok slots into the feed that never arrive as ordinary items, so they
        // are stopped where they are built. Each is optional: a build without the surface
        // simply skips it.
        PlaylistBottomBarAvailableFingerprint.method.requireLocals("Feed filter", 1)
        PlaylistBottomBarAvailableFingerprint.method.guardAtEntry(
            "Feed filter",
            "invoke-static {}, $CARD_FILTERS_CLASS_DESCRIPTOR->shouldHidePlaylistBar()Z",
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        // TikTok may remove this surface in a future build. If the marker still exists, its
        // insertion hook is mandatory; if the surface itself is absent, there is no card here
        // to suppress.
        var recUserCardSurfacePresent = false
        classDefForEach { classDef ->
            if (!recUserCardSurfacePresent) {
                recUserCardSurfacePresent = classDef.methods.any { method ->
                    method.implementation?.instructions?.any { instruction ->
                        instruction.getReference<StringReference>()?.string == "friend_recommend_card"
                    } == true
                }
            }
        }
        selectRecUserCardInsertion(
            RecUserCardInsertFingerprint.methodOrNull,
            recUserCardSurfacePresent,
        )?.let { insertion ->
            // Null is the app's own "no recommended users to insert" result.
            insertion.requireLocals("Feed filter", 1)
            insertion.guardAtEntry(
                "Feed filter",
                "invoke-static {}, $CARD_FILTERS_CLASS_DESCRIPTOR->shouldHideInsertedCards()Z",
                """
                    const/4 v0, 0x0
                    return-object v0
                """,
            )
        }

        FeedLynxCardLoadFingerprint.method.requireLocals("Feed filter", 1)
        FeedLynxCardLoadFingerprint.method.guardAtEntry(
            "Feed filter",
            "invoke-static {}, $CARD_FILTERS_CLASS_DESCRIPTOR->shouldHideInsertedCards()Z",
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        // An empty list is the app's own "no card to ask for", so the Lemon8 promo is never
        // requested and never arrives. Remove feed ads also drops it from the list if it came
        // another way.
        Lemon8CardRequestFingerprint.method.requireLocals("Feed filter", 1)
        Lemon8CardRequestFingerprint.method.guardAtEntry(
            "Feed filter",
            "invoke-static {}, $CARD_FILTERS_CLASS_DESCRIPTOR->shouldSkipLemon8PromoRequest()Z",
            """
                new-instance v0, Ljava/util/ArrayList;
                invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
                return-object v0
            """,
        )

        DramaBlockingAdFingerprint.method.apply {
            // Every return, not the first one. This fingerprint does not even name its method,
            // so a build that answers false down one path and true down another would have had
            // only one of them filtered, silently.
            findInstructionIndicesReversedOrThrow { opcode == Opcode.RETURN }.forEach { dramaReturnIndex ->
                val dramaRegister = getInstruction<OneRegisterInstruction>(dramaReturnIndex).registerA
                addInstructionsAtControlFlowLabel(
                    dramaReturnIndex,
                    """
                        invoke-static/range {v$dramaRegister .. v$dramaRegister}, $CARD_FILTERS_CLASS_DESCRIPTOR->shouldBlockForDramaAd(Z)Z
                        move-result v$dramaRegister
                    """,
                )
            }
        }

        SpecActTouchpointAttachFingerprint.method.requireLocals("Feed filter", 1)
        SpecActTouchpointAttachFingerprint.method.guardAtEntry(
            "Feed filter",
            "invoke-static {}, $CARD_FILTERS_CLASS_DESCRIPTOR->shouldHideEventBadge()Z",
            "return-void",
        )
    }
}

/** Refuses to skip a recommendation-card surface whose known insertion method merely drifted. */
internal fun selectRecUserCardInsertion(
    insertion: MutableMethod?,
    surfacePresent: Boolean,
): MutableMethod? {
    if (insertion == null && surfacePresent) {
        throw PatchException(
            "Feed filter: friend_recommend_card still exists, but its insertion method was not found.",
        )
    }
    return insertion
}

/**
 * Every read of the profile ad response's `awemeList`, answered by the extension instead.
 *
 * <p>The field keeps its real name because gson fills it, and the three methods reading it on
 * each retained build (the request, the coroutine that inserts the ads and the ad measurement)
 * are all named by R8, so the reads are found by the field rather than by their methods. The
 * hook goes after the read, on the fall-through, and a branch into the next instruction keeps
 * its target: the register holds the list only on the path that read it.
 */
internal fun BytecodePatchContext.filterProfileAdResponseReads() {
    val readers = mutableListOf<Pair<ClassDef, Method>>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith("Lapp/morphe/extension/")) return@classDefForEach
        classDef.methods.forEach { method ->
            if (method.implementation?.instructions?.any(Instruction::readsProfileAdList) == true) {
                readers += classDef to method
            }
        }
    }
    if (readers.isEmpty()) {
        throw PatchException(
            "Feed filter: nothing reads awemeList of $PROFILE_AD_RESPONSE_DESCRIPTOR any more.",
        )
    }
    readers.forEach { (classDef, method) ->
        val reader = mutableClassDefBy(classDef).findMutableMethodOf(method)
        reader.implementation!!.instructions.withIndex()
            .filter { it.value.readsProfileAdList() }
            .map { it.index }
            .asReversed()
            .forEach { index ->
                val register = reader.getInstruction<TwoRegisterInstruction>(index).registerA
                reader.addInstructions(
                    index + 1,
                    """
                        invoke-static/range {v$register .. v$register}, $EXTENSION_CLASS_DESCRIPTOR->filterProfileAdResponse(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$register
                    """,
                )
            }
    }
}

private fun Instruction.readsProfileAdList(): Boolean =
    opcode == Opcode.IGET_OBJECT && getReference<FieldReference>()?.let { field ->
        field.definingClass == PROFILE_AD_RESPONSE_DESCRIPTOR &&
            field.name == "awemeList" &&
            field.type == "Ljava/util/List;"
    } == true

/**
 * Hands the Tako questions card's view, p1 of `SearchTakoSugCardAssem.onViewCreated(View)V`, to
 * the extension at every return. The view is the method's last register; a build that writes
 * over it would hand the extension something else and fail verification on the way, so that
 * build and one with no return to hook go without the hook: a [PatchException] when [required],
 * the method left as it is otherwise.
 */
internal fun MutableMethod.hideTakoSugCardAtReturns(required: Boolean) {
    fun skip(why: String) {
        if (required) throw PatchException("Feed filter: SearchTakoSugCardAssem.onViewCreated $why")
    }
    val code = implementation ?: return skip("has no implementation")
    if (code.instructions.any { it.writes(code.registerCount - 1) }) return skip("writes over its view parameter")
    val returnIndices = code.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_VOID }
        .map { it.index }
    if (returnIndices.isEmpty()) return skip("has no return to hook")
    returnIndices.asReversed().forEach { returnIndex ->
        addInstructionsAtControlFlowLabel(
            returnIndex,
            "invoke-static/range {p1 .. p1}, $TAKO_AI_FILTER_CLASS_DESCRIPTOR->hideSearchSugCard(Landroid/view/View;)V",
        )
    }
}

/**
 * The older chain's payload branch can move to the next source without changing the result.
 * Current normalizers use the immutable-copy handoff in ImmutableCacheNormalizer.kt instead.
 */
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

    // The block used to do its work in the payload's own register, so both labels it re-enters
    // at were reached with a boolean sitting in a register the native path holds a reference in.
    // On 46.2.3 the instruction at each label writes that register before anything reads it
    // (index 6 and index 8 of LX/0pqs;->LIZ), so nothing was actually wrong; the block no longer
    // depends on the host doing that. A register of its own also leaves the payload read the
    // patcher branched on exactly as the host wrote it.
    val scratchRegister = getFreeRegisterProvider(
        payloadReadIndex,
        1,
        listOf(payloadRegister, resultRegister),
    ).getFreeRegister4Bit()

    addInstructionsWithLabels(
        payloadReadIndex,
        """
            iget-object v$scratchRegister, v$resultRegister, $cachePayloadField
            if-eqz v$scratchRegister, :morphe_cache_chain_native
            iget-object v$scratchRegister, v$scratchRegister, $cachedAwemeField
            invoke-static/range {v$scratchRegister .. v$scratchRegister}, $EXTENSION_CLASS_DESCRIPTOR->shouldKeepChainedCache(Lcom/ss/android/ugc/aweme/feed/model/Aweme;)Z
            move-result v$scratchRegister
            if-eqz v$scratchRegister, :morphe_cache_chain_next_source
        """,
        ExternalLabel("morphe_cache_chain_native", getInstruction(payloadReadIndex)),
        ExternalLabel("morphe_cache_chain_next_source", getInstruction(nextSourceIndex)),
    )
}

private fun MutableMethod.filterPlayLagCacheInsertion() {
    // v0 is written ahead of the host's own first instruction.
    requireLocals("Feed filter", 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static/range {p1 .. p1}, $EXTENSION_CLASS_DESCRIPTOR->shouldKeepPlayLagCache(Lcom/ss/android/ugc/aweme/feed/model/Aweme;)Z
            move-result v0
            if-nez v0, :morphe_keep_play_lag_cache_item
            return-void
        """,
        ExternalLabel("morphe_keep_play_lag_cache_item", getInstruction(0)),
    )
}

internal fun MutableMethod.filterReachBottomCacheDelivery(
    cachePayloadField: FieldReference,
    cachedAwemeField: FieldReference,
    rejectedCopy: MethodReference,
) {
    // v0 and v1 are written ahead of the host's own first instruction.
    requireLocals("Feed filter", 2)
    if (implementation!!.registerCount > 256) throw PatchException("Cache callback parameter is outside move-result-object's reach")
    addInstructions(
        0,
        """
            move-object/from16 v0, p1
            iget-object v0, v0, $cachePayloadField
            if-eqz v0, :morphe_keep_reach_bottom_cache_result
            iget-object v0, v0, $cachedAwemeField
            invoke-static/range {v0 .. v0}, $EXTENSION_CLASS_DESCRIPTOR->shouldKeepReachBottomCache(Lcom/ss/android/ugc/aweme/feed/model/Aweme;)Z
            move-result v0
            if-nez v0, :morphe_keep_reach_bottom_cache_result
            invoke-static/range {p1 .. p1}, $rejectedCopy
            move-result-object p1
            :morphe_keep_reach_bottom_cache_result
            nop
        """,
    )
}

/** The parameters of the final feed insertion payload constructor, sorted so order does not matter. */
private val INSERTION_PAYLOAD_PARAMETERS =
    listOf("I", "Ljava/lang/String;", "Ljava/util/List;").sorted()

/**
 * Whether the method is the payload's constructor: an int, a feed key and a list, in whatever
 * order this build's R8 put them.
 *
 * <p>The static factory beside it takes the same three, so being a `<init>` returning void is
 * what separates them, not the parameters.
 */
internal fun Method.isInsertionPayloadConstructor() =
    name == "<init>" &&
        returnType == "V" &&
        parameterTypes.map(CharSequence::toString).sorted() == INSERTION_PAYLOAD_PARAMETERS

/**
 * The `pN` the feed key arrives in, which is whichever parameter is the String.
 *
 * <p>It was `p2` on 46.2.3 and the constructor takes `(int, List, String)` on 46.7.3 and 46.8.3,
 * so a written `p2` would have handed a List to something that takes a String. Registers are
 * counted rather than indexed, because a wide parameter takes two of them.
 */
internal fun MutableMethod.insertionPayloadKeyRegister(): String {
    val keyIndex = parameterTypes.indexOfFirst { it.toString() == "Ljava/lang/String;" }
    if (keyIndex < 0) {
        throw PatchException(
            "Final feed insertion payload constructor takes no feed key: $parameterTypes",
        )
    }
    var register = if (AccessFlags.STATIC.value and accessFlags != 0) 0 else 1
    parameterTypes.take(keyIndex).forEach { register += if (it == "J" || it == "D") 2 else 1 }
    return "p$register"
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

    // The register the store actually reads from, not p3. They are the same on 46.2.3 and the
    // filtered list would have gone nowhere on a build that assembled the list somewhere else.
    val listStoreIndex = listStoreIndices.single()
    val listRegister = getInstruction<TwoRegisterInstruction>(listStoreIndex).registerA

    val call = callThroughLocals(
        "Feed filter",
        "invoke-static",
        "$EXTENSION_CLASS_DESCRIPTOR->filterLateInsertedAds(Ljava/lang/String;Ljava/util/List;)Ljava/util/List;",
        false,
        objectIn(insertionPayloadKeyRegister()),
        objectIn("v$listRegister"),
    )

    addInstructions(
        listStoreIndex,
        """
            $call
            move-result-object v$listRegister
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
            invoke-static/range {v$listRegister .. v$listRegister}, $EXTENSION_CLASS_DESCRIPTOR->filterProfileDetailAds(Ljava/util/List;)Ljava/util/List;
            move-result-object v$listRegister
        """,
    )
}

/** The cold start's orchestrator, a method of its own, found by what it calls. */
private fun BytecodePatchContext.findColdStartOrchestrators(golden: Method, offline: Method): List<MutableMethod> {
    val sites = invokeSitesOf(setOf(golden.coldStartCall(), offline.coldStartCall()), static = true)
    val calls = sites.groupBy { it.method.coldStartCall() }.values.associate { group ->
        mutableClassDefBy(group.first().owner).findMutableMethodOf(group.first().method) to
            group.map { it.target }.toSet()
    }
    return coldStartOrchestrators(golden, offline, calls)
}
