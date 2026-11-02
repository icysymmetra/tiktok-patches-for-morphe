/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.quality

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val H264 = "Lapp/morphe/extension/tiktok/playback/H264Playback;"
private const val FEATURE = "Prefer H.264 playback"

/** The player kit's gear, whose getCodecType the extension reads to tell H.264 apart. */
internal const val SIM_BIT_RATE = "Lcom/ss/android/ugc/playerkit/simapicommon/model/SimBitRate;"

/**
 * The int field a gear keeps its codec in. The extension reads it through the public
 * `getCodecType()I`, and the converters write it through `setCodecType(I)V` from the feed's
 * `is_bytevc1`, where 0 is H.264. Both have to be the plain accessors of one int field, or the
 * extension would read something other than the codec and drop the wrong gears, so a build
 * where they aren't fails here instead.
 */
internal fun requireGearCodecField(resolve: (String) -> ClassDef?): FieldReference {
    val gear = resolve(SIM_BIT_RATE) ?: throw PatchException("$FEATURE: $SIM_BIT_RATE is not a class in this build.")
    fun accessor(name: String, parameters: List<String>, returns: String): Method =
        gear.methods.singleOrNull {
            it.name == name && it.returnType == returns &&
                it.parameterTypes.map(CharSequence::toString) == parameters
        }?.takeIf { AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags) }
            ?: throw PatchException("$FEATURE: $SIM_BIT_RATE has no public $name${parameters.joinToString("", "(", ")")}$returns.")

    val read = accessor("getCodecType", emptyList(), "I").implementation?.instructions?.toList().orEmpty()
    val readField = read.takeIf {
        it.size == 2 && it[0].opcode == Opcode.IGET && it[1].opcode == Opcode.RETURN &&
            (it[0] as TwoRegisterInstruction).registerA == (it[1] as OneRegisterInstruction).registerA
    }?.let { (it[0] as ReferenceInstruction).reference as? FieldReference }
        ?.takeIf { it.definingClass == SIM_BIT_RATE && it.type == "I" }
        ?: throw PatchException("$FEATURE: SimBitRate.getCodecType no longer just returns an int field of its own.")

    val write = accessor("setCodecType", listOf("I"), "V").implementation?.instructions?.toList().orEmpty()
    val writeField = (write.firstOrNull { it.opcode == Opcode.IPUT } as? ReferenceInstruction)?.reference as? FieldReference
    if (writeField?.toString() != readField.toString()) {
        throw PatchException("$FEATURE: SimBitRate.setCodecType no longer writes the field getCodecType reads.")
    }
    return readField
}

/**
 * The injection reads the list from p1, which is the list only on an instance method taking it
 * alone. The fingerprints pin the name and parameters; this pins the rest.
 */
internal fun requirePlayerGearSetter(method: Method) {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.implementation == null ||
        method.parameterTypes.map(CharSequence::toString) != listOf("Ljava/util/List;")
    ) {
        throw PatchException("$FEATURE: ${method.definingClass}.${method.name} is not an instance setter taking only the gear list.")
    }
}

@Suppress("unused")
val h264PlaybackPatch = bytecodePatch(
    name = "Prefer H.264 playback",
    description = "Plays videos in an older format your phone decodes more easily, which can " +
        "help older phones play smoothly and run cooler. Videos without it play as before. Starts " +
        "off. Turn it on in Metra settings > App behavior.",
    default = true,
) {
    category("Playback")
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())
    dependsOn(settingsPatch, sharedExtensionPatch)
    execute {
        requireGearCodecField { classDefByOrNull(it) }
        // The same doors Play SDR instead of HDR and Playback quality filter. All three hand the
        // list through H264Playback.preferredGears, so they agree whichever hook runs first.
        mapOf(
            SimVideoSetBitRateFingerprint to "filterPlayerVideoGears",
            SimVideoUrlModelSetBitRateFingerprint to "filterPlayerUrlModelGears",
        ).forEach { (fingerprint, callback) ->
            fingerprint.method.apply {
                requirePlayerGearSetter(this)
                addInstructions(0, """
                    invoke-static/range { p1 .. p1 }, $H264->$callback(Ljava/util/List;)Ljava/util/List;
                    move-result-object p1
                """)
            }
        }
        SettingsStatusLoadFingerprint.method.addInstruction(0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableH264Playback()V")
    }
}
