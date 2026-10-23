/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/misc/settings/EnableOpenDebugPatch.kt
 */
package app.morphe.patches.tiktok.misc.settings

import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.patches.tiktok.shared.requireRegisters
import app.morphe.util.findFreeRegister
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.util.getFreeRegisterProvider
import app.morphe.patcher.util.getReference
import app.morphe.util.implementationOrPatchException
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.patcher.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method as SmaliMethod
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val SETTINGS_EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/settings/TikTokActivityHook;"
private const val OPEN_DEBUG_CELL_VM_DESCRIPTOR =
    "Lcom/ss/android/ugc/aweme/setting/ui/rvmpcompose/group/support/cells/OpenDebugCellVM;"
private const val AD_WEB_MORE_ACTION_SERVICE_DESCRIPTOR =
    "Lcom/ss/android/ugc/aweme/commercialize/hybrid/impl/web/AdWebMoreActionService;"
private const val SPARK_CONTEXT_DESCRIPTOR = "Lcom/bytedance/hybrid/spark/SparkContext;"
private const val TUX_ICON_VIEW_DESCRIPTOR = "Lcom/bytedance/tux/icon/TuxIconView;"
private const val AD_BROWSER_SETTINGS_KEY = "ad_browser_settings"

private const val ANDROID_CONTEXT_GET_STRING = "Landroid/content/Context;->getString(I)Ljava/lang/String;"

private data class OpenDebugTargets(
    val stateClass: String,
    val composeMutable: MutableMethod,
)

/**
 * Copies TikTok's sorted settings rows and moves its Open Debug singleton to index zero.
 *
 * TikTok 47.0.3 can already include that singleton in the support group. Removing it first avoids
 * duplicate Compose keys, which otherwise leave the later, natively sorted row on screen.
 */
internal fun settingsRowMoveToFrontInstructions(
    copyRegister: Int,
    valueRegister: Int,
    indexRegister: Int,
    listRegister: Int,
    openDebugField: FieldReference,
): String =
    """
        new-instance v$copyRegister, Ljava/util/ArrayList;
        move-object v$valueRegister, v$listRegister
        invoke-direct {v$copyRegister, v$valueRegister}, Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V
        sget-object v$valueRegister, ${openDebugField.definingClass}->OPEN_DEBUG:${openDebugField.type}
        invoke-virtual {v$copyRegister, v$valueRegister}, Ljava/util/ArrayList;->remove(Ljava/lang/Object;)Z
        const/4 v$indexRegister, 0x0
        invoke-virtual {v$copyRegister, v$indexRegister, v$valueRegister}, Ljava/util/ArrayList;->add(ILjava/lang/Object;)V
        move-object v$listRegister, v$copyRegister
    """

/**
 * The state constructor OpenDebugCellVM.defaultState() builds its row with. 47.1.16 passes it six
 * registers, so it's called with invoke-direct/range there and plain invoke-direct before.
 */
internal fun openDebugStateConstructor(defaultState: SmaliMethod, stateClass: String): MethodReference =
    defaultState.implementation?.instructions?.firstNotNullOfOrNull { instruction ->
        if (instruction.opcode != Opcode.INVOKE_DIRECT && instruction.opcode != Opcode.INVOKE_DIRECT_RANGE) {
            return@firstNotNullOfOrNull null
        }
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            ?: return@firstNotNullOfOrNull null
        reference.takeIf { it.name == "<init>" && it.definingClass == stateClass }
    } ?: throw PatchException("Settings: could not resolve the OpenDebug state constructor.")

/** The state class OpenDebugCellVM.defaultState() builds the row from: the first type it instantiates. */
internal fun openDebugStateClass(defaultState: SmaliMethod): String =
    defaultState.implementation?.instructions?.firstNotNullOfOrNull { insn ->
        if (insn.opcode != Opcode.NEW_INSTANCE) return@firstNotNullOfOrNull null
        ((insn as? ReferenceInstruction)?.reference as? TypeReference)?.type
    } ?: throw PatchException("Enable Open Debug: could not resolve OpenDebug state class from defaultState().")

/**
 * The OpenDebug row's compose method: `LIZ(state, Z, Z, LX/..., I)V`, reading the state's fields and
 * its title through Context.getString.
 */
