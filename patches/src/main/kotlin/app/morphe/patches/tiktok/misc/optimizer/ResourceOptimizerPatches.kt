/*
 * Adapted from kveld9/kveld-morphe-patches at
 * fcb1768620b8f98a6dd31e801074589ce9a63356 (GPL-3.0).
 * https://github.com/kveld9/kveld-morphe-patches/tree/fcb1768620b8f98a6dd31e801074589ce9a63356
 */
package app.morphe.patches.tiktok.misc.optimizer

import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.diagnostics.BUILD_DETAILS_ASSET
import app.morphe.patches.tiktok.misc.diagnostics.BuildChoice
import app.morphe.patches.tiktok.misc.diagnostics.BuildDetails
import app.morphe.patches.tiktok.misc.diagnostics.buildChoicePatch
import java.util.Locale

@Suppress("unused")
val p2pRelayBlockerPatch = rawResourcePatch(
    name = "Block P2P video relay",
    description = "Removes the files TikTok uses to pass videos on to other viewers through " +
        "your phone's internet connection. The app gets about 3.5 MB smaller. It's built in while " +
        "patching, so only patching again without it brings them back.",
    default = false,
) {
    category("Performance")
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())
    dependsOn(buildChoicePatch(BuildChoice.P2P))

    execute {
        val nativeFiles = listOf(
            "lib/arm64-v8a/libavmdlp2pv2.so",
            "lib/arm64-v8a/libp2plivevdp.so",
            "lib/armeabi-v7a/libavmdlp2pv2.so",
            "lib/armeabi-v7a/libp2plivevdp.so",
        )
        val result = stripVerifiedResources(
            get("."),
            "P2P Relay Blocker",
            emptyList(),
            nativeFiles,
            p2pRelayProfiles,
            resolveStandaloneFile = { path -> get(path) },
            versionName = packageMetadata.versionName,
        )
        BuildDetails.stripped(get(BUILD_DETAILS_ASSET), BuildChoice.P2P, result)
        result.report("P2P Relay Blocker")
    }
}

@Suppress("unused")
val coreAssetDebloatPatch = rawResourcePatch(
    name = "Remove content credential and card scanner assets",
    description = "Removes TikTok's payment card scanner and content credential files, saving " +
        "about 11 MB of storage. Anything in TikTok that needs them may stop working. Only " +
        "patching again without it brings them back.",
    default = false,
) {
    category("Performance")
    compatibleWith(*AppCompatibilities.tiktok())
    dependsOn(buildChoicePatch(BuildChoice.CORE))

    execute {
        // Only libraries nothing else in the APK links against. An emptied .so that another
        // library names in its DT_NEEDED list fails that library's dlopen ("file offset >=
        // file size: 0 >= 0"), and TikTok's Librarian turns that into an uncaught error.
        // libbytemonitor is needed by libbytebench, which the whole video editor stack loads,
        // so emptying it killed the app the moment the Create tab opened (S22, 2026-09-17);
        // libprofiler is needed by the crash handler's npth_ref_monitor and reschecker. Both stay.
        // So do Pitaya (its feature dex, its libraries and the Python runtime it loads) and the
        // live cast feature dex: every lib/*/libdex_*.so is injected at start-up, and an empty one
        // left TikTok's own strings in English on a Spanish phone (#125).
        val nativeFiles = listOf(
            "lib/arm64-v8a/libtt_c2pa_sdk.so",
            "lib/arm64-v8a/libtt_c2pa_sdk_d.so",
            "lib/armeabi-v7a/libtt_c2pa_sdk.so",
            "lib/armeabi-v7a/libtt_c2pa_sdk_d.so",
            "lib/arm64-v8a/libartlog_monitor.so",
            "lib/armeabi-v7a/libartlog_monitor.so",
        )
        decode("assets/microblink/**")
        val result = stripVerifiedResources(
            get("."),
            "Core Asset De-bloat",
            listOf("assets/microblink"),
            nativeFiles,
            coreAssetProfiles,
            resolveStandaloneFile = { path -> get(path) },
        )
        BuildDetails.stripped(get(BUILD_DETAILS_ASSET), BuildChoice.CORE, result)
        result.report("Core Asset De-bloat")
    }
}

