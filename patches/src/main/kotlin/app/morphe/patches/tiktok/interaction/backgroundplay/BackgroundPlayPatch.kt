/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.backgroundplay

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.cloneMutable
import app.morphe.patcher.util.getMutableMethod
import app.morphe.patcher.util.getReference
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/misc/BackgroundPlay;"
internal const val GATE_KEY = "background_play_enable"
internal const val REMEMBERED_KEY = "long_term_bg_play_enable"
internal const val KEVA = "Lcom/bytedance/keva/Keva;"

/**
 * The lazy that reads TikTok's background play mode, once a process: 0 off, 1 the long-press
 * menu turns it on for one video, 2 the menu turns it on for good. Every reader on 47.0.3, 47.1.3
 * and 47.1.4 (the menu action, the scene checks and the player's keep-playing controller) goes
 * through the one static getter that holds this lazy, so its value is the one place to decide.
 */
internal object BackgroundPlayGateReadFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(),
    strings = listOf(GATE_KEY).map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * The reads of the switch the menu leaves on in mode 2, remembered in TikTok's
 * `background_play_repo` store: the check that builds the keep-playing controller, the per-video
 * check, and a diagnostic line. The menu action writes the switch and never reads it.
 */
internal object BackgroundPlayRememberedReadFingerprint : Fingerprint(
    strings = listOf(REMEMBERED_KEY).map { string(it, StringComparisonType.CONTAINS) },
    filters = listOf(methodCall(definingClass = KEVA, name = "getBoolean")),
)

internal const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
internal const val IS_PHOTO_MODE = "isPhotoMode"

/**
 * TikTok's check of whether a video may play on in the background. The page it was opened from
 * (its event type, the second parameter) must be on TikTok's list, which has the For You and
 * Following feeds, search and other people's profiles but not your own, and the post must not
 * be an ad, a LIVE, paid content or a photo post. 47.0.3 makes the post checks here; 47.1.3 and
 * 47.1.4 call a helper for them.
 */
internal object BackgroundPlaySceneCheckFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(AWEME, "Ljava/lang/String;", "Ljava/lang/String;"),
    strings = listOf("search_result", "video").map { string(it, StringComparisonType.CONTAINS) },
    filters = listOf(methodCall(definingClass = "Ljava/util/Set;", name = "contains")),
)

internal const val AUDIO_MANAGER = "Landroid/media/AudioManager;"
internal const val FOCUS_LISTENER = "Landroid/media/AudioManager\$OnAudioFocusChangeListener;"

/**
 * TikTok's short claim on the sound for a page that resumes: transient audio focus, taken with
 * a listener that does nothing. The feed's page resume calls it, and so do Explore and a few
 * other pages.
 */
internal object PageAudioFocusFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("audio").map { string(it, StringComparisonType.CONTAINS) },
    filters = listOf(methodCall(parameters = listOf(AUDIO_MANAGER, FOCUS_LISTENER, "I", "I"), returnType = "I")),
)

internal const val PLAY_LISTENER = "Lcom/ss/android/ugc/aweme/player/sdk/api/OnUIPlayListener;"

/** The reason TikTok's media notification hands its background session's Play. */
internal const val NOTIFICATION_PLAY = 2

/**
 * The end of a video in TikTok's background session (#99): the session's own player listener.
 * When the session holds one video it rewinds it and pauses the session, then drops the session's
 * pending work on its handler and sets the notification to paused. On 47.1.4 the other player
 * listeners that rewind at the end touch no handler.
 */
internal object BackgroundPlayEndFingerprint : Fingerprint(
    name = "onPlayCompleted",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        methodCall(name = "seek", parameters = listOf("F"), returnType = "V"),
        methodCall(definingClass = "Landroid/os/Handler;", name = "removeCallbacksAndMessages"),
    ),
    custom = { _, classDef -> PLAY_LISTENER in classDef.interfaces },
)

