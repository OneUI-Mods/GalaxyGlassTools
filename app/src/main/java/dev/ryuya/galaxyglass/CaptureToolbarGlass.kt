package dev.ryuya.galaxyglass

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.OneShotPreDrawListener
import dev.ryuya.galaxyglass.hook.HookCallback
import dev.ryuya.galaxyglass.hook.Hooks
import dev.ryuya.galaxyglass.compat.DexSymbols
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.min

/** Styles only Samsung's existing toolbar plates and leaves their native blur and hit areas intact. */
internal class CaptureToolbarGlass(private val runtime: GlassCaptureRuntime) {
    private val main = Handler(Looper.getMainLooper())
    private val plates = WeakHashMap<View, Plate>()
    private val screenshotPlates = WeakHashMap<View, Plate>()
    private val guideParts = WeakHashMap<View, GuideBinding>()
    private val reported = mutableSetOf<String>()
    private var reduceTransparency = readTransparencySetting()
    private val targets = buildMap {
        val resources = runtime.context.resources
        val packageName = runtime.context.packageName
        for ((name, views) in LAYOUTS) {
            val layout = resources.getIdentifier(name, "layout", packageName)
            if (layout == 0) continue
            val anchors = views.mapNotNull { anchor ->
                val id = anchor.name?.let { resources.getIdentifier(it, "id", packageName) }
                if (id == 0) null else Target(id, anchor.cornerDp, anchor.ancestors)
            }
            if (anchors.isNotEmpty()) put(layout, Targets(name, anchors))
        }
        for (name in GUIDES) {
            val layout = resources.getIdentifier(name, "layout", packageName)
            if (layout != 0) put(layout, Targets(name, emptyList()))
        }
    }

    private data class Anchor(val name: String?, val cornerDp: Int? = null, val ancestors: Int = 0)
    private data class Target(val id: Int?, val cornerDp: Int?, val ancestors: Int)
    private data class Targets(val name: String, val anchors: List<Target>)

