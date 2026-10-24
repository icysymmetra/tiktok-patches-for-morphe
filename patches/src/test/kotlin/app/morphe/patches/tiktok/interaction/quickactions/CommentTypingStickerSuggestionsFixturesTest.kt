package app.morphe.patches.tiktok.interaction.quickactions

import app.morphe.Fixtures
import app.morphe.ResourceIds
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import org.junit.Assert.assertEquals
import org.junit.Test

/** Contract for the component that owns automatic comment sticker suggestions, on each declared build. */
class CommentTypingStickerSuggestionsFixturesTest {
    @Test
    fun `typing recommendation component has one boolean visibility boundary on each declared build`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val components = container.dexEntryNames.flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes.filter {
                    it.type == COMMENT_TYPING_STICKER_RECOMMEND_ASSEM
                }
            }
            assertEquals("one typing recommendation component", 1, components.size)

            val component = components.single()
            val visibilityMethods = component.methods.filter { method ->
                method.returnType == "V" &&
                    method.parameterTypes.map(CharSequence::toString) == listOf("Z") &&
                    method.implementation != null
            }
            assertEquals("one boolean visibility boundary", 1, visibilityMethods.size)

            // The view keeps its real name, typing_sticker_recommend_view, on every build; its
            // number moves (0x7f0a9680 on 47.0.3, 0x7f0a9780 on 47.1.3).
            val viewId = ResourceIds.read(apk)["com.zhiliaoapp.musically"]?.get(TYPING_STICKER_RECOMMEND_VIEW)?.singleOrNull()
                ?: error("${apk.name} has no single id named $TYPING_STICKER_RECOMMEND_VIEW")
            val viewBindings = component.methods.sumOf { method ->
                method.implementation?.instructions?.count { instruction ->
                    (instruction as? NarrowLiteralInstruction)?.narrowLiteral == viewId ||
                        (instruction as? WideLiteralInstruction)?.wideLiteral == viewId.toLong()
                } ?: 0
            }
            assertEquals("one typing sticker recommendation view binding", 1, viewBindings)
        }
    }
}

private const val TYPING_STICKER_RECOMMEND_VIEW = "typing_sticker_recommend_view"
