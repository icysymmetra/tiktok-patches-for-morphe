/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.blockauthor

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string

/**
 * Fires whenever a video becomes the current item in the feed, carrying the
 * `VideoItemParams` that holds the Aweme and therefore the author.
 *
 * This is the same anchor the "Always show publish date" patch uses, so it is known to
 * resolve on TikTok 46.2.3.
 */
internal object VideoAuthorInfoParamsFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/assem/videoauthorinfo/VideoAuthorInfoVM;",
    custom = { method, _ ->
        method.name == "paramSync2StateAccept" &&
            "Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;" in method.parameterTypes
    },
)

/**
 * TikTok's block endpoint, verified against TikTok 46.2.3:
 *
 * ```
 * @GET("/aweme/v1/user/block/")
 * Call<BlockStruct> block(@Query("user_id") String, @Query("sec_user_id") String,
 *                         @Query("block_type") int, @Query("source") int)
 * ```
 *
 * The extension calls this by reflection at runtime. The fingerprint exists so the patch
 * fails loudly at build time if a future TikTok build renames or reshapes it, rather than
 * installing a button that silently does nothing.
 */
internal object BlockServiceFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/profile/api/BlockApi\$BlockService;",
    name = "block",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;", "I", "I"),
)

/**
 * The feed panel slot that is TikTok's daily screen-time reminder, the full-screen card
 * over the feed once the limit under TikTok's own Time and well-being settings is spent.
 * The slot is only created to be shown (its `onViewCreated` logs `PrivacyDWL_popup_show`
 * unconditionally on 47.0.3), so the view being built is the reminder coming up, which is
 * what the "Leave when TikTok says time is up" switch reacts to.
 */
internal object DailyScreenTimeReminderFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/compliance/protection/timelock/ui/assem/" +
        "STMDailyScreenTimeAssem;",
    name = "onViewCreated",
    parameters = listOf("Landroid/view/View;"),
)

/**
 * Reports playback progress for the video that is actually on screen, carrying its id in
 * the first parameter. The bind callback above fires for items the feed has prefetched, so
 * this is what decides which of them is current.
 *
 * The same method is hooked by "Hide already seen videos", "Long-press controls" and
 * "Stay paused after a scroll". The first two prepend like this one; the last searches the
 * instructions for its own site, so a prepend at zero moves it rather than displacing it.
 */
internal object PlayerProgressAidFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "J", "J"),
    custom = { method, classDef ->
        method.name == "onPlayProgressChange" && classDef.endsWith("/PlayerController;")
    },
)

/**
 * PlayerController's play method: plays the video it is given and answers with a code for why it
 * didn't, or an empty string. TikTok plays the video on screen again through it as the app comes
 * back, from the feed panel's resume and again from the video's new surface. Its first check turns
 * a play down while casting and answers with that empty string. Its parameters move between
 * builds (int, Aweme, boolean on 46.2.3; Aweme, int, boolean, boolean on 47.0.3), and its log line
 * does not.
 */
internal object PlayerPlayFingerprint : Fingerprint(
    definingClass = PLAYER_CONTROLLER,
    returnType = "Ljava/lang/String;",
    strings = listOf("resumePlay, initialStartTimeMs:").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> method.parameterTypes.count { it.toString() == PLAYED_AWEME } == 1 },
)

internal const val PLAYED_AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
