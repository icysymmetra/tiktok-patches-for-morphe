package app.morphe.patches.tiktok.misc.navigation

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoneForYouHeaderAnchorTest {
    private val owner =
        "Lcom/ss/android/ugc/aweme/homepage/ui/view/tab/top/TopTabLayoutAbility;"

    @Test
    fun `the hook selects only the tab layout constructor with its icon sibling`() {
        assertTrue(isTopTabLayoutConstructor(constructor("Landroid/widget/FrameLayout;")))
        assertFalse(isTopTabLayoutConstructor(constructor("Landroid/view/View;")))
        assertFalse(isTopTabLayoutConstructor(ImmutableMethod(
            "LX/Other;", "<init>",
            listOf("LX/Tabs;", "Landroid/widget/FrameLayout;").map {
                ImmutableMethodParameter(it, null, null)
            }, "V",
            AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(3, emptyList(), null, null),
        )))
    }

    private fun constructor(secondParameter: String) = ImmutableMethod(
        owner,
        "<init>",
        listOf("LX/Tabs;", secondParameter).map {
            ImmutableMethodParameter(it, null, null)
        },
        "V",
        AccessFlags.PUBLIC.value,
        null,
        null,
        ImmutableMethodImplementation(3, emptyList(), null, null),
    )
}
