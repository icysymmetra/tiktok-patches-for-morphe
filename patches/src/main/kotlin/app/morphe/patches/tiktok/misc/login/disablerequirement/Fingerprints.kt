/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/misc/login/disablerequirement/Fingerprints.kt
 */
package app.morphe.patches.tiktok.misc.login.disablerequirement

import app.morphe.patcher.Fingerprint

// Both gates answer a primitive boolean on 46.2.3, 46.7.3, 46.8.3 and 46.9.3, read off each
// fixture with the dexlib2 probe. Saying so here is what makes a build that changed either of
// them to a Boolean object fail to resolve, instead of taking an integer return and failing
// verification on the phone.
// definingClass and name repeat what the custom blocks check, so the search reads only the
// classes that end that way instead of every method of TikTok (#54).
internal object MandatoryLoginServiceFingerprint : Fingerprint(
    definingClass = "/MandatoryLoginService;",
    name = "enableForcedLogin",
    returnType = "Z",
    custom = { method, classDef ->
        classDef.endsWith("/MandatoryLoginService;") && method.name == "enableForcedLogin"
    },
)

internal object MandatoryLoginService2Fingerprint : Fingerprint(
    definingClass = "/MandatoryLoginService;",
    name = "shouldShowForcedLogin",
    returnType = "Z",
    custom = { method, classDef ->
        classDef.endsWith("/MandatoryLoginService;") && method.name == "shouldShowForcedLogin"
    },
)

