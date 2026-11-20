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
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.PopupWindow
import android.widget.Toolbar
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Draws into existing system app chrome and keeps the application's content above it. */
internal class SystemAppGlassHost(
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
    private val namedPlates = SystemAppTargets.plates(app.packageName).mapNotNull { (name, corner) ->
        app.resources.getIdentifier(name, "id", app.packageName).takeIf { it != View.NO_ID && it != 0 }
            ?.let { it to corner }
    }.toMap()
    private val panelIds = listOf("android", app.packageName).map {
        app.resources.getIdentifier("parentPanel", "id", it)
    }.filter { it != 0 }.distinct()
    private val material by lazy { GlassMaterial(app.resources.displayMetrics.density) }
    private var config = SystemAppGlassConfig.read(prefs)
    private var reduced = reduceTransparency()
    private var interactive = false
    private var resumed = WeakReference<Activity>(null)
    private var hasDrawn = false
    private var failureLogged = false
    private var compose: SystemAppComposeChrome? = null
    private val composeCorners = FloatArray(8)
    private var guardStarted = false
    private var blocked = false
    private val readListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        main.removeCallbacks(readSettings)
        main.post(readSettings)
    }
    private val readSettings = Runnable {
        safely {
            config = SystemAppGlassConfig.read(prefs)
            if (config.strength > 0) material.tint = config.tint
            updateWindows()
            views.values.toList().forEach { it.invalidate() }
        }
    }

    fun start() {
        installWindowHooks()
        compose = runCatching {
            SystemAppComposeChrome(app.classLoader, ::composeStrength, ::drawCompose)
        }.getOrNull()
        app.registerActivityLifecycleCallbacks(this)
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
                } else updateWindows()
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }, Context.RECEIVER_NOT_EXPORTED)
        HookEntry.log("GLASS_SYSTEM_APP_READY ${app.packageName} process=$process build=${BuildConfig.VERSION_CODE}")
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
                        val panel = if (window.isFloating) {
                            panelIds.firstNotNullOfOrNull { decor.findViewById<View>(it) }
                                ?: decor.takeIf(::hasVisibleBackground)
                        } else null
                        observe(decor, owner, window, panel)
                    }
                }
                result
            }
        }.onFailure { HookEntry.log("GLASS_SYSTEM_APP_DIALOG_UNAVAILABLE ${app.packageName} ${it.javaClass.simpleName}") }

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
                            val panel = generateSequence(content) { it.parent as? View }
                                .firstOrNull(::hasVisibleBackground) ?: content
                            observe(root, activityOf(content.context), null, panel)
                        }
                    }
                }
                result
            }
        }.onFailure { HookEntry.log("GLASS_SYSTEM_APP_POPUP_UNAVAILABLE ${app.packageName} $name ${it.javaClass.simpleName}") }
    }

    override fun onActivityPostResumed(activity: Activity) = safely {
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
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

    private fun observe(root: View, owner: Activity?, window: Window?, panel: View?) {
        if (owner?.javaClass?.name?.let(::sensitive) == true) return
        if (views.containsKey(root)) return
        val binding = WindowBinding(root, owner, window, panel)
        views[root] = binding
        root.addOnAttachStateChangeListener(binding)
        if (root.isAttachedToWindow) binding.onViewAttachedToWindow(root)
    }

    private fun updateInteractive() {
        interactive = power?.isInteractive == true && keyguard?.isKeyguardLocked == false
    }

    private fun updateWindows() = safely {
        updateInteractive()
        views.values.toList().forEach { it.refresh() }
        compose?.refresh()
    }

    private fun composeStrength(view: View): Int {
        if (config.strength <= 0 || !interactive || reduced || blocked || !belongsToApp(view) ||
            !view.isShown || activityOf(view.context)?.javaClass?.name?.let(::sensitive) == true) return 0
        val params = view.rootView.layoutParams as? WindowManager.LayoutParams ?: return 0
        return if (permitted(params) && ensureGuard()) config.strength else 0
    }

    private fun drawCompose(view: View, canvas: Canvas, width: Float, height: Float, corner: Int, level: Int) {
        val radius = (corner * view.resources.displayMetrics.density).coerceAtMost(minOf(width, height) * .5f)
        composeCorners.fill(radius)
        material.tint = config.tint
        material.drawSurface(canvas, 0f, 0f, width, height, composeCorners, level,
            view.resources.configuration.isNightModeActive, fill = false, rim = true,
            profile = GlassMaterial.Profile.SYSTEM_SURFACE)
        didDraw()
    }

    private fun ensureGuard(): Boolean {
        if (!guardStarted) {
            guardStarted = true
            val guard = runCatching { CrashGuard.enterSystemApp(app, process) }.getOrElse {
                blocked = true
                HookEntry.log("GLASS_SYSTEM_APP_GUARD_UNAVAILABLE ${app.packageName} ${it.javaClass.simpleName}")
                return false
            }
            blocked = guard.blocked()
            if (blocked) HookEntry.log("GLASS_SYSTEM_APP_CRASH_GUARD ${app.packageName} process=$process")
            else main.postDelayed({ guard.healthy() }, 90_000L)
        }
        return !blocked
    }

    private fun permitted(params: WindowManager.LayoutParams): Boolean =
        params.flags and WindowManager.LayoutParams.FLAG_SECURE == 0 &&
            (params.type in WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW..WindowManager.LayoutParams.LAST_APPLICATION_WINDOW ||
                params.type in WindowManager.LayoutParams.FIRST_SUB_WINDOW..WindowManager.LayoutParams.LAST_SUB_WINDOW)

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

    private fun corner(view: View): Int? {
        namedPlates[view.id]?.let { return it }
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
                HookEntry.log("GLASS_SYSTEM_APP_UI_UNAVAILABLE ${app.packageName} ${error.javaClass.simpleName}")
            }
        }
    }

    private fun reduceTransparency() =
        Settings.System.getInt(app.contentResolver, REDUCE_TRANSPARENCY, 0) != 0

    private inner class WindowBinding(root: View, owner: Activity?, window: Window?, panel: View?) :
        View.OnAttachStateChangeListener, ViewTreeObserver.OnGlobalLayoutListener {
        private val root = WeakReference(root)
        private val owner = WeakReference(owner)
        private val window = WeakReference(window)
        private val panel = WeakReference(panel)
        private val plates = WeakHashMap<View, Plate>()
        private var observer: ViewTreeObserver? = null
        private var budget = 0

        override fun onViewAttachedToWindow(view: View) {
            observer = view.viewTreeObserver.also { it.addOnGlobalLayoutListener(this) }
            updateInteractive()
            onGlobalLayout()
        }

        override fun onViewDetachedFromWindow(view: View) = stop()

        override fun onGlobalLayout() = safely { refresh() }

        private fun enabled(root: View): Boolean {
            if (!interactive || reduced || config.strength <= 0 || !root.isAttachedToWindow || !root.isShown ||
                owner.get()?.isDestroyed == true || resumed.get()?.javaClass?.name?.let(::sensitive) == true
            ) return false
            val params = window.get()?.attributes ?: root.layoutParams as? WindowManager.LayoutParams ?: return false
            return permitted(params)
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
            val dedicated = panel.get()
            budget = MAX_VIEWS
            if (dedicated != null) {
                bind(dedicated, 24)
            } else {
                scan(root, 0)
            }
        }

        private fun scan(view: View, depth: Int) {
            if (budget-- <= 0 || depth > MAX_DEPTH || !view.isShown || view is SurfaceView || view is TextureView) return
            val radius = corner(view)
            if (radius != null && view.width > 0 && view.height > 0) {
                bind(view, radius)
                return
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    if (budget <= 0) break
                    scan(view.getChildAt(index), depth + 1)
                }
            }
        }

        private fun bind(view: View, corner: Int) {
            val old = plates[view]
            if (old?.overlay == true || (old != null && old === view.background)) return
            if (!ensureGuard()) return
            material.tint = config.tint
            val hostedCard = generateSequence(view.javaClass as Class<*>?) { it.superclass }
                .any { it.name == "androidx.cardview.widget.CardView" }
            val layer = Plate(view, view.background, corner, hostedCard)
            plates[view] = layer
            if (hostedCard) {
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

        fun invalidate() = plates.values.toList().forEach(Drawable::invalidateSelf)

        fun restore() = plates.entries.toList().forEach { (view, plate) -> unbind(view, plate) }

        fun stop() {
            observer?.takeIf(ViewTreeObserver::isAlive)?.removeOnGlobalLayoutListener(this)
            observer = null
            root.get()?.let {
                it.removeOnAttachStateChangeListener(this)
                if (views[it] === this) views.remove(it)
            }
            restore()
        }

        private inner class Plate(
            view: View, val stock: Drawable?, private val corner: Int, val overlay: Boolean,
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
                        fill = !overlay, rim = true, profile = GlassMaterial.Profile.SYSTEM_SURFACE)
                    didDraw()
                }
            }
        }
    }

    private fun didDraw() {
        if (!hasDrawn) {
            hasDrawn = true
            HookEntry.log("GLASS_SYSTEM_APP_DRAWN ${app.packageName} build=${BuildConfig.VERSION_CODE}")
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
        const val MAX_DEPTH = 12
        const val MAX_VIEWS = 240
        val COMMON_WIDGETS = mapOf(
            "androidx.appcompat.widget.Toolbar" to 0,
            "androidx.appcompat.widget.ActionBarContainer" to 0,
            "com.google.android.material.bottomnavigation.BottomNavigationView" to 0,
            "com.google.android.material.navigationrail.NavigationRailView" to 0,
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
