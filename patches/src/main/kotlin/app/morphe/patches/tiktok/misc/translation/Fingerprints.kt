package app.morphe.patches.tiktok.misc.translation

import app.morphe.patcher.Fingerprint
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal object TranslationKevaReadFingerprint : Fingerprint(
    definingClass = "/TranslationKevaServiceImpl;",
    name = "LIZIZ",
    returnType = "Z",
    parameters = emptyList(),
    strings = listOf("key_one_click_translation_active"),
)

internal object TranslationKevaWriteFingerprint : Fingerprint(
    definingClass = "/TranslationKevaServiceImpl;",
    name = "LJIIIIZZ",
    returnType = "V",
    parameters = listOf("Z"),
    strings = listOf("key_one_click_translation_active"),
)

internal object TranslationKevaClearFingerprint : Fingerprint(
    definingClass = "/TranslationKevaServiceImpl;",
    name = "LIZLLL",
    returnType = "V",
    parameters = emptyList(),
    strings = listOf("key_one_click_translation_active"),
)

internal object CommentTranslationReevaluateFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    strings = listOf(
        "reEvaluateTranslationState requestType:",
        ", shouldTranslate:",
        ", overrideStatus:",
    ),
)

internal object CommentAllToggleEmit25Fingerprint : Fingerprint(
    definingClass = "/AgS243S0100000_11;",
    name = "emit\$25",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("LY/AgS243S0100000_11;", "Ljava/lang/Object;", "LX/02vj;"),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.getReference<FieldReference>()?.name == "COMMENT_ALL_TOGGLE"
        } == true
    },
)

internal object CommentAllToggleEmit26Fingerprint : Fingerprint(
    definingClass = "/AgS243S0100000_11;",
    name = "emit\$26",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("LY/AgS243S0100000_11;", "Ljava/lang/Object;", "LX/02vj;"),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.getReference<FieldReference>()?.name == "COMMENT_ALL_TOGGLE"
        } == true
    },
)

internal object CommentTranslationStateEligibilityFingerprint : Fingerprint(
    definingClass = "/AgS243S0100000_11;",
    name = "emit\$37",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("LY/AgS243S0100000_11;", "Ljava/lang/Object;", "LX/02vj;"),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.getReference<MethodReference>()?.let { reference ->
                reference.name == "setTranslationState" ||
                    (reference.parameterTypes == listOf("Lcom/ss/android/ugc/aweme/comment/model/Comment;") &&
                        reference.returnType == "Z")
            } == true
        } == true
    },
)

internal object TranslationServiceCommentTranslatableFingerprint : Fingerprint(
    definingClass = "/TranslationServiceImpl;",
    name = "LJIIJ",
    returnType = "Z",
    parameters = listOf("Lcom/ss/android/ugc/aweme/comment/model/Comment;"),
)

internal object TranslationServiceCommentModeEligibleFingerprint : Fingerprint(
    definingClass = "/TranslationServiceImpl;",
    name = "LJIILIIL",
    returnType = "Z",
    parameters = listOf(
        "Lcom/ss/android/ugc/aweme/comment/model/Comment;",
        "LX/0Plo;",
    ),
)

internal object TranslationServiceCommentLanguageAllowedFingerprint : Fingerprint(
    definingClass = "/TranslationServiceImpl;",
    name = "LJJIIJZLJL",
    returnType = "Z",
    parameters = listOf("Lcom/ss/android/ugc/aweme/comment/model/Comment;"),
)

internal object TranslationServiceSeeTranslationClickFingerprint : Fingerprint(
    definingClass = "/TranslationServiceImpl;",
    name = "LJJJJLI",
    returnType = "V",
    parameters = listOf(
        "Lcom/ss/android/ugc/aweme/comment/model/Comment;",
        "Z",
    ),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.getReference<FieldReference>()?.name == "TRIGGER_BY_CLICK_SEE_TRANSLATION_SOMETIMES"
        } == true
    },
)

