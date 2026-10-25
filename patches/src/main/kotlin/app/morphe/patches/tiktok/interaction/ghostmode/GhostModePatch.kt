/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 * Follows eduardo3677-ai/tiktok-patches-for-morphe.
 */
package app.morphe.patches.tiktok.interaction.ghostmode

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.util.addInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.matchAllInDefiningClasses
import app.morphe.patcher.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags

// The classes each reporter lives in, a suffix like /Name;, shared by its fingerprint and the
// narrowed search below.
internal const val STORY_API = "/StoryApi;"
internal const val PROFILE_VIEWER_API = "/ProfileViewerApiService;"
internal const val TYPING_STATUS_SENDER = "/TypingStatusSenderTimer;"

/**
 * Reports that a story was seen, opened or interacted with.
 *
 * <p>definingClass repeats what the custom block checks so the search reads only the classes it
 * names (matchAllInDefiningClasses); without it every method of TikTok was tried (#54).
 */
internal object StoryViewReportFingerprint : Fingerprint(
    definingClass = STORY_API,
    custom = { method, classDef ->
        classDef.endsWith(STORY_API) &&
            method.name in setOf("reportStoryViewed", "reportUserInteraction", "reportStoryReveal")
    },
)

/** Records a profile visit against the profile's viewer list. */
internal object ProfileViewReportFingerprint : Fingerprint(
    definingClass = PROFILE_VIEWER_API,
    custom = { method, classDef ->
        classDef.endsWith(PROFILE_VIEWER_API) && method.name == "reportView"
    },
)

/** Pushes the "typing…" indicator into a conversation. */
internal object TypingStatusSenderFingerprint : Fingerprint(
    definingClass = TYPING_STATUS_SENDER,
    custom = { method, classDef ->
        classDef.endsWith(TYPING_STATUS_SENDER) &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes[0] == "Ljava/lang/String;" &&
            method.returnType == "V"
    },
)

/**
 * TikTok's play report sender (/aweme/v1/aweme/stats/). Every story view sends one with the
 * story's aid, play_delta=1 and story_consumption_type, beside the StoryApi report above, so a
 * viewer list still filled with only that one blocked (#39). R8 names it on every build (47.0.3
 * LX/09gS.LIZIZ, 47.1.3 LX/09b8.LIZIZ); its monitor string and its shape find it.
 */
internal object AwemeStatsSenderFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Ljava/lang/String;", "I", "Ljava/lang/String;", "I",
        "Lcom/ss/android/ugc/aweme/feed/model/Aweme;", "Ljava/lang/String;", "Lkotlin/jvm/functions/Function1;",
    ),
    strings = listOf("aweme_stats_monitor").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)

/**
 * The two coroutine bodies that send TikTok's activity status report (/tiktok/v1/activity_status/
 * report, the call that lights the green dot and "Active now" for your friends). The regular
 * poll runs in doReport$1, and the reportAdditional and cancelPolling paths go through
 * doReport$2. Each is the only caller of the report service in its dex. R8 renames every class,
 * but the trace tag each body opens with names the original class and lambda, and it is the
 * same on 47.0.3, 47.1.3 and 47.1.4.
 */
internal const val ONLINE_STATUS_REGULAR_TAG = "ActivityStatusReporter@261e.doReport\$1"
internal const val ONLINE_STATUS_IRREGULAR_TAG = "ActivityStatusReporter@261e.doReport\$2"

internal object OnlineStatusRegularReportFingerprint : Fingerprint(
    name = "invokeSuspend",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    strings = listOf(ONLINE_STATUS_REGULAR_TAG).map { string(it, StringComparisonType.CONTAINS) },
)

