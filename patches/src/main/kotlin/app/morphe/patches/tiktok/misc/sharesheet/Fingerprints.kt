package app.morphe.patches.tiktok.misc.sharesheet

import app.morphe.patcher.Fingerprint

// Share panel snapshot holder: X.0oVo(X.0oVp) in TikTok 46.2.3, X.0IKT(X.0ID0) in 47.1.4.
internal object SharePanelSnapshotConstructorFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    parameters = listOf("L"),
    strings = listOf("click_to_respond_duration", "config_duration"),
)
