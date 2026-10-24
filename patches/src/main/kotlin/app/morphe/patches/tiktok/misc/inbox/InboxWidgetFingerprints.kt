/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 * The widget injector fingerprints follow hxreborn/hxreborn-tiktok-patches (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string

/**
 * TikTok builds each Inbox, Activity and New followers section from a widget whose
 * injector answers {@code enable()}. Returning false there keeps the section from being
 * built at all, which is upstream of the row hiding {@code InboxFilter} does once the
 * views exist. All of these class names are real, so they survive obfuscation.
 */
internal object ActivityRecommendUserWidgetEnableFingerprint : Fingerprint(
    definingClass = "/NotificationRecommendUserWidgetV2Injector;",
    name = "enable",
    returnType = "Z",
    parameters = emptyList(),
)

internal object NewFollowersRecommendUserWidgetEnableFingerprint : Fingerprint(
    definingClass = "/FollowerUserCardWidgetV2Injector;",
    name = "enable",
    returnType = "Z",
    parameters = emptyList(),
)

/** The skeleton shown while the suggestion cards load; hidden with the cards themselves. */
internal object NewFollowersRecommendUserSkeletonEnableFingerprint : Fingerprint(
    definingClass = "/FollowerUserCardLoadingWidgetV2Injector;",
    name = "enable",
    returnType = "Z",
    parameters = emptyList(),
)

internal object InboxRecommendUserWidgetEnableFingerprint : Fingerprint(
    definingClass = "/RecommendUserWidgetV2Injector;",
    name = "enable",
    returnType = "Z",
    parameters = emptyList(),
)

private const val NOTIFICATION_WIDGET_CONTAINER_DESCRIPTOR = "/NotificationWidgetContainer;"

/**
 * Carries the log line {@code expandNotification()} and writes the collapsed flag, which
 * is how the flag's field is located without naming it.
 */
internal object ExpandNotificationListFingerprint : Fingerprint(
    definingClass = NOTIFICATION_WIDGET_CONTAINER_DESCRIPTOR,
    returnType = "V",
    strings = listOf("expandNotification()").map { string(it, StringComparisonType.CONTAINS) },
)

internal object NotificationWidgetContainerOnCreateFingerprint : Fingerprint(
    definingClass = NOTIFICATION_WIDGET_CONTAINER_DESCRIPTOR,
    name = "onCreate",
    returnType = "V",
    parameters = emptyList(),
)

internal object NotificationWidgetContainerConstructorFingerprint : Fingerprint(
    definingClass = NOTIFICATION_WIDGET_CONTAINER_DESCRIPTOR,
    name = "<init>",
    returnType = "V",
    parameters = emptyList(),
)

internal object FollowerWidgetContainerConstructorFingerprint : Fingerprint(
    definingClass = "/FollowerWidgetContainer;",
    name = "<init>",
    returnType = "V",
    parameters = emptyList(),
)