internal object OnlineStatusIrregularReportFingerprint : Fingerprint(
    name = "invokeSuspend",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    strings = listOf(ONLINE_STATUS_IRREGULAR_TAG).map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * Returns from a reporter before it sends anything, when the extension says to.
 *
 * <p>Only a reporter that returns nothing can take this. One that hands something back is
 * handing back a lazy `Call`, `Observable` or `Single` that its caller goes on to `enqueue` or
 * `subscribe`, and the only value this could put there is a null. That null was the crash on
 * opening a story with Ghost mode on, and the reason other people's profiles showed no
 * follower counts. Those reporters are suppressed where they are called instead, by stepping
 * over the whole send: see [skipReportsAtEveryCallSite].
 *
 * @return true when the guard was injected; false for an abstract method, one with no local
 *         register to hold the answer, or one that returns anything at all.
 */
internal fun MutableMethod.returnBeforeReporting(guard: String): Boolean {
    if (returnType != "V") return false
    val implementation = implementation ?: return false
    if (implementation.registerCount - numberOfParameterRegisters < 1) return false

    guardAtEntry(
        "Ghost mode",
        "invoke-static {}, $GHOST_MODE_EXTENSION->$guard()Z",
        "return-void",
    )
    return true
}

/**
 * Stops the reports that tell other people what you looked at: story views, profile
 * views and the typing indicator. It suppresses the client's own
 * reporting only; nothing here changes what the server already knows.
 */
@Suppress("unused")
val ghostModePatch = bytecodePatch(
    name = "Ghost mode",
    description = "Stops TikTok telling people you viewed their story or profile, or that " +
        "you're typing. What TikTok already recorded stays. A separate switch hides your online " +
        "status. Starts off. Turn it on in Metra settings > App behavior.",
) {
    category("Privacy")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableGhostMode()V",
        )
        // So the export carries the ghost mode family even on a run where no reporter is
        // reached. Without it a family that is simply absent says both "this build has no
        // ghost mode" and "nothing called it", and an export taken while a profile showed no
        // follower counts could not tell those apart.
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, $GHOST_MODE_EXTENSION->installed()V",
        )

        listOf(
            Triple(StoryViewReportFingerprint, STORY_API, "shouldBlockStoryView"),
            Triple(ProfileViewReportFingerprint, PROFILE_VIEWER_API, "shouldBlockProfileView"),
            Triple(TypingStatusSenderFingerprint, TYPING_STATUS_SENDER, "shouldBlockTypingStatus"),
        ).forEach { (fingerprint, suffix, guard) ->
            // Retrofit declarations have no body. Every concrete reporting method is mandatory.
            val reporters = matchAllInDefiningClasses(fingerprint, suffix).map { it.method }.filter { it.implementation != null }
            if (reporters.isEmpty()) {
                throw PatchException("Ghost mode: no concrete reporter for $guard.")
            }
            val (silent, lazy) = reporters.partition { it.returnType == "V" }
            if (silent.any { !it.returnBeforeReporting(guard) }) {
                throw PatchException("Ghost mode: could not install every $guard hook.")
            }
            if (lazy.isNotEmpty()) skipReportsAtEveryCallSite(lazy, guard)
        }

        // The activity status report. Each body is a launched coroutine whose result nobody
        // reads, so a null return ends it before it sends; the guard answers false unless
        // Ghost mode and Hide online status are both on, and the answer is read on every
        // report, so the switch needs no restart.
        listOf(OnlineStatusRegularReportFingerprint, OnlineStatusIrregularReportFingerprint).forEach { fingerprint ->
            val senders = fingerprint.matchAllOrNull().orEmpty().map { it.method }
            if (senders.size != 1) {
                throw PatchException("Ghost mode: expected one activity status sender, found ${senders.size}.")
            }
            senders.single().guardAtEntry(
                "Ghost mode",
                "invoke-static {}, $GHOST_MODE_EXTENSION->shouldBlockOnlineStatus()Z",
                "const/4 v0, 0x0\nreturn-object v0",
            )
        }

        // The play report a story view also sends. The guard asks about the Aweme (p4) and lets
        // every feed video's report through; it returns before the report is built, so the
        // callback that stamps the story's consumption type never runs either.
        val statsSenders = AwemeStatsSenderFingerprint.matchAllOrNull().orEmpty().map { it.method }
        if (statsSenders.size != 1) {
            throw PatchException("Ghost mode: expected one play report sender, found ${statsSenders.size}.")
        }
        statsSenders.single().guardAtEntry(
            "Ghost mode",
            "invoke-static/range {p4 .. p4}, " +
                "$GHOST_MODE_EXTENSION->shouldBlockStoryStats(Lcom/ss/android/ugc/aweme/feed/model/Aweme;)Z",
            "return-void",
        )
    }
}
