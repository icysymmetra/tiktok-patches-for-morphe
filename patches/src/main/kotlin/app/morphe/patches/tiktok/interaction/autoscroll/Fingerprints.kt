package app.morphe.patches.tiktok.interaction.autoscroll

import app.morphe.patcher.Fingerprint
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** Static feature gate reading the "fyp_auto_scroll" AB key (czc.o1 in 46.2.3, jhd.o1 in 47.1.4). */
internal object AutoScrollFeatureGateFingerprint : Fingerprint(
    returnType = "Z",
    parameters = emptyList(),
    strings = listOf("fyp_auto_scroll"),
    custom = { method, _ ->
        // Reject gates for other keys that merely contain the key, such as "tablet_fyp_auto_scroll".
        method.implementation?.instructions?.any { instruction ->
            (instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO) &&
                instruction.getReference<StringReference>()?.string == "fyp_auto_scroll"
        } == true
    },
)

/** Builds the auto scroll entry of the long-press panel (X.0oi7.LJI in 46.2.3, X.19wb.LJIIIIZZ in 47.1.4). */
internal object AutoScrollActionFactoryFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf("L"),
    strings = listOf("panel_auto_scroll", "auto_scroll"),
)
