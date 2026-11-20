package dev.ryuya.galaxyglass

import android.os.Build

// Also used by the settings app, which runs without the libxposed hook API.
internal object GlassTargets {
    const val LAUNCHER = "com.sec.android.app.launcher"
    const val SYSTEM_UI = "com.android.systemui"
    const val CAPTURE = "com.samsung.android.app.smartcapture"
    const val CAPTURE_SETTING = "glass_capture_controls"
    private const val FINGERPRINT =
        "samsung/m3qjpnw/m3q:17/CP2A.260605.016/S948QOPU1BZI9_SJP1BZI9:user/release-keys"
    val HOSTS = setOf(LAUNCHER, SYSTEM_UI, CAPTURE)

    fun supportsSystemGlass() = Build.VERSION.SDK_INT == 37 && Build.FINGERPRINT == FINGERPRINT

    fun supportsCaptureGlass() = supportsSystemGlass() ||
        (Build.VERSION.SDK_INT == 36 && Build.MODEL == "SM-S948Q" && Build.DEVICE == "m3q" &&
            Build.DISPLAY == "BP4A.251205.006.S948QOPS1AZHL")
}