@Suppress("unused")
val languagePackPurgerPatch = rawResourcePatch(
    name = "Remove unused language packs",
    description = "Removes the app languages you don't list in this patch's options, saving " +
        "up to about 26 MB of storage. English is always kept. With no list it keeps them all. " +
        "Only patching again brings removed ones back.",
    default = false,
) {
    category("Performance")
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())
    dependsOn(buildChoicePatch(BuildChoice.LANGUAGES))
    val targetLocales by stringOption(
        key = "locales",
        title = "Languages to keep",
        description = "The languages to keep, as short codes separated by commas, like en, es, " +
            "pt, fr or de. Type all to keep every language. English is always kept.",
        // Keep native translations unless the user chooses which languages to remove (#67).
        default = "all",
        required = false,
    )

    execute {
        decode("assets/strings#lang_*/**")
        val result = stripVerifiedLanguagePacks(get("."), targetLocales, languageInventories)
        if (result.retainedLocales == null) {
            // Build details keep "unverified": nothing here was checked against a reviewed APK.
            println("[Language Pack Purger] Kept every language pack. This APK's set isn't a reviewed one, so none could be removed.")
        } else {
            BuildDetails.languages(get(BUILD_DETAILS_ASSET), result)
            if (result.missingLocales.isNotEmpty()) {
                println(
                    "[Language Pack Purger] This TikTok has no pack for " +
                        "${result.missingLocales.sorted().joinToString()}, so there was nothing to keep for it.",
                )
            }
            if (result.files == 0) {
                println("[Language Pack Purger] Kept every reviewed language pack.")
            } else {
                result.report("Language Pack Purger")
            }
        }
    }
}

@Suppress("unused")
val studioCreationDebloatPatch = rawResourcePatch(
    name = "Remove creation tools",
    description = "Removes the bundled face-detection model and effect-creation component. " +
        "Keeps the shared photo and video editor needed by comment pictures. Face effects and " +
        "effect creation may stop working. Patch again without this to restore the removed files.",
    default = false,
) {
    category("Performance")
    compatibleWith(*AppCompatibilities.tiktok())
    dependsOn(buildChoicePatch(BuildChoice.CREATION))

    execute {
        // Keep feature DEX and the shared effect/VE libraries. Their *_plugin.so filenames
        // export the core libeffect.so / libttvesdk.so SONAMEs that the comment image editor's
        // retained NLE libraries need. Filename-only dependency audits missed those aliases.
        val nativeFiles = listOf(
            "lib/arm64-v8a/libEffectCreatorJni.so",
            "lib/armeabi-v7a/libEffectCreatorJni.so",
        )
        decode("assets/model/ttfacemodel/**")
        val result = stripVerifiedResources(
            get("."),
            "Studio & Creation De-bloat",
            listOf("assets/model/ttfacemodel"),
            nativeFiles,
            studioAssetProfiles,
            resolveStandaloneFile = { path -> get(path) },
        )
        BuildDetails.stripped(get(BUILD_DETAILS_ASSET), BuildChoice.CREATION, result)
        result.report("Studio & Creation De-bloat")
    }
}

@Suppress("unused")
val liveStreamSuiteOptimizerPatch = rawResourcePatch(
    name = "Remove LIVE extras",
    description = "Removes the files for LIVE co-hosting, joining a LIVE as a guest, matches, " +
        "games and gift effects, so the app gets about 3 MB smaller. The catch: in LIVEs, battle " +
        "scores and guest names can go missing, and joining someone's LIVE by voice or video, " +
        "co-hosting, games or animated gifts may stop working.",
    default = false,
) {
    category("Performance")
    compatibleWith(*AppCompatibilities.tiktok())
    dependsOn(buildChoicePatch(BuildChoice.LIVE), liveGiftEffectOptimizerPatch)

    execute {
        val nativeFiles = listOf(
            "lib/arm64-v8a/liblink_mic_sdk.so",
            "lib/armeabi-v7a/liblink_mic_sdk.so",
        )
        decode("assets/native_runtime_server/game/**", "assets/offline/tiktok_live_tt_live_lynx_match_component_container/**")
        val result = stripVerifiedResources(
            get("."),
            "LIVE Stream Suite Optimizer",
            listOf(
                "assets/native_runtime_server/game",
                "assets/offline/tiktok_live_tt_live_lynx_match_component_container",
            ),
            nativeFiles,
            liveAssetProfiles,
            resolveStandaloneFile = { path -> get(path) },
        )
        BuildDetails.stripped(get(BUILD_DETAILS_ASSET), BuildChoice.LIVE, result)
        result.report("LIVE Stream Suite Optimizer")
    }
}

