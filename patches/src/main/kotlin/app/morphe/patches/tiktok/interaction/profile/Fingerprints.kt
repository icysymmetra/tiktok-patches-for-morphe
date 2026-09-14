package app.morphe.patches.tiktok.interaction.profile

import app.morphe.patcher.Fingerprint
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal object ProfileViewReportFingerprint : Fingerprint(
    custom = { method, classDef ->
        classDef.endsWith("/ProfilePlatformViewModel;") &&
            method.implementation?.instructions?.any { instruction ->
                instruction.getReference<MethodReference>()?.let { reference ->
                    reference.definingClass == "Lcom/ss/android/ugc/profile/business/ci/viewer/api/ProfileViewerApiService;" &&
                        reference.name == "reportView"
                } == true
            } == true
    },
)
