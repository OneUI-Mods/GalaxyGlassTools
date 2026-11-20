package dev.ryuya.galaxyglass

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Resources
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Insets
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.PopupWindow
import android.widget.Toolbar
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Adds glass to app controls and the visible system-bar areas. */
internal class ApplicationGlassHost(
    private val app: Application,
    private val prefs: SharedPreferences,
    private val process: String,
) : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val keyguard = app.getSystemService(KeyguardManager::class.java)
    private val power = app.getSystemService(PowerManager::class.java)
    private val views = WeakHashMap<View, WindowBinding>()
    private val activityWindows = WeakHashMap<Activity, WeakReference<View>>()
    private val rolesByType = mutableMapOf<Class<*>, Int?>()
    private val plateNames = ApplicationGlassTargets.plates(app.packageName) + ApplicationGlassTargets.sheetPlates
    private val inputMethodPlateNames = ApplicationGlassTargets.inputMethodPlates(app.packageName)
    private val namedPlates = resolvePlates(plateNames)
    private val inputPlateIds: Map<Int, Int> = resolvePlates(inputMethodPlateNames)
    private val splitPlates = WeakHashMap<Resources, MutableMap<Int, Int?>>()
    private val sheetPanelId = app.resources.getIdentifier("design_bottom_sheet", "id", app.packageName)
    private val panelIds = listOf("android", app.packageName).map {
        app.resources.getIdentifier("parentPanel", "id", it)
    }.filter { it != 0 }.distinct()
    private val material by lazy { GlassMaterial(app.resources.displayMetrics.density) }
    private var config = ApplicationGlassConfig.read(prefs)
    private var reduced = reduceTransparency()
    private var interactive = false
    private var resumed = WeakReference<Activity>(null)
    private var hasDrawn = false
    private var failureLogged = false
    private val composeRenderers = mutableListOf<ApplicationComposeChrome>()
    private val composeLoaders = mutableSetOf<ClassLoader>()
    private val composeMethods = mutableSetOf<java.lang.reflect.Method>()
    private val composeCorners = FloatArray(8)
    private var inputMethod = WeakReference<InputMethodService>(null)
    private var guardStarted = false
    private var blocked = false
    private val readListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        main.removeCallbacks(readSettings)
        main.post(readSettings)
    }
    private val readSettings = Runnable {
        safely {
            config = ApplicationGlassConfig.read(prefs)
            if (config.strength > 0) material.tint = config.tint
            updateWindows()
            views.values.toList().forEach { it.invalidate() }
        }
    }

    fun start(firstActivity: Activity?) {
        installWindowHooks()
        if (inputMethodPlateNames.isNotEmpty()) installInputMethodHooks()
        prepareCompose(firstActivity?.classLoader ?: app.classLoader)
        app.registerActivityLifecycleCallbacks(this)
        resumed = WeakReference(firstActivity)
        prefs.registerOnSharedPreferenceChangeListener(readListener)
        app.contentResolver.registerContentObserver(
            Settings.System.getUriFor(REDUCE_TRANSPARENCY), false,
            object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    reduced = reduceTransparency()
                    updateWindows()
                }
            },
        )
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    interactive = false
                    views.values.toList().forEach { it.restore() }
                    composeRenderers.forEach(ApplicationComposeChrome::refresh)
                } else updateWindows()
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }, Context.RECEIVER_NOT_EXPORTED)
        if (firstActivity?.window?.peekDecorView()?.isAttachedToWindow == true) onActivityPostResumed(firstActivity)
        HookEntry.log("GLASS_APP_READY ${app.packageName} process=$process build=${BuildConfig.VERSION_CODE}")
    }

    private fun resolvePlates(names: Map<String, Int>): Map<Int, Int> =
        names.mapNotNull { (name, corner) ->
            app.resources.getIdentifier(name, "id", app.packageName).takeIf { it != 0 }?.let { it to corner }
        }.toMap()

    private fun prepareCompose(loader: ClassLoader) {
        if (!composeLoaders.add(loader)) return
        runCatching {
            ApplicationComposeChrome(loader, composeMethods, ::composeStrength, ::drawCompose)
        }.getOrNull()?.let(composeRenderers::add)
    }

    fun bindInputMethod(service: InputMethodService) {
        inputMethod = WeakReference(service)
        val window = service.window?.window ?: return
        val decor = window.peekDecorView() ?: return
        observeInputMethod(decor, window.attributes)
    }

    private fun installInputMethodHooks() {
        runCatching {
            val owner = Class.forName("android.view.WindowManagerGlobal")
            val add = owner.declaredMethods.single {
                it.name == "addView" && it.parameterTypes.getOrNull(0) == View::class.java &&
                    it.parameterTypes.getOrNull(1) == ViewGroup.LayoutParams::class.java
            }
            ModernHook.aroundMethod(add) { chain ->
                val result = chain.proceed()
                if (main.looper.isCurrentThread) safely {
                    val params = chain.args[1] as? WindowManager.LayoutParams
                    if (params != null && isInputMethodWindow(params)) {
                        observeInputMethod(chain.args[0] as View, params)
                    }
                }
                result
            }
        }.onFailure { HookEntry.log("GLASS_APP_INPUT_WINDOW_UNAVAILABLE ${app.packageName} ${it.javaClass.simpleName}") }
        runCatching {
            val start = InputMethodService::class.java.getMethod(
                "onStartInput", EditorInfo::class.java, Boolean::class.javaPrimitiveType,
            )
            ModernHook.aroundMethod(start) { chain ->
                val result = chain.proceed()
                if (main.looper.isCurrentThread && chain.thisObject === inputMethod.get()) safely {
                    updateWindows()
                    views.values.forEach { it.invalidate() }
                }
                result
            }
        }.onFailure { HookEntry.log("GLASS_APP_INPUT_EDITOR_UNAVAILABLE ${app.packageName} ${it.javaClass.simpleName}") }
    }

    private fun observeInputMethod(root: View, params: WindowManager.LayoutParams) {
        if (!isInputMethodWindow(params) || secure(params) || !belongsToApp(root)) return
        observe(root, null, null, null, ime = true)
    }

    private fun installWindowHooks() {
        runCatching {
            val show = Dialog::class.java.getMethod("show")
            ModernHook.aroundMethod(show) { chain ->
                val result = chain.proceed()
                if (main.looper.isCurrentThread) safely {
                    val dialog = chain.thisObject as Dialog
                    val window = dialog.window
                    val decor = window?.peekDecorView()
                    if (window != null && decor != null && belongsToApp(decor) &&
                        !sensitive(dialog.javaClass.name) && permitted(window.attributes)) {
                        val owner = activityOf(dialog.context)
                        if (owner == null && inputMethod.get() != null) return@safely
                        val sheet = sheetPanelId != 0 && decor.findViewById<View>(sheetPanelId) != null
                        val panel = if (window.isFloating && !sheet) {
                            panelIds.firstNotNullOfOrNull { decor.findViewById<View>(it) }
                                ?: decor.takeIf(::hasVisibleBackground)
                        } else null
                        observe(decor, owner, window, panel)
                    }
                }
                result
            }
        }.onFailure { HookEntry.log("GLASS_APP_DIALOG_UNAVAILABLE ${app.packageName} ${it.javaClass.simpleName}") }

        val integer = Int::class.javaPrimitiveType
        for (name in listOf("showAtLocation", "showAsDropDown")) runCatching {
            val show = PopupWindow::class.java.getMethod(name, View::class.java, integer, integer, integer)
            ModernHook.aroundMethod(show) { chain ->
                val result = chain.proceed()
                if (main.looper.isCurrentThread) safely {
                    val popup = chain.thisObject as PopupWindow
                    val content = popup.contentView
                    if (popup.isShowing && content != null && belongsToApp(content) &&
                        !sensitive(popup.javaClass.name) && !sensitive(content.javaClass.name)) {
                        val root = content.rootView
                        val attributes = root.layoutParams as? WindowManager.LayoutParams
                        if (attributes != null && permitted(attributes)) {
                            val owner = activityOf(content.context)
                            if (owner == null && inputMethod.get() != null) return@safely
                            val panel = generateSequence(content) { it.parent as? View }
                                .firstOrNull(::hasVisibleBackground) ?: content
                            observe(root, owner, null, panel)
                        }
                    }
                }
                result
            }
        }.onFailure { HookEntry.log("GLASS_APP_POPUP_UNAVAILABLE ${app.packageName} $name ${it.javaClass.simpleName}") }
    }

    override fun onActivityPostResumed(activity: Activity) = safely {
        prepareCompose(activity.classLoader)
        resumed = WeakReference(activity)
        updateInteractive()
        val window = activity.window ?: return@safely
        if (!permitted(window.attributes) || sensitive(activity.javaClass.name)) return@safely
        val root = window.peekDecorView() ?: return@safely
        activityWindows[activity] = WeakReference(root)
        observe(root, activity, window, null)
    }

    override fun onActivityStopped(activity: Activity) {
        activityWindows.remove(activity)?.get()?.let { views[it]?.stop() }
        if (resumed.get() === activity) resumed.clear()
    }

    override fun onActivityDestroyed(activity: Activity) = onActivityStopped(activity)
    override fun onActivityPreCreated(activity: Activity, state: Bundle?) = prepareCompose(activity.classLoader)
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

    private fun observe(root: View, owner: Activity?, window: Window?, panel: View?, ime: Boolean = false) {
        if (owner?.javaClass?.name?.let(::sensitive) == true) return
        if (views.containsKey(root)) return
        val binding = WindowBinding(root, owner, window, panel, ime)
        views[root] = binding
        root.addOnAttachStateChangeListener(binding)
        if (root.isAttachedToWindow) binding.onViewAttachedToWindow(root)
    }

    private fun updateInteractive() {
        interactive = power?.isInteractive == true && keyguard?.isKeyguardLocked == false &&
            !keyguard.isDeviceLocked
    }

    private fun updateWindows() = safely {
        updateInteractive()
        views.values.toList().forEach { it.refresh() }
        composeRenderers.forEach(ApplicationComposeChrome::refresh)
    }

    private fun composeStrength(view: View): Int {
        if (config.strength <= 0 || !interactive || reduced || blocked || !belongsToApp(view) ||
            !view.isShown || activityOf(view.context)?.javaClass?.name?.let(::sensitive) == true) return 0
        val params = view.rootView.layoutParams as? WindowManager.LayoutParams ?: return 0
        return if (permitted(params) && ensureGuard()) config.strength else 0
    }

    private fun drawCompose(
        view: View, canvas: Canvas, width: Float, height: Float, surface: ApplicationComposeChrome.Surface, level: Int,
    ) {
        val density = view.resources.displayMetrics.density
        val maxThickness = if (surface.radius == 0) 216f else 144f
        if (!surface.panel && minOf(width, height) > maxThickness * density) return
        val radius = (surface.radius * density).coerceAtMost(minOf(width, height) * .5f)
        composeCorners.fill(radius)
        material.tint = config.tint
        material.drawSurface(canvas, 0f, 0f, width, height, composeCorners, level,
            view.resources.configuration.isNightModeActive, fill = surface.panel, rim = true,
            opacity = if (surface.panel) .45f else 1f,
            profile = GlassMaterial.Profile.SYSTEM_SURFACE)
        didDraw()
    }

    private fun ensureGuard(): Boolean {
        if (!guardStarted) {
            guardStarted = true
            val guard = runCatching { CrashGuard.enterApplicationGlass(app, process) }.getOrElse {
                blocked = true
                HookEntry.log("GLASS_APP_GUARD_UNAVAILABLE ${app.packageName} ${it.javaClass.simpleName}")
                return false
            }
            blocked = guard.blocked()
            if (blocked) HookEntry.log("GLASS_APP_CRASH_GUARD ${app.packageName} process=$process")
            else main.postDelayed({ guard.healthy() }, 90_000L)
        }
        return !blocked
    }

    private fun secure(params: WindowManager.LayoutParams): Boolean =
        params.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun permitted(params: WindowManager.LayoutParams): Boolean =
        !secure(params) &&
            (params.type in WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW..WindowManager.LayoutParams.LAST_APPLICATION_WINDOW ||
                params.type in WindowManager.LayoutParams.FIRST_SUB_WINDOW..WindowManager.LayoutParams.LAST_SUB_WINDOW)

    private fun isInputMethodWindow(params: WindowManager.LayoutParams): Boolean =
        params.type == WindowManager.LayoutParams.TYPE_INPUT_METHOD ||
            params.type == WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG

    private fun permitsInputMethod(params: WindowManager.LayoutParams): Boolean {
        if (secure(params) || !isInputMethodWindow(params)) return false
        val editor = inputMethod.get()?.currentInputEditorInfo ?: return false
        if (editor.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return false
        val variation = editor.inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
        return variation != InputType.TYPE_NULL && variation !in PRIVATE_INPUT_TYPES
    }

    private fun belongsToApp(view: View) = view.context.applicationContext?.packageName == app.packageName

    private fun activityOf(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val base = current.baseContext
            if (base === current) break
            current = base
        }
        return resumed.get()
    }

    private fun sensitive(name: String): Boolean = SENSITIVE.any { name.contains(it, ignoreCase = true) }

    private fun hasVisibleBackground(view: View): Boolean = when (val background = view.background) {
        null -> false
        is ColorDrawable -> background.color ushr 24 > 0
        else -> true
    }

    private fun corner(view: View, ime: Boolean): Int? {
        if (ime) return inputPlateIds[view.id]
        namedPlates[view.id]?.let { return it }
        if (ApplicationGlassTargets.usesOverlay(app.packageName, view.javaClass.name)) return 0
        if (view.id != 0 && view.id != View.NO_ID && view.resources !== app.resources) {
            val splitNames = splitPlates.getOrPut(view.resources) { mutableMapOf() }
            if (!splitNames.containsKey(view.id)) {
                val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
                splitNames[view.id] = plateNames[name]
            }
            splitNames[view.id]?.let { return it }
        }
        if (view is Toolbar) return 0
        return rolesByType.getOrPut(view.javaClass) {
            generateSequence(view.javaClass as Class<*>?) { it.superclass }
                .firstNotNullOfOrNull { COMMON_WIDGETS[it.name] }
        }
    }

    private fun safely(action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            if (!failureLogged) {
                failureLogged = true
                HookEntry.log("GLASS_APP_UI_UNAVAILABLE ${app.packageName} ${error.javaClass.simpleName}")
            }
        }
    }

    private fun reduceTransparency() =
        Settings.System.getInt(app.contentResolver, REDUCE_TRANSPARENCY, 0) != 0

    private inner class WindowBinding(root: View, owner: Activity?, window: Window?, panel: View?, private val ime: Boolean) :
        View.OnAttachStateChangeListener, ViewTreeObserver.OnGlobalLayoutListener {
        private val root = WeakReference(root)
        private val owner = WeakReference(owner)
        private val window = WeakReference(window)
        private val panel = WeakReference(panel)
        private val plates = WeakHashMap<View, Plate>()
        private var observer: ViewTreeObserver? = null
        private val pending = ArrayDeque<View>()
        private var scanPosted = false
        private var scanAgain = false
        private val continueScan = Runnable {
            scanPosted = false
            safely {
                val attached = this.root.get() ?: return@safely
                if (!enabled(attached)) restore() else scanBatch(attached)
            }
        }
        private var barPlate: SystemBarPlate? = null

        override fun onViewAttachedToWindow(view: View) {
            observer = view.viewTreeObserver.also { it.addOnGlobalLayoutListener(this) }
            updateInteractive()
            onGlobalLayout()
        }

        override fun onViewDetachedFromWindow(view: View) = stop()

        override fun onGlobalLayout() = safely { refresh() }

        private fun enabled(root: View): Boolean {
            if (!interactive || reduced || config.strength <= 0 || !root.isAttachedToWindow || !root.isShown ||
                owner.get()?.isDestroyed == true || owner.get()?.isInPictureInPictureMode == true ||
                resumed.get()?.javaClass?.name?.let(::sensitive) == true
            ) return false
            val params = window.get()?.attributes ?: root.layoutParams as? WindowManager.LayoutParams ?: return false
            return if (ime) permitsInputMethod(params) else permitted(params)
        }

        fun refresh() {
            val root = root.get() ?: return
            if (!enabled(root) || blocked) {
                restore()
                return
            }
            plates.entries.toList().forEach { (view, plate) ->
                if (!view.isAttachedToWindow || view.rootView !== root || !view.isShown) unbind(view, plate)
                else if (plate.overlay) plate.setBounds(0, 0, view.width, view.height)
            }
            if (scanPosted) {
                scanAgain = true
                return
            }
            pending.addLast(panel.get() ?: root)
            scanBatch(root)
        }

        private fun updateSystemBars(root: View) {
            val activity = owner.get()
            val insets = root.rootWindowInsets
            val chrome = insets?.getInsets(WindowInsets.Type.systemBars()) ?: Insets.NONE
            val appWindow = activity != null && window.get() === activity.window
            if (!appWindow || chrome == Insets.NONE || !ensureGuard()) {
                removeSystemBars(root)
                return
            }
            val bars = barPlate ?: SystemBarPlate(root).also {
                root.overlay.add(it)
                barPlate = it
            }
            bars.setBounds(0, 0, root.width, root.height)
            bars.invalidateSelf()
        }

        private fun removeSystemBars(root: View) {
            barPlate?.let { root.overlay.remove(it) }
            barPlate = null
        }

        private fun scanBatch(root: View) {
            val dedicated = panel.get()
            var remaining = VIEWS_PER_BATCH
            while (remaining-- > 0 && pending.isNotEmpty()) {
                val view = pending.removeFirst()
                if (!view.isShown || view.rootView !== root || view is SurfaceView || view is TextureView) continue
                val radius = corner(view, ime) ?: if (view === dedicated) 24 else null
                if (radius != null && view.width > 0 && view.height > 0) bind(view, radius)
                if (view is ViewGroup) {
                    for (index in view.childCount - 1 downTo 0) pending.addFirst(view.getChildAt(index))
                }
            }
            updateSystemBars(root)
            if (pending.isEmpty() && scanAgain) {
                scanAgain = false
                pending.addLast(dedicated ?: root)
            }
            if (pending.isNotEmpty()) {
                scanPosted = true
                root.postOnAnimation(continueScan)
            }
        }

        private fun bind(view: View, corner: Int) {
            val old = plates[view]
            if (old?.overlay == true || (old != null && old === view.background)) return
            if (!ensureGuard()) return
            material.tint = config.tint
            val canvasToolbar = ApplicationGlassTargets.usesOverlay(app.packageName, view.javaClass.name)
            val hostedCard = generateSequence(view.javaClass as Class<*>?) { it.superclass }
                .any { it.name == "androidx.cardview.widget.CardView" }
            val layer = Plate(view, view.background, corner, hostedCard || canvasToolbar, canvasToolbar)
            plates[view] = layer
            if (layer.overlay) {
                layer.setBounds(0, 0, view.width, view.height)
                view.overlay.add(layer)
            } else {
                replaceBackground(view, layer)
                layer.stock?.callback = layer
            }
        }

        private fun unbind(view: View, plate: Plate) {
            plates.remove(view)
            if (plate.overlay) view.overlay.remove(plate)
            else if (view.background === plate) replaceBackground(view, plate.stock)
        }

        fun invalidate() {
            plates.values.toList().forEach(Drawable::invalidateSelf)
            barPlate?.invalidateSelf()
        }

        fun restore() {
            if (scanPosted) root.get()?.removeCallbacks(continueScan)
            scanPosted = false
            scanAgain = false
            pending.clear()
            plates.entries.toList().forEach { (view, plate) -> unbind(view, plate) }
            root.get()?.let(::removeSystemBars)
        }

        fun stop() {
            observer?.takeIf(ViewTreeObserver::isAlive)?.removeOnGlobalLayoutListener(this)
            observer = null
            root.get()?.let {
                it.removeOnAttachStateChangeListener(this)
                if (views[it] === this) views.remove(it)
            }
            restore()
        }

        // Edge-to-edge WebView, game and custom-renderer apps still have Android's
        // system-bar areas when they run outside immersive mode.
        private inner class SystemBarPlate(view: View) : Drawable() {
            private val view = WeakReference(view)
            private val square = FloatArray(8)
            private val rootLocation = IntArray(2)
            private val plateLocation = IntArray(2)

            override fun draw(canvas: Canvas) {
                val root = view.get() ?: return
                if (!canvas.isHardwareAccelerated || bounds.isEmpty || !enabled(root)) return
                val insets = root.rootWindowInsets ?: return
                val status = insets.getInsets(WindowInsets.Type.statusBars())
                val navigation = insets.getInsets(WindowInsets.Type.navigationBars())
                val caption = insets.getInsets(WindowInsets.Type.captionBar())
                val appearance = root.windowInsetsController?.systemBarsAppearance ?: 0
                val darkStatus = appearance and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS == 0
                val darkNavigation = appearance and WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS == 0
                val width = bounds.width().toFloat()
                val height = bounds.height().toFloat()
                val top = maxOf(status.top, caption.top, navigation.top).toFloat()
                val darkCaption = root.resources.configuration.isNightModeActive
                safely {
                    val saved = canvas.save()
                    try {
                        root.getLocationInWindow(rootLocation)
                        for (plate in plates.keys) {
                            if (!plate.isShown) continue
                            plate.getLocationInWindow(plateLocation)
                            val left = plateLocation[0] - rootLocation[0]
                            val y = plateLocation[1] - rootLocation[1]
                            canvas.clipOutRect(left, y, left + plate.width, y + plate.height)
                        }
                        material.tint = config.tint
                        paint(canvas, 0f, 0f, width, status.top.toFloat(), darkStatus)
                        paint(canvas, 0f, status.top.toFloat(), width, caption.top.toFloat(), darkCaption)
                        paint(canvas, 0f, maxOf(status.top, caption.top).toFloat(), width, navigation.top.toFloat(), darkNavigation)
                        paint(canvas, 0f, height - navigation.bottom, width, height, darkNavigation)
                        paint(canvas, 0f, top, navigation.left.toFloat(), height - navigation.bottom, darkNavigation)
                        paint(canvas, width - navigation.right, top, width, height - navigation.bottom, darkNavigation)
                    } finally {
                        canvas.restoreToCount(saved)
                    }
                }
            }

            private fun paint(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, dark: Boolean) {
                if (left >= right || top >= bottom) return
                material.drawSurface(canvas, left, top, right, bottom, square, config.strength, dark,
                    fill = true, rim = true, opacity = .38f, profile = GlassMaterial.Profile.SYSTEM_SURFACE)
                didDraw()
            }

            override fun setAlpha(alpha: Int) = Unit
            override fun setColorFilter(filter: ColorFilter?) = Unit
            @Deprecated("Required by Drawable")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }

        private inner class Plate(
            view: View, val stock: Drawable?, private val corner: Int, val overlay: Boolean, private val canvasToolbar: Boolean,
        ) : DrawableWrapper(if (overlay) null else stock) {
            private val view = WeakReference(view)
            private val shape = Rect()
            private val stockOutline = Outline()
            private val radii = FloatArray(8)

            override fun draw(canvas: Canvas) {
                super.draw(canvas)
                val target = view.get() ?: return
                val windowRoot = root.get() ?: return
                if (!canvas.isHardwareAccelerated || bounds.isEmpty || !enabled(windowRoot)) return
                safely {
                    stockOutline.setEmpty()
                    val boundary = if (overlay) target.background else stock
                    boundary?.getOutline(stockOutline)
                    val outlined = stockOutline.getRect(shape) && shape.intersect(bounds)
                    if (!outlined) shape.set(bounds)
                    val radius = stockOutline.radius.takeIf { outlined && it > 0f }
                        ?: (corner * target.resources.displayMetrics.density)
                    radii.fill(radius.coerceAtMost(minOf(shape.width(), shape.height()) * .5f))
                    material.drawSurface(canvas, shape.left.toFloat(), shape.top.toFloat(), shape.right.toFloat(),
                        shape.bottom.toFloat(), radii, config.strength, target.resources.configuration.isNightModeActive,
                        fill = !overlay || canvasToolbar, rim = true, opacity = if (canvasToolbar) .45f else 1f,
                        profile = GlassMaterial.Profile.SYSTEM_SURFACE)
                    didDraw()
                }
            }
        }
    }

    private fun didDraw() {
        if (!hasDrawn) {
            hasDrawn = true
            HookEntry.log("GLASS_APP_DRAWN ${app.packageName} build=${BuildConfig.VERSION_CODE}")
        }
    }

    private fun replaceBackground(view: View, background: Drawable?) {
        val start = view.paddingStart
        val top = view.paddingTop
        val end = view.paddingEnd
        val bottom = view.paddingBottom
        view.background = background
        if (view.paddingStart != start || view.paddingTop != top || view.paddingEnd != end || view.paddingBottom != bottom) {
            view.setPaddingRelative(start, top, end, bottom)
        }
    }

    private companion object {
        const val REDUCE_TRANSPARENCY = "accessibility_reduce_transparency"
        const val VIEWS_PER_BATCH = 240
        val PRIVATE_INPUT_TYPES = setOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        val COMMON_WIDGETS = mapOf(
            "androidx.appcompat.widget.Toolbar" to 0,
            "androidx.appcompat.widget.ActionBarContainer" to 0,
            "com.android.internal.widget.ActionBarContainer" to 0,
            "com.google.android.material.bottomappbar.BottomAppBar" to 0,
            "com.google.android.material.tabs.TabLayout" to 0,
            "com.google.android.material.bottomnavigation.BottomNavigationView" to 0,
            "com.google.android.material.navigationrail.NavigationRailView" to 0,
            "com.google.android.material.navigation.NavigationView" to 0,
            "com.google.android.material.search.SearchBar" to 24,
            "androidx.appcompat.widget.SearchView" to 20,
            "androidx.cardview.widget.CardView" to 24,
        )
        val SENSITIVE = listOf("bouncer", "credential", "confirmdevicepassword", "confirmpin", "confirmpattern",
            "biometricprompt", "fingerprintenroll", "faceenroll", "grantpermission", "permissiongrant",
            "packageinstaller", "deviceadminadd", "passwordactivity", "keyguard", "keychain", "securefolder",
            "accessibilityservicewarning", "mediamanagementpermission", "vpnpermission", "setupwizard")
    }
}
