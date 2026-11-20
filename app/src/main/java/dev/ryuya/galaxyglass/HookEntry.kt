package dev.ryuya.galaxyglass

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
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
            attachSystemApp(param)
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

    private fun attachSystemApp(param: PackageReadyParam) {
        if (process == "system" || process == "system_server" ||
            !SystemAppTargets.includes(param.applicationInfo) || apiVersion < 102 ||
            frameworkProperties and XposedInterface.PROP_CAP_REMOTE == 0L) return
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        ModernHook.aroundMethod(attach) { chain ->
            val result = chain.proceed()
            val app = chain.thisObject as Application
            if (app.packageName == param.packageName) runCatching {
                val flags = PackageManager.PackageInfoFlags.of(
                    (PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS).toLong(),
                )
                val hasActivities = app.packageManager.getPackageInfo(app.packageName, flags)
                    .activities.orEmpty().any { it.processName == process && SystemAppTargets.hasUiActivity(it.name) }
                if (hasActivities) HostStartupGate.whenUnlocked(app) {
                    runCatching {
                        val prefs = getRemotePreferences(SystemAppGlassConfig.REMOTE)
                        SystemAppGlassHost(app, prefs, process).start()
                    }.onFailure { log("GLASS_SYSTEM_APP_UNAVAILABLE ${app.packageName} ${it.javaClass.simpleName}") }
                }
            }.onFailure { log("GLASS_SYSTEM_APP_ATTACH_UNAVAILABLE ${app.packageName} ${it.javaClass.simpleName}") }
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
