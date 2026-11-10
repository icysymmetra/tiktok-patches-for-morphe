/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/interaction/speed/PlaybackSpeedPatch.kt
 */
package app.morphe.patches.tiktok.interaction.speed

import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.OnRenderFirstFrameFingerprint
import app.morphe.patches.tiktok.interaction.blockauthor.PlayerProgressAidFingerprint
import app.morphe.patches.tiktok.interaction.cleardisplay.OnRenderFirstFrameBodyFingerprint
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.cloneMutable
import app.morphe.patcher.util.getReference
import app.morphe.util.implementationOrPatchException
import app.morphe.util.sameBodiedOrPatchException
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/speed/PlaybackSpeedPatch;"
private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val VIEW = "Landroid/view/View;"
private const val BASE_SHARE_PACKAGE = "Lcom/ss/android/ugc/aweme/share/base/model/BaseSharePackage;"

/**
 * The no-argument PlayerController getter the first-frame body reads the video from. The class
 * has three public no-argument Aweme getters on every declared build and the body calls one of
 * them, so the body is what names it.
 */
internal fun Method.firstFrameAwemeGetter(): MethodReference =
    implementationOrPatchException("Playback speed").instructions.mapNotNull {
        it.getReference<MethodReference>()
    }.filter {
        it.definingClass == definingClass && it.parameterTypes.isEmpty() && it.returnType == AWEME
    }.distinctBy { it.toString() }
        .singleOrPatchException("Playback speed: first-frame Aweme getter")

/**
 * The static fields the speed selection method writes before its first branch: the selected
 * video and the two current-speed floats. Reading them off the writes is what keeps this
 * working when R8 renames them.
 */
internal fun Method.speedSelectionWrites(): List<FieldReference> =
    implementationOrPatchException("Playback speed").instructions.takeWhile {
        !it.opcode.name.startsWith("if-")
    }.filter { it.opcode == Opcode.SPUT || it.opcode == Opcode.SPUT_OBJECT }
        .mapNotNull { it.getReference<FieldReference>() }
        .filter { it.definingClass == definingClass }

/**
 * The body of the bridge that hands a speed to the player of the video on screen: what the
 * first-frame bridge does once it has picked a speed, with the speed passed in (p1) and the
 * controller (p0) in place of the first frame's own. The two free registers are v0 and v1, since
 * the bridge is cloned with two more than the stub has.
 */
internal fun liveSpeedBridgeBody(
    controller: String,
    awemeGetter: MethodReference,
    currentAwemeField: FieldReference,
    speedFields: List<FieldReference>,
    setSpeed: MethodReference,
): String = """
    check-cast p0, $controller
    invoke-virtual/range {p0 .. p0}, $awemeGetter
    move-result-object v0
    sput-object v0, $currentAwemeField
    ${speedFields.joinToString("\n") { "sput p1, $it" }}
    invoke-virtual/range {p0 .. p1}, $setSpeed
    return-void
"""

/** The live speed change's hook site and the two extension stubs it fills in. */
private class LiveSpeedSites(val progress: MutableMethod, val setter: MutableMethod, val aweme: MutableMethod)

/** The body of the bridge that answers which video a PlayerController has, for the same getter. */
internal fun liveAwemeBridgeBody(controller: String, awemeGetter: MethodReference): String = """
    check-cast p0, $controller
    invoke-virtual/range {p0 .. p0}, $awemeGetter
    move-result-object v0
    return-object v0
"""

