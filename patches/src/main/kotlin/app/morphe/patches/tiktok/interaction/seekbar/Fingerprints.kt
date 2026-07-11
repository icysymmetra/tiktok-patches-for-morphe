package app.morphe.patches.tiktok.interaction.seekbar

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val AWEME_CLASS = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"

private fun isTargetClass(classDef: ClassDef): Boolean =
    classDef.methods.any { sibling ->
        sibling.implementation?.instructions?.any { instruction ->
            (instruction as? ReferenceInstruction)?.reference?.let { reference ->
                reference is StringReference &&
                    (reference.string == "homepage_hot" || reference.string == "FeedRecommendFragment")
            } ?: false
        } == true
    }

internal object VanillaLongFilterFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(AWEME_CLASS),
    custom = { method, classDef ->
        isTargetClass(classDef) && (method.implementation?.instructions?.count() ?: 0) > 20
    },
)

internal object ShouldShowProgressBarFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(AWEME_CLASS),
    custom = { method, classDef ->
        isTargetClass(classDef) && (method.implementation?.instructions?.count() ?: 0) <= 20
    },
)

internal object SetSeekBarShowTypeFingerprint : Fingerprint(
    strings = listOf("seekbar show type change, change to:"),
)
