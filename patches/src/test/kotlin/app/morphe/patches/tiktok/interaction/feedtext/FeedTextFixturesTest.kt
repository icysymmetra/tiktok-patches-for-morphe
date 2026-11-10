package app.morphe.patches.tiktok.interaction.feedtext

import app.morphe.Fixtures
import app.morphe.ResourceIds
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterLiveness
import app.morphe.util.addInstruction
import app.morphe.util.cloneMutable
import app.morphe.patcher.util.getReference
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual vendor binders, fields, cardinality and branch/register safety. Native taps need devices. */
class FeedTextFixturesTest {
    @Test
    fun `all declared hosts resolve native owners and safely size before line breaking`() {
        val expected = mapOf(
            "47.4.3" to Triple("LX/09Bg;", "LX/07Yl;", 0x7f0a206a to 0x7f0a882c),
        )
        assertEquals(Fixtures.declaredVersions().toSet(), expected.keys)
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
            val byType = classes.associateBy { it.type }
            val native = resolveFeedText { byType[it] }
            val (controller, builder, ids) = expected.getValue(version)
            assertEquals(controller, native.controller.type)
            assertEquals(builder, native.builder.type)
            val resources = ResourceIds.read(apk).getValue("com.zhiliaoapp.musically")
            assertEquals(listOf(ids.first), resources.getValue("desc"))
            assertEquals(listOf(ids.second), resources.getValue("title"))
            assertEquals(POST_TIME_IDS.toList(), resources.getValue("tv_post_time"))
            assertEquals(2, native.sizeInputs.size)
            assertEquals(listOf(2, 10), native.sizeInputs.map { it.builderRegister })
            assertEquals(listOf(87, 277), native.sizeInputs.map { it.index })
            assertEquals(29, native.layoutFactory.implementation!!.registerCount)
            assertEquals("LIZJ", native.builderPaint.name)
            assertEquals("LJIIJ", native.builderSize.name)
            assertEquals("LJFF", native.builderCache.name)
            assertEquals(6, native.layoutCaches.size)
            assertEquals("LJLLL", native.refreshOriginal.name)
            assertEquals("LJLLLLLL", native.refreshTranslated.name)
            assertEquals("Oq", native.authorBind.name)
            assertEquals("LLLIIIL", native.authorView.name)
            assertTrue(native.authorLoader.implementation!!.instructions.any {
                it is NarrowLiteralInstruction && it.narrowLiteral == ids.second
            })
            // The post date beside the name: the same owner loads it, as the same kind of text view.
            assertEquals("LLLIIL", native.dateView.name)
            assertEquals(AUTHOR, native.dateView.definingClass)
            assertEquals(native.authorView.type, native.dateView.type)
            val loaderCode = native.authorLoader.implementation!!.instructions.toList()
            assertTrue("the date is stored before the name, where the view-created hook reads both",
                loaderCode.indexOfFirst { it.getReference<FieldReference>()?.toString() == native.dateView.toString() } <
                    native.authorStore)
            native.sizeInputs.forEach { input ->
                assertFalse(input.ownerLocal in RegisterLiveness.of(native.layoutFactory).liveInto(input.index))
                assertTrue(input.ownerLocal < 16)
            }
            val mutable = MutableMethod(native.layoutFactory)
            val before = mutable.implementation!!.instructions.toList()
            val branches = before.filterIsInstance<OffsetInstruction>().map { branch ->
                val source = before.single { it === branch }
                branch to before.single { it.location.codeAddress == source.location.codeAddress + branch.codeOffset }
            }
            mutable.markDescriptionBuilders(native.sizeInputs)
            val after = mutable.implementation!!.instructions.toList()
            assertEquals(before.size + 4, after.size)
            assertEquals(29, mutable.implementation!!.registerCount)
            assertEquals(2, after.count { it.getReference<MethodReference>()?.name == "descriptionBuilder" })
            branches.forEach { (branch, target) ->
                val source = after.single { it === branch }
                assertSame(target, after.single { it.location.codeAddress == source.location.codeAddress + branch.codeOffset })
            }
            val dispatch = MutableMethod(native.layoutDispatch)
            dispatch.bypassDescriptionCache(native.bypass)
            val flow = ControlFlow.of(dispatch)
            val fresh = flow.instructions.indexOfFirst { it.getReference<MethodReference>()?.name == "freshDescription" }
            val branch = fresh + 2
            assertEquals(Opcode.IF_NEZ, flow.instructions[branch].opcode)
            val fallback = flow.normal[branch].single { target ->
                flow.instructions[target].getReference<MethodReference>()?.toString() == native.layoutFactory.toString()
            }
            assertEquals(listOf(3, 4, 5, 6, 7, 8), flow.instructions[fallback].namedRegisters())
            val author = MutableMethod(native.authorBind)
            val returns = author.implementation!!.instructions.count { it.opcode == Opcode.RETURN_VOID }
            author.bindAuthorSize()
            val authorCode = author.implementation!!.instructions.toList()
            assertEquals("authorBinding", authorCode.first().getReference<MethodReference>()?.name)
            val enter = authorCode.first() as RegisterRangeInstruction
            assertEquals(2, enter.registerCount)
            assertTrue(enter.startRegister > 15)
            assertEquals(returns, authorCode.count { it.getReference<MethodReference>()?.name == "authorOwnerBound" })
            authorCode.indices.filter { authorCode[it].opcode == Opcode.RETURN_VOID }.forEach {
                assertEquals("authorOwnerBound", authorCode[it - 1].getReference<MethodReference>()?.name)
            }
            // A changed owner or extra font input must fail before any patch mutation.
            assertThrows(PatchException::class.java) { resolveFeedText { if (it == DESCRIPTION) null else byType[it] } }
            val duplicate = MutableClass(native.controller)
            duplicate.methods.add(native.layoutFactory.cloneMutable(name = "anotherDescriptionFactory"))
            rejects(byType, duplicate)
            val thirdInput = MutableClass(native.controller)
            val altered = thirdInput.methods.single { it.name == native.layoutFactory.name }
            val firstInput = native.sizeInputs.first().index
            val font = native.layoutFactory.implementation!!.instructions.elementAt(firstInput - 1).getReference<MethodReference>()!!
            altered.addInstruction(firstInput, "invoke-virtual { v2, v3 }, $font")
            rejects(byType, thirdInput)
            val missingFont = MutableClass(native.builder)
            missingFont.methods.removeAll { it.name == font.name }
            rejects(byType, missingFont)
        }
    }

    private fun rejects(original: Map<String, ClassDef>, changed: ClassDef) {
        assertThrows(PatchException::class.java) {
            resolveFeedText { if (it == changed.type) changed else original[it] }
        }
    }
}
