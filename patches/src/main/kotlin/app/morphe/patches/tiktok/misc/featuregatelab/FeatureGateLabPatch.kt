/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.misc.featuregatelab

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.absettings.APP_AB_INT_KEY_REGISTER
import app.morphe.patches.tiktok.misc.absettings.APP_AB_INT_PARAMETERS
import app.morphe.patches.tiktok.misc.absettings.AppAbIntBoundaryFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.cloneMutableAndPreserveParameters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/**
 * Placeholder for the obfuscated App AB manager class (X.0BYX in TikTok 46.2.3, X.02yF in 47.1.4). It is
 * resolved at patch time from [AppAbIntBoundaryFingerprint]; its typed getters are matched by signature only
 * because their names are obfuscated too.
 */
private const val APP_AB_PLACEHOLDER = "<app-ab>"
private const val ABMOCK_SETTINGS_MANAGER_DESCRIPTOR = "Lcom/bytedance/ies/abmock/SettingsManager;"
private const val LIVE_SETTINGS_DESCRIPTOR = "Lcom/bytedance/android/live_settings/SettingsManager;"
private const val ACTIVITY_CENTER_DESCRIPTOR = "Lcom/ss/android/ugc/tiktok/pns/activitycenter/EnterActivityCenterAction;"
private const val VE_CONFIG_DESCRIPTOR = "Lcom/ss/android/vesdk/VEConfigCenter;"
private const val PLAYER_SETTING_SERVICE_DESCRIPTOR =
    "Lcom/ss/android/ugc/aweme/video/simplayer/PlayerSettingServiceImpl;"
private const val RUNTIME_DESCRIPTOR = "Lapp/morphe/extension/tiktok/featuregatelab/FeatureGateLabRuntime;"

private data class TypedBoundary(
    val targetDescriptor: String,
    /** Method name, or null to match by signature only (obfuscated App AB getters). */
    val methodName: String?,
    val returnType: String,
    val parameters: List<String>,
    val keyParameter: String,
    val returnOpcode: Opcode,
    val runtimeMethod: String,
    val runtimeDescriptor: String,
    val wide: Boolean = false,
    val keyScratchRegister: String? = null,
)

