/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.blockauthor

import app.morphe.util.addInstructions
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.util.cloneMutable
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val HOLD = "Lapp/morphe/extension/tiktok/wellbeing/SessionPlaybackHold;"
internal const val PLAYER_CONTROLLER = "Lcom/ss/android/ugc/aweme/feed/controller/PlayerController;"
internal const val FEED_RECOMMEND_FRAGMENT = "Lcom/ss/android/ugc/aweme/feed/ui/FeedRecommendFragment;"
private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val KEY_EVENT = "Landroid/view/KeyEvent;"
private const val RESUME_LOG = "resumePlay aid:"

/** One call as TikTok makes it: the member and whether it goes through an interface. */
internal class NativeCall(val reference: MethodReference, val throughInterface: Boolean) {
    val invoke get() = if (throughInterface) "invoke-interface" else "invoke-virtual"
}

/**
 * The daily hold's view of TikTok's player: the video a PlayerController has on screen, the type
 * of its player manager, and that manager's pause and resume.
 *
 * <p>Every one of them is renamed by each build. The pause alone was `LIZ` on 46.2.3 and 46.8.3,
 * `LJJLIIIJILLIZJL` on 46.7.3 and `LJJLIIIJJI` on 47.0.3, where `LIZ` became a getter returning a
 * number, so the hold's call by name answered "paused" for a video that played on under the
 * panel. What stays put is where TikTok uses them itself:
 * - `PlayerController.pauseVideo()` asks itself for the current Aweme (its one no-argument call
 *   on itself returning one), fetches `getPlayerManager()`, and pauses (the one no-argument void
 *   call on that manager's type);
 * - `FeedRecommendFragment.onDispatchKeyEvent(KeyEvent)`, the For You feed's space key, toggles
 *   with that same pause and the resume, the other no-argument void call on the manager's type;
 * - the resume in the manager's implementation, the class the toggle fetches the manager from,
 *   logs "resumePlay aid:", which is checked so a reshuffled toggle can't hand over some other call.
 */
internal class NativePlaybackMembers(
    val awemeGetter: NativeCall,
    val manager: String,
    val pause: NativeCall,
    val resume: NativeCall,
    /** The manager's implementation, whose resume logs "resumePlay aid:". */
    val implementation: String,
)

private fun Method.calls(): List<Pair<Opcode, MethodReference>> =
    implementation?.instructions?.mapNotNull { instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        reference?.let { instruction.opcode to it }
    }.orEmpty()

private fun Method.hasString(value: String): Boolean =
    implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == value
    } == true

private fun Opcode.throughInterface() = this == Opcode.INVOKE_INTERFACE || this == Opcode.INVOKE_INTERFACE_RANGE

private fun List<Pair<Opcode, MethodReference>>.distinctCalls() = distinctBy { it.second.toString() }

/**
 * Reads the members off TikTok's own two users of them. [methodsOf] gives a class's methods, or
 * null when the class isn't there; the patch reads the app with it, and the fixture test the
 * APKs, so both run this very code.
 */
internal fun resolveNativePlaybackMembers(
    pauseVideo: Method,
    keyToggle: Method,
    methodsOf: (String) -> Iterable<Method>?,
): NativePlaybackMembers {
    val pauseCalls = pauseVideo.calls()
    val getter = pauseCalls.filter { (_, it) ->
        it.definingClass == PLAYER_CONTROLLER && it.parameterTypes.isEmpty() && it.returnType == AWEME
    }.distinctCalls().singleOrPatchException("Block author: pauseVideo's current-video getter")
    val manager = pauseCalls.map { it.second }.filter {
        it.definingClass == PLAYER_CONTROLLER && it.name == "getPlayerManager" && it.parameterTypes.isEmpty()
    }.distinctBy { it.toString() }.singleOrPatchException("Block author: pauseVideo's getPlayerManager").returnType
    fun managerCommands(calls: List<Pair<Opcode, MethodReference>>) = calls.filter { (_, it) ->
        it.definingClass == manager && it.parameterTypes.isEmpty() && it.returnType == "V"
    }.distinctCalls()
    val pause = managerCommands(pauseCalls).singleOrPatchException("Block author: pauseVideo's pause on $manager")
    val toggleCalls = keyToggle.calls()
    val toggle = managerCommands(toggleCalls)
    if (toggle.none { it.second.toString() == pause.second.toString() }) {
        throw PatchException(
            "Block author: the For You space-key toggle doesn't call pauseVideo's pause " +
                "${pause.second}, so it can't name the resume beside it.",
        )
    }
    val resume = toggle.filter { it.second.toString() != pause.second.toString() }
        .singleOrPatchException("Block author: the space-key toggle's resume on $manager")
    val implementation = toggleCalls.map { it.second }.filter {
        it.parameterTypes.isEmpty() && it.returnType == manager && it.definingClass != manager
    }.map { it.definingClass }.distinct()
        .singleOrPatchException("Block author: the class the space-key toggle takes its player manager from")
    val implementationMethods = methodsOf(implementation)
        ?: throw PatchException("Block author: $implementation, the player manager, isn't in this build.")
    if (implementationMethods.none {
            it.name == resume.second.name && it.parameterTypes.isEmpty() && it.returnType == "V" && it.hasString(RESUME_LOG)
        }
    ) {
        throw PatchException(
            "Block author: $implementation.${resume.second.name}() doesn't log \"$RESUME_LOG\", so it " +
                "isn't the resume the hold would call.",
        )
    }
    return NativePlaybackMembers(
        awemeGetter = NativeCall(getter.second, getter.first.throughInterface()),
        manager = manager,
        pause = NativeCall(pause.second, pause.first.throughInterface()),
        resume = NativeCall(resume.second, resume.first.throughInterface()),
        implementation = implementation,
    )
}

