/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.blockauthor

import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import app.morphe.util.superclassChain
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/wellbeing/SessionPlaybackHold;"
private const val AUDIO_MANAGER = "Landroid/media/AudioManager;"
private const val AUDIO_LISTENER = "Landroid/media/AudioManager\$OnAudioFocusChangeListener;"
private const val CONTEXT = "Landroid/content/Context;"

/** How far up a listener's superclasses the focus callback is looked for before giving up. */
private const val MAX_LISTENER_DEPTH = 16

/**
 * The host's player audio-focus helper, found by what it is rather than what it is called.
 *
 * <p>On 46.2.3 this was `LX/0q3r;` with its listener `LX/0q3s;`, and both names were written into
 * the patch, along with the name of the wrapper the request goes through and the exact registers
 * the host happened to use. All of that is renumbered by the next build. What does not change is
 * the shape: one class holding exactly one `AudioManager` and one listener, built from a Context,
 * with one `(Context)V` method that abandons focus and one that requests it. That shape names
 * exactly one class on 46.2.3, 46.7.3 and 46.8.3, and the same one the literals named.
 */
internal class NativeFocus(
    val helper: String,
    val listener: String,
    val request: MutableMethod,
    val abandon: MutableMethod,
    val change: MutableMethod,
)

internal fun BytecodePatchContext.resolveNativeFocus(): NativeFocus {
    val found = mutableListOf<Triple<ClassDef, Method, Method>>()
    val listenerOf = mutableMapOf<String, String>()
    classDefForEach { classDef ->
        val fields = classDef.fields.toList()
        if (fields.count { it.type == AUDIO_MANAGER } != 1) return@classDefForEach
        // One listener field, not the first of several. Only the change hook is placed off this
        // field; the other two read the listener off the host's own call. Two listener-typed
        // fields and a guess here would instrument a class the host never registers, while the
        // other two hooks reported the real one, and nothing would fail at patch time.
        //
        // A field typed as something that only reaches the interface through what it extends
        // counts, because R8 moves an interface onto a superclass routinely. A helper holding
        // both a declarer and an inheritor resolves to the declarer; two of either kind is an
        // ambiguity nothing here can settle, so the class is skipped and the count says so.
        val listeners = fields.filter { it.type != AUDIO_MANAGER && implementsAudioListener(it.type) }
        val listener = (listeners.singleOrNull { field ->
            classDefByOrNull(field.type)?.interfaces?.contains(AUDIO_LISTENER) == true
        } ?: listeners.singleOrNull())?.type ?: return@classDefForEach
        if (classDef.methods.none { it.name == "<init>" && it.parameterTypes.toList() == listOf(CONTEXT) }) {
            return@classDefForEach
        }
        val contextMethods = classDef.methods.filter {
            it.name != "<init>" && it.returnType == "V" &&
                it.parameterTypes.toList() == listOf(CONTEXT) &&
                AccessFlags.STATIC.value and it.accessFlags == 0
        }
        val abandon = contextMethods.singleOrNull { method -> method.calls { it.name == "abandonAudioFocus" } }
        val request = contextMethods.singleOrNull { method -> method.calls(::requestsFocus) }
        if (abandon == null || request == null || abandon == request) return@classDefForEach
        listenerOf[classDef.type] = listener
        found += Triple(classDef, request, abandon)
    }
    if (found.size != 1) {
        throw PatchException(
            "Block author button: expected one audio focus helper holding exactly one " +
                "AudioManager field and exactly one focus listener field, with a (Context)V that " +
                "abandons focus and one that requests it; found ${found.size}.",
        )
    }
    val (classDef, request, abandon) = found.single()
    val listenerType = listenerOf.getValue(classDef.type)
    val helper = mutableClassDefBy(classDef)
    // The callback can sit on a superclass of the field's type: the field is typed as whatever
    // the host declared and R8 is free to have put the implementation further up. Only the class
    // that declares it can be instrumented, so that is the one this walks to.
    val change = superclassChain(listenerType, MAX_LISTENER_DEPTH)
        .mapNotNull { type ->
            classDefByOrNull(type)?.let { mutableClassDefBy(type) }?.methods?.singleOrNull {
                it.name == "onAudioFocusChange" && it.returnType == "V" &&
                    it.parameterTypes.toList() == listOf("I") &&
                    AccessFlags.STATIC.value and it.accessFlags == 0
            }
        }
        .firstOrNull()
        ?: throw PatchException(
            "Block author button: neither $listenerType nor anything it extends declares " +
                "onAudioFocusChange(I)V.",
        )
    return NativeFocus(
        helper = classDef.type,
        listener = listenerType,
        request = helper.findMutableMethodOf(request),
        abandon = helper.findMutableMethodOf(abandon),
        change = change,
    )
}

/** Whether any instruction of the method invokes something the predicate accepts. */
private fun Method.calls(predicate: (MethodReference) -> Boolean): Boolean =
    implementation?.instructions?.any { instruction ->
        instruction.getReference<MethodReference>()?.let(predicate) == true
    } == true