    private fun install(loader: ClassLoader?) {
        if (targets.isEmpty()) return
        val screenshotDraw = runCatching { installScreenshotDraw(loader) }.onFailure {
            Hooks.log("Capture screenshot plate will use its layout: ${it.javaClass.simpleName}")
        }.isSuccess
        val inflate = LayoutInflater::class.java.getMethod("inflate", Int::class.javaPrimitiveType,
            ViewGroup::class.java, Boolean::class.javaPrimitiveType)
        Hooks.hook(inflate, object : HookCallback() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                if (targets[p.args?.getOrNull(0) as? Int] == null) return
                val parent = p.args?.getOrNull(1) as? ViewGroup ?: return
                p.setObjectExtra("glassChildCount", parent.childCount)
            }

            override fun afterHookedMethod(p: MethodHookParam) {
                val spec = targets[p.args?.getOrNull(0) as? Int] ?: return
                if (screenshotDraw && spec.name == SCREENSHOT_LAYOUT) return
                val inflated = p.result as? View ?: return
                if (inflated.context.packageName != runtime.context.packageName) return
                val parent = p.args?.getOrNull(1) as? ViewGroup
                val before = p.getObjectExtra("glassChildCount") as? Int
                val roots = if (parent != null && inflated === parent && before != null) {
                    (before until parent.childCount).map(parent::getChildAt)
                } else listOf(inflated)
                fun bindRoots() {
                    for (root in roots) {
                        if (spec.name in GUIDES) {
                            bindGuide(root, spec.name)
                            continue
                        }
                        for (anchor in spec.anchors) {
                            var view = if (anchor.id == null) root else root.findViewById<View>(anchor.id)
                            repeat(anchor.ancestors) { view = view?.parent as? View }
                            if (anchor.ancestors > 0 && view?.background == null) continue
                            if (view != null) bind(view, spec.name, anchor.cornerDp)
                        }
                    }
                }
                if (main.looper.isCurrentThread) bindRoots() else main.post(::bindRoots)
            }
        })
        runtime.onOptionsChanged { main.post { refresh() } }
        runtime.context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(REDUCE_TRANSPARENCY), false, object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    reduceTransparency = readTransparencySetting()
                    refresh()
                }
            },
        )
        Hooks.log("CAPTURE_TOOLBAR_GLASS_READY layouts=${targets.size}")
    }

    private fun installScreenshotDraw(loader: ClassLoader?) {
        val toolbar = DexSymbols.findClass(
            "com.samsung.android.app.smartcapture.screenshot.toolbar.ToolbarBackgroundView", loader,
        )
        Hooks.hook(toolbar.getDeclaredMethod("onDraw", Canvas::class.java), object : HookCallback() {
            override fun afterHookedMethod(p: MethodHookParam) {
                if (!enabled() || !main.looper.isCurrentThread) return
                val view = p.thisObject as? View ?: return
                val canvas = p.args?.getOrNull(0) as? Canvas ?: return
                if (!view.isShown || view.width <= 0 || view.height <= 0) return
                val plate = screenshotPlates.getOrPut(view) { createPlate(SCREENSHOT_LAYOUT, null) }
                plate.update(view, invalidate = false)
                plate.draw(canvas)
            }
        })
    }

    private fun refresh() {
        plates.entries.toList().forEach { (view, effect) -> update(view, effect) }
        screenshotPlates.keys.toList().forEach(View::invalidate)
        guideParts.entries.toList().forEach { (view, guide) -> guide.refresh(view) }
    }

    private fun bindGuide(root: View, layout: String) {
        val panel = (root as? ViewGroup)?.getChildAt(0) as? ViewGroup ?: return
        if (panel.childCount != 2 || (0 until panel.childCount).any { panel.getChildAt(it).background == null }) return
        for (index in 0 until panel.childCount) {
            val view = panel.getChildAt(index)
            if (view in guideParts) continue
            val guide = GuideBinding(panel, layout)
            guideParts[view] = guide
            view.addOnAttachStateChangeListener(guide)
            if (view.isAttachedToWindow) guide.onViewAttachedToWindow(view)
        }
    }

    private inner class GuideBinding(panel: View, private val layout: String) : View.OnAttachStateChangeListener {
        private val panel = WeakReference(panel)
        private var part: GuidePart? = null

        override fun onViewAttachedToWindow(view: View) {
            OneShotPreDrawListener.add(view) { refresh(view) }
        }

        override fun onViewDetachedFromWindow(view: View) = restore(view)

        fun refresh(view: View) {
            val region = panel.get()
            if (!enabled() || !view.isAttachedToWindow || region == null) {
                restore(view)
                return
            }
            val stock = view.background ?: return
            if (part === stock) {
                view.invalidate()
                return
            }
            val wrapper = GuidePart(view, region, stock) { didDraw(layout) }
            part = wrapper
            view.background = wrapper
            stock.callback = wrapper
        }

        private fun restore(view: View) {
            val wrapper = part ?: return
            if (view.background === wrapper) view.background = wrapper.stock
            part = null
        }
    }

    private inner class GuidePart(view: View, panel: View, val stock: Drawable, private val onDrawn: () -> Unit) :
        DrawableWrapper(stock) {
        private val view = WeakReference(view)
        private val panel = WeakReference(panel)
        private val highlight = GlassTipHighlight()
        private val position = IntArray(2)
        private val matrix = Matrix()

        override fun draw(canvas: Canvas) {
            val host = view.get()
            val region = panel.get()
            if (!enabled() || host == null || region == null || region.height <= 0 ||
                bounds.isEmpty || !canvas.isHardwareAccelerated) {
                super.draw(canvas)
                return
            }
            region.getLocationOnScreen(position)
            matrix.setTranslate(position[0].toFloat(), position[1].toFloat())
            host.transformMatrixToLocal(matrix)
            highlight.draw(canvas, stock, bounds, region.height, host.resources.configuration.isNightModeActive,
                runtime.strength, matrix)
            onDrawn()
        }
    }

    private fun enabled(): Boolean {
        return runtime.enabled && !reduceTransparency
    }

    private fun bind(view: View, layout: String, cornerDp: Int?) {
        if (plates.containsKey(view)) return
        val effect = createPlate(layout, cornerDp)
        plates[view] = effect
        view.addOnLayoutChangeListener { target, _, _, _, _, _, _, _, _ -> update(target, effect) }
        val lifecycle = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                OneShotPreDrawListener.add(v) { update(v, effect) }
            }
            override fun onViewDetachedFromWindow(v: View) {
                v.overlay.remove(effect)
                effect.restoreBackground()
            }
        }
        view.addOnAttachStateChangeListener(lifecycle)
        if (view.isAttachedToWindow) lifecycle.onViewAttachedToWindow(view)
    }

    private fun update(view: View, effect: Plate) {
        if (!enabled() || !view.isAttachedToWindow || view.width <= 0 || view.height <= 0) {
            view.overlay.remove(effect)
            effect.restoreBackground()
            return
        }
        if (!effect.rimOnly) effect.makeTranslucent(view.background)
        effect.update(view)
        view.overlay.add(effect)
    }

    private fun readTransparencySetting(): Boolean =
        Settings.System.getInt(runtime.context.contentResolver, REDUCE_TRANSPARENCY, 0) != 0

    private fun createPlate(layout: String, cornerDp: Int?) =
        Plate(cornerDp, { runtime.strength }, rimOnly = layout == CAMERA_FRAME) { didDraw(layout) }

    private fun didDraw(layout: String) {
        runtime.didDraw()
        if (reported.add(layout)) Hooks.log("CAPTURE_TOOLBAR_GLASS_DRAWN $layout")
    }

    private class Plate(private val cornerDp: Int?, private val strength: () -> Int,
        val rimOnly: Boolean, private val onDrawn: () -> Unit) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val outline = Outline()
        private val stockBounds = Rect()
        private val pathBounds = RectF()
        private lateinit var face: LinearGradient
        private lateinit var rim: LinearGradient
        private var radius = 0f
        private var density = 1f
        private var dark = false
        private var stock: Drawable? = null
        private var stockAlpha = 255
        private var appliedAlpha: Int? = null

        fun makeTranslucent(background: Drawable?) {
            if (background !== stock) {
                restoreBackground()
                stock = background
            }
            if (background !is ColorDrawable && background !is GradientDrawable) return
            if (background.alpha != appliedAlpha) stockAlpha = background.alpha
            val alpha = stockAlpha * (400 - strength().coerceIn(0, 100)) / 400
            if (alpha != background.alpha) background.mutate().alpha = alpha
            appliedAlpha = alpha
        }

        fun restoreBackground() {
            val background = stock
            stock = null
            if (background != null && background.alpha == appliedAlpha) background.alpha = stockAlpha
            appliedAlpha = null
        }

        fun update(view: View, invalidate: Boolean = true) {
            val night = view.resources.configuration.isNightModeActive
            outline.setEmpty()
            view.background?.getOutline(outline)
            val native = outline.radius > 0 && outline.getRect(stockBounds) && !stockBounds.isEmpty
            if (!native) stockBounds.set(0, 0, view.width, view.height)
            val scale = view.resources.displayMetrics.density
            val limit = min(stockBounds.width(), stockBounds.height()) / 2f
            val corner = if (native) outline.radius else min(limit, (cornerDp?.times(scale) ?: limit))
            if (bounds == stockBounds && dark == night && radius == corner && density == scale && ::rim.isInitialized) return
            bounds = stockBounds
            dark = night
            radius = corner
            density = scale
            face = gradient(if (dark) intArrayOf(0x46e8f3ff, 0x0627323c, 0x39101a27)
                else intArrayOf(0x59ffffff, 0x06f2f7ff, 0x24476982))
            rim = gradient(if (dark) intArrayOf(0xcff4f9ff.toInt(), 0x42e2f0ff, 0x72364658)
                else intArrayOf(0xeaffffff.toInt(), 0x509eabb7, 0x795c7a91))
            if (invalidate) invalidateSelf()
        }

        private fun gradient(colors: IntArray): LinearGradient =
            LinearGradient(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right * .64f + bounds.left * .36f,
                bounds.bottom.toFloat(), colors, floatArrayOf(0f, .46f, 1f), Shader.TileMode.CLAMP)

        override fun draw(canvas: Canvas) {
            if (!::rim.isInitialized || bounds.isEmpty) return
            val line = density * .76f
            pathBounds.set(bounds)
            pathBounds.inset(line, line)
            val curve = (radius - line).coerceAtLeast(0f)
            paint.alpha = 255 * strength().coerceIn(0, 100) / 100
            if (!rimOnly) {
                paint.style = Paint.Style.FILL
                paint.shader = face
                canvas.drawRoundRect(pathBounds, curve, curve, paint)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = line
            paint.shader = rim
            canvas.drawRoundRect(pathBounds, curve, curve, paint)
            onDrawn()
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Required by Drawable")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    companion object {
        private const val REDUCE_TRANSPARENCY = "accessibility_reduce_transparency"
        private const val CAMERA_FRAME = "screen_recorder_view_rec_start_item_floating"
        private const val SCREENSHOT_LAYOUT = "scroll_capture_action_button_stand_alone"
        private val GUIDES = setOf("scroll_capture_guide_pop_up_layout", "pin_guide_pop_up_layout",
            "smart_select_guide_pop_up_layout")
        private fun anchors(vararg ids: String, cornerDp: Int? = null) = ids.map { Anchor(it, cornerDp) }

        private val imageActions = anchors("capture_ai_edit_button", "capture_ai_pin_button",
            "capture_ai_copy_button", "capture_ai_share_button", "capture_ai_save_button")
        private val textActions = anchors("capture_ai_pin_button", "capture_ai_copy_button", "capture_ai_share_button")
        private val selectionCapsule = anchors("capture_ai_bottom_action")
        private val capsuleMenu = anchors("entity_scroll_view", cornerDp = 18)

        private val LAYOUTS = mapOf(
            SCREENSHOT_LAYOUT to anchors("screenshot_toolbar_background"),
            "screen_recorder_floating_button" to anchors("button_layout", "pause_stop_layout", "handler_layout") +
                anchors("pen_setting_layout", cornerDp = 20),
            "screen_recorder_countdown_view" to anchors("skip_countdown_button"),
            "screen_recorder_pointer_guide_pop_up_layout" to anchors(
                "screen_recorder_pointer_guided_tour_popup_pointer_bubble",
                "screen_recorder_pointer_guided_tour_popup_side_bubble", cornerDp = 18),
            "partial_recording_toolbar" to anchors("partial_toolbar_record_container_view",
                "partial_toolbar_operation_container_view", "record_area_close_button"),
            "scroll_capture_main_screen_write" to anchors("capture_more_two_btn_layout"),
            "flash_annotate_edit_toolbar" to anchors("flash_annotate_edit_toolbar"),
            "flash_annotate_toolbars_container" to anchors("toolbar_action_preview"),
            "flashannotate_erase_all_view" to anchors("erase_all"),
            "smart_select_toolbar_layout" to anchors("toolbar_background_for_anim"),
            "smart_select_preview_autoshape_toolbar_layout" to anchors("smart_select_auto_shape_toolbar"),
            "smart_select_preview_bottom_toolbar_layout" to anchors("smart_select_bottom_menu"),
            "smart_select_preview_bottom_toolbar_layout_new" to anchors("capture_gif_toolbar_container"),
            "smart_select_preview_layout" to anchors("smart_select_preview_pin_confirm_button", "copy_screen_container"),
            "smart_select_preview_extract_text_layout" to anchors("smart_select_meta_text_card_view", cornerDp = 24),
            "popup_container" to listOf(Anchor(null, 22)),
            "popup_menu_button" to listOf(Anchor(null, 18)),
            "popup_overflow_button" to listOf(Anchor(null, 18)),
            "partial_resizable_crop_view" to anchors("record_area_moving_button", cornerDp = 24),
            CAMERA_FRAME to anchors("fl_profile_white_frame", cornerDp = 48),
            "custom_toast" to anchors("custom_toast_textview", cornerDp = 20),
            "ai_select_ftu_dialog" to listOf(Anchor(null, 24)),
            "ai_select_capsule_external_search" to anchors("search_button", cornerDp = 18),
            "edge_panel_galaxy_ai" to anchors("content_view", cornerDp = 24),
            "rectangle_resizable_crop_view" to anchors("crop_confirm_button"),
            "pin_resizable_crop_view" to anchors("pin_confirm_button"),
            "pinui_vision_text_icon" to anchors("vision_text_button"),
            "pin_hover_layout" to listOf(Anchor("pin_hover_menu_save_button", ancestors = 2)),
            "ai_select_toolbar_image_toolbar_layout" to imageActions,
            "ai_select_toolbar_image_toolbar_layout_with_text_button" to imageActions + anchors("capture_ai_text_button"),
            "ai_select_toolbar_text_toolbar_layout" to textActions,
            "ai_select_toolbar_text_toolbar_layout_with_text_button" to textActions + anchors("capture_ai_text_button"),
            "ai_select_capsule_basic_capsule_view" to selectionCapsule + capsuleMenu,
            "ai_select_capsule_entity_capsule_view" to selectionCapsule + capsuleMenu,
            "ai_select_capsule_qr_capsule_view" to selectionCapsule,
            "ai_select_capsule_toggle_capsule_view" to selectionCapsule,
            "ai_select_capsule_contextual_capsule_view" to anchors("nudge_container"),
            "ai_select_capsule_overlapped_popup_capsule_layout" to capsuleMenu,
            "ai_select_capsule_more_menu_layout" to capsuleMenu,
            "ai_select_capsule_popup_capsule_layout" to capsuleMenu,
            "ai_select_capsule_popup_window_list_item_view" to anchors("entity_item_layout", cornerDp = 18),
            "ai_select_translate_lang_dialog_popup" to anchors("language_select_dialog_layout", cornerDp = 22),
            "ai_select_translate_lang_change_layout" to
                listOf(Anchor("ai_select_translate_lang_change_close_button", cornerDp = 20, ancestors = 2)),
            "language_select_dialog_popup" to anchors("language_select_dialog_inner_layout", cornerDp = 22),
            "ai_drawing_toolbar_layout" to anchors("pen_btn", "eraser_btn", "undoBtn", "redoBtn",
                "clear_button", "add_photo_button", "reset_button", "save_button", "share_button",
                "transparency_button", "more_menu", "close_button"),
            "ai_drawing_option_popup_layout" to anchors("popup_layout", cornerDp = 22),
            "ai_drawing_erase_all_popup_layout" to listOf(Anchor(null, 20)),
            "ai_drawing_transparency_popup_layout" to anchors("blur_seek_bar_wrapper", cornerDp = 20),
            "style_popup_layout" to anchors("style_popup_main_layout", cornerDp = 22),
            "setting_popup_layout" to anchors("total_layout", cornerDp = 20),
            "setting_color_setting_popup" to anchors("top_layout", cornerDp = 20),
            "setting_brush_setting_popup_layout" to anchors("drawing_brush_setting_popup_horizontal_view", cornerDp = 20),
            "setting_erase_all_menu_layout" to listOf(Anchor(null, 20)),
            "setting_favorite_option_menu_layout" to listOf(Anchor(null, 20)),
            "vision_text_help_popup" to anchors("vision_text_help_text_container", cornerDp = 20),
            "gif_resizable_crop_view" to anchors("gif_toolbar_record_container_view", "record_area_close_button",
                "stop_button_background", "stop_area_toolbar_close_btn"),
            "qr_dialog" to anchors("txtQRContentLayout", cornerDp = 20),
            "zoom_toolbar_layout" to anchors("zoom_in", "zoom_out"),
        )

        fun install(runtime: GlassCaptureRuntime, loader: ClassLoader?) = CaptureToolbarGlass(runtime).install(loader)
    }
}
