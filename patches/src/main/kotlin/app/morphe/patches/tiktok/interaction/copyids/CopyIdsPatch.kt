/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.copyids

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.sharesheet.ShareSnapshotFingerprint
import app.morphe.patches.tiktok.interaction.sharesheet.resolveSharePanel
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/interaction/CopyIds;"
internal const val SIGNATURE_COMPONENT =
    "Lcom/ss/android/ugc/profile/platform/business/header/business/bio/business/signature/base/ProfileHeaderBaseSignatureComponent;"
internal const val TUX_TEXT_VIEW = "Lcom/bytedance/tux/input/TuxTextView;"
internal const val BASE_SHARE_PACKAGE = "Lcom/ss/android/ugc/aweme/share/base/model/BaseSharePackage;"
internal const val SHARE_PANEL_MARKER = "share_panel_action"
internal const val SHARE_APPS_MARKER = "share_panel_channel"

/**
 * The share sheet's apps panel attach: `0IQ2` on 47.1.4, a FrameLayout whose onAttachedToWindow
 * names `share_panel_channel` and inflates the apps list. The profile sheet in Settings and
 * privacy has only this panel and Send to, no action panel.
 */
internal fun resolveShareAppsPanel(classes: Iterable<ClassDef>): Method {
    val candidates = classes.flatMap { classDef ->
        classDef.methods.filter { method ->
            method.name == "onAttachedToWindow" && method.returnType == "V" && method.parameterTypes.isEmpty() &&
                !AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.implementation?.instructions?.any {
                    it.getReference<StringReference>()?.string == SHARE_APPS_MARKER
                } == true
        }.map { classDef to it }
    }
    val (owner, method) = candidates.singleOrNull()
        ?: throw PatchException("Copy bio and IDs: expected one share apps panel attach method, found ${candidates.size}.")
    if (owner.superclass != "Landroid/widget/FrameLayout;") {
        throw PatchException("Copy bio and IDs: the share apps panel is no longer a FrameLayout.")
    }
    return method
}

/**
 * The profile header's bio bind, handed the signature and its extra spans: `Q53` on 47.0.3, `D73`
 * on 47.1.3 and 47.1.4. It's the item's one public (String, List) method returning nothing.
 */
internal object BioBindFingerprint : Fingerprint(
    definingClass = SIGNATURE_COMPONENT,
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Ljava/util/List;"),
)

/** The text view the bio item draws its signature in: its one TuxTextView field. */
internal fun ClassDef.bioTextField(): Field? = fields.singleOrNull {
    !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == TUX_TEXT_VIEW
}

/** The share model builder's one BaseSharePackage field, the package the sheet is built from. */
internal fun ClassDef.sharePackageField(): Field? = fields.singleOrNull {
    !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == BASE_SHARE_PACKAGE
}

@Suppress("unused")
val copyIdsPatch = bytecodePatch(
    name = "Copy bio and IDs",
    description = "Lets you long-press a bio to copy it, and adds share sheet buttons that copy a username, user ID or video ID. On by default. Turn it off in Metra settings > App behavior.",
    default = true,
) {
    category("Interaction")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableCopyIds()V",
        )

        val bioText = classDefBy(SIGNATURE_COMPONENT).bioTextField()
            ?: throw PatchException("Copy bio and IDs: $SIGNATURE_COMPONENT has no single text view field.")
        BioBindFingerprint.method.apply {
            // p0 and p1 are copied down first, so a larger frame can't push them past v15.
            if (implementation!!.registerCount - 3 < 2) {
                throw PatchException("Copy bio and IDs: the bio bind has no two free registers.")
            }
            addInstructions(
                0,
                """
                    move-object/from16 v0, p0
                    iget-object v0, v0, $SIGNATURE_COMPONENT->${bioText.name}:$TUX_TEXT_VIEW
                    move-object/from16 v1, p1
                    invoke-static { v0, v1 }, $EXTENSION->onBio(Landroid/view/View;Ljava/lang/String;)V
                """,
            )
        }

        // The share model's constructor, before its super call: only p1, the builder, is read.
        ShareSnapshotFingerprint.method.apply {
            val builder = parameterTypes.single().toString()
            val packageField = classDefBy(builder).sharePackageField()
                ?: throw PatchException("Copy bio and IDs: $builder holds no single BaseSharePackage.")
            if (implementation!!.registerCount - 2 < 1) {
                throw PatchException("Copy bio and IDs: the share model has no free register.")
            }
            addInstructions(
                0,
                """
                    move-object/from16 v0, p1
                    iget-object v0, v0, $builder->${packageField.name}:$BASE_SHARE_PACKAGE
                    invoke-static { v0 }, $EXTENSION->onSharePackage(Ljava/lang/Object;)V
                """,
            )
        }

        val panel = resolveSharePanel(classDefByStrings(SHARE_PANEL_MARKER))
        mutableClassDefBy(panel.definingClass).findMutableMethodOf(panel).addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION->onSharePanel(Landroid/view/View;)V",
        )
        val apps = resolveShareAppsPanel(classDefByStrings(SHARE_APPS_MARKER))
        mutableClassDefBy(apps.definingClass).findMutableMethodOf(apps).addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION->onAppsPanel(Landroid/view/View;)V",
        )
    }
}
