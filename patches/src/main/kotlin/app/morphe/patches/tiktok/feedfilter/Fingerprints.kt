/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/feedfilter/Fingerprints.kt
 */
package app.morphe.patches.tiktok.feedfilter

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val BASE_LIST_FRAGMENT_PANEL_DESCRIPTOR =
    "Lcom/ss/android/ugc/aweme/feed/panel/BaseListFragmentPanel;"
private const val AWEME_DESCRIPTOR = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val INSERT_CACHE_WHEN_PLAY_LAG_COMPONENT_DESCRIPTOR =
    "Lcom/ss/android/ugc/aweme/feed/component/InsertCacheWhenPlayLagComponent;"
private const val REACH_BOTTOM_CACHE_COMPONENT_DESCRIPTOR =
    "Lcom/ss/android/ugc/aweme/feed/component/ReachBottomCacheComponent;"

internal object MainFeedResponseFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/FeedApiService;",
    name = "fetchFeedList",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;",
    custom = { method, classDef ->
        classDef.type == "Lcom/ss/android/ugc/aweme/feed/FeedApiService;" &&
            method.parameterTypes.size == 1
    },
)

private const val SPLASH_AD_SERVICE_DESCRIPTOR =
    "Lcom/bytedance/ies/ugc/aweme/commercialize/splash/service/ISplashAdService;"

/**
 * Whether the instruction hands a List to the splash ad service: the cold-start TopView
 * preload handoff. The service's method is R8's and returns the preload task, so the call is
 * known by the service's real name and its one List parameter, in either invoke shape.
 */
internal fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.isTopViewPreloadHandoff(): Boolean =
    (opcode == com.android.tools.smali.dexlib2.Opcode.INVOKE_INTERFACE ||
        opcode == com.android.tools.smali.dexlib2.Opcode.INVOKE_INTERFACE_RANGE) &&
        getReference<MethodReference>()?.let { reference ->
            reference.definingClass == SPLASH_AD_SERVICE_DESCRIPTOR &&
                reference.parameterTypes == listOf("Ljava/util/List;")
        } == true

/**
 * Whether the instruction reads the response's preload ads off the FeedItemList: the TopView
 * preload read, which every non-null response reaches before TikTok's own "nothing to preload"
 * check jumps past the handoff. The field keeps its name on every retained build.
 */
internal fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.isTopViewPreloadRead(): Boolean =
    opcode == com.android.tools.smali.dexlib2.Opcode.IGET_OBJECT &&
        getReference<FieldReference>()?.let { field ->
            field.definingClass == "Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;" &&
                field.name == "preloadAds" && field.type == "Ljava/util/List;"
        } == true

/**
 * The feed fetch and its post-processing: it reads the response's preload ads, stamps each
 * with the request id and log pb, hands the list to the splash ad service as a TopView
 * preload task and writes hasAd into every item, all before fetchFeedList returns. The class
 * keeps its name; the method and its parameter type are R8's, and FeedApi carries two methods
 * of this exact shape (the other is the thin request), so the fetch is the one whose body
 * makes the handoff, which is also the site the patch rewrites.
 */
internal object FeedApiFetchFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/api/FeedApi;",
    returnType = "Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;",
    custom = { method, _ ->
        method.parameterTypes.size == 1 && method.implementation?.instructions?.let { instructions ->
            instructions.any { it.isTopViewPreloadRead() } && instructions.any { it.isTopViewPreloadHandoff() }
        } == true
    },
)

/**
 * The real-named model getter is the stable late boundary for main-feed lists. TikTok 47.0.3
 * has delivery paths that do not return through FeedApiService.fetchFeedList, but all retained
 * builds expose this exact getter before consumers can read the response items.
 */
internal object FeedItemListGetItemsFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;",
    name = "getItems",
    returnType = "Ljava/util/List;",
    parameters = emptyList(),
)

/**
 * Where TikTok parses every profile list into a FeedItemList (posts, Liked, collections, private
 * posts, whoever's profile), before anything reads it. Only the two profile fetchers pass it
 * FeedItemList, and the For You feed parses in FeedApi, never through here (traced on 47.0.3 and
 * 47.1.3). The implementation keeps its real names; the headers parameter is R8's.
 */
internal object ProfileApiExecuteFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/services/ProfileDependentComponentImpl;",
    name = "apiExecuteGetJSONObject",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(
        "I", "Ljava/lang/String;", "Ljava/lang/Class;", "Ljava/lang/String;", "L", "Z", "Ljava/lang/String;",
    ),
)

/** Whether the instruction is a list model's setItems(List), the call the opened-list fill copies through. */
internal fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.isModelSetItems(): Boolean =
    opcode == com.android.tools.smali.dexlib2.Opcode.INVOKE_VIRTUAL &&
        getReference<MethodReference>()?.let { reference ->
            reference.name == "setItems" && reference.returnType == "V" &&
                reference.parameterTypes == listOf("Ljava/util/List;")
        } == true

/** Whether the instruction is a read of a list model's real-named data field, which holds its FeedItemList. */
internal fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.isModelDataRead(): Boolean =
    opcode == com.android.tools.smali.dexlib2.Opcode.IGET_OBJECT &&
        getReference<FieldReference>()?.let { field ->
            field.name == "mData" && field.type == "Ljava/lang/Object;"
        } == true

/**
 * Where a list the reader opened from a grid becomes the list TikTok's profile model plays: a
 * collection, or another grid handing its loaded videos to the video page (X.0OfL.LJIIL on 47.1.4,
 * called through X.0OfJ from the collection click and one other grid). It copies the grid's list
 * into the model's FeedItemList with setItems, then writes the paging on it. That FeedItemList
 * carries no profile uid and no profile parse saw it, so its getItems read ran the main feed's
 * rules (#135). R8 names the class and method; the shape and the maxCursor write name it.
 */
internal object OpenedListFillFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/util/List;", "J", "Z", "Ljava/lang/String;"),
    custom = { method, _ ->
        method.implementation?.instructions?.let { instructions ->
            instructions.any { it.isModelSetItems() } && instructions.any { it.isModelDataRead() } &&
                instructions.any {
                    it.opcode == com.android.tools.smali.dexlib2.Opcode.IPUT_WIDE &&
                        it.getReference<FieldReference>()?.let { field ->
                            field.definingClass == "Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;" &&
                                field.name == "maxCursor"
                        } == true
                }
        } == true
    },
)

