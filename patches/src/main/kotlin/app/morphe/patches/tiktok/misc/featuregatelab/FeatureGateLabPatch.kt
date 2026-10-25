/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.misc.featuregatelab

import app.morphe.util.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.absettings.APP_AB_INT_KEY_REGISTER
import app.morphe.patches.tiktok.misc.absettings.APP_AB_RAW
import app.morphe.patches.tiktok.misc.absettings.MethodShape
import app.morphe.patches.tiktok.misc.absettings.appAbClass
import app.morphe.patches.tiktok.misc.absettings.methodOfShape
import app.morphe.patches.tiktok.misc.absettings.methodsOfShape
import app.morphe.patches.tiktok.misc.absettings.shape
import app.morphe.patches.tiktok.misc.settings.registerForBuildDetails
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.callThroughLocals
import app.morphe.patches.tiktok.shared.objectIn
import app.morphe.patches.tiktok.shared.valueIn
import app.morphe.patches.tiktok.shared.wideIn
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.cloneMutableAndPreserveParameters
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/**
 * Stands in for the app AB class in the table below. That class has no name a patch can carry
 * (it was `LX/0BYX;` on 46.2.3 and something else on every build since), so the table names it
 * with this and execute resolves it by the getters it carries.
 */
private const val APP_AB = "<app ab class>"
private const val ABMOCK_SETTINGS_MANAGER_DESCRIPTOR = "Lcom/bytedance/ies/abmock/SettingsManager;"
private const val LIVE_SETTINGS_DESCRIPTOR = "Lcom/bytedance/android/live_settings/SettingsManager;"
private const val ACTIVITY_CENTER_DESCRIPTOR = "Lcom/ss/android/ugc/tiktok/pns/activitycenter/EnterActivityCenterAction;"
private const val VE_CONFIG_DESCRIPTOR = "Lcom/ss/android/vesdk/VEConfigCenter;"
private const val PLAYER_SETTING_SERVICE_DESCRIPTOR =
    "Lcom/ss/android/ugc/aweme/video/simplayer/PlayerSettingServiceImpl;"
private const val RUNTIME_DESCRIPTOR = "Lapp/morphe/extension/tiktok/featuregatelab/FeatureGateLabRuntime;"

private data class TypedBoundary(
    val targetDescriptor: String,
    val methodName: String,
    val returnType: String,
    val parameters: List<String>,
    val keyParameter: String,
    val returnOpcode: Opcode,
    val runtimeMethod: String,
    val runtimeDescriptor: String,
    val wide: Boolean = false,
    /** Whether the boundary is a static method, which is what [keyParameter] counts from. */
    val isStatic: Boolean = false,
    /**
     * Whether [methodName] is a name TikTok wrote rather than one R8 assigned.
     *
     * <p>A keep rule saves a class name without saving its members. `SettingsManager` proves it
     * on itself: `getSettingsValueProvider` survives and everything beside it is `LIZ`, `LIZIZ`,
     * `LJFF`. A minified name is worth a tiebreak between two methods of one shape and nothing
     * more, because requiring it turns a rename into a failure on a build the shape would have
     * patched. A real name is the anchor and is required, so that an unrelated helper of the
     * same signature cannot stand in for a getter that was inlined away.
     */
    val nameIsStable: Boolean = false,
)

