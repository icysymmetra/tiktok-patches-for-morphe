/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionFilter
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.HostApplicationAttachBaseContextFingerprint
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/privacy/DevicePrivacyGuard;"
private const val CLIPBOARD = "Landroid/content/ClipboardManager;"
private const val NETWORK_CAPABILITIES = "Landroid/net/NetworkCapabilities;"
private const val NETWORK_INTERFACE = "Ljava/net/NetworkInterface;"
private const val AD_ID_INFO = "Lcom/google/android/gms/ads/identifier/AdvertisingIdClient\$Info;"
private const val STRING = "Ljava/lang/String;"
private const val PARCEL = "Landroid/os/Parcel;"
private const val DEVICE_IDENTITY = "Lapp/morphe/extension/tiktok/privacy/DeviceIdentity;"
private const val CONTENT_RESOLVER = "Landroid/content/ContentResolver;"
private const val SETTINGS_SECURE = "Landroid/provider/Settings\$Secure;"
private const val SETTINGS_SYSTEM = "Landroid/provider/Settings\$System;"

/** The interface token a client writes before asking Google's advertising id service anything. */
internal const val AD_ID_SERVICE_TOKEN = "com.google.android.gms.ads.identifier.internal.IAdvertisingIdService"
private const val BINDER_TRANSACT = "Landroid/os/IBinder;->transact(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z"

/** The service's transaction codes: 1 answers the id, 2 whether ad tracking is limited. */
private const val GET_ID = 1
private const val IS_LIMIT_AD_TRACKING_ENABLED = 2

/**
 * What a hooked read hands TikTok, and the extension method that answers it. Each takes the value
 * the read produced and returns the one TikTok gets, the same type, so the answer goes back into
 * the register the read wrote.
 */
internal enum class AdIdRead(val hook: String, val moveResult: String) {
    /** The id, off the service's reply (readString) or Info's id field. */
    ID("$EXTENSION->interceptAdvertisingIdRead($STRING)$STRING", "move-result-object"),

    /** The limit flag as the service's reply carries it, an int where nonzero means limited. */
    LIMIT_REPLY("$EXTENSION->interceptLimitAdTrackingReply(I)I", "move-result"),

    /** The limit flag off Info's boolean field, where R8 inlined isLimitAdTrackingEnabled. */
    LIMIT_FIELD("$EXTENSION->interceptLimitAdTracking(Z)Z", "move-result"),
}

/** One read: the instruction at [index] leaves the value in [register], and the hook goes right after it. */
internal data class AdIdReadSite(val index: Int, val register: Int, val read: AdIdRead)

/**
 * The reads in [method] that take the advertising id or its limit flag straight off Google's
 * service, past Play Services: the method writes the service's interface token, calls
 * IBinder.transact with code 1 or 2, and reads the reply parcel with readString (the id) or
 * readInt (the flag), after readException. Each site is the move-result of that read. Empty for
 * any other method, Play Services' own proxy included (it keeps the token in a field, and its
 * answer reaches TikTok through Info, which [advertisingInfoFieldReads] and the getId hook cover).
 */
