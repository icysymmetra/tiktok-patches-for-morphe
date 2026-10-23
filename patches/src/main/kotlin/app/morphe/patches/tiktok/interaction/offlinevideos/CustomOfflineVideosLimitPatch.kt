/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.offlinevideos

import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.patcher.util.getReference
import app.morphe.util.implementationOrPatchException
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH_NAME = "Custom offline videos limit"

private const val CUSTOM_OFFLINE_VIDEOS_HELPER =
    "Lapp/morphe/extension/tiktok/offline/CustomOfflineVideosLimitPatch;"

/** The option enum's constructor: the name, the ordinal, the limit, the minutes and the size. */
private val OPTION_CONSTRUCTOR = listOf("Ljava/lang/String;", "I", "I", "I", "I")

/** The exact range constructor whose final three arguments hold the configurable values. */
internal fun isCustomOfflineOptionConstructor(instruction: Instruction, optionEnum: String): Boolean {
    if (instruction.opcode != Opcode.INVOKE_DIRECT_RANGE) return false
    val range = instruction as? RegisterRangeInstruction ?: return false
    val reference = instruction.getReference<MethodReference>() ?: return false
    return range.registerCount == OPTION_CONSTRUCTOR.size + 1 &&
        reference.definingClass == optionEnum &&
        reference.name == "<init>" &&
        reference.returnType == "V" &&
        reference.parameterTypes.map(CharSequence::toString) == OPTION_CONSTRUCTOR
}