internal object FollowFeedFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lcom/ss/android/ugc/aweme/follow/presenter/FollowFeedList;",
    strings = listOf("feed").map { string(it, StringComparisonType.CONTAINS) },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/ss/android/ugc/aweme/follow/presenter/FollowFeedList;",
            name = "getItems",
        ),
    ),
    custom = { method, _ ->
        method.parameterTypes.size == 2 && method.implementation?.instructions?.any {
            it.getReference<MethodReference>()?.let { reference ->
                reference.definingClass == "Lcom/ss/android/ugc/aweme/follow/presenter/FollowFeedList;" &&
                    reference.name == "getItems"
            } == true
        } == true
    },
)

/**
 * No access flags: the patcher compares them exactly, and 46.9.3 marks this getter `public
 * final` where the three builds before it say `public`. The class, name, return type and empty
 * parameter list name one method on every retained build, which is all the flags added.
 */
internal object FollowFeedListGetItemsFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/follow/presenter/FollowFeedList;",
    name = "getItems",
    returnType = "Ljava/util/List;",
    parameters = emptyList(),
)

internal object InsertedFeedItemsFingerprint : Fingerprint(
    definingClass = BASE_LIST_FRAGMENT_PANEL_DESCRIPTOR,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "I",
        "Ljava/lang/String;",
        "Ljava/util/List;",
    ),
    custom = { method, _ ->
        val references = method.implementation?.instructions
            ?.mapNotNull { it.getReference<MethodReference>() }
            ?: emptyList()
        val payloadFactory = references.singleOrNull { reference ->
            reference.parameterTypes == listOf(
                "I",
                "Ljava/lang/String;",
                "Ljava/util/List;",
            ) && reference.returnType.startsWith("L")
        }

        payloadFactory != null && references.any { reference ->
            reference.definingClass == BASE_LIST_FRAGMENT_PANEL_DESCRIPTOR &&
                reference.parameterTypes == listOf(payloadFactory.returnType) &&
                reference.returnType == "V"
        }
    },
)

internal object FinalFeedInsertionFingerprint : Fingerprint(
    definingClass = BASE_LIST_FRAGMENT_PANEL_DESCRIPTOR,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    strings = listOf(
        "insertItemList fall to downgrade logic",
        "homepage_hot",
        "ad_rerank",
    ).map { string(it, StringComparisonType.CONTAINS) },
    filters = listOf(
        methodCall(
            definingClass = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;",
            name = "isAd",
            returnType = "Z",
        ),
    ),
    custom = custom@{ method, _ ->
        if (method.parameterTypes.size != 1) return@custom false

        val payloadType = method.parameterTypes.single().toString()
        val instructions = method.implementation?.instructions ?: return@custom false
        val readsPayloadList = instructions.any { instruction ->
            instruction.getReference<FieldReference>()?.let { reference ->
                instruction.opcode == com.android.tools.smali.dexlib2.Opcode.IGET_OBJECT &&
                    reference.definingClass == payloadType &&
                    reference.type == "Ljava/util/List;"
            } == true
        }
        val readsPayloadSource = instructions.any { instruction ->
            instruction.getReference<FieldReference>()?.let { reference ->
                instruction.opcode == com.android.tools.smali.dexlib2.Opcode.IGET_OBJECT &&
                    reference.definingClass == payloadType &&
                    reference.type == "Ljava/lang/String;"
            } == true
        }
        val checksNativeAdState = instructions.any { instruction ->
            instruction.getReference<MethodReference>()?.let { reference ->
                reference.definingClass == "Lcom/ss/android/ugc/aweme/feed/model/Aweme;" &&
                    reference.name == "isAd" &&
                    reference.parameterTypes.isEmpty() &&
                    reference.returnType == "Z"
            } == true
        }

        readsPayloadList && readsPayloadSource && checksNativeAdState
    },
)

internal fun Method.countColdStartFeedItemListStores(): Int =
    implementation?.instructions?.count {
        it.opcode == com.android.tools.smali.dexlib2.Opcode.SPUT_OBJECT &&
            it.getReference<FieldReference>()?.type ==
            "Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;"
    } ?: 0

/**
 * The golden hit-cache method: one of the cold start's five stores, the cold start itself being a
 * method of its own (47.0.3's golden method ran it, with three of four stores).
 */
internal object ColdStartGoldenCacheFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = emptyList(),
    strings = listOf("processGoldenVideoHitCache hitCache , time cost ").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> method.countColdStartFeedItemListStores() in 1..4 },
)

/** The call descriptor of a cold-start cache method, as an invoke site names it. */
internal fun Method.coldStartCall(): String =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/**
 * The methods that call both the golden and the offline cache method, those two aside: the cold
 * start's orchestrator (X/04iE.LJI on 47.1.3). [calls] lists each candidate's static call targets.
 */
internal fun <M : Method> coldStartOrchestrators(golden: Method, offline: Method, calls: Map<M, Set<String>>): List<M> {
    val wanted = setOf(golden.coldStartCall(), offline.coldStartCall())
    return calls.filter { (method, targets) ->
        targets.containsAll(wanted) && method.coldStartCall() !in wanted
    }.keys.toList()
}

/** The cold start's FeedItemList stores: the orchestrator's, the golden method's and the offline method's. */
internal const val COLD_START_STORES = 5

internal object ColdStartOfflineCacheFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = emptyList(),
    strings = listOf("processOfflineVideoHitCache error").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> method.countColdStartFeedItemListStores() in 1..4 },
)

/** Names TikTok's cache-result data class without depending on its R8 descriptor. */
internal object CacheResultClassFingerprint : Fingerprint(
    name = "toString",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    strings = listOf("CacheLoadResult(success=").map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * Newer cache providers pass every result through this normalizer before a callback sees it.
 * The result and helper descriptors move with R8; Aweme's survey cleanup method does not.
 */
internal object CacheResultNormalizerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    custom = custom@{ method, _ ->
        if (method.parameterTypes.size != 1) return@custom false

        method.implementation?.instructions?.count { instruction ->
            instruction.getReference<MethodReference>()?.let { reference ->
                reference.definingClass == AWEME_DESCRIPTOR &&
                    reference.name == "clearSurveyInfoForCacheConsume" &&
                    reference.parameterTypes.isEmpty() &&
                    reference.returnType == "V"
            } == true
        } == 2
    },
)

internal object CacheChainDeliveryFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    custom = custom@{ method, classDef ->
        if (method.parameterTypes.size != 1 || classDef.fields.count() != 2) return@custom false

