package dev.ryuya.galaxyglass

import android.app.Application
import android.app.Dialog
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.ListPopupWindow
import android.widget.PopupWindow
import androidx.annotation.RequiresApi
import androidx.core.view.OneShotPreDrawListener
import dev.ryuya.galaxyglass.compat.DexSymbols
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Adds glass to identified SystemUI plates as their owning layout is inflated. */
@RequiresApi(33)
internal class GlassSystemSurfaces(
    private val app: Application,
    private val main: Handler,
    private val strength: () -> Int,
    private val lockStrength: () -> Int,
    private val lockScreenVisible: () -> Boolean,
    private val unlocked: () -> Boolean,
    private val screenOn: () -> Boolean,
    private val material: GlassMaterial,
    private val panelBackdrop: () -> GlassPanelBackdrop?,
    private val canUseShadeBackdrop: Boolean,
    private val onTransparencyChanged: () -> Unit,
    private val onDrawn: () -> Unit,
    private val onLockDrawn: () -> Unit,
    private val onSurface: (String, GlassSurfaceStage) -> Unit,
) {
    private val catalog = GlassSystemCatalog.resolve(app.resources)
    private val scopes = WeakHashMap<View, Scope>()
    private val owners = WeakHashMap<View, Scope>()
    private val windows = WeakHashMap<View, WeakReference<Window>>()
    private val loggedSurfaces = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val loggedRefractions = mutableSetOf<String>()
    private val refracting = WeakHashMap<ViewGroup, Refraction>()
    private val notificationPlates = WeakHashMap<View, Unit>()
    private val artworkId = app.resources.getIdentifier("sec_album_art", "id", app.packageName)
    private val parentPanelId = app.resources.getIdentifier("parentPanel", "id", "android")
    private val samsungParentPanelId = app.resources.getIdentifier("parentPanel", "id", app.packageName)
    private var reduceTransparency = reducedTransparency()
    private var tips: GlassSamsungTips? = null
    private val blurRadiusField = runCatching {
        DexSymbols.findClass("com.android.internal.policy.PhoneWindow")
            .getDeclaredField("mBackgroundBlurRadius").apply { isAccessible = true }
    }.getOrNull()

    init {
        check(catalog.isNotEmpty()) { "No SystemUI glass anchors resolved" }
        val inflate = LayoutInflater::class.java.getMethod(
            "inflate", Int::class.javaPrimitiveType, ViewGroup::class.java, Boolean::class.javaPrimitiveType,
        )
        val show = Dialog::class.java.getMethod("show")
        val layouts = ModernHook.aroundMethod(inflate) { chain ->
            val spec = catalog[chain.args[0] as Int] ?: return@aroundMethod chain.proceed()
            val parent = chain.args[1] as? ViewGroup
            val before = parent?.childCount ?: 0
            val result = chain.proceed()
            safely(spec.name) {
                if (parent != null && result === parent) {
                    for (index in before until parent.childCount) observe(parent.getChildAt(index), spec)
                } else if (result is View) {
                    observe(result, spec)
                }
            }
            result
        }
        try {
            ModernHook.aroundMethod(show) { chain ->
                val result = chain.proceed()
                safely("dialog") { observeWindow((chain.thisObject as Dialog).window) }
                result
            }
        } catch (error: Throwable) {
            layouts.unhook()
            throw error
        }
        installNativePopups()
        installPopup(SAMSUNG_TOOLTIP) {
            tips = GlassSamsungTips({ if (allowsEditorGlass()) strength() else 0 }) {
                reportDrawn(SAMSUNG_TOOLTIP)
            }
        }
        installPopup(GlassSystemCatalog.BUBBLE_FLYOUT, ::installBubbleFlyout)
        if (canUseShadeBackdrop) runCatching { installShadeRefraction() }.onFailure {
            HookEntry.log("GLASS_SYSTEM_BACKDROP_UNAVAILABLE ${it.javaClass.simpleName}")
        }
        if (canUseShadeBackdrop) runCatching { installNotificationControls() }.onFailure {
            HookEntry.log("GLASS_NOTIFICATION_CONTROLS_UNAVAILABLE ${it.javaClass.simpleName}")
        }
        app.contentResolver.registerContentObserver(Settings.System.getUriFor(REDUCE_TRANSPARENCY), false,
            object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    reduceTransparency = reducedTransparency()
                    refresh()
                    onTransparencyChanged()
                }
            })
        observeExistingShade()
        HookEntry.log("GLASS_SYSTEM_CATALOG_READY layouts=${catalog.size}")
    }

    fun refresh() {
        tips?.refresh()
        scopes.values.toList().forEach { it.refresh() }
    }

    fun backdropChanged() {
        refracting.keys.toList().forEach(View::invalidate)
        notificationPlates.keys.toList().filter(View::isShown).forEach(View::invalidate)
    }

    fun allowsEditorGlass(): Boolean = !reduceTransparency && unlocked() && screenOn()

    private fun reportDrawn(name: String) {
        if (!loggedSurfaces.add(name)) return
        onSurface(name, GlassSurfaceStage.DRAWN)
        onDrawn()
    }

    private fun installBubbleFlyout() {
        val owner = DexSymbols.findClass("com.android.wm.shell.bubbles.BubbleFlyoutView", false, app.classLoader)
        val remaining = owner.getField("mPercentStillFlyout")
        val progress = owner.getField("mPercentTransitionedToDot")
        val tint = owner.getField("mFloatingBackgroundColor")
        val rect = owner.getField("mBgRect")
        val offsetX = owner.getField("mBgTranslationX")
        val offsetY = owner.getField("mBgTranslationY")
        val cardCorner = owner.getField("mCornerRadius")
        val dotCorner = owner.getField("mNewDotRadius")
        val draw = owner.getDeclaredMethod("onDraw", Canvas::class.java)
        ModernHook.aroundMethod(draw) { chain ->
            val view = chain.thisObject as View
            val canvas = chain.args[0] as Canvas
            val level = strength()
            val window = view.rootView.layoutParams as? WindowManager.LayoutParams
            if (level <= 0 || !allowsEditorGlass() || !main.looper.isCurrentThread ||
                !canvas.isHardwareAccelerated || window == null ||
                window.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) {
                return@aroundMethod chain.proceed()
            }
            val fade = ((remaining.getFloat(view) - .18f) / .7f).coerceIn(0f, 1f)
            val opacity = fade * fade * (3f - 2f * fade)
            val drawingLevel = (level * opacity).roundToInt()
            val original = tint.getInt(view)
            if (drawingLevel <= 0 || original ushr 24 == 0) return@aroundMethod chain.proceed()
            val alpha = ((original ushr 24) * (1f - .46f * opacity * level / 100f)).roundToInt()
            tint.setInt(view, (alpha shl 24) or (original and 0x00ffffff))
            val result = try {
                chain.proceed()
            } finally {
                tint.setInt(view, original)
            }
            safely(GlassSystemCatalog.BUBBLE_FLYOUT) {
                val bounds = rect.get(view) as RectF
                if (bounds.isEmpty) return@safely
                val dx = offsetX.getFloat(view)
                val dy = offsetY.getFloat(view)
                val moving = progress.getFloat(view).coerceIn(0f, 1f)
                val radius = cardCorner.getFloat(view) * (1f - moving) + dotCorner.getFloat(view) * moving
                material.draw(canvas, bounds.left + dx, bounds.top + dy, bounds.right + dx, bounds.bottom + dy,
                    radius, radius, drawingLevel, view.resources.configuration.isNightModeActive,
                    profile = GlassMaterial.Profile.SYSTEM_SURFACE)
                reportDrawn(GlassSystemCatalog.BUBBLE_FLYOUT)
            }
            result
        }
    }

    private fun installNativePopups() {
        for ((owner, name) in GlassSystemCatalog.popupMenus) {
            installPopup(name) {
                val type = DexSymbols.findClass(owner, false, app.classLoader)
                    .asSubclass(ListPopupWindow::class.java)
                val show = type.getDeclaredMethod("show")
                val quickEditor = name == "quick_panel_edit_popup"
                ModernHook.aroundMethod(show) { chain ->
                    val result = chain.proceed()
                    safely(name) {
                        val popup = chain.thisObject as ListPopupWindow
                        val list = popup.listView ?: return@safely
                        // Samsung mounts the editor's per-view blur on the ListView itself.
                        val plate = if (quickEditor) list else findPopupPlate(list) ?: list
                        observePopup(plate, name, keepFill = quickEditor)
                    }
                    result
                }
            }
        }
        installPopup(GlassSystemCatalog.QS_TOOLTIP) {
            val tooltip = DexSymbols.findClass("com.android.systemui.qs.buttons.QSTooltipWindow", false, app.classLoader)
            val window = tooltip.getDeclaredField("mTipWindow").apply { isAccessible = true }
            val show = tooltip.getDeclaredMethod("showToolTip", View::class.java, java.lang.Integer.TYPE)
            ModernHook.aroundMethod(show) { chain ->
                val result = chain.proceed()
                safely(GlassSystemCatalog.QS_TOOLTIP) {
                    val popup = window.get(chain.thisObject) as? PopupWindow ?: return@safely
                    if (!popup.isShowing) return@safely
                    val content = popup.contentView ?: return@safely
                    val plate = findPopupPlate(content) ?: return@safely
                    observePopup(plate, GlassSystemCatalog.QS_TOOLTIP)
                }
                result
            }
        }
    }

    private fun installPopup(name: String, install: () -> Unit) {
        runCatching(install).onFailure {
            HookEntry.log("GLASS_SYSTEM_POPUP_UNAVAILABLE $name ${it.javaClass.simpleName}")
        }
    }

    private fun findPopupPlate(content: View): View? = generateSequence(content) { it.parent as? View }
        .firstOrNull { view ->
            val background = view.background ?: return@firstOrNull false
            background !is ColorDrawable || background.color ushr 24 != 0
        }

    private fun observePopup(view: View, name: String, keepFill: Boolean = false) {
        val target = GlassSystemCatalog.Target(0, 22, keepFill = keepFill)
        observe(view, GlassSystemCatalog.Surface(name, listOf(target), dialog = false, lockScreen = false,
            notificationControls = null))
    }

    private data class Refraction(val name: String, val effect: GlassSystemSurfaceEffect, val media: Boolean)

    private fun installNotificationControls() {
        val type = DexSymbols.findClass(
            "com.android.systemui.statusbar.notification.row.NotificationGuts", false, app.classLoader,
        )
        val draw = type.getDeclaredMethod("onDraw", Canvas::class.java)
        val background = type.getDeclaredField("mBackground").apply { isAccessible = true }
        val actualHeight = type.getDeclaredField("mActualHeight").apply { isAccessible = true }
        val clipTop = type.getDeclaredField("mClipTopAmount").apply { isAccessible = true }
        val clipBottom = type.getDeclaredField("mClipBottomAmount").apply { isAccessible = true }
        val bounds = Rect()
        ModernHook.aroundMethod(draw) { chain ->
            val result = chain.proceed()
            if (!main.looper.isCurrentThread) return@aroundMethod result
            val view = chain.thisObject as ViewGroup
            val scope = scopes[view] ?: return@aroundMethod result
            if (scope.spec.notificationControls != GlassSystemCatalog.NotificationControls.PLATE) return@aroundMethod result
            safely(scope.spec.name) {
                val level = scope.visibleStrength(view)
                if (level <= 0) return@safely
                val stock = background.get(view) as? Drawable ?: return@safely
                bounds.set(0, clipTop.getInt(view).coerceAtLeast(0), view.width,
                    actualHeight.getInt(view) - clipBottom.getInt(view).coerceAtLeast(0))
                if (!bounds.intersect(0, 0, view.width, view.height)) return@safely
                val stage = scope.drawNotificationControls(view, chain.args[0] as Canvas, bounds, stock, level)
                for (index in 0 until view.childCount) {
                    val child = view.getChildAt(index)
                    val childScope = scopes[child] ?: continue
                    if (child.isShown &&
                        childScope.spec.notificationControls == GlassSystemCatalog.NotificationControls.CONTENT) {
                        onSurface(childScope.spec.name, stage)
                    }
                }
            }
            result
        }
    }

    private fun installShadeRefraction() {
        val dispatch = ViewGroup::class.java.getDeclaredMethod("dispatchDraw", Canvas::class.java)
        val child = ViewGroup::class.java.getDeclaredMethod("drawChild", Canvas::class.java, View::class.java,
            Long::class.javaPrimitiveType)
        val groupHook = ModernHook.aroundMethod(dispatch) { chain ->
            if (!main.looper.isCurrentThread || refracting.isEmpty()) return@aroundMethod chain.proceed()
            val group = chain.thisObject as ViewGroup
            val plate = refracting[group]
            if (plate != null && (!plate.media || group.findViewById<View>(artworkId)?.isShown != true)) {
                drawShadeBackdrop(group, chain.args[0] as Canvas, plate)
            }
            chain.proceed()
        }
        try {
            ModernHook.aroundMethod(child) { chain ->
                if (!main.looper.isCurrentThread || refracting.isEmpty()) return@aroundMethod chain.proceed()
                val group = chain.thisObject as ViewGroup
                val plate = refracting[group]
                val result = chain.proceed()
                if (plate?.media == true && (chain.args[1] as View).id == artworkId) {
                    drawShadeBackdrop(group, chain.args[0] as Canvas, plate)
                }
                result
            }
        } catch (error: Throwable) {
            groupHook.unhook()
            throw error
        }
    }

    private fun drawShadeBackdrop(view: ViewGroup, canvas: Canvas, plate: Refraction) = safely(plate.name) {
        val source = panelBackdrop() ?: return@safely
        if (!unlocked() || lockScreenVisible() || !screenOn() || reduceTransparency) return@safely
        val layout = view.rootView.layoutParams as? WindowManager.LayoutParams ?: return@safely
        if (layout.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) return@safely
        if (plate.effect.drawBackdrop(view, canvas, source, if (plate.media) 0.46f else 0.78f) &&
            loggedRefractions.add(plate.name)) {
            HookEntry.log("GLASS_SYSTEM_SURFACE_REFRACTION ${plate.name}")
            onSurface(plate.name, GlassSurfaceStage.REFRACTED)
        }
    }

    private fun observe(view: View, spec: GlassSystemCatalog.Surface) {
        if (Looper.myLooper() != main.looper) {
            main.post { safely(spec.name) { observe(view, spec) } }
            return
        }
        if (scopes.containsKey(view)) return
        onSurface(spec.name, GlassSurfaceStage.FOUND)
        val scope = Scope(view, spec)
        scopes[view] = scope
        view.addOnAttachStateChangeListener(scope)
        if (view.isAttachedToWindow) scope.onViewAttachedToWindow(view)
    }

    private fun observeWindow(window: Window?) {
        if (Looper.myLooper() != main.looper) {
            main.post { safely("dialog") { observeWindow(window) } }
            return
        }
        val decor = window?.peekDecorView() ?: return
        val attached = scopes.values.filter {
            it.spec.dialog && it.view.get()?.let { root -> root.isAttachedToWindow && root.rootView === decor } == true
        }
        if (attached.isEmpty()) return
        windows[decor] = WeakReference(window)
        attached.forEach { it.refresh() }
    }

    private fun observeExistingShade() = safely("existing shade") {
        val known = catalog.values.associateBy { it.name }
        val type = DexSymbols.findClass("android.view.WindowManagerGlobal")
        val global = type.getMethod("getInstance").invoke(null)
        val roots = type.getMethod("getWindowViews").invoke(global) as? List<*> ?: return@safely
        for (root in roots.filterIsInstance<View>()) {
            for (name in EXISTING_CHIPS) {
                val spec = known[name] ?: continue
                val id = spec.targets.single().id
                root.findViewById<View>(id)?.let { observe(it, spec) }
            }
            if (root.javaClass.name != SHADE_ROOT) continue
            fun find(name: String): View? {
                val id = app.resources.getIdentifier(name, "id", app.packageName)
                return if (id == 0) null else root.findViewById(id)
            }
            for ((layout, id) in EXISTING_LOCKSCREEN) {
                val spec = known[layout] ?: continue
                find(id)?.let { observe(it, spec) }
            }
            for ((layout, id) in EXISTING_PANEL_PLATES) {
                val spec = known[layout] ?: continue
                find(id)?.let { observe(it, spec) }
            }
            find("qs_detail_container")?.let { view ->
                known["sec_qs_detail_container"]?.let { observe(view, it) }
            }
            find("sec_qs_media_controls")?.let { view ->
                val layout = if (find("sec_qs_media_drag_and_drop") != null) "oneui_90_sec_media_view" else "sec_media_view"
                known[layout]?.let { observe(view, it) }
            }
            val footer = generateSequence(find("dismiss_text")) { it.parent as? View }
                .firstOrNull { it.javaClass.name == SHADE_FOOTER }
            if (footer != null) known["notification_2025_footer"]?.let { observe(footer, it) }
        }
    }

    private fun reducedTransparency() =
        Settings.System.getInt(app.contentResolver, REDUCE_TRANSPARENCY, 0) != 0

    private fun safely(name: String, work: () -> Unit) {
        try {
            work()
        } catch (error: Exception) {
            if (loggedSurfaces.add("error:$name")) {
                HookEntry.log("GLASS_SYSTEM_SURFACE_UNAVAILABLE $name ${error.javaClass.simpleName}")
            }
        }
    }

    private inner class Scope(root: View, val spec: GlassSystemCatalog.Surface) :
        View.OnAttachStateChangeListener, View.OnLayoutChangeListener, ViewTreeObserver.OnPreDrawListener,
        ViewTreeObserver.OnGlobalLayoutListener {
        val view = WeakReference(root)
        private val effects = WeakHashMap<View, GlassSystemSurfaceEffect>()
        private val blur = DialogBlur()
        private val notificationEffect by lazy { GlassSystemSurfaceEffect(material, false) }
        private var notificationStage = GlassSurfaceStage.FOUND
        private var panelObserver: ViewTreeObserver? = null
        private var panelStrength = -1

        override fun onViewAttachedToWindow(view: View) {
            if (spec.notificationControls == GlassSystemCatalog.NotificationControls.PLATE) notificationPlates[view] = Unit
            view.addOnLayoutChangeListener(this)
            panelObserver = view.viewTreeObserver.also { observer ->
                observer.addOnGlobalLayoutListener(this)
                if (spec.name in EXISTING_PANEL_PLATES) observer.addOnPreDrawListener(this)
            }
            OneShotPreDrawListener.add(view, ::refresh)
        }

        override fun onViewDetachedFromWindow(view: View) {
            if (spec.notificationControls == GlassSystemCatalog.NotificationControls.PLATE) notificationPlates.remove(view)
            view.removeOnLayoutChangeListener(this)
            panelObserver?.takeIf { it.isAlive }?.let { observer ->
                observer.removeOnGlobalLayoutListener(this)
                if (spec.name in EXISTING_PANEL_PLATES) observer.removeOnPreDrawListener(this)
            }
            panelObserver = null
            panelStrength = -1
            restore()
        }

        override fun onPreDraw(): Boolean {
            onGlobalLayout()
            return true
        }

        override fun onGlobalLayout() {
            val root = view.get() ?: return
            val current = visibleStrength(root)
            if (current != panelStrength) refresh()
        }

        override fun onLayoutChange(
            v: View, left: Int, top: Int, right: Int, bottom: Int,
            oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
        ) = refresh()

        fun visibleStrength(root: View): Int {
            val locked = lockScreenVisible()
            val onLock = spec.lockScreen || (locked && spec.name in MEDIA_LAYOUTS &&
                generateSequence(root.parent as? View) { it.parent as? View }.any {
                    it.javaClass.name == LOCK_MEDIA_CONTAINER
                })
            val level = when {
                onLock && locked -> lockStrength()
                !onLock && !locked && unlocked() -> strength()
                else -> 0
            }
            if (level <= 0 || reduceTransparency || !screenOn() || !root.isAttachedToWindow || !root.isShown) return 0
            val layout = root.rootView.layoutParams as? WindowManager.LayoutParams
            if (layout == null || layout.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) return 0
            return level
        }

        fun drawNotificationControls(root: ViewGroup, canvas: Canvas, bounds: Rect, stock: Drawable,
            level: Int): GlassSurfaceStage {
            val refracted = notificationEffect.drawNotificationControls(root, canvas, bounds, stock, level, panelBackdrop())
            val stage = if (refracted) GlassSurfaceStage.REFRACTED else GlassSurfaceStage.DRAWN
            if (stage > notificationStage) {
                notificationStage = stage
                onSurface(spec.name, stage)
                onDrawn()
            }
            return stage
        }

        fun refresh() = safely(spec.name) {
            val root = view.get() ?: return@safely
            val level = visibleStrength(root)
            panelStrength = level
            if (spec.notificationControls != null) {
                if (spec.notificationControls == GlassSystemCatalog.NotificationControls.PLATE) root.invalidate()
                return@safely
            }
            if (level <= 0) {
                restore()
                return@safely
            }
            val kept = mutableSetOf<View>()
            var styled = false
            for (anchor in spec.targets) {
                val target = if (anchor.id == 0) root else root.findViewById<View>(anchor.id) ?: continue
                styled = apply(target, level, anchor.cornerDp, kept, anchor.keepFill,
                    anchor.powerActionIcon, anchor.shadeBackdrop) || styled
            }
            if (spec.dialog) {
                val decor = root.rootView
                val window = windows[decor]?.get()
                if (!styled) {
                    val panel = decor.findViewById<View>(parentPanelId)
                        ?: decor.findViewById<View>(samsungParentPanelId)
                    if (panel != null) styled = apply(panel, level, 0, kept)
                    if (!styled && window?.isFloating == true) {
                        styled = apply(decor, level, 0, kept)
                        if (!styled && panel != null) styled = apply(panel, level, 24, kept)
                    }
                }
                if (styled && window != null && decor !in kept) {
                    if (blur.apply(window, level)) onSurface(spec.name, GlassSurfaceStage.NATIVE_BLUR)
                } else blur.restore()
            }
            effects.keys.toList().filterNot(kept::contains).forEach { release(it) }
        }

        private fun apply(
            target: View,
            level: Int,
            corner: Int,
            kept: MutableSet<View>,
            keepFill: Boolean = false,
            powerActionIcon: Boolean = false,
            shadeBackdrop: Boolean = false,
        ): Boolean {
            if (owners[target]?.let { it !== this } == true) return false
            val effect = effects.getOrPut(target) {
                GlassSystemSurfaceEffect(material, powerActionIcon) {
                    if (loggedSurfaces.add(spec.name)) {
                        HookEntry.log("GLASS_SYSTEM_SURFACE_DRAWN ${spec.name}")
                        onSurface(spec.name, GlassSurfaceStage.DRAWN)
                    }
                    if (lockScreenVisible()) onLockDrawn() else onDrawn()
                }
            }
            if (!effect.update(target, level, corner, keepFill, boundedPlate = corner > 0)) return false
            if (shadeBackdrop && canUseShadeBackdrop && target is ViewGroup) {
                refracting[target] = Refraction(spec.name, effect, spec.name in MEDIA_LAYOUTS)
            }
            kept += target
            if (target !== view.get() && owners[target] !== this) target.addOnLayoutChangeListener(this)
            owners[target] = this
            return true
        }

        private fun restore() {
            effects.keys.toList().forEach { release(it) }
            blur.restore()
        }

        private fun release(target: View) {
            effects.remove(target)?.restore(target)
            if (target !== view.get()) target.removeOnLayoutChangeListener(this)
            if (target is ViewGroup) refracting.remove(target)
            if (owners[target] === this) owners.remove(target)
        }
    }

    /** Uses the stock compositor only on windows whose theme already permits background blur. */
    private inner class DialogBlur {
        private var owner = WeakReference<Window>(null)
        private var appliedRadius = 0

        fun apply(window: Window, strength: Int): Boolean {
            val radiusField = blurRadiusField ?: return false
            val old = owner.get()
            if (old !== window) restore()
            val windowManager = window.context.getSystemService(WindowManager::class.java)
            val currentRadius = radiusField.getInt(window)
            if (windowManager?.isCrossWindowBlurEnabled != true || (currentRadius != 0 && currentRadius != appliedRadius)) {
                restore()
                return false
            }
            if (!supportsBlur(window)) return false
            val radius = ((5f + strength * 0.12f) * window.context.resources.displayMetrics.density).roundToInt()
            if (radius != currentRadius) window.setBackgroundBlurRadius(radius)
            owner = WeakReference(window)
            appliedRadius = radius
            return radiusField.getInt(window) == radius
        }

        private fun supportsBlur(window: Window): Boolean {
            val value = TypedValue()
            return window.isFloating &&
                window.context.theme.resolveAttribute(android.R.attr.windowIsTranslucent, value, true) &&
                value.type == TypedValue.TYPE_INT_BOOLEAN && value.data != 0
        }

        fun restore() {
            val window = owner.get()
            owner.clear()
            if (window != null && appliedRadius > 0 && blurRadiusField?.getInt(window) == appliedRadius) {
                window.setBackgroundBlurRadius(0)
            }
            appliedRadius = 0
        }
    }

    private companion object {
        const val REDUCE_TRANSPARENCY = "accessibility_reduce_transparency"
        const val SHADE_ROOT = "com.android.systemui.shade.NotificationShadeWindowView"
        const val SHADE_FOOTER = "com.android.systemui.statusbar.notification.footer.ui.view.FooterView"
        const val LOCK_MEDIA_CONTAINER = "com.android.systemui.statusbar.notification.stack.MediaContainerView"
        val MEDIA_LAYOUTS = setOf("oneui_90_sec_media_view", "sec_media_view")
        val EXISTING_LOCKSCREEN = mapOf(
            "keyguard_sec_affordance_view_left" to "left_shortcut_area",
            "keyguard_sec_affordance_view_right" to "right_shortcut_area",
            "ongoing_nowbar_view" to "ongoing_activity_nowbar_view",
        )
        val EXISTING_PANEL_PLATES = mapOf(
            "status_bar_notification_dnd_status" to "notification_dnd_status_text_icon_container",
            "qs_panel_multi_sim_preffered_slot" to "qs_multi_sim_preffered_slot",
            "sec_status_bar_notification_shelf" to "notification_shelf_text_area",
        )
        val EXISTING_CHIPS = listOf("ongoing_activity_chip_content", "sec_ongoing_activity_chip_item",
            "samsung_ongoing_call_chip", "samsung_ongoing_cast_chip")
    }
}
