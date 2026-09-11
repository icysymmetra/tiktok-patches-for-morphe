package app.morphe.patches.tiktok.interaction.antirecording

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patcher.patch.PatchException
import app.morphe.util.findMutableMethodOf
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction22c
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

private data class ScreenCaptureCallSite(
    val classDef: ClassDef,
    val method: Method,
    val instructionIndexes: List<Int>,
)

private const val WINDOW = "Landroid/view/Window;"
private const val LAYOUT_PARAMS = "Landroid/view/WindowManager\$LayoutParams;"
private const val PROTECTION = "Lapp/morphe/extension/tiktok/capture/ScreenCaptureProtection;"

private fun MethodReference.isWindowFlagWrite() = definingClass == WINDOW && returnType == "V" &&
    when (name) {
        "addFlags" -> parameterTypes == listOf("I")
        "setFlags" -> parameterTypes == listOf("I", "I")
        "setAttributes" -> parameterTypes == listOf("Landroid/view/WindowManager\$LayoutParams;")
        else -> false
    }

@Suppress("unused")
val antiRecordingPatch = resourcePatch(
    name = "Disable screen capture detection",
    description = "Disables capture detection and secure-window screenshot protection, including Circle to Search blocking.",
    default = true,
) {
    compatibleWith(*AppCompatibilities.tiktok4623())

    dependsOn(
        bytecodePatch {
            dependsOn(sharedExtensionPatch)
            execute {
                listOf(
                    antiRecordingAddedFingerprint,
                    antiRecordingRemovedFingerprint,
                ).forEach { fingerprint ->
                    fingerprint.method.returnEarly()
                }

                val callSites = mutableListOf<ScreenCaptureCallSite>()
                val windowSites = mutableListOf<ScreenCaptureCallSite>()
                val layoutParamsFlagSites = mutableListOf<ScreenCaptureCallSite>()
                classDefForEach { classDef ->
                    // Never rewrite the bridge itself: its calls must reach Android directly.
                    if (classDef.type.startsWith("Lapp/morphe/extension/")) return@classDefForEach
                    classDef.methods.forEach { method ->
                        val windowIndexes = mutableListOf<Int>()
                        val layoutParamsFlagIndexes = mutableListOf<Int>()
                        val indexes = method.implementation?.instructions
                            ?.mapIndexedNotNull { index, instruction ->
                                val reference = (instruction as? ReferenceInstruction)?.reference
                                    ?: return@mapIndexedNotNull null
                                if (
                                    instruction.opcode == Opcode.IPUT &&
                                    reference is FieldReference &&
                                    reference.definingClass == LAYOUT_PARAMS &&
                                    reference.name == "flags" &&
                                    reference.type == "I"
                                ) {
                                    layoutParamsFlagIndexes += index
                                }
                                val methodReference = reference as? MethodReference
                                    ?: return@mapIndexedNotNull null
                                if (methodReference.isWindowFlagWrite()) windowIndexes += index
                                if (methodReference.definingClass != "Landroid/app/Activity;") {
                                    return@mapIndexedNotNull null
                                }
                                if (
                                    methodReference.name != "registerScreenCaptureCallback" &&
                                    methodReference.name != "unregisterScreenCaptureCallback"
                                ) {
                                    return@mapIndexedNotNull null
                                }
                                index
                            }
                            .orEmpty()
                        if (indexes.isNotEmpty()) {
                            callSites += ScreenCaptureCallSite(classDef, method, indexes)
                        }
                        if (windowIndexes.isNotEmpty()) {
                            windowSites += ScreenCaptureCallSite(classDef, method, windowIndexes)
                        }
                        if (layoutParamsFlagIndexes.isNotEmpty()) {
                            layoutParamsFlagSites += ScreenCaptureCallSite(classDef, method, layoutParamsFlagIndexes)
                        }
                    }
                }

                if (windowSites.isEmpty()) throw PatchException("No Android window protection boundaries found")
                if (layoutParamsFlagSites.isEmpty()) {
                    throw PatchException("No WindowManager.LayoutParams flags assignments found")
                }
                windowSites.forEach { callSite ->
                    val mutableMethod = mutableClassDefBy(callSite.classDef).findMutableMethodOf(callSite.method)
                    callSite.instructionIndexes.forEach { index ->
                        val instruction = mutableMethod.implementation!!.instructions[index]
                        val reference = (instruction as ReferenceInstruction).reference as MethodReference
                        val bridge = ImmutableMethodReference(
                            PROTECTION, reference.name, listOf(WINDOW) + reference.parameterTypes, "V",
                        )
                        // Receiver becomes static argument 0. Keep every register and code-unit
                        // width, so caller state, branches and exception ranges remain intact.
                        val replacement = when (instruction.opcode) {
                            Opcode.INVOKE_VIRTUAL -> (instruction as FiveRegisterInstruction).let {
                                BuilderInstruction35c(Opcode.INVOKE_STATIC, it.registerCount,
                                    it.registerC, it.registerD, it.registerE, it.registerF, it.registerG, bridge)
                            }
                            Opcode.INVOKE_VIRTUAL_RANGE -> (instruction as RegisterRangeInstruction).let {
                                BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, it.startRegister, it.registerCount, bridge)
                            }
                            else -> throw PatchException("Unexpected window invocation: ${instruction.opcode}")
                        }
                        mutableMethod.replaceInstruction(index, replacement)
                    }
                }

                val layoutParamsFlagBridge = ImmutableMethodReference(
                    PROTECTION,
                    "setLayoutParamsFlags",
                    listOf(LAYOUT_PARAMS, "I"),
                    "V",
                )
                layoutParamsFlagSites.forEach { callSite ->
                    val mutableMethod = mutableClassDefBy(callSite.classDef).findMutableMethodOf(callSite.method)
                    callSite.instructionIndexes.forEach { index ->
                        val instruction = mutableMethod.implementation!!.instructions[index] as Instruction22c
                        // Preserve both registers. RiVanced masks registerA in place before the
                        // field write, which can alter later target behavior when that register is reused.
                        mutableMethod.replaceInstruction(
                            index,
                            BuilderInstruction35c(
                                Opcode.INVOKE_STATIC,
                                2,
                                instruction.registerB,
                                instruction.registerA,
                                0,
                                0,
                                0,
                                layoutParamsFlagBridge,
                            ),
                        )
                    }
                }

                callSites.forEach { callSite ->
                    val mutableMethod = mutableClassDefBy(callSite.classDef)
                        .findMutableMethodOf(callSite.method)
                    callSite.instructionIndexes.forEach { index ->
                        mutableMethod.replaceInstruction(index, "nop")
                    }
                }
            }
        },
    )

    finalize {
        document("AndroidManifest.xml").use { document ->
            document.documentElement.removeElementsByAndroidName("uses-permission", "android.permission.DETECT_SCREEN_CAPTURE")
            document.documentElement.removeElementsByAndroidName("permission", "android.permission.DETECT_SCREEN_CAPTURE")
        }
    }
}

private fun Element.removeElementsByAndroidName(tagName: String, value: String) {
    buildList {
        val nodes = getElementsByTagName(tagName)
        for (index in 0 until nodes.length) {
            (nodes.item(index) as? Element)
                ?.takeIf { it.getAttribute("android:name") == value }
                ?.let(::add)
        }
    }.forEach { element ->
        element.parentNode?.removeChild(element)
    }
}
