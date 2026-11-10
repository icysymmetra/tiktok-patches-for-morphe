/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal const val COMMENT_SURVEY_ITEM = "Lcom/ss/android/ugc/aweme/comment/experiment/CommentSurveyDataItem;"
internal const val COMMENT_SURVEY_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/comment/CommentSurvey;"

/**
 * TikTok's comment survey config: the one static, parameterless method that answers the survey a
 * comment list may show, read from the server's comment_survey setting, or null when there's none.
 * Null is already its everyday answer, so every reader takes it as no survey. The survey model
 * keeps its name; the getter is 0GlX.LIZIZ on 47.0.3, 0FPt.LIZIZ on 47.1.3 and 0FPx.LIZIZ on 47.1.4.
 */
internal object CommentSurveyConfigFingerprint : Fingerprint(
    returnType = COMMENT_SURVEY_ITEM,
    parameters = emptyList(),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)
