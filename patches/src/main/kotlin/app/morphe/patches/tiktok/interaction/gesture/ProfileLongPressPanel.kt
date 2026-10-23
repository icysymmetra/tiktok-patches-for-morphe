/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.gesture

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef

internal const val PROFILE_LONG_PRESS_PANEL = "Lapp/morphe/extension/tiktok/interaction/ProfileLongPressPanel;"
internal const val LPP_PROTOCOL = "Lcom/ss/android/ugc/aweme/longpress/LPPProtocol;"

/**
 * TikTok's long-press panel scene normalizer: a static String to String that maps the AI series
 * page onto the series page and keeps every other name. The panel gate, its structure, its config
 * and its item filter all pass the page name through it before looking it up in the server's
 * long-press panel config. The two strings sit in about twenty classes, so the class holding the
 * config (a static LPPProtocol) is what makes it this one.
 */
internal object LongPressSceneFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/String;"),
    strings = listOf("homepage_ai_series", "homepage_series").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, classDef -> AccessFlags.STATIC.isSet(method.accessFlags) && classDef.holdsPanelConfig() },
)

internal fun ClassDef.holdsPanelConfig() =
    fields.any { AccessFlags.STATIC.isSet(it.accessFlags) && it.type == LPP_PROTOCOL }

/** Hands the page name to the extension first, which answers For You's for a creator's profile (#120). */
internal fun MutableMethod.aliasProfileLongPressScene() {
    if (implementation == null) throw PatchException("Long-press controls: the long-press scene normalizer has no body")
    addInstructions(
        0,
        """
            invoke-static { p0 }, $PROFILE_LONG_PRESS_PANEL->scene(Ljava/lang/String;)Ljava/lang/String;
            move-result-object p0
        """,
    )
}
