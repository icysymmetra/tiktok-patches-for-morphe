/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.videofit

import app.morphe.patcher.Fingerprint
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/interaction/VideoFit;"

/** The whole `com.ss.android.ugc.aweme.videoadaption` package kept its names, this one included. */
private const val RESULT = "Lcom/ss/android/ugc/aweme/videoadaption/adaptionparams/VideoAdaptionResult;"

/**
 * The story cell's way of putting the chosen size on a view: the result writes itself. It reads
 * its own width and height, writes them into the view's layout parameters and then applies the
 * offsets that go with a crop, so changing its fields before it runs changes all three.
 */
private object SaveAdaptionResultFingerprint : Fingerprint(
    definingClass = RESULT,
    name = "saveResultInner",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
)

/**
 * The feed cell's way, which is the one the For You feed takes. The cell's adaption component
 * hands the result and the video view to a static helper that writes the size and the offsets
 * into the view, and to a second one that says whether the view already matches the result.
 * Both live in a file facade R8 names per build, so each is found by its parameter list, which
 * names the result class and is carried by one method in the app on every build seen.
 */
private object ApplyAdaptionResultFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/view/View;", RESULT, "Lkotlin/jvm/functions/Function0;"),
    custom = { method, _ -> method.isAdaptionHelper(3) },
)

private object AdaptionAppliedFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/view/View;", RESULT),
    custom = { method, _ -> method.isAdaptionHelper(2) },
)

/**
 * Static, exactly these parameters, and a body that reads the result's size. A fingerprint
 * matches a parameter list by prefix, so the exact types, the count and the reads are what hold
 * the shape to the helpers, and the patch counts every method this accepts.
 */
private fun Method.isAdaptionHelper(parameters: Int): Boolean {
    if (!AccessFlags.STATIC.isSet(accessFlags)) return false
    if (parameterTypes.size != parameters) return false
    val types = parameterTypes.map(CharSequence::toString)
    if (types[0] != "Landroid/view/View;" || types[1] != RESULT) return false
    if (parameters == 3 && types[2] != "Lkotlin/jvm/functions/Function0;") return false
    val calls = implementation?.instructions
        ?.mapNotNull { it.getReference<MethodReference>() }
        ?.filter { it.definingClass == RESULT }
        ?.map { it.name }
        ?.toSet() ?: return false
    return "getWidth" in calls && "getHeight" in calls
}

