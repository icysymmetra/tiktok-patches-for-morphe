/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.resume

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val CONTINUE_CONFIG =
    "Lcom/ss/android/ugc/aweme/feed/experiment/FeedPlayProgressContinueConfig;"

/**
 * The lambda that answers whether a video resumes where it was left.
 *
 * <p>Its class was written here as `LX/0Lyr;`, which is `LX/0MGs;` on 46.7.3 and `LX/0M77;` on
 * 46.8.3. What it reads is not renamed: `FeedPlayProgressContinueConfig.enable`, and exactly one
 * `invoke()` in the app reads it on each of the three builds.
 */
internal object FeedProgressContinueGateFingerprint : Fingerprint(
    name = "invoke",
    // The filter repeats a reference the custom block requires, so the patcher reads only the
    // classes that make it (#54).
    filters = listOf(fieldAccess(definingClass = CONTINUE_CONFIG, name = "enable")),
    returnType = "Ljava/lang/Object;",
    parameters = emptyList(),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.getReference<FieldReference>()?.let { field ->
                field.definingClass == CONTINUE_CONFIG && field.name == "enable"
            } == true
        } == true
    },
)

internal object FeedPlayCompletedFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    custom = { method, classDef ->
        classDef.type == "Lcom/ss/android/ugc/aweme/feed/controller/PlayerController;" &&
            method.name == "onPlayCompleted"
    },
)

internal object FeedPlayProgressFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "J", "J"),
    custom = { method, classDef ->
        classDef.type == "Lcom/ss/android/ugc/aweme/feed/controller/PlayerController;" &&
            method.name == "onPlayProgressChange"
    },
)

// The method that answers "where should this video start", read out of the 46.2.3 fixture. The
// signature alone is unique across the app's 1,872,612 methods; the string is one of three
// landscape tags it compares and is here so a build that renames the method still fails loudly
// rather than binding to something else.
internal object FeedProgressResumePositionFingerprint : Fingerprint(
    returnType = "J",
    parameters = listOf("Lcom/ss/android/ugc/aweme/feed/model/Aweme;", "Ljava/lang/String;"),
    strings = listOf("landscape_change_keep_tag").map { string(it, StringComparisonType.CONTAINS) },
)
