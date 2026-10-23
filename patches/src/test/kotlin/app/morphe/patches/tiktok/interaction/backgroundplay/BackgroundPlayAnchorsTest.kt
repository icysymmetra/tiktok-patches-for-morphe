/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.backgroundplay

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Keep playing in the background changes, held to each declared build: the one lazy that
 * reads the mode hands back the int it hooks, and every place TikTok loads the remembered
 * switch's key either reads it through a hook or stores it, so no read is left on TikTok's say.
 * The replay (#99) hooks one end of a background video and presses the Play the notification does.
 */
class BackgroundPlayAnchorsTest {
    private fun Instruction.loads(key: String) =
        (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
            getReference<StringReference>()?.string == key

    @Test
    fun `the mode read and the remembered switch reads resolve on each build`() {
        Fixtures.forEachDeclared { apk ->
            val classes = HashMap<String, ClassDef>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) classes.putIfAbsent(classDef.type, classDef)
            }
            val version = Fixtures.versionOf(apk)
            val methods: List<Method> = classes.values.flatMap { it.methods }.filter { it.implementation != null }

            val gate = classes.values.flatMap { classDef -> classDef.methods.filter { BackgroundPlayGateReadFingerprint.takes(it, classDef) } }
            assertEquals("$version: the gate read takes ${gate.map { it.definingClass }}", 1, gate.size)
            val gateBody = gate.single().implementation!!.instructions.toList()
            val key = gateBody.indexOfFirst { it.loads(GATE_KEY) }
            val read = (key + 1 until gateBody.size).first { gateBody[it].getReference<MethodReference>()?.returnType == "I" }
            val result = gateBody[read + 1]
            assertEquals("$version: the gate's int is not kept", Opcode.MOVE_RESULT, result.opcode)
            // The int the patch changes is the one the lazy boxes and hands back.
            val box = gateBody[read + 2]
            assertEquals("$version: the gate's int is not what the lazy returns",
                "Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;", box.getReference<MethodReference>().toString())
            assertEquals((result as OneRegisterInstruction).registerA, (box as FiveRegisterInstruction).registerC)

            // The fingerprint's candidates: the key's string plus a Keva getBoolean anywhere.
            val candidates = methods.filter { method ->
                val body = method.implementation!!.instructions
                body.any { it.loads(REMEMBERED_KEY) } && body.any {
                    val call = it.getReference<MethodReference>()
                    call?.definingClass == KEVA && call.name == "getBoolean"
                }
            }
            var hooked = 0
            for (method in candidates) {
                val body = method.implementation!!.instructions.toList()
                for (at in body.indices.filter { body.readsKevaBoolean(it, REMEMBERED_KEY) }) {
                    assertEquals("$version: a read in ${method.definingClass}->${method.name} is not kept",
                        Opcode.MOVE_RESULT, body.getOrNull(at + 1)?.opcode)
                    hooked++
                }
            }
            assertTrue("$version: $hooked remembered reads, the controller and per-video checks need 2", hooked >= 2)

            // Every other load of the key feeds a store, not a read the patch would miss.
            val unhooked = methods.flatMap { method ->
                val body = method.implementation!!.instructions.toList()
                body.indices.filter { at ->
                    body[at].loads(REMEMBERED_KEY) &&
                        (at + 1..minOf(at + 3, body.size - 1)).none { body.readsKevaBoolean(it, REMEMBERED_KEY) } &&
                        (at + 1..minOf(at + 3, body.size - 1)).none { body[it].getReference<MethodReference>()?.let { call ->
                            call.definingClass == KEVA && call.name.startsWith("store")
                        } == true }
                }.map { "${method.definingClass}->${method.name}@$it" }
            }
            assertTrue("$version: the remembered switch is read where no hook is: $unhooked", unhooked.isEmpty())
        }
    }

    @Test
    fun `the scene check and its photo post check resolve on each build`() {
        Fixtures.forEachDeclared { apk ->
            val classes = HashMap<String, ClassDef>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) classes.putIfAbsent(classDef.type, classDef)
            }
            val version = Fixtures.versionOf(apk)

            val found = classes.values.flatMap { classDef -> classDef.methods.filter { BackgroundPlaySceneCheckFingerprint.takes(it, classDef) } }
            assertEquals("$version: the scene check takes ${found.map { it.definingClass }}", 1, found.size)
            val check = found.single()
            assertTrue("$version: the scene check is not static", AccessFlags.STATIC.isSet(check.accessFlags))
            val body = check.implementation!!.instructions.toList()
            val lookup = body.indexOfFirst { instruction ->
                instruction.opcode == Opcode.INVOKE_INTERFACE && instruction.getReference<MethodReference>()?.let {
                    it.definingClass == "Ljava/util/Set;" && it.name == "contains"
                } == true
            }
            val call = body[lookup] as FiveRegisterInstruction

            // The set it looks in is TikTok's scene list, filled with the feeds' event types.
            val load = body.subList(0, lookup).last { it.opcode == Opcode.SGET_OBJECT && (it as OneRegisterInstruction).registerA == call.registerC }
            val list = load.getReference<FieldReference>()!!
            val listed = classes.getValue(list.definingClass).methods.single { it.name == "<clinit>" }.implementation!!.instructions
                .mapNotNull { instruction -> instruction.getReference<StringReference>()?.string?.takeIf { instruction.loads(it) } }
            assertTrue("$version: ${list.definingClass} lists $listed",
                listed.containsAll(listOf("homepage_hot", "homepage_follow", "others_homepage")))

            // What it looks up is the event type, the second parameter, and the answer lands elsewhere.
            assertEquals("$version: the lookup is not of the event type",
                check.implementation!!.registerCount - check.parameters.size + 1, call.registerD)
            val result = body[lookup + 1]
            assertEquals("$version: the lookup's answer is not kept", Opcode.MOVE_RESULT, result.opcode)
            assertNotEquals(call.registerD, (result as OneRegisterInstruction).registerA)

            // Photo posts are ruled out here (47.0.3) or in the one post check it calls (47.1.x).
            val photoCheck = if (body.photoModeCall() >= 0) body else {
                val helpers = body.filter { it.isStaticAwemeCheck() }.mapNotNull { instruction ->
                    val reference = instruction.getReference<MethodReference>()!!
                    classes[reference.definingClass]?.methods?.firstOrNull {
                        it.name == reference.name && it.parameterTypes.map(CharSequence::toString) == listOf(AWEME) && it.returnType == "Z"
                    }?.implementation?.instructions?.toList()
                }.filter { it.photoModeCall() >= 0 }
                assertEquals("$version: post checks ruling out photo posts", 1, helpers.size)
                helpers.single()
            }
            assertEquals("$version: the photo post check is not kept", Opcode.MOVE_RESULT, photoCheck[photoCheck.photoModeCall() + 1].opcode)

            // It's the check the per-video read of the remembered switch leads to.
            val callers = classes.values.flatMap { it.methods }.filter { method ->
                method.implementation?.instructions?.any {
                    val reference = it.getReference<MethodReference>()
                    reference?.definingClass == check.definingClass && reference.name == check.name &&
                        reference.parameterTypes.map(CharSequence::toString) == check.parameterTypes.map(CharSequence::toString)
                } == true
            }
            assertTrue("$version: no remembered-switch read calls the scene check", callers.any { method ->
                val instructions = method.implementation!!.instructions.toList()
                instructions.indices.any { instructions.readsKevaBoolean(it, REMEMBERED_KEY) }
            })
        }
    }

    @Test
    fun `the page's claim on the sound resolves on each build`() {
        Fixtures.forEachDeclared { apk ->
            val classes = HashMap<String, ClassDef>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) classes.putIfAbsent(classDef.type, classDef)
            }
            val version = Fixtures.versionOf(apk)

            val found = classes.values.flatMap { classDef -> classDef.methods.filter { PageAudioFocusFingerprint.takes(it, classDef) } }
            assertEquals("$version: the page's claim takes ${found.map { it.definingClass }}", 1, found.size)
            val claim = found.single()
            assertFalse("$version: the page's claim is static", AccessFlags.STATIC.isSet(claim.accessFlags))
            assertTrue("$version: the page's claim has no local for the guard",
                claim.implementation!!.registerCount - claim.parameters.size - 1 >= 1)

            // A transient claim on the music stream, with a listener that does nothing.
            val body = claim.implementation!!.instructions.toList()
            val request = body.indexOfFirst { instruction ->
                instruction.getReference<MethodReference>()?.parameterTypes?.map(CharSequence::toString) ==
                    listOf(AUDIO_MANAGER, FOCUS_LISTENER, "I", "I")
            }
            val call = body[request] as FiveRegisterInstruction
            fun literal(register: Int) = body.subList(0, request).last {
                it is OneRegisterInstruction && it.registerA == register
            }.let { (it as? NarrowLiteralInstruction)?.narrowLiteral }
            assertEquals("$version: the stream", 3, literal(call.registerE))
            assertEquals("$version: the kind of claim", 2, literal(call.registerF))
            val listener = body.subList(0, request).last {
                it.opcode == Opcode.IGET_OBJECT && (it as OneRegisterInstruction).registerA == call.registerD
            }.getReference<FieldReference>()!!.type
            val onChange = classes.getValue(listener).methods.single { it.name == "onAudioFocusChange" }
            assertEquals("$version: $listener does something with a focus change",
                listOf(Opcode.RETURN_VOID), onChange.implementation!!.instructions.map { it.opcode })

            // The feed's page resume is one of its callers.
            assertTrue("$version: no page resume calls the claim", classes.values.flatMap { it.methods }.any { method ->
                val instructions = method.implementation?.instructions?.toList() ?: return@any false
                instructions.any { it.getReference<StringReference>()?.string?.let { text ->
                    text.startsWith("PageAudioProcessor") && text.contains(".onPageResumed")
                } == true } && instructions.any {
                    it.getReference<MethodReference>()?.let { reference ->
                        reference.definingClass == claim.definingClass && reference.name == claim.name &&
                            reference.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;")
                    } == true
                }
            })
        }
    }

    @Test
    fun `the background session's end and the Play the replay presses resolve on each build`() {
        Fixtures.forEachDeclared { apk ->
            val classes = HashMap<String, ClassDef>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) classes.putIfAbsent(classDef.type, classDef)
            }
            val version = Fixtures.versionOf(apk)

            val found = classes.values.flatMap { classDef -> classDef.methods.filter { BackgroundPlayEndFingerprint.takes(it, classDef) } }
            assertEquals("$version: the background session's end takes ${found.map { it.definingClass }}", 1, found.size)
            val end = found.single()
            val shape = resolveBackgroundEnd(end, classes::get)

            // The hook is a plain invoke-static of the answer and the listener, and the listener
            // is the register the method reads its session from.
            val listener = end.implementation!!.registerCount - 2
            assertTrue("$version: the answer sits in v${shape.singleRegister}", shape.singleRegister <= 15)
            assertTrue("$version: the listener sits in v$listener", listener <= 15)
            val body = end.implementation!!.instructions.toList()
            val read = body.first { instruction ->
                instruction.opcode == Opcode.IGET_OBJECT && instruction.getReference<FieldReference>()?.let {
                    it.definingClass == shape.session.definingClass && it.name == shape.session.name
                } == true
            }
            assertEquals("$version: the listener is not where the hook reads it", listener, (read as TwoRegisterInstruction).registerB)
            assertEquals("$version: the answer is not what TikTok branches on", Opcode.IF_EQZ, body[shape.singleResult + 1].opcode)

            // The session and its listener belong together.
            val session = classes.getValue(shape.play.definingClass)
            assertTrue("$version: ${session.type} doesn't hold ${end.definingClass}", session.fields.any { it.type == end.definingClass })

            // TikTok's media notification presses that Play with the reason the bridge passes.
            val owners = setOf(session.type) + session.interfaces
            val pressed = classes.values.flatMap { it.methods }.any { method ->
                val instructions = method.implementation?.instructions?.toList() ?: return@any false
                instructions.indices.any { at ->
                    val call = instructions[at]
                    (call.opcode == Opcode.INVOKE_VIRTUAL || call.opcode == Opcode.INVOKE_INTERFACE) &&
                        call.getReference<MethodReference>()?.let {
                            it.definingClass in owners && it.name == shape.play.name &&
                                it.parameterTypes.map(CharSequence::toString) == listOf("I")
                        } == true &&
                        instructions.subList(0, at).lastOrNull { previous ->
                            previous is OneRegisterInstruction && previous.registerA == (call as FiveRegisterInstruction).registerD
                        }?.let { (it as? NarrowLiteralInstruction)?.narrowLiteral } == NOTIFICATION_PLAY
                }
            }
            assertTrue("$version: nothing presses ${shape.play.name} with $NOTIFICATION_PLAY", pressed)
        }
    }

    @Test
    fun `a read counts only when the key goes into the key register just before`() {
        val getBoolean = ImmutableMethodReference(KEVA, "getBoolean", listOf("Ljava/lang/String;", "Z"), "Z")
        fun key(register: Int, value: String = REMEMBERED_KEY) =
            ImmutableInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(value))
        fun default(register: Int) = ImmutableInstruction11n(Opcode.CONST_4, register, 0)
        fun call(keyRegister: Int, reference: ImmutableMethodReference = getBoolean, opcode: Opcode = Opcode.INVOKE_VIRTUAL) =
            ImmutableInstruction35c(opcode, 3, 0, keyRegister, 2, 0, 0, reference)

        assertTrue(listOf(key(1), call(1)).readsKevaBoolean(1, REMEMBERED_KEY))
        assertTrue(listOf(key(1), default(2), call(1)).readsKevaBoolean(2, REMEMBERED_KEY))
        assertFalse("another key", listOf(key(1, "bg_play_toast"), call(1)).readsKevaBoolean(1, REMEMBERED_KEY))
        assertFalse("the key in another register", listOf(key(4), call(1)).readsKevaBoolean(1, REMEMBERED_KEY))
        assertFalse("the key too far back",
            listOf(key(1), default(2), default(5), default(6), call(1)).readsKevaBoolean(4, REMEMBERED_KEY))
        assertFalse("a store", listOf(key(1), call(1, ImmutableMethodReference(KEVA, "storeBoolean",
            listOf("Ljava/lang/String;", "Z"), "V"))).readsKevaBoolean(1, REMEMBERED_KEY))
        assertFalse("another class's getBoolean", listOf(key(1), call(1, ImmutableMethodReference(
            "Landroid/content/SharedPreferences;", "getBoolean", listOf("Ljava/lang/String;", "Z"), "Z"),
            Opcode.INVOKE_INTERFACE)).readsKevaBoolean(1, REMEMBERED_KEY))
        assertFalse("not a call", listOf(key(1), default(2)).readsKevaBoolean(1, REMEMBERED_KEY))
    }
}