private val boundaries = listOf(
    TypedBoundary(APP_AB, "LIZ", "Z", listOf("I", "Ljava/lang/String;", "Z", "Z"), "p2", Opcode.RETURN, "overrideBoolean", "(Ljava/lang/String;Z)Z"),
    TypedBoundary(APP_AB, "LIZJ", "D", listOf("D", "I", "Ljava/lang/String;", "Z"), "p4", Opcode.RETURN_WIDE, "overrideDouble", "(Ljava/lang/String;D)D", true),
    TypedBoundary(APP_AB, "LIZLLL", "F", listOf("I", "Ljava/lang/String;", "Z", "F"), "p2", Opcode.RETURN, "overrideFloat", "(Ljava/lang/String;F)F"),
    TypedBoundary(APP_AB, "LJFF", "I", listOf("I", "I", "Ljava/lang/String;", "Z"), APP_AB_INT_KEY_REGISTER, Opcode.RETURN, "overrideInt", "(Ljava/lang/String;I)I"),
    TypedBoundary(APP_AB, "LJII", "J", listOf("I", "J", "Ljava/lang/String;", "Z"), "p4", Opcode.RETURN_WIDE, "overrideLong", "(Ljava/lang/String;J)J", true),
    TypedBoundary(APP_AB, "LJIIIIZZ", "Ljava/lang/String;", listOf("I", "Ljava/lang/String;", "Ljava/lang/String;", "Z"), "p2", Opcode.RETURN_OBJECT, "overrideString", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
    TypedBoundary(ABMOCK_SETTINGS_MANAGER_DESCRIPTOR, "LIZ", "Z", listOf("Ljava/lang/String;", "Z"), "p0", Opcode.RETURN, "overrideBoolean", "(Ljava/lang/String;Z)Z", isStatic = true),
    TypedBoundary(ABMOCK_SETTINGS_MANAGER_DESCRIPTOR, "LIZIZ", "D", listOf("Ljava/lang/String;", "D"), "p0", Opcode.RETURN_WIDE, "overrideDouble", "(Ljava/lang/String;D)D", true, isStatic = true),
    TypedBoundary(ABMOCK_SETTINGS_MANAGER_DESCRIPTOR, "LIZJ", "F", listOf("Ljava/lang/String;", "F"), "p0", Opcode.RETURN, "overrideFloat", "(Ljava/lang/String;F)F", isStatic = true),
    TypedBoundary(ABMOCK_SETTINGS_MANAGER_DESCRIPTOR, "LJ", "I", listOf("Ljava/lang/String;", "I"), "p0", Opcode.RETURN, "overrideInt", "(Ljava/lang/String;I)I", isStatic = true),
    TypedBoundary(ABMOCK_SETTINGS_MANAGER_DESCRIPTOR, "LJFF", "J", listOf("Ljava/lang/String;", "J"), "p0", Opcode.RETURN_WIDE, "overrideLong", "(Ljava/lang/String;J)J", true, isStatic = true),
    TypedBoundary(ABMOCK_SETTINGS_MANAGER_DESCRIPTOR, "LJI", "Ljava/lang/String;", listOf("Ljava/lang/String;", "Ljava/lang/String;"), "p0", Opcode.RETURN_OBJECT, "overrideString", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", isStatic = true),
    TypedBoundary(LIVE_SETTINGS_DESCRIPTOR, "getBooleanValue", "Z", listOf("Ljava/lang/String;", "Z"), "p1", Opcode.RETURN, "overrideLiveBoolean", "(Ljava/lang/String;Z)Z", nameIsStable = true),
    TypedBoundary(LIVE_SETTINGS_DESCRIPTOR, "getDoubleValue", "D", listOf("Ljava/lang/String;", "D"), "p1", Opcode.RETURN_WIDE, "overrideLiveDouble", "(Ljava/lang/String;D)D", true, nameIsStable = true),
    TypedBoundary(LIVE_SETTINGS_DESCRIPTOR, "getFloatValue", "F", listOf("Ljava/lang/String;", "F"), "p1", Opcode.RETURN, "overrideLiveFloat", "(Ljava/lang/String;F)F", nameIsStable = true),
    TypedBoundary(LIVE_SETTINGS_DESCRIPTOR, "getIntValue", "I", listOf("Ljava/lang/String;", "I"), "p1", Opcode.RETURN, "overrideLiveInt", "(Ljava/lang/String;I)I", nameIsStable = true),
    TypedBoundary(LIVE_SETTINGS_DESCRIPTOR, "getLongValue", "J", listOf("Ljava/lang/String;", "J"), "p1", Opcode.RETURN_WIDE, "overrideLiveLong", "(Ljava/lang/String;J)J", true, nameIsStable = true),
    TypedBoundary(LIVE_SETTINGS_DESCRIPTOR, "getStringValue", "Ljava/lang/String;", listOf("Ljava/lang/String;", "Ljava/lang/String;"), "p1", Opcode.RETURN_OBJECT, "overrideLiveString", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", nameIsStable = true),
    TypedBoundary(VE_CONFIG_DESCRIPTOR, "getValue", "Ljava/lang/Boolean;", listOf("Ljava/lang/String;", "Z"), "p1", Opcode.RETURN_OBJECT, "overrideVeBoolean", "(Ljava/lang/String;Ljava/lang/Boolean;)Ljava/lang/Boolean;", nameIsStable = true),
    TypedBoundary(VE_CONFIG_DESCRIPTOR, "getValue", "F", listOf("Ljava/lang/String;", "F"), "p1", Opcode.RETURN, "overrideVeFloat", "(Ljava/lang/String;F)F", nameIsStable = true),
    TypedBoundary(VE_CONFIG_DESCRIPTOR, "getValue", "I", listOf("Ljava/lang/String;", "I"), "p1", Opcode.RETURN, "overrideVeInt", "(Ljava/lang/String;I)I", nameIsStable = true),
    TypedBoundary(VE_CONFIG_DESCRIPTOR, "getValue", "J", listOf("Ljava/lang/String;", "J"), "p1", Opcode.RETURN_WIDE, "overrideVeLong", "(Ljava/lang/String;J)J", true, nameIsStable = true),
    TypedBoundary(VE_CONFIG_DESCRIPTOR, "getValue", "Ljava/lang/String;", listOf("Ljava/lang/String;", "Ljava/lang/String;"), "p1", Opcode.RETURN_OBJECT, "overrideVeString", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", nameIsStable = true),
)

@Suppress("unused")
val featureGateLabPatch = bytecodePatch(
    name = "Feature Gate Lab",
    description = "Adds an advanced page that lists the hidden settings TikTok uses to test " +
        "features and lets you change them. It's for people who like to experiment. Starts off. " +
        "Turn it on in Metra settings > Diagnostics.",
    default = true,
) {
    category("Settings")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        boundaries.forEach { boundary ->
            val target = if (boundary.targetDescriptor == APP_AB) appAbClass()
            else mutableClassDefBy(boundary.targetDescriptor)
            val shape = MethodShape(boundary.returnType, boundary.parameters, boundary.isStatic)
            // Whether the name is required turns on whether R8 assigned it, not on whether the
            // class name did. SettingsManager keeps its own name and calls its getters LIZ and
            // LJFF, the same alphabet as the app AB class; live_settings and VEConfigCenter
            // keep theirs all the way down.
            val ofShape = target.methods.filter { it.shape() == shape }
            val candidates =
                if (boundary.nameIsStable) ofShape.filter { it.name == boundary.methodName }
                else ofShape
            val method = when (candidates.size) {
                1 -> candidates.single()
                0 -> throw PatchException(
                    "Feature Gate Lab boundary not found: ${target.type} has no " +
                        (if (boundary.nameIsStable) "${boundary.methodName} of " else "") +
                        "${boundary.returnType}${boundary.parameters}.",
                )
                else -> candidates.singleOrNull { it.name == boundary.methodName }
                    ?: throw PatchException(
                        "Feature Gate Lab boundary is ambiguous: ${target.type} has " +
                            "${candidates.size} of ${boundary.returnType}${boundary.parameters} and " +
                            "none is called ${boundary.methodName}.",
                    )
            }
            method.patchBoundary(boundary)
        }

        // Every one of them, not the one. The raw getter's key is p1 and its flag is p2
        // whichever door the app came through, so a build that carries two carries two doors
        // into the same thing and the Lab has to hold both open. 46.9.3 is the first that does.
        appAbClass().methodsOfShape(APP_AB_RAW, "Feature Gate Lab raw App AB boundary")
            .forEach { it.patchRawAbBoundary() }

        val settingsManager = mutableClassDefBy(ABMOCK_SETTINGS_MANAGER_DESCRIPTOR)
        // By shape, like the table above: these were `LJII` and `LJIIIIZZ`, which are R8's names
        // on a class whose own name is kept, and each shape is the only one of its kind here.
        // Static, because this one reads its key from p0 and the shape does not say so.
        val objectGetterWithoutDefault = settingsManager.methodOfShapeOrNamed(
            "LJII",
            "Feature Gate Lab SettingsManager static object boundary without default",
        ) {
            it.returnType == "Ljava/lang/Object;" &&
                it.parameterTypes == listOf("Ljava/lang/String;", "Ljava/lang/Class;") &&
                AccessFlags.STATIC.value and it.accessFlags != 0
        }
        objectGetterWithoutDefault
            .cloneMutableAndPreserveParameters()
            .patchSettingsManagerObjectBoundary(
            hasDefault = false,
            isStatic = true,
        )

        val objectGetterWithDefault = settingsManager.methodOfShapeOrNamed(
            "LJIIIIZZ",
            "Feature Gate Lab SettingsManager instance object boundary with default",
        ) {
            it.returnType == "Ljava/lang/Object;" &&
                it.parameterTypes == listOf(
                    "Ljava/lang/String;",
                    "Ljava/lang/Class;",
                    "Ljava/lang/Object;",
                ) &&
                AccessFlags.STATIC.value and it.accessFlags == 0
        }
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
        // The parameter list used to be spelled out here and 46.8.3 added a fourth, a Uri, which
        // took the whole patch down. Nothing below reads a parameter: the schema is taken off the
        // return register, so what identifies the method is that it is the class's one String
        // getSchema. A second one appearing is worth failing on rather than guessing between.
        val getSchema = activityCenter.methods.singleOrNull {
            it.name == "getSchema" && it.returnType == "Ljava/lang/String;"
        } ?: throw PatchException("Feature Gate Lab Activity Center schema boundary not found")
        getSchema.patchActivityCenterSchema()

        val runtime = mutableClassDefBy(RUNTIME_DESCRIPTOR)
        val installed = runtime.methods.singleOrNull {
            it.name == "isInstalled" && it.returnType == "Z" && it.parameterTypes.isEmpty()
        } ?: throw PatchException("Feature Gate Lab runtime marker not found")
        installed.returnEarly(true)
        registerForBuildDetails("enableFeatureGateLab")
    }
}

/**
 * The one method of the shape, or the one of that shape called [name] when the shape has stopped
 * being unique. The same trade the boundary table above makes: an R8 name is worth a tiebreak and
 * not a requirement.
 */
private fun MutableClass.methodOfShapeOrNamed(
    name: String,
    what: String,
    shape: (MutableMethod) -> Boolean,
): MutableMethod {
    val ofShape = methods.filter(shape)
    return when (ofShape.size) {
        1 -> ofShape.single()
        0 -> throw PatchException("$what not found on $type")
        else -> ofShape.singleOrNull { it.name == name }
            ?: throw PatchException(
                "$what is ambiguous: $type has ${ofShape.size} of that shape and none is $name",
            )
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
            val call = if (hasClassKey) {
                callThroughLocals(
                    "Feature Gate Lab",
                    "invoke-static",
                    "$RUNTIME_DESCRIPTOR->observeLiveSettingsClassObject(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;",
                    objectIn("p1"),
                    objectIn("v$register"),
                )
            } else {
                callThroughLocals(
                    "Feature Gate Lab",
                    "invoke-static",
                    "$RUNTIME_DESCRIPTOR->observeLiveSettingsObject(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    objectIn("p1"),
                    objectIn("p2"),
                    objectIn("v$register"),
                )
            }
            val hook = "$call\nmove-result-object v$register$returnCast"
            addInstructionsAtControlFlowLabel(index, hook)
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
            val call = if (hasDefault) {
                callThroughLocals(
                    "Feature Gate Lab",
                    "invoke-static",
                    "$RUNTIME_DESCRIPTOR->observeSettingsObject(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    objectIn("p1"),
                    objectIn("p2"),
                    objectIn("p3"),
                    objectIn("v$register"),
                )
            } else if (isStatic) {
                callThroughLocals(
                    "Feature Gate Lab",
                    "invoke-static",
                    "$RUNTIME_DESCRIPTOR->observeSettingsObjectWithoutDefault(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;",
                    objectIn("p0"),
                    objectIn("p1"),
                    objectIn("v$register"),
                )
            } else {
                callThroughLocals(
                    "Feature Gate Lab",
                    "invoke-static",
                    "$RUNTIME_DESCRIPTOR->observeSettingsObjectWithoutDefault(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;",
                    objectIn("p1"),
                    objectIn("p2"),
                    objectIn("v$register"),
                )
            }
            val hook = "$call\nmove-result-object v$register"
            addInstructionsAtControlFlowLabel(index, hook)
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
            val call = callThroughLocals(
                "Feature Gate Lab",
                "invoke-static",
                "$RUNTIME_DESCRIPTOR->overridePlayerValue(Ljava/lang/String;Ljava/lang/reflect/Type;Ljava/lang/Object;)Ljava/lang/Object;",
                objectIn("p1"),
                objectIn("p2"),
                objectIn("v$register"),
            )
            addInstructionsAtControlFlowLabel(index, "$call\nmove-result-object v$register")
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
            val call = callThroughLocals(
                "Feature Gate Lab",
                "invoke-static",
                "$RUNTIME_DESCRIPTOR->overrideRawAbValue(Ljava/lang/String;Ljava/lang/Object;Z)Ljava/lang/Object;",
                objectIn("p1"),
                objectIn("v$register"),
                valueIn("p2"),
            )
            addInstructionsAtControlFlowLabel(index, "$call\nmove-result-object v$register")
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
            addInstructionsAtControlFlowLabel(
                index,
                """
                    invoke-static/range {v$register .. v$register}, $RUNTIME_DESCRIPTOR->transformActivityCenterSchema(Ljava/lang/String;)Ljava/lang/String;
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
            // The key is a parameter register, and on a large host method that sits above the
            // fifteen a plain invoke can name. One boundary used to carry its own hand written
            // move for exactly that; every one of them goes through the same helper now, which
            // leaves the instruction alone when it already fits.
            val result = when (boundary.returnOpcode) {
                Opcode.RETURN_WIDE -> wideIn("v$register")
                Opcode.RETURN_OBJECT -> objectIn("v$register")
                else -> valueIn("v$register")
            }
            val call = callThroughLocals(
                "Feature Gate Lab",
                "invoke-static",
                "$RUNTIME_DESCRIPTOR->${boundary.runtimeMethod}${boundary.runtimeDescriptor}",
                objectIn(boundary.keyParameter),
                result,
            )
            val moveResult = when (boundary.returnOpcode) {
                Opcode.RETURN_WIDE -> "move-result-wide v$register"
                Opcode.RETURN_OBJECT -> "move-result-object v$register"
                else -> "move-result v$register"
            }
            addInstructionsAtControlFlowLabel(index, "$call\n$moveResult")
        }
}
