package app.morphe.patches.tiktok.misc.navigation

import app.morphe.appendFixtureInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tab list getters, whose names R8 assigns and whose bodies are identical apart from the
 * method each calls to fill the list. What separates them is the scene that filler reports.
 */
class TabModelListAnchorTest {
    private val assem = "Lcom/ss/android/ugc/aweme/main/assems/tabs/TabAbilityAssem;"
    private val list = "Ljava/util/concurrent/CopyOnWriteArrayList;"

    @Test
    fun `each getter is picked by the scene the method filling its list reports`() {
        val top = getter("W52", "Io", "LL")
        val bottom = getter("OA", "Go", "LLILLL")
        val classDef = assem(top, bottom, filler("Io", "scene_get_top_tab"), filler("Go", "scene_get_bottom_tab"))

        assertTrue(matchesTop(top, classDef))
        assertFalse(matchesTop(bottom, classDef))
        assertTrue(matchesBottom(bottom, classDef))
        assertFalse(matchesBottom(top, classDef))
    }

    @Test
    fun `a getter that does more than hand back its list is not one of them`() {
        // Three of the five list getters on the class read their list, check it and rebuild it.
        val busy = MutableMethod(
            ImmutableMethod(
                assem, "r9", emptyList(), "Ljava/util/List;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(2, emptyList(), null, null),
            ),
        ).apply {
            appendFixtureInstructions(
                """
                    invoke-virtual { p0 }, $assem->Io()V
                    iget-object v0, p0, $assem->LLILZ:$list
                    invoke-virtual { v0 }, $list->isEmpty()Z
                    move-result v0
                    return-object v0
                """,
            )
        }
        val classDef = assem(busy, filler("Io", "scene_get_top_tab"))

        assertFalse(matchesTop(busy, classDef))
    }

    @Test
    fun `a getter that returns its list and then does more is not one of them`() {
        // The three instructions have to be the whole body. A longer method that opens with them
        // returns at the third, so what follows is reached by a branch and belongs to something
        // else this must not rewrite.
        val longer = MutableMethod(
            ImmutableMethod(
                assem, "W52", emptyList(), "Ljava/util/List;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(2, emptyList(), null, null),
            ),
        ).apply {
            appendFixtureInstructions(
                """
                    invoke-virtual { p0 }, $assem->Io()V
                    iget-object v0, p0, $assem->LL:$list
                    return-object v0
                    const/4 v0, 0x0
                    return-object v0
                """,
            )
        }
        val classDef = assem(longer, filler("Io", "scene_get_top_tab"))

        assertFalse(matchesTop(longer, classDef))
    }

    @Test
    fun `two getters answering to one scene are both refused`() {
        val first = getter("W52", "Io", "LL")
        val second = getter("r9", "Io", "LLILZ")
        val classDef = assem(first, second, filler("Io", "scene_get_top_tab"))

        assertFalse(matchesTop(first, classDef))
        assertFalse(matchesTop(second, classDef))
    }

    @Test
    fun `a filler that reports no scene answers to neither`() {
        val orphan = getter("W52", "Io", "LL")
        val classDef = assem(orphan, filler("Io", "scene_get_something_else"))

        assertFalse(matchesTop(orphan, classDef))
        assertFalse(matchesBottom(orphan, classDef))
    }

    private fun matchesTop(method: Method, classDef: ClassDef) =
        TopTabModelListFingerprint.custom!!(method, classDef)

    private fun matchesBottom(method: Method, classDef: ClassDef) =
        BottomTabModelListFingerprint.custom!!(method, classDef)

    /** Fill the list, read it, hand it back: the whole body of the two getters that matter. */
    private fun getter(name: String, fills: String, field: String) = MutableMethod(
        ImmutableMethod(
            assem, name, emptyList(), "Ljava/util/List;",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(2, emptyList(), null, null),
        ),
    ).apply {
        appendFixtureInstructions(
            """
                invoke-virtual { p0 }, $assem->$fills()V
                iget-object v0, p0, $assem->$field:$list
                return-object v0
            """,
        )
    }

    private fun filler(name: String, scene: String) = MutableMethod(
        ImmutableMethod(
            assem, name, emptyList(), "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(2, emptyList(), null, null),
        ),
    ).apply {
        appendFixtureInstructions(
            """
                const-string v0, "$scene"
                return-void
            """,
        )
    }

    private fun assem(vararg methods: Method) = ImmutableClassDef(
        assem,
        AccessFlags.PUBLIC.value,
        "Ljava/lang/Object;",
        null,
        null,
        null,
        null,
        methods.toList(),
    )
}
