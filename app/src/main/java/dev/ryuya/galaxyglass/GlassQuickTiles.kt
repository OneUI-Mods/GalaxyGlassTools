package dev.ryuya.galaxyglass

import android.graphics.Canvas
import android.content.res.Resources
import androidx.annotation.RequiresApi
import dev.ryuya.galaxyglass.compat.DexSymbols
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JvmModifier
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/** Styles only the native background containers. Tile labels and icons stay on Samsung's drawing path. */
@RequiresApi(33)
internal class GlassQuickTiles(
    loader: ClassLoader,
    resources: Resources,
    private val level: () -> Int,
    private val editLevel: () -> Int,
    private val dark: () -> Boolean,
    private val unlocked: () -> Boolean,
    private val pointer: () -> GlassPointer?,
    private val material: GlassMaterial,
    private val density: Float,
    private val backdrop: () -> GlassPanelBackdrop?,
    private val onDrawn: () -> Unit,
    private val onEditorDrawn: () -> Unit,
    private val onFailed: (Exception) -> Unit,
    private val onEditorStage: (GlassSurfaceStage) -> Unit,
) {
    private enum class Surface(val profile: GlassMaterial.Profile) {
        CARD(GlassMaterial.Profile.QUICK_CONTROL),
        GRID(GlassMaterial.Profile.QUICK_GROUP),
        SLIDER(GlassMaterial.Profile.QUICK_SLIDER),
        SIM_BAR(GlassMaterial.Profile.QUICK_GROUP),
        EDITOR(GlassMaterial.Profile.SYSTEM_SURFACE),
    }
    private data class Clip(val surface: Surface, val nativeShape: Any)
    private data class Size(val packed: Long, val layoutDirection: Any, val density: Float)
    private data class Frame(
        val canvas: Canvas,
        val outline: GlassQuickOutline.Geometry,
        val light: GlassGlint?,
        val screenX: Float,
        val screenY: Float,
    )

    private var drawingUnavailable = false
    private val drawnSurfaces = mutableSetOf<Surface>()
    private val refractedSurfaces = mutableSetOf<Surface>()
    private val drawNodes = WeakHashMap<Any, Unit>()
    private val modifiers = object : LinkedHashMap<Clip, Any>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Clip, Any>?) = size > 24
    }
    private val invalidateDraw: Method?

    init {
        fun type(name: String) = DexSymbols.findClass(name, false, loader)
        val modifier = type("androidx.compose.ui.Modifier")
        val emptyModifier = modifier.getField("Companion").get(null)
        val then = modifier.getMethod("then", modifier)
        val function = type("kotlin.jvm.functions.Function1")
        val invoke = function.methods.single { JvmModifier.isAbstract(it.modifiers) && it.parameterCount == 1 }
        val unit = type("kotlin.Unit").getField("INSTANCE").get(null)
        val scope = type("androidx.compose.ui.node.LayoutNodeDrawScope")
        val drawContent = scope.getMethod("drawContent")
        val getSize = scope.getMethod("getSize-NH-jbRc")
        val getLayoutDirection = scope.getMethod("getLayoutDirection")
        val getDensity = scope.getMethod("getDensity")
        val getContext = scope.getMethod("getDrawContext")
        val getCanvas = getContext.returnType.getMethod("getCanvas")
        val nativeCanvas = type("androidx.compose.ui.graphics.AndroidCanvas").getField("internalCanvas")
        require(nativeCanvas.type == Canvas::class.java && getSize.returnType == Long::class.javaPrimitiveType)
        val outline = GlassQuickOutline(loader)
        val drawNode = runCatching { scope.getField("drawNode") }.getOrNull()
        invalidateDraw = drawNode?.let { node ->
            runCatching { type("androidx.compose.ui.node.DrawModifierNodeKt").getMethod("invalidateDraw", node.type) }.getOrNull()
        }
        val locate = drawNode?.let {
            runCatching {
                val node = type("androidx.compose.ui.node.DelegatableNode")
                val coordinator = type("androidx.compose.ui.node.DelegatableNodeKt")
                    .getMethod("requireCoordinator-64DMado", node, Int::class.javaPrimitiveType)
                val position = coordinator.returnType.getMethod("localToScreen-MK-Hz9U", Long::class.javaPrimitiveType)
                val location: (Any) -> Long = { target -> position.invoke(coordinator.invoke(null, target, 4), 0L) as Long }
                location
            }.getOrNull()
        }
        val draw = type("androidx.compose.ui.draw.DrawModifierKt").getMethod("drawWithContent", modifier, function)

        fun overlay(surface: Surface, nativeShape: Any): Any = modifiers.getOrPut(Clip(surface, nativeShape)) {
            var lastSize: Size? = null
            var geometry: GlassQuickOutline.Geometry? = null

            fun frame(nativeScope: Any): Frame? {
                val packed = getSize.invoke(nativeScope) as Long
                val width = Float.fromBits((packed ushr 32).toInt())
                val height = Float.fromBits(packed.toInt())
                if (!width.isFinite() || !height.isFinite() || width < 14 * density || height < 14 * density) return null
                val size = Size(packed, requireNotNull(getLayoutDirection.invoke(nativeScope)), getDensity.invoke(nativeScope) as Float)
                if (size != lastSize) {
                    geometry = outline.read(nativeShape, size.packed, size.layoutDirection, nativeScope)
                    lastSize = size
                }
                val shape = geometry ?: return null
                val canvas = nativeCanvas.get(getCanvas.invoke(getContext.invoke(nativeScope))) as Canvas
                if (!canvas.isHardwareAccelerated) return null
                val node = drawNode?.get(nativeScope)
                if (invalidateDraw != null && node != null) drawNodes[node] = Unit
                val touch = pointer()
                val position = node?.let { runCatching { locate?.invoke(it) }.getOrNull() }
                val screenX = position?.let { Float.fromBits((it ushr 32).toInt()) } ?: Float.NaN
                val screenY = position?.let { Float.fromBits(it.toInt()) } ?: Float.NaN
                val light = if (screenX.isFinite() && screenY.isFinite()) {
                    touch?.onSurface(screenX, screenY, width, height, density)
                } else null
                return Frame(canvas, shape, light, screenX, screenY)
            }

            val callback = Proxy.newProxyInstance(function.classLoader, arrayOf(function)) { proxy, called, args ->
                when (called.name) {
                    invoke.name -> {
                        val nativeScope = requireNotNull(args?.firstOrNull())
                        val strength = if (surface == Surface.EDITOR) editLevel() else level()
                        val mode = dark()
                        val frame = if (strength > 0 && unlocked() && !drawingUnavailable) {
                            try { frame(nativeScope) } catch (error: Exception) {
                                drawingUnavailable = true
                                onFailed(error)
                                null
                            }
                        } else null
                        if (frame != null) {
                            drawFrame(frame, strength, mode, surface, rim = false)
                        }
                        drawContent.invoke(nativeScope)
                        if (frame != null) {
                            drawFrame(frame, strength, mode, surface, rim = true)
                            if (drawnSurfaces.add(surface)) {
                                HookEntry.log("GLASS_QUICK_SURFACE ${surface.name.lowercase()}")
                                if (surface == Surface.EDITOR) onEditorStage(GlassSurfaceStage.DRAWN)
                            }
                            if (surface == Surface.EDITOR) onEditorDrawn() else onDrawn()
                        }
                        unit
                    }
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "One UI glass ${surface.name.lowercase()} background"
                    else -> null
                }
            }
            requireNotNull(draw.invoke(null, emptyModifier, callback))
        }
        installNativeGroupSurfaces(loader, modifier, then, ::overlay)
        runCatching { installEditorSurfaces(loader, resources, modifier, then, ::overlay) }
            .onFailure { HookEntry.log("GLASS_QUICK_EDITOR_UNAVAILABLE ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun installEditorSurfaces(
        loader: ClassLoader,
        resources: Resources,
        modifier: Class<*>,
        then: Method,
        overlay: (Surface, Any) -> Any,
    ) {
        fun type(name: String) = DexSymbols.findClass(name, false, loader)
        val panel = "com.android.systemui.samsung.quicksetting.ui.panel."
        val rounded = type("androidx.compose.foundation.shape.RoundedCornerShape")
        val background = type("androidx.compose.foundation.BackgroundKt").declaredMethods.single {
            it.name == "background-bw27NRU" && it.parameterCount == 3 && it.parameterTypes[0] == modifier
        }
        val button = type(panel + "SecQuickSettingHeaderKt").declaredMethods.single {
            it.name == "RoundedButton" && it.parameterCount == 7 && it.parameterTypes[0] == modifier
        }
        val tileBoxButton = type("com.android.systemui.samsung.quicksetting.ui.tilebox.QuickTileBoxSceneMainKt")
            .declaredMethods.single {
                it.name == "TileBoxDoneButton" && it.parameterCount == 5 && it.parameterTypes[0] == modifier
            }
        val menu = type(panel + "ContextMenuKt").declaredMethods.single {
            it.name.startsWith("ContextMenu-") && it.parameterCount == 9
        }
        val editLabels = listOf("qs_customizer_settings", "quick_settings_done", "sec_quick_settings_done", "done")
            .map { resources.getIdentifier(it, "string", "com.android.systemui") }.filter { it != 0 }.toSet()
        val buttonShape = type("androidx.compose.foundation.shape.RoundedCornerShapeKt").getDeclaredMethod(
            "RoundedCornerShape-0680j_4", Float::class.javaPrimitiveType,
        ).invoke(null, 24f) ?: error("Editor button outline unavailable")
        val editingPlate = ThreadLocal<Boolean>()
        val handles = mutableListOf<ModernHook.Handle>()
        try {
            handles += ModernHook.aroundMethod(background) { chain ->
                val shape = chain.args[2]
                if (editingPlate.get() != true || !rounded.isInstance(shape)) return@aroundMethod chain.proceed()
                editingPlate.remove()
                val strength = editLevel()
                val args = chain.args.toTypedArray()
                val stock = args[1] as Long
                if (stock and 0xffffffffL == 0L) {
                    val alpha = ((stock ushr 56) * (1f - .45f * strength / 100f)).toInt()
                    args[1] = (stock and 0x00ffffffffffffffL) or (alpha.toLong() shl 56)
                }
                then.invoke(chain.proceed(args), overlay(Surface.EDITOR, requireNotNull(shape)))
            }
            for ((renderer, changedIndex) in listOf(button to 5, tileBoxButton to 4)) {
                ModernHook.deoptimize(renderer)
                handles += ModernHook.aroundMethod(renderer) { chain ->
                    val editorButton = (chain.args[1] as? Int)?.let(editLabels::contains) == true
                    if (editLevel() <= 0 || !unlocked() || (renderer == button && !editorButton)) {
                        return@aroundMethod chain.proceed()
                    }
                    val args = chain.args.toTypedArray()
                    args[0] = then.invoke(args[0], overlay(Surface.EDITOR, buttonShape))
                    // Ask Compose to compare the new modifier rather than reuse the caller's flag.
                    args[changedIndex] = (args[changedIndex] as Int) and 0b1110.inv()
                    chain.proceed(args)
                }
            }
            ModernHook.deoptimize(menu)
            handles += ModernHook.aroundMethod(menu) { chain ->
                if (editLevel() <= 0 || !unlocked()) return@aroundMethod chain.proceed()
                val previous = editingPlate.get()
                editingPlate.set(true)
                try {
                    chain.proceed()
                } finally {
                    if (previous == null) editingPlate.remove() else editingPlate.set(previous)
                }
            }
            HookEntry.log("GLASS_QUICK_EDITOR_READY")
        } catch (error: Throwable) {
            handles.asReversed().forEach { it.unhook() }
            throw error
        }
    }

    private fun drawFrame(frame: Frame, strength: Int, dark: Boolean, surface: Surface, rim: Boolean) {
        try {
            val outline = frame.outline
            if (!rim && frame.screenX.isFinite() && frame.screenY.isFinite() && backdrop()?.draw(
                    frame.canvas, frame.screenX, frame.screenY, outline.left, outline.top, outline.right, outline.bottom,
                    outline.radii, strength, dark, material.tintFactor, opacity = strength * 0.01f,
                ) == true) {
                if (refractedSurfaces.add(surface)) {
                    HookEntry.log("GLASS_QUICK_REFRACTION ${surface.name.lowercase()}")
                    if (surface == Surface.EDITOR) onEditorStage(GlassSurfaceStage.REFRACTED)
                }
                return
            }
            material.drawSurface(
                frame.canvas, outline.left, outline.top, outline.right, outline.bottom, outline.radii,
                strength, dark, fill = !rim, rim = rim, glint = frame.light, profile = surface.profile,
            )
        } catch (error: Exception) {
            drawingUnavailable = true
            onFailed(error)
        }
    }

    private fun installNativeGroupSurfaces(
        loader: ClassLoader,
        modifier: Class<*>,
        then: Method,
        overlay: (Surface, Any) -> Any,
    ) {
        val tiles = "com.android.systemui.samsung.quicksetting.ui.tiles."
        fun method(owner: String, name: String, count: Int? = null) =
            DexSymbols.findClass(owner, false, loader).declaredMethods.single {
                it.name == name && (count == null || it.parameterCount == count)
            }
        val grid = method(tiles + "QuickTileBoxKt", "QuickTileBox", 5)
        val bars = method(tiles + "IconProgressBarWithTileKt", "IconProgressBarWithTile-S4AO9yg")
        val iconText = method(tiles + "IconTextTileBackgroundKt", "IconTextTileBackground", 4)
        val shape = DexSymbols.findClass("androidx.compose.ui.graphics.Shape", false, loader)
        val rounded = DexSymbols.findClass("androidx.compose.foundation.shape.RoundedCornerShape", false, loader)
        val clip = DexSymbols.findClass("androidx.compose.ui.draw.ClipKt", false, loader).getMethod("clip", modifier, shape)
        val box = method(
            "com.android.systemui.samsung.quicksetting.ui.common.uielement.BoxWithOutlineKt",
            "BoxWithOutline-8QY48No", 10,
        )
        require(box.parameterTypes[0] == modifier && box.parameterTypes[4] == Long::class.javaPrimitiveType)
        require(box.parameterTypes[8] == Int::class.javaPrimitiveType)
        val nativeSurface = ThreadLocal<Surface>()
        fun hookRenderer(renderer: Method, surface: Surface): ModernHook.Handle {
            ModernHook.deoptimize(renderer)
            return ModernHook.aroundMethod(renderer) { chain ->
                if (level() <= 0 || !unlocked() || drawingUnavailable) return@aroundMethod chain.proceed()
                val previous = nativeSurface.get()
                nativeSurface.set(surface)
                try {
                    chain.proceed()
                } finally {
                    if (previous == null) nativeSurface.remove() else nativeSurface.set(previous)
                }
            }
        }
        val handles = mutableListOf<ModernHook.Handle>()
        try {
            handles += ModernHook.aroundMethod(clip) { chain ->
                val native = chain.proceed()
                val surface = nativeSurface.get()
                val nativeShape = chain.args[1]
                if ((surface != Surface.GRID && surface != Surface.SLIDER) || !rounded.isInstance(nativeShape)) native
                else {
                    nativeSurface.remove()
                    then.invoke(native, overlay(surface, requireNotNull(nativeShape)))
                }
            }
            handles += ModernHook.aroundMethod(box) { chain ->
                val nativeShape = chain.args[5]
                val surface = nativeSurface.get()
                if ((surface != Surface.CARD && surface != Surface.SIM_BAR) || !rounded.isInstance(nativeShape)) {
                    return@aroundMethod chain.proceed()
                }
                val args = chain.args.toTypedArray()
                val stockColor = args[4] as Long
                // sRGB Compose Color stores ARGB in the upper word. Leave the invisible one-cell placeholder alone.
                val alpha = (stockColor ushr 56).toInt()
                if (stockColor and 0xffffffffL != 0L || alpha <= 5) return@aroundMethod chain.proceed()
                val glassAlpha = (alpha * (1f - 0.70f * level() / 100f)).toInt().coerceIn(0, 255)
                args[3] = 0L // The glass has its own rim. Suppress this card's stock outline.
                args[4] = (stockColor and 0x00ffffffffffffffL) or (glassAlpha.toLong() shl 56)
                args[0] = then.invoke(args[0], overlay(surface, requireNotNull(nativeShape)))
                args[8] = (args[8] as Int) or 1
                nativeSurface.remove()
                chain.proceed(args)
            }
            for ((renderer, surface) in listOf(grid to Surface.GRID, bars to Surface.SLIDER, iconText to Surface.CARD)) {
                handles += hookRenderer(renderer, surface)
            }
        } catch (error: Throwable) {
            handles.asReversed().forEach { it.unhook() }
            throw error
        }
        runCatching {
            // BoxWithConstraints subcomposes Samsung's SIM bar after the entry function returns.
            val renderer = method(tiles + "MultiSIMPreferredBarKt\$\$ExternalSyntheticLambda5", "invoke", 3)
            hookRenderer(renderer, Surface.SIM_BAR)
        }.onFailure { HookEntry.log("GLASS_QUICK_SIM_BAR_UNAVAILABLE ${it.javaClass.simpleName}") }
    }

    fun refresh() {
        val invalidate = invalidateDraw ?: return
        val iterator = drawNodes.keys.iterator()
        while (iterator.hasNext()) {
            try {
                invalidate.invoke(null, iterator.next())
            } catch (_: ReflectiveOperationException) {
                iterator.remove()
            }
        }
    }
}
