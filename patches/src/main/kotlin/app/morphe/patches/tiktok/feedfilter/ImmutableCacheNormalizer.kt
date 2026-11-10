/*
 * Copyright 2026 Metra contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.feedfilter

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.privacy.invokeSitesOf
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

internal const val NORMALIZED_CACHE_BRIDGE = "metraNormalizeCacheResult"

/** Native cleanup runs on the original state, then downstream consumers receive an immutable copy. */
internal fun normalizedCacheBridge(normalizer: MethodReference, payload: FieldReference,
    aweme: FieldReference, emptyCopy: MethodReference): MutableMethod =
    MutableMethod(ImmutableMethod(normalizer.definingClass, NORMALIZED_CACHE_BRIDGE,
        listOf(ImmutableMethodParameter(payload.definingClass, null, null)), payload.definingClass,
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value,
        null, null, ImmutableMethodImplementation(2, emptyList(), null, null))).apply {
        addInstructions(0, """
            invoke-static/range {p0 .. p0}, $normalizer
            if-eqz p0, :metra_keep_normalized_result
            iget-object v0, p0, $payload
            if-eqz v0, :metra_keep_normalized_result
            iget-object v0, v0, $aweme
            invoke-static/range {v0 .. v0}, Lapp/morphe/extension/tiktok/feedfilter/FeedItemsFilter;->shouldKeepNormalizedCache(Lcom/ss/android/ugc/aweme/feed/model/Aweme;)Z
            move-result v0
            if-nez v0, :metra_keep_normalized_result
            invoke-static/range {p0 .. p0}, $emptyCopy
            move-result-object p0
            :metra_keep_normalized_result
            return-object p0
        """)
    }

internal fun BytecodePatchContext.installImmutableCacheNormalizer(normalizer: MutableMethod,
    payload: FieldReference, aweme: FieldReference, emptyCopy: MutableMethod) {
    val owner = mutableClassDefBy(normalizer.definingClass)
    if (owner.methods.any { it.name == NORMALIZED_CACHE_BRIDGE }) throw PatchException("Cache normalizer already adapted")
    val sites = invokeSitesOf(setOf(normalizer.toString()), static = true)
    if (sites.size != 3) throw PatchException("Expected three reviewed cache-normalizer handoffs, found ${sites.size}")
    // On 47.4.3 each result is immediately returned, or forwarded to its one callback.
    // Reject aliasing or extra work instead of replacing a register that later code ignores.
    sites.forEach { site ->
        if (site.registers.size != 1 || site.registers.single() > 255) throw PatchException("Unencodable cache normalizer argument")
        val register = site.registers.single()
        val suffix = site.method.implementation!!.instructions.drop(site.index + 1).toList()
        val returnsResult = suffix.size == 1 && suffix[0].opcode == Opcode.RETURN_OBJECT &&
            (suffix[0] as OneRegisterInstruction).registerA == register
        val callback = suffix.getOrNull(1)?.getReference<MethodReference>()
        val forwardsResult = suffix.size == 3 && suffix[0].opcode == Opcode.IGET_OBJECT &&
            suffix[1].opcode == Opcode.INVOKE_INTERFACE && suffix[2].opcode == Opcode.RETURN_VOID &&
            (suffix[0] as TwoRegisterInstruction).registerA != register &&
            (suffix[1] as FiveRegisterInstruction).let {
                it.registerCount == 2 && it.registerD == register &&
                    it.registerC == (suffix[0] as TwoRegisterInstruction).registerA
            } &&
            callback != null && callback.parameterTypes.map(CharSequence::toString) == listOf(payload.definingClass) && callback.returnType == "V"
        if (!returnsResult && !forwardsResult) throw PatchException("Unreviewed cache continuation: ${site.method}")
    }
    val bridge = normalizedCacheBridge(normalizer, payload, aweme, emptyCopy)
    owner.methods.add(bridge)
    sites.forEach { site ->
        val method = mutableClassDefBy(site.owner).findMutableMethodOf(site.method)
        val register = site.registers.single()
        method.replaceInstruction(site.index, "invoke-static/range {v$register .. v$register}, $bridge")
        method.addInstruction(site.index + 1, "move-result-object v$register")
    }
}
