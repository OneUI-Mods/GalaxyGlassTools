package dev.ryuya.galaxyglass

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle

internal object GlassSettings {
    private const val HOME_ID = "dev.ryuya.oneuihometweaks"
    private const val CAPTURE_ID = "dev.ryuya.galaxycapture"
    private const val HOME_CERT = "7938e1850451b95c45c8f138a768c9da7c5d9db7fb61a883ac8dc724077ddb03"
    private const val CAPTURE_CERT = "3c18719ba633ed0e891d8dca62053c849986156fd10410bd5f0115113ffa6075"
    private const val HOME_IMPORTED = "imported_home"
    private const val CAPTURE_IMPORTED = "imported_capture"
    val homeKeys = listOf(GlassAppearance.QUICK, GlassAppearance.NOTIFICATIONS, GlassAppearance.HOME,
        GlassAppearance.VOLUME, GlassAppearance.SYSTEM_SURFACES, GlassAppearance.LOCK_SCREEN)

    @Synchronized
    fun migrate(context: Context) {
        val prefs = context.getSharedPreferences(ConfigProvider.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(HOME_IMPORTED, false)) {
            legacy(context, HOME_ID, HOME_CERT)?.let { incoming ->
                val change = prefs.edit()
                for (key in homeKeys) {
                    if (!prefs.contains(key)) change.putBoolean(key, incoming.getBoolean(key))
                }
                for (key in listOf(GlassAppearance.STRENGTH, GlassAppearance.TINT, "volume")) {
                    if (!prefs.contains(key) && incoming.containsKey(key)) change.putInt(key, incoming.getInt(key))
                }
                change.putBoolean(HOME_IMPORTED, true).apply()
            }
        }
        if (!prefs.getBoolean(CAPTURE_IMPORTED, false)) {
            legacy(context, CAPTURE_ID, CAPTURE_CERT)?.let { incoming ->
                val change = prefs.edit()
                if (!prefs.contains(GlassTargets.CAPTURE_SETTING)) {
                    change.putBoolean(GlassTargets.CAPTURE_SETTING, incoming.getBoolean(GlassTargets.CAPTURE_SETTING))
                }
                change.putBoolean(CAPTURE_IMPORTED, true).apply()
            }
        }
    }

    private fun legacy(context: Context, packageName: String, digest: String): Bundle? {
        val authority = "$packageName.config"
        @Suppress("DEPRECATION")
        val host = context.packageManager.resolveContentProvider(authority, 0) ?: return Bundle.EMPTY
        if (host.packageName != packageName || !context.packageManager.hasSigningCertificate(
                packageName, digest.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), PackageManager.CERT_INPUT_SHA256))
            return null
        return runCatching {
            context.contentResolver.call(Uri.parse("content://$authority"), "glass_export", null, null)
        }.getOrNull()
    }

    fun snapshot(context: Context): Bundle {
        val prefs = context.getSharedPreferences(ConfigProvider.PREFS, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("enabled", true)
        val homeReady = prefs.getBoolean(HOME_IMPORTED, false)
        val captureReady = prefs.getBoolean(CAPTURE_IMPORTED, false)
        return Bundle().apply {
            for (key in homeKeys) putBoolean(key, enabled && homeReady && prefs.getBoolean(key, false))
            putBoolean(GlassTargets.CAPTURE_SETTING, enabled && captureReady && prefs.getBoolean(GlassTargets.CAPTURE_SETTING, false))
            putInt(GlassAppearance.STRENGTH, prefs.getInt(GlassAppearance.STRENGTH, GlassAppearance.DEFAULT_STRENGTH).coerceIn(0, 100))
            putInt(GlassAppearance.TINT, GlassAppearance.tintPercent(prefs.getInt(GlassAppearance.TINT, GlassAppearance.DEFAULT_TINT)))
            putInt("volume", prefs.getInt("volume", 0).coerceIn(0, 2))
        }
    }

    fun pending(context: Context): Boolean {
        val prefs = context.getSharedPreferences(ConfigProvider.PREFS, Context.MODE_PRIVATE)
        return !prefs.getBoolean(HOME_IMPORTED, false) || !prefs.getBoolean(CAPTURE_IMPORTED, false)
    }
}
