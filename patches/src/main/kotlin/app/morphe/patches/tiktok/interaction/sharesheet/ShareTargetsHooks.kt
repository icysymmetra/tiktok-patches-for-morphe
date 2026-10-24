/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.sharesheet

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.cloneMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val WHAT = "Share sheet tools (added apps)"
private const val SHARE_TARGETS = "Lapp/morphe/extension/tiktok/share/ShareTargets;"
internal const val SHARE_CHANNEL_INFO = "Lcom/ss/android/ugc/aweme/share/base/model/ShareChannelInfo;"
private const val STRING = "Ljava/lang/String;"
private const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"
private const val FUNCTION2 = "Lkotlin/jvm/functions/Function2;"
private const val LIST = "Ljava/util/List;"
private const val SHARE_PLATFORM = "Lcom/ss/android/ugc/aweme/share/SharePlatform;"
internal const val VIDEO_SHARE_MODE = "shareMode"
internal const val PHOTO_SHARE_MODE = "photoShareMode"

/**
 * TikTok's channel for a share target its server names: it holds the target's ShareChannelInfo,
 * is built from it alone and answers its key. One class on each declared build.
 */
internal fun ClassDef.isGenericShareChannel(): Boolean =
    fields.any { it.type == SHARE_CHANNEL_INFO } &&
        methods.any { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf(SHARE_CHANNEL_INFO) } &&
        methods.any { it.name == "key" && it.parameterTypes.isEmpty() && it.returnType == STRING }

/**
 * What the extension's three bridges call.
 *
 * @property channel the generic channel class, built from a ShareChannelInfo.
 * @property base its superclass, the package-based channel every app channel extends, which
 *   declares [packageGetter].
 * @property iconField the static lazy holder of TikTok's channel icon map, and [iconValue] the
 *   call that hands the map out.
 */
internal data class ShareTargetMembers(
    val channel: ClassDef,
    val base: ClassDef,
    val packageGetter: Method,
    val iconField: FieldReference,
    val iconValue: MethodReference,
)

/**
 * Reads the members off the generic channel [candidates] (classes passing [isGenericShareChannel]).
 * [classOf] gives a class or null; the patch reads the app with it and the fixture test the APKs,
 * so both run this code.
 */
internal fun shareTargetMembers(candidates: List<ClassDef>, classOf: (String) -> ClassDef?): ShareTargetMembers {
    val channel = candidates.singleOrNull()
        ?: throw PatchException("$WHAT: ${candidates.size} generic share channels, ${candidates.map { it.type }}")
    val getter = channel.methods.singleOrNull { method ->
        method.name != "key" && method.parameterTypes.isEmpty() && method.returnType == STRING &&
            method.readsField(SHARE_CHANNEL_INFO, "packageName")
    } ?: throw PatchException("$WHAT: ${channel.type} has no single getter for its package")
    val base = channel.superclass?.let(classOf)
        ?: throw PatchException("$WHAT: ${channel.type}'s superclass ${channel.superclass} isn't in the app")
    val baseGetter = base.methods.singleOrNull {
        it.name == getter.name && it.parameterTypes.isEmpty() && it.returnType == STRING
    } ?: throw PatchException("$WHAT: ${base.type} doesn't declare ${getter.name}()")
    val icon = channel.methods.filter { it.returnType == DRAWABLE }.firstNotNullOfOrNull { it.iconMapRead() }
        ?: throw PatchException("$WHAT: ${channel.type} reads no channel icon map")
    for ((what, flags) in listOf(
        channel.type to channel.accessFlags,
        "${channel.type}-><init>" to channel.methods.first { it.name == "<init>" && it.parameterTypes.size == 1 }.accessFlags,
        base.type to base.accessFlags,
        "${base.type}->${baseGetter.name}()" to baseGetter.accessFlags,
        icon.first.toString() to (classOf(icon.first.definingClass)?.fields
            ?.firstOrNull { it.name == icon.first.name }?.accessFlags ?: 0),
        icon.first.definingClass to (classOf(icon.first.definingClass)?.accessFlags ?: 0),
    )) {
        if (!AccessFlags.PUBLIC.isSet(flags)) {
            throw PatchException("$WHAT: $what isn't public in this build, and the extension calls it from outside.")
        }
    }
    return ShareTargetMembers(channel, base, baseGetter, icon.first, icon.second)
}