@Suppress("unused")
val playbackSpeedPatch = bytecodePatch(
    name = "Playback speed",
    description = "Remembers the speed you picked for the next video, and lets you set a " +
        "default speed, add speeds up to 3x to the menu and choose the speed for press and hold. " +
        "On by default. Turn it off in Metra settings > App behavior.",
    default = true,
) {
    category("Playback")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        // The live speed gesture's hook and bridges are resolved before the first write below, so
        // a build that lost one of them leaves nothing half patched. They're an add-on: on a build
        // nobody has checked, a miss leaves the live change out with a note and the rest of the
        // patch goes on. On a declared one LiveSpeedAnchorsTest holds them, so a miss fails it.
        val liveExtension = mutableClassDefBy(EXTENSION)
        val live = try {
            val progress = PlayerProgressAidFingerprint.method
            check(!AccessFlags.STATIC.isSet(progress.accessFlags)) {
                "Playback speed: ${progress.name} became static, so p0 is not the player."
            }
            check(progress.parameterTypes.firstOrNull()?.toString() == "Ljava/lang/String;") {
                "Playback speed: ${progress.name} no longer takes the video id first, so p1 is not it."
            }
            // The live bridge casts the reporting player to the first frame's controller class.
            check(progress.definingClass == OnRenderFirstFrameBodyFingerprint.method.definingClass) {
                "Playback speed: the progress report sits on ${progress.definingClass}, not on the first " +
                    "frame's ${OnRenderFirstFrameBodyFingerprint.method.definingClass}."
            }
            val liveSetter = liveExtension.methods.filter { it.name == "setNativeSpeed" }
                .singleOrPatchException("Playback speed: extension setNativeSpeed bridge")
            val liveAweme = liveExtension.methods.filter { it.name == "nativeAweme" }
                .singleOrPatchException("Playback speed: extension nativeAweme bridge")
            check(
                AccessFlags.STATIC.isSet(liveSetter.accessFlags) && liveSetter.returnType == "V" &&
                    liveSetter.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;", "F"),
            ) { "Playback speed: the extension's setNativeSpeed stub is not static (Object, float)V." }
            check(
                AccessFlags.STATIC.isSet(liveAweme.accessFlags) && liveAweme.returnType == AWEME &&
                    liveAweme.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;"),
            ) { "Playback speed: the extension's nativeAweme stub is not static (Object)Aweme." }
            LiveSpeedSites(progress, liveSetter, liveAweme)
        } catch (problem: Exception) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            println("[Playback speed] Left out the live speed change on ${packageMetadata.versionName}: ${problem.message}")
            null
        }

        val selection = PlaybackSpeedSelectionBoundaryFingerprint.method
        check(AccessFlags.STATIC.isSet(selection.accessFlags)) {
            "Playback speed: ${selection.name} is no longer static, so p0 is not the speed."
        }
        selection.addInstruction(
            0,
            "invoke-static/range {p0 .. p3}, $EXTENSION->onSelection(F${AWEME}Ljava/lang/String;Ljava/lang/String;)V",
        )

        val controllerSetSpeed = PlayerControllerSetSpeedFingerprint.method
        val playerManagerSetSpeed = controllerSetSpeed.playerManagerSpeedBoundary() ?: throw PatchException(
            "Playback speed: player-manager speed boundary was not resolved.",
        )

        val playerControllerClass = mutableClassDefBy(controllerSetSpeed.definingClass)
        val transitionResetCandidates = playerControllerClass.methods.filter { method ->
            method.parameterTypes == listOf(
                "Lcom/ss/android/ugc/aweme/feed/model/Aweme;",
                "Z",
            ) && method.returnType == "V" && method.implementation?.instructions?.count { instruction ->
                instruction.getReference<MethodReference>() == playerManagerSetSpeed
            } == 3
        }
        val transitionReset = transitionResetCandidates.singleOrPatchException(
            "Playback speed: three-branch feed transition reset",
        )

        val transitionCallers = playerControllerClass.methods.filter { method ->
            val instructions = method.implementation?.instructions ?: return@filter false
            val callsTransitionReset = instructions.any { instruction ->
                instruction.getReference<MethodReference>()?.let { reference ->
                    reference.definingClass == transitionReset.definingClass &&
                        reference.name == transitionReset.name &&
                        reference.parameterTypes == transitionReset.parameterTypes &&
                        reference.returnType == transitionReset.returnType
                } == true
            }
            callsTransitionReset && instructions.any { instruction ->
                instruction.getReference<MethodReference>() == playerManagerSetSpeed
            }
        }
        if (transitionCallers.isEmpty()) {
            throw PatchException(
                "Playback speed: no feed transition caller with a direct speed write was found.",
            )
        }

        (listOf(transitionReset) + transitionCallers).forEach { method ->
            val directCalls = method.implementationOrPatchException("Playback speed").instructions
                .withIndex().mapNotNull { (index, instruction) ->
                if (instruction.getReference<MethodReference>() != playerManagerSetSpeed) {
                    return@mapNotNull null
                }
                val speedRegister = when (instruction) {
                    is FiveRegisterInstruction -> instruction.registerD
                    is RegisterRangeInstruction -> instruction.startRegister + 1
                    else -> throw PatchException(
                        "Playback speed: unsupported feed transition speed invocation shape.",
                    )
                }
                index to speedRegister
            }
            directCalls.asReversed().forEach { (index, speedRegister) ->
                method.addInstructions(
                    index,
                    """
                        invoke-static/range {v$speedRegister .. v$speedRegister}, $EXTENSION->preserveTransitionSpeed(F)F
                        move-result v$speedRegister
                    """,
                )
            }
        }

        check(!AccessFlags.STATIC.isSet(transitionReset.accessFlags)) {
            "Playback speed: ${transitionReset.name} became static, so p1 is not the video."
        }
        transitionReset.addInstruction(0, "invoke-static/range {p1 .. p1}, $EXTENSION->beginVideo($AWEME)V")

        val frame = OnRenderFirstFrameBodyFingerprint.method
        // Keep native menu highlighting and its same-speed guard aligned with the player.
        val stateWrites = selection.speedSelectionWrites()
        val currentAwemeField = stateWrites.filter { it.type == AWEME }
            .singleOrPatchException("Playback speed: selected-video state field")
        val speedFields = stateWrites.filter { it.type == "F" }.distinctBy { it.toString() }
        check(speedFields.size == 2) {
            "Playback speed: expected two float fields written before the first branch of " +
                "${selection.name}, found ${speedFields.size}."
        }
        check(mutableClassDefBy(selection.definingClass).fields.filter { field ->
            stateWrites.any { it.name == field.name }
        }.all { AccessFlags.PUBLIC.isSet(it.accessFlags) }) {
            "Playback speed: the state fields on ${selection.definingClass} are not all public, " +
                "so the extension cannot read them."
        }
        val awemeGetter = frame.firstFrameAwemeGetter()
        val extension = mutableClassDefBy(EXTENSION)
        val original = extension.methods.filter { it.name == "onFirstFrame" }
            .singleOrPatchException("Playback speed: extension onFirstFrame bridge")
        val bridge = original.cloneMutable(additionalRegisters = 2)
        extension.methods.remove(original)
        extension.methods.add(bridge)
        bridge.addInstructions(0, """
            check-cast p0, ${frame.definingClass}
            invoke-virtual/range {p0 .. p0}, $awemeGetter
            move-result-object v0
            invoke-static/range {v0 .. v0}, $EXTENSION->getPlaybackSpeedForVideo($AWEME)F
            move-result v1
            sput-object v0, $currentAwemeField
            ${speedFields.joinToString("\n") { "sput v1, $it" }}
            move-object/from16 v0, p0
            invoke-virtual/range {v0 .. v1}, $controllerSetSpeed
            return-void
        """)

        OnRenderFirstFrameFingerprint.method.addInstruction(
            0,
            "invoke-static/range {p0 .. p0}, $EXTENSION->onFirstFrame(Ljava/lang/Object;)V",
        )

        // A live speed change, for the edge swipe set to Speed, reaches TikTok's player only
        // through bytecode as well, in the same shape as the first-frame bridge: a controller and
        // a speed in, the native selection state brought along, the controller's setSpeed called.
        // Which controller is on screen is the progress report's to say (a preloaded neighbour
        // can draw its first frame early), so that report hands the extension its controller.
        if (live != null) {
            val liveSetterBridge = live.setter.cloneMutable(additionalRegisters = 2)
            liveExtension.methods.remove(live.setter)
            liveExtension.methods.add(liveSetterBridge)
            liveSetterBridge.addInstructions(
                0,
                liveSpeedBridgeBody(frame.definingClass, awemeGetter, currentAwemeField, speedFields, controllerSetSpeed),
            )
            val liveAwemeBridge = live.aweme.cloneMutable(additionalRegisters = 2)
            liveExtension.methods.remove(live.aweme)
            liveExtension.methods.add(liveAwemeBridge)
            liveAwemeBridge.addInstructions(0, liveAwemeBridgeBody(frame.definingClass, awemeGetter))
            live.progress.addInstruction(
                0,
                "invoke-static/range {p0 .. p1}, $EXTENSION->onPlayerProgress(Ljava/lang/Object;Ljava/lang/String;)V",
            )
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableLiveSpeed()V",
            )
        }

        // Resolve the menu's lazy Float-list factory from its own constructor references.
        val menuClass = mutableClassDefBy(PlaybackSpeedMenuFingerprint.method.definingClass)
        val factoryOwners = menuClass.methods.filter { it.name == "<init>" }.flatMap { method ->
            method.implementationOrPatchException("Playback speed").instructions
                .mapNotNull { it.getReference<MethodReference>() }
        }.filter {
            it.parameterTypes == listOf("I") && it.returnType == it.definingClass &&
                it.definingClass.startsWith("Lkotlin/jvm/internal/")
        }.map { it.definingClass }.distinct()
        val expected = listOf(0.5f, 1f, 1.5f, 2f, 3f).map { it.toRawBits() }
        val factories = factoryOwners.flatMap { mutableClassDefBy(it).methods }.filter { method ->
            method.returnType == "Ljava/lang/Object;" && AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.implementation?.instructions?.filterIsInstance<NarrowLiteralInstruction>()
                    ?.map { it.narrowLiteral }?.containsAll(expected) == true
        }
        // 46.9.3 carries this body twice on one lambda class, invoke$328 and invoke$851, with
        // identical instructions: R8 outlined the same lambda once per call site. Both hand back
        // the same speed list and the menu reaches one of them, so refusing on the count would
        // have taken the patch down over a duplicate of itself. Two bodies that are not the same
        // body are a different matter and still refuse, because then this would be guessing
        // which one the menu means.
        factories.sameBodiedOrPatchException("Playback speed: menu list factory").forEach { factory ->
            val returns = factory.implementationOrPatchException("Playback speed").instructions
                .withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
            check(returns.isNotEmpty()) {
                "Playback speed: the menu list factory returns no object to replace."
            }
            returns.asReversed().forEach { (index, instruction) ->
                val register = (instruction as OneRegisterInstruction).registerA
                factory.addInstructionsAtControlFlowLabel(index, """
                    invoke-static/range {v$register .. v$register}, $EXTENSION->menuSpeeds(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$register
                """)
            }
        }
        // TikTok's speed menu toast names the nearest built-in speed for a custom one: the click
        // handler picks the label by exact value (only 0.5, 1.5, 2 and 3 have their own) and
        // formats "Playing at %s speed", so 2.5x reads "Playing at 2x speed". Where the label goes
        // into the format's one argument the chosen speed field is also still in hand, so the label
        // is reworded to the speed there. Of the class's three (View, BaseSharePackage) methods the
        // other two are trivial delegators; the handler is the one that reads the speed and builds
        // the toast's single format argument.
        val speedField = menuClass.fields.filter { it.type == "F" }
            .singleOrPatchException("Playback speed: the speed menu class's float speed field")
        fun readsSpeed(instruction: com.android.tools.smali.dexlib2.iface.instruction.Instruction) =
            instruction.opcode == Opcode.IGET && instruction.getReference<FieldReference>()?.let {
                it.definingClass == speedField.definingClass && it.name == speedField.name && it.type == "F"
            } == true
        val toastHandler = menuClass.methods.filter { method ->
            method.parameterTypes == listOf(VIEW, BASE_SHARE_PACKAGE) && method.returnType == "V" &&
                method.implementation?.instructions?.count { it.opcode == Opcode.APUT_OBJECT } == 1 &&
                method.implementation!!.instructions.any(::readsSpeed)
        }.singleOrPatchException("Playback speed: the speed menu toast handler")
        val toastInstructions = toastHandler.implementationOrPatchException("Playback speed").instructions.toList()
        val speedRegister = toastInstructions.filter(::readsSpeed)
            .singleOrPatchException("Playback speed: the menu toast's speed field read")
            .let { (it as OneRegisterInstruction).registerA }
        val labelPut = toastInstructions.withIndex().filter { it.value.opcode == Opcode.APUT_OBJECT }
            .singleOrPatchException("Playback speed: the menu toast's format argument")
        val labelRegister = (labelPut.value as ThreeRegisterInstruction).registerA
        if (labelRegister > 15 || speedRegister > 15) {
            throw PatchException("Playback speed: the menu toast's registers no longer fit a short call.")
        }
        toastHandler.addInstructions(
            labelPut.index,
            """
                invoke-static {v$labelRegister, v$speedRegister}, $EXTENSION->menuSpeedText(Ljava/lang/String;F)Ljava/lang/String;
                move-result-object v$labelRegister
            """,
        )

        // The hold gesture plays at a literal 2x (upstream #52): one 2.0f in the press method,
        // feeding the apply call and the "already at 2x" lock state, and two in the release
        // method, one for the speed-up telemetry and one the pull-down lock applies and
        // persists through the selection boundary. Every one of them goes through the hold
        // speed row, so press, lock and telemetry agree on the number, and so do TikTok's own
        // words for the hold: the two banners and the lock toast (HoldSpeed.kt).
        val press = EdgeSpeedupPressFingerprint.method
        val release = EdgeSpeedupReleaseFingerprint.method
        press.routeThroughHoldSpeed(press.holdSpeedLiterals("the hold gesture's press method"))
        release.routeThroughHoldSpeed(release.holdSpeedLiterals("the hold gesture's release method"))
        rewordHoldSpeedToasts(release)
        rewordHoldSpeedBanners(HoldBannerReceiverFingerprint.method)

        SettingsStatusLoadFingerprint.method.addInstruction(0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enablePlaybackSpeed()V")
    }
}
