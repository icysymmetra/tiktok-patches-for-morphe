/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val ROOT_GUARD = "Lapp/morphe/extension/tiktok/privacy/DevicePrivacyGuard;"
private const val ROOT = "Device privacy guard (root)"
private const val CONTEXT = "Landroid/content/Context;"
private const val STRING = "Ljava/lang/String;"
private const val MAP = "Ljava/util/Map;"
private const val LIST = "Ljava/util/List;"

internal enum class RootSignal(
    val markers: List<String>,
    val parameters: List<String>,
    val returnType: String,
    val hiddenMember: String?,
) {
    STATUS(listOf("test-keys", "/system/app/Superuser.apk", "/system/xbin/which su",
        "/system/bin/which su", "which su", "busybox which su"), emptyList(), "Z", null),
    PACKAGES(listOf("rootPackage", "summary", "hit", "items"), listOf(CONTEXT), MAP, "hiddenRootPackages"),
    PATHS(listOf("rootPath", "summary", "hit", "items"), emptyList(), MAP, "hiddenRootPaths"),
    WHICH_SU(listOf("which su", "sh", "-c", "state", "path"), emptyList(), MAP, "hiddenWhichSu"),
    SU_COMMAND(listOf("su", "-c", "id", "uid=0", "uidZero", "state"), emptyList(), MAP, "hiddenSuCommand"),
}

private fun Method.strings(): Set<String> = implementation?.instructions?.toList().orEmpty()
    .mapNotNull { it.getReference<StringReference>()?.string }.toSet()

/** The root maps share a catalog with other diagnostics, whose own methods must remain native. */
private fun ClassDef.hasRootToolCatalog(): Boolean = methods.any {
    it.name == "<clinit>" && it.strings().containsAll(listOf(
        "com.topjohnwu.magisk", "com.koushikdutta.superuser", "/system/bin/su", "/data/adb/magisk",
    ))
}

internal fun Method.isRootSignal(signal: RootSignal, owner: ClassDef): Boolean {
    if (!AccessFlags.PUBLIC.isSet(accessFlags) || !AccessFlags.STATIC.isSet(accessFlags) ||
        returnType != signal.returnType || parameterTypes.map(CharSequence::toString) != signal.parameters ||
        !strings().containsAll(signal.markers)) return false
    val code = implementation!!.instructions.toList()
    if (signal == RootSignal.STATUS) return code.any {
        it.opcode == Opcode.SGET_OBJECT && it.getReference<FieldReference>()?.let { field ->
            field.definingClass == "Landroid/os/Build;" && field.name == "TAGS" && field.type == STRING
        } == true
    } && code.any {
        it.getReference<MethodReference>()?.let { called ->
            called.definingClass == "Ljava/io/File;" && called.name == "exists" &&
                called.returnType == "Z" && called.parameterTypes.isEmpty()
        } == true
    }
    if (!owner.hasRootToolCatalog()) return false
    val calls = code.mapNotNull { it.getReference<MethodReference>() }.filter { it.definingClass == owner.type }
    return when (signal) {
        RootSignal.PACKAGES -> calls.any {
            it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(CONTEXT, STRING)
        } && code.any { it.opcode == Opcode.SGET_OBJECT && it.getReference<FieldReference>()?.let { field ->
            field.definingClass == owner.type && field.type == LIST
        } == true }
        RootSignal.PATHS -> calls.any {
            it.returnType == STRING && it.parameterTypes.map(CharSequence::toString) == listOf(STRING)
        } && code.any { it.opcode == Opcode.SGET_OBJECT && it.getReference<FieldReference>()?.let { field ->
            field.definingClass == owner.type && field.type == LIST
        } == true }
        RootSignal.WHICH_SU, RootSignal.SU_COMMAND -> calls.any {
            it.returnType.startsWith("L") && it.parameterTypes.map(CharSequence::toString) == listOf(LIST)
        }
        RootSignal.STATUS -> false
    }
}

internal class RootSignalFingerprint(signal: RootSignal) : Fingerprint(
    strings = signal.markers.map { string(it, StringComparisonType.CONTAINS) },
    parameters = signal.parameters,
    returnType = signal.returnType,
    custom = { method, owner -> method.isRootSignal(signal, owner) },
)

internal fun <T> singleRootSignal(signal: RootSignal, found: List<T>): T = found.singleOrNull()
    ?: throw PatchException("$ROOT: found ${found.size} $signal methods")

/** The original probes run intact only when the switch is off, paused or unavailable at startup. */
internal fun MutableMethod.guardRootSignal(signal: RootSignal) {
    check(returnType == signal.returnType && AccessFlags.STATIC.isSet(accessFlags) &&
        parameterTypes.map(CharSequence::toString) == signal.parameters) { "$ROOT: wrong result ABI for $signal" }
    val answer = if (signal == RootSignal.STATUS) "const/4 v0, 0x0\nreturn v0" else """
        invoke-static {}, $ROOT_GUARD->${signal.hiddenMember}()$MAP
        move-result-object v0
        return-object v0
    """
    guardAtEntry(ROOT, "invoke-static {}, $ROOT_GUARD->hideRoot()Z", answer)
}

internal fun BytecodePatchContext.hookRootSignals() {
    // Resolve all five before changing any. A collision or missing ABI aborts the patch.
    val matches = RootSignal.entries.associateWith { signal ->
        singleRootSignal(signal, RootSignalFingerprint(signal).matchAllOrNull().orEmpty())
    }
    if (matches.filterKeys { it != RootSignal.STATUS }.values.map { it.originalClassDef.type }.toSet().size != 1) {
        throw PatchException("$ROOT: the root diagnostic methods no longer share one catalog")
    }
    matches.forEach { (signal, match) -> match.method.guardRootSignal(signal) }
    println("[Device privacy guard] Guarded ${matches.size} root results.")
}
