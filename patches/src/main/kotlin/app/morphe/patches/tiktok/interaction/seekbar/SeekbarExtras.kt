/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.seekbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.popups.writes
import app.morphe.patches.tiktok.shared.callThroughLocals
import app.morphe.patches.tiktok.shared.objectIn
import app.morphe.patches.tiktok.shared.valueIn
import app.morphe.patches.tiktok.shared.wideIn
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import app.morphe.util.addInstructions
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * The time beside the progress bar and the bigger handle (#90), both part of Show the progress bar.
 *
 * TikTok's bar is a LinearLayout (the class whose show-type setter the patch already rewrites)
 * holding one frame with a SeekBar subclass in it. The bar's progress is a percent from 0 to 100,
 * which its (FZ)V progress method stores and hands the SeekBar as an int out of 10000. A separate
 * (I)V style method sizes the SeekBar's line and handle for each show type: 0 at rest, 1 paused,
 * 2 a special layout, 100 to 102 while dragging. The percent comes from a progress event that
 * PlayerController builds as the player reports, carrying the percent, the position in
 * milliseconds and the video; the feed's controller, the detail page's and the others read it and
 * call the bar's progress method with that percent.
 */

private const val PATCH = "Show the progress bar"
internal const val SEEKBAR_TIME = "Lapp/morphe/extension/tiktok/seekbar/SeekbarTime;"
internal const val SEEKBAR_HANDLE = "Lapp/morphe/extension/tiktok/seekbar/SeekbarHandle;"
private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val FLOAT = "Ljava/lang/Float;"
private const val INTEGER = "Ljava/lang/Integer;"
private const val LINEAR_LAYOUT = "Landroid/widget/LinearLayout;"

/**
 * PlayerController's progress callback, one of the methods TikTok did not rename. It runs as the
 * player reports where it is, and builds the progress event the bar's controllers read.
 */
internal object PlayProgressFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/controller/PlayerController;",
    name = "onPlayProgressChange",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "J", "J"),
)

/** A method's or a call's parameter types as descriptors. A Method is a MethodReference too. */
private val MethodReference.params get() = parameterTypes.map(CharSequence::toString)

private fun Method.calls(): List<MethodReference> =
    implementation?.instructions?.mapNotNull { it.getReference<MethodReference>() }.orEmpty()

/**
 * The progress event: the one constructor the callback calls that takes the percent, the
 * position and the video first. The buffering event has the same constructor and is built in
 * another callback, which is why the class is found from here rather than by its shape alone.
 */
internal fun Method.progressEvent(): MethodReference {
    val built = implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode == Opcode.INVOKE_DIRECT || it.opcode == Opcode.INVOKE_DIRECT_RANGE }
        .mapNotNull { it.getReference<MethodReference>() }
        .filter { it.name == "<init>" && it.returnType == "V" && it.params.take(3) == listOf("F", "J", AWEME) }
        .distinctBy { "${it.definingClass}${it.params}" }
    return built.singleOrNull() ?: throw PatchException(
        "$PATCH: $definingClass->$name builds ${built.size} progress events, expected one: " +
            built.map { it.definingClass },
    )
}

/**
 * The bar's progress method: the same-class (FZ)V method its setProgress(F) hands the percent
 * to, the one every controller reaches, directly or through that setter.
 */
internal fun barProgressOf(barType: String, methods: Iterable<Method>): MethodReference {
    val setter = methods.singleOrNull { it.name == "setProgress" && it.params == listOf("F") && it.returnType == "V" }
        ?: throw PatchException("$PATCH: $barType has no setProgress(F)")
    val targets = setter.calls()
        .filter { it.definingClass == barType && it.params == listOf("F", "Z") && it.returnType == "V" }
        .distinctBy { it.name }
    return targets.singleOrNull() ?: throw PatchException(
        "$PATCH: $barType setProgress(F) hands its percent to ${targets.size} (FZ)V methods, expected one",
    )
}