/** The implementation's own resume: the method the hold's check above found logging the line. */
internal fun <M : Method> Iterable<M>.implementationResume(members: NativePlaybackMembers): M =
    filter {
        it.name == members.resume.reference.name && it.parameterTypes.isEmpty() && it.returnType == "V" &&
            it.hasString(RESUME_LOG)
    }.singleOrPatchException("Block author: ${members.implementation}'s resume")

internal fun BytecodePatchContext.resolveNativePlayback(): NativePlaybackMembers {
    fun method(type: String, name: String, parameters: List<String>): Method =
        (classDefByOrNull(type) ?: throw PatchException("Block author: $type isn't in this build."))
            .methods.filter { it.name == name && it.parameterTypes.map { p -> p.toString() } == parameters }
            .singleOrPatchException("Block author: $type->$name")
    return resolveNativePlaybackMembers(
        pauseVideo = method(PLAYER_CONTROLLER, "pauseVideo", emptyList()),
        keyToggle = method(FEED_RECOMMEND_FRAGMENT, "onDispatchKeyEvent", listOf(KEY_EVENT)),
    ) { classDefByOrNull(it)?.methods }
}

/**
 * Puts the hold's question in front of TikTok's own plays of a video a pause switch or the app
 * lock has paused (#83). On a cold start the first video starts again about 1.7 s after the
 * first-frame pause, and pausing it after that start let it play for a moment, so the start itself
 * is asked about: PlayerController's play method, which the feed panel's resume, a new surface, a
 * network change and a TopView ad's end all reach, and the manager implementation's resume, which
 * TikTok's feed events and closing sheets call without it. The hold's own resume goes through.
 */
internal fun BytecodePatchContext.installSwitchPlayGuards(members: NativePlaybackMembers) {
    PlayerPlayFingerprint.method.apply {
        // The controller and its Aweme passed as one range, so the Aweme has to come right after.
        val aweme = awemeParameterRegister()
        if (aweme != 1) throw PatchException("Block author: the play method's Aweme is p$aweme, not p1.")
        // A refused play answers the way the method's own casting check does: an empty string.
        guardAtEntry(
            "Block author",
            "invoke-static/range { p0 .. p1 }, $HOLD->refusePlay(Ljava/lang/Object;Ljava/lang/Object;)Z",
            "const-string v0, \"\"\nreturn-object v0",
        )
    }
    mutableClassDefBy(members.implementation).methods.implementationResume(members).guardAtEntry(
        "Block author",
        "invoke-static/range { p0 .. p0 }, $HOLD->refuseResume(Ljava/lang/Object;)Z",
        "return-void",
    )
}

/** Writes the members into the hold's three bridge methods, whose own bodies serve the tests. */
internal fun BytecodePatchContext.installNativePlayback(members: NativePlaybackMembers) {
    val hold = mutableClassDefBy(HOLD)
    fun rewrite(name: String, body: String) {
        val original = hold.methods.filter { it.name == name }
            .singleOrPatchException("Block author: the session hold's $name bridge")
        val bridge = original.cloneMutable(additionalRegisters = 1)
        hold.methods.remove(original)
        hold.methods.add(bridge)
        bridge.addInstructions(0, body)
    }
    rewrite("currentAweme", """
        check-cast p0, $PLAYER_CONTROLLER
        ${members.awemeGetter.invoke}/range {p0 .. p0}, ${members.awemeGetter.reference}
        move-result-object v0
        return-object v0
    """)
    for ((name, call) in listOf("pauseNative" to members.pause, "resumeNative" to members.resume)) {
        rewrite(name, """
            check-cast p0, ${members.manager}
            ${call.invoke}/range {p0 .. p0}, ${call.reference}
            const/4 v0, 0x1
            return v0
        """)
    }
}
