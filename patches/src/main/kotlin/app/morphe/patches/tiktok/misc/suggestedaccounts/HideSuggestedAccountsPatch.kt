import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.suggestedaccounts.ProfileHeaderMore
import app.morphe.patches.tiktok.misc.suggestedaccounts.InboxFooter
import app.morphe.patches.tiktok.misc.suggestedaccounts.InboxNewFollowersFooter
import app.morphe.patches.tiktok.misc.suggestedaccounts.InboxActivitiesFooter

internal const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/suggestedaccounts/HideSuggestedAccounts;"

@Suppress("unused")
val hideSuggestedAccountsPath = bytecodePatch(
    name = "Hide suggested accounts",
    description = "Removes suggested accounts from profile and inbox.  Supports TikTok 43.8.3.",
    default = true,
) {
    compatibleWith(*AppCompatibilities.tiktok4383())

    dependsOn(sharedExtensionPatch)

    execute {
        ProfileHeaderMore.method.shouldReturnVoidEarly()
        InboxFooter.method.shouldReturnVoidEarly()
        InboxNewFollowersFooter.method.shouldReturnVoidEarly()
        InboxActivitiesFooter.method.shouldReturnBoolEarly()
    }
}

private fun MutableMethod.shouldReturnVoidEarly() {
    shouldReturnEarly("return-void")
}

private fun MutableMethod.shouldReturnBoolEarly() {
    shouldReturnEarly("""
        const/4 v1, 0x0
        return v1
    """)
}

private fun MutableMethod.shouldReturnEarly(instructions: String) {
    addInstructions(
        0,
        """
            invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->enabled()Z
            move-result v0
    
            if-eqz v0, :disabled
            $instructions
            :disabled
            nop
        """,
    )
}