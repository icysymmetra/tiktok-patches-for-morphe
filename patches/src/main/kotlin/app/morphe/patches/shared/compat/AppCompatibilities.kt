/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/shared/compat/AppCompatibilities.kt
 *
 * Central Morphe `Compatibility` metadata so Morphe Manager shows human-readable app names.
 */
package app.morphe.patches.shared.compat

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object AppCompatibilities {
    private const val TIKTOK_COLOR = 0xFE2C55

    /**
     * SHA-256 of TikTok's signing certificate (CN=musical.ly): the "certificate SHA-256 digest"
     * that `apksigner verify --print-certs` prints for every retained vendor build. Morphe Manager
     * holds an APK picked from storage to it and warns before patching one another key signed,
     * which is the only check a user gets against a repackaged "TikTok mod".
     */
    const val TIKTOK_SIGNER_SHA256 = "9041803e91bcb814b4b4399fb5c85a91640b755e5e8ba76813814bf4cf2ab5ba"

    /** TikTok 47.4.3's version code. APKMirror carries its universal APK and a split bundle. */
    const val TIKTOK_4743_VERSION_CODE = 2024704030

    /** The version codes of every declared TikTok build. */
    val TIKTOK_VERSION_CODES = setOf(TIKTOK_4743_VERSION_CODE)

    /**
     * Targets: the TikTok global package, 47.4.3 only. Only the newest stable build is declared:
     * when a newer one is supported, the one before it is dropped in the same release, so every
     * patch is read against one fixture.
     */
    fun tiktok(): Array<Compatibility> = arrayOf(
        Compatibility(
            name = "TikTok",
            packageName = "com.zhiliaoapp.musically",
            appIconColor = TIKTOK_COLOR,
            signatures = setOf(TIKTOK_SIGNER_SHA256),
            targets = listOf(
                AppTarget(version = "47.4.3", versionCode = TIKTOK_4743_VERSION_CODE),
            ),
        ),
    )

    /** PineDrama's package, TikTok's drama app. */
    const val PINEDRAMA_PACKAGE = "com.ss.android.ttmd.video"

    /**
     * SHA-256 of PineDrama's signing certificate (CN=TikTok Drama), as `apksigner verify
     * --print-certs` prints it for the APKMirror 47.1.4 bundle.
     */
    const val PINEDRAMA_SIGNER_SHA256 = "e97ca0c8835c5a9e4f7702a0382b103ede9c5d51e5194c8db54f4c8833f54e18"

    /**
     * PineDrama 47.1.4's version code, the same as TikTok 47.1.4's. It is PineDrama's newest stable
     * build on APKMirror, so PineDrama stays there while TikTok moves on.
     */
    const val PINEDRAMA_4714_VERSION_CODE = 2024701040

    /** Version codes declared by this Metra catalog, for patches that read the manifest. */
    val DECLARED_VERSION_CODES = TIKTOK_VERSION_CODES

    /**
     * PineDrama 47.1.4 (#156). It's built from TikTok's 47.1.4 code, so the patches whose anchors it
     * keeps apply to it. Only those declare it: the rest anchor on TikTok's feed, LIVE, inbox or
     * resources, which PineDrama changed or dropped. Each of those patches has to match both
     * TikTok's declared build and this one.
     */
    fun pineDrama(): Array<Compatibility> = arrayOf(
        Compatibility(
            name = "PineDrama",
            packageName = PINEDRAMA_PACKAGE,
            appIconColor = TIKTOK_COLOR,
            signatures = setOf(PINEDRAMA_SIGNER_SHA256),
            targets = listOf(
                AppTarget(version = "47.1.4", versionCode = PINEDRAMA_4714_VERSION_CODE),
            ),
        ),
    )

    /** Historical shared helper; this Metra adaptation targets TikTok global only. */
    fun tiktokAndPineDrama(): Array<Compatibility> = tiktok()
}
