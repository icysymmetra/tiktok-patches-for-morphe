/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.quickactions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private object QuickShareConfigReader : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = emptyList(),
    strings = listOf("im_long_press_share_button_to_quick_share"),
    custom = { method, _ ->
        method.implementation?.instructions?.any {
            it.getReference<MethodReference>()?.toString() == "Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;"
        } == true
    },
)

/** Follow the keyed reader into its lazy field and hook the getter each gesture calls. */
internal fun BytecodePatchContext.resolveLongPressQuickShareGate(): MutableMethod {
    val reader = QuickShareConfigReader.method.definingClass
    val gates = mutableListOf<Method>()
    classDefForEach { owner ->
        val initializer = owner.methods.firstOrNull { it.name == "<clinit>" } ?: return@classDefForEach
        val body = initializer.implementation?.instructions?.toList() ?: return@classDefForEach
        val fields = body.windowed(5).mapNotNull { (create, init, factory, result, store) ->
            if (create.opcode != Opcode.NEW_INSTANCE || create.getReference<TypeReference>()?.type != reader ||
                init.opcode != Opcode.INVOKE_DIRECT ||
                init.getReference<MethodReference>()?.toString() != "$reader-><init>()V" ||
                factory.opcode != Opcode.INVOKE_STATIC || result.opcode != Opcode.MOVE_RESULT_OBJECT ||
                store.opcode != Opcode.SPUT_OBJECT
            ) return@mapNotNull null
            val closure = (create as OneRegisterInstruction).registerA
            val value = factory.getReference<MethodReference>() ?: return@mapNotNull null
            val field = store.getReference<FieldReference>() ?: return@mapNotNull null
            field.takeIf {
                (init as FiveRegisterInstruction).registerC == closure &&
                    (factory as FiveRegisterInstruction).registerCount == 1 &&
                    factory.registerC == closure &&
                    value.parameterTypes == listOf("Lkotlin/jvm/functions/Function0;") &&
                    field.definingClass == owner.type && field.type == value.returnType &&
                    (result as OneRegisterInstruction).registerA == (store as OneRegisterInstruction).registerA
            }
        }
        if (fields.isEmpty()) return@classDefForEach
        gates += owner.methods.filter { method ->
            if (method.returnType != "I" || method.parameterTypes.isNotEmpty() ||
                !AccessFlags.STATIC.isSet(method.accessFlags)
            ) return@filter false
            val code = method.implementation?.instructions?.toList() ?: return@filter false
            val first = code.firstOrNull() ?: return@filter false
            first.opcode == Opcode.SGET_OBJECT && first.getReference<FieldReference>() in fields &&
                code.any { it.getReference<MethodReference>()?.name == "getValue" } &&
                code.any { it.getReference<MethodReference>()?.toString() == "Ljava/lang/Number;->intValue()I" }
        }
    }
    val gate = gates.singleOrPatchException("Long press quick share: getter for the keyed lazy value")
    return mutableClassDefBy(gate.definingClass).methods.single {
        it.name == gate.name && it.returnType == "I" && it.parameterTypes.isEmpty()
    }
}
