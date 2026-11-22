package dev.ryuya.galaxyglass

import android.app.Activity
import android.app.Application
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import android.os.UserManager
import android.util.Log
import dev.ryuya.galaxyglass.GlassTargets.CAPTURE
import dev.ryuya.galaxyglass.GlassTargets.CAPTURE_SETTING
import dev.ryuya.galaxyglass.GlassTargets.HOSTS
import dev.ryuya.galaxyglass.GlassTargets.LAUNCHER
import dev.ryuya.galaxyglass.GlassTargets.SYSTEM_UI
import dev.ryuya.galaxyglass.compat.DexSymbols
import dev.ryuya.galaxyglass.hook.Hooks
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.ref.WeakReference

class HookEntry : XposedModule() {
    private lateinit var process: String
    private val main = Handler(Looper.getMainLooper())
    private var capture: GlassCaptureHost? = null
    private var appearanceHost: GlassHost? = null
    private var applicationGlass: ApplicationGlassHost? = null
    private var applicationGlassApp: Application? = null
    private var hostApp: Application? = null
    private var hostLoader: ClassLoader? = null
    private var guardMark: Runnable? = null
    private var guardDeadline = 0L
    @Volatile private var reconnectState: Array<*>? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        process = param.processName
        ModernHook.attach(this)
        Hooks.attach(this)
        GlassComposeCallbacks.configure(moduleApplicationInfo.sourceDir)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val target = param.packageName
        if (!param.isFirstPackage) return
        if (target !in HOSTS) {
            attachApplicationGlass(param)
            return
        }
        if (Process.myUid() / 100000 != 0) return
        val supported = if (target == CAPTURE) GlassTargets.supportsCaptureGlass() else GlassTargets.supportsSystemGlass()
        if (!supported) return
        val helper = target == SYSTEM_UI && CrashGuard.isSystemUiAppearanceHelper(process)
        val capture = target == CAPTURE && (process == CAPTURE || process.startsWith("$CAPTURE:"))
        val homeEdge = target == LAUNCHER && process == "$LAUNCHER:cocktailsettings"
        if (!helper && !capture && !homeEdge && process != target) return
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        ModernHook.aroundMethod(attach) { chain ->
            val result = chain.proceed()
            val app = chain.thisObject as Application
            if (app.packageName == target) HostStartupGate.whenUnlocked(app) { activity ->
                runCatching {
                    if (!capture && !homeEdge) DexSymbols.configure(app)
                    val guard = when {
                        helper -> CrashGuard.enterSystemUiAppearanceHelper(app, process)
                        homeEdge -> CrashGuard.enterApplicationGlass(app, process)
                        else -> CrashGuard.enter(app)
                    }
                    if (guard.blocked()) {
                        val feature = when {
                            capture -> CAPTURE_SETTING
                            homeEdge -> "glass_home_edge_settings"
                            target == LAUNCHER -> GlassAppearance.HOME
                            else -> GlassAppearance.SYSTEM_SURFACES
                        }
                        HostStatus.reportFeature(app, feature, "CRASH_GUARD", "", BuildConfig.VERSION_CODE)
                    } else {
                        if (capture) {
                            installCapture(app, param.classLoader, null)
                            scheduleCaptureHealth(guard, SystemClock.uptimeMillis() + 90_000)
                            return@runCatching
                        }
                        if (target == SYSTEM_UI || target == LAUNCHER) {
                            val attach = {
                                installAppearanceHost(app, param.classLoader, null,
                                    initialActivity = activity?.takeUnless(Activity::isDestroyed))
                                scheduleCaptureHealth(guard, SystemClock.uptimeMillis() + 90_000)
                            }
                            if (homeEdge) attach() else DexSymbols.whenPrepared(app, attach)
                            return@runCatching
                        }
                    }
                }.onFailure { log("Glass startup unavailable: ${it.javaClass.simpleName} ${it.message}") }
            }
            result
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean =
        MainThreadReload.run(main) handOff@{
            val applicationHost = applicationGlass
            val application = applicationGlassApp
            if (applicationHost != null && application != null) {
                if (!applicationHost.canRetire()) return@handOff false
                if (!GlassHookDispatch.stop {
                        param.setSavedInstanceState(arrayOf(application, application.classLoader,
                            applicationHost.stateForReload(), 0L, true))
                    }) return@handOff false
                applicationHost.close()
                applicationGlass = null
                applicationGlassApp = null
                return@handOff true
            }
            val app = hostApp
            val loader = hostLoader
            val host = capture
            val helper = appearanceHost
            if (app == null || loader == null || (host == null && helper == null)) {
                val state = reconnectState ?: return@handOff false
                if (!GlassHookDispatch.stop { param.setSavedInstanceState(state) })
                    return@handOff false
                reconnectState = null
                return@handOff true
            }
            if (host?.readyForHotReload() == false || helper?.idleForHotReload() == false)
                return@handOff false
            if (helper != null && Build.VERSION.SDK_INT < 33) return@handOff false
            if (!GlassHookDispatch.stop {
                    val state = if (Build.VERSION.SDK_INT >= 33 && helper != null) helper.stateForReload()
                        else host?.exportState()
                    param.setSavedInstanceState(arrayOf(app, loader, state, guardDeadline))
                }) return@handOff false
            host?.close()
            if (Build.VERSION.SDK_INT >= 33) helper?.close()
            if (helper != null) DexSymbols.detachObserver()
            appearanceHost = null
            guardMark?.let(main::removeCallbacks)
            guardMark = null
            guardDeadline = 0L
            capture = null
            hostApp = null
            hostLoader = null
            true
        }

    override fun onHotReloaded(param: HotReloadedParam) {
        val state = requireNotNull(param.savedInstanceState as? Array<*>)
        val app = requireNotNull(state.getOrNull(0) as? Application)
        val loader = requireNotNull(state.getOrNull(1) as? ClassLoader)
        if (state.getOrNull(4) == true) {
            check(ApplicationGlassTargets.includes(app.applicationInfo))
            check(Process.myUid() / 100000 == 0 ||
                app.getSystemService(UserManager::class.java)?.isManagedProfile == true)
            onModuleLoaded(param)
            check(MainThreadReload.run(main) {
                reconnectState = state
                val previous = requireNotNull(state.getOrNull(2) as? Array<*>)
                val activity = (previous.firstOrNull() as? WeakReference<*>)?.get() as? Activity
                installApplicationGlass(app, activity?.takeUnless(Activity::isDestroyed), previous)
                installInputMethodCreation(app) { applicationGlass?.bindInputMethod(it) }
                param.oldHookHandles.forEach { it.unhook() }
                reconnectState = null
                reportHotUpdate()
                true
            }) { "System app main thread was unavailable" }
            return
        }
        val captureTarget = app.packageName == CAPTURE && (param.processName == CAPTURE ||
            param.processName.startsWith("$CAPTURE:"))
        check(captureTarget || isAppearanceHost(param.processName, app.packageName))
        onModuleLoaded(param)
        reconnectState = state
        check(MainThreadReload.run(main) {
            val guard = if (captureTarget) CrashGuard.resume(app)
                else if (process == LAUNCHER || process == SYSTEM_UI) CrashGuard.resume(app)
                else CrashGuard.resumeAppearanceHelper(app, process)
            check(!guard.blocked()) { "Glass startup protection is active" }
            val deadline = state.getOrNull(3) as? Long ?: 0L
            if (captureTarget) {
                installCapture(app, loader, state.getOrNull(2))
            } else {
                if (process != "$LAUNCHER:cocktailsettings") DexSymbols.configure(app)
                installAppearanceHost(app, loader, state.getOrNull(2) as? Array<*>, ::reportHotUpdate)
            }
            if (deadline > 0) scheduleCaptureHealth(guard, deadline)
            param.oldHookHandles.forEach { it.unhook() }
            if (captureTarget) reportHotUpdate()
            reconnectState = null
            true
        }) { "Glass host main thread was unavailable" }
    }

    private fun reportHotUpdate() {
        log("ONEUI_MODS_HOT_RELOAD package=${BuildConfig.APPLICATION_ID} " +
            "version=${BuildConfig.VERSION_CODE} pid=${Process.myPid()}")
    }

    private fun isAppearanceHost(name: String, packageName: String): Boolean =
        (packageName == SYSTEM_UI && (name == SYSTEM_UI || CrashGuard.isSystemUiAppearanceHelper(name))) ||
            (packageName == LAUNCHER && (name == LAUNCHER || name == "$LAUNCHER:cocktailsettings"))

    private fun installAppearanceHost(
        app: Application,
        loader: ClassLoader,
        state: Array<*>?,
        ready: (() -> Unit)? = null,
        initialActivity: Activity? = null,
    ) {
        GlassHookDispatch.track()
        val brief = process == "$SYSTEM_UI:edgelighting"
        val host = GlassHost(app, loader, initialActivity,
            briefOnly = brief,
            systemSurfacesOnly = app.packageName == SYSTEM_UI && process != SYSTEM_UI && !brief,
            homeEdgeOnly = process == "$LAUNCHER:cocktailsettings")
        try {
            host.start(state, ready)
        } catch (error: Throwable) {
            if (Build.VERSION.SDK_INT >= 33) host.close()
            throw error
        }
        appearanceHost = host
        hostApp = app
        hostLoader = loader
    }

    private fun installCapture(app: Application, loader: ClassLoader, state: Any?) {
        GlassHookDispatch.track()
        val host = GlassCaptureHost(app, loader)
        try {
            host.start(state)
        } catch (error: Throwable) {
            host.close()
            throw error
        }
        capture = host
        hostApp = app
        hostLoader = loader
    }

    private fun scheduleCaptureHealth(guard: CrashGuard, deadline: Long) {
        val mark = Runnable {
            guardMark = null
            guardDeadline = 0L
            guard.healthy()
        }
        guardMark = mark
        guardDeadline = deadline
        main.postAtTime(mark, deadline)
    }

    private fun attachApplicationGlass(param: PackageReadyParam) {
        if (process == "system" || process == "system_server" ||
            !ApplicationGlassTargets.includes(param.applicationInfo) || apiVersion < 102 ||
            frameworkProperties and XposedInterface.PROP_CAP_REMOTE == 0L) return
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        ModernHook.aroundMethod(attach) { chain ->
            val result = chain.proceed()
            val app = chain.thisObject as Application
            if (app.packageName == param.packageName &&
                (Process.myUid() / 100000 == 0 ||
                    app.getSystemService(UserManager::class.java)?.isManagedProfile == true)) {
                prepareApplicationGlass(app)
            }
            result
        }
    }

    private fun installApplicationGlass(app: Application, activity: Activity?, previous: Array<*>? = null) {
        GlassHookDispatch.track()
        val preferences = getRemotePreferences(ApplicationGlassConfig.REMOTE)
        val host = ApplicationGlassHost(app, preferences, process)
        try {
            host.start(activity, previous)
        } catch (error: Throwable) {
            host.close()
            throw error
        }
        applicationGlass = host
        applicationGlassApp = app
    }

    private fun installInputMethodCreation(app: Application, attach: (InputMethodService) -> Unit) {
        if (!ApplicationGlassTargets.hasInputMethodSurfaces(app.packageName)) return
        runCatching {
            val onCreate = InputMethodService::class.java.getMethod("onCreate")
            ModernHook.aroundMethod(onCreate) { chain ->
                val result = chain.proceed()
                val service = chain.thisObject as InputMethodService
                if (service.packageName == app.packageName) attach(service)
                result
            }
        }.onFailure { log("GLASS_APP_INPUT_METHOD_UNAVAILABLE ${app.packageName} ${it.javaClass.simpleName}") }
    }

    private fun prepareApplicationGlass(app: Application) {
        GlassHookDispatch.track()
        var waitingForUnlock = false
        var attempts = 0
        var adapterFailed = false
        var activity = WeakReference<Activity>(null)
        var inputMethod = WeakReference<InputMethodService>(null)
        lateinit var lifecycle: Application.ActivityLifecycleCallbacks
        fun start() {
            if (waitingForUnlock || applicationGlass != null || adapterFailed || attempts >= 3) return
            waitingForUnlock = true
            runCatching {
                HostStartupGate.whenUnlocked(app) {
                    waitingForUnlock = false
                    attempts++
                    runCatching {
                        installApplicationGlass(app, activity.get()?.takeUnless(Activity::isDestroyed))
                        app.unregisterActivityLifecycleCallbacks(lifecycle)
                        inputMethod.get()?.let { applicationGlass?.bindInputMethod(it) }
                    }.onFailure {
                        adapterFailed = true
                        app.unregisterActivityLifecycleCallbacks(lifecycle)
                        log("GLASS_APP_UNAVAILABLE ${app.packageName} ${it.javaClass.simpleName}")
                    }
                }
            }.onFailure {
                waitingForUnlock = false
                attempts++
                log("GLASS_APP_START_UNAVAILABLE ${app.packageName} ${it.javaClass.simpleName}")
            }
        }
        lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(current: Activity, state: Bundle?) {
                activity = WeakReference(current)
                start()
            }

            override fun onActivityPostResumed(current: Activity) {
                if (applicationGlass == null && !waitingForUnlock) {
                    activity = WeakReference(current)
                    start()
                }
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(lifecycle)
        installInputMethodCreation(app) { service ->
            inputMethod = WeakReference(service)
            val running = applicationGlass
            if (running == null) start() else runCatching { running.bindInputMethod(service) }
                .onFailure { log("GLASS_APP_INPUT_METHOD_UNAVAILABLE ${app.packageName} ${it.javaClass.simpleName}") }
        }
        log("GLASS_APP_SCOPE_READY ${app.packageName} process=$process build=${BuildConfig.VERSION_CODE}")
    }

    companion object {
        fun log(message: String) {
            Log.i("GalaxyGlassTools", message)
            ModernHook.log(message)
        }
    }
}
