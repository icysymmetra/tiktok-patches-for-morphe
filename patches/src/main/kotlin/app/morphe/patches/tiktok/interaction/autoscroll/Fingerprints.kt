package app.morphe.patches.tiktok.interaction.autoscroll

import app.morphe.patcher.Fingerprint

internal object AutoScrollFeatureGateFingerprint : Fingerprint(
    definingClass = "Lczc/o1;",
    name = "LIZ",
    returnType = "Z",
    parameters = emptyList(),
    strings = listOf("fyp_auto_scroll"),
)

internal object AutoScrollActionFactoryFingerprint : Fingerprint(
    definingClass = "LX/0oi7;",
    name = "LJI",
    returnType = "LX/0oe7;",
    parameters = listOf("LX/0oeK;"),
    strings = listOf("panel_auto_scroll", "auto_scroll"),
)
