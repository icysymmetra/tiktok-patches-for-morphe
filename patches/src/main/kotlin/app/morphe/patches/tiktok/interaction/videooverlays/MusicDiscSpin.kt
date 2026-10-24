/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val MUSIC_DISC_SPIN_DESCRIPTOR = "Lapp/morphe/extension/tiktok/feed/MusicDiscSpin;"

/** 1 or 3 holds the disc still, 2 or 3 stops the track name scrolling. 47.1.4's default is 3. */
internal const val MUSIC_ANIMATION_CLOSE_KEY = "music_animation_close_exp"

/** Above 0 lets the disc turn whatever the close setting says, for that many seconds a turn. */
internal const val MUSIC_COVER_ROTATION_DURATION_KEY = "video_music_cover_visual_opt_rotation_duration"

/** The app AB boolean that holds the cover's animation (and the track name's), default false. */
internal const val ANIMATION_OPT_KEY = "anim_opt"

/** The name the disc's animator registers its low end check under, and starts through. */
internal const val MUSIC_COVER_DEGRADE_KEY = "musicCoverDegrade"

/** What the For You cell hands its play animation under, next to the event the disc waits for. */
internal const val START_PLAY_ANIMATION_KEY = "startPlayAnimation"

/** The load scene strategy that switches the feed's play animation off while the phone is under heavy load. */
internal const val PLAY_ANIMATION_DOWNGRADE_ID = "feed_play_anim_downgrade"

internal const val MUSIC_COVER_ASSEM = "Lcom/ss/android/ugc/aweme/feed/assem/music/VideoMusicCoverAssem;"
internal const val MUSIC_COVER_VM = "Lcom/ss/android/ugc/aweme/feed/assem/music/VideoMusicCoverVM;"
internal const val VIDEO_VIEW_CELL = "Lcom/ss/android/ugc/aweme/feed/adapter/VideoViewCell;"

/** The app AB class's int getter as its callers write it: flags, default, key, sticky. */
private val AB_INT_READ = listOf("I", "I", "Ljava/lang/String;", "Z")

/** The app AB class's boolean getter as its callers write it: flags, key, sticky, default. */
private val AB_BOOLEAN_READ = listOf("I", "Ljava/lang/String;", "Z", "Z")

/**
 * The lazy value behind `music_animation_close_exp`: a no-argument lambda body that reads the key
 * through the app AB int getter and boxes it. Its class is renamed on every build (0Af6 on
 * 47.1.4), so the key, a name TikTok wrote, is the anchor. The ABMock table that lists the key
 * with its default is a void method with no boxing, which the shape keeps out.
 *
 * <p>On 47.1.4 the disc's turn starts only from VideoMusicCoverAssem's play subscriber, which
 * returns first when this reads 1 or 3 and the rotation duration below is not above 0 (#68). It
 * is the first of the holds on the way; the ones after it are below.
 */
internal object MusicAnimationCloseFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf(),
    strings = listOf(MUSIC_ANIMATION_CLOSE_KEY).map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> isBoxedAbIntRead(method, MUSIC_ANIMATION_CLOSE_KEY) },
)

/** The same for `video_music_cover_visual_opt_rotation_duration` (0AnS on 47.1.4). */
internal object MusicCoverRotationDurationFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf(),
    strings = listOf(MUSIC_COVER_ROTATION_DURATION_KEY).map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> isBoxedAbIntRead(method, MUSIC_COVER_ROTATION_DURATION_KEY) },
)

/**
 * A no-argument body that loads exactly [key], reads an int through the app AB getter and boxes
 * one int. The fingerprint's string is matched inside longer strings too, so the exact load is
 * what keeps a longer key of the same stem out.
 */
internal fun isBoxedAbIntRead(method: Method, key: String): Boolean {
    if (method.parameterTypes.isNotEmpty() || method.returnType != "Ljava/lang/Object;") return false
    val code = method.implementation?.instructions?.toList() ?: return false
    val loadsKey = code.any { it.getReference<StringReference>()?.string == key }
    val readsInt = code.any { instruction ->
        instruction.getReference<MethodReference>()?.let {
            it.returnType == "I" && it.parameterTypes.map(CharSequence::toString) == AB_INT_READ
        } == true
    }
    return loadsKey && readsInt && code.count { it.isIntBoxing() } == 1
}

