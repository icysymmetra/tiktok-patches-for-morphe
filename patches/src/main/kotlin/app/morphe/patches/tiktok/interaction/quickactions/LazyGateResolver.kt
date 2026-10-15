/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.quickactions

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/**
 * TikTok feature gates of the form `static fun LIZ(): T = lazyValue.value` live in small holder classes whose
 * names change every build (X.0BJV / X.0BIZ in 46.2.3, X.09Xp in 47.1.4). The holder's static initializer
 * instantiates the Function0 lambda that reads the AB key, so the lambda class (matched by its key string)
 * identifies the holder; [isGate] then selects the gate method inside it.
 */
internal fun BytecodePatchContext.resolveLazyGateMethod(
    keyLambdaType: String,
    patchName: String,
    isGate: (Method) -> Boolean,
): MutableMethod {
    var gateMethod: MutableMethod? = null
    classDefForEach { classDef ->
        if (gateMethod != null) return@classDefForEach
        val instantiatesLambda = classDef.methods.any { method ->
            method.name == "<clinit>" &&
                method.implementation?.instructions?.any { instruction ->
                    instruction.opcode == Opcode.NEW_INSTANCE &&
                        instruction.getReference<TypeReference>()?.type == keyLambdaType
                } == true
        }
        if (!instantiatesLambda) return@classDefForEach
        val gate = classDef.methods.singleOrNull { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && isGate(method)
        } ?: return@classDefForEach
        gateMethod = mutableClassDefBy(classDef).findMutableMethodOf(gate)
    }
    return gateMethod
        ?: throw PatchException("$patchName: feature gate holding the lambda $keyLambdaType was not resolved.")
}
