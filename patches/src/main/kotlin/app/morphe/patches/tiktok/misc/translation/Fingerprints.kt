/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.misc.translation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.methodCall
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal object MultiCommentTranslationStartFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/util/List;", "L", "Z"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/ss/android/ugc/aweme/comment/translation/MultiTranslationBody;",
        ),
    ),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.getReference<MethodReference>()?.definingClass ==
                "Lcom/ss/android/ugc/aweme/comment/translation/MultiTranslationBody;"
        } == true
    },
)

internal object BaseCommentCellBindFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/commentv2/commentlist/powercell/BaseCommentCell;",
    returnType = "V",
    parameters = listOf("L"),
    strings = listOf("comment_panel").map { string(it, StringComparisonType.CONTAINS) },
    filters = listOf(
        methodCall(
            definingClass = "this",
            returnType = "Lcom/ss/android/ugc/aweme/comment/model/Comment;",
        ),
    ),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.getReference<MethodReference>()?.let { reference ->
                reference.definingClass ==
                    "Lcom/ss/android/ugc/aweme/commentv2/commentlist/powercell/BaseCommentCell;" &&
                    reference.returnType == "Lcom/ss/android/ugc/aweme/comment/model/Comment;"
            } == true
        } == true
    },
)

internal object CommentListLoadedFingerprint : Fingerprint(
    returnType = "V",
    // The filter repeats a reference the custom block requires, so the patcher reads only the
    // classes that make it (#54).
    filters = listOf(
        fieldAccess(definingClass = "Lcom/ss/android/ugc/aweme/comment/model/CommentItemList;", name = "lazySplitItemsParseTask"),
    ),
    custom = { method, _ ->
        val fields = method.implementation?.instructions?.mapNotNull { instruction ->
            instruction.getReference<FieldReference>()
        } ?: emptyList()
        fields.any { reference ->
            reference.definingClass == "Lcom/ss/android/ugc/aweme/comment/model/CommentItemList;" &&
                reference.name == "items" &&
                reference.type == "Ljava/util/List;"
        } && fields.any { reference ->
            reference.definingClass == "Lcom/ss/android/ugc/aweme/comment/model/CommentItemList;" &&
                reference.name == "lazySplitItemsParseTask"
        }
    },
)

/** TikTok's one read of feed_translation_reverse, the lazy that keeps it for the process (#143). */
internal object FeedTranslationReverseFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf(),
    strings = listOf(FEED_TRANSLATION_REVERSE).map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> method.feedTranslationReverseRead() != null },
)