private fun Instruction.isIntBoxing(): Boolean =
    (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) &&
        getReference<MethodReference>()?.let {
            it.definingClass == "Ljava/lang/Integer;" && it.name == "valueOf" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("I")
        } == true

/**
 * Hands the int the lazy value is about to box to [extensionMethod], a static `(I)I` on
 * MusicDiscSpin, and boxes its answer instead. The call goes in front of the boxing, on the
 * register the boxing reads, so TikTok's read and its default stay as they were and the answer
 * is what the lazy value keeps for the launch.
 */
internal fun MutableMethod.answerMusicDiscSetting(patch: String, extensionMethod: String) {
    val instructions = implementation?.instructions?.toList()
        ?: throw PatchException("$patch: $definingClass->$name has no implementation.")
    val boxing = instructions.withIndex().filter { it.value.isIntBoxing() }
    if (boxing.size != 1) {
        throw PatchException("$patch: $definingClass->$name boxes ${boxing.size} ints, expected one.")
    }
    val (index, instruction) = boxing.single()
    val register = when (instruction) {
        is FiveRegisterInstruction -> instruction.registerC
        is RegisterRangeInstruction -> instruction.startRegister
        else -> throw PatchException("$patch: $definingClass->$name boxes from no register it names.")
    }
    addInstructionsAtControlFlowLabel(index, answer(register, extensionMethod, "I"))
}

/** A static `(type)type` call on MusicDiscSpin with [register], and the answer back into it. */
private fun answer(register: Int, extensionMethod: String, type: String): String {
    val target = "$MUSIC_DISC_SPIN_DESCRIPTOR->$extensionMethod($type)$type"
    val call = if (register <= 15) {
        "invoke-static {v$register}, $target"
    } else {
        "invoke-static/range {v$register .. v$register}, $target"
    }
    return "$call\nmove-result v$register"
}

/*
 * Past the close setting, the disc's turn on For You (47.1.4) still has to get through these, in
 * this order. None of them holds it with TikTok's own defaults on a Samsung phone that is not in
 * TikTok's power saving mode, so each is a value the server can send or a load the phone is under:
 *
 * 1. VideoViewCell's play hook (the For You holder's base) sends the play animation event only
 *    when TikTok's power mode is off, the high frame rate hold below is false, and the load
 *    scene strategy feed_play_anim_downgrade has not switched the play animation off (it does
 *    at its SEVERE load level).
 * 2. The play subscriber returns when the photo has no music, when it already started, when
 *    VideoMusicCoverVM's hold (anim_opt, or a tablet layout) is true, in power mode, and when the
 *    high frame rate hold is true. That hold is one static boolean the power module sets at
 *    launch from frame_rate_high_type: 4 always, 2 when any display runs above 60 Hz (the S22's
 *    120 Hz panel), 1 from its own frame check, and 0 (the default) only on a Google phone above
 *    60 Hz while frame_rate_high_device is on.
 * 3. The runnable it posts builds the animator (8 seconds a turn unless the duration says
 *    otherwise) and starts it through the check registered under musicCoverDegrade, whose
 *    enable() is false under some of TikTok's new user strategies, when ui_down_grade_strategy
 *    has its 1 bit, and when pythia_low_device_opt is open and the on device performance score
 *    is missing or outside its range.
 */

/**
 * VideoMusicCoverVM's hold on the cover's animation: true when the app AB boolean `anim_opt`
 * (default false) is on, or the tablet layout asks for it. The play subscriber returns before the
 * disc starts while it is true; the pause and stop subscribers only act while it is false, so a
 * false answer keeps those working. The class is named by TikTok, and the key is the anchor
 * inside it (VideoMusicTitleVM reads the same key for the track name and is left alone).
 */
internal object MusicCoverAnimationHoldFingerprint : Fingerprint(
    definingClass = MUSIC_COVER_VM,
    returnType = "Z",
    parameters = listOf(),
    strings = listOf(ANIMATION_OPT_KEY).map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> isCoverAnimationHold(method) },
)

