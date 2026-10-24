package app.morphe.patches.tiktok.captchapopup

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptchaMemberContractsTest {
    @Test
    fun `request getters resolve through a superclass and transitive interfaces`() {
        val activitySource = interfaceDef(
            "Ltest/ActivitySource;",
            methods = listOf(method("Ltest/ActivitySource;", "getActivity", emptyList(), ACTIVITY)),
        )
        val requestBase = classDef(
            "Ltest/RequestBase;",
            interfaces = listOf(activitySource.type),
        )
        val serviceSource = interfaceDef(
            "Ltest/ServiceSource;",
            methods = listOf(method("Ltest/ServiceSource;", "getServiceType", emptyList(), STRING)),
        )
        val requestContract = interfaceDef(
            "Ltest/RequestContract;",
            interfaces = listOf(serviceSource.type),
        )
        val request = classDef(
            "Ltest/Request;",
            superclass = requestBase.type,
            interfaces = listOf(requestContract.type),
        )
        val callback = classDef(
            "Ltest/Callback;",
            methods = listOf(method("Ltest/Callback;", "onFail", listOf("I"), "V")),
        )
        val all = resolver(activitySource, requestBase, serviceSource, requestContract, request, callback)

        assertSame(callback, requireRiskControlMembers(request.type, callback.type, all))
    }

    @Test
    fun `risk callback resolves through a superclass and uses virtual invoke`() {
        val callbackBase = classDef(
            "Ltest/CallbackBase;",
            methods = listOf(method("Ltest/CallbackBase;", "onFail", listOf("I"), "V")),
        )
        val callback = classDef("Ltest/Callback;", superclass = callbackBase.type)
        val request = classDef(
            "Ltest/Request;",
            methods = listOf(
                method("Ltest/Request;", "getActivity", emptyList(), ACTIVITY),
                method("Ltest/Request;", "getServiceType", emptyList(), STRING),
            ),
        )
        val all = resolver(callbackBase, callback, request)

        assertSame(callback, requireRiskControlMembers(request.type, callback.type, all))
        assertEquals("invoke-virtual", callback.instanceInvokeKind())
    }

    @Test
    fun `dismiss calls resolve through class and interface parents`() {
        val classBase = classDef(
            "Ltest/ClassDismissBase;",
            methods = listOf(method("Ltest/ClassDismissBase;", "LIZJ", emptyList(), "V")),
        )
        val classCallback = classDef("Ltest/ClassDismiss;", superclass = classBase.type)
        val interfaceBase = interfaceDef(
            "Ltest/InterfaceDismissBase;",
            methods = listOf(method("Ltest/InterfaceDismissBase;", "LIZIZ", emptyList(), "V")),
        )
        val interfaceCallback = interfaceDef(
            "Ltest/InterfaceDismiss;",
            interfaces = listOf(interfaceBase.type),
        )
        val resolve = resolver(classBase, classCallback, interfaceBase, interfaceCallback)

        assertEquals(
            "invoke-virtual/range {p3 .. p3}, Ltest/ClassDismiss;->LIZJ()V",
            captchaDismissCall(classCallback.type, "LIZJ", resolve),
        )
        assertEquals(
            "invoke-interface/range {p3 .. p3}, Ltest/InterfaceDismiss;->LIZIZ()V",
            captchaDismissCall(interfaceCallback.type, "LIZIZ", resolve),
        )
    }

    @Test
    fun `static and private members do not satisfy an injected instance call`() {
        val staticBase = classDef(
            "Ltest/StaticBase;",
            methods = listOf(
                method("Ltest/StaticBase;", "onFail", listOf("I"), "V", isStatic = true),
            ),
        )
        val staticCallback = classDef("Ltest/StaticCallback;", superclass = staticBase.type)
        val privateBase = classDef(
            "Ltest/PrivateBase;",
            methods = listOf(
                method("Ltest/PrivateBase;", "onFail", listOf("I"), "V", isPrivate = true),
            ),
        )
        val privateCallback = classDef("Ltest/PrivateCallback;", superclass = privateBase.type)

        val request = classDef(
            "Ltest/Request;",
            methods = listOf(
                method("Ltest/Request;", "getActivity", emptyList(), ACTIVITY),
                method("Ltest/Request;", "getServiceType", emptyList(), STRING),
            ),
        )

        assertThrows(IllegalStateException::class.java) {
            requireRiskControlMembers(
                request.type,
                staticCallback.type,
                resolver(staticBase, staticCallback, request),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            requireRiskControlMembers(
                request.type,
                privateCallback.type,
                resolver(privateBase, privateCallback, request),
            )
        }
    }

    @Test
    fun `the risk control execute fingerprint requires an instance receiver`() {
        val instance = method("Ltest/Risk;", "execute", listOf("Ltest/R;", "Ltest/C;"), "Z")
        val static = method(
            "Ltest/Risk;",
            "execute",
            listOf("Ltest/R;", "Ltest/C;"),
            "Z",
            isStatic = true,
        )
        val owner = classDef("Ltest/Risk;", methods = listOf(instance, static))

        assertTrue(RiskControlServiceExecuteFingerprint.custom!!(instance, owner))
        assertFalse(RiskControlServiceExecuteFingerprint.custom!!(static, owner))
    }

    @Test
    fun `a malformed hierarchy cycle terminates without finding a member`() {
        val left = interfaceDef("Ltest/Left;", interfaces = listOf("Ltest/Right;"))
        val right = interfaceDef("Ltest/Right;", interfaces = listOf(left.type))

        assertFalse(left.hasInstanceMethodInHierarchy(resolver(left, right), "missing", "V", emptyList()))
    }

    private fun resolver(vararg classes: ClassDef): (String) -> ClassDef? {
        val byType = classes.associateBy { it.type }
        return byType::get
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returnType: String,
        isStatic: Boolean = false,
        isPrivate: Boolean = false,
    ): Method = ImmutableMethod(
        owner,
        name,
        parameters.map { ImmutableMethodParameter(it, null, null) },
        returnType,
        (if (isPrivate) AccessFlags.PRIVATE.value else AccessFlags.PUBLIC.value) or
            if (isStatic) AccessFlags.STATIC.value else 0,
        null,
        null,
        null,
    )

    private fun classDef(
        type: String,
        superclass: String = "Ljava/lang/Object;",
        interfaces: List<String> = emptyList(),
        methods: List<Method> = emptyList(),
    ): ClassDef = ImmutableClassDef(
        type,
        AccessFlags.PUBLIC.value,
        superclass,
        interfaces,
        null,
        null,
        null,
        methods,
    )

    private fun interfaceDef(
        type: String,
        interfaces: List<String> = emptyList(),
        methods: List<Method> = emptyList(),
    ): ClassDef = ImmutableClassDef(
        type,
        AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value,
        "Ljava/lang/Object;",
        interfaces,
        null,
        null,
        null,
        methods,
    )

    private companion object {
        const val ACTIVITY = "Landroid/app/Activity;"
        const val STRING = "Ljava/lang/String;"
    }
}
