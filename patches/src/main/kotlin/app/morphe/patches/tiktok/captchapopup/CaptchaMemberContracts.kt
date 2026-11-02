/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.captchapopup

import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import java.util.ArrayDeque

/** True when this method has an instance receiver and can use p0 as that receiver. */
internal fun Method.hasInstanceReceiver(): Boolean =
    accessFlags and AccessFlags.STATIC.value == 0

/** The invoke opcode family required by a value whose declared type is this class or interface. */
internal fun ClassDef.instanceInvokeKind(): String =
    if (accessFlags and AccessFlags.INTERFACE.value != 0) "invoke-interface" else "invoke-virtual"

/**
 * Finds a public instance method using the same superclass and superinterface resolution available to
 * an invoke on [type]. Dexlib's [ClassDef.methods] contains declared members only, while a legal
 * callback or request can inherit the member the injected invoke names.
 */
internal fun ClassDef.hasInstanceMethodInHierarchy(
    resolve: (String) -> ClassDef?,
    name: String,
    returnType: String,
    parameterTypes: List<String>,
): Boolean {
    val pending = ArrayDeque<ClassDef>()
    val visited = HashSet<String>()
    pending.add(this)

    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!visited.add(current.type)) continue
        if (current.methods.any { method ->
                method.hasInstanceReceiver() &&
                    method.accessFlags and AccessFlags.PUBLIC.value != 0 &&
                    method.name == name &&
                    method.returnType == returnType &&
                    method.parameterTypes.map(CharSequence::toString) == parameterTypes
            }
        ) {
            return true
        }

        current.superclass?.let(resolve)?.let(pending::addLast)
        current.interfaces.forEach { type -> resolve(type.toString())?.let(pending::addLast) }
    }

    return false
}

/** Validates every virtual call emitted by the risk-control service hook. */
internal fun requireRiskControlMembers(
    requestType: String,
    callbackType: String,
    resolve: (String) -> ClassDef?,
): ClassDef {
    val feature = "Hide the risk control CAPTCHA"
    val request = resolve(requestType)
        ?: throw PatchException("$feature: $requestType is not a class in this build.")
    for ((getter, returns) in listOf(
        "getActivity" to "Landroid/app/Activity;",
        "getServiceType" to "Ljava/lang/String;",
    )) {
        check(request.hasInstanceMethodInHierarchy(resolve, getter, returns, emptyList())) {
            "$feature: $requestType has no $getter()$returns."
        }
    }

    val callback = resolve(callbackType)
        ?: throw PatchException("$feature: $callbackType is not a class in this build.")
    check(callback.hasInstanceMethodInHierarchy(resolve, "onFail", "V", listOf("I"))) {
        "$feature: $callbackType has no onFail(I)V to refuse the request with."
    }
    return callback
}

/** Builds a dismiss call only after its inherited instance-method contract has been proved. */
internal fun captchaDismissCall(
    type: String,
    name: String,
    resolve: (String) -> ClassDef?,
): String {
    val callback = resolve(type)
        ?: throw PatchException("Hide CAPTCHA popups: $type is not a class in this build.")
    if (!callback.hasInstanceMethodInHierarchy(resolve, name, "V", emptyList())) {
        throw PatchException("Hide CAPTCHA popups: $type has no $name()V to dismiss the request with.")
    }
    return "${callback.instanceInvokeKind()}/range {p3 .. p3}, $type->$name()V"
}