internal fun isCoverAnimationHold(method: Method): Boolean {
    if (method.definingClass != MUSIC_COVER_VM || method.returnType != "Z" || method.parameterTypes.isNotEmpty()) return false
    val code = method.implementation?.instructions?.toList() ?: return false
    return code.any { it.getReference<StringReference>()?.string == ANIMATION_OPT_KEY } &&
        code.any { instruction ->
            instruction.getReference<MethodReference>()?.let {
                it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == AB_BOOLEAN_READ
            } == true
        }
}

/**
 * VideoMusicCoverAssem's play subscriber (09bG on 47.1.4): handed the cover assem, it asks the
 * cover view model's hold and then reads one static boolean, the high frame rate hold, before it
 * lets the disc start. The pause and stop subscribers ask the same hold and read no static
 * boolean, which is what keeps them out.
 */
internal object MusicCoverPlaySubscriberFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
    custom = { method, _ -> highFrameRateHoldIndex(method) >= 0 },
)

/**
 * Where a method that casts to the cover assem reads its one static boolean after asking a
 * no-argument boolean of the cover view model, or -1.
 */
internal fun highFrameRateHoldIndex(method: Method): Int {
    val code = method.implementation?.instructions?.toList() ?: return -1
    if (code.none { it.opcode == Opcode.CHECK_CAST && it.getReference<TypeReference>()?.type == MUSIC_COVER_ASSEM }) return -1
    val asks = code.indexOfFirst { instruction ->
        instruction.opcode == Opcode.INVOKE_VIRTUAL && instruction.getReference<MethodReference>()?.let {
            it.definingClass == MUSIC_COVER_VM && it.returnType == "Z" && it.parameterTypes.isEmpty()
        } == true
    }
    if (asks < 0) return -1
    return code.withIndex().filter { it.index > asks && it.value.opcode == Opcode.SGET_BOOLEAN }
        .singleOrNull()?.index ?: -1
}

/**
 * VideoViewCell's play hook, the one method of the cell that hands over `startPlayAnimation`.
 * It reads the same high frame rate hold before it sends the event the play subscriber waits for.
 */
internal object PlayAnimationTriggerFingerprint : Fingerprint(
    definingClass = VIDEO_VIEW_CELL,
    returnType = "V",
    parameters = listOf(),
    strings = listOf(START_PLAY_ANIMATION_KEY).map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * The play animation's load scene strategy, by the id TikTok's load scenes know it under. Its
 * class also holds the method that applies a level, which sets the one static boolean the play
 * hook reads (true at DISABLE, its SEVERE level).
 */
internal object PlayAnimationDowngradeFingerprint : Fingerprint(
    name = "getStrategyId",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf(PLAY_ANIMATION_DOWNGRADE_ID).map { string(it, StringComparisonType.CONTAINS) },
)

/** Where [method] writes its one static boolean, or -1. On the strategy's class, the level's apply. */
internal fun playAnimationSwitchIndex(method: Method): Int {
    val code = method.implementation?.instructions?.toList() ?: return -1
    return code.withIndex().filter { it.value.opcode == Opcode.SPUT_BOOLEAN }.singleOrNull()?.index ?: -1
}

/** Where [method] reads the static boolean [field], when it reads it exactly once, or -1. */
internal fun staticBooleanReadIndex(method: Method, field: FieldReference): Int {
    val code = method.implementation?.instructions?.toList() ?: return -1
    return code.withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.SGET_BOOLEAN && instruction.getReference<FieldReference>()?.let {
            it.definingClass == field.definingClass && it.name == field.name && it.type == field.type
        } == true
    }.singleOrNull()?.index ?: -1
}

/**
 * VideoMusicCoverAssem's onViewCreated, which registers the disc animator's low end check under
 * `musicCoverDegrade`. The runnable that builds the animator starts it through that check.
 */
internal object MusicCoverDegradeRegistrationFingerprint : Fingerprint(
    definingClass = MUSIC_COVER_ASSEM,
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    strings = listOf(MUSIC_COVER_DEGRADE_KEY).map { string(it, StringComparisonType.CONTAINS) },
)

/** The check onViewCreated registers: the last object it builds before it loads the name. */
internal fun degradeCheckType(method: Method): String? {
    val code = method.implementation?.instructions?.toList() ?: return null
    val key = code.indexOfFirst { it.getReference<StringReference>()?.string == MUSIC_COVER_DEGRADE_KEY }
    if (key < 0) return null
    return code.subList(0, key).lastOrNull { it.opcode == Opcode.NEW_INSTANCE }?.getReference<TypeReference>()?.type
}