/**
 * In the share sheet's snapshot constructor, the index of the write that stores its finished
 * channel row: the first write of one of the snapshot's own List fields after its single Function2
 * call. That call is the per-channel check against the server's platform list, and it runs after
 * the server-order sort, so a channel added before it is dropped and one added after it stays
 * where it was put. -1 when there's no single such call or no write after it.
 */
internal fun Method.finishedChannelRowStore(): Int {
    val instructions = implementation?.instructions?.toList() ?: return -1
    val checks = instructions.indices.filter { index ->
        val call = (instructions[index] as? ReferenceInstruction)?.reference as? MethodReference
        instructions[index].opcode == Opcode.INVOKE_INTERFACE && call?.definingClass == FUNCTION2 && call.name == "invoke"
    }
    if (checks.size != 1) return -1
    for (index in checks.single() + 1 until instructions.size) {
        if (instructions[index].opcode != Opcode.IPUT_OBJECT) continue
        val field = (instructions[index] as ReferenceInstruction).reference as? FieldReference ?: continue
        if (field.definingClass == definingClass && field.type == LIST) return index
    }
    return -1
}

/** Adds the picked apps to the snapshot's finished channel row, right where it's stored. */
context(patchContext: BytecodePatchContext)
internal fun hookFinishedChannelRow() {
    val method = ShareSnapshotFingerprint.method
    val index = method.finishedChannelRowStore()
    if (index < 0) {
        throw PatchException("$WHAT: the share sheet no longer stores its channel row after the server's check")
    }
    val store = method.getInstruction<TwoRegisterInstruction>(index)
    val field = method.getInstruction<ReferenceInstruction>(index).reference as FieldReference
    val row = store.registerA
    method.addInstructions(
        index + 1,
        """
            invoke-static/range { v$row .. v$row }, $SHARE_TARGETS->withPicked($LIST)$LIST
            move-result-object v$row
            iput-object v$row, v${store.registerB}, ${field.definingClass}->${field.name}:${field.type}
        """,
    )
}

/**
 * One of TikTok's two lookups of a channel key's share mode in the server's platform list:
 * [field] is [VIDEO_SHARE_MODE] for a video, `(key, list)`, and [PHOTO_SHARE_MODE] for a photo
 * post, `(key)`. A key the list doesn't name gets -2, and the sheet drops a channel whose mode
 * isn't 0, the plain link share, so an added app's channel is answered here before the list is.
 */
internal fun Method.isShareModeLookup(field: String): Boolean =
    AccessFlags.STATIC.isSet(accessFlags) && returnType == "I" &&
        parameterTypes.firstOrNull()?.toString() == STRING && readsField(SHARE_PLATFORM, field)

private object VideoShareModeFingerprint : Fingerprint(
    returnType = "I",
    parameters = listOf(STRING, LIST),
    filters = listOf(fieldAccess(definingClass = SHARE_PLATFORM, name = VIDEO_SHARE_MODE)),
    custom = { method, _ -> method.isShareModeLookup(VIDEO_SHARE_MODE) },
)

private object PhotoShareModeFingerprint : Fingerprint(
    returnType = "I",
    parameters = listOf(STRING),
    filters = listOf(fieldAccess(definingClass = SHARE_PLATFORM, name = PHOTO_SHARE_MODE)),
    custom = { method, _ -> method.isShareModeLookup(PHOTO_SHARE_MODE) },
)