/** What the replay needs from the background session's end. */
internal class BackgroundEnd(
    /** Index of the move-result that keeps the answer to whether the session holds one video. */
    val singleResult: Int,
    /** The register that answer lands in. */
    val singleRegister: Int,
    /** The listener's field holding its session. */
    val session: FieldReference,
    /** The session's Play, the one its notification presses. */
    val play: MethodReference,
)

/**
 * Finds the pieces of [end] the replay uses, or says which one moved: the check whether the
 * session holds one video, kept and branched on before the rewind; the session the listener
 * holds, which is the class its pause is called on; and that session's Play, its one other
 * method taking a reason that resumes the player. The bridge reaches them from the extension's
 * package, so they have to be public.
 */
internal fun resolveBackgroundEnd(end: Method, classOf: (String) -> ClassDef?): BackgroundEnd {
    fun fail(why: String): Nothing = throw PatchException("Keep playing in the background: $why")
    fun Instruction.call() = getReference<MethodReference>()
    fun MethodReference.takes() = parameterTypes.map(CharSequence::toString)

    val instructions = end.implementation?.instructions?.toList() ?: fail("the background session's end has no code.")
    val check = instructions.indexOfFirst {
        it.opcode == Opcode.INVOKE_STATIC && it.call()?.let { target -> target.returnType == "Z" && target.parameterTypes.isEmpty() } == true
    }
    val result = instructions.getOrNull(check + 1) as? OneRegisterInstruction
    val branch = instructions.getOrNull(check + 2) as? OneRegisterInstruction
    if (check < 0 || result == null || branch == null || result.opcode != Opcode.MOVE_RESULT ||
        branch.opcode != Opcode.IF_EQZ || branch.registerA != result.registerA
    ) {
        fail("the background session's end no longer checks whether it holds one video before it rewinds.")
    }
    val seek = (check + 3 until instructions.size).firstOrNull { at ->
        instructions[at].call()?.let { it.name == "seek" && it.takes() == listOf("F") } == true
    } ?: fail("the background session's end no longer rewinds the video.")
    val pause = (seek + 1 until instructions.size).firstOrNull { at ->
        instructions[at].opcode == Opcode.INVOKE_VIRTUAL &&
            instructions[at].call()?.let { it.returnType == "V" && it.takes() == listOf("I") } == true
    }?.let { instructions[it].call()!! } ?: fail("the background session's end no longer pauses its session.")

    val listener = classOf(end.definingClass) ?: fail("${end.definingClass} is missing.")
    val sessions = listener.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == pause.definingClass }
    val session = sessions.singleOrNull()
        ?: fail("${end.definingClass} holds ${sessions.size} sessions of ${pause.definingClass}, expected 1.")
    val sessionClass = classOf(pause.definingClass) ?: fail("${pause.definingClass} is missing.")
    val plays = sessionClass.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.name != pause.name && method.returnType == "V" &&
            method.takes() == listOf("I") && method.implementation?.instructions?.any { instruction ->
                instruction.call()?.let { it.name == "resume" && it.parameterTypes.isEmpty() && it.returnType == "V" } == true
            } == true
    }
    val play = plays.singleOrNull()
        ?: fail("${pause.definingClass} has ${plays.size} methods that resume its player for a reason, expected 1.")
    // The extension reaches all four from its own package.
    if (listOf(listener.accessFlags, session.accessFlags, sessionClass.accessFlags, play.accessFlags).any { !AccessFlags.PUBLIC.isSet(it) }) {
        fail("the listener, its session or the session's Play is no longer public.")
    }
    return BackgroundEnd(check + 1, result.registerA, session, play)
}

/** The string a const-string loads, or null. */
private fun Instruction.string(): String? =
    if (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) getReference<StringReference>()?.string else null

/** Index of the first `AwemeExtKt.isPhotoMode(aweme)` call, or -1. */
internal fun List<Instruction>.photoModeCall(): Int = indexOfFirst { instruction ->
    instruction.opcode == Opcode.INVOKE_STATIC && instruction.getReference<MethodReference>()?.let {
        it.name == IS_PHOTO_MODE && it.definingClass.endsWith("/AwemeExtKt;") && it.returnType == "Z" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(AWEME)
    } == true
}

