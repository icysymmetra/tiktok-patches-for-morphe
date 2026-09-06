/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/feedfilter/Fingerprints.kt
 */
package app.morphe.patches.tiktok.feedfilter

import app.morphe.patcher.Fingerprint
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
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

internal object FollowFeedFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lcom/ss/android/ugc/aweme/follow/presenter/FollowFeedList;",
    strings = listOf("feed"),
    custom = { method, _ ->
        method.parameterTypes.size == 2 && method.implementation?.instructions?.any {
            it.getReference<MethodReference>()?.let { reference ->
                reference.definingClass == "Lcom/ss/android/ugc/aweme/follow/presenter/FollowFeedList;" &&
                    reference.name == "getItems"
            } == true
        } == true
    },
)

internal object FollowFeedListGetItemsFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/follow/presenter/FollowFeedList;",
    name = "getItems",
    accessFlags = listOf(AccessFlags.PUBLIC),
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

internal object ColdStartCachedFeedFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = emptyList(),
    strings = listOf(
        "processGoldenVideoHitCache hitCache , time cost ",
        "processOfflineVideoHitCache error",
    ),
    custom = { method, _ ->
        method.implementation?.instructions?.count {
            it.opcode == com.android.tools.smali.dexlib2.Opcode.SPUT_OBJECT &&
                it.getReference<FieldReference>()?.type ==
                "Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;"
        } == 4
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
    strings = listOf("middle_insert_when_video_lagging"),
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
    strings = listOf("onRefreshResult: type="),
)

internal object ProfileLoadMoreResultFingerprint : Fingerprint(
    definingClass = PROFILE_AWEME_LIST_FRAGMENT_DESCRIPTOR,
    returnType = "V",
    parameters = listOf("Ljava/util/List;", "Z"),
    strings = listOf("onLoadMoreResult: type="),
)

internal object ProfileLoadLatestResultFingerprint : Fingerprint(
    definingClass = PROFILE_AWEME_LIST_FRAGMENT_DESCRIPTOR,
    returnType = "V",
    parameters = listOf("Ljava/util/List;", "Z"),
    strings = listOf("onLoadLatestResult: type="),
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

internal object TakoAiFeedButtonSetVisibleFingerprint : Fingerprint(
    definingClass = "/feed/assem/tikbot/TakoAssem;",
    name = "bq",
    returnType = "V",
    parameters = listOf("Z"),
)

internal object FollowFeedPresenterPostProcessFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Lcom/ss/android/ugc/aweme/follow/presenter/FollowFeedList;"),
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
