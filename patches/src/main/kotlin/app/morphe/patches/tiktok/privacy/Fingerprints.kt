/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * TikTok's own cached lookup of its package: a static method that takes a PackageManager, the
 * package name and flags, calls {@code getPackageInfo(name, flags)} and keeps the answers for a
 * few flag combinations (0, GET_SIGNATURES and GET_META_DATA) in static PackageInfo fields. Every read of TikTok's own signing certificate
 * that TikTok's Java code makes, the AppLog {@code sig_hash} among them, comes through here. The
 * obfuscated name held ("V3") across the declared builds but the class did not, so this matches on
 * shape: exactly one method per fixture.
 */
internal object SelfPackageInfoCacheFingerprint : Fingerprint(
    returnType = "Landroid/content/pm/PackageInfo;",
    parameters = listOf("Landroid/content/pm/PackageManager;", "Ljava/lang/String;", "I"),
    filters = listOf(methodCall(definingClass = "Landroid/content/pm/PackageManager;", name = "getPackageInfo")),
    custom = { method, classDef ->
        // The injected StoreIdentity fallback has the same cache shape. Hook only TikTok's wrapper.
        val native = !classDef.type.startsWith("Lapp/morphe/extension/")
        // The hook reads p0 as the PackageManager, which only a static method's p0 is.
        val static = AccessFlags.STATIC.isSet(method.accessFlags)
        val instructions = method.implementation?.instructions?.toList().orEmpty()
        val callsGetPackageInfo = instructions.any {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { ref ->
                ref.definingClass == "Landroid/content/pm/PackageManager;" &&
                    ref.name == "getPackageInfo" && ref.parameterTypes.size == 2
            } == true
        }
        val cachesPackageInfo = instructions.any {
            it.opcode == Opcode.SPUT_OBJECT &&
                ((it as? ReferenceInstruction)?.reference as? FieldReference)?.type ==
                "Landroid/content/pm/PackageInfo;"
        }
        native && static && callsGetPackageInfo && cachesPackageInfo
    },
)

/**
 * TikTok's lookup of the Pitaya plugin, cached after the first call. Everything the plugin does
 * starts from what this returns: its start-up (the boot executor that loads the native Pitaya
 * libraries, and the real core), and the copy of every app log and CEP event TikTok hands it.
 * Each caller checks for null, which is what it gets when the plugin isn't installed.
 */
internal object PitayaPluginLookupFingerprint : Fingerprint(
    returnType = "Lcom/ss/android/ugc/aweme/pitaya/IPitayaBundle;",
    parameters = listOf(),
    strings = listOf("com.ss.android.ugc.aweme.pitaya.PitayaPluginImpl").map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * The one door Pitaya's real engine comes in by. The Pitaya plugin hands its core provider over
 * here; until it does, every core TikTok asks for is a stand-in that answers "host not ready".
 * PitayaCoreFactory's provider is fixed at class load, and nothing else attaches a real core.
 */
internal object PitayaRealProviderFingerprint : Fingerprint(
    definingClass = "Lcom/bytedance/pitaya/api/mutilinstance/DelegateCoreProvider;",
    name = "setRealProvider",
    returnType = "V",
    parameters = listOf("Lcom/bytedance/pitaya/api/CoreProvider;"),
)

/**
 * TikTok's start of Pitaya Lite, which hands PitayaLite its settings and the device id. Stopped
 * here rather than inside PitayaLite, whose class initializer loads libAndroidPitayaProxy.
 */
internal object PitayaLiteStartFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf("pitaya_lite_settings").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ ->
        method.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == PITAYA_LITE
        } == true
    },
)

internal const val PITAYA_LITE = "Lcom/bytedance/pitaya/api/PitayaLite;"

internal object WebViewTrackingFingerprint : Fingerprint(
    strings = listOf("addJavascriptInterface").map { string(it, StringComparisonType.CONTAINS) },
    definingClass = "Lcom/bytedance/hybrid/",
)
