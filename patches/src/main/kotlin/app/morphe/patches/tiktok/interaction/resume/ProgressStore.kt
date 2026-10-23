/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.resume

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.getReference
import app.morphe.util.writeRegister
import app.morphe.util.writesAWideRegister
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/**
 * Where TikTok keeps the playback positions it resumes from.
 *
 * <p>A static holder carries three things the patch has to touch: a lazily built `LruCache` of
 * positions keyed by aid, the record it last stored or handed out, and the aid that record
 * belongs to. The holder, its fields and the Lazy interface are all renamed per build (the
 * holder is `LX/0Lze;` on 46.2.3 and `LX/0M7t;` on 46.8.3, where `LX/0Lze;` is an unrelated
 * class with no fields at all), so each is read off the host's own code rather than written
 * down. Named here, the injected lines assembled on every build and failed on the first
 * completed video of any build but 46.2.3.
 */
internal class ProgressStore(
    /** The static Lazy the cache of positions is built behind. */
    val cache: FieldReference,
    /** `getValue()` on that Lazy. */
    val cacheValue: MethodReference,
    /** How the host calls it: `invoke-interface` for an interface, which Lazy is. */
    val cacheValueInvoke: String,
    /** The record last stored or handed out. */
    val lastRecord: FieldReference,
    /** The aid that record belongs to. */
    val lastAid: FieldReference,
)

internal fun FieldReference.smali() = "$definingClass->$name:$type"

internal fun MethodReference.smali() =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/**
 * The store, read off the host's `onPlayProgressChange` at the `LruCache.put` that ends it.
 *
 * <p>That put takes the cache out of the Lazy (a static of the holder, through `getValue()` and a
 * cast to `LruCache`) and stores the record it has just written into the holder's "last record"
 * static. The aid beside it is the one String static the holder's own reader writes next to the
 * record: the reader is the holder's only static `(String)` method handing a record back, and it
 * answers from those two statics before it goes to the cache. Anything that does not line up
 * with that is refused by name.
 */
internal fun progressStoreAt(method: Method, putIndex: Int, classOf: (String) -> ClassDef?): ProgressStore {
    fun fail(why: String): Nothing = throw PatchException("Resume videos after scrolling: $why")

    val instructions = method.implementation?.instructions?.toList() ?: fail("the progress method has no body.")
    val put = instructions.getOrNull(putIndex) as? FiveRegisterInstruction
        ?: fail("the progress cache put is not a plain invoke.")

    // The cache: what the Lazy's getValue() hands back, cast to LruCache on the way.
    var at = instructions.writerOf(put.registerC, putIndex)
    val cast = instructions.getOrNull(at)
    if (cast?.opcode == Opcode.CHECK_CAST) {
        val type = cast.getReference<TypeReference>()?.type
        if (type != "Landroid/util/LruCache;") fail("the progress cache is cast to $type, not LruCache.")
        at = instructions.writerOf(put.registerC, at)
    }
    if (at < 1 || instructions[at].opcode != Opcode.MOVE_RESULT_OBJECT) {
        fail("the progress cache is not the answer of a call.")
    }
    val call = instructions[at - 1] as? FiveRegisterInstruction
        ?: fail("the progress cache does not come out of a plain call.")
    val cacheValue = call.getReference<MethodReference>()
    if ((call.opcode != Opcode.INVOKE_INTERFACE && call.opcode != Opcode.INVOKE_VIRTUAL) ||
        cacheValue == null || cacheValue.name != "getValue" ||
        cacheValue.parameterTypes.isNotEmpty() || cacheValue.returnType != "Ljava/lang/Object;" ||
        call.registerCount != 1
    ) {
        fail("the progress cache does not come out of a Lazy's getValue().")
    }
    val cache = instructions.getOrNull(instructions.writerOf(call.registerC, at - 1))
        ?.takeIf { it.opcode == Opcode.SGET_OBJECT }
        ?.getReference<FieldReference>()
        ?.takeIf { it.type == cacheValue.definingClass }
        ?: fail("the Lazy the progress cache comes out of is not read from a static.")
    val holder = cache.definingClass

    // The record: what the put stores, read out of the holder's own static.
    val lastRecord = instructions.getOrNull(instructions.writerOf(put.registerE, putIndex))
        ?.takeIf { it.opcode == Opcode.SGET_OBJECT }
        ?.getReference<FieldReference>()
        ?.takeIf { it.definingClass == holder }
        ?: fail("the stored position is not read out of a static of $holder.")

    // The aid: the String static the holder's reader writes beside the record.
    val holderClass = classOf(holder) ?: fail("the store $holder is not in the app.")
    val readers = holderClass.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;") &&
            it.returnType == lastRecord.type
    }
    if (readers.size != 1) {
        fail("expected one static (String) method on $holder handing back ${lastRecord.type}, found ${readers.size}.")
    }
    val written = readers.single().implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode == Opcode.SPUT_OBJECT }
        .mapNotNull { it.getReference<FieldReference>() }
        .filter { it.definingClass == holder }
    if (written.none { it.name == lastRecord.name && it.type == lastRecord.type }) {
        fail("the reader on $holder does not write the record it hands back.")
    }
    val aids = written.filter { it.type == "Ljava/lang/String;" }.distinctBy { it.name }
    if (aids.size != 1) {
        fail("expected the reader on $holder to write one String static beside the record, found ${aids.size}.")
    }

    val invoke = if (call.opcode == Opcode.INVOKE_INTERFACE) "invoke-interface" else "invoke-virtual"
    return ProgressStore(cache, cacheValue, invoke, lastRecord, aids.single())
}

/**
 * The nearest instruction above [index] that writes [register], a check-cast included, or -1.
 * A straight walk back, which is only as good as the checks made on what it finds.
 */
private fun List<Instruction>.writerOf(register: Int, index: Int): Int {
    for (i in index - 1 downTo 0) {
        val instruction = this[i]
        if (instruction.opcode == Opcode.CHECK_CAST &&
            (instruction as OneRegisterInstruction).registerA == register
        ) {
            return i
        }
        val written = instruction.writeRegister ?: continue
        if (written == register || (instruction.writesAWideRegister && written + 1 == register)) return i
    }
    return -1
}
