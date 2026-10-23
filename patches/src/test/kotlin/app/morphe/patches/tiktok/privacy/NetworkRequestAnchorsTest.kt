package app.morphe.patches.tiktok.privacy

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Network request report rests on, held to each TikTok build the bundle declares.
 *
 * The patch counts a request at the start of SsHttpCall.getResponseWithInterceptorChain, one
 * run per attempt, and reads the call's originalRequest there. That sees every call only if both
 * ways a call runs reach the method, execute directly and enqueue through the runnable it posts,
 * and each builds the request (ensureOriginalRequestCreated) before the chain runs. The report
 * then reads the request's host and its body's length, all real-named.
 */
class NetworkRequestAnchorsTest {
    private val call = "Lcom/bytedance/retrofit2/SsHttpCall;"
    private val request = "Lcom/bytedance/retrofit2/client/Request;"
    private val body = "Lcom/bytedance/retrofit2/mime/TypedOutput;"

    @Test
    fun `each declared build builds every call's request before the chain the report counts at`() {
        Fixtures.forEachDeclared { apk ->
            val classes = HashMap<String, ClassDef>()
            val callers = mutableListOf<Pair<String, List<Instruction>>>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            container.dexEntryNames.forEach { entry ->
                container.getEntry(entry)!!.dexFile.classes.forEach { classDef ->
                    if (classDef.type == call || classDef.type == request || classDef.type == body) {
                        classes.putIfAbsent(classDef.type, classDef)
                    }
                    classDef.methods.forEach methods@{ method ->
                        val instructions = method.implementation?.instructions?.toList() ?: return@methods
                        if (instructions.any { invokes(it, call, "getResponseWithInterceptorChain") }) {
                            callers += "${method.definingClass}->${method.name}" to instructions
                        }
                    }
                }
            }

            val callClass = classes.getValue(call)
            assertTrue(
                "the chain method takes nothing",
                callClass.methods.any { it.name == "getResponseWithInterceptorChain" && it.parameterTypes.isEmpty() },
            )
            assertEquals(
                "the field the report reads",
                request, callClass.fields.single { it.name == "originalRequest" }.type,
            )

            // Exactly two ways in: execute (a twin method on the call itself) and a runnable's run.
            assertEquals("callers of the chain: ${callers.map { it.first }}", 2, callers.size)
            val execute = callers.single { it.first.startsWith("$call->") }
            val runnable = callers.single { !it.first.startsWith("$call->") }
            assertTrue("${runnable.first} is a run()", runnable.first.endsWith("->run"))
            assertTrue(
                "execute builds the request before the chain",
                buildsBefore(execute.second, execute.second.indexOfFirst { invokes(it, call, "getResponseWithInterceptorChain") }),
            )
            val runnableClass = runnable.first.substringBefore("->")
            val enqueue = callClass.methods.single { it.name == "enqueue" }.implementation!!.instructions.toList()
            val posts = enqueue.indexOfFirst {
                it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == runnableClass
            }
            assertTrue("enqueue creates the runnable that runs the chain", posts >= 0)
            assertTrue(
                "enqueue builds the request before it hands the runnable on",
                enqueue.drop(posts).any { invokes(it, call, "ensureOriginalRequestCreated") },
            )

            val requestClass = classes.getValue(request)
            assertTrue("Request.getHost", requestClass.methods.any { it.name == "getHost" && it.returnType == "Ljava/lang/String;" })
            // The debug line names a log host's path by this getter; a build without it still counts.
            assertTrue("Request.getPath", requestClass.methods.any { it.name == "getPath" && it.returnType == "Ljava/lang/String;" })
            assertTrue("Request.getBody", requestClass.methods.any { it.name == "getBody" && it.returnType == body })
            assertTrue("TypedOutput.length", classes.getValue(body).methods.any { it.name == "length" && it.returnType == "J" })
        }
    }

    private fun invokes(instruction: Instruction, owner: String, name: String): Boolean {
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return reference.definingClass == owner && reference.name == name
    }

    private fun buildsBefore(instructions: List<Instruction>, index: Int): Boolean =
        index > 0 && instructions.take(index).any { invokes(it, call, "ensureOriginalRequestCreated") }
}
