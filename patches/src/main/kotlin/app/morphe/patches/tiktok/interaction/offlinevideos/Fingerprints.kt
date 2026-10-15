package app.morphe.patches.tiktok.interaction.offlinevideos

import app.morphe.patcher.Fingerprint
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal object OfflineModeSheetOptionsFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, classDef ->
        classDef.endsWith("/OfflineModeSheetPageAssem;") &&
            method.name == "<clinit>" &&
            method.parameterTypes.isEmpty()
    },
)

internal object OfflineModeListConstructorFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, classDef ->
        classDef.endsWith("/OfflineModeListVM;") &&
            method.name == "<init>" &&
            method.parameterTypes.isEmpty()
    },
)

/**
 * Static config holder of the offline-mode options (X.0sIr in TikTok 46.2.3, X.09zG in 47.1.4). Its class
 * initializer builds the video-count option lists (50/100/150/200 and 60/120/240/480 variants) and stores
 * them in static List fields.
 */
internal object OfflineModeOptionConfigFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        method.name == "<clinit>" &&
            method.parameterTypes.isEmpty() &&
            method.implementation?.instructions?.let { instructions ->
                val literals = instructions.mapNotNull { (it as? NarrowLiteralInstruction)?.narrowLiteral }.toSet()
                literals.containsAll(listOf(50, 100, 150, 200, 480)) &&
                    instructions.any { instruction ->
                        instruction.opcode == Opcode.SPUT_OBJECT &&
                            instruction.getReference<FieldReference>()?.type == "Ljava/util/List;"
                    }
            } == true
    },
)

/** Offline option enum (X.0mE9 in TikTok 46.2.3, X.1Psf in 47.1.4) whose entries carry count, minutes and size. */
internal object OfflineModeOptionEnumFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("DOWNLOAD_200_VIDEOS"),
    custom = { method, _ -> method.name == "<clinit>" && method.parameterTypes.isEmpty() },
)