/**
 * Whether the type is the focus listener interface or reaches it, following what its class
 * extends and what its interfaces extend.
 *
 * <p>Not the field type's own interface list: R8 moves an interface onto a superclass and puts a
 * sub-interface in front of one as a matter of course, and either leaves the direct list without
 * the name in it. That is the same brittleness the literals had, one level down.
 */
private fun BytecodePatchContext.implementsAudioListener(
    type: String,
    seen: MutableSet<String> = mutableSetOf(),
): Boolean {
    if (type == AUDIO_LISTENER) return true
    if (!seen.add(type)) return false
    val classDef = classDefByOrNull(type) ?: return false
    if (classDef.interfaces.any { implementsAudioListener(it, seen) }) return true
    return classDef.superclass?.let { implementsAudioListener(it, seen) } == true
}

/**
 * TikTok does not call `requestAudioFocus` itself. It goes through a static wrapper taking the
 * manager, the listener and two ints, which is the platform call's own argument list with the
 * manager in front, so that is what identifies it. The platform call is recognised as well, so
 * that a build which stopped going through the wrapper is named by
 * [captureNativeFocusRequest] as a request of the wrong shape rather than found by nothing.
 */
private fun requestsFocus(reference: MethodReference): Boolean =
    reference.name == "requestAudioFocus" ||
        (reference.returnType == "I" &&
            reference.parameterTypes.toList() == listOf(AUDIO_MANAGER, AUDIO_LISTENER, "I", "I"))

internal fun MutableMethod.captureNativeFocusRequest() {
    val instructions = implementation!!.instructions
    // Resolution accepts a method that contains at least one focus request; this needs the one it
    // rewrites. Two of them is a normal-path and error-path pair, which resolves and then reaches
    // here, so it is answered rather than left to a bare "more than one matching element".
    val index = instructions.withIndex().singleOrNull {
        it.value.getReference<MethodReference>()?.let(::requestsFocus) == true
    }?.index ?: throw PatchException(
        "Block author button: expected one focus request in $definingClass->$name.",
    )
    // The opcode is checked before the cast: a build that grew enough registers here emits
    // invoke-static/range, which is not a FiveRegisterInstruction, and casting first threw a
    // ClassCastException over the message written for exactly that case.
    check(instructions[index].opcode == Opcode.INVOKE_STATIC) {
        "Block author button: the focus request in $definingClass->$name is " +
            "${instructions[index].opcode}, not invoke-static."
    }
    val call = instructions[index] as FiveRegisterInstruction
    val reference = instructions[index].getReference<MethodReference>()!!
    check(call.registerCount == 4) {
        "Block author button: the focus request is not a four-argument static call."
    }
    // The original return also owns the null branches and Exception handler. Insert at the
    // invoke, then remove its old copy, so those paths never enter an orphaned move-result.
    // The host discards the request's answer, which is what makes the last argument's register
    // free to hold it: nothing reads that register again before the return.
    check(instructions[index + 1].opcode == Opcode.RETURN_VOID) {
        "Block author button: the focus request is no longer the last thing its method does."
    }
    val manager = call.registerC
    val listener = call.registerD
    val result = call.registerF
    addInstructions(
        index,
        """
            invoke-static {v$manager, v$listener, v${call.registerE}, v${call.registerF}}, $reference
            move-result v$result
            invoke-static {v$listener, v$result}, $EXTENSION->onNativeFocusRequestResult(Ljava/lang/Object;I)V
        """,
    )
    implementation!!.removeInstruction(index + 3)
}

internal fun MutableMethod.captureNativeFocusChange() {
    addInstruction(
        0,
        "invoke-static/range {p0 .. p1}, $EXTENSION->onNativeFocusChange(Ljava/lang/Object;I)V",
    )
}

internal fun MutableMethod.captureNativeFocusAbandon() {
    val instructions = implementation!!.instructions
    val index = instructions.withIndex().singleOrNull {
        it.value.getReference<MethodReference>()?.name == "abandonAudioFocus"
    }?.index ?: throw PatchException(
        "Block author button: expected one abandonAudioFocus call in $definingClass->$name.",
    )
    check(instructions[index].opcode == Opcode.INVOKE_VIRTUAL) {
        "Block author button: abandonAudioFocus in $definingClass->$name is " +
            "${instructions[index].opcode}, not invoke-virtual."
    }
    val call = instructions[index] as FiveRegisterInstruction
    check(call.registerCount == 2) {
        "Block author button: abandonAudioFocus is not called on a manager with one listener."
    }
    // Invalidate before calling Android, including when the native Exception handler runs.
    // registerD is the listener the host hands to the platform.
    addInstruction(index, "invoke-static {v${call.registerD}}, $EXTENSION->onNativeFocusAbandon(Ljava/lang/Object;)V")
}
