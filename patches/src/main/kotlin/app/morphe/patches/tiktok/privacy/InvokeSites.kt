/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.util.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.util.classesCalling
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * One call to a framework method the privacy patches want to stand in front of.
 *
 * @property registers the registers the call passes, receiver first, in order.
 * @property ranged whether the call is the /range form, which the replacement has to be too.
 */
internal data class InvokeSite(
    val owner: ClassDef,
    val method: Method,
    val index: Int,
    val target: String,
    val registers: List<Int>,
    val ranged: Boolean,
)

/** The invoke opcodes a site may use, by whether the callee is static or an interface method. */
private val VIRTUAL_OPCODES = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)
private val STATIC_OPCODES = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
private val INTERFACE_OPCODES = setOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)

/**
 * Every call to one of [targets] outside the extension, in both invoke forms.
 *
 * <p>The first version of these patches matched `INVOKE_VIRTUAL` alone, and a call carrying
 * five arguments or a register above v15 is `invoke-virtual/range`, so `ContentResolver.query`
 * with its five parameters was never intercepted at all: the patch applied, printed a count of
 * zero, and the README said contacts were blocked. Both forms are read here and the replacement
 * is emitted in the same form the site used.
 *
 * @param targets full method descriptors, `Lpkg/Type;->name(params)ret`.
 * @param static whether the targets are static, which changes the opcodes that can reach them.
 * @param throughInterface whether the targets are interface methods, reached by invoke-interface.
 */
internal fun BytecodePatchContext.invokeSitesOf(
    targets: Set<String>,
    static: Boolean = false,
    throughInterface: Boolean = false,
): List<InvokeSite> {
    val opcodes = when {
        throughInterface -> INTERFACE_OPCODES
        static -> STATIC_OPCODES
        else -> VIRTUAL_OPCODES
    }
    val names = targetNames(targets)
    val callers = classesCalling(targets.map { it.substringBefore("->") })
    val sites = mutableListOf<InvokeSite>()
    classDefForEach { owner ->
        if (owner.type !in callers || owner.type.startsWith("Lapp/morphe/extension/")) return@classDefForEach
        owner.methods.forEach { method ->
            method.implementation?.instructions?.forEachIndexed { index, instruction ->
                if (instruction.opcode !in opcodes) return@forEachIndexed
                val reference = instruction.getReference<MethodReference>() ?: return@forEachIndexed
                // The name first: writing every call in TikTok out as a descriptor to compare took a
                // quarter of a whole patching run, once for each privacy patch (#54).
                if (reference.name !in names) return@forEachIndexed
                val ref = reference.toString()
                if (ref !in targets) return@forEachIndexed
                val registers: List<Int>
                val ranged: Boolean
                when (instruction) {
                    is FiveRegisterInstruction -> {
                        registers = listOf(
                            instruction.registerC, instruction.registerD, instruction.registerE,
                            instruction.registerF, instruction.registerG,
                        ).take(instruction.registerCount)
                        ranged = false
                    }
                    is RegisterRangeInstruction -> {
                        registers = (0 until instruction.registerCount).map { instruction.startRegister + it }
                        ranged = true
                    }
                    else -> return@forEachIndexed
                }
                sites += InvokeSite(owner, method, index, ref, registers, ranged)
            }
        }
    }
    return sites
}

/** The method names in full descriptors like `Lpkg/Type;->name(params)ret`. */
internal fun targetNames(targets: Collection<String>): Set<String> =
    targets.mapTo(HashSet()) { target ->
        require("->" in target && "(" in target) { "not a full method descriptor: $target" }
        target.substringAfter("->").substringBefore('(')
    }

/**
 * The `invoke-static` that hands this site's registers to [replacement], a full static method
 * descriptor whose parameters are the receiver followed by the original parameters.
 */
internal fun InvokeSite.staticCall(replacement: String): String =
    if (ranged) {
        "invoke-static/range { v${registers.first()} .. v${registers.last()} }, $replacement"
    } else {
        "invoke-static { ${registers.joinToString(", ") { "v$it" }} }, $replacement"
    }

/** This site's own interface call again, in the form it had, for a site kept behind a prefix. */
internal fun InvokeSite.interfaceCall(): String =
    if (ranged) {
        "invoke-interface/range { v${registers.first()} .. v${registers.last()} }, $target"
    } else {
        "invoke-interface { ${registers.joinToString(", ") { "v$it" }} }, $target"
    }

/**
 * Puts a static call taking only the receiver in front of each interface site, and keeps the
 * site's own call right after it. The static takes the site's place rather than being inserted
 * before it, so a branch that jumped to the call now lands on the static and runs both.
 */
internal fun BytecodePatchContext.prefixSites(sites: List<InvokeSite>, prefix: String) {
    sites.sortedWith(compareBy({ it.owner.type }, { it.method.toString() }, { -it.index })).forEach { site ->
        val method = mutableClassDefBy(site.owner).findMutableMethodOf(site.method)
        method.replaceInstruction(site.index, site.copy(registers = listOf(site.registers.first())).staticCall(prefix))
        method.addInstruction(site.index + 1, site.interfaceCall())
    }
}

/**
 * Replaces each site with a static call, looked up by the site's target in [replacements].
 *
 * <p>Sites are applied in reverse order within a method so an earlier index is still right
 * after a later one has been rewritten, which matters once an insertion is mixed in.
 */
internal fun BytecodePatchContext.replaceSites(
    sites: List<InvokeSite>,
    replacements: Map<String, String>,
) {
    sites.sortedWith(compareBy({ it.owner.type }, { it.method.toString() }, { -it.index })).forEach { site ->
        val replacement = replacements.getValue(site.target)
        mutableClassDefBy(site.owner).findMutableMethodOf(site.method)
            .replaceInstruction(site.index, site.staticCall(replacement))
    }
}