@Suppress("unused")
val videoFitPatch = bytecodePatch(
    name = "Fit the video to the screen",
    description = "Shows the whole video instead of cutting off its sides on folding phones, " +
        "squarer screens and split screen. A second switch does the opposite and fills the " +
        "screen. Starts off. Turn it on in Metra settings > App behavior.",
) {
    category("Playback")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        // Every check comes before the first write. The patcher does not take a failed patch's
        // writes back out, so a check that failed after the feed helpers were rewritten would
        // leave them rewritten in an APK whose report says the patch did not apply.
        val applier = ApplyAdaptionResultFingerprint.method
        val checker = AdaptionAppliedFingerprint.method
        val story = SaveAdaptionResultFingerprint.method
        val settingsStatus = SettingsStatusLoadFingerprint.method
        val resultClass = SaveAdaptionResultFingerprint.originalClassDef

        // What the extension reflects on, refused here rather than as a silent no-op in the feed:
        // the two size getters, the operator the copy carries across, and the copy a data class
        // generates, with the two sizes first and the two offsets after them.
        for (getter in listOf("getWidth", "getHeight")) {
            check(resultClass.methods.any { it.name == getter && it.parameterTypes.isEmpty() && it.returnType == "I" }) {
                "Fit video to the screen: VideoAdaptionResult has no $getter()I."
            }
        }
        val operator = checkNotNull(
            resultClass.methods.firstOrNull { it.name == "getResultOperator" && it.parameterTypes.isEmpty() },
        ) {
            "Fit video to the screen: VideoAdaptionResult has no getResultOperator()."
        }
        // The copy takes the operator back as its last argument, and the extension hands it what
        // the getter answered. A copy whose last parameter is some other type would throw on
        // every fitted video, and the throw is caught, which is the silent no-op refused here.
        val copy = listOf("I", "I", "Ljava/lang/Float;", "Ljava/lang/Float;", operator.returnType)
        check(resultClass.methods.any { it.name == "copy" && it.parameterTypes.map(CharSequence::toString) == copy }) {
            "Fit video to the screen: VideoAdaptionResult has no copy(int, int, Float, Float, ${operator.returnType})."
        }

        // Each helper has to be the only method of its shape in the app. The patcher hands back
        // its first match, which would otherwise be whichever class it happened to walk first.
        var appliers = 0
        var checkers = 0
        classDefForEach { classDef ->
            for (method in classDef.methods) {
                if (method.returnType == "V" && method.isAdaptionHelper(3)) appliers++
                if (method.returnType == "Z" && method.isAdaptionHelper(2)) checkers++
            }
        }
        check(appliers == 1) {
            "Fit video to the screen: expected one static (View, VideoAdaptionResult, Function0)V " +
                "that applies the result, found $appliers."
        }
        check(checkers == 1) {
            "Fit video to the screen: expected one static (View, VideoAdaptionResult)Z that " +
                "compares the result, found $checkers."
        }

        // The story cell writes the result's own fields, from inside the result's own class.
        val registers = story.implementation!!.registerCount
        story.requireLocals("Fit video to the screen", 1)
        // The field writes and the offset calls name p0 in forms that reach v15 and no further.
        // A frame that puts p0 past it would assemble into nothing readable.
        check(registers <= 16) {
            "Fit video to the screen: saveResultInner holds $registers registers, and the " +
                "plain invoke that hands the result over cannot name one past v15."
        }
        // The fields are written by name, and smali resolves a field it cannot find at run
        // time rather than here, as a NoSuchFieldError in the feed. Refuse that now.
        for ((name, type) in listOf(
            "width" to "I", "height" to "I",
            "translateX" to "Ljava/lang/Float;", "translateY" to "Ljava/lang/Float;",
        )) {
            check(resultClass.fields.any { it.name == name && it.type == type }) {
                "Fit video to the screen: VideoAdaptionResult has no field $name:$type."
            }
        }

        // The feed cell. p0 is the view and p1 the result, in a static method; the result is
        // swapped for the fitted copy before the helper reads it, and cast back to what the
        // signature says it is so the reads that follow verify.
        for (helper in listOf(applier, checker)) {
            helper.addInstructions(
                0,
                """
                    invoke-static/range { p0 .. p1 }, ${EXTENSION}->fitted(Landroid/view/View;Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object p1
                    check-cast p1, $RESULT
                """,
            )
        }

        // The story cell, which sizes the view from inside the result. The result's own class,
        // so an iput on its final fields is something the runtime verifier allows. Nothing
        // outside it can write them, which is why the size is changed here and not in the
        // extension.
        story.apply {
            val result = definingClass
            // p0 is the result, p1 the video view. The width is asked for first because that is
            // where the fit is decided; the height that goes with it and the offsets are then
            // read back rather than worked out again from a width already changed. A negative
            // width is the extension saying leave it, and every write below is skipped: the
            // switch off, a video that fits, or a result it could not read all leave TikTok's
            // own numbers exactly as they were.
            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p0 .. p1 }, ${EXTENSION}->fitWidthFor(Ljava/lang/Object;Landroid/view/View;)I
                    move-result v0
                    if-ltz v0, :morphe_fit_leave
                    iput v0, p0, $result->width:I
                    invoke-static/range { p0 .. p0 }, ${EXTENSION}->fittedHeightFor(Ljava/lang/Object;)I
                    move-result v0
                    iput v0, p0, $result->height:I
                    iget-object v0, p0, $result->translateX:Ljava/lang/Float;
                    invoke-static { p0, v0 }, ${EXTENSION}->fittedTranslation(Ljava/lang/Object;Ljava/lang/Float;)Ljava/lang/Float;
                    move-result-object v0
                    iput-object v0, p0, $result->translateX:Ljava/lang/Float;
                    iget-object v0, p0, $result->translateY:Ljava/lang/Float;
                    invoke-static { p0, v0 }, ${EXTENSION}->fittedTranslation(Ljava/lang/Object;Ljava/lang/Float;)Ljava/lang/Float;
                    move-result-object v0
                    iput-object v0, p0, $result->translateY:Ljava/lang/Float;
                """,
                ExternalLabel("morphe_fit_leave", getInstruction(0)),
            )
        }

        settingsStatus.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableVideoFit()V",
        )
    }
}