        val resultType = method.parameterTypes.single().toString()
        val instructions = method.implementation?.instructions ?: return@custom false
        val fieldReferences = instructions.mapNotNull { it.getReference<FieldReference>() }
        val methodReferences = instructions.mapNotNull { it.getReference<MethodReference>() }
        val ownFieldTypes = classDef.fields.map { it.type }.toSet()

        val readsResultPayload = fieldReferences.any { reference ->
            reference.definingClass == resultType &&
                reference.type.startsWith("L") &&
                reference.type != "Ljava/lang/String;"
        }
        val followsLinkedCacheSource = fieldReferences.any { reference ->
            reference.definingClass in ownFieldTypes && reference.type == reference.definingClass
        }
        val dispatchesNextCacheSource = methodReferences.any { reference ->
            reference.returnType == "V" &&
                reference.parameterTypes.size == 2 &&
                reference.parameterTypes.all { it.toString() in ownFieldTypes }
        }
        val launchesCoroutine = methodReferences.any { reference ->
            reference.parameterTypes.any { it.toString() == "Lkotlin/jvm/functions/Function2;" }
        }

        readsResultPayload && followsLinkedCacheSource &&
            dispatchesNextCacheSource && launchesCoroutine
    },
)

internal object InsertCacheWhenPlayLagFingerprint : Fingerprint(
    definingClass = INSERT_CACHE_WHEN_PLAY_LAG_COMPONENT_DESCRIPTOR,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(AWEME_DESCRIPTOR, "I", "Z"),
    strings = listOf("middle_insert_when_video_lagging").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ ->
        val references = method.implementation?.instructions
            ?.mapNotNull { it.getReference<MethodReference>() }
            ?: emptyList()

        references.any { reference ->
            reference.definingClass.endsWith("/AwemeBizOptExtKt;") &&
                reference.name == "setAwemeCacheType" &&
                reference.parameterTypes == listOf(AWEME_DESCRIPTOR, "I")
        } && references.any { reference ->
            reference.definingClass.endsWith("/DataLayerAbility;") &&
                reference.parameterTypes.firstOrNull()?.toString() == AWEME_DESCRIPTOR
        }
    },
)

internal object ReachBottomCacheDeliveryFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    strings = listOf(
        "golden_house",
        "offline_video_useout",
        "golden_insert",
    ).map { string(it, StringComparisonType.CONTAINS) },
    filters = listOf(
        methodCall(
            definingClass = "/IFeedPanelPlatformAbility;",
            parameters = listOf(
                "I",
                "Ljava/lang/String;",
                "Ljava/util/List;",
            ),
        ),
    ),
    custom = custom@{ method, classDef ->
        if (method.parameterTypes.size != 1) return@custom false
        if (classDef.fields.none { it.type == REACH_BOTTOM_CACHE_COMPONENT_DESCRIPTOR }) {
            return@custom false
        }

        val references = method.implementation?.instructions
            ?.mapNotNull { it.getReference<MethodReference>() }
            ?: return@custom false
        references.any { reference ->
            reference.definingClass.endsWith("/DataLayerAbility;") &&
                reference.parameterTypes.firstOrNull()?.toString() == AWEME_DESCRIPTOR
        } && references.any { reference ->
            reference.definingClass.endsWith("/IFeedPanelPlatformAbility;") &&
                reference.parameterTypes == listOf(
                    "I",
                    "Ljava/lang/String;",
                    "Ljava/util/List;",
                )
        }
    },
)

private const val PROFILE_AWEME_LIST_FRAGMENT_DESCRIPTOR =
    "Lcom/ss/android/ugc/profile/business/profile/tab/AwemeListFragmentImpl;"
private const val DETAIL_FRAGMENT_DESCRIPTOR =
    "Lcom/ss/android/ugc/aweme/detail/ui/DetailFragment;"
private const val DETAIL_PAGE_ABILITY_DESCRIPTOR =
    "Lcom/ss/android/ugc/aweme/detail/platform/IDetailPageAbility;"

internal object ProfileRefreshResultFingerprint : Fingerprint(
    definingClass = PROFILE_AWEME_LIST_FRAGMENT_DESCRIPTOR,
    returnType = "V",
    parameters = listOf("Ljava/util/List;", "Z"),
    strings = listOf("onRefreshResult: type=").map { string(it, StringComparisonType.CONTAINS) },
)

internal object ProfileLoadMoreResultFingerprint : Fingerprint(
    definingClass = PROFILE_AWEME_LIST_FRAGMENT_DESCRIPTOR,
    returnType = "V",
    parameters = listOf("Ljava/util/List;", "Z"),
    strings = listOf("onLoadMoreResult: type=").map { string(it, StringComparisonType.CONTAINS) },
)

internal object ProfileLoadLatestResultFingerprint : Fingerprint(
    definingClass = PROFILE_AWEME_LIST_FRAGMENT_DESCRIPTOR,
    returnType = "V",
    parameters = listOf("Ljava/util/List;", "Z"),
    strings = listOf("onLoadLatestResult: type=").map { string(it, StringComparisonType.CONTAINS) },
)

internal object ProfileDetailAdEventFingerprint : Fingerprint(
    definingClass = DETAIL_FRAGMENT_DESCRIPTOR,
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    custom = custom@{ method, _ ->
        if (method.parameterTypes.size != 1) return@custom false

        val eventType = method.parameterTypes.single().toString()
        val instructions = method.implementation?.instructions ?: return@custom false
        val readsListFromEvent = instructions.any { instruction ->
            instruction.getReference<FieldReference>()?.let { reference ->
                instruction.opcode == com.android.tools.smali.dexlib2.Opcode.IGET_OBJECT &&
                    reference.definingClass == eventType &&
                    reference.type == "Ljava/util/List;"
            } == true
        }
        val forwardsListToDetailPager = instructions.any { instruction ->
            instruction.getReference<MethodReference>()?.let { reference ->
                reference.definingClass == DETAIL_PAGE_ABILITY_DESCRIPTOR &&
                    reference.parameterTypes == listOf("Ljava/util/List;") &&
                    reference.returnType == "V"
            } == true
        }

        readsListFromEvent && forwardsListToDetailPager
    },
)

private const val MID_AD_COMPONENT_DESCRIPTOR =
    "Lcom/ss/android/ugc/feed/platform/panel/midad/MidAdComponent;"

/**
 * The mid-roll ad component's splice. Given the video on screen and an ad, it finds the video
 * in the pager adapter and puts the ad in its place, logging {@code midroll_ads_show} first.
 * This is the route issue #2 was about: it runs after every list the other feed hooks see, so
 * an ad reached the profile pager while the profile list carried 184 videos with 0 removed.
 *
 * <p>Static on every retained build, with the same five parameters (the video, the ad, a flag,
 * the component and the adapter) and the same event string; the log strings beside it are
 * stripped after 46.2.3, and the method's own name changes on every build, so neither is used.
 */