/** The bar's style method: the same-class (I)V method the show-type setter calls with each style. */
internal fun Method.barStyle(): MethodReference {
    val styles = calls()
        .filter { it.definingClass == definingClass && it.name != name && it.params == listOf("I") && it.returnType == "V" }
        .distinctBy { it.name }
    return styles.singleOrNull() ?: throw PatchException(
        "$PATCH: $definingClass->$name calls ${styles.size} (I)V methods of its class, expected one style method",
    )
}

/** One size the style method passes to the seek bar, and the extension call that may raise it. */
internal data class StyleArgument(val index: Int, val register: Int, val call: String)

/** The seek bar's line setter: progress and background colors, height, radius, secondary color, offset, animate. */
private val LINE_SHAPE = listOf(INTEGER, INTEGER, FLOAT, FLOAT, INTEGER, "F", "Z")

/**
 * The sizes the style method hands the seek bar, found by the shape of each setter:
 * - the line setter, an instance call on the seek bar, whose third argument is the line height;
 * - the chapter marks setter, static, taking the seek bar and then the same height;
 * - the handle setter, static, taking the seek bar, then width, height and corner radius.
 * Each has to be there exactly once.
 */
internal fun Method.styleArguments(): List<StyleArgument> {
    val instructions = implementation?.instructions?.toList()
        ?: throw PatchException("$PATCH: $definingClass->$name has no implementation")

    fun sites(what: String, static: Boolean, shape: (MethodReference) -> Boolean): List<Pair<Int, MethodReference>> {
        val opcodes = if (static) setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
        else setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)
        val found = instructions.withIndex().mapNotNull { (index, instruction) ->
            if (instruction.opcode !in opcodes) return@mapNotNull null
            val call = instruction.getReference<MethodReference>() ?: return@mapNotNull null
            if (call.returnType == "V" && shape(call)) index to call else null
        }
        if (found.size != 1) {
            throw PatchException("$PATCH: $definingClass->$name calls the seek bar's $what ${found.size} times, expected once")
        }
        return found
    }

    val line = sites("line setter", static = false) { it.params == LINE_SHAPE }.single()
    val seekBar = line.second.definingClass
    val marks = sites("chapter marks setter", static = true) {
        it.definingClass == seekBar &&
            it.params == listOf(seekBar, FLOAT, FLOAT, "F", FLOAT, FLOAT, FLOAT, INTEGER, "Z", "I")
    }.single()
    val handle = sites("handle setter", static = true) {
        it.definingClass == seekBar && it.params == listOf(seekBar, FLOAT, FLOAT, FLOAT, INTEGER, "F", "Z", "I")
    }.single()

    fun argument(site: Pair<Int, MethodReference>, static: Boolean, parameter: Int, call: String) =
        StyleArgument(site.first, instructions[site.first].argumentRegister(site.second, static, parameter), call)

    return listOf(
        argument(line, static = false, parameter = 2, call = "lineHeight"),
        argument(marks, static = true, parameter = 1, call = "lineHeight"),
        argument(handle, static = true, parameter = 1, call = "handleSide"),
        argument(handle, static = true, parameter = 2, call = "handleSide"),
        argument(handle, static = true, parameter = 3, call = "handleCorner"),
    )
}

/** The register an invoke passes as [parameter] of [call], the receiver of an instance call not counted. */
internal fun Instruction.argumentRegister(call: MethodReference, static: Boolean, parameter: Int): Int {
    val registers = when (this) {
        is RegisterRangeInstruction -> List(registerCount) { startRegister + it }
        is FiveRegisterInstruction ->
            listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> throw PatchException("$PATCH: ${call.name} is not called by an invoke")
    }
    var at = if (static) 0 else 1
    call.params.take(parameter).forEach { at += if (it == "J" || it == "D") 2 else 1 }
    return registers[at]
}

/**
 * Whether a branch, a switch or a handler goes straight to the instruction at [index]. Code put
 * in front of that instruction would be skipped on that path, so the hooks refuse such a spot.
 */
