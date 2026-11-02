/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/interaction/downloads/Fingerprints.kt
 */
package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal object AclCommonShareFingerprint : Fingerprint(
    definingClass = "/ACLCommonShare;",
    name = "getCode",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "I",
)

internal object AclCommonShare2Fingerprint : Fingerprint(
    definingClass = "/ACLCommonShare;",
    name = "getShowType",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "I",
)

internal object AclCommonShare3Fingerprint : Fingerprint(
    definingClass = "/ACLCommonShare;",
    name = "getTranscode",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "I",
)

// The five below name their class and not their method. An obfuscated method name is a
// counter-assigned label that the next TikTok build is free to reshuffle, and it was the only
// thing here that would have moved. Each is unique on 46.2.3 without it, checked method by method
// over the whole class on 2026-09-09: of the three (Context, String) methods returning a Uri, one
// holds "video/mp4" and one "video/*" and the third neither; of the two (Context, String, String)
// ones, one holds "/Camera" and the other "image/*"; and the six-parameter copy is the only
// method of its shape on its class at all. What stays is the class, the signature and the strings,
// which is what every other fingerprint in this tree is anchored on.

/**
 * The media-store helper the download patch works through. Its class was written into all six of
 * the fingerprints below as `/0L4Q;`, and it is `LX/0LWq;` on 46.7.3 and `LX/0LEM;` on 46.8.3.
 * Its own method names have not moved, and each of the six is named by its signature together
 * with the SQL selection, the media type or the `MediaStore` table it works on, every one of
 * which resolves to a single method on all three builds without the class.
 *
 * <p>Where a downloaded video is written.
 */
internal object VideoDownloadUriFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/net/Uri;",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
    strings = listOf("/", "/Camera", "/Camera/", "video/mp4").map { string(it, StringComparisonType.CONTAINS) },
)

internal object AwemeGetVideoFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Lcom/ss/android/ugc/aweme/feed/model/Video;",
    custom = { method, classDef ->
        classDef.endsWith("/Aweme;") &&
            method.name == "getVideo" &&
            method.parameterTypes.isEmpty()
    },
)

internal fun Method.drawsCommentImageWatermark(): Boolean =
    implementation?.instructions?.any { instruction ->
        val reference = instruction.getReference<MethodReference>() ?: return@any false
        instruction.opcode == Opcode.INVOKE_VIRTUAL &&
            reference.definingClass == "Landroid/graphics/Canvas;" &&
            reference.name == "drawBitmap" &&
            reference.parameterTypes == listOf(
                "Landroid/graphics/Bitmap;",
                "F",
                "F",
                "Landroid/graphics/Paint;",
            ) && reference.returnType == "V"
    } == true

internal object CommentImageWatermarkFingerprint : Fingerprint(
    strings = listOf("[tiktok_logo]").map { string(it, StringComparisonType.CONTAINS) },
    parameters = listOf("Landroid/graphics/Bitmap;"),
    returnType = "V",
    custom = { method, _ -> method.drawsCommentImageWatermark() },
)

/**
 * Strings match by substring, so "/", "/Camera" and "/Camera/" are all satisfied by the
 * lookup method's own "/Camera/" and told the two apart only by which the patcher visited
 * first. The lookup is the one that queries by relative path and MIME type; this one must not.
 */
internal object PhotoDownloadUriFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/net/Uri;",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;", "Ljava/lang/String;"),
    strings = listOf("/", "/Camera", "/Camera/").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ ->
        method.implementation?.instructions?.none { instruction ->
            val string = instruction.getReference<StringReference>()?.string ?: return@none false
            string == "image/*" || string.contains("relative_path=?")
        } == true
    },
)

internal object VideoLookupUriFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/net/Uri;",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
    strings = listOf("/Camera/", "video/*", "(relative_path=? OR relative_path=?) AND _display_name=?").map { string(it, StringComparisonType.CONTAINS) },
)

internal object PhotoLookupUriFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/net/Uri;",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;", "Ljava/lang/String;"),
    strings = listOf("/Camera/", "image/*", "(relative_path=? OR relative_path=?) AND _display_name=?").map { string(it, StringComparisonType.CONTAINS) },
)

internal object VideoMediaStoreInsertFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/net/Uri;",
    parameters = listOf(
        "Landroid/content/Context;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/provider/MediaStore\$Video\$Media;",
            name = "getContentUri",
        ),
    ),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.getReference<MethodReference>()?.let { reference ->
                reference.definingClass == "Landroid/provider/MediaStore\$Video\$Media;" &&
                    reference.name == "getContentUri"
            } == true
        } == true
    },
)

internal object PhotoMediaStoreInsertFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/net/Uri;",
    parameters = listOf(
        "Landroid/content/Context;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
    ),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/provider/MediaStore\$Images\$Media;",
            name = "getContentUri",
        ),
    ),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.getReference<MethodReference>()?.let { reference ->
                reference.definingClass == "Landroid/provider/MediaStore\$Images\$Media;" &&
                    reference.name == "getContentUri"
            } == true
        } == true
    },
)

/**
 * The copy that puts a saved image or video into the gallery.
 *
 * <p>It was `LX/0L4G;` and it delegated to `LX/0L4Q;`, both written here. They are `LX/0LEA;` and
 * `LX/0LEM;` on 46.8.3. The six-parameter signature and the three media types together name one
 * method on all three builds without either, and what is left of the delegation check is that the
 * two MediaStore inserts it calls are on one class, whatever that class is called.
 */
internal object ImagePostMediaCopyFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Landroid/net/Uri;",
    parameters = listOf(
        "Landroid/content/Context;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z",
        "Ljava/lang/String;",
        "I",
    ),
    strings = listOf("/Camera/", "video/mp4", "image/jpeg").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ ->
        val inserts = method.implementation?.instructions
            ?.mapNotNull { it.getReference<MethodReference>() }
            ?.filter {
                it.returnType == "Landroid/net/Uri;" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf(
                        "Landroid/content/Context;",
                        "Ljava/lang/String;",
                        "Ljava/lang/String;",
                        "Ljava/lang/String;",
                    )
            }
            .orEmpty()
        val distinct = inserts.distinctBy { "${it.definingClass}->${it.name}" }
        distinct.size >= 2 && distinct.map { it.definingClass }.distinct().size == 1
    },
)

/**
 * TikTok's save of a comment photo: an R8-named class and method, but the one method in the app
 * that asks for storage under the "bpea-comment_photo_save_request_storage" policy. It unboxes
 * the tapped photo's index, reads the comment, and hands its downloader the still's UrlModel
 * alone, which is where a live photo's clip is lost (upstream #169).
 */
internal object CommentPhotoSaveFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    strings = listOf("bpea-comment_photo_save_request_storage").map { string(it, StringComparisonType.CONTAINS) },
)

internal object StickerPreviewSourceFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Ljava/lang/String;",
        "Lcom/ss/android/ugc/aweme/im/common/model/StickerItem;",
        "Landroid/view/View;",
        "Z",
        "Ljava/lang/String;",
        "Ljava/util/Map;",
        "Lkotlin/jvm/functions/Function0;",
        "Lkotlin/jvm/functions/Function0;",
        "Lkotlin/jvm/functions/Function0;",
    ),
    // The parameter list names StickerItem, which TikTok does not rename, and no other method
    // in any of the three builds shares it. The class was `LX/0UL9;` on 46.2.3 and is `LX/0WU6;`
    // and `LX/0m0M;` on the two builds since, its own name `LJ` throughout, so what is left is
    // that it calls the preview bind, by that bind's shape rather than by its name.
    //
    // Instance, because the source hook reads the StickerItem out of p2 and a static method of
    // the same declared parameter list would have the View there.
    custom = { method, _ ->
        AccessFlags.STATIC.value and method.accessFlags == 0 &&
            method.implementation?.instructions?.any { instruction ->
                instruction.getReference<MethodReference>()?.isStickerPreviewBind() == true
            } == true
    },
)

internal object DownloadSuccessCoroutineFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    strings = listOf(
        "DownloadAction@71a5.startDownload\$globalListener\$1\$onSuccess\$1",
        "filePath",
        "fileExist",
    ).map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> method.name == "invokeSuspend" },
)