internal object MidAdReplaceFingerprint : Fingerprint(
    definingClass = MID_AD_COMPONENT_DESCRIPTOR,
    returnType = "V",
    strings = listOf("midroll_ads_show").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ ->
        AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.parameterTypes.size == 5 &&
            method.parameterTypes[0].toString() == AWEME_DESCRIPTOR &&
            method.parameterTypes[1].toString() == AWEME_DESCRIPTOR &&
            method.parameterTypes[2].toString() == "Z"
    },
)

/** Where the mid-roll ad component comes to life, so the export carries its family on every run. */
internal object MidAdComponentCreateFingerprint : Fingerprint(
    definingClass = MID_AD_COMPONENT_DESCRIPTOR,
    name = "onCreate",
    returnType = "V",
    parameters = listOf(),
)

private const val USER_DESCRIPTOR = "Lcom/ss/android/ugc/aweme/profile/model/User;"

/** The response of the profile pager's own ad request, a real name on every retained build. */
internal const val PROFILE_AD_RESPONSE_DESCRIPTOR =
    "Lcom/ss/android/ugc/aweme/commercialize/profile/impl/ad/CommerceProfileAdResponse;"

/**
 * TikTok's own answer to whether a creator's video pager should ask for ads: it reads the
 * `profile_ad_experiment` value and, for some values, the creator's friend match and follow
 * status. On 46.2.3 its only callers are the profile ad module's two request gates, one for
 * each value of the module's `profileAdVersion`.
 *
 * <p>Static `(User)Z` with that string on 46.2.3 through 46.9.3 (`LX/0lk2;->LIZ` on 46.2.3). The
 * only other method carrying the string registers the setting and takes nothing. No access
 * flags: the patcher compares them exactly, and the static flag is checked here instead.
 */
internal object ProfileAdEligibilityFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(USER_DESCRIPTOR),
    strings = listOf("profile_ad_experiment").map { string(it, StringComparisonType.CONTAINS) },
    filters = listOf(
        methodCall(
            definingClass = USER_DESCRIPTOR,
            name = "getFollowStatus",
            returnType = "I",
        ),
    ),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)

/**
 * Every bottom banner under a feed video reaches its cell through this getter: the Tako "Ask"
 * banner, the "Search" one, series and drama bars. Named, and without access flags for the
 * reason [FollowFeedListGetItemsFingerprint] gives.
 */
internal object AwemeBannersFingerprint : Fingerprint(
    definingClass = AWEME_DESCRIPTOR,
    name = "getBanners",
    returnType = "Ljava/util/List;",
    parameters = emptyList(),
)

/**
 * The search page's own result list. It arrives parsed, then this method walks its items to
 * stamp the request id on each, which makes it the one place every result passes through
 * before the grid sees it. The field it walks is read directly by forty other methods, so
 * there is nothing later to hook.
 */
internal object SearchResultRequestIdFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/search/pages/result/topsearch/core/model/SearchMixFeedList;",
    name = "setRequestId",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
)

/**
 * Binds a server-drawn Lynx card into its row of the search results, TikTok's Short Drama block
 * among them. TikTok 47.0.3 streams the Top results in chunks and builds these cards from a
 * chunk's patches, so they never pass through the result list [SearchResultRequestIdFingerprint]
 * filters. The cell keeps its real name on every fixture, and so does its bind.
 */
internal object SearchLynxCardBindFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/search/lynx/core/ui/component/SearchLynxCardCell;",
    name = "onBindItemView",
    returnType = "V",
)

internal const val SEARCH_MIX_FEED_DESCRIPTOR = "Lcom/ss/android/ugc/aweme/search/pages/result/topsearch/core/model/SearchMixFeed;"
internal const val DYNAMIC_PATCH_DESCRIPTOR = "Lcom/ss/android/ugc/aweme/discover/mixfeed/DynamicPatch;"
internal const val LYNX_SSR_INFO_DESCRIPTOR = "Lcom/ss/android/ugc/aweme/search/pages/result/topsearch/core/model/LynxSSRInfo;"

/**
 * The Top results list's own Lynx card holder binding a card's data into its row (LX/0J8X;->P5
 * on 47.0.3, called by the results adapter's bind). The Short Drama block is bound here: TikTok
 * builds it from a streamed chunk's patches and slots it into the adapter, past the result list
 * [SearchResultRequestIdFingerprint] filters. Named by the real types among its ten parameters:
 * the DynamicPatch second, the Lynx server-render info fifth and the card itself ninth.
 */
internal object SearchLynxHolderBindFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        val parameters = method.parameterTypes.map { it.toString() }
        parameters.size == 10 &&
            parameters[1] == DYNAMIC_PATCH_DESCRIPTOR &&
            parameters[4] == LYNX_SSR_INFO_DESCRIPTOR &&
            parameters[8] == SEARCH_MIX_FEED_DESCRIPTOR
    },
)

/**
 * The results adapter's other Lynx card holder. The adapter's bind hands a card to this one
 * instead of [SearchLynxHolderBindFingerprint]'s when TikTok routes it here: feed type 996 always,
 * and feed type 65514 when its template is on the server's `dynamic_new_arch_white_list` (empty by
 * default). DynamicViewHolder keeps its real name on every fixture; its bind takes eight
 * parameters, the fragment first, the DynamicPatch second and the Lynx server-render info fifth.
 */
internal object SearchDynamicHolderBindFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/search/lynx/core/ui/viewholder/DynamicViewHolder;",
    returnType = "V",
    custom = { method, _ ->
        val parameters = method.parameterTypes.map { it.toString() }
        parameters.size == 8 &&
            parameters[1] == DYNAMIC_PATCH_DESCRIPTOR &&
            parameters[4] == LYNX_SSR_INFO_DESCRIPTOR
    },
)

/**
 * The Friends tab is its own feed and never arrives as a FeedItemList. Its response carries
 * FriendsFeed wrappers in a real named `friendFeedData` field, and `setRequestId` on that
 * class, which is what the search grid is hooked on, has no callers at all in 46.2.3.
 *
 * Three places are hooked because no single one covers it. The all-arguments constructor
 * catches a response the app builds itself, and the no-argument one delegates to it. It does
 * not catch a response off the wire: gson calls the constructor with defaults and then writes
 * the fields reflectively, so the list is still empty when the constructor returns. For that,
 * the getter and the success callback below run once the fields are populated.
 */
