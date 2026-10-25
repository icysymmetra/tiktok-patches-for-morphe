/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.looping

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/interaction/StoryControls;"

/** The story pager, a real class name on every declared build. */
internal const val STORY_PAGER =
    "Lcom/ss/android/ugc/aweme/story/feed/common/collection/component/StoryCollectionViewPagerComponent;"

/** The constants of the pager's play mode enum, real names on every declared build. */
internal val STORY_PLAY_MODE_CONSTANTS = setOf(
    "AUTO_PLAY_NEXT_USER",
    "LOOP_CURRENT_USER",
    "LOOP_CURRENT_VIDEO",
    "LOOP_LAST_VIDEO_AFTER_ONE_USER_FINISH",
    "QUIT_AFTER_FINISH",
)

/** True when the class is the play mode enum: an enum that declares every constant above. */
internal fun isStoryPlayModeEnum(classDef: ClassDef): Boolean {
    if (classDef.superclass != "Ljava/lang/Enum;") return false
    val names = classDef.staticFields.map { it.name }.toSet()
    return names.containsAll(STORY_PLAY_MODE_CONSTANTS)
}

/** The pager's play-completed callback: it takes the player's id string and returns nothing. */
internal fun ClassDef.playCompletedMethod() =
    methods.singleOrNull {
        it.name == "onPlayCompleted" && it.returnType == "V" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;") &&
            !AccessFlags.STATIC.isSet(it.accessFlags)
    }

/** Indexes in a method's instructions that read an instance field of the enum's type. */
internal fun Iterable<com.android.tools.smali.dexlib2.iface.instruction.Instruction>.playModeReads(
    enumType: String,
): List<Int> = mapIndexedNotNull { index, instruction ->
    val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
    if (instruction.opcode == Opcode.IGET_OBJECT && field?.type == enumType) index else null
}

@Suppress("unused")
val storyControlsPatch = bytecodePatch(
    name = "Story controls",
    description = "Lets a story replay when it ends instead of moving on, and keeps a photo story on screen until you tap or swipe. Starts off. Turn it on in Metra settings > App behavior.",
    default = true,
) {
    category("Playback")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableStoryControls()V",
        )

        val pager = classDefByOrNull(STORY_PAGER)
            ?: throw PatchException("Story controls: no $STORY_PAGER.")
        val enumType = findPlayModeEnum()
        val mutablePager = mutableClassDefBy(pager)

        // Loop a story: every place the pager reads its play mode gets the switch's answer.
        var reads = 0
        for (method in mutablePager.methods.toList()) {
            val indexes = method.implementation?.instructions?.playModeReads(enumType).orEmpty()
            for (index in indexes.asReversed()) {
                val register = method.getInstruction<OneRegisterInstruction>(index).registerA
                if (register > 15) {
                    throw PatchException("Story controls: the play mode register v$register does not fit the hook.")
                }
                method.addInstructions(
                    index + 1,
                    """
                        invoke-static {v$register}, $EXTENSION->playMode(Ljava/lang/Enum;)Ljava/lang/Enum;
                        move-result-object v$register
                        check-cast v$register, $enumType
                    """,
                )
                reads++
            }
        }
        if (reads == 0) throw PatchException("Story controls: the story pager no longer reads its play mode.")

        // Hold a photo story: the play-completed callback is what moves a story on by itself.
        val completed = pager.playCompletedMethod()
            ?: throw PatchException("Story controls: the story pager has no onPlayCompleted(String).")
        val hook: MutableMethod = mutablePager.findMutableMethodOf(completed)
        val registers = hook.implementation!!.registerCount
        if (registers < 3) {
            throw PatchException(
                "Story controls: onPlayCompleted has $registers registers, none free below its parameters.",
            )
        }
        hook.addInstructionsWithLabels(
            0,
            """
                invoke-static {p0}, $EXTENSION->holdPhotoStory(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :morphe_story_native
                return-void
            """,
            ExternalLabel("morphe_story_native", hook.getInstruction(0)),
        )
    }
}

private fun BytecodePatchContext.findPlayModeEnum(): String {
    val found = mutableListOf<String>()
    classDefForEach { if (isStoryPlayModeEnum(it)) found += it.type }
    if (found.size != 1) {
        throw PatchException("Story controls: expected one story play mode enum, found ${found.size}.")
    }
    return found.single()
}
