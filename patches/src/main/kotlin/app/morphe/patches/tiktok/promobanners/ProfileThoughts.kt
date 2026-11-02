/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.promobanners

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.findFreeRegister
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** The profile header's picture, yours or someone else's. Its real name is kept on 47.1.4. */
internal const val PROFILE_AVATAR_ASSEM = "Lcom/ss/android/ugc/profile/business/avatar/ProfileAvatarAssem;"

/** The line TikTok logs when it tells the profile picture whether its Thoughts bubble shows. */
internal const val THOUGHT_VISIBILITY_LOG = "updateStoryNote, isThoughtVisible="

/**
 * The bubble's width setter. The bubble's interface keeps its real member names, and the profile
 * picture calls this one on the bubble it is about to keep and on nothing else.
 */
internal const val BUBBLE_WIDTH_SETTER = "setContentMaxWidth"

private const val THOUGHTS = "Lapp/morphe/extension/tiktok/profile/ProfileThoughts;"

/**
 * Where the profile picture builds its Thoughts bubble (#122), the note TikTok draws as a speech
 * bubble above a profile's picture, and on your own profile the "Share your thoughts..." prompt in
 * the same place. It finds the bubble's view, sets it up and keeps it in a field of its own, and
 * that one store is the only place the field is written. Every later read of the field (the note
 * binding, the visibility callback, the layout passes) checks it for null or tests its type
 * first, so an empty field is a profile picture with no bubble.
 */
internal object ProfileAvatarViewCreatedFingerprint : Fingerprint(
    definingClass = PROFILE_AVATAR_ASSEM,
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
)

/**
 * The callback the Thoughts builder calls with whether the bubble shows, handed in by the
 * profile picture's note binding. With true it opens a spacer above the picture, moves the
 * header down and logs the bubble as shown, all of which reads that third argument. Its class is
 * renamed on every build, so it is found by its log line and by holding the profile picture.
 */
internal object ThoughtVisibilityCallbackFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;"),
    strings = listOf(THOUGHT_VISIBILITY_LOG).map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> isThoughtVisibilityCallback(method) },
)

internal fun isThoughtVisibilityCallback(method: Method): Boolean {
    if (AccessFlags.STATIC.isSet(method.accessFlags)) return false
    return method.implementation?.instructions?.any { instruction ->
        instruction.getReference<FieldReference>()?.type == PROFILE_AVATAR_ASSEM
    } == true
}

/** The bubble's one store in the profile picture's onViewCreated, and a register free after it. */
internal class ThoughtBubbleStore(
    val index: Int,
    val bubble: Int,
    val owner: Int,
    val field: String,
    val answer: Int,
)

/**
 * Finds the store of the Thoughts bubble into the profile picture's own field. The field is
 * picked by what it holds: the type the method sets the bubble's width through. An `iput-object`
 * names its registers in four bits, so the bubble and the picture are both under v16, and the
 * answer register has to be too, because it is written back into that field.
 */
internal fun Method.thoughtBubbleStore(patch: String): ThoughtBubbleStore {
    val instructions = implementation?.instructions?.toList()
        ?: throw PatchException("$patch: $definingClass->$name has no implementation.")
    val bubbleTypes = instructions.mapNotNullTo(mutableSetOf()) { instruction ->
        instruction.getReference<MethodReference>()?.takeIf {
            it.name == BUBBLE_WIDTH_SETTER && it.returnType == "V" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("I")
        }?.definingClass
    }
    val bubbleType = bubbleTypes.singleOrNull() ?: throw PatchException(
        "$patch: the profile picture sets a Thoughts bubble's width through ${bubbleTypes.size} types, not one.",
    )
    val stores = instructions.withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.IPUT_OBJECT && instruction.getReference<FieldReference>()?.let {
            it.definingClass == PROFILE_AVATAR_ASSEM && it.type == bubbleType
        } == true
    }
    val store = stores.singleOrNull() ?: throw PatchException(
        "$patch: the profile picture stores its Thoughts bubble ${stores.size} times, not once.",
    )
    val write = store.value as TwoRegisterInstruction
    val field = store.value.getReference<FieldReference>()!!
    val answer = findFreeRegister(store.index + 1, write.registerA, write.registerB)
    if (answer > 15) {
        throw PatchException("$patch: no register under v16 is free after the Thoughts bubble is stored.")
    }
    return ThoughtBubbleStore(
        store.index,
        write.registerA,
        write.registerB,
        "${field.definingClass}->${field.name}:${field.type}",
        answer,
    )
}

/**
 * Right after the profile picture keeps its bubble, asks the extension whether Thoughts are
 * hidden. When they are, the extension has already taken the bubble's view off the screen and
 * the field is emptied, so nothing later puts a note in it or shows it again.
 */
internal fun MutableMethod.hideThoughtBubble(store: ThoughtBubbleStore) = addInstructionsWithLabels(
    store.index + 1,
    """
        invoke-static/range { v${store.bubble} .. v${store.bubble} }, $THOUGHTS->hideBubble(Ljava/lang/Object;)Z
        move-result v${store.answer}
        if-eqz v${store.answer}, :morphe_thought_bubble_kept
        const/4 v${store.answer}, 0x0
        iput-object v${store.answer}, v${store.owner}, ${store.field}
        :morphe_thought_bubble_kept
        nop
    """,
)

/**
 * Puts the switch in front of the visibility callback. With Thoughts hidden, the third argument,
 * whether the bubble shows, becomes false before anything reads it, so the spacer above the
 * picture stays closed and the header keeps its place. The question lands in v0, a local here
 * (the callback has six), and requireLocals checks that before anything is written.
 */
internal fun MutableMethod.keepThoughtSpaceClosed(patch: String) {
    requireLocals(patch, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static {}, $THOUGHTS->hideOnProfiles()Z
            move-result v0
            if-eqz v0, :morphe_thought_space_left
            sget-object p3, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
            :morphe_thought_space_left
            nop
        """,
    )
}