private fun StripSummary.report(patchName: String) {
    if (files == 0) {
        println("[$patchName] This build ships none of the reviewed files, so there was nothing to empty.")
    } else if (alreadyStripped) {
        println("[$patchName] The reviewed target set was already empty.")
    } else {
        val mebibytes = String.format(Locale.US, "%.2f", bytes.toDouble() / (1024.0 * 1024.0))
        println("[$patchName] Emptied $files reviewed files ($mebibytes MiB unpacked).")
    }
}

private fun file(path: String, sha256: String) = ResourceFileContract(path, sha256)

private val microblinkFiles = listOf(
    file("assets/microblink/blinkcard/Model_1118d9d674e23996f70c6416b2bf5a6ce6ef24a6ad2c92f0ddd1e198e5f05305.rtttl", "5175cfccf687db041900edf58775cdda68553977e043941166fc63d26d460298"),
    file("assets/microblink/blinkcard/Model_349432d66ef2b216155673b634f7d5c47795bed35719b954f726b5f0856740f3.rtttl", "70c2d6e2db5739948f6b25c691454328e64fee3de381b19d4f984d238f201e2c"),
    file("assets/microblink/blinkcard/Model_3b11c3ffacbbf390b932fb9a7024f1a0016f66281ea8c790f8b5903374ad89c2.rtttl", "baae6d2360a3ce299b13b0750f6f8f21d4eb9961031b1cb6318bb59c29ca2d65"),
    file("assets/microblink/blinkcard/Model_47a2b4fe3503a673b9119db53c50711a9f9ae9b3ada515a534fdbd8013623d43.rtttl", "f088377e3391855d5ada5ff4001c89fdb6acd29a40f25b346bc988c6e84f4f50"),
    file("assets/microblink/blinkcard/Model_830c13896f96c1cb6d5cad725f44e6aae470f8672d640d20b3272ed4bb839699.rtttl", "ee18e0f7a9b964eb2fb4dd08abe6f5017f0ae9486f308c9a27d65e81dcc85aa8"),
    file("assets/microblink/blinkcard/Model_9f6734be0f5c1e4f3c6c621f4a72db8241feaf7c8705dc68a9cc07a7b634ee85.rtttl", "c0d8dca352eef82edbfcb63465f656783e5cdda57b435104c25b1a552ddd6263"),
    file("assets/microblink/blinkcard/Model_b9263312a9b623d1a3b75b643ccdcbc36aae52c278d721443468147c50e44583.rtttl", "8cd96554162a2b0b7d413d95b3d99da6cfd8e84caa499714f034850f788cbcd7"),
    file("assets/microblink/blinkcard/Model_cc1fab8df49d9a21de6c7b76ccf0dac40b17fcfb7073cc520eca073cbf8e33e9.rtttl", "61e33f9748bd27c4e2bb88fc677e05a8517b018fbc3d9899b6e9e852a627dcc0"),
    file("assets/microblink/blinkcard/Model_e946dc0b1d15d7dd6ab8b936593bd77a07a9d8a5fa7a803e531713a6f5d2eab6.rtttl", "5b531081f2d15eda91f1598089281c4a887a4990325eac3f66ffbf23de359e8f"),
    file("assets/microblink/blinkcard/Model_ee471cb6e7b68287281f761c2b05221043b9b059c6cea9132523ab2981f9a7ca.rtttl", "74174713ad1974152b8a5d92405b91bf84ee515a36f5691f7314a6b4720f352c"),
    file("assets/microblink/blinkcard/Model_f132d1bd7614b1274fafb8a41ec6c047b84b2a43654ae2da5ddd78a2765601c6.rtttl", "00caf18a73114c05903ddb448afbd1191ff07825a318664fcc82360ce7e9290d"),
    file("assets/microblink/blinkcard/device_list.json", "7093365bcfc7a97ac57f2aefb23d9be05b0fc707b5aea58305ec76a8abefd670"),
    file("assets/microblink/blinkcard/device_list_mb.json", "07b3a400c1246e09de51fc15c57308d5ff0feeb09ae2bbfa3511fdd4afe06aed"),
)

