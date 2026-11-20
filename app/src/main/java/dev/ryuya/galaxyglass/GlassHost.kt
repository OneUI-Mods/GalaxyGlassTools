package dev.ryuya.galaxyglass

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import androidx.annotation.RequiresApi
import dev.ryuya.galaxyglass.compat.DexSymbols
import java.lang.ref.WeakReference

/** Starts the renderer for its assigned native process. */
internal class GlassHost(
    private val app: Application,
    private val loader: ClassLoader,
    activity: Activity?,
    private val briefOnly: Boolean = false,
    private val systemSurfacesOnly: Boolean = false,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = BackgroundExecutors.serial("OneUiGlassConfig")
    private val systemUi = app.packageName == "com.android.systemui"
    private val material = GlassMaterial(app.resources.displayMetrics.density)
    private val keyguard = app.getSystemService(KeyguardManager::class.java)
    private val power = app.getSystemService(PowerManager::class.java)
    private var appearance = GlassAppearance()
    private var nightMode = app.resources.configuration.isNightModeActive
    private var homeActivity = WeakReference(activity)
    private var unlocked = false
    private var quick: GlassQuickTiles? = null
    private var cards: GlassNotificationCards? = null
    private var popups: GlassNotificationPopups? = null
    private var notificationShade: GlassNotificationShade? = null
    private var panelBackdrop: GlassPanelBackdrop? = null
    private var volume: GlassVolumePanel? = null
    private var volumePlugin: VolumePluginDiscovery? = null
    private var systemSurfaces: GlassSystemSurfaces? = null
    private var lockScreenState: GlassLockScreenState? = null
    private var dock: GlassHomeSurfaces? = null
    private var shadeTouchInstalled = false
    private var drawScheduled = false
    private val shadeLight = GlassTouchLight {
        if (!drawScheduled) {
            drawScheduled = true
            Choreographer.getInstance().postFrameCallback {
                drawScheduled = false
                if (Build.VERSION.SDK_INT >= 33) refreshPanelSurfaces()
            }
        }
    }
    private val unavailable = mutableSetOf<String>()
    private val drawn = mutableSetOf<String>()
    private val reported = mutableMapOf<String, String>()
    private val surfaceStages = mutableMapOf<String, GlassSurfaceStage>()

    fun start() {
        if (Build.VERSION.SDK_INT < 33 || !GlassTargets.supportsSystemGlass()) return
        if (briefOnly) popups = createPopups()
        if (systemUi && !briefOnly && !systemSurfacesOnly) {
            volumePlugin = runCatching { VolumePluginDiscovery(loader) }
                .onFailure { HookEntry.log("GLASS_VOLUME_PLUGIN_UNAVAILABLE ${it.javaClass.simpleName}") }.getOrNull()
        }
        if (systemSurfacesOnly && Build.VERSION.SDK_INT >= 33 && Looper.myLooper() == main.looper) {
            // Screenshot and freeform can inflate their first window before settings I/O completes.
            prepareSystemHelper()
        }
        main.post {
            nightMode = app.resources.configuration.isNightModeActive
            app.registerComponentCallbacks(object : ComponentCallbacks {
                override fun onConfigurationChanged(configuration: Configuration) {
                    val night = configuration.isNightModeActive
                    if (nightMode == night) return
                    nightMode = night
                    if (Build.VERSION.SDK_INT >= 33) refreshSurfaces()
                }

                override fun onLowMemory() = Unit
            })
            unlocked = keyguard?.isKeyguardLocked == false
            if (systemUi) {
                if (!briefOnly && !systemSurfacesOnly) {
                    lockScreenState = runCatching {
                        GlassLockScreenState(app, loader, main, ::refreshLockScreen)
                    }.onFailure {
                        HookEntry.log("GLASS_LOCK_SCREEN_STATE_UNAVAILABLE ${it.javaClass.simpleName}")
                    }.getOrNull()
                }
                trackKeyguard()
            }
            else if (Build.VERSION.SDK_INT >= 33) trackHomeActivity()
            val uri = requireNotNull(ConfigProvider.URI)
            app.contentResolver.registerContentObserver(uri, false, object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) = read()
            })
            read()
        }
    }

    private fun trackKeyguard() {
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                unlocked = when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> false
                    Intent.ACTION_USER_PRESENT -> true
                    else -> keyguard?.isKeyguardLocked == false
                }
                lockScreenState?.setInteractive(intent.action != Intent.ACTION_SCREEN_OFF)
                if (!unlocked) {
                    shadeLight.clear()
                    if (Build.VERSION.SDK_INT >= 33) panelBackdrop?.clear()
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    cards?.refresh()
                    popups?.refresh()
                    volume?.refresh()
                    systemSurfaces?.refresh()
                }
                notificationShade?.refresh()
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, Context.RECEIVER_NOT_EXPORTED)
    }

    private fun refreshLockScreen() {
        if (Build.VERSION.SDK_INT < 33 || !appearance.lockScreen) return
        cards?.refresh()
        systemSurfaces?.refresh()
    }

    private fun lockScreenVisible() = lockScreenState?.visible == true

    @RequiresApi(33)
    private fun trackHomeActivity() {
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> dock?.clearDockBackdrops()
                    Intent.ACTION_USER_PRESENT -> dock?.refresh()
                    Intent.ACTION_WALLPAPER_CHANGED -> dock?.wallpaperChanged()
                }
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_WALLPAPER_CHANGED)
        }, Context.RECEIVER_NOT_EXPORTED)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                if (activity.javaClass.name !in HOME_ACTIVITY_NAMES) return
                homeActivity = WeakReference(activity)
                dock?.find(activity.window.decorView)
                dock?.refresh()
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) {
                if (homeActivity.get() === activity) dock?.clearDockBackdrops()
            }
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (homeActivity.get() === activity) homeActivity.clear()
            }
        })
    }

    private fun read() {
        worker.execute {
            val next = runCatching {
                val config = app.contentResolver.call(ConfigProvider.URI, "config", null, null)
                    ?: return@runCatching GlassAppearance()
                GlassAppearance(
                    quick = config.getBoolean(GlassAppearance.QUICK),
                    notifications = config.getBoolean(GlassAppearance.NOTIFICATIONS),
                    home = config.getBoolean(GlassAppearance.HOME),
                    strength = config.getInt(GlassAppearance.STRENGTH),
                    tint = config.getInt(GlassAppearance.TINT, GlassAppearance.DEFAULT_TINT),
                    volume = config.getBoolean(GlassAppearance.VOLUME),
                    volumeTheme = config.getInt("volume"),
                    systemSurfaces = config.getBoolean(GlassAppearance.SYSTEM_SURFACES),
                    lockScreen = config.getBoolean(GlassAppearance.LOCK_SCREEN),
                )
            }.getOrNull() ?: GlassAppearance()
            main.post {
                if (Build.VERSION.SDK_INT >= 33) apply(next)
            }
        }
    }

    @RequiresApi(33)
    private fun apply(next: GlassAppearance) {
        val changed = appearance != next
        appearance = next
        material.tint = next.tint
        if (systemSurfacesOnly) {
            if (next.systemSurfaces) prepareSystemHelper()
            if (changed) systemSurfaces?.refresh()
            return
        }
        if (systemUi && !briefOnly) {
            val panelsEnabled = next.strength > 0 && (next.quick || next.notifications || next.systemSurfaces)
            if (panelsEnabled) {
                if (!shadeTouchInstalled) installShadeTouch()
                installPanelBackdrop()
            } else {
                shadeLight.clear()
                panelBackdrop?.clear()
            }
            update(GlassAppearance.QUICK, next.quick, quick != null) {
                installQuickSurfaces()
            }
            update(GlassAppearance.NOTIFICATIONS, next.notifications, cards != null) {
                installNotificationCards()
            }
            update(GlassAppearance.LOCK_SCREEN, next.lockScreen,
                lockScreenState != null && systemSurfaces != null && cards != null) {
                checkNotNull(lockScreenState)
                if (systemSurfaces == null) installSystemSurfaces()
                if (cards == null) installNotificationCards()
            }
            update(GlassAppearance.VOLUME, next.volume, volume != null) {
                volume = GlassVolumePanel(app, loader, main, worker, material, volumePlugin,
                    { if (unlocked && appearance.volume) appearance.strength else 0 },
                    { view ->
                        when (appearance.volumeTheme) {
                            1 -> false
                            2 -> true
                            else -> view.resources.configuration.isNightModeActive
                        }
                    }, ::refreshPopupUnlock,
                    { markDrawn(GlassAppearance.VOLUME) })
            }
            update(GlassAppearance.SYSTEM_SURFACES, next.systemSurfaces, systemSurfaces != null) {
                installSystemSurfaces()
            }
            if (quick == null) runCatching { installQuickSurfaces() }
                .onFailure { HookEntry.log("GLASS_COMPOSE_PLATES_UNAVAILABLE ${it.javaClass.simpleName}") }
        } else if (!systemUi) {
            update(GlassAppearance.HOME, next.home, dock != null) {
                dock = GlassHomeSurfaces(app, loader, main, worker, { if (appearance.home) appearance.strength else 0 },
                    ::isNight, material, { markDrawn(GlassAppearance.HOME) }, ::reportSurface)
                homeActivity.get()?.takeUnless(Activity::isDestroyed)?.let { dock?.find(it.window.decorView) }
            }
        }
        if (changed) refreshSurfaces()
    }

    @RequiresApi(33)
    private fun installNotificationCards() {
        val popupGlass = popups ?: createPopups().also { popups = it }
        cards = GlassNotificationCards(loader, { appearance }, { unlocked }, ::lockScreenVisible,
            ::isNight, { shadeLight.pointer }, material, { panelBackdrop }, popupGlass,
            { markDrawn(GlassAppearance.NOTIFICATIONS) },
            {
                reportSurface(GlassSystemCatalog.LOCK_NOTIFICATIONS, GlassSurfaceStage.DRAWN)
                markDrawn(GlassAppearance.LOCK_SCREEN)
            },
            {
                reportSurface(GlassSystemCatalog.NOTIFICATION_SHELF, GlassSurfaceStage.DRAWN)
                markDrawn(GlassAppearance.NOTIFICATIONS)
            })
    }

    @RequiresApi(33)
    private fun prepareSystemHelper() {
        if (systemSurfaces != null || GlassAppearance.SYSTEM_SURFACES in unavailable) return
        try {
            installSystemSurfaces()
        } catch (error: Exception) {
            unavailable += GlassAppearance.SYSTEM_SURFACES
            HookEntry.log("GLASS_SYSTEM_HELPER_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }

    @RequiresApi(33)
    private fun installSystemSurfaces() {
        systemSurfaces = GlassSystemSurfaces(
            app, main, { if (appearance.systemSurfaces) appearance.strength else 0 },
            { if (appearance.lockScreen) appearance.strength else 0 }, ::lockScreenVisible,
            { unlocked }, { power?.isInteractive == true }, material,
            { panelBackdrop }, !systemSurfacesOnly,
            { quick?.refresh() },
            { markDrawn(GlassAppearance.SYSTEM_SURFACES) },
            { markDrawn(GlassAppearance.LOCK_SCREEN) },
            ::reportSurface,
        )
    }

    @RequiresApi(33)
    private fun installQuickSurfaces() {
        quick = GlassQuickTiles(loader, app.resources, { if (appearance.quick) appearance.strength else 0 },
            { if (appearance.systemSurfaces && systemSurfaces?.allowsEditorGlass() == true) appearance.strength else 0 },
            { if (appearance.volume) appearance.strength else 0 },
            ::isNight, { unlocked }, { shadeLight.pointer }, material, app.resources.displayMetrics.density,
            { panelBackdrop }, { markDrawn(GlassAppearance.QUICK) },
            { markDrawn(GlassAppearance.SYSTEM_SURFACES) },
            { markDrawn(GlassAppearance.VOLUME) }, ::reportSurface)
    }

    @RequiresApi(33)
    private fun refreshSurfaces() {
        quick?.refresh()
        cards?.refresh()
        popups?.refresh()
        notificationShade?.refresh()
        volume?.refresh()
        systemSurfaces?.refresh()
        dock?.refresh()
    }

    @RequiresApi(33)
    private fun createPopups() = GlassNotificationPopups(
        loader, main, worker, app.resources.displayMetrics.density,
        { if (unlocked) appearance else GlassAppearance() }, ::refreshPopupUnlock, ::isNight, material,
        briefOnly,
    )

    private fun refreshPopupUnlock() {
        // SystemUI reload can preserve the user's unlocked session without broadcasting USER_PRESENT.
        unlocked = power?.isInteractive == true && keyguard?.isKeyguardLocked == false
    }

    @RequiresApi(33)
    private fun installPanelBackdrop() {
        if (panelBackdrop != null) return
        val density = app.resources.displayMetrics.density
        val captureWorker = BackgroundExecutors.serial("OneUiGlassShadeCapture")
        val backdrop = GlassPanelBackdrop(main, captureWorker, density, ::refreshPanelSurfaces, live = true)
        panelBackdrop = backdrop
        var checkedUnlockThisOpening = false
        notificationShade = runCatching {
            GlassNotificationShade(loader, { if (unlocked && appearance.notifications) appearance.strength else 0 },
                { material.tintFactor }, ::isNight, density,
                { view, open ->
                    if (!open) checkedUnlockThisOpening = false
                    else if (!unlocked && !lockScreenVisible() && hasShadeEffects() && !checkedUnlockThisOpening) {
                        checkedUnlockThisOpening = true
                        refreshPopupUnlock()
                    }
                    backdrop.observe(view, open && canCaptureShade())
                },
                { canCaptureShade() && backdrop.needsObservation() },
                { markDrawn(GlassAppearance.NOTIFICATIONS) })
        }.onFailure { HookEntry.log("GLASS_NOTIFICATION_SHADE_UNAVAILABLE ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
    }

    private fun canCaptureShade() = unlocked && !lockScreenVisible() && hasShadeEffects()

    private fun hasShadeEffects() = appearance.strength > 0 &&
        (appearance.quick || appearance.notifications || appearance.systemSurfaces)

    @RequiresApi(33)
    private fun refreshPanelSurfaces() {
        if (appearance.quick || appearance.systemSurfaces) quick?.refresh()
        if (appearance.notifications) cards?.refresh()
        if (appearance.systemSurfaces) systemSurfaces?.backdropChanged()
    }

    private fun installShadeTouch() {
        shadeTouchInstalled = true
        try {
            val shade = DexSymbols.findClass("com.android.systemui.shade.NotificationShadeWindowView", false, loader)
                .asSubclass(View::class.java)
            val dispatch = shade.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
            ModernHook.aroundMethod(dispatch) { chain ->
                if (appearance.strength > 0 && (appearance.quick || appearance.notifications)) {
                    val event = chain.args[0] as MotionEvent
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        val wasUnlocked = unlocked
                        unlocked = keyguard?.isKeyguardLocked == false
                        if (unlocked != wasUnlocked) notificationShade?.refresh()
                    }
                    if (unlocked) shadeLight.touch(chain.thisObject as View, event)
                }
                chain.proceed()
            }
        } catch (error: Exception) {
            HookEntry.log("GLASS_SHADE_TOUCH_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }

    private fun update(feature: String, enabled: Boolean, installed: Boolean, install: () -> Unit) {
        val state = when {
            !enabled || appearance.strength == 0 -> {
                drawn.remove(feature)
                "OFF"
            }
            feature in unavailable -> "UNSUPPORTED"
            feature in drawn -> "DRAWN"
            installed -> "READY"
            else -> try {
                install()
                "READY"
            } catch (error: Exception) {
                unavailable += feature
                HookEntry.log("GLASS_UNSUPPORTED $feature ${error.javaClass.simpleName}: ${error.message}")
                "UNSUPPORTED"
            } catch (error: LinkageError) {
                unavailable += feature
                HookEntry.log("GLASS_UNSUPPORTED $feature ${error.javaClass.simpleName}")
                "UNSUPPORTED"
            }
        }
        publish(feature, state)
    }

    private fun markDrawn(feature: String) {
        if (drawn.add(feature)) publish(feature, "DRAWN")
    }

    private fun publish(feature: String, state: String, detail: String = "") {
        if (reported.put(feature, state) == state) return
        if (state != "OFF") HookEntry.log("GLASS_${state} $feature build=${BuildConfig.VERSION_CODE}")
        worker.execute { HostStatus.reportFeature(app, feature, state, detail, BuildConfig.VERSION_CODE) }
    }

    private fun reportSurface(name: String, stage: GlassSurfaceStage) {
        val previous = surfaceStages[name]
        if (previous != null && previous >= stage) return
        surfaceStages[name] = stage
        HookEntry.log("GLASS_SURFACE ${stage.name} $name")
        worker.execute {
            HostStatus.reportFeature(app, GlassSurfaceStage.FEATURE_PREFIX + name, stage.name, "", BuildConfig.VERSION_CODE)
        }
    }

    private fun isNight(): Boolean = nightMode

    private companion object {
        val HOME_ACTIVITY_NAMES = setOf(
            "com.sec.android.app.launcher.Launcher",
            "com.sec.android.app.launcher.activities.LauncherActivity",
        )
    }
}
