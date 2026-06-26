package app.morphe.patches.tiktok.misc.suggestedaccounts

import app.morphe.patcher.Fingerprint

internal object ProfileHeaderMore : Fingerprint(
    definingClass = "recommend/assemble/ProfileHeaderRecommendComponent;",
    name = "Fm",
    returnType = "V",
    parameters = emptyList(),
)

internal object InboxFooter : Fingerprint(
    definingClass = "aweme/inbox/v2/container/UserCardWidgetContainer;",
    name = "Gm",
    returnType = "V",
    parameters = listOf("Lcom/ss/android/ugc/aweme/inbox/widget/v2/InboxWidget;"),
)

internal object InboxNewFollowersFooter : Fingerprint(
    definingClass = "aweme/inbox/followerv2/FollowerUserCardWidgetContainer;",
    name = "Gm",
    returnType = "V",
    parameters = listOf("Lcom/ss/android/ugc/aweme/inbox/widget/v2/InboxWidget;"),
)

internal object InboxActivitiesFooter : Fingerprint(
    definingClass = "aweme/relation/recuser/inbox/NotificationRecommendUserWidgetV2Injector;",
    name = "enable",
    returnType = "Z",
    parameters = emptyList(),
)