internal object FriendsFeedResponseFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/friendstab/api/FriendsFeedResponse;",
    name = "<init>",
    returnType = "V",
    parameters = listOf(
        "I", "Z", "Ljava/util/List;", "Ljava/lang/String;", "Ljava/lang/String;",
        "Lcom/ss/android/ugc/aweme/feed/model/LogPbBean;", "I", "Ljava/util/List;",
        "Ljava/util/List;", "Ljava/lang/String;",
    ),
)

/** Walks `friendFeedData` itself, so the list is populated by the time it runs. */
internal object FriendsFeedAwemeListFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/friendstab/api/FriendsFeedResponse;",
    name = "getAwemeList",
    returnType = "Ljava/util/List;",
    parameters = emptyList(),
)

/**
 * Where a fetched response is handed to the tab. The class is obfuscated, but the callback
 * kept its name and the body names the response's own field, which is what identifies it.
 */
internal object FriendsFeedSuccessFingerprint : Fingerprint(
    name = "onSuccess",
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.getReference<FieldReference>()?.let { reference ->
                reference.definingClass ==
                    "Lcom/ss/android/ugc/aweme/friendstab/api/FriendsFeedResponse;" &&
                    reference.name == "friendFeedData"
            } == true
        } == true
    },
)

/**
 * The Friends tab's Reposts list, a feed of its own since 47.4.3 dropped the V3 Friends feed.
 * Gson fills `repostFeedData` after the constructor, and every reader outside the data class
 * goes through this getter, so it sees the list populated.
 */
internal object RepostFeedDataFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/repostfeed/model/RepostFeedResponse;",
    name = "getRepostFeedData",
    returnType = "Ljava/util/List;",
    parameters = emptyList(),
)

internal object TakoAiFeedButtonSetVisibleFingerprint : Fingerprint(
    definingClass = "/feed/assem/tikbot/TakoAssem;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Z"),
    strings = listOf("right_container_chat_gpt").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ ->
        val references = method.implementation?.instructions
            ?.mapNotNull { it.getReference<MethodReference>() }
            ?: emptyList()
        references.any { reference ->
            reference.definingClass == "Landroid/view/View;" &&
                reference.name == "getVisibility" &&
                reference.parameterTypes.isEmpty() &&
                reference.returnType == "I"
        } && references.any { reference ->
            reference.definingClass == "Landroid/view/View;" &&
                reference.name == "isShown" &&
                reference.parameterTypes.isEmpty() &&
                reference.returnType == "Z"
        }
    },
)

/**
 * The bind of the Tako "Ask" strip under a video's caption, a bottom slot on the video cell
 * (issue #6). Named {@code hs} on 46.2.3 and {@code onBind} on the three later builds, so it is
 * found by what it does: it is the one single-Object method on the class that casts its
 * argument to the video's item params before reading the video.
 */
internal object TakoAskBarBindFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/tako/detail/keyframe/ui/TakoDetailKeyFrameBottomAssemAssem;",
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.opcode == com.android.tools.smali.dexlib2.Opcode.CHECK_CAST &&
                instruction.getReference<com.android.tools.smali.dexlib2.iface.reference.TypeReference>()
                    ?.type == "Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;"
        } == true
    },
)

/**
 * The trigger component for the same slot. On some accounts TikTok draws the ask bar through
 * this trigger instead of (or alongside) the slot component. A reporter's export on 0.39.0
 * showed the slot hook never firing while the bar still appeared: the trigger was the one
 * that ran. Its {@code hs} is a bridge into {@code Sp(VideoItemParams)}, which gets the
 * content view and registers a show callback, so hiding the view at the top of the trigger's
 * bind covers both paths.
 *
 * <p>Matched by its defining class and by the {@code CHECK_CAST} to {@code VideoItemParams}
 * inside its body, the same way the slot fingerprint works.
 */
internal object TakoAskBarTriggerBindFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/tako/detail/keyframe/ui/TakoDetailKeyFrameBottomTrigger;",
    returnType = "V",
    parameters = listOf("Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;"),
    custom = { method, _ ->
        // The name changes on every build (Sp, mr, yr, Kr), but it is always the sole
        // (VideoItemParams)V method that is not the boolean variant (Wp, ur, Dr, Qr). Both
        // sit on the same class with the same parameter; the return type separates them.
        !com.android.tools.smali.dexlib2.AccessFlags.ABSTRACT.isSet(method.accessFlags)
    },
)

/**
 * The feed-level Tako trigger, in the tikbot package rather than the detail keyframe package.
 * A reporter on 0.39.0 still saw the ask bar while the detail-page hook never fired, so the
 * bar was drawn through a different component entirely. This trigger is the one that binds per
 * video on the main scrolling feed and registers the callback that makes the ask bar visible.
 * Same structural pattern as the detail trigger: one {@code (VideoItemParams)V} method, one
 * {@code (VideoItemParams)Z} method, both with names that change on every build.
 */
internal object TakoFeedTriggerBindFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/assem/tikbot/TakoTrigger;",
    returnType = "V",
    parameters = listOf("Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;"),
    custom = { method, _ ->
        !com.android.tools.smali.dexlib2.AccessFlags.ABSTRACT.isSet(method.accessFlags)
    },
)

/**
 * The roof variant of the feed-level Tako trigger, which sits above the normal trigger and
 * covers the same slot from the "roof" layout position. Same (VideoItemParams)V pattern.
 */
internal object TakoFeedTriggerRoofBindFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/assem/tikbot/TakoTriggerRoof;",
    returnType = "V",
    parameters = listOf("Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;"),
    custom = { method, _ ->
        !com.android.tools.smali.dexlib2.AccessFlags.ABSTRACT.isSet(method.accessFlags)
    },
)

private const val TAKO_RIGHT_BOTTOM_ENTRANCE = "Lcom/ss/android/ugc/aweme/tako/otherpage/feed/mainentrance/ui/"

/**
 * The Tako bubble at the bottom right of a feed video (discussion #115), a component of its own
 * in the Tako package rather than the tikbot button or the ask bar. TikTok draws it from one of
 * two subclasses of a shared base: the fixed one that stays put while the pager scrolls and the
 * one inside each cell. Each binds through a single {@code (VideoItemParams)V} method that only
 * tells the entrance's view model whether to show (Mr on 47.1.4), and each launches the
 * coroutine that applies that answer from {@code onViewCreated}, right after the base lays out.
 */