// One reviewed path set per strip for the declared build, read off its universal APK and its
// split bundle. The log monitor, camera, relay and link mic libraries are rebuilt from one
// TikTok build to the next, so a strip refuses any other build's files ("no
// reviewed path set matched"), and a build the bundle moves to needs its own sets read off its
// fixtures. The Microblink files and the C2PA debug libraries have been byte-identical on every
// build read so far, and 47.4.3 rebuilt the main C2PA library.
private val c2paArm64Files = listOf(
    file("lib/arm64-v8a/libtt_c2pa_sdk.so", "930a55727f14497d1e4bbc2756282eae8e11140e2495ab8bbed8c10099e80c45"),
    file("lib/arm64-v8a/libtt_c2pa_sdk_d.so", "a64ce0fb43e7c22e2d95d8bbfcaede522d585c5d51dbae87d24db1359c27a01a"),
)

private val c2paArmeabiFiles = listOf(
    file("lib/armeabi-v7a/libtt_c2pa_sdk.so", "1dd736b9620a9a6e01dc7692ed8a7f141ca6e344bf632b2d93cd0e88e0251957"),
    file("lib/armeabi-v7a/libtt_c2pa_sdk_d.so", "a96af4a99de68503234e3ab658d9702ac436ae1e0650dc5551e4e29a89700b0c"),
)

// 47.4.3 (APKMirror's universal APK, read on 2026-10-17). Its split bundle holds the universal
// APK's bytes for every file it carries.
private val monitorFiles = listOf(
    file("lib/arm64-v8a/libartlog_monitor.so", "58ef4dc22fbbfae9ed1099ceba0f9c8549bdf6ecdb46c7a52b92ad0102fb19d3"),
    file("lib/armeabi-v7a/libartlog_monitor.so", "2f5810cf024b209e0c6de883af8ca92325217571a39d4ad58035cb8c3ea0c251"),
)

// APKMirror also sells TikTok as a split bundle (tiktok-47-4-3, "arm64-v8a + armeabi-v7a",
// 120-640dpi, read on 2026-10-17). Morphe Manager and the desktop CLI merge every split in one,
// and every file the merged APK shares with the universal APK is byte-identical: the arm64
// libraries it carries, only part of the armeabi-v7a set, and some of the 64 language asset
// directories, because those ship as language splits. Each bundle profile is the universal APK's
// profile less the files the bundle doesn't have: besides that part of the armeabi-v7a set, the
// arm64 C2PA debug library, both arm64 editor plugins and all four P2P relay libraries. It keeps
// 25 languages.

/** [profile] without [absent], every one of which it must list: its build's split bundle. */
private fun bundleOf(profile: ResourceProfile, vararg absent: String): ResourceProfile {
    val paths = profile.files.map { it.path }.toSet()
    val missing = absent.filterNot { it in paths }
    check(missing.isEmpty()) { "${profile.label} does not list $missing, so it cannot be left out of the bundle profile" }
    return ResourceProfile("${profile.label} split bundle", profile.files.filterNot { it.path in absent })
}

private val coreAssets = ResourceProfile(
    "TikTok 47.4.3",
    microblinkFiles + c2paArm64Files + c2paArmeabiFiles + monitorFiles,
)

internal val coreAssetProfiles = listOf(
    coreAssets,
    bundleOf(
        coreAssets,
        "lib/arm64-v8a/libtt_c2pa_sdk_d.so",
        "lib/armeabi-v7a/libtt_c2pa_sdk_d.so",
    ),
)

private val studioArm64Files = listOf(
    file("assets/model/ttfacemodel/tt_face_v11.1.model", "c4b081d04f6829f7cd5849abca0941891f1a7f2cb250efd7f0e42fece5426610"),
    file("lib/arm64-v8a/libEffectCreatorJni.so", "b7db28436aa1ccd85e3d52d1f916817397be6b35dfff40d1bb69d0d5251a3293"),
)

private val studioAssets = ResourceProfile(
    "TikTok 47.4.3",
    studioArm64Files +
        listOf(
            file("lib/armeabi-v7a/libEffectCreatorJni.so", "d3ae58712413c2d1d06dcf508eb7850452f58c93b9befd57867cd029421c9482"),
        ),
)

internal val studioAssetProfiles = listOf(
    studioAssets,
    bundleOf(
        studioAssets,
        "lib/armeabi-v7a/libEffectCreatorJni.so",
    ),
)

