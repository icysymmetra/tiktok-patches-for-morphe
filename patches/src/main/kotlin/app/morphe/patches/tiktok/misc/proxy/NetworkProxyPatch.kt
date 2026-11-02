/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.proxy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.HostApplicationAttachBaseContextFingerprint
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/network/NetworkProxy;"

/** The framework call the host application's attachBaseContext makes, which the install follows. */
internal const val FRAMEWORK_ATTACH = "Landroid/app/Application;->attachBaseContext(Landroid/content/Context;)V"

/**
 * TTNet's own key for the proxy its Cronet engine starts with, read from the debug config file
 * TTNet looks for in the APK's assets (ttnet_config.json). Release builds ship no such file, so
 * the read hands back "" and the engine starts with no proxy.
 */
internal const val TTNET_PROXY_KEY = "ttnet_proxy"

internal const val JSON_FROM_STRING = "Lorg/json/JSONObject;-><init>(Ljava/lang/String;)V"

/** How far before `new JSONObject(config)` the config read's result may sit (five on every declared build). */
private const val CONFIG_LOOKBACK = 6

/**
 * TTNet's one builder of TikTok's Cronet engine, org.chromium.CronetClient.tryCreateCronetEngine,
 * on every declared build. TikTok's API client goes through the engine this builds once a process;
 * whether its image loader and LIVE websocket share that engine is a device check. The read sits
 * behind isBOEProxyEnabled(), ByteDance's internal test environment, which is off in release.
 */
internal object CronetEngineCreateFingerprint : Fingerprint(
    definingClass = "Lorg/chromium/CronetClient;",
    name = "tryCreateCronetEngine",
    returnType = "V",
    strings = listOf(TTNET_PROXY_KEY, "boe_proxy_enabled").map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * Where [method] takes in TTNet's debug config: the index of the `move-result-object` after a
 * static `(Context)String` call whose result goes, untouched, into the `new JSONObject(String)`
 * that sits just before the `const-string` of [TTNET_PROXY_KEY]. TikTok hands that field to
 * the engine builder's proxy setter. A read in any other shape isn't counted, so the patch
 * stops instead of wrapping something else.
 */
internal fun ttnetConfigReads(method: Method): List<Int> {
    val instructions = method.implementation?.instructions?.toList() ?: return emptyList()
    return instructions.indices.mapNotNull { keyIndex ->
        val key = instructions[keyIndex]
        if (key.opcode != Opcode.CONST_STRING || key.getReference<StringReference>()?.string != TTNET_PROXY_KEY) {
            return@mapNotNull null
        }
        val parseIndex = keyIndex - 1
        val parse = instructions.getOrNull(parseIndex) ?: return@mapNotNull null
        if (parse.opcode != Opcode.INVOKE_DIRECT || parse.getReference<MethodReference>()?.toString() != JSON_FROM_STRING) {
            return@mapNotNull null
        }
        val config = (parse as FiveRegisterInstruction).registerD
        val written = (parseIndex - 1 downTo maxOf(0, parseIndex - CONFIG_LOOKBACK))
            .firstOrNull { writes(instructions[it], config) } ?: return@mapNotNull null
        if (instructions[written].opcode != Opcode.MOVE_RESULT_OBJECT) return@mapNotNull null
        val call = instructions.getOrNull(written - 1) ?: return@mapNotNull null
        val read = call.getReference<MethodReference>() ?: return@mapNotNull null
        val readsConfig = call.opcode == Opcode.INVOKE_STATIC &&
            read.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;") &&
            read.returnType == "Ljava/lang/String;"
        if (readsConfig) written else null
    }
}

private fun writes(instruction: Instruction, register: Int): Boolean {
    if (!instruction.opcode.setsRegister()) return false
    val target = (instruction as? OneRegisterInstruction)?.registerA ?: return false
    return target == register || (instruction.opcode.setsWideRegister() && target + 1 == register)
}

/**
 * Where the proxy is put in place for the rest of the process: just past the host application's
 * one call to the framework's attachBaseContext, before TikTok's own start-up builds a network
 * client. The shared extension hook anchors on the same call and inserts the context setter
 * there in its finalize, after every patch's execute, so the setter lands ahead of this call
 * and the settings can be read by the time it runs. App lock is placed the same way. The method
 * has to be the application's own, so p1 is the Context.
 */
internal fun proxyInstallIndex(method: Method): Int {
    if (AccessFlags.STATIC.isSet(method.accessFlags)) {
        throw PatchException("Network proxy: the host application's attachBaseContext is static, so p1 isn't its Context.")
    }
    val instructions = method.implementation?.instructions?.toList()
        ?: throw PatchException("Network proxy: the host application's attachBaseContext has no code.")
    val superCalls = instructions.indices.filter { index ->
        instructions[index].getReference<MethodReference>()?.toString() == FRAMEWORK_ATTACH
    }
    if (superCalls.size != 1) {
        throw PatchException(
            "Network proxy: expected one call to Application.attachBaseContext in the host application, found ${superCalls.size}.",
        )
    }
    return superCalls.single() + 1
}

@Suppress("unused")
val networkProxyPatch = bytecodePatch(
    name = "Network proxy",
    description = "Sends TikTok's feed, search and comments through a proxy server you set up. Videos and LIVEs still load directly, and so do Metra's own saves and short-link lookups. Other apps aren't affected. Starts off. Turn it on in Metra settings > Region spoof, then restart TikTok.",
) {
    category("Settings")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        // Everything is found and checked before the first write: the patcher doesn't take a
        // failed patch's writes back out.
        val engine = CronetEngineCreateFingerprint.method
        val reads = ttnetConfigReads(engine)
        if (reads.size != 1) {
            throw PatchException(
                "Network proxy: ${engine.definingClass}->${engine.name} no longer reads TTNet's " +
                    "debug config into the JSON its $TTNET_PROXY_KEY field comes from exactly once (found ${reads.size}).",
            )
        }
        val host = HostApplicationAttachBaseContextFingerprint.method
        val installAt = proxyInstallIndex(host)

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableNetworkProxy()V",
        )
        // A range invoke, so the parameter register's number never has to fit a 4-bit operand.
        host.addInstruction(
            installAt,
            "invoke-static/range { p1 .. p1 }, $EXTENSION->install(Landroid/content/Context;)V",
        )
        // The config TikTok is about to parse comes back with the proxy in its ttnet_proxy
        // field, and TikTok's own code hands that to the engine builder.
        val resultIndex = reads.single()
        val register = engine.getInstruction<OneRegisterInstruction>(resultIndex).registerA
        engine.addInstructions(
            resultIndex + 1,
            """
                invoke-static/range { v$register .. v$register }, $EXTENSION->ttnetConfig(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v$register
            """,
        )
    }
}