/** The check's own answer, which the animator starts on when it is true. */
internal fun isDegradeEnable(method: Method) =
    method.name == "enable" && method.returnType == "Z" && method.parameterTypes.isEmpty() &&
        method.implementation != null

/**
 * Hands every boolean [this] returns to [extensionMethod], a static `(Z)Z` on MusicDiscSpin, and
 * returns its answer. The call goes on the return's own label, so every branch that lands there
 * is answered.
 */
internal fun MutableMethod.answerMusicDiscReturn(patch: String, extensionMethod: String) {
    val returns = implementation?.instructions?.withIndex()
        ?.filter { it.value.opcode == Opcode.RETURN }?.map { it.index }.orEmpty()
    if (returns.isEmpty()) throw PatchException("$patch: $definingClass->$name returns no boolean to answer.")
    for (index in returns.asReversed()) {
        val register = (implementation!!.instructions[index] as OneRegisterInstruction).registerA
        addInstructionsAtControlFlowLabel(index, answer(register, extensionMethod, "Z"))
    }
}

/**
 * Hands the static boolean read at [index] to [extensionMethod], a static `(Z)Z` on MusicDiscSpin,
 * right after it lands and before anything tests it.
 */
internal fun MutableMethod.answerMusicDiscRead(patch: String, index: Int, extensionMethod: String) {
    val read = implementation?.instructions?.getOrNull(index)
    if (read == null || read.opcode != Opcode.SGET_BOOLEAN) {
        throw PatchException("$patch: $definingClass->$name reads no static boolean at $index.")
    }
    addInstructions(index + 1, answer((read as OneRegisterInstruction).registerA, extensionMethod, "Z"))
}

/**
 * Every hold on the disc's way that the two switches answer, found before any of them changes,
 * so a build missing one is left out whole.
 */
internal class MusicDiscHooks(
    private val close: MutableMethod,
    private val duration: MutableMethod,
    private val hold: MutableMethod,
    private val subscriber: MutableMethod,
    private val trigger: MutableMethod,
    private val degrade: MutableMethod,
    playAnimationSwitch: Method,
) {
    private val frameRateHold: FieldReference
    private val playAnimationOff: FieldReference

    init {
        val read = highFrameRateHoldIndex(subscriber)
        if (read < 0) throw PatchException("The play subscriber ${subscriber.definingClass} reads no high frame rate hold.")
        frameRateHold = subscriber.implementation!!.instructions[read].getReference<FieldReference>()!!
        if (staticBooleanReadIndex(trigger, frameRateHold) < 0) {
            throw PatchException("${trigger.definingClass}->${trigger.name} does not read the high frame rate hold once.")
        }
        val write = playAnimationSwitchIndex(playAnimationSwitch)
        if (write < 0) {
            throw PatchException("${playAnimationSwitch.definingClass}->${playAnimationSwitch.name} switches no play animation.")
        }
        playAnimationOff = playAnimationSwitch.implementation!!.instructions.elementAt(write).getReference<FieldReference>()!!
        if (staticBooleanReadIndex(trigger, playAnimationOff) < 0) {
            throw PatchException("${trigger.definingClass}->${trigger.name} does not read the play animation switch once.")
        }
    }

    fun apply(patch: String) {
        close.answerMusicDiscSetting(patch, "closeSetting")
        duration.answerMusicDiscSetting(patch, "rotationSeconds")
        hold.answerMusicDiscReturn(patch, "coverAnimationHeld")
        subscriber.answerMusicDiscRead(patch, highFrameRateHoldIndex(subscriber), "highFrameRateHeld")
        // Each index is found again after the hook before it, which moved what follows it.
        trigger.answerMusicDiscRead(patch, staticBooleanReadIndex(trigger, frameRateHold), "highFrameRateHeld")
        trigger.answerMusicDiscRead(patch, staticBooleanReadIndex(trigger, playAnimationOff), "playAnimationSwitchedOff")
        degrade.answerMusicDiscReturn(patch, "degradeAllows")
    }
}
