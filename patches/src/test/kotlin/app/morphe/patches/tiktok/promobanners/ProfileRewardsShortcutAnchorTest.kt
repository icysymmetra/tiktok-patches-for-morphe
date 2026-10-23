package app.morphe.patches.tiktok.promobanners

import app.morphe.appendFixtureInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRewardsShortcutAnchorTest {
    private val owner = "LX/ProfileRewardsBinder;"

    @Test
    fun `the profile rewards binder is anchored by its real model icon and view id write`() {
        assertTrue(isProfileRewardsIconBinder(binder(profileModel = true)))
        assertFalse(isProfileRewardsIconBinder(binder(profileModel = false)))
    }

    private fun binder(profileModel: Boolean) = MutableMethod(
        ImmutableMethod(
            owner,
            "invoke",
            listOf(owner, "Ljava/lang/Object;").map {
                ImmutableMethodParameter(it, null, null)
            },
            "Ljava/lang/Object;",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            ImmutableMethodImplementation(5, emptyList(), null, null),
        ),
    ).apply {
        val model = if (profileModel) {
            "Lcom/bytedance/touchpoint/api/model/ProfileActivityIcon;"
        } else {
            "LX/UnrelatedModel;"
        }
        appendFixtureInstructions(
            """
                move-object v0, p1
                check-cast v0, Lcom/bytedance/tux/icon/TuxIconView;
                const v1, 0x7f0a8a54
                invoke-virtual { v0, v1 }, Landroid/view/View;->setId(I)V
                iget-object v1, v2, $model->iconUrl:Ljava/lang/String;
                return-object v0
            """,
        )
    }
}