internal fun rawAdvertisingIdReads(method: Method): List<AdIdReadSite> {
    val instructions = method.implementation?.instructions?.toList() ?: return emptyList()
    if (instructions.none { it.getReference<StringReference>()?.string == AD_ID_SERVICE_TOKEN }) return emptyList()
    val sites = mutableListOf<AdIdReadSite>()
    instructions.forEachIndexed { index, instruction ->
        if (instruction.opcode != Opcode.INVOKE_INTERFACE && instruction.opcode != Opcode.INVOKE_INTERFACE_RANGE) return@forEachIndexed
        if (instruction.getReference<MethodReference>()?.toString() != BINDER_TRANSACT) return@forEachIndexed
        // transact(code, data, reply, flags) on the binder: registers are binder, code, data, reply, flags.
        val registers = invokeRegisters(instruction) ?: return@forEachIndexed
        if (registers.size != 5) return@forEachIndexed
        val (read, reader, result) = when (constantBefore(instructions, index, registers[1])) {
            GET_ID -> Triple(AdIdRead.ID, "readString", Opcode.MOVE_RESULT_OBJECT)
            IS_LIMIT_AD_TRACKING_ENABLED -> Triple(AdIdRead.LIMIT_REPLY, "readInt", Opcode.MOVE_RESULT)
            else -> return@forEachIndexed
        }
        val reply = registers[3]
        for (next in index + 1 until instructions.size - 1) {
            val call = instructions[next].getReference<MethodReference>() ?: continue
            if (call.definingClass != PARCEL || invokeRegisters(instructions[next])?.singleOrNull() != reply) continue
            if (call.name == "readException") continue
            val moved = instructions[next + 1]
            if (call.name == reader && moved.opcode == result) {
                sites += AdIdReadSite(next + 1, (moved as OneRegisterInstruction).registerA, read)
            }
            break
        }
    }
    return sites
}

/**
 * The reads of AdvertisingIdClient.Info's two fields in [method]: iget-boolean of its limit flag,
 * which is all that's left of isLimitAdTrackingEnabled after R8 inlined it, and iget-object of its
 * id (getId's own body and toString). Each site is the iget itself.
 */
internal fun advertisingInfoFieldReads(method: Method): List<AdIdReadSite> =
    method.implementation?.instructions?.toList().orEmpty().mapIndexedNotNull { index, instruction ->
        val (read, type) = when (instruction.opcode) {
            Opcode.IGET_BOOLEAN -> AdIdRead.LIMIT_FIELD to "Z"
            Opcode.IGET_OBJECT -> AdIdRead.ID to STRING
            else -> return@mapIndexedNotNull null
        }
        val field = instruction.getReference<FieldReference>() ?: return@mapIndexedNotNull null
        if (field.definingClass != AD_ID_INFO || field.type != type) return@mapIndexedNotNull null
        AdIdReadSite(index, (instruction as OneRegisterInstruction).registerA, read)
    }

/**
 * Info holds one id and one flag, so its one String field is the id and its one boolean field is
 * whether ad tracking is limited. R8 renames both, so the patch goes by type and holds the shape.
 */
internal fun isAdvertisingInfoShape(info: ClassDef): Boolean =
    info.instanceFields.count { it.type == STRING } == 1 && info.instanceFields.count { it.type == "Z" } == 1

/** The registers an invoke passes, in order, in either form. */
private fun invokeRegisters(instruction: Instruction): List<Int>? = when (instruction) {
    is FiveRegisterInstruction -> listOf(
        instruction.registerC, instruction.registerD, instruction.registerE,
        instruction.registerF, instruction.registerG,
    ).take(instruction.registerCount)
    is RegisterRangeInstruction -> (0 until instruction.registerCount).map { instruction.startRegister + it }
    else -> null
}

/** The constant last written to [register] before [index], or null when something else wrote it. */
private fun constantBefore(instructions: List<Instruction>, index: Int, register: Int): Int? {
    for (back in index - 1 downTo 0) {
        val instruction = instructions[back]
        if (!instruction.opcode.setsRegister() || (instruction as? OneRegisterInstruction)?.registerA != register) continue
        return (instruction as? NarrowLiteralInstruction)?.narrowLiteral
    }
    return null
}

/** The types of the classes holding an instruction [filter] matches, read off the patcher's index. */
private fun BytecodePatchContext.classesWith(filter: InstructionFilter): Set<String> =
    Fingerprint(filters = listOf(filter)).matchAllOrNull().orEmpty().mapTo(HashSet()) { it.originalClassDef.type }

/**
 * Answers every read [sitesOf] finds in the classes of [types] in place: a range call hands the
 * value the read left in its register to the extension, and the answer is moved back into that
 * same register, so no other register is touched. A range call takes any register number, which
 * an iget's destination may be. Sites go in from the last, so an earlier index still holds.
 */
