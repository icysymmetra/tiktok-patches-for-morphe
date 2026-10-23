/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.blockauthor

import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.inbox.MainActivityOnCreateFingerprint
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.interaction.resume.FeedPlayCompletedFingerprint
import app.morphe.patches.tiktok.shared.OnRenderFirstFrameFingerprint
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.patcher.patch.PatchException
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/blockauthor/BlockAuthorPatch;"

private const val VIDEO_ITEM_PARAMS_DESCRIPTOR =
    "Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;"

private const val FINISH_LAST_VIDEO =
    "Lapp/morphe/extension/tiktok/wellbeing/FinishLastVideo;"

/**
 * TikTok's player as the session hold sees it: the video a PlayerController actually has on
 * screen, and that player's own pause and resume. Block author's hold and switches and the app
 * lock all stop the video through it, so it is its own patch that each depends on and that
 * runs once however many of them are picked.
 */
internal val sessionPlaybackBridgePatch = bytecodePatch {
    dependsOn(sharedExtensionPatch)

    execute {
        // A bind is not "this video is on screen": the feed binds the items either side of
        // the current one before the user reaches them, so a bind tracker would arm the next
        // creator. The player names the video that is actually playing, which is what selects
        // among the bound items. p1 is that id; p0 also lets the session hold pause the
        // owning player without retaining a global player instance.
        PlayerProgressAidFingerprint.method.capturePlayingAweme()
        // A start's first video plays from its first frame, a second or two before its first
        // progress report, so "Don't start the first video" hears about the player here too (#83).
        OnRenderFirstFrameFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, " +
                "$EXTENSION_CLASS_DESCRIPTOR->onFirstFrame(Ljava/lang/Object;)V",
        )

        // The hold's pause and resume, and the video it checks it is pausing. Their names
        // change with every build, so they are read off TikTok's own pauseVideo and the For You
        // feed's space-key toggle and written into the hold's three bridge methods.
        val nativePlayback = resolveNativePlayback()
        installNativePlayback(nativePlayback)
        // And TikTok's own plays of a video they paused are turned down until they hand it back,
        // so a cold start's second play of the first video never starts it (#83).
        installSwitchPlayGuards(nativePlayback)

        // The activity, so the pause switches can follow the app going away and coming back, and
        // its saved state, which tells a screen Android built again from a first start. p0 is the
        // activity and p1 onCreate's Bundle, and /range because a parameter register is usually
        // above v15. Here rather than in Block author button: Comment tools' "pause behind the
        // comments" needs it too, and with only Comment tools picked the row was there and the
        // video played on.
        MainActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p1 }, " +
                "Lapp/morphe/extension/tiktok/playback/PausePlayback;->" +
                "install(Landroid/app/Activity;Landroid/os/Bundle;)V",
        )
    }
}