internal object TakoRightBottomFixedEntranceBindFingerprint : Fingerprint(
    definingClass = "${TAKO_RIGHT_BOTTOM_ENTRANCE}TakoRightBottomFixedEntrance;",
    returnType = "V",
    parameters = listOf("Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;"),
)

internal object TakoRightBottomInCellEntranceBindFingerprint : Fingerprint(
    definingClass = "${TAKO_RIGHT_BOTTOM_ENTRANCE}TakoRightBottomInCellEntrance;",
    returnType = "V",
    parameters = listOf("Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;"),
)

internal object TakoRightBottomFixedEntranceCreatedFingerprint : Fingerprint(
    definingClass = "${TAKO_RIGHT_BOTTOM_ENTRANCE}TakoRightBottomFixedEntrance;",
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
)

internal object TakoRightBottomInCellEntranceCreatedFingerprint : Fingerprint(
    definingClass = "${TAKO_RIGHT_BOTTOM_ENTRANCE}TakoRightBottomInCellEntrance;",
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
)

/**
 * The two Tako entrances on the search page, the one opened by the magnifier on the feed
 * (issue #22). TikTok serves either a lone "Ask Tako" bubble or a "Voice | Ask Tako" pill above
 * the keyboard, one class each, both under a base whose {@code onViewCreated} inflates a
 * ViewStub through a single {@code (ViewStub)View} method and skips every later step when that
 * answers null. The lone bubble's own inflater already answers null with TikTok's gate off, so
 * null is the app's own way of saying there is no entrance. The method is named Rp, lr, yr, Qr
 * and Mr across the five retained builds, so it is found by its shape.
 */
internal val takoSearchEntranceVariants = listOf(
    "Lcom/ss/android/ugc/aweme/search/pages/middlepage/takoentrance/ui/TakoSingleRoundButtonAssem;",
    "Lcom/ss/android/ugc/aweme/search/pages/middlepage/takoentrance/ui/MixedRoundFloatingButtonAssem;",
)

internal fun isTakoSearchEntranceInflater(method: com.android.tools.smali.dexlib2.iface.Method) =
    method.definingClass in takoSearchEntranceVariants &&
        method.returnType == "Landroid/view/View;" &&
        method.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/ViewStub;") &&
        method.implementation != null &&
        !com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(method.accessFlags)

internal object TakoSearchBubbleInflateFingerprint : Fingerprint(
    definingClass = takoSearchEntranceVariants[0],
    returnType = "Landroid/view/View;",
    parameters = listOf("Landroid/view/ViewStub;"),
    custom = { method, _ -> isTakoSearchEntranceInflater(method) },
)

internal object TakoSearchPillInflateFingerprint : Fingerprint(
    definingClass = takoSearchEntranceVariants[1],
    returnType = "Landroid/view/View;",
    parameters = listOf("Landroid/view/ViewStub;"),
    custom = { method, _ -> isTakoSearchEntranceInflater(method) },
)

/**
 * The Tako bar inside the comments sheet, the "related words" strip TikTok draws above the
 * comment list. It is one of the sheet's server-driven top bar components, served under biz
 * type SEARCH_TAKO (this service, in the Tako package) or SEARCH_TAKO_BG (the commentv2 bridge
 * below, which hands the same Tako service its questions). The header resolver asks the
 * service `canShow` before it builds the component and treats false as a business condition
 * not met, which is the answer the guard gives. The first and last parameters are R8 types that
 * change on every build; the three in the middle are real named on all five retained builds.
 */
internal const val TAKO_COMMENT_TOP_BAR_SERVICE =
    "Lcom/ss/android/ugc/aweme/tako/detail/related/TakoCommentTopBarServiceImpl;"
internal const val COMMENT_TOP_BAR_BRIDGE_BASE =
    "Lcom/ss/android/ugc/aweme/commentv2/headeranchor/common/bridge/BaseTopBarBridgeServiceImpl;"
internal const val TAKO_COMMENT_TOP_BAR_BRIDGE =
    "Lcom/ss/android/ugc/aweme/commentv2/headeranchor/common/bridge/BgTakoTopBarServiceImpl;"

internal fun isCommentTopBarCanShow(method: com.android.tools.smali.dexlib2.iface.Method): Boolean {
    val parameters = method.parameterTypes.map(CharSequence::toString)
    return method.name == "canShow" &&
        method.returnType == "Z" &&
        method.implementation != null &&
        parameters.size == 5 &&
        parameters[1] == "Lcom/ss/android/ugc/aweme/feed/model/CommentTopBarComponent;" &&
        parameters[2] == "Lcom/ss/android/ugc/aweme/feed/model/Aweme;" &&
        parameters[3] == "Ljava/lang/String;"
}

internal object TakoCommentTopBarCanShowFingerprint : Fingerprint(
    definingClass = TAKO_COMMENT_TOP_BAR_SERVICE,
    name = "canShow",
    returnType = "Z",
    custom = { method, _ -> isCommentTopBarCanShow(method) },
)

/**
 * The bridge base serves nine commentv2 bridges (ads, shop, POI, search, activity and the
 * rest), so its `canShow` is guarded with the service itself handed over and the extension
 * answers only for the Tako bridge, whose class name the fingerprint below pins.
 */
internal object CommentTopBarBridgeCanShowFingerprint : Fingerprint(
    definingClass = COMMENT_TOP_BAR_BRIDGE_BASE,
    name = "canShow",
    returnType = "Z",
    custom = { method, _ -> isCommentTopBarCanShow(method) },
)

internal object TakoCommentTopBarBridgeFingerprint : Fingerprint(
    definingClass = TAKO_COMMENT_TOP_BAR_BRIDGE,
    name = "bridgeTopBar",
    parameters = emptyList(),
)

internal const val VISUAL_SEARCH_SERVICE =
    "Lcom/ss/android/ugc/aweme/search/df/api/visualsearch/ISearchVisualSearchService;"
internal const val SMART_SEARCH_INFO = "Lcom/ss/android/ugc/aweme/feed/model/search/SmartSearchInfoStruct;"
private const val POST_MODE_ENTRANCE = "Lcom/ss/android/ugc/aweme/model/PostModeEntranceMechanism;"

/**
 * Whether the instruction hands a caption tap to TikTok's search panel: the visual search
 * service, real named, asked with the video's smart search info and answering whether the panel
 * opened. The method's own name is R8's, so the call is known by its owner and its types.
 */