internal object TranslationServiceSingleStringRequestFingerprint : Fingerprint(
    definingClass = "/TranslationServiceImpl;",
    name = "LJIIIZ",
    returnType = "LX/0x7U;",
    parameters = listOf("Ljava/lang/String;"),
)

internal object TranslationServiceMultipleStringRequestFingerprint : Fingerprint(
    definingClass = "/TranslationServiceImpl;",
    name = "LJIIIIZZ",
    returnType = "LX/0x7U;",
    parameters = listOf("Ljava/lang/String;", "Ljava/util/List;"),
)

internal object TranslationServiceAwemeTranslationRequestFingerprint : Fingerprint(
    definingClass = "/TranslationServiceImpl;",
    name = "LJIIJJI",
    returnType = "LX/0x7U;",
    parameters = listOf(
        "J",
        "Lcom/ss/android/ugc/aweme/feed/model/Aweme;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
    ),
)

internal object TranslationServiceFutureRequestFingerprint : Fingerprint(
    definingClass = "/TranslationServiceImpl;",
    name = "LLIIII",
    returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;"),
)

internal object TranslationServiceModeStateWriteFingerprint : Fingerprint(
    definingClass = "/TranslationServiceImpl;",
    name = "LJI",
    returnType = "V",
    parameters = listOf("LX/0Plo;", "Ljava/lang/String;"),
)

internal object TranslationServiceModeStateBroadcastFingerprint : Fingerprint(
    definingClass = "/TranslationServiceImpl;",
    name = "LJJJLZIJ",
    returnType = "V",
    parameters = listOf("LX/0Plo;", "Ljava/lang/String;"),
)

internal object MultiCommentTranslationStartFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/util/List;", "L", "Z"),
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
    strings = listOf("comment_panel"),
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

/**
 * Completion callback of the native multi-comment translation batch.
 * 46.2.3: static synthetic run$N(self) on a shared runnable class; 47.1.4: dedicated Runnable.run().
 * The text variant (Comment.setText) is selected over the audio-transcript variant.
 */
internal object MultiCommentTranslationCompleteFingerprint : Fingerprint(
    name = "run",
    returnType = "V",
    parameters = emptyList(),
    strings = listOf("MultiCommentTranslationTask startTranslate onComplete "),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.getReference<MethodReference>()?.let { reference ->
                reference.definingClass == "Lcom/ss/android/ugc/aweme/comment/model/Comment;" &&
                    reference.name == "setText"
            } == true
        } == true
    },
)

internal object MultiCommentTranslationCacheCopyFingerprint : Fingerprint(
    definingClass = "LX/0Pwq;",
    name = "LJI",
    returnType = "V",
    parameters = listOf(
        "Lcom/ss/android/ugc/aweme/comment/model/Comment;",
        "Lcom/ss/android/ugc/aweme/comment/model/Comment;",
        "Z",
    ),
)

internal object MultiCommentTranslationStateFingerprint : Fingerprint(
    definingClass = "LX/0Pwq;",
    name = "LJ",
    returnType = "V",
    parameters = listOf(
        "Lcom/ss/android/ugc/aweme/comment/model/Comment;",
        "Z",
    ),
)

internal object CommentSetTextFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/comment/model/Comment;",
    name = "setText",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
)

internal object CommentSetOriginalTextFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/comment/model/Comment;",
    name = "setOriginalText",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
)

internal object CommentSetTranslatedTextFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/comment/model/Comment;",
    name = "setTranslatedText",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
)

internal object CommentSetTranslatedFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/comment/model/Comment;",
    name = "setTranslated",
    returnType = "V",
    parameters = listOf("Z"),
)

internal object CommentSetTranslateVariantFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/comment/model/Comment;",
    name = "setTranslateVariant",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
)

internal object CommentSetTransBtnStyleFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/comment/model/Comment;",
    name = "setTransBtnStyle",
    returnType = "V",
    parameters = listOf("I"),
)
