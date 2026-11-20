package dev.ryuya.galaxyglass

import android.app.Application
import android.app.Dialog
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import dev.ryuya.galaxyglass.compat.DexSymbols

internal class GlassCaptureHost(private val app: Application, private val loader: ClassLoader) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = BackgroundExecutors.serial("GlassCaptureConfig")
    private val runtime = GlassCaptureRuntime(app, worker)

    fun start() {
        CaptureToolbarGlass.install(runtime, loader)
        val glass = RecordingStartGlass(runtime)
        if (Application.getProcessName() == "${GlassTargets.CAPTURE}:screenrecorder" ||
            Application.getProcessName() == GlassTargets.CAPTURE) installDialogs(glass)
        app.contentResolver.registerContentObserver(ConfigProvider.URI, false, object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = read()
        })
        read()
    }

    private fun read() {
        worker.execute {
            val config = runCatching {
                app.contentResolver.call(ConfigProvider.URI, "config", null, null)
            }.getOrNull()
            main.post {
                runtime.update(config?.getBoolean(GlassTargets.CAPTURE_SETTING) == true,
                    config?.getInt(GlassAppearance.STRENGTH, GlassAppearance.DEFAULT_STRENGTH) ?: 0,
                    config?.getInt(GlassAppearance.TINT, GlassAppearance.DEFAULT_TINT) ?: GlassAppearance.DEFAULT_TINT)
            }
        }
    }

    private fun installDialogs(glass: RecordingStartGlass) {
        runCatching {
            ModernHook.aroundMethod(Dialog::class.java.getMethod("show")) { chain ->
                val result = chain.proceed()
                val dialog = chain.thisObject as Dialog
                runCatching {
                    when (dialog.window?.peekDecorView()?.tag) {
                        START_DIALOG -> glass.attach(dialog)
                        TARGET_PICKER -> glass.attach(dialog, captureBackdrop = false)
                    }
                }.onFailure { HookEntry.log("Capture dialog glass unavailable: ${it.javaClass.simpleName}") }
                result
            }
        }.onFailure { HookEntry.log("Capture tagged dialog hook unavailable: ${it.javaClass.simpleName}") }
        runCatching { installRecordingStartHook(glass) }
            .onFailure { HookEntry.log("Recording start hook unavailable: ${it.javaClass.simpleName}") }
    }

    private fun installRecordingStartHook(glass: RecordingStartGlass) {
        val controller = DexSymbols.findClass(
            "com.samsung.android.app.smartcapture.screenrecorder.ScreenRecorderController", false, loader)
        val popup = controller.getDeclaredField("mSoundSettingSuggestionPopup").apply { isAccessible = true }
        val record = controller.declaredMethods.single { it.name == "showSoundSettingSuggestionPopup" }
        ModernHook.aroundMethod(record) { chain ->
            val result = chain.proceed()
            runCatching {
                val dialog = popup.get(chain.thisObject) as? Dialog ?: return@runCatching
                dialog.window?.decorView?.setTag(START_DIALOG)
                glass.attach(dialog)
            }.onFailure { HookEntry.log("Recording start glass unavailable: ${it.javaClass.simpleName}") }
            result
        }
    }

    private companion object {
        const val TARGET_PICKER = "dev.ryuya.galaxyglass.capture_target"
        const val START_DIALOG = "dev.ryuya.galaxyglass.capture_start"
    }
}

internal class GlassCaptureRuntime(val context: Context, private val worker: java.util.concurrent.Executor) {
    private val listeners = mutableListOf<() -> Unit>()
    var enabled = false
        private set
    var strength = 0
        private set
    var tint = GlassAppearance.DEFAULT_TINT
        private set
    val stockTransparency: Float get() = 1.5f - tint / 100f
    val faceTint: Float get() = .35f + 1.3f * tint / 100f
    private var reported = false

    fun onOptionsChanged(listener: () -> Unit) {
        listeners += listener
    }

    fun update(enabled: Boolean, strength: Int, tint: Int) {
        val active = enabled && strength > 0
        val nextTint = GlassAppearance.tintPercent(tint)
        if (this.enabled == active && this.strength == strength && this.tint == nextTint) return
        val wasActive = this.enabled
        this.enabled = active
        this.strength = strength
        this.tint = nextTint
        if (wasActive != active) {
            reported = false
            report(if (active) "READY" else "OFF")
        }
        listeners.forEach { it() }
    }

    fun didDraw() {
        if (reported) return
        reported = true
        report("DRAWN")
    }

    private fun report(state: String) {
        worker.execute { HostStatus.reportFeature(context, GlassTargets.CAPTURE_SETTING, state, "", BuildConfig.VERSION_CODE) }
    }
}
