/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/misc/settings/EnableOpenDebugPatch.kt
 */
package app.morphe.patches.tiktok.misc.settings

import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.util.findMutableMethodOf
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method as SmaliMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val SETTINGS_EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/settings/TikTokActivityHook;"
private const val OPEN_DEBUG_CELL_VM_DESCRIPTOR =
    "Lcom/ss/android/ugc/aweme/setting/ui/rvmpcompose/group/support/cells/OpenDebugCellVM;"

private const val ANDROID_CONTEXT_GET_STRING = "Landroid/content/Context;->getString(I)Ljava/lang/String;"

private data class OpenDebugTargets(
    val stateClass: String,
    val composeMutable: MutableMethod,
)

@Suppress("unused")
val settingsPatch = bytecodePatch(
    name = "Settings",
    description = "Adds the Metra patches settings menu to TikTok. Supports TikTok 47.1.4.",
    default = true,
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok4714())

    execute {
        addLegacySettingsEntryFallback()

        val initializeSettingsMethodDescriptor =
            "$SETTINGS_EXTENSION_CLASS_DESCRIPTOR->initialize(" +
                "Lcom/bytedance/ies/ugc/aweme/commercialize/compliance/personalization/AdPersonalizationActivity;" +
                ")Z"

        fun isOpenDebugRowCompose(method: SmaliMethod, stateClass: String): Boolean {
            val impl = method.implementation ?: return false
            val hasGetString = impl.instructions.any { insn ->
                insn.opcode == Opcode.INVOKE_VIRTUAL &&
                    ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == ANDROID_CONTEXT_GET_STRING
            }
            val readsState = impl.instructions.any { insn ->
                if (insn.opcode != Opcode.IGET_OBJECT) return@any false
                val ref = (insn as? ReferenceInstruction)?.reference as? FieldReference ?: return@any false
                ref.definingClass == stateClass
            }
            return hasGetString && readsState
        }

        fun resolveOpenDebugTargets(): OpenDebugTargets {
            val defaultState = OpenDebugCellVmDefaultStateFingerprint.method
            val stateClass = defaultState.implementation?.instructions?.firstNotNullOfOrNull { insn ->
                if (insn.opcode != Opcode.NEW_INSTANCE) return@firstNotNullOfOrNull null
                ((insn as? ReferenceInstruction)?.reference as? TypeReference)?.type
            } ?: throw PatchException("Enable Open Debug: could not resolve OpenDebug state class from defaultState().")

            val composeMethods = mutableListOf<Pair<ClassDef, SmaliMethod>>()
            classDefForEach { classDef ->
                for (method in classDef.methods) {
                    if (method.name != "LIZ" || method.returnType != "V") continue
                    val p = method.parameterTypes
                    if (p.size != 5) continue
                    if (p[0] != stateClass) continue
                    if (p[1] != "Z" || p[2] != "Z" || p[4] != "I") continue
                    if (!p[3].startsWith("LX/")) continue
                    if (!isOpenDebugRowCompose(method, stateClass)) continue
                    composeMethods += classDef to method
                }
            }

            if (composeMethods.isEmpty()) {
                throw PatchException(
                    "Enable Open Debug: no OpenDebug row compose found for state $stateClass.",
                )
            }
            if (composeMethods.size > 1) {
                throw PatchException(
                    "Enable Open Debug: multiple OpenDebug row compose methods found for state $stateClass.",
                )
            }

            val (composeClassDef, composeMethod) = composeMethods.single()
            return OpenDebugTargets(
                stateClass = stateClass,
                composeMutable = mutableClassDefBy(composeClassDef).findMutableMethodOf(composeMethod),
            )
        }

        val targets = resolveOpenDebugTargets()
        val openDebugStateClass = targets.stateClass
        val composeMutable = targets.composeMutable

        fun clickLambdaScore(method: SmaliMethod, classType: String): Int {
            val impl = method.implementation ?: return 0
            var score = 0
            for (insn in impl.instructions) {
                val ref = (insn as? ReferenceInstruction)?.reference
                if (insn.opcode == Opcode.IGET_OBJECT) {
                    val field = ref as? FieldReference ?: continue
                    if (field.definingClass == classType && field.name == "l1") score += 25
                    if (field.definingClass == classType && field.name == "l0") score += 15
                }
                if (insn.opcode == Opcode.CHECK_CAST) {
                    val type = ref as? TypeReference ?: continue
                    if (type.type == openDebugStateClass) score += 40
                    if (type.type == "Landroid/content/Context;") score += 10
                }
            }
            return score
        }

        fun resolveClickWrapperMethod(): MutableMethod {
            var wrapperInvokeName: String? = null
            val composeInstructions = composeMutable.implementation!!.instructions.toList()
            val wrapperClass = composeInstructions.withIndex().firstNotNullOfOrNull { (index, insn) ->
                if (insn.opcode != Opcode.INVOKE_DIRECT) return@firstNotNullOfOrNull null
                val instruction = insn as? Instruction35c ?: return@firstNotNullOfOrNull null
                val ref = instruction.reference as? MethodReference
                    ?: return@firstNotNullOfOrNull null
                if (!ref.definingClass.startsWith("Lkotlin/jvm/internal/AwS")) return@firstNotNullOfOrNull null
                if (ref.parameterTypes != listOf(openDebugStateClass, "Landroid/content/Context;", "I")) {
                    return@firstNotNullOfOrNull null
                }

                val discriminatorRegister = when (instruction.registerCount) {
                    4 -> instruction.registerF
                    5 -> instruction.registerG
                    else -> throw PatchException(
                        "Enable Open Debug: unexpected click wrapper constructor register count ${instruction.registerCount}.",
                    )
                }
                val discriminator = composeInstructions
                    .take(index)
                    .asReversed()
                    .firstNotNullOfOrNull { previous ->
                        val register = (previous as? OneRegisterInstruction)?.registerA
                            ?: return@firstNotNullOfOrNull null
                        if (register != discriminatorRegister) return@firstNotNullOfOrNull null
                        (previous as? NarrowLiteralInstruction)?.narrowLiteral
                    } ?: throw PatchException(
                    "Enable Open Debug: could not resolve click wrapper discriminator.",
                )
                wrapperInvokeName = "invoke\$$discriminator"
                ref.definingClass
            } ?: throw PatchException(
                "Enable Open Debug: could not resolve OpenDebug click wrapper class from compose method.",
            )

            val matches = mutableListOf<MutableMethod>()
            classDefForEach { classDef ->
                if (classDef.type != wrapperClass) return@classDefForEach
                for (method in classDef.methods) {
                    if (method.parameterTypes.size != 1 || method.parameterTypes[0] != classDef.type) continue
                    if (method.name != wrapperInvokeName) continue
                    val score = clickLambdaScore(method, classDef.type)
                    if (score < 70) continue
                    matches += mutableClassDefBy(classDef).findMutableMethodOf(method)
                }
            }

            if (matches.size != 1) {
                throw PatchException(
                    "Enable Open Debug: expected one OpenDebug click handler in $wrapperClass, found ${matches.size}.",
                )
            }
            return matches.single()
        }

        fun resolveOpenDebugFunction2Method(): MutableMethod {
            val defaultState = OpenDebugCellVmDefaultStateFingerprint.method
            val openDebugVmClass = defaultState.definingClass
            val lambdaClass = defaultState.implementation?.instructions?.firstNotNullOfOrNull { insn ->
                if (insn.opcode != Opcode.INVOKE_DIRECT) return@firstNotNullOfOrNull null
                val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
                    ?: return@firstNotNullOfOrNull null
                if (!ref.definingClass.startsWith("Lkotlin/jvm/internal/AwS")) return@firstNotNullOfOrNull null
                if (ref.parameterTypes.firstOrNull() != openDebugVmClass) return@firstNotNullOfOrNull null
                ref.definingClass
            } ?: throw PatchException(
                "Enable Open Debug: could not resolve OpenDebug Function2 lambda class.",
            )

            val matches = mutableListOf<MutableMethod>()
            classDefForEach { classDef ->
                if (classDef.type != lambdaClass) return@classDefForEach
                for (method in classDef.methods) {
                    if (!method.name.matches(Regex("invoke\\\$\\d+"))) continue
                    if (method.returnType != "Ljava/lang/Object;") continue
                    val parameters = method.parameterTypes
                    if (parameters.size != 3 || parameters[0] != lambdaClass) continue
                    val hasOpenDebugCast = method.implementation?.instructions?.any { insn ->
                        insn.opcode == Opcode.CHECK_CAST &&
                            ((insn as? ReferenceInstruction)?.reference as? TypeReference)?.type == openDebugVmClass
                    } == true
                    if (!hasOpenDebugCast) continue
                    matches += mutableClassDefBy(classDef).findMutableMethodOf(method)
                }
            }

            if (matches.size != 1) {
                throw PatchException(
                    "Enable Open Debug: expected one OpenDebug Function2 lambda, found ${matches.size}.",
                )
            }
            return matches.single()
        }

        fun MutableMethod.openMorpheSettingsAtStart(contextRegister: String) {
            addInstructions(
                0,
                """
                    invoke-static {}, Lapp/morphe/extension/shared/Utils;->getContext()Landroid/content/Context;
                    move-result-object v$contextRegister
                    if-eqz v$contextRegister, :return_unit
                    new-instance v1, Landroid/content/Intent;
                    const-class v2, Lcom/bytedance/ies/ugc/aweme/commercialize/compliance/personalization/AdPersonalizationActivity;
                    invoke-direct {v1, v$contextRegister, v2}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
                    const/high16 v2, 0x10000000
                    invoke-virtual { v1, v2 }, Landroid/content/Intent;->setFlags(I)Landroid/content/Intent;
                    const-string v2, "morphe_settings"
                    invoke-virtual { v1, v2 }, Landroid/content/Intent;->setAction(Ljava/lang/String;)Landroid/content/Intent;
                    invoke-virtual { v$contextRegister, v1 }, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V
                    :return_unit
                    sget-object v$contextRegister, Lkotlin/Unit;->LIZ:Lkotlin/Unit;
                    return-object v$contextRegister
                """,
            )
        }

        fun addOpenDebugToVisibleSettingsList(): Boolean {
            val composeRowsMethod = SettingsComposeRowsFingerprint.methodOrNull ?: return false
            val openDebugField = SupportGroupDefaultStateFingerprint.method.implementation?.instructions
                ?.firstNotNullOfOrNull { instruction ->
                    if (instruction.opcode != Opcode.SGET_OBJECT) return@firstNotNullOfOrNull null
                    val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                        ?: return@firstNotNullOfOrNull null
                    field.takeIf { it.name == "SECTION_HEADER" }
                } ?: return false

            val sortedListIndex = composeRowsMethod.implementation?.instructions?.indexOfLast {
                if (it.opcode != Opcode.INVOKE_STATIC) return@indexOfLast false
                val reference = (it as? ReferenceInstruction)?.reference as? MethodReference
                    ?: return@indexOfLast false
                reference.name in setOf("LJLJLLL", "LJLLLL") &&
                    reference.parameterTypes == listOf("Ljava/util/Comparator;", "Ljava/lang/Iterable;") &&
                    reference.returnType == "Ljava/util/List;"
            } ?: -1
            if (sortedListIndex < 0) return false

            val listRegister = (composeRowsMethod.getInstruction(sortedListIndex + 1) as? OneRegisterInstruction)
                ?.registerA ?: return false

            composeRowsMethod.addInstructions(
                sortedListIndex + 2,
                """
                    new-instance v0, Ljava/util/ArrayList;
                    move-object v1, v$listRegister
                    invoke-direct {v0, v1}, Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V
                    sget-object v1, ${openDebugField.definingClass}->OPEN_DEBUG:${openDebugField.type}
                    const/4 v2, 0x0
                    invoke-virtual {v0, v2, v1}, Ljava/util/ArrayList;->add(ILjava/lang/Object;)V
                    move-object v$listRegister, v0
                """,
            )

            return true
        }

        if (!addOpenDebugToVisibleSettingsList()) {
            SupportGroupDefaultStateFingerprint.method.apply {
                val sectionHeaderSgetIndex = indexOfFirstInstructionOrThrow {
                    opcode == Opcode.SGET_OBJECT && getReference<FieldReference>()?.name == "SECTION_HEADER"
                }

                val sectionHeaderField = getInstruction<ReferenceInstruction>(sectionHeaderSgetIndex).reference as FieldReference
                val addInstruction = getInstruction<Instruction35c>(sectionHeaderSgetIndex + 1)
                val addReference = addInstruction.reference as MethodReference
                val listRegister = addInstruction.registerC
                val itemRegister = addInstruction.registerD

                addInstructions(
                    sectionHeaderSgetIndex + 2,
                    """
                        sget-object v$itemRegister, ${sectionHeaderField.definingClass}->OPEN_DEBUG:${sectionHeaderField.type}
                        invoke-virtual { v$listRegister, v$itemRegister }, $addReference
                    """,
                )
            }
        }

        AdPersonalizationActivityOnCreateFingerprint.method.apply {
            val initializeSettingsIndex = implementation!!.instructions.indexOfFirst { it.opcode == Opcode.INVOKE_SUPER } + 1
            val thisRegister = getInstruction<Instruction35c>(initializeSettingsIndex - 1).registerC
            val usableRegister = implementation!!.registerCount - parameters.size - 2

            addInstructionsWithLabels(
                initializeSettingsIndex,
                """
                    invoke-static {v$thisRegister}, $initializeSettingsMethodDescriptor
                    move-result v$usableRegister
                    if-eqz v$usableRegister, :do_not_open
                    return-void
                """,
                ExternalLabel("do_not_open", getInstruction(initializeSettingsIndex)),
            )
        }

        AdPersonalizationActivityOnBackPressedFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range {p0 .. p0}, $SETTINGS_EXTENSION_CLASS_DESCRIPTOR->handleBackPressed(Lcom/bytedance/ies/ugc/aweme/commercialize/compliance/personalization/AdPersonalizationActivity;)Z
                    move-result v0
                    if-eqz v0, :morphe_settings_not_handled
                    return-void
                """,
                ExternalLabel("morphe_settings_not_handled", getInstruction(0)),
            )
        }

        val compose: SmaliMethod = composeMutable
        val getStringInvokeIndex = compose.indexOfFirstInstructionOrThrow {
            opcode == Opcode.INVOKE_VIRTUAL &&
                getReference<MethodReference>()?.toString() == ANDROID_CONTEXT_GET_STRING
        }
        val moveResultIndex = getStringInvokeIndex + 1
        val titleStringRegister = compose.getInstruction<OneRegisterInstruction>(moveResultIndex).registerA

        composeMutable.addInstruction(moveResultIndex + 1, "const-string v$titleStringRegister, \"Metra patches\"")

        OpenDebugCellVmDefaultStateFingerprint.methodOrNull?.let { defaultState ->
            val constructorReference = defaultState.implementation!!.instructions.firstNotNullOfOrNull { instruction ->
                if (instruction.opcode != Opcode.INVOKE_DIRECT) return@firstNotNullOfOrNull null
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    ?: return@firstNotNullOfOrNull null
                reference.takeIf { it.name == "<init>" && it.definingClass == openDebugStateClass }
            } ?: throw PatchException("Settings: could not resolve the OpenDebug state constructor.")

            var stateConstructor: MutableMethod? = null
            classDefForEach { classDef ->
                if (classDef.type != constructorReference.definingClass) return@classDefForEach
                val constructor = classDef.methods.singleOrNull { method ->
                    method.name == constructorReference.name &&
                        method.parameterTypes == constructorReference.parameterTypes
                } ?: return@classDefForEach
                stateConstructor = mutableClassDefBy(classDef).findMutableMethodOf(constructor)
            }
            val constructor = stateConstructor
                ?: throw PatchException("Settings: OpenDebug state constructor was not found.")
            val iconType = VectorResourceToStringFingerprint.originalClassDef.type
            val iconLoadIndex = constructor.indexOfFirstInstructionOrThrow {
                opcode == Opcode.SGET_OBJECT && getReference<FieldReference>()?.type == iconType
            }
            val iconRegister = constructor.getInstruction<OneRegisterInstruction>(iconLoadIndex).registerA
            val tempRegister = constructor.findFreeRegister(iconLoadIndex + 1, iconRegister)

            constructor.addInstructions(
                iconLoadIndex + 1,
                """
                    new-instance v$iconRegister, $iconType
                    const v$tempRegister, 0x7f010088
                    invoke-direct {v$iconRegister, v$tempRegister}, $iconType-><init>(I)V
                """,
            )
        }

        val clickWrapperMethod = resolveClickWrapperMethod()
        val openDebugClickWrapperClass = clickWrapperMethod.definingClass
        clickWrapperMethod.apply {
            addInstructions(
                0,
                """
                    iget-object v0, p0, $openDebugClickWrapperClass->l1:Ljava/lang/Object;
                    check-cast v0, Landroid/content/Context;
                    new-instance v1, Landroid/content/Intent;
                    const-class v2, Lcom/bytedance/ies/ugc/aweme/commercialize/compliance/personalization/AdPersonalizationActivity;
                    invoke-direct {v1, v0, v2}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
                    const-string v2, "morphe_settings"
                    invoke-virtual { v1, v2 }, Landroid/content/Intent;->setAction(Ljava/lang/String;)Landroid/content/Intent;
                    const/high16 v2, 0x10000000
                    invoke-virtual { v1, v2 }, Landroid/content/Intent;->addFlags(I)Landroid/content/Intent;
                    invoke-virtual { v0, v1 }, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V
                    sget-object v0, Lkotlin/Unit;->LIZ:Lkotlin/Unit;
                    return-object v0
                """,
            )
        }

        resolveOpenDebugFunction2Method().openMorpheSettingsAtStart("0")
    }
}