private val boundaries = listOf(
    TypedBoundary(APP_AB_PLACEHOLDER, null, "Z", listOf("I", "Ljava/lang/String;", "Z", "Z"), "p2", Opcode.RETURN, "overrideBoolean", "(Ljava/lang/String;Z)Z"),
    TypedBoundary(APP_AB_PLACEHOLDER, null, "D", listOf("D", "I", "Ljava/lang/String;", "Z"), "p4", Opcode.RETURN_WIDE, "overrideDouble", "(Ljava/lang/String;D)D", true),
    TypedBoundary(APP_AB_PLACEHOLDER, null, "F", listOf("I", "Ljava/lang/String;", "Z", "F"), "p2", Opcode.RETURN, "overrideFloat", "(Ljava/lang/String;F)F"),
    TypedBoundary(APP_AB_PLACEHOLDER, null, "I", APP_AB_INT_PARAMETERS, APP_AB_INT_KEY_REGISTER, Opcode.RETURN, "overrideInt", "(Ljava/lang/String;I)I"),
    TypedBoundary(APP_AB_PLACEHOLDER, null, "J", listOf("I", "J", "Ljava/lang/String;", "Z"), "p4", Opcode.RETURN_WIDE, "overrideLong", "(Ljava/lang/String;J)J", true),
    TypedBoundary(APP_AB_PLACEHOLDER, null, "Ljava/lang/String;", listOf("I", "Ljava/lang/String;", "Ljava/lang/String;", "Z"), "p2", Opcode.RETURN_OBJECT, "overrideString", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", keyScratchRegister = "v0"),
    TypedBoundary(ABMOCK_SETTINGS_MANAGER_DESCRIPTOR, "LIZ", "Z", listOf("Ljava/lang/String;", "Z"), "p0", Opcode.RETURN, "overrideBoolean", "(Ljava/lang/String;Z)Z"),
    TypedBoundary(ABMOCK_SETTINGS_MANAGER_DESCRIPTOR, "LIZIZ", "D", listOf("Ljava/lang/String;", "D"), "p0", Opcode.RETURN_WIDE, "overrideDouble", "(Ljava/lang/String;D)D", true),
    TypedBoundary(ABMOCK_SETTINGS_MANAGER_DESCRIPTOR, "LIZJ", "F", listOf("Ljava/lang/String;", "F"), "p0", Opcode.RETURN, "overrideFloat", "(Ljava/lang/String;F)F"),
    TypedBoundary(ABMOCK_SETTINGS_MANAGER_DESCRIPTOR, "LJ", "I", listOf("Ljava/lang/String;", "I"), "p0", Opcode.RETURN, "overrideInt", "(Ljava/lang/String;I)I"),
    TypedBoundary(ABMOCK_SETTINGS_MANAGER_DESCRIPTOR, "LJFF", "J", listOf("Ljava/lang/String;", "J"), "p0", Opcode.RETURN_WIDE, "overrideLong", "(Ljava/lang/String;J)J", true),
    TypedBoundary(ABMOCK_SETTINGS_MANAGER_DESCRIPTOR, "LJI", "Ljava/lang/String;", listOf("Ljava/lang/String;", "Ljava/lang/String;"), "p0", Opcode.RETURN_OBJECT, "overrideString", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
    TypedBoundary(LIVE_SETTINGS_DESCRIPTOR, "getBooleanValue", "Z", listOf("Ljava/lang/String;", "Z"), "p1", Opcode.RETURN, "overrideLiveBoolean", "(Ljava/lang/String;Z)Z"),
    TypedBoundary(LIVE_SETTINGS_DESCRIPTOR, "getDoubleValue", "D", listOf("Ljava/lang/String;", "D"), "p1", Opcode.RETURN_WIDE, "overrideLiveDouble", "(Ljava/lang/String;D)D", true),
    TypedBoundary(LIVE_SETTINGS_DESCRIPTOR, "getFloatValue", "F", listOf("Ljava/lang/String;", "F"), "p1", Opcode.RETURN, "overrideLiveFloat", "(Ljava/lang/String;F)F"),
    TypedBoundary(LIVE_SETTINGS_DESCRIPTOR, "getIntValue", "I", listOf("Ljava/lang/String;", "I"), "p1", Opcode.RETURN, "overrideLiveInt", "(Ljava/lang/String;I)I"),
    TypedBoundary(LIVE_SETTINGS_DESCRIPTOR, "getLongValue", "J", listOf("Ljava/lang/String;", "J"), "p1", Opcode.RETURN_WIDE, "overrideLiveLong", "(Ljava/lang/String;J)J", true),
    TypedBoundary(LIVE_SETTINGS_DESCRIPTOR, "getStringValue", "Ljava/lang/String;", listOf("Ljava/lang/String;", "Ljava/lang/String;"), "p1", Opcode.RETURN_OBJECT, "overrideLiveString", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
    TypedBoundary(VE_CONFIG_DESCRIPTOR, "getValue", "Ljava/lang/Boolean;", listOf("Ljava/lang/String;", "Z"), "p1", Opcode.RETURN_OBJECT, "overrideVeBoolean", "(Ljava/lang/String;Ljava/lang/Boolean;)Ljava/lang/Boolean;"),
    TypedBoundary(VE_CONFIG_DESCRIPTOR, "getValue", "F", listOf("Ljava/lang/String;", "F"), "p1", Opcode.RETURN, "overrideVeFloat", "(Ljava/lang/String;F)F"),
    TypedBoundary(VE_CONFIG_DESCRIPTOR, "getValue", "I", listOf("Ljava/lang/String;", "I"), "p1", Opcode.RETURN, "overrideVeInt", "(Ljava/lang/String;I)I"),
    TypedBoundary(VE_CONFIG_DESCRIPTOR, "getValue", "J", listOf("Ljava/lang/String;", "J"), "p1", Opcode.RETURN_WIDE, "overrideVeLong", "(Ljava/lang/String;J)J", true),
    TypedBoundary(VE_CONFIG_DESCRIPTOR, "getValue", "Ljava/lang/String;", listOf("Ljava/lang/String;", "Ljava/lang/String;"), "p1", Opcode.RETURN_OBJECT, "overrideVeString", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
)

@Suppress("unused")
val featureGateLabPatch = bytecodePatch(
    name = "Feature Gate Lab",
    description = "Adds a menu for viewing and overriding supported TikTok feature flags and configuration values.",
    default = true,
) {
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.tiktok4714())

    execute {
        val appAbDescriptor = AppAbIntBoundaryFingerprint.originalClassDef.type

        boundaries.forEach { boundary ->
            val targetDescriptor = if (boundary.targetDescriptor == APP_AB_PLACEHOLDER) appAbDescriptor else boundary.targetDescriptor
            val target = mutableClassDefBy(targetDescriptor)
            val method = target.methods.singleOrNull {
                (boundary.methodName == null || it.name == boundary.methodName) &&
                    it.returnType == boundary.returnType &&
                    it.parameterTypes == boundary.parameters
            } ?: throw PatchException("Feature Gate Lab boundary not found: $targetDescriptor->${boundary.methodName ?: "<by signature>"}${boundary.parameters}")
            method.patchBoundary(boundary)
        }

        val rawAbmock = mutableClassDefBy(appAbDescriptor)
        // 46.2.3 had one (String, Z) object getter; 47.1.4 added a second variant that forces the
        // "return string for object" flag, so prefer the original one (LJIIJJI) when several match.
        val rawGetterCandidates = rawAbmock.methods.filter {
            it.returnType == "Ljava/lang/Object;" &&
                it.parameterTypes == listOf("Ljava/lang/String;", "Z")
        }
        val rawGetter = rawGetterCandidates.singleOrNull()
            ?: rawGetterCandidates.singleOrNull { it.name == "LJIIJJI" }
            ?: throw PatchException("Feature Gate Lab raw App AB boundary not found")
        rawGetter.patchRawAbBoundary()

        val settingsManager = mutableClassDefBy(ABMOCK_SETTINGS_MANAGER_DESCRIPTOR)
        val objectGetterWithoutDefault = settingsManager.methods.singleOrNull {
            it.name == "LJII" &&
                it.returnType == "Ljava/lang/Object;" &&
                it.parameterTypes == listOf("Ljava/lang/String;", "Ljava/lang/Class;")
        } ?: throw PatchException("Feature Gate Lab SettingsManager object boundary without default not found")
        objectGetterWithoutDefault
            .cloneMutableAndPreserveParameters()
            .patchSettingsManagerObjectBoundary(
            hasDefault = false,
            isStatic = true,
        )

        val objectGetterWithDefault = settingsManager.methods.singleOrNull {
            it.name == "LJIIIIZZ" &&
                it.returnType == "Ljava/lang/Object;" &&
                it.parameterTypes == listOf(
                    "Ljava/lang/String;",
                    "Ljava/lang/Class;",
                    "Ljava/lang/Object;",
                )
        } ?: throw PatchException("Feature Gate Lab SettingsManager object boundary with default not found")
        objectGetterWithDefault.patchSettingsManagerObjectBoundary(
            hasDefault = true,
            isStatic = false,
        )

        val liveSettingsManager = mutableClassDefBy(LIVE_SETTINGS_DESCRIPTOR)
        val liveObjectGetterWithDefault = liveSettingsManager.methods.singleOrNull {
            it.name == "getValueSafely" &&
                it.returnType == "Ljava/lang/Object;" &&
                it.parameterTypes == listOf("Ljava/lang/String;", "Ljava/lang/Object;")
        } ?: throw PatchException("Feature Gate Lab Live object boundary with default not found")
        liveObjectGetterWithDefault.patchLiveSettingsObjectBoundary(hasClassKey = false)

        val liveObjectGetterByClass = liveSettingsManager.methods.singleOrNull {
            it.name == "getValueSafely" &&
                it.returnType == "Ljava/lang/Object;" &&
                it.parameterTypes == listOf("Ljava/lang/Class;")
        } ?: throw PatchException("Feature Gate Lab Live object boundary by class not found")
        liveObjectGetterByClass.patchLiveSettingsObjectBoundary(hasClassKey = true)

        val liveStringArrayGetter = liveSettingsManager.methods.singleOrNull {
            it.name == "getStringArrayValue" &&
                it.returnType == "[Ljava/lang/String;" &&
                it.parameterTypes == listOf("Ljava/lang/String;", "[Ljava/lang/String;")
        } ?: throw PatchException("Feature Gate Lab Live string-array boundary not found")
        liveStringArrayGetter.patchLiveSettingsObjectBoundary(hasClassKey = false)

        val playerSettings = mutableClassDefBy(PLAYER_SETTING_SERVICE_DESCRIPTOR)
        val playerGetter = playerSettings.methods.singleOrNull {
            it.name == "get" &&
                it.returnType == "Ljava/lang/Object;" &&
                it.parameterTypes == listOf(
                    "Ljava/lang/String;",
                    "Ljava/lang/reflect/Type;",
                    "Ljava/lang/Object;",
                    "Z",
                    "Z",
                )
        } ?: throw PatchException("Feature Gate Lab PlayerSettingService boundary not found")
        playerGetter.patchPlayerSettingBoundary()

        val activityCenter = mutableClassDefBy(ACTIVITY_CENTER_DESCRIPTOR)
        // getSchema(String, String, String) in 46.2.3 gained a trailing Uri parameter in 47.1.4; only the
        // returned schema string is transformed, so match on the leading parameters.
        val getSchema = activityCenter.methods.singleOrNull {
            it.name == "getSchema" &&
                it.returnType == "Ljava/lang/String;" &&
                it.parameterTypes.size >= 3 &&
                it.parameterTypes.take(3).map(CharSequence::toString) == listOf(
                    "Ljava/lang/String;",
                    "Ljava/lang/String;",
                    "Ljava/lang/String;",
                )
        } ?: throw PatchException("Feature Gate Lab Activity Center schema boundary not found")
        getSchema.patchActivityCenterSchema()

        val runtime = mutableClassDefBy(RUNTIME_DESCRIPTOR)
        val installed = runtime.methods.singleOrNull {
            it.name == "isInstalled" && it.returnType == "Z" && it.parameterTypes.isEmpty()
        } ?: throw PatchException("Feature Gate Lab runtime marker not found")
        installed.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
    }
}

private fun MutableMethod.patchLiveSettingsObjectBoundary(hasClassKey: Boolean) {
    val implementation = implementation
        ?: throw PatchException("Feature Gate Lab Live object boundary has no implementation")
    implementation.instructions
        .withIndex()
        .filter { (_, instruction) -> instruction.opcode == Opcode.RETURN_OBJECT }
        .map { (index, instruction) ->
            index to ((instruction as? OneRegisterInstruction)?.registerA
                ?: throw PatchException("Feature Gate Lab Live object return without result register"))
        }
        .asReversed()
        .forEach { (index, register) ->
            val returnCast = if (returnType == "Ljava/lang/Object;") {
                ""
            } else {
                "\ncheck-cast v$register, $returnType"
            }
            val hook = if (hasClassKey) {
                """
                    invoke-static {p1, v$register}, $RUNTIME_DESCRIPTOR->observeLiveSettingsClassObject(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$register$returnCast
                """
            } else {
                """
                    invoke-static {p1, p2, v$register}, $RUNTIME_DESCRIPTOR->observeLiveSettingsObject(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$register$returnCast
                """
            }
            addInstructions(index, hook)
        }
}

private fun MutableMethod.patchSettingsManagerObjectBoundary(
    hasDefault: Boolean,
    isStatic: Boolean,
) {
    val implementation = implementation
        ?: throw PatchException("Feature Gate Lab SettingsManager object boundary has no implementation")
    implementation.instructions
        .withIndex()
        .filter { (_, instruction) -> instruction.opcode == Opcode.RETURN_OBJECT }
        .map { (index, instruction) ->
            index to ((instruction as? OneRegisterInstruction)?.registerA
                ?: throw PatchException("Feature Gate Lab SettingsManager object return without result register"))
        }
        .asReversed()
        .forEach { (index, register) ->
            val hook = if (hasDefault) {
                """
                    invoke-static {p1, p2, p3, v$register}, $RUNTIME_DESCRIPTOR->observeSettingsObject(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$register
                """
            } else if (isStatic) {
                """
                    invoke-static {p0, p1, v$register}, $RUNTIME_DESCRIPTOR->observeSettingsObjectWithoutDefault(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$register
                """
            } else {
                """
                    invoke-static {p1, p2, v$register}, $RUNTIME_DESCRIPTOR->observeSettingsObjectWithoutDefault(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$register
                """
            }
            addInstructions(index, hook)
        }
}

private fun MutableMethod.patchPlayerSettingBoundary() {
    val implementation = implementation
        ?: throw PatchException("Feature Gate Lab PlayerSettingService boundary has no implementation")
    implementation.instructions
        .withIndex()
        .filter { (_, instruction) -> instruction.opcode == Opcode.RETURN_OBJECT }
        .map { (index, instruction) ->
            index to ((instruction as? OneRegisterInstruction)?.registerA
                ?: throw PatchException("Feature Gate Lab PlayerSettingService return without result register"))
        }
        .asReversed()
        .forEach { (index, register) ->
            addInstructions(
                index,
                """
                    invoke-static {p1, p2, v$register}, $RUNTIME_DESCRIPTOR->overridePlayerValue(Ljava/lang/String;Ljava/lang/reflect/Type;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$register
                """,
            )
        }
}

private fun MutableMethod.patchRawAbBoundary() {
    val implementation = implementation
        ?: throw PatchException("Feature Gate Lab raw App AB boundary has no implementation")
    implementation.instructions
        .withIndex()
        .filter { (_, instruction) -> instruction.opcode == Opcode.RETURN_OBJECT }
        .map { (index, instruction) ->
            index to ((instruction as? OneRegisterInstruction)?.registerA
                ?: throw PatchException("Feature Gate Lab raw App AB return without result register"))
        }
        .asReversed()
        .forEach { (index, register) ->
            addInstructions(
                index,
                """
                    invoke-static {p1, v$register, p2}, $RUNTIME_DESCRIPTOR->overrideRawAbValue(Ljava/lang/String;Ljava/lang/Object;Z)Ljava/lang/Object;
                    move-result-object v$register
                """,
            )
        }
}

private fun MutableMethod.patchActivityCenterSchema() {
    val implementation = implementation
        ?: throw PatchException("Feature Gate Lab Activity Center boundary has no implementation")
    implementation.instructions
        .withIndex()
        .filter { (_, instruction) -> instruction.opcode == Opcode.RETURN_OBJECT }
        .map { (index, instruction) ->
            index to ((instruction as? OneRegisterInstruction)?.registerA
                ?: throw PatchException("Feature Gate Lab Activity Center return without result register"))
        }
        .asReversed()
        .forEach { (index, register) ->
            addInstructions(
                index,
                """
                    invoke-static {v$register}, $RUNTIME_DESCRIPTOR->transformActivityCenterSchema(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$register
                """,
            )
        }
}

private fun MutableMethod.patchBoundary(boundary: TypedBoundary) {
    val implementation = implementation ?: throw PatchException("Feature Gate Lab boundary has no implementation: $this")
    implementation.instructions
        .withIndex()
        .filter { (_, instruction) -> instruction.opcode == boundary.returnOpcode }
        .map { (index, instruction) ->
            index to ((instruction as? OneRegisterInstruction)?.registerA
                ?: throw PatchException("Feature Gate Lab return without result register: $this"))
        }
        .asReversed()
        .forEach { (index, register) ->
            val resultRegisters = if (boundary.wide) "v$register, v${register + 1}" else "v$register"
            val keyRegister = boundary.keyScratchRegister ?: boundary.keyParameter
            val prepareKey = boundary.keyScratchRegister?.let {
                if (it == "v$register") {
                    throw PatchException("Feature Gate Lab key scratch overlaps result register: $this")
                }
                "move-object/from16 $it, ${boundary.keyParameter}\n"
            }.orEmpty()
            val moveResult = when (boundary.returnOpcode) {
                Opcode.RETURN_WIDE -> "move-result-wide v$register"
                Opcode.RETURN_OBJECT -> "move-result-object v$register"
                else -> "move-result v$register"
            }
            addInstructions(
                index,
                """
                    $prepareKey
                    invoke-static {$keyRegister, $resultRegisters}, $RUNTIME_DESCRIPTOR->${boundary.runtimeMethod}${boundary.runtimeDescriptor}
                    $moveResult
                """,
            )
        }
}
