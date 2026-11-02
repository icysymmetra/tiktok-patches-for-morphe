/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.antirecording

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.fieldAccess
import app.morphe.util.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.privacy.targetNames
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classesCalling
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/capture/ScreenCapture;"
private const val LAYOUT_PARAMS = "Landroid/view/WindowManager\$LayoutParams;"
private data class CaptureSite(val owner: ClassDef, val method: Method, val index: Int, val replacement: String)

private fun writesLayoutFlags(instruction: Instruction) =
    instruction.opcode == Opcode.IPUT &&
        instruction.getReference<FieldReference>()?.let { it.definingClass == LAYOUT_PARAMS && it.name == "flags" } == true

/**
 * Every write of a layout parameters' flags. Popups and floating windows built from their own
 * parameters, Compose's PopupLayout among them (47.1.4 X.01tC), set the secure flag there and reach
 * the window manager without a Window call. The extension's own writes are left out: hooking them
 * would have the extension call itself.
 */
private object LayoutFlagsWriteFingerprint : Fingerprint(
    // The filter repeats a reference the custom block requires, so the patcher reads only the
    // classes that make it (#54).
    filters = listOf(fieldAccess(definingClass = LAYOUT_PARAMS, name = "flags", opcode = Opcode.IPUT)),
    custom = { method, classDef ->
        !classDef.type.startsWith("Lapp/morphe/extension/") &&
            method.implementation?.instructions?.any(::writesLayoutFlags) == true
    },
)

internal object CircleSearchBlockFingerprint : Fingerprint(
    // This is the experiment key, not the separate show_tag_after_circle_search_block key.
    strings = listOf(string("circle_search_block")),
    name = "invoke", parameters = emptyList(), returnType = "Ljava/lang/Object;",
)

@Suppress("unused")
val allowScreenCapturePatch = bytecodePatch(
    name = "Allow screenshots and Circle to Search",
    description = "Lets screenshots, screen recordings and Circle to Search work on TikTok screens that would otherwise come out black. Starts off. Turn it on in Metra settings > App behavior, then restart TikTok.",
) {
    category("Downloads")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())
    execute {
        val signatures = mapOf(
            "Landroid/view/Window;->addFlags(I)V" to "addFlags(Landroid/view/Window;I)V",
            "Landroid/view/Window;->setFlags(II)V" to "setFlags(Landroid/view/Window;II)V",
            "Landroid/view/Window;->setAttributes(Landroid/view/WindowManager@LayoutParams;)V" to
                "setAttributes(Landroid/view/Window;Landroid/view/WindowManager@LayoutParams;)V",
        ).mapKeys { it.key.replace('@', '$') }.mapValues { it.value.replace('@', '$') }
        val names = targetNames(signatures.keys)
        val callers = classesCalling(signatures.keys.map { it.substringBefore("->") })
        val sites = mutableListOf<CaptureSite>()
        classDefForEach { owner ->
            if (owner.type in callers && !owner.type.startsWith("Lapp/morphe/extension/")) owner.methods.forEach { method ->
                method.implementation?.instructions?.forEachIndexed { index, instruction ->
                    // Opcode, then name, then the whole descriptor: every instruction of TikTok
                    // passes here, and a descriptor per instruction cost seconds a run (#54).
                    if (instruction.opcode != Opcode.INVOKE_VIRTUAL && instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE) {
                        return@forEachIndexed
                    }
                    val reference = instruction.getReference<MethodReference>() ?: return@forEachIndexed
                    if (reference.name !in names) return@forEachIndexed
                    val target = signatures[reference.toString()]
                    if (target != null) {
                        val invoke = when (instruction) {
                            is FiveRegisterInstruction -> {
                                val regs = listOf(instruction.registerC, instruction.registerD, instruction.registerE,
                                    instruction.registerF, instruction.registerG).take(instruction.registerCount)
                                "invoke-static { " + regs.joinToString(", ") { "v$it" } + " }, "
                            }
                            is RegisterRangeInstruction -> "invoke-static/range { v" + instruction.startRegister +
                                " .. v" + (instruction.startRegister + instruction.registerCount - 1) + " }, "
                            else -> throw PatchException(
                                "Allow screenshots and Circle to Search: ${owner.type}->${method.name} " +
                                    "instruction $index is ${instruction.javaClass.simpleName}; expected " +
                                    "a five-register or range Window invocation.",
                            )
                        }
                        sites += CaptureSite(owner, method, index, invoke + EXTENSION + "->" + target)
                    }
                }
            }
        }
        listOf("addFlags", "setFlags", "setAttributes").forEach { name ->
            if (sites.none { it.replacement.contains("->$name(") }) {
                throw PatchException(
                    "Allow screenshots and Circle to Search: no native Window.$name call was found.",
                )
            }
        }
        val layoutWrites = LayoutFlagsWriteFingerprint.matchAllOrNull().orEmpty().map { match ->
            match.method to match.method.implementation!!.instructions.withIndex()
                .filter { writesLayoutFlags(it.value) }.map { it.index }
        }
        if (layoutWrites.none { it.second.isNotEmpty() }) {
            throw PatchException("Allow screenshots and Circle to Search: nothing writes a layout parameters' flags.")
        }
        sites.forEach { site ->
            mutableClassDefBy(site.owner).findMutableMethodOf(site.method).replaceInstruction(site.index, site.replacement)
        }
        // Each store is replaced rather than its value rewritten, because TikTok can read the
        // register again after it. iput's registers are 4-bit, so the five-register form fits.
        layoutWrites.forEach { (method, writes) ->
            writes.forEach { index ->
                val store = method.getInstruction<TwoRegisterInstruction>(index)
                method.replaceInstruction(index,
                    "invoke-static { v${store.registerB}, v${store.registerA} }, $EXTENSION->setLayoutFlags(${LAYOUT_PARAMS}I)V")
            }
        }
        CircleSearchBlockFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow { opcode == Opcode.RETURN_OBJECT }.forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA
                addInstructionsAtControlFlowLabel(index, """
                    invoke-static/range { v$register .. v$register }, $EXTENSION->circleBlock(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$register
                """)
            }
        }
        SettingsStatusLoadFingerprint.method.addInstruction(0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableScreenCapture()V")
    }
}
