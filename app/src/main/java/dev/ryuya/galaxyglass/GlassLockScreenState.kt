package dev.ryuya.galaxyglass

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.PowerManager
import android.provider.Settings
import dev.ryuya.galaxyglass.compat.DexSymbols

/** Tracks the ordinary, interactive keyguard surface from SystemUI's own window state. */
internal class GlassLockScreenState(
    context: Context,
    loader: ClassLoader,
    private val main: Handler,
    changed: () -> Unit,
) {
    private var showing = false
    private var observedWindowState = false
    private var interactive = context.getSystemService(PowerManager::class.java)?.isInteractive == true
    private val resolver = context.contentResolver
    private var reduceTransparency = Settings.System.getInt(resolver, REDUCE_TRANSPARENCY, 0) != 0
    private val dispatchChanged = Runnable(changed)
    val visible: Boolean get() = showing && interactive && !reduceTransparency

    init {
        val owner = DexSymbols.findClass("com.android.systemui.shade.NotificationShadeWindowControllerImpl", false, loader)
        val state = DexSymbols.findClass("com.android.systemui.shade.NotificationShadeWindowState", false, loader)
        val showingField = state.getField("keyguardShowing")
        val barState = state.getField("statusBarState")
        val hiddenWhen = listOf(
            "keyguardOccluded", "bouncerShowing", "keyguardNeedsInput", "dozing", "dreaming",
            "keyguardGoingAway", "keyguardFadingAway", "qsExpanded", "communalVisible", "isSwitchingUsers", "securedWindow",
        ).map(state::getField)
        fun isShowing(current: Any): Boolean =
            showingField.getBoolean(current) && barState.getInt(current) == KEYGUARD &&
                hiddenWhen.none { it.getBoolean(current) }
        val applyWindow = owner.getDeclaredMethod("apply", state)
        ModernHook.aroundMethod(applyWindow) { chain ->
            val result = chain.proceed()
            val next = isShowing(requireNotNull(chain.args[0]))
            if (main.looper.isCurrentThread) observe(next) else main.post { observe(next) }
            result
        }
        // SystemUI can already have an open keyguard when the glass adapters start.
        main.post {
            if (!observedWindowState) runCatching {
                val dependency = DexSymbols.findClass("com.android.systemui.Dependency", false, loader)
                val instance = dependency.getField("sDependency").get(null) ?: return@runCatching
                val shadeType = DexSymbols.findClass("com.android.systemui.shade.ShadeController", false, loader)
                val shade = dependency.getMethod("getDependencyInner", Any::class.java)
                    .invoke(instance, shadeType) ?: return@runCatching
                val controller = shade.javaClass.getField("mNotificationShadeWindowController").get(shade)
                if (owner.isInstance(controller) && !observedWindowState) {
                    val current = owner.getField("mCurrentState").get(controller) ?: return@runCatching
                    observe(isShowing(current))
                }
            }.onFailure { HookEntry.log("GLASS_LOCK_SCREEN_INITIAL_STATE_UNAVAILABLE ${it.javaClass.simpleName}") }
        }
        resolver.registerContentObserver(Settings.System.getUriFor(REDUCE_TRANSPARENCY), false,
            object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    val wasVisible = visible
                    reduceTransparency = Settings.System.getInt(resolver, REDUCE_TRANSPARENCY, 0) != 0
                    if (wasVisible != visible) notifyChanged()
                }
            })
    }

    fun setInteractive(value: Boolean) {
        val wasVisible = visible
        interactive = value
        if (wasVisible != visible) notifyChanged()
    }

    private fun setShowing(value: Boolean) {
        val wasVisible = visible
        showing = value
        if (wasVisible != visible) notifyChanged()
    }

    private fun observe(value: Boolean) {
        observedWindowState = true
        setShowing(value)
    }

    private fun notifyChanged() {
        main.removeCallbacks(dispatchChanged)
        main.post(dispatchChanged)
    }

    private companion object {
        const val KEYGUARD = 1
        const val REDUCE_TRANSPARENCY = "accessibility_reduce_transparency"
    }
}
