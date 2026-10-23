/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.profile

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/profile/FollowStatus;"
internal const val BASE_UI_COMPONENT = "Lcom/ss/android/ugc/profile/platform/base/component/BaseUIComponent;"
internal const val PROFILE_COMMON_INFO = "Lcom/ss/android/ugc/profile/platform/base/data/ProfileCommonInfo;"
internal const val HEADER_TEXT_ITEM =
    "Lcom/ss/android/ugc/profile/platform/business/header/business/info/business/userinfo/base/CommonInfoBaseUIComponent;"
internal const val RELATION_USER_CELL =
    "Lcom/ss/android/ugc/profile/business/ur/following/ui/viewholder/assem/RelationUserCellAssem;"
internal const val REUSED_SLOT_ASSEM = "Lcom/bytedance/assem/arch/reused/ReusedUISlotAssem;"
private const val VIEW = "Landroid/view/View;"

/**
 * The bind of each text item TikTok's server lays a profile header out with: the @username, the
 * name, labels and the rest all go through it. On 47.0.3 (`os`), 47.1.3 and 47.1.4 (`vs`) it is
 * the one public, non-final, no-argument method of the item besides onCreate, and the one that
 * sets the item's text.
 */
internal object ProfileHeaderTextBindFingerprint : Fingerprint(
    definingClass = HEADER_TEXT_ITEM,
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = emptyList(),
    custom = { method, _ -> method.name != "onCreate" && method.setsText() },
)

/**
 * The bind of a follower or following list cell, handed the list item that holds the account
 * (`onBind` on 47.0.3, `z4` on 47.1.3 and 47.1.4). It casts its argument first thing.
 */
internal object RelationCellBindFingerprint : Fingerprint(
    definingClass = RELATION_USER_CELL,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    custom = { method, _ -> method.implementation?.instructions?.firstOrNull()?.opcode == Opcode.CHECK_CAST },
)

internal fun Method.setsText() = implementation?.instructions?.any { instruction ->
    instruction.getReference<MethodReference>()?.let {
        it.definingClass == "Landroid/widget/TextView;" && it.name == "setText" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/CharSequence;")
    } == true
} == true

/** The header item's way to its profile's common info: the one no-argument getter of that type. */
internal fun ClassDef.commonInfoGetter(): Method? = methods.singleOrNull {
    !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == PROFILE_COMMON_INFO
}

/** The view a header item draws in: the one View field of the item's base class. */
internal fun ClassDef.itemViewField(): Field? = fields.singleOrNull {
    !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == VIEW
}

@Suppress("unused")
val showFollowStatusPatch = bytecodePatch(
    name = "Show follow status",
    description = "Shows on a profile whether that person follows you back, and marks accounts in your follow lists that don't. On by default. Turn it off in Metra settings > App behavior. Optional local counts and appearance controls apply to your own profile.",
    default = true,
) {
    category("Interaction")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableFollowStatus()V",
        )

        val base = classDefBy(BASE_UI_COMPONENT)
        val commonInfo = base.commonInfoGetter()
            ?: throw PatchException("Show follow status: $BASE_UI_COMPONENT has no single profile info getter.")
        val itemView = base.itemViewField()
            ?: throw PatchException("Show follow status: $BASE_UI_COMPONENT has no single view field.")
        ProfileHeaderTextBindFingerprint.method.apply {
            if (implementation!!.registerCount < 3) {
                throw PatchException("Show follow status: the header text bind has no two free registers.")
            }
            // p0 sits above v15 in this method, so it is copied down before the 4-bit instructions.
            addInstructions(
                0,
                """
                    move-object/from16 v0, p0
                    invoke-virtual { v0 }, $BASE_UI_COMPONENT->${commonInfo.name}()$PROFILE_COMMON_INFO
                    move-result-object v1
                    iget-object v0, v0, $BASE_UI_COMPONENT->${itemView.name}:$VIEW
                    invoke-static { v0, v1 }, $EXTENSION->onHeaderText(${VIEW}Ljava/lang/Object;)V
                """,
            )
        }
        RelationCellBindFingerprint.method.apply {
            if (implementation!!.registerCount < 3) {
                throw PatchException("Show follow status: the follow list cell bind has no free register.")
            }
            addInstructions(
                0,
                """
                    invoke-virtual { p0 }, $REUSED_SLOT_ASSEM->getContentView()$VIEW
                    move-result-object v0
                    invoke-static { v0, p1 }, $EXTENSION->onRelationCell(${VIEW}Ljava/lang/Object;)V
                """,
            )
        }
        hookLocalProfileCounts()
        hookLocalProfileAppearance()
    }
}