@Suppress("unused")
val blockAuthorPatch = bytecodePatch(
    name = "Block author button",
    description = "Adds buttons on videos to block the creator, hide them only on your phone, " +
        "or block the sound, so you can get rid of what you don't want in one tap. Starts off. " +
        "Turn it on in Metra settings > Interface.",
) {
    category("Interaction")
    dependsOn(settingsPatch, sharedExtensionPatch, sessionPlaybackBridgePatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableBlockAuthor()V",
        )

        // PausePlayback.install, which the pause switches follow the app with, comes from
        // sessionPlaybackBridgePatch.

        // TikTok's daily screen-time reminder coming up over the feed, for the switch that
        // leaves the app instead. After the invoke-super: the method opens with a null-context
        // early return whose branch target the insert must not sit in front of, and past the
        // super call the slot is really attached.
        val reminderMethod = DailyScreenTimeReminderFingerprint.method
        val reminderSuperIndex = reminderMethod.indexOfFirstInstructionOrThrow {
            opcode == Opcode.INVOKE_SUPER
        }
        reminderMethod.addInstruction(
            reminderSuperIndex + 1,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/wellbeing/RestReminder;->reminderShown()V",
        )

        // Track the author of whichever video is currently on screen.
        val trackerMethod = VideoAuthorInfoParamsFingerprint.method
        val paramsRegister = trackerMethod.registerOfParameter(VIDEO_ITEM_PARAMS_DESCRIPTOR)
            ?: error("Could not locate the VideoItemParams parameter on paramSync2StateAccept")

        // Must be invoke-static/range. Parameter registers sit at the top of the frame, so
        // on a method with a large frame this register is well above v15, which the plain
        // invoke-static (format 35c) cannot encode. The smali assembler drops the whole
        // method when that happens, and the patcher then fails with "Collection is empty".
        trackerMethod.addInstruction(
            0,
            "invoke-static/range { $paramsRegister .. $paramsRegister }, " +
                "$EXTENSION_CLASS_DESCRIPTOR->setCurrentVideoParams(Ljava/lang/Object;)V",
        )

        // The video actually playing, and the player's own pause and resume, come from
        // sessionPlaybackBridgePatch, which the app lock brings in as well.

        // A replacement native activity can take focus from the hold itself. Observe its
        // actual grant and loss so release can distinguish that from an external audio owner.
        // The helper and its listener are found by their shape; their names were once written
        // here and were the first thing a newer build renamed.
        val nativeFocus = resolveNativeFocus()
        nativeFocus.request.captureNativeFocusRequest()
        nativeFocus.abandon.captureNativeFocusAbandon()
        nativeFocus.change.captureNativeFocusChange()

        // Assert the block endpoint still looks the way the extension expects. The
        // extension calls it by reflection, so without this the patch would install a
        // button that silently fails on a build that reshaped the API.
        BlockServiceFingerprint.method

        val pager = mutableClassDefBy("Lcom/ss/android/ugc/aweme/common/widget/VerticalViewPager;")
        validateBlockPager(pager.methods)

        // Let the last video finish. While the daily hold waits for the video on screen, the
        // feed's pager turns a swipe down the way TikTok's own setDisableScroll does: both touch
        // methods answer false before they look at the event, so the pager never takes the
        // gesture and everything inside it still gets its touches. The extension only says yes
        // for the main activity's pager, so a video opened from messages keeps its swipe.
        listOf("onInterceptTouchEvent", "onTouchEvent").forEach { name ->
            val touch = pager.methods.singleOrNull {
                it.name == name && it.parameterTypes == listOf("Landroid/view/MotionEvent;") &&
                    it.returnType == "Z" && it.implementation != null
            } ?: throw PatchException(
                "Block author: VerticalViewPager no longer declares $name(MotionEvent), which " +
                    "the last-video swipe hold answers. Read the class on the new build.",
            )
            touch.guardAtEntry(
                "Block author",
                "invoke-static/range { p0 .. p0 }, $FINISH_LAST_VIDEO->holdsSwipe(Landroid/view/View;)Z",
                "const/4 v0, 0x0\nreturn v0",
            )
        }
        // The video ending is what ends that wait. p1 is the id the progress callback reports.
        FeedPlayCompletedFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p1 .. p1 }, $FINISH_LAST_VIDEO->onPlayCompleted(Ljava/lang/String;)V",
        )

        val detail = mutableClassDefBy("Lcom/ss/android/ugc/aweme/detail/ui/DetailPageFragment;")
        val visibility = "Lapp/morphe/extension/tiktok/blockauthor/FeedVisibility;"
        listOf(
            Triple("onViewCreated", listOf("Landroid/view/View;", "Landroid/os/Bundle;"), "onDetailView(Ljava/lang/Object;Landroid/view/View;)V"),
            Triple("onResume", emptyList(), "onDetailResume(Ljava/lang/Object;)V"),
            Triple("onPause", emptyList(), "onDetailPause(Ljava/lang/Object;)V"),
            Triple("onDestroyView", emptyList(), "onDetailDestroyed(Ljava/lang/Object;)V"),
            Triple("setUserVisibleHint", listOf("Z"), "onDetailVisibility(Ljava/lang/Object;Z)V"),
        ).forEach { (name, parameters, callback) ->
            val candidates = detail.methods.filter { it.name == name && it.parameterTypes == parameters }
            check(candidates.size == 1) {
                "Block author: expected DetailPageFragment to declare one $name$parameters, " +
                    "found ${candidates.size}. A lifecycle method it no longer overrides is " +
                    "inherited, and there is nothing on the class to hook."
            }
            val method = candidates.single()
            val endRegister = if (parameters.isEmpty()) "p0" else "p1"
            method.addInstruction(0, "invoke-static/range { p0 .. $endRegister }, $visibility->$callback")
        }

        // A video the reader paused stays paused when the app comes back. TikTok plays it again
        // through PlayerController's play method as the app returns, from the feed panel's resume
        // and again from the video's new surface, so the answer is asked at its start. A play it
        // turns down answers the way TikTok's own casting check does, with an empty string.
        PlayerPlayFingerprint.method.apply {
            requireLocals("Block author", 1)
            val aweme = "p${awemeParameterRegister()}"
            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { $aweme .. $aweme }, Lapp/morphe/extension/tiktok/playback/KeepPaused;->refusePlay(Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :play
                    const-string v0, ""
                    return-object v0
                """,
                ExternalLabel("play", getInstruction(0)),
            )
        }
    }
}

/**
 * The p-register of the one Aweme parameter: past `this` in p0 when the method has one, and past
 * every parameter before it, wide ones twice.
 */
internal fun com.android.tools.smali.dexlib2.iface.Method.awemeParameterRegister(): Int {
    val parameters = parameterTypes.map(CharSequence::toString)
    val index = parameters.indexOf(PLAYED_AWEME)
    check(index >= 0) { "Block author: the play method takes no Aweme." }
    val receiver = if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    return receiver + parameters.take(index).sumOf { if (it == "J" || it == "D") 2 else 1 }
}

internal fun validateBlockPager(methods: Iterable<com.android.tools.smali.dexlib2.iface.Method>) {
    listOf(
        Triple("getCurrentItem", emptyList(), "I"),
        Triple("getScrollState", emptyList(), "I"),
        Triple("setCurrentItem", listOf("I"), "V"),
        Triple("canScrollVertically", listOf("I"), "Z"),
    ).forEach { (name, parameters, result) ->
        check(methods.count { it.name == name && it.parameterTypes == parameters &&
            it.returnType == result && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null } == 1) {
            "Block author: native pager contract changed: $name$parameters$result"
        }
    }
}

internal fun MutableMethod.capturePlayingAweme() {
    addInstruction(
        0,
        "invoke-static/range { p0 .. p1 }, " +
            "$EXTENSION_CLASS_DESCRIPTOR->setPlayingAweme(Ljava/lang/Object;Ljava/lang/String;)V",
    )
}

/**
 * Resolves the smali register holding the parameter of [descriptor].
 *
 * Wide parameters occupy two registers, so the offset cannot be derived from the
 * parameter index alone.
 */
internal fun app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.registerOfParameter(
    descriptor: String,
): String? {
    var register = if (accessFlags and AccessFlags.STATIC.value != 0) 0 else 1

    for (parameterType in parameterTypes) {
        val type = parameterType.toString()
        if (type == descriptor) return "p$register"
        register += if (type == "J" || type == "D") 2 else 1
    }

    return null
}
