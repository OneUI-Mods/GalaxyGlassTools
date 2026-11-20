package dev.ryuya.galaxyglass

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object TargetReload {
    enum class Target(val packageName: String, val button: Int) {
        HOME(GlassTargets.LAUNCHER, R.id.glass_reload_home),
        CAPTURE(GlassTargets.CAPTURE, R.id.glass_reload_capture),
    }

    enum class Result(val message: Int) {
        HOME_READY(R.string.glass_reload_home_ready),
        CAPTURE_STOPPED(R.string.glass_reload_capture_ready),
        CAPTURE_BUSY(R.string.glass_reload_capture_busy),
        ROOT_REQUIRED(R.string.glass_reload_root_required),
        FAILED(R.string.glass_reload_failed),
    }

    private val worker = BackgroundExecutors.serial("GlassTargetReload")
    private val running = AtomicBoolean()

    fun start(target: Target, complete: (Result) -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) return false
        worker.execute {
            val result = try {
                reload(target)
            } finally {
                running.set(false)
            }
            complete(result)
        }
        return true
    }

    // Only the app's two explicit buttons can invoke these fixed, primary-user operations.
    private fun reload(target: Target): Result {
        val command = """
            [ "${'$'}(/system/bin/id -u)" = 0 ] || exit 10
            [ "${'$'}(/system/bin/am get-current-user)" = 0 ] || exit 11
        """.trimIndent() + "\n" + when (target) {
            Target.HOME -> """
                /system/bin/am stop-app --user 0 ${target.packageName} || exit 11
                /system/bin/am start --user 0 -W -a android.intent.action.MAIN \
                    -c android.intent.category.HOME -p ${target.packageName} || exit 11
                exit 20
            """.trimIndent()
            Target.CAPTURE -> """
                state=${'$'}(/system/bin/dumpsys -t 3 media_projection) || exit 11
                projection=${'$'}(printf '%s\n' "${'$'}state" | /system/bin/awk '/^Media Projection:/ { getline; print; exit }')
                [ -n "${'$'}projection" ] || exit 11
                [ "${'$'}projection" = null ] || exit 12
                recorder=${'$'}(/system/bin/dumpsys -t 3 activity services ScreenRecorderService) || exit 11
                if printf '%s\n' "${'$'}recorder" | /system/bin/grep -q 'ServiceRecord.* u0 ${target.packageName}/'; then
                    exit 12
                fi
                /system/bin/am stop-app --user 0 ${target.packageName} || exit 11
                exit 21
            """.trimIndent()
        }
        val process = try {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .redirectOutput(File("/dev/null"))
                .start()
        } catch (_: IOException) {
            return Result.ROOT_REQUIRED
        }
        return try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) return Result.FAILED
            when (process.exitValue()) {
                20 -> Result.HOME_READY
                21 -> Result.CAPTURE_STOPPED
                12 -> Result.CAPTURE_BUSY
                11 -> Result.FAILED
                else -> Result.ROOT_REQUIRED
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.FAILED
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