internal val liveAssetProfiles = listOf(
    // The universal APK and the split bundle carry these five files byte for byte.
    ResourceProfile(
        "TikTok 47.4.3",
        listOf(
            file("assets/native_runtime_server/game/scripts/ttmg-core.js.zip", "897d0f54569ea8c34b31652943f9abd7f89a842357078ea9bc78ca47fa0c8a14"),
            file("assets/offline/tiktok_live_tt_live_lynx_match_component_container/mainV12/template.js", "d648b3e0ad779a0dde442ca381212661ea97eeddc255dbb92003aab5f4514460"),
            file("assets/offline/tiktok_live_tt_live_lynx_match_component_container/match_invitee_v3/template.js", "8e84ec297249a771c9c9d56438f6c509d2a0f438c759ee65e212ab14ef7cb3eb"),
            file("lib/arm64-v8a/liblink_mic_sdk.so", "5c5d258ecf9b42ed601c63ca6e745cbfc67922734b5f8f83f9711ec5c4f08dea"),
            file("lib/armeabi-v7a/liblink_mic_sdk.so", "08b427a49d1d3dfd480de37fb56a4ac6956768a9b80b00a1ddf8cd9fa5b494d5"),
        ),
    ),
)

internal val p2pRelayProfiles = listOf(
    ResourceProfile(
        "TikTok 47.4.3",
        listOf(
            file("lib/arm64-v8a/libavmdlp2pv2.so", "0959e219573d27d3da85550150059c48bc7821776a42bc12fcf9b30ef345e823"),
            file("lib/arm64-v8a/libp2plivevdp.so", "ed35b032fac7c169860d3f37bc3be1933df9bddc8ac6785413f9608945946337"),
            file("lib/armeabi-v7a/libavmdlp2pv2.so", "584388b01c531adb6fc34665f30450cdeb8e5b1e76a52e53f5ba721e5db6d431"),
            file("lib/armeabi-v7a/libp2plivevdp.so", "dd8a626f8b0efe36a883096923bed56ecb457e9408b26262c844442901dc1d09"),
        ),
    ),
    // The split bundle ships no relay library at all, so there is nothing to empty. Only on
    // 47.4.3: another build without these files has renamed or moved them, and the strip must
    // refuse it.
    ResourceProfile("TikTok 47.4.3 split bundle", emptyList(), onlyVersion = "47.4.3"),
    // PineDrama 47.1.4 (APKMirror's bundle, read on 2026-10-17) ships no relay library in its base
    // or any ABI split, so the strip has nothing to empty there either.
    ResourceProfile("PineDrama 47.1.4", emptyList(), onlyVersion = "47.1.4"),
)

internal val languageInventories = listOf(
    // The universal APK: 207 files in 64 directories.
    LanguageInventoryContract(
        directories = setOf(
            "af", "ar", "az", "bg", "bn", "ca", "ceb", "cs", "da", "de", "el", "en", "es", "et", "fa", "fi",
            "fil", "fr", "ga", "gu", "he", "hi", "hr", "hu", "id", "in", "is", "it", "iw", "ja", "jv", "kk",
            "km", "kn", "ko", "lt", "lv", "ml", "mr", "ms", "my", "nb", "nl", "or", "pa", "pl", "pt", "ro",
            "ru", "sk", "sl", "sq", "sv", "sw", "ta", "te", "th", "tr", "uk", "ur", "uz", "vi", "zh", "zu",
        ),
        pathManifestSha256 = "38f412319f00a31a025e03095ea9bf4266b412c2322d2a6b0b4a031a073b0c08",
        contentManifestSha256 = setOf("f12d6e65efec082e1fba1a5144cb2ee9d6de29b6ab672f0934c5a41facbf581d"),
    ),
    // The split bundle: 25 language splits and 102 files.
    LanguageInventoryContract(
        directories = setOf(
            "ar", "de", "en", "es", "et", "fi", "fr", "hi", "hu", "id", "in", "it", "ja", "ko", "ms", "nl",
            "pl", "pt", "ru", "sv", "th", "tr", "uk", "vi", "zh",
        ),
        pathManifestSha256 = "33aad0753feebf540e226b415eecfc59323d40f7b52dfc70a06bd278e7acc9a6",
        contentManifestSha256 = setOf("8688824360ce6e2a7ecf53fdd05f130435ab2f99f31aaec4ed796ed70eeeb619"),
    ),
)
