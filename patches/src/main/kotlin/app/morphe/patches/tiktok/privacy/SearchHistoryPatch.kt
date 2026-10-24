/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstruction
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/privacy/SearchHistoryRecording;"
internal const val SEARCH_HISTORY = "Lcom/ss/android/ugc/aweme/search/model/SearchHistory;"
internal const val MANUAL_SEARCH_PV_STORE =
    "Lcom/ss/android/ugc/aweme/search/pages/middlepage/history/ManualSearchPvStore;"
internal const val MANUAL_SEARCH_USER_STATE =
    "Lcom/ss/android/ugc/aweme/search/pages/middlepage/history/ManualSearchPvStore\$UserState;"

/**
 * The history manager's record method, the one place a new search joins the list the search
 * page shows. The manager is renamed on every build (0D7Z on 47.0.3, 0D1i on 47.1.3, 0D1m on
 * 47.1.4) and reached through an interface, so the callers (typed searches, suggestions, the
 * Lynx search page) all land here. Its log lines open with the real name, as in
 * `recordSearchHistory, repo:`, and no other method loads one; the manager's delete method has
 * the same signature and logs `deleteSearchHistory, repo = `, which is why the signature alone
 * doesn't do.
 */
internal fun isSearchHistoryRecorder(method: Method): Boolean =
    !AccessFlags.STATIC.isSet(method.accessFlags) && !AccessFlags.ABSTRACT.isSet(method.accessFlags) &&
        method.returnType == "V" &&
        method.parameterTypes.map(CharSequence::toString) == listOf(SEARCH_HISTORY, "Ljava/lang/String;") &&
        method.implementation?.instructions?.any {
            it.getReference<StringReference>()?.string?.startsWith("recordSearchHistory,") == true
        } == true

/**
 * ManualSearchPvStore's writer, which keeps a per-account log of each typed query with the
 * times it was searched (64 per query) and saves it. It is the only static (String, String)V
 * on the store, and the only method that sets the account state's real-named
 * `lastHistoryChannelSearchMs`.
 */
internal fun isManualSearchRecorder(method: Method): Boolean =
    method.definingClass == MANUAL_SEARCH_PV_STORE && AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.returnType == "V" &&
        method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "Ljava/lang/String;") &&
        method.implementation?.instructions?.any { instruction ->
            instruction.opcode == Opcode.IPUT_OBJECT &&
                instruction.getReference<FieldReference>()?.let {
                    it.definingClass == MANUAL_SEARCH_USER_STATE && it.name == "lastHistoryChannelSearchMs"
                } == true
        } == true

private object SearchHistoryRecordFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(SEARCH_HISTORY, "Ljava/lang/String;"),
    custom = { method, _ -> isSearchHistoryRecorder(method) },
)

private object ManualSearchRecordFingerprint : Fingerprint(
    definingClass = MANUAL_SEARCH_PV_STORE,
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;"),
    custom = { method, _ -> isManualSearchRecorder(method) },
)

/** Leaves a history writer before it touches anything when the switch is on. */
internal fun MutableMethod.skipWhenHistoryOff() = guardAtEntry(
    "Stop saving search history",
    "invoke-static {}, $EXTENSION->shouldSkip()Z",
    "return-void",
)

/**
 * Keeps new searches out of the search history TikTok saves on the phone. Both writers return
 * before they change anything, so what is already saved stays and can still be deleted from
 * the search page. Searching itself is untouched, and so is whatever TikTok keeps on its
 * servers.
 */
@Suppress("unused")
val searchHistoryPatch = bytecodePatch(
    name = "Stop saving search history",
    description = "Stops TikTok saving your new searches on your phone. Older searches stay " +
        "until you delete them, and TikTok's servers may keep their own record. Starts off. Turn " +
        "it on in Metra settings > App behavior.",
) {
    category("Privacy")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        // Both resolved before anything is written, so a build missing either one leaves the
        // patch unapplied rather than half applied.
        val recorder = SearchHistoryRecordFingerprint.method
        val manual = ManualSearchRecordFingerprint.method
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableSearchHistory()V",
        )
        recorder.skipWhenHistoryOff()
        manual.skipWhenHistoryOff()
    }
}