internal fun isOpenDebugRowComposeMethod(method: SmaliMethod, stateClass: String): Boolean {
    if (method.name != "LIZ" || method.returnType != "V") return false
    val p = method.parameterTypes
    if (p.size != 5 || p[0] != stateClass) return false
    if (p[1] != "Z" || p[2] != "Z" || p[4] != "I") return false
    if (!p[3].startsWith("LX/")) return false
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

/** The lambda class the OpenDebug row's click goes through, and the `invoke$N` its discriminator picks. */
internal data class OpenDebugClickWrapper(val className: String, val invokeName: String)

/**
 * The click wrapper the OpenDebug row's compose method builds: the constructor taking the row's
 * state, a Context and the int that picks which `invoke$N` runs. It's found by those parameters
 * alone. The wrapper's obfuscated class name is TikTok's own (`Lkotlin/jvm/internal/AwS...` in
 * 47.1.4) and another app built from the same code, PineDrama, names it differently.
 */
internal fun openDebugClickWrapper(compose: SmaliMethod, stateClass: String): OpenDebugClickWrapper {
    val instructions = compose.implementationOrPatchException("Enable Open Debug").instructions.toList()
    val wanted = listOf(stateClass, "Landroid/content/Context;", "I")
    return instructions.withIndex().firstNotNullOfOrNull { (index, insn) ->
        if (insn.opcode != Opcode.INVOKE_DIRECT) return@firstNotNullOfOrNull null
        val instruction = insn as? Instruction35c ?: return@firstNotNullOfOrNull null
        val ref = instruction.reference as? MethodReference ?: return@firstNotNullOfOrNull null
        if (ref.name != "<init>" || ref.parameterTypes.map(CharSequence::toString) != wanted) {
            return@firstNotNullOfOrNull null
        }

        val discriminatorRegister = when (instruction.registerCount) {
            4 -> instruction.registerF
            5 -> instruction.registerG
            else -> throw PatchException(
                "Enable Open Debug: unexpected click wrapper constructor register count ${instruction.registerCount}.",
            )
        }
        val discriminator = instructions
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
        OpenDebugClickWrapper(ref.definingClass, "invoke\$$discriminator")
    } ?: throw PatchException(
        "Enable Open Debug: could not resolve OpenDebug click wrapper class from compose method.",
    )
}

/**
 * The Function2 lambda defaultState() hands the OpenDebug view model to: the first constructor it
 * calls whose first parameter is that view model. Found by that parameter alone, for the same
 * reason as [openDebugClickWrapper].
 */
internal fun openDebugFunction2LambdaClass(defaultState: SmaliMethod): String {
    val openDebugVmClass = defaultState.definingClass
    return defaultState.implementation?.instructions?.firstNotNullOfOrNull { insn ->
        if (insn.opcode != Opcode.INVOKE_DIRECT) return@firstNotNullOfOrNull null
        val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
            ?: return@firstNotNullOfOrNull null
        if (ref.name != "<init>") return@firstNotNullOfOrNull null
        if (ref.parameterTypes.firstOrNull()?.toString() != openDebugVmClass) return@firstNotNullOfOrNull null
        ref.definingClass
    } ?: throw PatchException(
        "Enable Open Debug: could not resolve OpenDebug Function2 lambda class.",
    )
}

/**
 * Swaps the OpenDebug row's icon for the settings gear where TikTok loads it. Through 47.1.4 the
 * state's constructor reads it from a static. On 47.1.16 defaultState() reads it and hands the one
 * value to both icon parameters, so swapping it there covers both.
 */
internal fun swapOpenDebugIcon(
    constructor: MutableMethod,
    defaultState: MutableMethod,
    vectorResource: String,
    iconResourceId: Int,
) {
    fun MutableMethod.iconLoadIndex() = indexOfFirstInstruction {
        opcode == Opcode.SGET_OBJECT && getReference<FieldReference>()?.type == vectorResource
    }
    val method = listOf(constructor, defaultState).firstOrNull { it.iconLoadIndex() >= 0 }
        ?: throw PatchException("Settings: neither the OpenDebug state nor defaultState() loads a $vectorResource icon.")
    val iconLoadIndex = method.iconLoadIndex()
    val iconRegister = method.getInstruction<OneRegisterInstruction>(iconLoadIndex).registerA
    val tempRegister = method.findFreeRegister(iconLoadIndex + 1, iconRegister)

    method.addInstructions(
        iconLoadIndex + 1,
        """
            new-instance v$iconRegister, $vectorResource
            const v$tempRegister, $iconResourceId
            invoke-direct {v$iconRegister, v$tempRegister}, $vectorResource-><init>(I)V
        """,
    )
}

/**
 * The type of TikTok's `VectorResource(resId: Int)` data class, found by the string constant its
 * generated toString() appends. Exactly one class carries it, and it has to have the one-int
 * constructor the patch calls, or the patch says so rather than assembling a call to nothing.
 */
private fun BytecodePatchContext.vectorResourceClass(): String {
    val carriers = classDefByStrings(VECTOR_RESOURCE_TO_STRING)
    val carrier = carriers.singleOrNull() ?: throw PatchException(
        "Settings: expected one class carrying \"$VECTOR_RESOURCE_TO_STRING\", found ${carriers.size}.",
    )
    val type = carrier.type
    val hasIntConstructor = carrier.methods.any {
        it.name == "<init>" && it.parameterTypes.toList() == listOf("I")
    }
    if (!hasIntConstructor) {
        throw PatchException("Settings: $type has no <init>(I)V to build the icon with.")
    }
    return type
}

private const val VECTOR_RESOURCE_TO_STRING = "VectorResource(resId="

/**
 * Reads TikTok's settings gear id from the bytecode that renders the Ad Browser settings action.
 *
 * The action is found by its stable key. The stable AdWebMoreActionService then identifies which
 * zero-argument interface method supplies the icon it passes to TuxIconView. The Ad Browser
 * implementations share one returned resource id in that method, even when one offers a second
 * conditional icon. This copies TikTok's own answer without opening resources.arsc or assuming
 * the id assigned by a particular build.
 */
internal fun resolveSettingsIconResourceId(
    iconRenderer: SmaliMethod,
    keyCarriers: Iterable<ClassDef>,
): Int {
    val rendererInstructions = iconRenderer.implementation?.instructions?.toList().orEmpty()
    val iconCalls = rendererInstructions.mapNotNull { instruction ->
        if (instruction.opcode != Opcode.INVOKE_INTERFACE) return@mapNotNull null
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            ?: return@mapNotNull null
        reference.takeIf { it.parameterTypes.isEmpty() && it.returnType == "I" }
    }
    val iconMethod = iconCalls.singleOrNull() ?: throw PatchException(
        "Settings: expected AdWebMoreActionService to make one interface icon call, " +
            "found ${iconCalls.size}.",
    )

    val implementations = keyCarriers.filter { classDef ->
        iconMethod.definingClass in classDef.interfaces && classDef.methods.any { method ->
            val instructions = method.implementation?.instructions?.toList() ?: return@any false
            method.parameterTypes.isEmpty() &&
                method.returnType == "Ljava/lang/String;" &&
                instructions.size == 2 &&
                instructions[0].opcode == Opcode.CONST_STRING &&
                ((instructions[0] as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                AD_BROWSER_SETTINGS_KEY &&
                instructions[1].opcode == Opcode.RETURN_OBJECT &&
                (instructions[0] as? OneRegisterInstruction)?.registerA ==
                (instructions[1] as? OneRegisterInstruction)?.registerA
        }
    }.toList()
    if (implementations.isEmpty()) {
        throw PatchException(
            "Settings: no $AD_BROWSER_SETTINGS_KEY action implements ${iconMethod.definingClass}.",
        )
    }

    val returnedIds = implementations.associate { classDef ->
        val method = classDef.methods.singleOrNull { candidate ->
            candidate.name == iconMethod.name &&
                candidate.parameterTypes == iconMethod.parameterTypes &&
                candidate.returnType == iconMethod.returnType
        } ?: throw PatchException(
            "Settings: ${classDef.type} does not implement the selected icon method $iconMethod once.",
        )
        val instructions = method.implementation?.instructions?.toList()
            ?: throw PatchException("Settings: ${classDef.type}'s icon method has no implementation.")
        val ids = instructions.zipWithNext().mapNotNull { (value, exit) ->
            if (exit.opcode != Opcode.RETURN) return@mapNotNull null
            val valueRegister = (value as? OneRegisterInstruction)?.registerA
                ?: return@mapNotNull null
            val returnRegister = (exit as? OneRegisterInstruction)?.registerA
                ?: return@mapNotNull null
            if (valueRegister != returnRegister) return@mapNotNull null
            (value as? NarrowLiteralInstruction)?.narrowLiteral
        }.toSet()
        if (ids.isEmpty()) {
            throw PatchException(
                "Settings: ${classDef.type}'s icon method has no direct integer return.",
            )
        }
        classDef.type to ids
    }
    val sharedIds = returnedIds.values.reduce(Set<Int>::intersect)
    val id = sharedIds.singleOrNull() ?: throw PatchException(
        "Settings: $AD_BROWSER_SETTINGS_KEY implementations do not identify one shared icon: " +
            returnedIds.entries.joinToString { (type, ids) ->
                "$type=[${ids.joinToString { "0x${it.toUInt().toString(16)}" }}]"
            } + ".",
    )
    if (id ushr 24 != 0x7f) {
        throw PatchException(
            "Settings: $AD_BROWSER_SETTINGS_KEY returned non-app resource 0x${id.toUInt().toString(16)}.",
        )
    }
    return id
}

private fun BytecodePatchContext.settingsIconResourceId(): Int {
    val renderer = mutableClassDefBy(AD_WEB_MORE_ACTION_SERVICE_DESCRIPTOR).methods.singleOrNull { method ->
        method.returnType == "V" && method.parameterTypes == listOf(
            SPARK_CONTEXT_DESCRIPTOR,
            "Landroid/content/Context;",
            TUX_ICON_VIEW_DESCRIPTOR,
        )
    } ?: throw PatchException(
        "Settings: AdWebMoreActionService icon renderer was not found once.",
    )
    return resolveSettingsIconResourceId(
        renderer,
        classDefByStrings(AD_BROWSER_SETTINGS_KEY),
    )
}

@Suppress("unused")
val settingsPatch = bytecodePatch(
    name = "Settings",
    description = "Adds the Metra settings page to TikTok, at the top of Settings and " +
        "privacy. Pressing and holding For You at the top of the feed opens it too. That's where " +
        "you turn Metra's features on and off. Works as soon as you patch it in, with no switch.",
    default = true,
) {
    category("Settings")
    dependsOn(sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        // First, before any other write: TikTok's own crash recovery would delete what this
        // screen saves.
        keepHushfeedPreferenceFiles(
            LaunchCrashKeepListFingerprint.method,
            LaunchCrashKeepListFingerprint.classDef.methods,
        )

        val initializeSettingsMethodDescriptor =
            "$SETTINGS_EXTENSION_CLASS_DESCRIPTOR->initialize(" +
                "Lcom/bytedance/ies/ugc/aweme/commercialize/compliance/personalization/AdPersonalizationActivity;" +
                ")Z"

        fun resolveOpenDebugTargets(): OpenDebugTargets {
            val stateClass = openDebugStateClass(OpenDebugCellVmDefaultStateFingerprint.method)

            val composeMethods = mutableListOf<Pair<ClassDef, SmaliMethod>>()
            classDefForEach { classDef ->
                for (method in classDef.methods) {
                    if (isOpenDebugRowComposeMethod(method, stateClass)) composeMethods += classDef to method
                }
            }

            val (composeClassDef, composeMethod) = composeMethods.singleOrNull()
                ?: throw PatchException(
                    "Enable Open Debug: expected one OpenDebug row compose for $stateClass, " +
                        "found ${composeMethods.size}.",
                )
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
            val wrapper = openDebugClickWrapper(composeMutable, openDebugStateClass)
            val wrapperClass = wrapper.className

            val matches = mutableListOf<MutableMethod>()
            classDefForEach { classDef ->
                if (classDef.type != wrapperClass) return@classDefForEach
                for (method in classDef.methods) {
                    if (method.parameterTypes.size != 1 || method.parameterTypes[0] != classDef.type) continue
                    if (method.name != wrapper.invokeName) continue
                    val score = clickLambdaScore(method, classDef.type)
                    if (score < 70) continue
                    matches += mutableClassDefBy(classDef).findMutableMethodOf(method)
                }
            }

            return matches.singleOrNull() ?: throw PatchException(
                "Enable Open Debug: expected one OpenDebug click handler in $wrapperClass, " +
                    "found ${matches.size}.",
            )
        }

        fun resolveOpenDebugFunction2Method(): MutableMethod {
            val defaultState = OpenDebugCellVmDefaultStateFingerprint.method
            val openDebugVmClass = defaultState.definingClass
            val lambdaClass = openDebugFunction2LambdaClass(defaultState)

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

            return matches.singleOrNull() ?: throw PatchException(
                "Enable Open Debug: expected one OpenDebug Function2 lambda, found ${matches.size}.",
            )
        }

        fun MutableMethod.openMorpheSettingsAtStart(contextRegister: String) {
            // The body replaces the lambda outright and returns, so the parameters are fair
            // to write over; the frame still has to hold the three registers it names. The
            // OpenDebug lambda has no locals at all on 46.2.3, only its parameters.
            requireRegisters("Settings", 3)
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
                reference.isSettingsRowsSort()
            } ?: -1
            if (sortedListIndex < 0) return false

            val listRegister = (composeRowsMethod.getInstruction(sortedListIndex + 1) as? OneRegisterInstruction)
                ?.registerA ?: return false

            // v0, v1 and v2 were free here on 46.2.3 and nothing said so. The list this
            // rebuilds is 41 registers deep, and all three are read by the injected code, so
            // they have to be found rather than assumed. All three are named by plain invokes,
            // which reach v15 and no further.
            val registers = composeRowsMethod.getFreeRegisterProvider(
                sortedListIndex + 2,
                3,
                listOf(listRegister),
            )
            val copyRegister = registers.getFreeRegister4Bit()
            val valueRegister = registers.getFreeRegister4Bit()
            val indexRegister = registers.getFreeRegister4Bit()

            composeRowsMethod.addInstructions(
                sortedListIndex + 2,
                settingsRowMoveToFrontInstructions(
                    copyRegister,
                    valueRegister,
                    indexRegister,
                    listRegister,
                    openDebugField,
                ),
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
            val activityImplementation = implementationOrPatchException("Settings")
            val invokeSuperIndex = indexOfFirstInstructionOrThrow { opcode == Opcode.INVOKE_SUPER }
            val invokeSuper = getInstruction(invokeSuperIndex) as? Instruction35c
                ?: throw PatchException(
                    "Settings: ${definingClass}->${name} uses an unsupported invoke-super form.",
                )
            val initializeSettingsIndex = invokeSuperIndex + 1
            val thisRegister = invokeSuper.registerC
            // The highest local. Counting parameters rather than the registers they occupy
            // put this on top of a wide parameter's upper half on any method holding one.
            val locals = activityImplementation.registerCount - numberOfParameterRegisters
            check(locals >= 1) {
                "Settings: the ad personalisation activity has no free local register."
            }
            val usableRegister = locals - 1

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
            requireLocals("Settings", 1)
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

        composeMutable.addInstruction(moveResultIndex + 1, "const-string v$titleStringRegister, \"Metra\"")

        OpenDebugCellVmDefaultStateFingerprint.methodOrNull?.let { defaultState ->
            defaultState.implementationOrPatchException("Settings")
            val constructorReference = openDebugStateConstructor(defaultState, openDebugStateClass)

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

            // The row's icon is a Kotlin data class wrapping a resource id. Its obfuscated name
            // changes with every build, so its generated toString label is the type anchor. The
            // value comes from TikTok's own Ad Browser settings action, whose stable renderer says
            // which interface method supplies the gear it puts in a TuxIconView.
            swapOpenDebugIcon(constructor, defaultState, vectorResourceClass(), settingsIconResourceId())
        }

        val clickWrapperMethod = resolveClickWrapperMethod()
        val openDebugClickWrapperClass = clickWrapperMethod.definingClass
        clickWrapperMethod.apply {
            // v0, v1 and v2 all written at index 0, and the body returns without reaching the
            // wrapper's own code, so the parameters may be written over. The frame has to hold
            // three registers all the same.
            requireRegisters("Settings", 3)
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
        // A long press on For You, TikTok's nearest thing to a logo on the feed, opens the page
        // too. A build where it can't be found keeps everything above and leaves For You alone.
        hookForYouLongPress()
        registerForBuildDetails("enableSettings")
    }
}
