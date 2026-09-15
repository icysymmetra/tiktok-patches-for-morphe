package app.morphe.patches.tiktok.interaction.story

import app.morphe.patcher.Fingerprint
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal object StoryViewReportFingerprint : Fingerprint(
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.getReference<MethodReference>()?.let { reference ->
                reference.definingClass == "Lcom/ss/android/ugc/aweme/story/api/StoryApi;" &&
                    reference.name == "reportStoryViewed"
            } == true
        } == true
    },
)

