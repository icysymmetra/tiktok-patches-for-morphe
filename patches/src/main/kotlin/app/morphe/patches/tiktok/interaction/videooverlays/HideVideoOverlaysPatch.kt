/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.util.addInstruction
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.authorregion.DetailActivityOnCreateFingerprint
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.inbox.MainActivityOnCreateFingerprint
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patches.tiktok.shared.requireLocals

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/feed/VideoOverlayHider;"

private object FullscreenEntranceFingerprint : Fingerprint(
    definingClass = FULLSCREEN_COMPONENT,
    custom = { method, _ -> isFullscreenBind(method) },
)
private object LocationCardFingerprint : Fingerprint(
    strings = listOf("PoiAnchorView2", "bindData").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> isLocationCardBind(method, "PoiAnchorView2") },
)
private object LocationDealCardFingerprint : Fingerprint(
    strings = listOf("PoiDealAnchorView", "bindData").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> isLocationCardBind(method, "PoiDealAnchorView") },
)
private object LocationBadgeListFingerprint : Fingerprint(
    custom = { method, _ -> isLocationBadgeListFactory(method) },
)
private object FeedReportButtonGateFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    strings = listOf(FEED_REPORT_GATE_KEY).map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> isFeedReportButtonGate(method) },
)

private const val PATCH_NAME = "Hide video overlays"

@Suppress("unused")
val hideVideoOverlaysPatch = bytecodePatch(
    name = "Hide video overlays",
    description = "Lets you hide clutter over videos, like the caption, the music line, " +
        "buttons on the right, surveys and location labels, so you see more of the video. It can " +
        "also make the music disc spin again or keep it still. Each has its own switch. Starts " +
        "off. Turn it on in Metra settings > Interface.",
) {
    category("Feed")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        val status = SettingsStatusLoadFingerprint.method
        val activity = MainActivityOnCreateFingerprint.method
        val detail = DetailActivityOnCreateFingerprint.method
        val controls = resolveFeedOverlayControls(
            FullscreenEntranceFingerprint.method,
            listOf("PoiAnchorView2" to LocationCardFingerprint.method,
                "PoiDealAnchorView" to LocationDealCardFingerprint.method),
        ) { classDefByOrNull(it) }
        val badgeList = LocationBadgeListFingerprint.method.resolveLocationBadgeList()
        val reportGate = FeedReportButtonGateFingerprint.method
        // The Footnotes banner's gate is required on a declared build, where
        // FootnoteBannerAnchorsTest holds it, and left out with a note on any other, so one
        // renamed gate does not take the rest of the overlay switches down with it.
        val footnoteGate = try {
            FootnoteBannerGateFingerprint.method.also { it.requireLocals(PATCH_NAME, 1) }
        } catch (problem: Exception) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            println("[$PATCH_NAME] Left out Hide Footnotes on ${packageMetadata.versionName}: ${problem.message}")
            null
        }
        // The music disc's two switches (#68), the same way: required where MusicDiscSpinAnchorsTest
        // holds every hold on the disc's way, left out with a note anywhere else.
        val musicDisc = try {
            MusicDiscHooks(
                close = MusicAnimationCloseFingerprint.method,
                duration = MusicCoverRotationDurationFingerprint.method,
                hold = MusicCoverAnimationHoldFingerprint.method,
                subscriber = MusicCoverPlaySubscriberFingerprint.method,
                trigger = PlayAnimationTriggerFingerprint.method,
                degrade = MusicCoverDegradeRegistrationFingerprint.method.let { registration ->
                    val check = degradeCheckType(registration)
                        ?: throw PatchException("$PATCH_NAME: onViewCreated registers nothing under $MUSIC_COVER_DEGRADE_KEY.")
                    mutableClassDefBy(check).methods.singleOrNull(::isDegradeEnable)
                        ?: throw PatchException("$PATCH_NAME: $check has no enable() to answer.")
                },
                playAnimationSwitch = PlayAnimationDowngradeFingerprint.method.definingClass.let { strategy ->
                    classDefBy(strategy).methods.singleOrNull { playAnimationSwitchIndex(it) >= 0 }
                        ?: throw PatchException("$PATCH_NAME: $strategy switches no play animation.")
                },
            )
        } catch (problem: Exception) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            println("[$PATCH_NAME] Left out the music disc switches on ${packageMetadata.versionName}: ${problem.message}")
            null
        }
        // A missing new control must not leave an otherwise failed patch partly applied.
        controls()
        badgeList()
        // The regional Report button above the creator's avatar (issue #21). Answering false is
        // the state every region without the button already runs in.
        reportGate.guardAtEntry(
            PATCH_NAME,
            "invoke-static {}, Lapp/morphe/extension/tiktok/feed/FeedOverlayControls;->shouldHideReportButton()Z",
            """
                const/4 v0, 0x0
                return v0
            """,
        )
        // Footnotes. The banner's two components both ask this one gate before they build
        // anything, and false is the answer a video without a note already gets.
        if (footnoteGate != null) {
            footnoteGate.hideFootnoteBanner(PATCH_NAME)
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableFootnotes()V",
            )
        }
        // The music disc. 47.1.4 ships music_animation_close_exp at 3, which keeps the disc from
        // turning; each lazy read hands its value to MusicDiscSpin before boxing it. The holds
        // past it (anim_opt, the high frame rate hold, the play animation's load downgrade and
        // musicCoverDegrade) come from the server or the phone's load, and each hands its answer
        // to MusicDiscSpin too. With both switches off every value goes through as TikTok read it.
        if (musicDisc != null) {
            musicDisc.apply(PATCH_NAME)
            status.addInstruction(
                0,
                "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableMusicDiscSpin()V",
            )
        }
        status.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableVideoOverlays()V",
        )

        // p0 is the activity. /range because a parameter register is usually above v15. The
        // detail page too: a process started into search by a deep link opens its videos there
        // with no MainActivity, and nothing followed them (#84).
        listOf(activity, detail).forEach {
            it.addInstruction(
                0,
                "invoke-static/range { p0 .. p0 }, " +
                    "$EXTENSION_CLASS_DESCRIPTOR->install(Landroid/app/Activity;)V",
            )
        }
    }
}
