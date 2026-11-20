package dev.ryuya.galaxyglass

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import dev.ryuya.galaxyglass.compat.DexSymbols
import dev.ryuya.galaxyglass.hook.Hooks
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
        if (!param.isFirstPackage || target !in HOSTS || Process.myUid() / 100000 != 0) return
        if (!(if (target == CAPTURE) captureSupported() else supported())) return
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
                        else GlassHost(app, param.classLoader, activity,
                            briefOnly = process == "$SYSTEM_UI:edgelighting",
                            systemSurfacesOnly = helper && process != "$SYSTEM_UI:edgelighting").start()
                        Handler(Looper.getMainLooper()).postDelayed({ guard.healthy() }, 90_000)
                    }
                }.onFailure { log("Glass startup unavailable: ${it.javaClass.simpleName} ${it.message}") }
            }
            result
        }
    }

    companion object {
        const val LAUNCHER = "com.sec.android.app.launcher"
        const val SYSTEM_UI = "com.android.systemui"
        const val CAPTURE = "com.samsung.android.app.smartcapture"
        const val CAPTURE_SETTING = "glass_capture_controls"
        private const val FINGERPRINT =
            "samsung/m3qjpnw/m3q:17/CP2A.260605.016/S948QOPU1BZI9_SJP1BZI9:user/release-keys"
        val HOSTS = setOf(LAUNCHER, SYSTEM_UI, CAPTURE)

        fun supported() = Build.VERSION.SDK_INT == 37 && Build.FINGERPRINT == FINGERPRINT

        fun captureSupported() = supported() ||
            (Build.VERSION.SDK_INT == 36 && Build.MODEL == "SM-S948Q" && Build.DEVICE == "m3q" &&
                Build.DISPLAY == "BP4A.251205.006.S948QOPS1AZHL")

        fun log(message: String) {
            Log.i("GalaxyGlassTools", message)
            ModernHook.log(message)
        }
    }
}
