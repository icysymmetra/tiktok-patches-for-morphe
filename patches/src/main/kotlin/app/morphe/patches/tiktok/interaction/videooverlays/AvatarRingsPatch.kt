/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val RINGS = "Lapp/morphe/extension/tiktok/feed/AvatarRings;"
private const val AVATAR = "Lcom/ss/android/ugc/aweme/feed/assem/avatar/"
internal const val AVATAR_USER = "Lcom/ss/android/ugc/aweme/profile/model/User;"
private const val AVATAR_AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
internal const val AVATAR_LIVE_DATA_ADAPTER = "Lcom/ss/android/ugc/aweme/live/avatar/AvatarLiveDataAdapter;"

/** The story status every story ring keys on, a plain field getter on every build. */
internal object UserStoryStatusFingerprint : Fingerprint(
    definingClass = AVATAR_USER,
    name = "getStoryStatus",
    parameters = emptyList(),
    returnType = "I",
)

/**
 * The feed avatar's bind: it attaches FeedAvatarLiveAssem when the author live check says yes
 * and FeedAvatarSocialPublishAssem when the author has a story. R8 renames it (bs on 47.0.3, Pr
 * on 47.1.x); it is the wrap's only VideoItemParams-to-void method that names the live assem.
 */
internal object FeedAvatarWrapBindFingerprint : Fingerprint(
    definingClass = "${AVATAR}FeedAvatarAssemWrap;",
    parameters = listOf("Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;"),
    returnType = "V",
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.opcode == Opcode.CONST_CLASS &&
                ((instruction as? ReferenceInstruction)?.reference as? TypeReference)?.type ==
                "${AVATAR}FeedAvatarLiveAssem;"
        } == true
    },
)

private fun MethodReference.isAuthorLiveCheck() =
    returnType == "Z" && parameterTypes.map { it.toString() } == listOf(AVATAR_AWEME, AVATAR_USER)

/**
 * The author live check the feed avatar's bind calls: one static (Aweme, User)Z method, renamed
 * by R8 on every build (09A7.LIZIZ on 47.0.3, 0963.LIZIZ on 47.1.3, 0967.LIZIZ on 47.1.4).
 */
internal fun Method.authorLiveCheck(): MethodReference {
    val calls = implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
        .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        .filter { it.isAuthorLiveCheck() }
        .distinctBy { it.toString() }
    return calls.singleOrNull()
        ?: throw IllegalStateException("Feed avatar bind calls ${calls.size} author live checks: $calls")
}

/** The indices of the move-result that takes each User.isLive answer. */
internal fun List<Instruction>.isLiveResults(): List<Int> = indices.filter { index ->
    index > 0 && this[index].opcode == Opcode.MOVE_RESULT &&
        this[index - 1].opcode.let { it == Opcode.INVOKE_VIRTUAL || it == Opcode.INVOKE_VIRTUAL_RANGE } &&
        ((this[index - 1] as? ReferenceInstruction)?.reference as? MethodReference)?.toString() ==
        "$AVATAR_USER->isLive()Z"
}

@Suppress("unused")
val avatarRingsPatch = bytecodePatch(
    name = "Remove avatar rings",
    description = "Takes the story and LIVE rings off profile pictures, so tapping a picture opens the profile instead of a story or LIVE. Starts off. Turn it on in Metra settings > Interface.",
    default = true,
) {
    category("Feed")
    compatibleWith(*AppCompatibilities.tiktok())
    dependsOn(settingsPatch, sharedExtensionPatch)
    execute {
        UserStoryStatusFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow { opcode == Opcode.RETURN }.forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA
                addInstructionsAtControlFlowLabel(index, """
                    invoke-static/range { v$register .. v$register }, $RINGS->storyStatus(I)I
                    move-result v$register
                """)
            }
        }

        val liveCheck = FeedAvatarWrapBindFingerprint.method.authorLiveCheck()
        mutableClassDefBy(liveCheck.definingClass).methods.single { method ->
            method.name == liveCheck.name && method.returnType == liveCheck.returnType &&
                method.parameterTypes.map { it.toString() } == liveCheck.parameterTypes.map { it.toString() }
        }.apply {
            findInstructionIndicesReversedOrThrow { opcode == Opcode.RETURN }.forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA
                addInstructionsAtControlFlowLabel(index, """
                    invoke-static/range { v$register .. v$register }, $RINGS->authorLive(Z)Z
                    move-result v$register
                """)
            }
        }

        var adapterSites = 0
        mutableClassDefBy(AVATAR_LIVE_DATA_ADAPTER).methods.forEach { method ->
            if (method.implementation == null) return@forEach
            method.instructions.toList().isLiveResults().reversed().forEach { index ->
                val register = method.getInstruction<OneRegisterInstruction>(index).registerA
                method.addInstructions(index + 1, """
                    invoke-static/range { v$register .. v$register }, $RINGS->avatarLive(Z)Z
                    move-result v$register
                """)
                adapterSites++
            }
        }
        check(adapterSites > 0) { "AvatarLiveDataAdapter no longer reads User.isLive" }

        SettingsStatusLoadFingerprint.method.addInstruction(0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableAvatarRings()V")
    }
}