/** Gives an added app's channel the plain link share mode on videos and photo posts. */
context(patchContext: BytecodePatchContext)
internal fun hookShareModes() {
    for (fingerprint in listOf(VideoShareModeFingerprint, PhotoShareModeFingerprint)) {
        val method = fingerprint.method
        method.requireLocals(WHAT, 1)
        method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p0 .. p0 }, $SHARE_TARGETS->shareModeOf($STRING)I
                move-result v0
                if-ltz v0, :morphe_tiktok_share_mode
                return v0
            """,
            ExternalLabel("morphe_tiktok_share_mode", method.getInstruction(0)),
        )
    }
}

private fun Method.readsField(owner: String, name: String): Boolean =
    implementation?.instructions?.any { instruction ->
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        field != null && field.definingClass == owner && field.name == name
    } == true

/**
 * The icon map read in a channel's icon method: a static lazy field, its value, and a cast of that
 * value to Map. Null when [this] doesn't read it.
 */
private fun Method.iconMapRead(): Pair<FieldReference, MethodReference>? {
    val instructions = implementation?.instructions?.toList() ?: return null
    for (index in 0 until instructions.size - 3) {
        val read = instructions[index]
        if (read.opcode != Opcode.SGET_OBJECT) continue
        val field = (read as ReferenceInstruction).reference as? FieldReference ?: continue
        val call = instructions[index + 1]
        if (call.opcode != Opcode.INVOKE_INTERFACE) continue
        val value = (call as ReferenceInstruction).reference as? MethodReference ?: continue
        if (value.definingClass != field.type || value.name != "getValue" ||
            value.parameterTypes.isNotEmpty() || value.returnType != "Ljava/lang/Object;"
        ) {
            continue
        }
        val cast = instructions[index + 3]
        if (instructions[index + 2].opcode != Opcode.MOVE_RESULT_OBJECT || cast.opcode != Opcode.CHECK_CAST) continue
        if (((cast as ReferenceInstruction).reference as? TypeReference)?.type != "Ljava/util/Map;") continue
        return field to value
    }
    return null
}

/** Writes TikTok's members into the bodies of ShareTargets' three bridges. */
internal fun BytecodePatchContext.hookShareTargets() {
    val candidates = mutableListOf<ClassDef>()
    classDefForEach { classDef ->
        if (classDef.isGenericShareChannel()) candidates += classDef
    }
    val members = shareTargetMembers(candidates) { type -> classDefByOrNull(type) }
    val channel = members.channel.type
    val base = members.base.type
    val icon = members.iconField

    val targets = mutableClassDefBy(SHARE_TARGETS)
    fun rewrite(name: String, locals: Int, body: String) {
        val original = targets.methods.singleOrNull { it.name == name }
            ?: throw PatchException("$WHAT: ShareTargets has no single $name bridge")
        val bridge = original.cloneMutable(additionalRegisters = locals)
        targets.methods.remove(original)
        targets.methods.add(bridge)
        bridge.addInstructions(0, body)
    }
    rewrite("newChannel", 1, """
        check-cast p0, $SHARE_CHANNEL_INFO
        new-instance v0, $channel
        invoke-direct { v0, p0 }, $channel-><init>($SHARE_CHANNEL_INFO)V
        return-object v0
    """)
    rewrite("packageOf", 1, """
        instance-of v0, p0, $base
        if-eqz v0, :none
        check-cast p0, $base
        invoke-virtual { p0 }, $base->${members.packageGetter.name}()$STRING
        move-result-object v0
        return-object v0
        :none
        const/4 v0, 0x0
        return-object v0
    """)
    rewrite("iconMap", 1, """
        sget-object v0, ${icon.definingClass}->${icon.name}:${icon.type}
        invoke-interface { v0 }, ${members.iconValue.definingClass}->getValue()Ljava/lang/Object;
        move-result-object v0
        return-object v0
    """)
}