internal fun Method.jumpedTo(index: Int): Boolean {
    val flow = ControlFlow.of(this)
    return flow.normal.withIndex().any { (from, next) -> from + 1 != index && index in next } ||
        flow.exceptional.any { index in it }
}

/**
 * Whether the event's constructor leaves its percent, position and video (p1 to p4) as they came
 * in, so they still hold them at its return.
 */
internal fun Method.keepsEventArguments(): Boolean {
    val code = implementation ?: return false
    val parameterRegisters = 1 + parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
    val self = code.registerCount - parameterRegisters
    val kept = (self + 1)..(self + 4)
    return code.instructions.none { instruction -> kept.any { instruction.writes(it) } }
}

/*
 * Each hook below checks its method and returns what it would add, adding nothing yet, so the
 * patch can check every hook an extra needs before any of them goes in.
 */

/** Records each progress event at the end of its constructor: the percent, the position and the video. */
internal fun MutableMethod.progressTickHook(): () -> Unit {
    val returns = implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }
    if (returns.size != 1) {
        throw PatchException("$PATCH: the progress event's constructor returns in ${returns.size} places, expected one")
    }
    if (jumpedTo(returns.single().index)) {
        throw PatchException("$PATCH: a branch in the progress event's constructor goes straight to its return")
    }
    if (!keepsEventArguments()) {
        throw PatchException("$PATCH: the progress event's constructor reuses its percent, position or video registers")
    }
    // p1 the percent, p2 and p3 the wide position, p4 the video, as progressEvent() requires.
    val index = returns.single().index
    val call = callThroughLocals(
        PATCH,
        "invoke-static",
        "$SEEKBAR_TIME->onPlayTick(FJ$AWEME)V",
        valueIn("p1"),
        wideIn("p2"),
        objectIn("p4"),
    )
    return { addInstructions(index, call) }
}

/** Hands the time the percent the bar is about to draw, before the bar draws it. */
internal fun MutableMethod.barProgressHook(): () -> Unit {
    val call = callThroughLocals(PATCH, "invoke-static", "$SEEKBAR_TIME->onBarProgress(${LINEAR_LAYOUT}F)V", objectIn("p0"), valueIn("p1"))
    return { addInstructions(0, call) }
}

/**
 * Passes each size the style method hands the seek bar through the bigger handle on its way in,
 * and tells both features which style is starting. Every size register is read by its setter and
 * by nothing after it, which is checked here, so raising it changes that call and no other.
 */
internal fun MutableMethod.barStyleHook(): () -> Unit {
    val arguments = styleArguments()
    val liveness = RegisterLiveness.of(this)
    arguments.forEach { argument ->
        if (argument.register in liveness.liveInto(argument.index + 1)) {
            throw PatchException(
                "$PATCH: v${argument.register}, the ${argument.call} argument in $definingClass->$name, " +
                    "is read again after its setter, so it can't be raised there",
            )
        }
        if (jumpedTo(argument.index)) {
            throw PatchException("$PATCH: a branch in $definingClass->$name goes straight to the ${argument.call} setter")
        }
    }
    val time = callThroughLocals(PATCH, "invoke-static", "$SEEKBAR_TIME->onBarStyle(${LINEAR_LAYOUT}I)V", objectIn("p0"), valueIn("p1"))
    val handle = callThroughLocals(PATCH, "invoke-static", "$SEEKBAR_HANDLE->beginStyle(${LINEAR_LAYOUT}I)V", objectIn("p0"), valueIn("p1"))
    return {
        // Last call first, so each insertion leaves the indices of the earlier calls where they were.
        arguments.sortedByDescending { it.index }.forEach { argument ->
            val register = argument.register
            addInstructions(
                argument.index,
                """
                    invoke-static/range {v$register .. v$register}, $SEEKBAR_HANDLE->${argument.call}($FLOAT)$FLOAT
                    move-result-object v$register
                """,
            )
        }
        addInstructions(0, time)
        addInstructions(0, handle)
    }
}
