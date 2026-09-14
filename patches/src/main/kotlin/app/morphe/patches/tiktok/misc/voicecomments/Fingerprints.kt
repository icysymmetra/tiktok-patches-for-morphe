/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.misc.voicecomments

import app.morphe.patcher.Fingerprint
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal object VoiceCommentPublishGateFingerprint : Fingerprint(
    returnType = "Z",
    parameters = emptyList(),
    custom = { method, classDef ->
        classDef.type == "LX/0AkX;" &&
            method.name == "LIZ" &&
            method.implementation?.instructions?.let { instructions ->
                instructions.any { instruction ->
                    instruction.getReference<MethodReference>()?.let { reference ->
                        reference.definingClass == "LX/01xP;" &&
                            reference.name == "getValue" &&
                            reference.parameterTypes.isEmpty() &&
                            reference.returnType == "Ljava/lang/Object;"
                    } == true
                } && instructions.any { instruction ->
                    instruction.getReference<MethodReference>()?.let { reference ->
                        reference.definingClass == "Ljava/lang/Number;" &&
                            reference.name == "intValue" &&
                            reference.parameterTypes.isEmpty() &&
                            reference.returnType == "I"
                    } == true
                }
            } == true
    },
)