@Suppress("unused")
val customOfflineVideosLimitPatch = bytecodePatch(
    name = "Custom offline videos limit",
    description = "Lets you pick how many videos TikTok saves for watching offline, from 1 to " +
        "10,000, and keep them until you delete them. Handy when you're often without internet. " +
        "Starts off. Turn it on in Metra settings > Downloads.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        // Everything the writes below need is found and checked before the first of them, because
        // the patcher does not take a failed patch's writes back out.

        // Without this the two switches were gated on the Downloads patch's flag, so
        // selecting this patch alone left the feature with no way to turn it on, and
        // selecting Downloads alone showed two switches that did nothing.
        val settingsStatus = SettingsStatusLoadFingerprint.method

        // The sheet's own choices, frozen from an array in its initialiser.
        val sheet = OfflineModeSheetOptionsFingerprint.method
        val freezeListIndex = sheet.indexOfFirstInstructionOrThrow {
            opcode == Opcode.INVOKE_STATIC &&
                getReference<MethodReference>()?.let { reference ->
                    reference.parameterTypes == listOf("[Ljava/lang/Object;") &&
                        reference.returnType == "Ljava/util/List;"
                } == true
        }
        val sheetResultIndex = sheet.indexOfFirstInstructionOrThrow(freezeListIndex + 1) {
            opcode == Opcode.MOVE_RESULT_OBJECT
        }
        val sheetRegister = sheet.getInstruction<OneRegisterInstruction>(sheetResultIndex).registerA

        // The lists the offline settings read. Their holder is renamed per build and was rebuilt
        // between them, so it is found through the detail page, which asks it a static question
        // as its view is made: the one class it asks a no-argument boolean whose initialiser
        // builds static Lists out of boxed counts.
        val detail = OfflineModeDetailViewCreatedFingerprint.method
        val asked = detail.implementationOrPatchException("Custom offline videos limit")
            .instructions.mapNotNull { instruction ->
                if (instruction.opcode != Opcode.INVOKE_STATIC) return@mapNotNull null
                val call = instruction.getReference<MethodReference>() ?: return@mapNotNull null
                call.definingClass.takeIf { call.parameterTypes.isEmpty() && call.returnType == "Z" }
            }.distinct()
        val holders = asked.filter { type ->
            classDefByOrNull(type)?.methods?.firstOrNull { it.name == "<clinit>" }
                ?.let { optionListWrites(it, type).isNotEmpty() && it.boxesCounts() } == true
        }
        val holder = holders.singleOrNull() ?: throw PatchException(
            "Custom offline videos limit: expected the offline detail page to ask one class " +
                "that builds the option lists, found ${holders.size} among $asked.",
        )
        val holderInit = mutableClassDefBy(holder).methods.filter { it.name == "<clinit>" }
            .singleOrPatchException("Custom offline videos limit: $holder class initialiser")
        val listResults = optionListResults(holderInit, holder)

        // The download limit choices: an enum found by the constants it declares, whose custom
        // entry is built with the limit, the minutes and the size the extension overrides.
        val options = OfflineModeOptionEnumFingerprint.method
        // The enum's own type, off the match. It was written here as LX/0mE9;, which is the
        // 46.2.3 name and nothing else.
        val optionEnum = options.definingClass
        val customEnumFieldWriteIndex = options.indexOfFirstInstructionOrThrow {
            opcode == Opcode.SPUT_OBJECT &&
                getReference<FieldReference>()?.let { field ->
                    field.definingClass == optionEnum &&
                        field.name == "DOWNLOAD_200_VIDEOS" &&
                        field.type == optionEnum
                } == true
        }
        // Six registers (the new value, the name, the ordinal and three numbers) only fit the
        // range form, which is the one the three builds use. A plain invoke used to be accepted
        // here and then cast to a range, which would have thrown instead of saying so.
        val optionInstructions = options.implementationOrPatchException("Custom offline videos limit")
            .instructions.toList()
        val customEnumConstructorIndex = optionInstructions
            .take(customEnumFieldWriteIndex)
            .withIndex()
            .lastOrNull { (_, instruction) -> isCustomOfflineOptionConstructor(instruction, optionEnum) }
            ?.index ?: throw PatchException(
            "Custom offline videos limit: no six-register /range $optionEnum constructor with " +
                "parameters $OPTION_CONSTRUCTOR precedes DOWNLOAD_200_VIDEOS.",
        )
        val constructor = options.getInstruction<RegisterRangeInstruction>(customEnumConstructorIndex)
        val limitRegister = constructor.startRegister + 3
        val minutesRegister = limitRegister + 1
        val sizeRegister = limitRegister + 2

        // How long an offline video lives before TikTok hides it and deletes its file (#123).
        // Required on a declared build, where OfflineVideoLifetimeAnchorsTest holds it, and left
        // out with a note on any other, so a renamed lifetime doesn't take the limit down with it.
        val lifetime = try {
            OfflineVideoLifetimeFingerprint.method.also { it.requireLocals(PATCH_NAME, 2) }
        } catch (problem: Exception) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            println("[$PATCH_NAME] Left out Keep offline videos on ${packageMetadata.versionName}: ${problem.message}")
            null
        }

        // The same switch keeps TikTok's Auto adjust and its default-on clean-up from trimming or
        // clearing the list at start (#123). All three are required together on a declared build
        // and left out together, with a note, on any other.
        val tierGuards = try {
            Triple(
                AutoAdjustBootFingerprint.method.also { it.requireLocals(PATCH_NAME, 1) },
                AutoAdjustRollbackFingerprint.method.also { it.requireLocals(PATCH_NAME, 1) },
                DefaultEnableStateFingerprint.method.also { it.defaultEnableCleanupReturnIndex(PATCH_NAME) },
            )
        } catch (problem: Exception) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            println("[$PATCH_NAME] Left out Keep offline videos through Auto adjust on ${packageMetadata.versionName}: ${problem.message}")
            null
        }

        settingsStatus.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableCustomOfflineVideos()V",
        )

        sheet.addInstructions(sheetResultIndex + 1, wrapOptions(sheetRegister))

        // Highest first, so the indices below stay where they were read.
        for (resultIndex in listResults.sortedDescending()) {
            val register = holderInit.getInstruction<OneRegisterInstruction>(resultIndex).registerA
            holderInit.addInstructions(resultIndex + 1, wrapOptions(register))
        }

        options.addInstructions(
            customEnumConstructorIndex,
            """
                invoke-static/range {v$limitRegister .. v$limitRegister}, $CUSTOM_OFFLINE_VIDEOS_HELPER->getCustomOfflineVideoLimitOrOriginal(I)I
                move-result v$limitRegister
                invoke-static/range {v$minutesRegister .. v$minutesRegister}, $CUSTOM_OFFLINE_VIDEOS_HELPER->getCustomOfflineVideoMinutesOrOriginal(I)I
                move-result v$minutesRegister
                invoke-static/range {v$sizeRegister .. v$sizeRegister}, $CUSTOM_OFFLINE_VIDEOS_HELPER->getCustomOfflineVideoSizeMbOrOriginal(I)I
                move-result v$sizeRegister
            """,
        )

        if (lifetime != null) {
            tierGuards?.let { (autoAdjust, rollback, defaultEnable) ->
                autoAdjust.keepThroughAutoAdjust(PATCH_NAME)
                rollback.keepThroughAutoAdjust(PATCH_NAME)
                defaultEnable.keepThroughDefaultEnableCleanup(PATCH_NAME)
            }
            lifetime.keepOfflineVideos(PATCH_NAME)
            settingsStatus.addInstruction(
                0,
                "invoke-static {}, " +
                    "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableKeepOfflineVideos()V",
            )
        }
    }
}