private fun BytecodePatchContext.answerReads(
    types: Set<String>,
    sitesOf: (Method) -> List<AdIdReadSite>,
): List<AdIdReadSite> {
    val methods = mutableListOf<Pair<ClassDef, Method>>()
    classDefForEach { owner ->
        if (owner.type !in types || owner.type.startsWith("Lapp/morphe/extension/")) return@classDefForEach
        owner.methods.forEach { method -> if (sitesOf(method).isNotEmpty()) methods += owner to method }
    }
    val answered = mutableListOf<AdIdReadSite>()
    methods.forEach { (owner, method) ->
        val mutable = mutableClassDefBy(owner).findMutableMethodOf(method)
        // Read again off the method being changed, in case an earlier hook moved its instructions.
        val sites = sitesOf(mutable)
        sites.sortedByDescending { it.index }.forEach { site ->
            mutable.addInstructions(
                site.index + 1,
                """
                    invoke-static/range { v${site.register} .. v${site.register} }, ${site.read.hook}
                    ${site.read.moveResult} v${site.register}
                """,
            )
        }
        answered += sites
    }
    return answered
}

@Suppress("unused")
val devicePrivacyGuardPatch = bytecodePatch(
    name = "Device privacy guard",
    description = "Stops TikTok reading what you copied and gives it a blank advertising ID. " +
        "Can also hide a VPN connection and root status with details about root tools, " +
        "and give TikTok a new device identity. " +
        "Each switch starts off in Metra settings > App behavior. Changing Hide root needs a restart.",
) {
    category("Privacy")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableDevicePrivacyGuard()V",
        )

        // One replacement per signature. A single intercept returning ClipData for all three
        // put a ClipData where getText's CharSequence and hasPrimaryClip's boolean were
        // expected, which the verifier rejects as soon as the class loads.
        val replacements = mapOf(
            "$CLIPBOARD->getPrimaryClip()Landroid/content/ClipData;" to
                "$EXTENSION->interceptPrimaryClip($CLIPBOARD)Landroid/content/ClipData;",
            "$CLIPBOARD->getText()Ljava/lang/CharSequence;" to
                "$EXTENSION->interceptClipboardText($CLIPBOARD)Ljava/lang/CharSequence;",
            "$CLIPBOARD->hasPrimaryClip()Z" to
                "$EXTENSION->interceptHasPrimaryClip($CLIPBOARD)Z",
        )
        val sites = invokeSitesOf(replacements.keys)
        if (sites.isEmpty()) {
            throw PatchException("Device privacy guard: no clipboard read call site was found.")
        }
        replaceSites(sites, replacements)
        println("[Device privacy guard] Intercepted ${sites.size} clipboard read sites.")

        // VPN and advertising id, both virtual calls. hasTransport is answered false only for the
        // VPN transport, every other transport untouched; the Info.getId read is answered with the
        // blank id. The site hands the Info in as an Object, since the extension doesn't compile
        // against Play Services. One replacement per signature, each returning the type read back.
        val vpnAndAdId = mapOf(
            "$NETWORK_CAPABILITIES->hasTransport(I)Z" to
                "$EXTENSION->interceptHasTransport(${NETWORK_CAPABILITIES}I)Z",
            "$AD_ID_INFO->getId()Ljava/lang/String;" to
                "$EXTENSION->interceptAdvertisingId(Ljava/lang/Object;)Ljava/lang/String;",
        )
        val vpnAdSites = invokeSitesOf(vpnAndAdId.keys)
        if (vpnAdSites.none { it.target.startsWith(NETWORK_CAPABILITIES) }) {
            throw PatchException("Device privacy guard: no NetworkCapabilities.hasTransport call site was found.")
        }
        if (vpnAdSites.none { it.target.startsWith(AD_ID_INFO) }) {
            throw PatchException("Device privacy guard: no advertising id read call site was found.")
        }
        replaceSites(vpnAdSites, vpnAndAdId)

        // getNetworkInterfaces is static, so its sites are read on their own and the replacement
        // takes no receiver. It drops the tunnel interfaces a VPN adds.
        val interfaces = mapOf(
            "$NETWORK_INTERFACE->getNetworkInterfaces()Ljava/util/Enumeration;" to
                "$EXTENSION->interceptNetworkInterfaces()Ljava/util/Enumeration;",
        )
        val interfaceSites = invokeSitesOf(interfaces.keys, static = true)
        if (interfaceSites.isEmpty()) {
            throw PatchException("Device privacy guard: no NetworkInterface.getNetworkInterfaces call site was found.")
        }
        replaceSites(interfaceSites, interfaces)
        println(
            "[Device privacy guard] Intercepted ${vpnAdSites.size} VPN and advertising-id sites " +
                "and ${interfaceSites.size} network-interface sites.",
        )

        // A new device identity. Its wipe runs before anything else in the application's
        // attachBaseContext, ahead of TikTok's first read of a device id, and its Android ID
        // answers TikTok's Settings.Secure reads, which every part of TikTok makes through one
        // privacy wrapper (one call site on 47.4.3 and on PineDrama 47.1.4). Settings.System is
        // where old Android versions answered it too; TikTok reads it there through the same
        // wrapper, so it's answered when found and not required. The range form reaches p1 at any
        // register count, which the four-bit form only does while p1 is v15 or lower.
        HostApplicationAttachBaseContextFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p1 .. p1 }, $DEVICE_IDENTITY->start(Landroid/content/Context;)V",
        )
        val androidIdReads = mapOf(
            "$SETTINGS_SECURE->getString(${CONTENT_RESOLVER}$STRING)$STRING" to
                "$EXTENSION->interceptSecureString(${CONTENT_RESOLVER}$STRING)$STRING",
            "$SETTINGS_SYSTEM->getString(${CONTENT_RESOLVER}$STRING)$STRING" to
                "$EXTENSION->interceptSystemString(${CONTENT_RESOLVER}$STRING)$STRING",
        )
        val androidIdSites = invokeSitesOf(androidIdReads.keys, static = true)
        if (androidIdSites.none { it.target.startsWith(SETTINGS_SECURE) }) {
            throw PatchException("Device privacy guard: no Settings.Secure.getString call site was found.")
        }
        replaceSites(androidIdSites, androidIdReads)
        println("[Device privacy guard] Intercepted ${androidIdSites.size} Settings string reads for a new device identity.")

        // The rest of the advertising id, the reads that never call Info.getId. Two SDKs ask
        // Google's service for the id and the limit flag themselves, and R8 inlined
        // isLimitAdTrackingEnabled into a read of Info's boolean field wherever TikTok asks it.
        // With the switch on they all get what Android answers once the user deletes the id:
        // the blank id, and limited. These run last so no site collected above has moved.
        val info = classDefByOrNull(AD_ID_INFO)
            ?: throw PatchException("Device privacy guard: AdvertisingIdClient\$Info is missing.")
        if (!isAdvertisingInfoShape(info)) {
            throw PatchException("Device privacy guard: AdvertisingIdClient\$Info no longer holds one id and one limit flag.")
        }
        val serviceReads = answerReads(classesWith(string(AD_ID_SERVICE_TOKEN)), ::rawAdvertisingIdReads)
        if (serviceReads.none { it.read == AdIdRead.ID } || serviceReads.none { it.read == AdIdRead.LIMIT_REPLY }) {
            throw PatchException("Device privacy guard: the direct advertising id service reads were not found.")
        }
        val fieldReads = answerReads(classesWith(fieldAccess(definingClass = AD_ID_INFO)), ::advertisingInfoFieldReads)
        if (fieldReads.none { it.read == AdIdRead.LIMIT_FIELD }) {
            throw PatchException("Device privacy guard: no read of the advertising id's limit flag was found.")
        }
        println(
            "[Device privacy guard] Answered ${serviceReads.size} direct advertising id service reads " +
                "and ${fieldReads.size} advertising id field reads.",
        )
        hookRootSignals()
    }
}
