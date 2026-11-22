package dev.ryuya.galaxyglass

import android.view.SurfaceControl
import android.view.View
import android.view.ViewTreeObserver
import dev.ryuya.galaxyglass.compat.DexSymbols
import java.lang.ref.WeakReference

/** Excludes the empty PiP controls buffer from composition until the native menu draws again. */
internal class HiddenPipMenuSurface(loader: ClassLoader) :
    ViewTreeObserver.OnGlobalLayoutListener, ViewTreeObserver.OnPreDrawListener,
    View.OnAttachStateChangeListener {
    private val controller = DexSymbols.findClass("com.android.wm.shell.pip2.phone.PhonePipMenuController", loader)
    private val menuField = controller.getDeclaredField("mPipMenuView").apply { isAccessible = true }
    private val menuType = menuField.type.asSubclass(View::class.java)
    private val menuState = menuType.getDeclaredField("mMenuState").apply { isAccessible = true }
    private val pendingState = menuType.getDeclaredField("mPendingMenuState").apply { isAccessible = true }
    private val settingsButton = menuType.getDeclaredField("mSettingsButton").apply { isAccessible = true }
    private val menuController = menuType.getDeclaredField("mController").apply { isAccessible = true }
    private val systemWindows = controller.getDeclaredField("mSystemWindows").apply { isAccessible = true }
    private val menuSurface = systemWindows.type.getDeclaredMethod("getViewSurface", View::class.java)
        .apply { isAccessible = true }
    private val viewRoot = View::class.java.getMethod("getViewRootImpl")
    private var menu = WeakReference<View>(null)
    private var observer = WeakReference<ViewTreeObserver>(null)
    private var suppressed: SurfaceControl? = null
    private var retired = false
    private var reported = false

    init {
        // SurfaceControlViewHost registers this as a windowless root. Find an existing
        // menu too, so its video task and window can stay open during a hot update.
        val globalType = DexSymbols.findClass("android.view.WindowManagerGlobal")
        val global = globalType.getMethod("getInstance").invoke(null)
        val rootsField = globalType.getDeclaredField("mWindowlessRoots").apply { isAccessible = true }
        val lock = globalType.getDeclaredField("mLock").apply { isAccessible = true }.get(global)
        val rootView = viewRoot.returnType.getMethod("getView")
        val roots = synchronized(checkNotNull(lock)) { (rootsField.get(global) as List<*>).toList() }
        val existing = roots.asSequence().mapNotNull { rootView.invoke(it) as? View }
            .firstOrNull { it.javaClass == menuType }

        ModernHook.aroundMethod(controller.getDeclaredMethod("attach", SurfaceControl::class.java)) { chain ->
            val result = chain.proceed()
            if (!retired) safely { watch(menuField.get(chain.thisObject) as? View) }
            result
        }
        safely { watch(existing) }
        HookEntry.log("GLASS_PIP_EMPTY_MENU_READY attached=${existing?.isAttachedToWindow == true}")
    }

    private fun watch(view: View?) {
        if (menu.get() === view) return
        unwatch()
        if (view?.javaClass != menuType) return
        menu = WeakReference(view)
        view.addOnAttachStateChangeListener(this)
        if (view.isAttachedToWindow) onViewAttachedToWindow(view)
    }

    override fun onViewAttachedToWindow(view: View) = safely {
        if (observer.get() !== view.viewTreeObserver) {
            removeObserver()
            val tree = view.viewTreeObserver
            tree.addOnGlobalLayoutListener(this)
            tree.addOnPreDrawListener(this)
            observer = WeakReference(tree)
        }
        update()
    }

    override fun onViewDetachedFromWindow(view: View) = safely {
        removeObserver()
        restore()
    }

    override fun onGlobalLayout() = safely(::update)

    override fun onPreDraw(): Boolean {
        safely(::update)
        return true
    }

    private fun update() {
        val view = menu.get() ?: return
        if (view.display?.displayId != 0) return
        val idle = menuState.getInt(view) == 0 && pendingState.getInt(view) == 0
        // Samsung also starts with a VISIBLE root before a menu has ever opened.
        // Once a show is pending or the buttons start fading in, keep stock composition.
        if (!idle || (view.visibility == View.VISIBLE && (settingsButton.get(view) as View).alpha > 0f)) {
            restore()
            return
        }
        if (suppressed?.isValid == true) return
        val owner = menuController.get(view) ?: return
        val surface = menuSurface.invoke(systemWindows.get(owner), view) as? SurfaceControl ?: return
        if (!surface.isValid) return
        restore()
        setAlpha(surface, 0f)
        suppressed = surface
        if (!reported) {
            reported = true
            HookEntry.log("GLASS_PIP_EMPTY_MENU_EXCLUDED")
        }
    }

    private fun restore() {
        val surface = suppressed ?: return
        if (surface.isValid) setAlpha(surface, 1f)
        suppressed = null
    }

    private fun setAlpha(surface: SurfaceControl, alpha: Float) {
        SurfaceControl.Transaction().use { transaction ->
            transaction.setAlpha(surface, alpha).apply()
        }
    }

    private fun removeObserver() {
        observer.get()?.takeIf { it.isAlive }?.let {
            it.removeOnGlobalLayoutListener(this)
            it.removeOnPreDrawListener(this)
        }
        observer.clear()
    }

    private fun unwatch() {
        removeObserver()
        menu.get()?.removeOnAttachStateChangeListener(this)
        menu.clear()
        restore()
    }

    fun close() {
        retired = true
        unwatch()
    }

    private inline fun safely(work: () -> Unit) {
        if (retired) return
        try {
            work()
        } catch (error: Exception) {
            HookEntry.log("GLASS_PIP_EMPTY_MENU_UNAVAILABLE ${error.javaClass.simpleName}")
            runCatching(::close)
        }
    }
}