/** The list in [register], handed to the extension to have the custom limit added to it. */
private fun wrapOptions(register: Int) =
    """
        invoke-static/range {v$register .. v$register}, $CUSTOM_OFFLINE_VIDEOS_HELPER->getOfflineVideoOptions(Ljava/util/List;)Ljava/util/List;
        move-result-object v$register
    """

/** Whether the initialiser boxes ints, which is what the counts in the option lists are. */
private fun Method.boxesCounts() = implementation?.instructions?.any { instruction ->
    instruction.getReference<MethodReference>()?.let {
        it.definingClass == "Ljava/lang/Integer;" && it.name == "valueOf" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("I")
    } == true
} == true

/** Every `sput-object` in [init] to a static List of [holder]'s own. */
internal fun optionListWrites(init: Method, holder: String): List<Int> =
    init.implementation?.instructions?.withIndex()?.filter { (_, instruction) ->
        instruction.opcode == Opcode.SPUT_OBJECT &&
            instruction.getReference<FieldReference>()?.let {
                it.definingClass == holder && it.type == "Ljava/util/List;"
            } == true
    }?.map { it.index }.orEmpty()

/**
 * Where each option list is handed over in [init]: the `move-result-object` of the call that
 * freezes an array into a List, right before it is stored. Two lists on 46.2.3 and four on
 * 46.8.3, and every one of them is a list the offline settings may show, so every one gets the
 * custom limit. A static List of the holder's stored any other way is refused rather than
 * skipped, because a skipped one is a menu that silently lacks the entry.
 */
internal fun optionListResults(init: Method, holder: String): List<Int> {
    val instructions = init.implementation?.instructions?.toList()
        ?: throw PatchException("Custom offline videos limit: the option holder has no initialiser body.")
    val writes = optionListWrites(init, holder)
    if (writes.isEmpty()) {
        throw PatchException("Custom offline videos limit: $holder stores no option list.")
    }
    return writes.map { writeIndex ->
        val write = instructions[writeIndex]
        val stored = (write as? OneRegisterInstruction)?.registerA
            ?: throw PatchException(
                "Custom offline videos limit: $holder option-list write at $writeIndex has no register.",
            )
        val result = instructions.getOrNull(writeIndex - 1)
        val freeze = instructions.getOrNull(writeIndex - 2)?.getReference<MethodReference>()
        val field = write.getReference<FieldReference>()?.name
            ?: throw PatchException(
                "Custom offline videos limit: $holder option-list write at $writeIndex has no field.",
            )
        if (result?.opcode != Opcode.MOVE_RESULT_OBJECT ||
            (result as OneRegisterInstruction).registerA != stored ||
            freeze?.returnType != "Ljava/util/List;" ||
            freeze.parameterTypes.map(CharSequence::toString) != listOf("[Ljava/lang/Object;")
        ) {
            throw PatchException(
                "Custom offline videos limit: $holder stores $field without freezing it from an " +
                    "array straight before, so there is no list to add the custom limit to.",
            )
        }
        writeIndex - 1
    }
}
