/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.guardAtEntry

/** The log tag the banner's update writes under, on 47.0.3, 47.1.3 and 47.1.4 alike. */
internal const val GROUP_BANNER_LOG_TAG = "inbox_recommend_new_group_banner"

/**
 * The Inbox's invitation to start a group chat. InboxRecommendGroupBannerAssem keeps its real
 * name, and so does the notice struct it is handed, but the update method is renamed on every
 * build (xM2, sO2), so it is the one instance method of the class that takes that struct and
 * writes the banner's log tag. It posts the banner's state to the Inbox's banner slot: a Pair
 * of INIT and null when the Inbox is built, TOP_SHOW and the banner's data to show it, and
 * DISMISS when the notice is empty. A banner that is never updated stays at INIT.
 */
internal object InboxGroupBannerUpdateFingerprint : Fingerprint(
    definingClass = "/InboxRecommendGroupBannerAssem;",
    returnType = "V",
    parameters = listOf("Lcom/ss/android/ugc/aweme/im/common/model/IMNoticeMsgStruct;"),
    strings = listOf(GROUP_BANNER_LOG_TAG).map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * Puts the group chat prompt switch in front of the banner's update. With the switch on the
 * update returns before it posts anything, and with it off the update runs from its own first
 * instruction. The answer lands in v0, a local on every build (the update has thirteen).
 */
internal fun MutableMethod.hideGroupChatBanner(patch: String) = guardAtEntry(
    patch,
    "invoke-static {}, Lapp/morphe/extension/tiktok/inbox/InboxControls;->shouldHideGroupChatBanner()Z",
    "return-void",
)
