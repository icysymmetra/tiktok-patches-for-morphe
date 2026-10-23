/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

private const val AUTO_STREAK = "Lapp/morphe/extension/tiktok/inbox/AutoStreak;"
private const val MESSENGER = "Lapp/morphe/extension/tiktok/inbox/StreakMessenger;"
private const val RECEIVER = "app.morphe.extension.tiktok.inbox.AutoStreakReceiver"
internal const val QUICK_REPLY_RECEIVER = "Lcom/ss/android/ugc/aweme/im/sdk/notification/PushQuickActionReceiver;"
internal const val IM_CORE_PROXY = "Lcom/bytedance/ies/im/core/api/proxy/IIMCoreProxyService;"

/**
 * The notification quick reply's send: the method on the receiver that reads the chat and the
 * reply text out of the link it is handed. The receiver kept its name and its query keys.
 */
internal object QuickReplySendFingerprint : Fingerprint(
    definingClass = QUICK_REPLY_RECEIVER,
    strings = listOf("conv_id", "reply_text").map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * How the quick reply finds the sender it hands a message to: TikTok's IM core, the chat
 * business on it, and that business's sender. The quick reply sends nothing when any of them
 * is missing, which is exactly the state of a process an alarm has just started.
 */
internal class ReadyLookup(
    val core: MethodReference,
    val business: FieldReference,
    val chat: MethodReference,
    val sender: MethodReference,
)

/** The lookup in the quick reply's send, or a message saying which step is missing. */
internal fun readyLookupIn(method: Method): Result<ReadyLookup> {
    val instructions = method.implementation?.instructions?.toList()
        ?: return Result.failure(IllegalStateException("the send has no body"))
    val business = instructions.firstNotNullOfOrNull { instruction ->
        instruction.takeIf { it.opcode == Opcode.SGET_OBJECT }
            ?.getReference<FieldReference>()?.takeIf { it.name == "TIKTOK_SOCIAL_IM" }
    } ?: return Result.failure(IllegalStateException("no TIKTOK_SOCIAL_IM read"))
    val chats = instructions.withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.INVOKE_INTERFACE &&
            instruction.getReference<MethodReference>()?.let {
                it.definingClass == IM_CORE_PROXY && it.parameterTypes == listOf(business.type) &&
                    it.returnType.startsWith("L")
            } == true
    }
    if (chats.size != 1) {
        return Result.failure(IllegalStateException("${chats.size} chat lookups on the IM core"))
    }
    val (chatIndex, chatCall) = chats.single()
    val chat = chatCall.getReference<MethodReference>()!!
    val core = instructions.subList(0, chatIndex).lastOrNull { instruction ->
        instruction.opcode == Opcode.INVOKE_STATIC &&
            instruction.getReference<MethodReference>()?.let {
                it.parameterTypes.isEmpty() && it.returnType == IM_CORE_PROXY
            } == true
    }?.getReference<MethodReference>()
        ?: return Result.failure(IllegalStateException("no IM core getter before the chat lookup"))
    val sender = instructions.subList(chatIndex + 1, minOf(instructions.size, chatIndex + 6)).firstOrNull { instruction ->
        instruction.opcode == Opcode.INVOKE_INTERFACE &&
            instruction.getReference<MethodReference>()?.let {
                it.definingClass == chat.returnType && it.parameterTypes.isEmpty() && it.returnType.startsWith("L")
            } == true
    }?.getReference<MethodReference>()
        ?: return Result.failure(IllegalStateException("no sender asked for after the chat lookup"))
    return Result.success(ReadyLookup(core, business, chat, sender))
}

private fun Element.child(tag: String, attributes: Map<String, String>): Element {
    val element = ownerDocument.createElement(tag)
    attributes.forEach { (name, value) -> element.setAttribute(name, value) }
    appendChild(element)
    return element
}

/**
 * The alarm's receiver, declared switched off so it starts nothing until the streak is turned
 * on, and the permissions an alarm needs to come on time and to survive a reboot.
 */
private val autoStreakManifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { xml ->
            val manifest = xml.documentElement
            val declared = (0 until xml.getElementsByTagName("uses-permission").length)
                .map { (xml.getElementsByTagName("uses-permission").item(it) as Element).getAttribute("android:name") }
                .toSet()
            // Exact alarms: asked for by name below Android 13, where it is granted on install,
            // and the alarm-clock form from 13 on, which is granted without a prompt.
            mapOf(
                "android.permission.RECEIVE_BOOT_COMPLETED" to null,
                "android.permission.SCHEDULE_EXACT_ALARM" to "32",
                "android.permission.USE_EXACT_ALARM" to null,
            ).forEach { (permission, maxSdk) ->
                if (permission in declared) return@forEach
                val attributes = mutableMapOf("android:name" to permission)
                if (maxSdk != null) attributes["android:maxSdkVersion"] = maxSdk
                val element = xml.createElement("uses-permission")
                attributes.forEach { (name, value) -> element.setAttribute(name, value) }
                manifest.insertBefore(element, manifest.firstChild)
            }

            val application = xml.getElementsByTagName("application").item(0) as? Element
                ?: throw PatchException("Keep a streak going: the manifest has no application")
            val receiver = application.child(
                "receiver",
                mapOf("android:name" to RECEIVER, "android:enabled" to "false", "android:exported" to "false"),
            )
            val filter = receiver.child("intent-filter", emptyMap())
            listOf(
                "android.intent.action.BOOT_COMPLETED",
                "android.intent.action.MY_PACKAGE_REPLACED",
                "android.intent.action.TIME_SET",
                "android.intent.action.TIMEZONE_CHANGED",
            ).forEach { action -> filter.child("action", mapOf("android:name" to action)) }
        }
    }
}

@Suppress("unused")
val autoStreakPatch = bytecodePatch(
    name = "Keep a streak going",
    description = "Sends a daily message you choose to people you pick, at a time you set, so " +
        "your message streaks keep going on days you don't open TikTok. Starts off. Turn it on in " +
        "Metra settings > Interface.",
) {
    category("Inbox")
    dependsOn(settingsPatch, sharedExtensionPatch, autoStreakManifestPatch)

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        val lookup = readyLookupIn(QuickReplySendFingerprint.method).getOrElse {
            throw PatchException("Keep a streak going: the quick reply changed, ${it.message}.")
        }
        val ready = mutableClassDefBy(MESSENGER).methods.singleOrNull {
            it.name == "messagingReady" && it.returnType == "Z" &&
                it.parameterTypes == listOf("Ljava/lang/Object;", "Ljava/lang/Object;")
        } ?: throw PatchException("Keep a streak going: the extension has no messagingReady to fill in.")
        // The quick reply's own lookup, in the stub's two spare parameter registers. What the
        // stub did before this is left behind as unreachable code.
        ready.addInstructionsWithLabels(
            0,
            """
                invoke-static {}, ${lookup.core}
                move-result-object p0
                if-eqz p0, :not_ready
                sget-object p1, ${lookup.business}
                invoke-interface {p0, p1}, ${lookup.chat}
                move-result-object p0
                if-eqz p0, :not_ready
                invoke-interface {p0}, ${lookup.sender}
                move-result-object p0
                if-eqz p0, :not_ready
                const/4 p0, 0x1
                return p0
                :not_ready
                const/4 p0, 0x0
                return p0
            """,
        )

        // Opening TikTok puts back an alarm a reboot or a force stop cleared, and sends a
        // message the alarm missed.
        MainActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $AUTO_STREAK->onAppOpened(Landroid/app/Activity;)V",
        )

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableAutoStreak()V",
        )
    }
}