/** Whether this is a static call taking just a post and answering yes or no. */
internal fun Instruction.isStaticAwemeCheck(): Boolean =
    opcode == Opcode.INVOKE_STATIC && getReference<MethodReference>()?.let {
        it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(AWEME)
    } == true

/**
 * Whether [index] is `Keva.getBoolean(key, default)` with [key] loaded into its key register by a
 * const-string just before it (TikTok's code loads it on the line before, or two before when the
 * default goes in between).
 */
internal fun List<Instruction>.readsKevaBoolean(index: Int, key: String): Boolean {
    val call = this[index]
    if (call.opcode != Opcode.INVOKE_VIRTUAL) return false
    val reference = call.getReference<MethodReference>() ?: return false
    if (reference.definingClass != KEVA || reference.name != "getBoolean" ||
        reference.parameterTypes.map(CharSequence::toString) != listOf("Ljava/lang/String;", "Z")
    ) {
        return false
    }
    val keyRegister = (call as FiveRegisterInstruction).registerD
    return (maxOf(0, index - 3) until index).any { at ->
        val load = this[at]
        load.string() == key && (load as OneRegisterInstruction).registerA == keyRegister
    }
}

@Suppress("unused")
val backgroundPlayPatch = bytecodePatch(
    name = "Keep playing in the background",
    description = "Keeps the video playing when you leave TikTok or turn the screen off, with " +
        "a notification to pause it. It can also start the video over when it ends. Handy for " +
        "music and talks. Starts off. Turn it on in Metra settings > App behavior, then restart TikTok.",
) {
    category("Playback")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableBackgroundPlay()V",
        )

        // The mode: the first int read after the key is the settings manager handing it over.
        BackgroundPlayGateReadFingerprint.method.apply {
            val instructions = implementation!!.instructions.toList()
            val key = instructions.indexOfFirst { it.string() == GATE_KEY }
            val read = (key + 1 until instructions.size).firstOrNull { at ->
                instructions[at].getReference<MethodReference>()?.returnType == "I"
            } ?: throw PatchException("Keep playing in the background: the gate read no longer asks for an int after its key.")
            val result = instructions.getOrNull(read + 1)
            if (result?.opcode != Opcode.MOVE_RESULT) {
                throw PatchException("Keep playing in the background: the gate's int is not kept after its read.")
            }
            val register = (result as OneRegisterInstruction).registerA
            addInstructions(
                read + 2,
                """
                    invoke-static { v$register }, $EXTENSION->mode(I)I
                    move-result v$register
                """,
            )
        }

        // Photo posts first: on 47.0.3 their check comes after the list lookup in the same method.
        val check = BackgroundPlaySceneCheckFingerprint.method
        val checkInstructions = check.implementation!!.instructions.toList()
        val photoCheck = if (checkInstructions.photoModeCall() >= 0) check else {
            checkInstructions.filter { it.isStaticAwemeCheck() }
                .map { it.getReference<MethodReference>()!!.getMutableMethod() }
                .firstOrNull { it.implementation!!.instructions.toList().photoModeCall() >= 0 }
                ?: throw PatchException("Keep playing in the background: the scene check no longer rules out photo posts where it's expected to.")
        }
        photoCheck.apply {
            val instructions = implementation!!.instructions.toList()
            val call = instructions.photoModeCall()
            val result = instructions.getOrNull(call + 1)
            if (result?.opcode != Opcode.MOVE_RESULT) {
                throw PatchException("Keep playing in the background: the photo post check is not kept.")
            }
            val register = (result as OneRegisterInstruction).registerA
            addInstructions(
                call + 2,
                """
                    invoke-static { v$register }, $EXTENSION->photoMode(Z)Z
                    move-result v$register
                """,
            )
        }

        // The page: the check's first Set.contains is its lookup of the event type in TikTok's list.
        check.apply {
            val instructions = implementation!!.instructions.toList()
            val lookup = instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_INTERFACE && it.getReference<MethodReference>()?.let { reference ->
                    reference.definingClass == "Ljava/util/Set;" && reference.name == "contains"
                } == true
            }
            val result = instructions.getOrNull(lookup + 1)
            if (result?.opcode != Opcode.MOVE_RESULT) {
                throw PatchException("Keep playing in the background: the scene check no longer keeps its list lookup.")
            }
            val scene = (instructions[lookup] as FiveRegisterInstruction).registerD
            val register = (result as OneRegisterInstruction).registerA
            if (scene == register) {
                throw PatchException("Keep playing in the background: the scene check's lookup overwrites the page it looked up.")
            }
            addInstructions(
                lookup + 2,
                """
                    invoke-static { v$register, v$scene }, $EXTENSION->scene(ZLjava/lang/String;)Z
                    move-result v$register
                """,
            )
        }

        // At a cold start TikTok holds the feed's page resume back until the feed's first page
        // loads. Leave before that and the page's claim on the sound lands in the background,
        // where TikTok's own background player takes it for another app and pauses.
        PageAudioFocusFingerprint.method.guardAtEntry(
            "Keep playing in the background",
            "invoke-static {}, $EXTENSION->skipsPageFocus()Z",
            "return-void",
        )

        // A video that ends in the background (#99). The extension gets the answer to whether the
        // session holds one video right where TikTok keeps it, and presses the session's Play
        // once TikTok has rewound and paused, the way the notification's Play does.
        val end = BackgroundPlayEndFingerprint.method
        val shape = resolveBackgroundEnd(end) { classDefByOrNull(it) }
        // The listener's own register: `this` and the one String after the locals.
        val listener = end.implementation!!.registerCount - 2
        if (shape.singleRegister > 15 || listener > 15) {
            throw PatchException("Keep playing in the background: the background session's end keeps its registers past v15.")
        }
        val extension = mutableClassDefBy(EXTENSION)
        val stub = extension.methods.filter { it.name == "replay" }
            .singleOrPatchException("Keep playing in the background: extension bridge replay")
        val bridge = stub.cloneMutable(additionalRegisters = 2)
        extension.methods.remove(stub)
        extension.methods.add(bridge)
        val session = shape.session
        bridge.addInstructions(
            0,
            """
                check-cast p0, ${end.definingClass}
                iget-object v0, p0, ${session.definingClass}->${session.name}:${session.type}
                const/4 v1, 0x${NOTIFICATION_PLAY.toString(16)}
                invoke-virtual { v0, v1 }, ${shape.play.definingClass}->${shape.play.name}(I)V
                return-void
            """,
        )
        end.addInstruction(
            shape.singleResult + 1,
            "invoke-static { v${shape.singleRegister}, v$listener }, $EXTENSION->onBackgroundEnd(ZLjava/lang/Object;)V",
        )

        var remembered = 0
        BackgroundPlayRememberedReadFingerprint.matchAll().forEach { match ->
            val method = match.method
            val instructions = method.implementation!!.instructions.toList()
            // Later reads first, so an insertion never moves one still to come.
            instructions.indices.filter { instructions.readsKevaBoolean(it, REMEMBERED_KEY) }.asReversed().forEach { read ->
                val result = instructions.getOrNull(read + 1)
                if (result?.opcode != Opcode.MOVE_RESULT) {
                    throw PatchException("Keep playing in the background: a remembered-switch read in ${method.definingClass} is not kept.")
                }
                val register = (result as OneRegisterInstruction).registerA
                method.addInstructions(
                    read + 2,
                    """
                        invoke-static { v$register }, $EXTENSION->remembered(Z)Z
                        move-result v$register
                    """,
                )
                remembered++
            }
        }
        // The controller check and the per-video check are the two that decide; fewer means a
        // read moved and background play would still follow TikTok's own switch.
        if (remembered < 2) {
            throw PatchException("Keep playing in the background: found $remembered reads of TikTok's remembered background play switch, expected at least 2.")
        }
        println("[Background play] Hooked the mode read, the scene and photo post checks, the page's claim on the sound, the end of a background video and $remembered reads of the remembered switch.")
    }
}
