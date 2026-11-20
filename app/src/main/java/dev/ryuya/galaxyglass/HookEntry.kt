package dev.ryuya.galaxyglass

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
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

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        process = param.processName
        ModernHook.attach(this)
        Hooks.attach(this)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val target = param.packageName
        if (!param.isFirstPackage || Process.myUid() / 100000 != 0) return
        if (target !in HOSTS) {
            attachApplicationGlass(param)
            return
        }
        val supported = if (target == CAPTURE) GlassTargets.supportsCaptureGlass() else GlassTargets.supportsSystemGlass()
        if (!supported) return
        val helper = target == SYSTEM_UI && CrashGuard.isSystemUiAppearanceHelper(process)
        val capture = target == CAPTURE && (process == CAPTURE || process.startsWith("$CAPTURE:"))
        if (!helper && !capture && process != target) return
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        ModernHook.aroundMethod(attach) { chain ->
            val result = chain.proceed()
            val app = chain.thisObject as Application
            if (app.packageName == target) HostStartupGate.whenUnlocked(app) { activity ->
                runCatching {
                    if (!capture) DexSymbols.configure(app)
                    val guard = if (helper) CrashGuard.enterSystemUiAppearanceHelper(app, process) else
                        CrashGuard.enter(app)
                    if (guard.blocked()) {
                        HostStatus.reportFeature(app, if (capture) CAPTURE_SETTING else if (target == LAUNCHER)
                            GlassAppearance.HOME else GlassAppearance.SYSTEM_SURFACES, "CRASH_GUARD", "", BuildConfig.VERSION_CODE)
                    } else {
                        if (capture) GlassCaptureHost(app, param.classLoader).start()
                        else DexSymbols.whenPrepared(app) {
                            runCatching {
                                GlassHost(app, param.classLoader, activity?.takeUnless { it.isDestroyed },
                                    briefOnly = process == "$SYSTEM_UI:edgelighting",
                                    systemSurfacesOnly = helper && process != "$SYSTEM_UI:edgelighting").start()
                            }.onFailure { log("Glass host unavailable: ${it.javaClass.simpleName} ${it.message}") }
                        }
                        Handler(Looper.getMainLooper()).postDelayed({ guard.healthy() }, 90_000)
                    }
                }.onFailure { log("Glass startup unavailable: ${it.javaClass.simpleName} ${it.message}") }
            }
            result
        }
    }

    private fun attachApplicationGlass(param: PackageReadyParam) {
        if (process == "system" || process == "system_server" ||
            !ApplicationGlassTargets.includes(param.applicationInfo) || apiVersion < 102 ||
            frameworkProperties and XposedInterface.PROP_CAP_REMOTE == 0L) return
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        ModernHook.aroundMethod(attach) { chain ->
            val result = chain.proceed()
            val app = chain.thisObject as Application
            if (app.packageName == param.packageName) {
                // A scoped app's push, sync and service processes may never build an Activity.
                // Prepare rendering before the first Activity creates its view hierarchy.
                app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityPreCreated(activity: Activity, state: Bundle?) {
                        app.unregisterActivityLifecycleCallbacks(this)
                        val firstActivity = WeakReference(activity)
                        runCatching {
                            HostStartupGate.whenUnlocked(app) {
                                runCatching {
                                    val prefs = getRemotePreferences(ApplicationGlassConfig.REMOTE)
                                    ApplicationGlassHost(app, prefs, process).start(firstActivity.get()?.takeUnless(Activity::isDestroyed))
                                }.onFailure { log("GLASS_APP_UNAVAILABLE ${app.packageName} ${it.javaClass.simpleName}") }
                            }
                        }.onFailure { log("GLASS_APP_START_UNAVAILABLE ${app.packageName} ${it.javaClass.simpleName}") }
                    }

                    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                    override fun onActivityStarted(activity: Activity) = Unit
                    override fun onActivityResumed(activity: Activity) = Unit
                    override fun onActivityPaused(activity: Activity) = Unit
                    override fun onActivityStopped(activity: Activity) = Unit
                    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                    override fun onActivityDestroyed(activity: Activity) = Unit
                })
                log("GLASS_APP_SCOPE_READY ${app.packageName} process=$process build=${BuildConfig.VERSION_CODE}")
            }
            result
        }
    }

    companion object {
        fun log(message: String) {
            Log.i("GalaxyGlassTools", message)
            ModernHook.log(message)
        }
    }
}
