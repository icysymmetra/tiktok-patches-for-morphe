/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.translation

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ReceivedDmTranslationFixturesTest {
    @Test fun `all reflected received owners and untouched native menu returns qualify on declared hosts`() = withClasses { classes ->
        val targets = findReceivedPreviewTargets(classes::get)
        assertEquals(RECEIVED_ASSEM, targets.bind.definingClass)
        assertEquals(RECEIVED_MENU, targets.menu.definingClass)
        assertEquals(2, targets.bind.receivedBindReturns().size)
        val native = targets.menu.implementation!!.instructions.toList()
        val patched = MutableMethod(targets.menu)
        patched.appendReceivedMenu(targets.result)
        val code = patched.implementation!!.instructions.toList()
        assertEquals(native.size + 2, code.size)
        assertEquals("appendAction", code[targets.result.returned].getReference<MethodReference>()!!.name)
        assertEquals(3, code.count { it.opcode == Opcode.RETURN_OBJECT })
        val originalCalls = native.mapNotNull { it.getReference<MethodReference>() }
        assertEquals(originalCalls, code.mapNotNull { it.getReference<MethodReference>() }.filter { it.definingClass != RECEIVED_PREVIEW })
        assertTrue(originalCalls.any { it.definingClass == "Lcom/bytedance/ies/powerlist/PowerCell;" && it.name == "getItem" })
    }

    @Test fun `ordinary untranslated text uses the same received model with nullable translation metadata`() = withClasses { classes ->
        // 47.4.3 obfuscates TextTranslateProtocol, so pin it by the skeleton protocol it implements and the cell it registers.
        val protocol = classes.getValue("LX/0dfS;")
        assertTrue("Lcom/ss/android/ugc/aweme/im/sdk/chat/messagelist/skeleton/protocol/SkeletonMessageListProtocol;" in protocol.interfaces)
        assertTrue(protocol.methods.single { it.name == "<init>" }.implementation!!.instructions.any {
            it.opcode == Opcode.CONST_CLASS && it.getReference<TypeReference>()?.type == RECEIVED_CELL
        })
        val enabled = protocol.methods.single { it.name == "isEnabled" }
        assertEquals(listOf(Opcode.CONST_4, Opcode.RETURN), enabled.implementation!!.instructions.map { it.opcode })
        val receive = protocol.methods.single { it.name == "LIZLLL" }
        val code = receive.implementation!!.instructions.toList()
        val offsets = code.runningFold(0) { offset, instruction -> offset + instruction.codeUnits }
        val metadata = code.indexOfFirst { it.getReference<MethodReference>()?.let { reference ->
            reference.definingClass == "LX/0CHK;" && reference.name == "LJIIIIZZ"
        } == true }
        assertTrue(metadata >= 0)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, code[metadata + 1].opcode)
        assertEquals(Opcode.IF_EQZ, code[metadata + 2].opcode)
        assertEquals((code[metadata + 1] as OneRegisterInstruction).registerA, (code[metadata + 2] as OneRegisterInstruction).registerA)
        val destination = offsets[metadata + 2] + (code[metadata + 2] as OffsetInstruction).codeOffset
        val language = code.indexOfFirst { it.getReference<FieldReference>()?.name == "sourceLanguage" }
        assertTrue(language > metadata && destination > offsets[language])
        val constructor = code.indexOfFirst { it.getReference<MethodReference>()?.let { reference -> reference.definingClass == "LX/0dfZ;" && reference.name == "<init>" } == true }
        assertTrue(destination < offsets[constructor])
        val model = classes.getValue("LX/0dfZ;")
        assertTrue("LX/0dfc;" in model.interfaces)
        assertTrue(model.methods.single { it.name == "LJIILL" }.fields().any { it.name == "LLJJIJIIJIL" && it.type == "Ljava/lang/CharSequence;" })
        assertTrue(model.methods.single { it.name == "LLIIJLIL" }.calls().any { it.definingClass == "LX/0dfb;" && it.name == "LIZJ" })
    }

    @Test fun `native menu styles custom text and invokes only the added callback`() = withClasses { classes ->
        for (owner in listOf("LX/19Ho;", "LX/19Hp;")) {
            val binder = classes.getValue(owner).methods.single { it.name == "onBindViewHolder" }
            assertTrue(binder.implementation!!.instructions.any { it.opcode == Opcode.CHECK_CAST && it.getReference<TypeReference>()?.type == "LX/18gz;" })
            assertTrue(binder.fields().any { it.definingClass == "LX/18gz;" && it.name == "LIZ" && it.type == "Ljava/lang/String;" })
            assertTrue(binder.calls().any { it.definingClass == "Landroid/widget/TextView;" && it.name == "setText" })
            assertTrue(binder.calls().any { it.definingClass == "Lcom/bytedance/tux/icon/TuxIconView;" && it.name == "setIconRes" })
        }
        val dispatch = classes.getValue("Lpt6/AbS697S0100000_31_I1;")
        for (name in listOf("LIZ\$8", "LIZ\$9")) {
            val method = dispatch.methods.single { it.name == name }
            assertEquals(listOf("Lkotlin/jvm/functions/Function1;->invoke"), method.calls().map { "${it.definingClass}->${it.name}" })
            assertEquals(listOf("LJII"), method.fields().filter { it.definingClass == "LX/18pP;" }.map { it.name })
            assertFalse(method.calls().any { it.name.contains("send", true) || it.name.contains("translate", true) })
        }
    }

    @Test fun `native text session and current model remain distinct from alignment and sent row owners`() = withClasses { classes ->
        val assem = classes.getValue(RECEIVED_ASSEM)
        val rendering = assem.methods.single { it.name == "Zp" }
        assertTrue(rendering.fields().any { it.definingClass == RECEIVED_ASSEM && it.name == "LLJJL" && it.type == "LX/04QJ;" })
        assertTrue(rendering.fields().any { it.definingClass == "LX/0YZ7;" && it.name == "LL" && it.type.endsWith("/SessionInfo;") })
        val getter = assem.methods.single { it.name == "mq" }
        assertTrue(getter.fields().any { it.name == "LLJJLIIIJLLLLLLLZ" && it.type == "LX/04QJ;" })
        assertTrue(classes.getValue("LX/0dfB;").fields.any { it.name == "LL" && it.type == "Landroid/view/View;" })
        val skeleton = classes.getValue("Lcom/ss/android/ugc/aweme/im/sdk/chat/messagelist/skeleton/cell/IMSkeletonLayoutReusedUISlotAssem;")
        assertTrue(skeleton.methods.single { it.name == "hq" }.calls().any {
            it.name == "pq" && it.returnType == "Lcom/ss/android/ugc/aweme/views/InterceptingConstraintLayout;"
        })
        val unbind = assem.methods.single { it.name == "unBind" }
        assertEquals(listOf("cq"), unbind.calls().map { it.name })
        val cancel = assem.methods.single { it.name == "cq" }
        assertTrue(cancel.calls().any { it.name == "dispose" && it.definingClass == "LX/06P3;" })
        assertTrue(cancel.calls().any { it.definingClass == "Lkotlinx/coroutines/JobSupport;" && it.name == "LIZ" })
        assertTrue(classes.getValue(RECEIVED_ROW).superclass == RECEIVED_ASSEM)
        assertFalse(RECEIVED_ROW == "${RECEIVED_CONTENT}TextTranslateSendAssem;")
    }

    @Test fun `changed received subtype session ownership and native menu constructors fail before wiring`() = withClasses { classes ->
        val menu = classes.getValue(RECEIVED_MENU)
        val noCell = replace(menu, fields = menu.fields.filterNot { it.name == "LLLILZ" })
        assertThrows(PatchException::class.java) { findReceivedPreviewTargets((classes + (menu.type to noCell))::get) }
        val received = classes.getValue(RECEIVED_ROW)
        val wrongRow = replace(received, superclass = "${RECEIVED_CONTENT}TextTranslateSendAssem;")
        assertThrows(PatchException::class.java) { findReceivedPreviewTargets((classes + (received.type to wrongRow))::get) }
        val holder = classes.getValue("LX/0YZ7;")
        val noSession = replace(holder, fields = holder.fields.filterNot { it.name == "LL" })
        assertThrows(PatchException::class.java) { findReceivedPreviewTargets((classes + (holder.type to noSession))::get) }
        val item = classes.getValue("LX/18pP;")
        val noFactory = replace(item, methods = item.methods.filterNot { it.name == "<init>" })
        assertThrows(PatchException::class.java) { findReceivedPreviewTargets((classes + (item.type to noFactory))::get) }
    }

    @Test fun `both declared preview plans qualify before installation and either missing owner remains fatal`() = withClasses { classes ->
        val plans = findDmPreviewPlans(true, classes::get)
        assertNotNull(plans.outgoing)
        assertNotNull(plans.received)
        val editor = MutableMethod(plans.outgoing!!.viewCreated)
        val menu = MutableMethod(plans.received!!.menu)
        val beforeEditor = editor.implementation!!.instructions.toList()
        val beforeMenu = menu.implementation!!.instructions.toList()
        val lookup = classes + listOf(editor, menu).associate { method ->
            method.definingClass to replace(classes.getValue(method.definingClass), methods = classes.getValue(method.definingClass).methods.map {
                if (it.name == method.name && it.parameterTypes == method.parameterTypes) method else it
            })
        }
        var omitted = 0
        for (missing in listOf(DM_ASSEM, RECEIVED_MENU)) {
            assertThrows(PatchException::class.java) {
                findDmPreviewPlans(true, { if (it == missing) null else lookup[it] }) { _, _ -> omitted++ }
            }
            assertEquals(beforeEditor, editor.implementation!!.instructions.toList())
            assertEquals(beforeMenu, menu.implementation!!.instructions.toList())
        }
        assertEquals(0, omitted)
    }

    @Test fun `an undeclared host omits only its unavailable UI feature`() = withClasses { classes ->
        val omitted = mutableListOf<String>()
        val noComposer = findDmPreviewPlans(false, { if (it == DM_ASSEM) null else classes[it] }) { feature, _ -> omitted += feature }
        assertNull(noComposer.outgoing)
        assertNotNull(noComposer.received)
        assertEquals(listOf("DM draft preview"), omitted)
        omitted.clear()
        val noReceived = findDmPreviewPlans(false, { if (it == RECEIVED_MENU) null else classes[it] }) { feature, _ -> omitted += feature }
        assertNotNull(noReceived.outgoing)
        assertNull(noReceived.received)
        assertEquals(listOf("received message preview"), omitted)
    }

    @Test fun `incoming qualification independently requires the shared request language cancellation and response ABIs`() = withClasses { classes ->
        val defects = listOf(
            "LX/03j7;" to replace(classes.getValue("LX/03j7;"), methods = classes.getValue("LX/03j7;").methods.filterNot { it.name == "LLFII" }),
            "LX/1O0d;" to replace(classes.getValue("LX/1O0d;"), methods = classes.getValue("LX/1O0d;").methods.filterNot { it.name == "LIZ" }),
            "LX/1O0f;" to replace(classes.getValue("LX/1O0f;"), methods = classes.getValue("LX/1O0f;").methods.filterNot { it.name == "LJ" }),
            "LX/02tl;" to replace(classes.getValue("LX/02tl;"), methods = classes.getValue("LX/02tl;").methods.filterNot { it.name == "LIZ" }),
            "LX/02tm;" to replace(classes.getValue("LX/02tm;"), methods = classes.getValue("LX/02tm;").methods.filterNot { it.name == "onSubscribe" }),
            "LX/02ty;" to replace(classes.getValue("LX/02ty;"), methods = classes.getValue("LX/02ty;").methods.filterNot { it.name == "dispose" }),
            "Lcom/ss/android/ugc/aweme/translation/model/MultiTranslationResultDM;".let { type ->
                type to replace(classes.getValue(type), fields = classes.getValue(type).fields.filterNot { it.name == "results" })
            },
            "Lcom/ss/android/ugc/aweme/translation/model/TranslationResultDM;".let { type ->
                type to replace(classes.getValue(type), fields = classes.getValue(type).fields.filterNot { it.name == "originalText" })
            },
        )
        for ((type, changed) in defects) {
            val lookup = classes + (type to changed)
            assertThrows(PatchException::class.java) { findReceivedPreviewTargets(lookup::get) }
            assertThrows(PatchException::class.java) { findDmPreviewPlans(true, lookup::get) }
            val omitted = mutableListOf<String>()
            val optional = findDmPreviewPlans(false, lookup::get) { feature, _ -> omitted += feature }
            assertNull(optional.outgoing)
            assertNull(optional.received)
            assertEquals(listOf("DM draft preview", "received message preview"), omitted)
            val withoutComposer = findDmPreviewPlans(false, { if (it == DM_ASSEM) null else lookup[it] })
            assertNull(withoutComposer.received)
        }
    }

    @Test fun `undeclared host policy never suppresses an unexpected qualification failure`() {
        val failure = IllegalStateException("unexpected reader failure")
        val actual = assertThrows(IllegalStateException::class.java) {
            findDmPreviewPlans(false, { throw failure }) { _, _ -> throw AssertionError("Unexpected failures must not be omitted") }
        }
        assertSame(failure, actual)
    }

    @Test fun `missing shared account broadcasts omit both previews and strict hosts fail before mutation`() = withClasses { classes ->
        val plans = findDmPreviewPlans(true, classes::get)
        assertNotNull(plans.accounts)
        val editor = MutableMethod(plans.outgoing!!.viewCreated)
        val menu = MutableMethod(plans.received!!.menu)
        val editorBefore = editor.implementation!!.instructions.toList()
        val menuBefore = menu.implementation!!.instructions.toList()
        val lookup = classes + (DM_ASSEM to replace(classes.getValue(DM_ASSEM),
            methods = classes.getValue(DM_ASSEM).methods.filterNot { it == plans.outgoing.viewCreated } + editor)) +
            (RECEIVED_MENU to replace(classes.getValue(RECEIVED_MENU),
                methods = classes.getValue(RECEIVED_MENU).methods.filterNot { it == plans.received.menu } + menu))
        for (missing in listOf(DM_ACCOUNT_BROADCASTER, DM_ACCOUNT_LISTENER)) {
            assertThrows(PatchException::class.java) { findDmPreviewPlans(true, { if (it == missing) null else lookup[it] }) }
            val omitted = mutableListOf<String>()
            val optional = findDmPreviewPlans(false, { if (it == missing) null else lookup[it] }) { feature, _ -> omitted += feature }
            assertNull(optional.outgoing); assertNull(optional.received); assertNull(optional.accounts)
            assertEquals(listOf("DM draft preview", "received message preview"), omitted)
            assertEquals(editorBefore, editor.implementation!!.instructions.toList())
            assertEquals(menuBefore, menu.implementation!!.instructions.toList())
            val incomingOnly = findDmPreviewPlans(false, { if (it == missing || it == DM_ASSEM) null else lookup[it] })
            assertNull(incomingOnly.received); assertNull(incomingOnly.accounts)
        }
    }

    @Test fun `independent previews share one account plan and never depend on late SDK logout`() = withClasses { classes ->
        for (missing in listOf(DM_ASSEM, RECEIVED_MENU, "LX/0BSI;")) {
            val plans = findDmPreviewPlans(false, { if (it == missing) null else classes[it] })
            assertNotNull(plans.accounts)
            assertEquals(missing != DM_ASSEM, plans.outgoing != null)
            assertEquals(missing != RECEIVED_MENU, plans.received != null)
        }
        val neither = findDmPreviewPlans(false, {
            when (it) {
                DM_ASSEM, RECEIVED_MENU -> null
                DM_ACCOUNT_BROADCASTER, DM_ACCOUNT_LISTENER -> throw AssertionError("No preview needs an account hook")
                else -> classes[it]
            }
        })
        assertNull(neither.accounts)
    }

    @Test fun `an unexpected shared account reader error stays fatal on optional hosts`() = withClasses { classes ->
        val failure = IllegalStateException("unexpected account reader failure")
        val actual = assertThrows(IllegalStateException::class.java) {
            findDmPreviewPlans(false, { if (it == DM_ACCOUNT_BROADCASTER) throw failure else classes[it] }) {
                _, _ -> throw AssertionError("Unexpected shared errors must not be omitted")
            }
        }
        assertSame(failure, actual)
    }

    private fun Method.calls() = implementation?.instructions?.mapNotNull { it.getReference<MethodReference>() }.orEmpty()
    private fun Method.fields() = implementation?.instructions?.mapNotNull { it.getReference<FieldReference>() }.orEmpty()
    private fun replace(owner: ClassDef, superclass: String? = owner.superclass,
        fields: Iterable<com.android.tools.smali.dexlib2.iface.Field> = owner.fields,
        methods: Iterable<Method> = owner.methods) = ImmutableClassDef(owner.type, owner.accessFlags, superclass,
        owner.interfaces, owner.sourceFile, owner.annotations, fields, methods)
    private fun withClasses(block: (Map<String, ClassDef>) -> Unit) {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence().flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.associateBy { it.type }
            block(classes)
        }
    }
}