internal fun Instruction.opensCaptionSearchPanel(): Boolean =
    (opcode == Opcode.INVOKE_INTERFACE || opcode == Opcode.INVOKE_INTERFACE_RANGE) &&
        getReference<MethodReference>()?.let { call ->
            call.definingClass == VISUAL_SEARCH_SERVICE && call.returnType == "Z" &&
                call.parameterTypes.any { it.toString() == SMART_SEARCH_INFO }
        } == true

/** Each instruction's code address, with one past the end. */
internal fun List<Instruction>.codeAddresses(): IntArray {
    val addresses = IntArray(size + 1)
    for (index in indices) addresses[index + 1] = addresses[index] + this[index].codeUnits
    return addresses
}

/** The index a branch at [index] lands on, or null when it lands between instructions. */
internal fun List<Instruction>.branchTarget(index: Int, addresses: IntArray = codeAddresses()): Int? {
    val target = addresses[index] + (this[index] as OffsetInstruction).codeOffset
    return addresses.indexOfFirst { it == target }.takeIf { it in indices }
}

/**
 * Issue #162. The index of the move that holds the gate the caption's expand asks before it
 * hands the tap to the search panel (the one with "Discover more with Tako" and "Ask Tako"):
 * the nearest static no-argument boolean before the panel's single call, whose answer goes
 * straight into an if-eqz that jumps past that call, to where the caption expands in place.
 * Null when the method isn't that shape.
 */
internal fun Method.captionSearchPanelGate(): Int? {
    val instructions = implementation?.instructions?.toList() ?: return null
    val open = instructions.indices.filter { instructions[it].opensCaptionSearchPanel() }.singleOrNull() ?: return null
    val addresses = instructions.codeAddresses()
    for (ask in open - 1 downTo 0) {
        val gate = instructions[ask]
        if (gate.opcode != Opcode.INVOKE_STATIC && gate.opcode != Opcode.INVOKE_STATIC_RANGE) continue
        val call = gate.getReference<MethodReference>() ?: continue
        if (call.returnType != "Z" || call.parameterTypes.isNotEmpty()) continue
        val move = instructions.getOrNull(ask + 1) ?: continue
        val branch = instructions.getOrNull(ask + 2) ?: continue
        if (move.opcode != Opcode.MOVE_RESULT || branch.opcode != Opcode.IF_EQZ) continue
        if ((move as OneRegisterInstruction).registerA != (branch as OneRegisterInstruction).registerA) continue
        val skip = instructions.branchTarget(ask + 2, addresses) ?: continue
        if (skip > open) return ask + 1
    }
    return null
}

/**
 * The feed caption's expand and collapse, the same method Comment tools hands to the comments:
 * the one method taking a boolean, a string and the post page's entrance enum in that order,
 * here also asking a gate before the caption's search panel.
 */
internal object CaptionSearchPanelFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Z", "Ljava/lang/String;", POST_MODE_ENTRANCE),
    custom = { method, _ -> method.captionSearchPanelGate() != null },
)

internal const val TITLE_PANEL_STYLE = "Lcom/ss/android/ugc/aweme/experiment/TitlePanelStyle;"

/**
 * Issue #162 follow-up. Whether the title panel helper treats a video's caption as a smart
 * search one: a public static (Aweme, String, boolean)boolean in the class that reads
 * TitlePanelStyle's DESC_AND_SEARCH and DESC_AND_COMMENT_AND_SEARCH, which asks that class's own
 * static (Aweme)boolean (whether the video can show the search entrance at all). The caption
 * builder calls it with "this video has smart search info" as the boolean and, on a yes, always
 * draws the "more" tail that leads into the search panel, whether the caption fits or not. Its
 * sibling with the same parameters asks a (Aweme, boolean)boolean instead, which is what keeps
 * the two apart.
 */
internal fun Method.asksSmartSearchCaption(classDef: ClassDef): Boolean {
    val own = implementation?.instructions?.toList() ?: return false
    val asksOwnGate = own.any { instruction ->
        (instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE) &&
            instruction.getReference<MethodReference>()?.let { call ->
                call.definingClass == definingClass && call.returnType == "Z" &&
                    call.parameterTypes.map(CharSequence::toString) == listOf(AWEME_DESCRIPTOR)
            } == true
    }
    if (!asksOwnGate) return false
    val styles = mutableSetOf<String>()
    for (sibling in classDef.methods) {
        val body = sibling.implementation ?: continue
        for (instruction in body.instructions) {
            val field = instruction.getReference<FieldReference>() ?: continue
            if (field.definingClass == TITLE_PANEL_STYLE) styles.add(field.name)
        }
    }
    return "DESC_AND_SEARCH" in styles && "DESC_AND_COMMENT_AND_SEARCH" in styles
}

internal object SmartSearchCaptionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf(AWEME_DESCRIPTOR, "Ljava/lang/String;", "Z"),
    custom = { method, classDef -> method.asksSmartSearchCaption(classDef) },
)

internal const val SEARCH_DYNAMIC_TAB_LIST_DESCRIPTOR =
    "Lcom/ss/android/ugc/aweme/search/pages/result/common/tabs/core/model/SearchDynamicTabList;"

/** Whether the instruction reads the served tab list off the response: the field both getters open with. */
internal fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.isSearchTabListRead(): Boolean =
    opcode == com.android.tools.smali.dexlib2.Opcode.IGET_OBJECT &&
        getReference<FieldReference>()?.let { field ->
            field.definingClass == SEARCH_DYNAMIC_TAB_LIST_DESCRIPTOR &&
                field.name == "tabList" && field.type == "Ljava/util/List;"
        } == true

/**
 * The search results tab strip (Ask Tako, Top, Users, Videos, ...) is served as a list of dynamic
 * tab infos on this response, and the strip's view model is its only consumer, reading it through
 * these two getters: the plain one, and the one that keeps the keys the app's tab registry knows.
 * Both are real names on a real-named class, and each opens with one read of the field, where a
 * Tako tab would be dropped from what every consumer sees. On 47.0.3 the Ask Tako pill is not in
 * this list (the strip's keys are general, user, video, shop, live, music, place, photos and
 * hashtag) but a view of its own, hidden through the fragment below; the filter at these reads is
 * the guard for a build that serves it as data, and the extension records the keys it meets.
 */
internal object SearchDynamicTabListGetTabListFingerprint : Fingerprint(
    definingClass = SEARCH_DYNAMIC_TAB_LIST_DESCRIPTOR,
    name = "getTabList",
    returnType = "Ljava/util/List;",
    parameters = emptyList(),
)

internal object SearchDynamicTabListGetSearchTabListFingerprint : Fingerprint(
    definingClass = SEARCH_DYNAMIC_TAB_LIST_DESCRIPTOR,
    name = "getSearchTabList",
    returnType = "Ljava/util/List;",
    parameters = emptyList(),
)

