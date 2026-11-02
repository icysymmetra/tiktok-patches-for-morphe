/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.feedtext

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.authorregion.DetailActivityOnCreateFingerprint
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.inbox.MainActivityOnCreateFingerprint
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.cloneMutable
import app.morphe.patcher.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode

internal const val FEED_TEXT = "Lapp/morphe/extension/tiktok/feed/FeedTextSize;"

/** Each builder is marked at its owning font input; unrelated users of the builder stay native. */
internal fun MutableMethod.markDescriptionBuilders(inputs: List<SizeInput>) {
    for (input in inputs.sortedByDescending { it.index }) {
        addInstructions(input.index, """
            move-object/from16 v${input.ownerLocal}, p0
            ${if (input.argumentLocal == input.builderRegister) "" else "move-object/from16 v${input.argumentLocal}, v${input.builderRegister}"}
            invoke-static { v${input.ownerLocal}, v${input.argumentLocal} }, $FEED_TEXT->descriptionBuilder(Ljava/lang/Object;Ljava/lang/Object;)V
        """)
    }
}

internal fun MutableMethod.bypassDescriptionCache(bypass: CacheBypass) {
    val code = implementation!!.instructions.toList()
    addInstructionsAtControlFlowLabel(bypass.index, """
        invoke-static/range { v${bypass.ownerRegister} .. v${bypass.ownerRegister} }, $FEED_TEXT->freshDescription(Ljava/lang/Object;)Z
        move-result v${bypass.resultLocal}
        if-nez v${bypass.resultLocal}, :fresh_description
    """, ExternalLabel("fresh_description", code[bypass.fallback]))
}

/** Every native return completes the bind, including an early return after a recycled-cell check. */
internal fun MutableMethod.bindAuthorSize() {
    implementation!!.instructions.toList().indices.filter {
        implementation!!.instructions.elementAt(it).opcode == Opcode.RETURN_VOID
    }.sortedDescending().forEach { index ->
        addInstructionsAtControlFlowLabel(index,
            "invoke-static/range { p0 .. p0 }, $FEED_TEXT->authorOwnerBound(Ljava/lang/Object;)V")
    }
    addInstruction(0, "invoke-static/range { p0 .. p1 }, $FEED_TEXT->authorBinding(Ljava/lang/Object;Ljava/lang/Object;)V")
}

/** Resolve everything before writing any native method or extension bridge. */
internal fun BytecodePatchContext.installFeedText() {
    val native = resolveFeedText { classDefByOrNull(it) }
    val extension = mutableClassDefBy(FEED_TEXT)
    fun bridge(name: String) = extension.methods.singleOrNull { it.name == name }
        ?: throw PatchException("Feed text sizes: missing $name bridge")
    val bridges = linkedMapOf<String, MutableMethod>()
    for (name in listOf("descriptionViewOf", "authorViewOf", "dateViewOf", "resizeDescriptionBuilder", "refreshDescription",
            "refreshAuthor")) {
        bridges[name] = bridge(name)
    }
    fun rewrite(name: String, locals: Int, body: String) {
        val original = bridges.getValue(name)
        val method = original.cloneMutable(additionalRegisters = locals)
        method.addInstructions(0, body)
        extension.methods.remove(original)
        extension.methods.add(method)
    }
    val controller = native.controller.type
    val builder = native.builder.type
    rewrite("descriptionViewOf", 1, """
        instance-of v0, p0, $controller
        if-eqz v0, :none
        check-cast p0, $controller
        iget-object v0, p0, ${native.descriptionView}
        return-object v0
        :none
        const/4 v0, 0x0
        return-object v0
    """)
    rewrite("authorViewOf", 1, """
        instance-of v0, p0, $AUTHOR
        if-eqz v0, :none
        check-cast p0, $AUTHOR
        iget-object v0, p0, ${native.authorView}
        return-object v0
        :none
        const/4 v0, 0x0
        return-object v0
    """)
    rewrite("dateViewOf", 1, """
        instance-of v0, p0, $AUTHOR
        if-eqz v0, :none
        check-cast p0, $AUTHOR
        iget-object v0, p0, ${native.dateView}
        return-object v0
        :none
        const/4 v0, 0x0
        return-object v0
    """)
    rewrite("resizeDescriptionBuilder", 2, """
        check-cast p0, $builder
        iget-object v0, p0, ${native.builderPaint}
        invoke-static { v0, p1 }, $FEED_TEXT->descriptionPaint(Landroid/graphics/Paint;Landroid/view/View;)Z
        move-result v1
        if-eqz v1, :native_size
        invoke-virtual { v0 }, Landroid/graphics/Paint;->getTextSize()F
        move-result v1
        iput v1, p0, ${native.builderSize}
        const/4 v1, 0x0
        iput-boolean v1, p0, ${native.builderCache}
        :native_size
        return-void
    """)
    // The native original/translated refresh methods honor the VM's current expanded state.
    // Clearing their own wrappers forces them through the same parser and native layout factory.
    rewrite("refreshDescription", 2, """
        check-cast p0, $controller
        iget-object v0, p0, ${native.translationOwner}
        iget-boolean v1, v0, ${native.translationFlag}
        const/4 v0, 0x0
        ${native.layoutCaches.joinToString("\n") { "iput-object v0, p0, $it" }}
        if-nez v1, :translated
        invoke-virtual { p0 }, ${native.refreshOriginal}
        return-void
        :translated
        invoke-virtual { p0 }, ${native.refreshTranslated}
        return-void
    """)
    rewrite("refreshAuthor", 1, """
        check-cast p0, $AUTHOR
        check-cast p1, $AWEME
        invoke-virtual/range { p0 .. p1 }, ${native.authorBind}
        return-void
    """)
    mutableClassDefBy(controller).findMutableMethodOf(native.layoutFactory).markDescriptionBuilders(native.sizeInputs)
    mutableClassDefBy(controller).findMutableMethodOf(native.layoutDispatch).bypassDescriptionCache(native.bypass)
    mutableClassDefBy(builder).findMutableMethodOf(native.build).addInstruction(0,
        "invoke-static/range { p0 .. p0 }, $FEED_TEXT->descriptionBuilding(Ljava/lang/Object;)V")
    mutableClassDefBy(AUTHOR).findMutableMethodOf(native.authorLoader).addInstruction(native.authorStore + 1,
        "invoke-static/range { p0 .. p0 }, $FEED_TEXT->authorOwnerBound(Ljava/lang/Object;)V")
    mutableClassDefBy(AUTHOR).findMutableMethodOf(native.authorBind).bindAuthorSize()
}

@Suppress("unused")
val feedTextSizePatch = bytecodePatch(
    name = "Feed text sizes",
    description = "Lets you make video descriptions, creator names and post dates bigger or smaller, " +
        "so they're easier to read. Starts off. Pick a size in Metra settings > Interface.",
) {
    category("Interface")
    compatibleWith(*AppCompatibilities.tiktok())
    dependsOn(settingsPatch, sharedExtensionPatch)
    execute {
        val windows = listOf(MainActivityOnCreateFingerprint.method, DetailActivityOnCreateFingerprint.method)
        installFeedText()
        SettingsStatusLoadFingerprint.method.addInstruction(0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableFeedTextSize()V")
        // The detail page as well, for a process a deep link starts with no MainActivity (#84).
        windows.forEach {
            it.addInstruction(0,
                "invoke-static/range { p0 .. p0 }, $FEED_TEXT->install(Landroid/app/Activity;)V")
        }
    }
}
