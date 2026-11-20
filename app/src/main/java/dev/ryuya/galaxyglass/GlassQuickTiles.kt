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
    private val volumeLevel: () -> Int,
    private val dark: () -> Boolean,
    private val unlocked: () -> Boolean,
    private val pointer: () -> GlassPointer?,
    private val material: GlassMaterial,
    private val density: Float,
    private val backdrop: () -> GlassPanelBackdrop?,
    private val onDrawn: () -> Unit,
    private val onEditorDrawn: () -> Unit,
    private val onVolumeDrawn: () -> Unit,
    private val onSurface: (String, GlassSurfaceStage) -> Unit,
) {
    private enum class Surface(val profile: GlassMaterial.Profile) {
        CARD(GlassMaterial.Profile.QUICK_CONTROL),
        GRID(GlassMaterial.Profile.QUICK_GROUP),
        SLIDER(GlassMaterial.Profile.QUICK_SLIDER),
        SIM_BAR(GlassMaterial.Profile.QUICK_GROUP),
        SIM_POPUP(GlassMaterial.Profile.SYSTEM_SURFACE),
        HEADER_ACTION(GlassMaterial.Profile.QUICK_CONTROL),
        DETAIL_PANEL(GlassMaterial.Profile.SYSTEM_SURFACE),
        HEADER_TOOLTIP(GlassMaterial.Profile.SYSTEM_SURFACE),
        SECURITY_FOOTER(GlassMaterial.Profile.QUICK_CONTROL),
        MEDIA(GlassMaterial.Profile.QUICK_GROUP),
        EMPTY_MEDIA(GlassMaterial.Profile.QUICK_GROUP),
        CONTROL_BAR(GlassMaterial.Profile.QUICK_GROUP),
        PANEL_SETTINGS(GlassMaterial.Profile.SYSTEM_SURFACE),
        PANEL_SUBMENU(GlassMaterial.Profile.SYSTEM_SURFACE),
        BRIGHTNESS_ACTIONS(GlassMaterial.Profile.QUICK_CONTROL),
        SOUNDCRAFT_ACTIONS(GlassMaterial.Profile.QUICK_CONTROL),
        PRIVACY_ACTIONS(GlassMaterial.Profile.QUICK_CONTROL),
        DND_ACTIONS(GlassMaterial.Profile.QUICK_CONTROL),
        VOLUME_OPTIONS(GlassMaterial.Profile.SYSTEM_SURFACE),
        VOLUME_POPUP(GlassMaterial.Profile.SYSTEM_SURFACE),
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

    private val failedSurfaces = mutableSetOf<Surface>()

    private fun strength(surface: Surface): Int {
        if (!unlocked() || surface in failedSurfaces) return 0
        return when (surface) {
            Surface.EDITOR, Surface.PANEL_SETTINGS, Surface.PANEL_SUBMENU -> editLevel()
            Surface.VOLUME_OPTIONS, Surface.VOLUME_POPUP -> volumeLevel()
            else -> level()
        }
    }

    private fun disable(surface: Surface, error: Exception) {
        if (failedSurfaces.add(surface)) {
            HookEntry.log("GLASS_QUICK_DRAW_UNAVAILABLE ${surface.name.lowercase()} ${error.javaClass.simpleName}")
        }
    }
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
                        if (invalidateDraw != null) drawNode.get(nativeScope)?.let { drawNodes[it] = Unit }
                        val strength = strength(surface)
                        val mode = dark()
                        val frame = if (strength > 0) {
                            try { frame(nativeScope) } catch (error: Exception) {
                                disable(surface, error)
                                null
                            }
                        } else null
                        if (frame != null) drawFrame(frame, strength, mode, surface, rim = false)
                        drawContent.invoke(nativeScope)
                        if (frame != null && surface !in failedSurfaces) {
                            drawFrame(frame, strength, mode, surface, rim = true)
                            if (surface !in failedSurfaces && drawnSurfaces.add(surface)) {
                                HookEntry.log("GLASS_QUICK_SURFACE ${surface.name.lowercase()}")
                                report(surface, GlassSurfaceStage.DRAWN)
                            }
                            if (surface !in failedSurfaces) {
                                if (surface == Surface.EDITOR || surface == Surface.PANEL_SETTINGS ||
                                    surface == Surface.PANEL_SUBMENU) onEditorDrawn()
                                else if (surface == Surface.VOLUME_OPTIONS || surface == Surface.VOLUME_POPUP) onVolumeDrawn()
                                else onDrawn()
                            }
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
        val drawingSurface = ThreadLocal<Surface>()
        val masks = mutableMapOf<Surface, Any>()
        fun mask(surface: Surface): Any = masks.getOrPut(surface) {
            val callback = Proxy.newProxyInstance(function.classLoader, arrayOf(function)) { proxy, called, args ->
                when (called.name) {
                    invoke.name -> {
                        val nativeScope = requireNotNull(args?.firstOrNull())
                        val previous = drawingSurface.get()
                        if (strength(surface) > 0) drawingSurface.set(surface)
                        try {
                            drawContent.invoke(nativeScope)
                        } finally {
                            if (previous == null) drawingSurface.remove() else drawingSurface.set(previous)
                        }
                        unit
                    }
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "One UI glass background tint ${surface.name.lowercase()}"
                    else -> null
                }
            }
            requireNotNull(draw.invoke(null, emptyModifier, callback))
        }

        installNativePlates(loader, modifier, then, drawingSurface, ::mask, ::overlay)
        runCatching { installHeaderActions(loader, resources, modifier, then, ::overlay) }
            .onFailure { HookEntry.log("GLASS_QUICK_HEADER_ACTIONS_UNAVAILABLE ${it.javaClass.simpleName}") }
        runCatching { installEditorButtons(loader, resources, modifier, then, ::overlay) }
            .onFailure { HookEntry.log("GLASS_QUICK_EDITOR_BUTTONS_UNAVAILABLE ${it.javaClass.simpleName}") }
    }

    private enum class PlateMode { CLIP, FILL, CLIPPED_FILL, BOX, ROW, SUBMENU }
    private data class Renderer(
        val surface: Surface,
        val owner: String,
        val method: String,
        val mode: PlateMode,
        val parameters: Int? = null,
        val row: Int = 1,
    )

    private class RenderPass(val renderer: Renderer) {
        var clipModifier: Any? = null
        var nativeShape: Any? = null
        var boxShape: Any? = null
        var rows = 0
        var bound = false
    }

    private fun installNativePlates(
        loader: ClassLoader, modifier: Class<*>, then: Method,
        drawingSurface: ThreadLocal<Surface>, mask: (Surface) -> Any, overlay: (Surface, Any) -> Any,
    ) {
        fun type(name: String) = DexSymbols.findClass(name, false, loader)
        val shape = type("androidx.compose.ui.graphics.Shape")
        val rounded = type("androidx.compose.foundation.shape.RoundedCornerShape")
        val roundedShape = type("androidx.compose.foundation.shape.RoundedCornerShapeKt")
            .getDeclaredMethod("RoundedCornerShape-0680j_4", Float::class.javaPrimitiveType)
        val rowShape = requireNotNull(roundedShape.invoke(null, 18f))
        val rectangle = type("androidx.compose.ui.graphics.RectangleShapeKt").getField("RectangleShape").get(null)
        val clip = type("androidx.compose.ui.draw.ClipKt").getMethod("clip", modifier, shape)
        val fill = type("androidx.compose.foundation.BackgroundKt").getDeclaredMethod("background-bw27NRU",
            modifier, Long::class.javaPrimitiveType, shape)
        val fillWidth = type("androidx.compose.foundation.layout.SizeKt").getDeclaredMethod("fillMaxWidth\$default",
            modifier, Float::class.javaPrimitiveType, Int::class.javaPrimitiveType, Any::class.java)
        val box = type("com.android.systemui.samsung.quicksetting.ui.common.uielement.BoxWithOutlineKt").declaredMethods.single {
            it.name == "BoxWithOutline-8QY48No" && it.parameterCount == 10 && it.parameterTypes[0] == modifier &&
                it.parameterTypes[5] == rounded
        }
        val animatedVisibility = type("androidx.compose.animation.AnimatedVisibilityKt").declaredMethods.single {
            it.name == "AnimatedVisibility" && it.parameterCount == 9 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType
                && it.parameterTypes[1] == modifier
        }
        val background = type("androidx.compose.foundation.BackgroundNode")
        val backgroundDraw = background.getDeclaredMethod("draw", type("androidx.compose.ui.node.LayoutNodeDrawScope"))
        val color = background.getField("color")
        val active = ThreadLocal<RenderPass>()
        val handles = mutableListOf<ModernHook.Handle>()

        try {
            handles += ModernHook.aroundMethod(backgroundDraw) { chain ->
                val surface = drawingSurface.get() ?: return@aroundMethod chain.proceed()
                val node = chain.thisObject
                val stock = color.getLong(node)
                val level = strength(surface)
                drawingSurface.remove()
                try {
                    if (level > 0 && stock and 0xffffffffL == 0L) {
                        val reduction = when (surface) {
                            Surface.CARD, Surface.SIM_BAR, Surface.EMPTY_MEDIA, Surface.CONTROL_BAR -> .70f
                            Surface.EDITOR, Surface.PANEL_SETTINGS, Surface.PANEL_SUBMENU -> .45f
                            else -> .55f
                        }
                        val alpha = ((stock ushr 56) * (1f - reduction * level / 100f)).toLong()
                        color.setLong(node, stock and 0x00ffffffffffffffL or (alpha shl 56))
                    }
                    chain.proceed()
                } finally {
                    color.setLong(node, stock)
                    drawingSurface.set(surface)
                }
            }
            handles += ModernHook.aroundMethod(clip) { chain ->
                val result = chain.proceed()
                val pass = active.get() ?: return@aroundMethod result
                val nativeShape = chain.args[1]
                if (pass.bound || !rounded.isInstance(nativeShape)) return@aroundMethod result
                when (pass.renderer.mode) {
                    PlateMode.CLIP -> {
                        pass.bound = true
                        then.invoke(result, overlay(pass.renderer.surface, requireNotNull(nativeShape)))
                    }
                    PlateMode.CLIPPED_FILL, PlateMode.SUBMENU -> {
                        pass.clipModifier = result
                        pass.nativeShape = nativeShape
                        result
                    }
                    else -> result
                }
            }
            handles += ModernHook.aroundMethod(box) { chain ->
                val pass = active.get() ?: return@aroundMethod chain.proceed()
                if (pass.bound || pass.renderer.mode != PlateMode.BOX) return@aroundMethod chain.proceed()
                pass.boxShape = chain.args[5]
                try {
                    chain.proceed()
                } finally {
                    pass.boxShape = null
                }
            }
            ModernHook.deoptimize(box)
            handles += ModernHook.aroundMethod(fill) { chain ->
                val pass = active.get() ?: return@aroundMethod chain.proceed()
                if (pass.bound) return@aroundMethod chain.proceed()
                val shapeArg = chain.args[2]
                val stock = chain.args[1] as Long
                if (stock and 0xffffffffL != 0L || stock ushr 56 < 6L) return@aroundMethod chain.proceed()
                if (pass.renderer.mode == PlateMode.SUBMENU && pass.nativeShape != null && shapeArg === rectangle) {
                    val args = chain.args.toTypedArray()
                    args[0] = then.invoke(args[0], mask(pass.renderer.surface))
                    return@aroundMethod chain.proceed(args)
                }
                val nativeShape = when (pass.renderer.mode) {
                    PlateMode.BOX -> pass.boxShape?.takeIf { it === shapeArg || shapeArg === rectangle }
                    PlateMode.FILL -> shapeArg?.takeIf(rounded::isInstance)
                    PlateMode.CLIPPED_FILL -> pass.nativeShape?.takeIf {
                        shapeArg === rectangle && chain.args[0] === pass.clipModifier
                    }
                    else -> null
                } ?: return@aroundMethod chain.proceed()
                pass.bound = true
                val args = chain.args.toTypedArray()
                args[0] = then.invoke(args[0], mask(pass.renderer.surface))
                then.invoke(chain.proceed(args), overlay(pass.renderer.surface, nativeShape))
            }
            handles += ModernHook.aroundMethod(animatedVisibility) { chain ->
                val pass = active.get() ?: return@aroundMethod chain.proceed()
                val nativeShape = pass.nativeShape
                if (pass.renderer.mode != PlateMode.SUBMENU || nativeShape == null || pass.bound) {
                    return@aroundMethod chain.proceed()
                }
                pass.bound = true
                val args = chain.args.toTypedArray()
                args[1] = then.invoke(args[1], overlay(pass.renderer.surface, nativeShape))
                args[7] = (args[7] as Int) and 0b1110000.inv()
                args[8] = (args[8] as Int) and 0b10.inv()
                chain.proceed(args)
            }
            handles += ModernHook.aroundMethod(fillWidth) { chain ->
                val result = chain.proceed()
                val pass = active.get() ?: return@aroundMethod result
                if (pass.renderer.mode != PlateMode.ROW || pass.bound || ++pass.rows != pass.renderer.row) {
                    return@aroundMethod result
                }
                pass.bound = true
                then.invoke(result, overlay(pass.renderer.surface, rowShape))
            }

            val qs = "com.android.systemui.samsung.quicksetting.ui."
            val tiles = qs + "tiles."
            val media = qs + "media.components."
            val panel = qs + "panel."
            val settings = qs + "settings."
            val details = qs + "details."
            val renderers = listOf(
                Renderer(Surface.CARD, tiles + "IconTextTileBackgroundKt", "IconTextTileBackground", PlateMode.BOX, 4),
                Renderer(Surface.GRID, tiles + "QuickTileBoxKt", "QuickTileBox", PlateMode.CLIP, 5),
                Renderer(Surface.SLIDER, tiles + "IconProgressBarWithTileKt", "IconProgressBarWithTile-S4AO9yg", PlateMode.CLIP),
                Renderer(Surface.SIM_BAR, tiles + "MultiSIMPreferredBarKt\$\$ExternalSyntheticLambda5", "invoke", PlateMode.BOX, 3),
                Renderer(Surface.SIM_POPUP, "base.packages.SystemUI.samsung.compose_qs.src." + tiles +
                    "MultiSIMPreferredPopupKt", "MultiSIMPreferredPopup", PlateMode.CLIPPED_FILL),
                Renderer(Surface.DETAIL_PANEL, details + "PopupDetailSceneKt", "DetailBG-J5j9r9c", PlateMode.FILL),
                Renderer(Surface.HEADER_TOOLTIP, panel + "SecQuickSettingHeaderKt\$\$ExternalSyntheticLambda5", "invoke", PlateMode.FILL, 3),
                Renderer(Surface.SECURITY_FOOTER, qs + "banner.SecurityFooterKt\$\$ExternalSyntheticLambda0", "invoke", PlateMode.ROW, 3),
                Renderer(Surface.MEDIA, media + "OneUI90MediaPlayerPageKt", "OneUI90MediaPlayerPage-77lviBw", PlateMode.CLIPPED_FILL),
                Renderer(Surface.MEDIA, media + "MediaPlayerPageKt", "MediaPlayerPage-iL6HX2k", PlateMode.CLIPPED_FILL),
                Renderer(Surface.EMPTY_MEDIA, tiles + "OneUI90EmptyMediaPlayerKt\$\$ExternalSyntheticLambda2", "invoke", PlateMode.BOX, 3),
                Renderer(Surface.CONTROL_BAR, tiles + "QuickControlBarKt\$\$ExternalSyntheticLambda3", "invoke", PlateMode.BOX, 3),
                Renderer(Surface.PANEL_SETTINGS, settings + "PanelSettingGroupKt", "PanelSettingGroup", PlateMode.CLIPPED_FILL),
                Renderer(Surface.PANEL_SETTINGS, settings + "PanelSettingViewTypeSelectorKt", "PanelSettingViewTypeSelector", PlateMode.CLIPPED_FILL),
                Renderer(Surface.PANEL_SUBMENU, settings + "SubMenuContainerKt", "SubMenuContainer", PlateMode.SUBMENU),
                Renderer(Surface.BRIGHTNESS_ACTIONS, details + "SecBrightnessDetailSceneKt", "BrightBarDetailCommandButtons", PlateMode.ROW),
                Renderer(Surface.SOUNDCRAFT_ACTIONS, details + "soundcraft.SoundCraftMainKt", "DetailCommandButtons", PlateMode.ROW, row = 2),
                Renderer(Surface.PRIVACY_ACTIONS, details + "screenprivacy.ScreenPrivacyDetailContentKt", "OptionItem", PlateMode.ROW, row = 2),
                Renderer(Surface.DND_ACTIONS, details + "DndDetailPageKt\$\$ExternalSyntheticLambda4", "invoke", PlateMode.ROW, 3),
                Renderer(Surface.EDITOR, panel + "ContextMenuKt", "ContextMenu-", PlateMode.FILL, 9),
                Renderer(Surface.VOLUME_OPTIONS,
                    "com.android.systemui.volume.panel.component.selector.ui.composable.VolumePanelRadioButtonsKt",
                    "VolumePanelRadioButtonBar-cjTkxnM", PlateMode.FILL),
                Renderer(Surface.VOLUME_POPUP,
                    "com.android.systemui.volume.panel.component.popup.ui.composable.VolumePanelPopup",
                    "PopupComposable", PlateMode.ROW, 4, row = 2),
            )
            for (renderer in renderers) runCatching {
                if (renderer.surface == Surface.HEADER_TOOLTIP) {
                    // PlainTooltip delegates its stock fill to a Material Surface content lambda.
                    val surface = type("androidx.compose.material3.SurfaceKt")
                    ModernHook.deoptimize(surface.declaredMethods.single { it.name == "surface-XO-JAsU" })
                    val content = type("androidx.compose.material3.SurfaceKt\$\$ExternalSyntheticLambda0")
                    ModernHook.deoptimize(content.getDeclaredMethod("invoke", Any::class.java, Any::class.java))
                }
                val method = type(renderer.owner).declaredMethods.single {
                    (it.name == renderer.method || (renderer.method.endsWith("-") && it.name.startsWith(renderer.method))) &&
                        (renderer.parameters == null || it.parameterCount == renderer.parameters)
                }
                ModernHook.deoptimize(method)
                handles += ModernHook.aroundMethod(method) { chain ->
                    val previous = active.get()
                    active.set(RenderPass(renderer))
                    try {
                        chain.proceed()
                    } finally {
                        if (previous == null) active.remove() else active.set(previous)
                    }
                }
                report(renderer.surface, GlassSurfaceStage.FOUND)
                HookEntry.log("GLASS_QUICK_PLATE_READY ${renderer.surface.name.lowercase()} ${method.name}")
            }.onFailure {
                HookEntry.log("GLASS_QUICK_PLATE_UNAVAILABLE ${renderer.owner} ${it.javaClass.simpleName}")
            }
        } catch (error: Throwable) {
            handles.asReversed().forEach(ModernHook.Handle::unhook)
            throw error
        }
    }

    private fun installHeaderActions(loader: ClassLoader, resources: Resources, modifier: Class<*>, then: Method,
        overlay: (Surface, Any) -> Any) {
        fun type(name: String) = DexSymbols.findClass(name, false, loader)
        val icons = listOf("tw_ic_ab_edit_mtrl", "tw_ic_ab_power_mtrl", "tw_ic_ab_setting_mtrl").mapNotNull {
            resources.getIdentifier(it, "drawable", "com.android.systemui").takeIf { id -> id != 0 }
        }.toSet()
        check(icons.isNotEmpty())
        val circle = requireNotNull(type("androidx.compose.foundation.shape.RoundedCornerShapeKt")
            .getField("CircleShape").get(null))
        val size = type("androidx.compose.foundation.layout.SizeKt").getDeclaredMethod("size-VpY3zN4",
            modifier, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        // This lambda creates the button's touch-sized Box inside Samsung's tooltip host.
        val owner = type("com.android.systemui.samsung.quicksetting.ui.panel.SecQuickSettingHeaderKt\$\$ExternalSyntheticLambda14")
        val renderer = owner.getDeclaredMethod("invoke", Any::class.java, Any::class.java)
        val icon = owner.getDeclaredField("f\$9").apply { isAccessible = true }
        val headerButton = ThreadLocal<Boolean>()
        val hook = ModernHook.aroundMethod(size) { chain ->
            val result = chain.proceed()
            if (headerButton.get() != true) return@aroundMethod result
            headerButton.remove()
            then.invoke(result, overlay(Surface.HEADER_ACTION, circle))
        }
        try {
            ModernHook.deoptimize(renderer)
            ModernHook.aroundMethod(renderer) { chain ->
                if (icon.getInt(chain.thisObject) !in icons) {
                    return@aroundMethod chain.proceed()
                }
                val previous = headerButton.get()
                headerButton.set(true)
                try {
                    chain.proceed()
                } finally {
                    if (previous == null) headerButton.remove() else headerButton.set(previous)
                }
            }
        } catch (error: Throwable) {
            hook.unhook()
            throw error
        }
    }

    private fun installEditorButtons(
        loader: ClassLoader,
        resources: Resources,
        modifier: Class<*>,
        then: Method,
        overlay: (Surface, Any) -> Any,
    ) {
        fun type(name: String) = DexSymbols.findClass(name, false, loader)
        val panel = "com.android.systemui.samsung.quicksetting.ui.panel."
        val button = type(panel + "SecQuickSettingHeaderKt").declaredMethods.single {
            it.name == "RoundedButton" && it.parameterCount == 7 && it.parameterTypes[0] == modifier
        }
        val tileBoxButton = type("com.android.systemui.samsung.quicksetting.ui.tilebox.QuickTileBoxSceneMainKt")
            .declaredMethods.single {
                it.name == "TileBoxDoneButton" && it.parameterCount == 5 && it.parameterTypes[0] == modifier
            }
        val editLabels = listOf("qs_customizer_settings", "quick_settings_done", "sec_quick_settings_done", "done")
            .map { resources.getIdentifier(it, "string", "com.android.systemui") }.filter { it != 0 }.toSet()
        val buttonShape = type("androidx.compose.foundation.shape.RoundedCornerShapeKt").getDeclaredMethod(
            "RoundedCornerShape-0680j_4", Float::class.javaPrimitiveType,
        ).invoke(null, 24f) ?: error("Editor button outline unavailable")
        val handles = mutableListOf<ModernHook.Handle>()
        try {
            for ((renderer, changedIndex) in listOf(button to 5, tileBoxButton to 4)) {
                ModernHook.deoptimize(renderer)
                handles += ModernHook.aroundMethod(renderer) { chain ->
                    val editorButton = (chain.args[1] as? Int)?.let(editLabels::contains) == true
                    if (renderer == button && !editorButton) {
                        return@aroundMethod chain.proceed()
                    }
                    val args = chain.args.toTypedArray()
                    args[0] = then.invoke(args[0], overlay(Surface.EDITOR, buttonShape))
                    // Ask Compose to compare the new modifier rather than reuse the caller's flag.
                    args[changedIndex] = (args[changedIndex] as Int) and 0b1110.inv()
                    chain.proceed(args)
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
                    report(surface, GlassSurfaceStage.REFRACTED)
                }
                return
            }
            material.drawSurface(
                frame.canvas, outline.left, outline.top, outline.right, outline.bottom, outline.radii,
                strength, dark, fill = !rim, rim = rim, glint = frame.light, profile = surface.profile,
            )
        } catch (error: Exception) {
            disable(surface, error)
        }
    }

    private fun report(surface: Surface, stage: GlassSurfaceStage) {
        val name = if (surface == Surface.EDITOR) GlassSystemCatalog.EDITOR
            else GlassSystemCatalog.QUICK_COMPOSE_PREFIX + surface.name.lowercase()
        onSurface(name, stage)
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