/**
 * The fragment that inflates the search results tab strip, with the Ask Tako pill at its head when
 * TikTok's own Tako gate allows it: a text view keeping the real id name tv_tab_tako_entrance, in a
 * clickable pill, in a frame, in a column beside the tab row, all styled by an R8-named helper the
 * fragment calls before it returns. Real-named class and method, so the extension is handed the
 * view at every return and finds the pill by that id name.
 */
internal object SearchContainerFragmentOnViewCreatedFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/search/pages/core/ui/fragment/SearchContainerFragment;",
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "Landroid/os/Bundle;"),
)

/**
 * The "People also ask Tako" card in the search results on 47.4.3, a native card (not Lynx) with a
 * Tako header over a list of questions, in a full-width row between the video rows. Its slot
 * component finds the header and the question list in {@code onViewCreated}; real-named class and
 * method, so the extension is handed the view at every return.
 */
internal object SearchTakoSugCardCreatedFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/search/pages/result/topsearch/tako/SearchTakoSugCardAssem;",
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
)

internal object FollowFeedPresenterPostProcessFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Lcom/ss/android/ugc/aweme/follow/presenter/FollowFeedList;"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/ss/android/ugc/aweme/follow/presenter/FollowFeedList;",
            name = "setInsertedResults",
        ),
    ),
    custom = { method, _ ->
        val references = method.implementation?.instructions
            ?.mapNotNull { it.getReference<MethodReference>() }
            ?: emptyList()
        references.any {
            it.definingClass == "Lcom/ss/android/ugc/aweme/feed/model/Aweme;" &&
                it.name == "isAd"
        } && references.any {
            it.definingClass == "Lcom/ss/android/ugc/aweme/follow/presenter/FollowFeedList;" &&
                it.name == "setItems"
        } && references.any {
            it.definingClass == "Lcom/ss/android/ugc/aweme/follow/presenter/FollowFeedList;" &&
                it.name == "setInsertedResults"
        }
    },
)

internal object TakoAiFeedButtonBindFingerprint : Fingerprint(
    definingClass = "/feed/assem/tikbot/TakoAssem;",
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
)


/** The playlist bar along the bottom of a video that belongs to a series. */
internal object PlaylistBottomBarAvailableFingerprint : Fingerprint(
    definingClass = "/InteractPlayListBottomBarAssem;",
    returnType = "Z",
    parameters = listOf("Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;"),
)

/** Answers whether an item is a short drama advert that locks scrolling until it ends. */
internal object DramaBlockingAdFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/impl/DramaBlockingAdServiceImpl;",
    returnType = "Z",
    parameters = listOf("Lcom/ss/android/ugc/aweme/feed/model/Aweme;"),
)

/** Attaches the floating promotional event badge to the feed. */
internal object SpecActTouchpointAttachFingerprint : Fingerprint(
    definingClass = "/specact/SpecActServiceImpl;",
    returnType = "V",
    parameters = listOf("Landroid/view/ViewGroup;", "Landroidx/fragment/app/Fragment;"),
)

private val REC_USER_CARD_PARAMETERS =
    listOf("I", "Ljava/util/List;", "Ljava/lang/String;", "Lkotlin/jvm/functions/Function0;").sorted()

/** The semantic method shape survives R8 reordering its List and String parameters. */
internal fun Method.isRecUserCardInsertion() =
    parameterTypes.map(CharSequence::toString).sorted() == REC_USER_CARD_PARAMETERS

/** Builds the friend recommendation card; a null result is the app's own "nothing to insert". */
internal object RecUserCardInsertFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Lkotlin/Pair;",
    strings = listOf("friend_recommend_card").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> method.isRecUserCardInsertion() },
)

/**
 * The Lemon8 card handler's request builder, a LIZJ(handler, FeedCardInsertData, List) that
 * answers a list of card type requests. Every card-insert handler has this shape, and the one
 * that keeps the not-interested time of the Lemon8 big card is the only one with this key.
 */
internal object Lemon8CardRequestFingerprint : Fingerprint(
    returnType = "Ljava/util/List;",
    parameters = listOf(
        "L",
        "Lcom/ss/android/ugc/feed/platform/cardinsert/data/FeedCardInsertData;",
        "Ljava/util/List;",
    ),
    strings = listOf("fyp_big_card_not_interested_time").map { string(it, StringComparisonType.CONTAINS) },
)

/** Loads the Lynx view behind an inserted card, which happens before any list filter runs. */
internal object FeedLynxCardLoadFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf(
        "Landroid/content/Context;",
        "Ljava/lang/String;",
        "Lcom/ss/android/ugc/aweme/feed/model/Aweme;",
        "Ljava/lang/String;",
        "L",
    ),
    strings = listOf("feedDynamicComponentLoadSuccess").map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * The share guide that pops up after a like, asking the reader to share the video with friends.
 * Named {@code O} on 46.2.3, {@code J} on 46.7.3, {@code H} on 46.8.3, {@code D} on 46.9.3;
 * matched by its defining class, parameter shape and the {@code "share_guide"} string it logs.
 * Upstream #22.
 */
internal object ShareGuideFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/panel/FullFeedFragmentPanel;",
    returnType = "V",
    parameters = listOf("I", "Lcom/ss/android/ugc/aweme/feed/model/Aweme;", "Ljava/lang/String;"),
    strings = listOf("share_guide").map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * The LIVE feed's page handler (issue #57): the draw list, TikTok's vertical pager of LIVE rooms,
 * hands each page of /webcast/feed/ items to this static Kotlin default-argument bridge, which
 * turns them into rooms. {@code LX/0KGC;->LJJLIL} on 47.0.3, {@code LX/0KJ1;->LJJLIIIJLLLLLLLZ} on
 * 47.1.3, {@code LX/0KJ5;} on 47.1.4. A class initializer holds the same two strings, so the
 * shape is pinned too: the provider itself first, then the page and its FeedExtra.
 */
internal object LiveDrawRoomPageFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "L",
        "Ljava/util/List;",
        "Lcom/bytedance/android/live/base/model/feed/FeedExtra;",
        "Ljava/lang/String;",
        "I",
        "Z",
        "Z",
        "Z",
        "Z",
        "I",
    ),
    strings = listOf("following_window_live_cell_loadmore", "tiktok_message_inner_loadmore").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, classDef ->
        AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.parameterTypes.firstOrNull()?.toString() == classDef.type
    },
)
