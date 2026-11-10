package app.morphe.patches.tiktok.misc.settings

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ACTION = "LX/ShareAction;"
private const val OTHER_ACTION = "LX/OtherAction;"
private const val KEY = "ad_browser_settings"

class SettingsIconSelectorTest {
    @Test
    fun `the renderer-selected icon wins over another resource returned by the action`() {
        val gear = 0x7f010900
        val action = action(
            "LX/AdBrowserSettings;",
            gear,
            alternate = 0x7f010001,
        )

        assertEquals(gear, resolveSettingsIconResourceId(renderer(), listOf(action)))
    }

    @Test
    fun `both ad browser implementations have to supply the same icon`() {
        val gear = 0x7f010088
        val carriers = listOf(
            action("LX/AdBrowserSettings;", gear),
            action("LX/AdBrowserSettingsForSpark;", gear, additionalIcon = 0x7f010999),
            action("LX/UnrelatedKey;", 0x7f010777, key = "share_setting"),
            action("LX/WrongInterface;", 0x7f010666, interfaceType = OTHER_ACTION),
        )

        assertEquals(gear, resolveSettingsIconResourceId(renderer(), carriers))
    }

    @Test
    fun `disagreeing host implementations fail instead of choosing one`() {
        val error = assertThrows(PatchException::class.java) {
            resolveSettingsIconResourceId(
                renderer(),
                listOf(
                    action("LX/First;", 0x7f010088),
                    action("LX/Second;", 0x7f010089),
                ),
            )
        }

        assertTrue(error.message!!.contains("one shared icon"))
    }

    @Test
    fun `a changed renderer contract fails before reading an arbitrary integer method`() {
        val renderer = method(
            "LX/IconRenderer;",
            "render",
            "V",
            """
                invoke-interface {v0}, $ACTION->icon()I
                move-result v0
                invoke-interface {v0}, $ACTION->other()I
                move-result v0
                return-void
            """,
        )

        val error = assertThrows(PatchException::class.java) {
            resolveSettingsIconResourceId(renderer, listOf(action("LX/Action;", 0x7f010088)))
        }

        assertTrue(error.message!!.contains("one interface icon call"))
    }

    @Test
    fun `a constant in another register is not mistaken for the returned icon`() {
        val error = assertThrows(PatchException::class.java) {
            resolveSettingsIconResourceId(
                renderer(),
                listOf(
                    action(
                        "LX/BrokenAction;",
                        0x7f010088,
                        iconInstructions = """
                            const v1, 0x7f010999
                            return v0
                        """,
                    ),
                ),
            )
        }

        assertTrue(error.message!!.contains("no direct integer return"))
    }

    @Test
    fun `a key string in another register does not admit an unrelated carrier`() {
        val error = assertThrows(PatchException::class.java) {
            resolveSettingsIconResourceId(
                renderer(),
                listOf(
                    action(
                        "LX/BrokenKeyAction;",
                        0x7f010088,
                        keyInstructions = """
                            const-string v1, "$KEY"
                            return-object v0
                        """,
                    ),
                ),
            )
        }

        assertTrue(error.message!!.contains("no $KEY action"))
    }

    private fun renderer() = method(
        "LX/IconRenderer;",
        "render",
        "V",
        """
            invoke-interface {v0}, $ACTION->icon()I
            move-result v0
            return-void
        """,
    )

    private fun action(
        type: String,
        icon: Int,
        alternate: Int = 0x7f010777,
        additionalIcon: Int? = null,
        iconInstructions: String? = null,
        keyInstructions: String? = null,
        key: String = KEY,
        interfaceType: String = ACTION,
    ): ClassDef {
        val iconBody = iconInstructions ?: if (additionalIcon == null) {
            """
                const v0, $icon
                return v0
            """
        } else {
            """
                const/4 v1, 0x0
                if-eqz v1, :normal_icon
                const v0, $additionalIcon
                return v0
                :normal_icon
                const v0, $icon
                return v0
            """
        }
        return ImmutableClassDef(
            type,
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            listOf(interfaceType),
            null,
            null,
            null,
            listOf(
                method(
                    type,
                    "key",
                    "Ljava/lang/String;",
                    keyInstructions ?: """
                        const-string v0, "$key"
                        return-object v0
                    """,
                ),
                method(
                    type,
                    "icon",
                    "I",
                    iconBody,
                ),
                method(
                    type,
                    "other",
                    "I",
                    """
                        const v0, $alternate
                        return v0
                    """,
                ),
            ),
        )
    }

    private fun method(
        owner: String,
        name: String,
        returnType: String,
        body: String,
    ): Method = MutableMethod(
        ImmutableMethod(
            owner,
            name,
            emptyList(),
            returnType,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            ImmutableMethodImplementation(2, emptyList(), null, null),
        ),
    ).apply {
        addInstructions(0, body)
    }
}
