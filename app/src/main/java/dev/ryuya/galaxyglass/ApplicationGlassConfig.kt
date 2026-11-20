package dev.ryuya.galaxyglass

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** Only appearance preferences are shared with scoped applications. */
internal data class ApplicationGlassConfig(val strength: Int, val tint: Int) {
    companion object {
        const val SYSTEM_SWITCH = "glass_system_apps"
        const val INSTALLED_SWITCH = "glass_installed_apps"
        // Retain the group used by running 0.3.0 system app processes.
        const val REMOTE = "system_app_appearance"

        fun read(prefs: SharedPreferences, system: Boolean): ApplicationGlassConfig {
            val key = if (system) SYSTEM_SWITCH else INSTALLED_SWITCH
            val strength = if (prefs.getBoolean(key, false)) {
                prefs.getInt(GlassAppearance.STRENGTH, GlassAppearance.DEFAULT_STRENGTH).coerceIn(0, 100)
            } else 0
            return ApplicationGlassConfig(strength, GlassAppearance.tintPercent(
                prefs.getInt(GlassAppearance.TINT, GlassAppearance.DEFAULT_TINT),
            ))
        }

        fun publish(local: SharedPreferences, remote: SharedPreferences): Boolean =
            remote.edit()
                .putBoolean(SYSTEM_SWITCH, local.getBoolean("enabled", true) && local.getBoolean(SYSTEM_SWITCH, true))
                .putBoolean(INSTALLED_SWITCH, local.getBoolean("enabled", true) && local.getBoolean(INSTALLED_SWITCH, true))
                .putInt(GlassAppearance.STRENGTH, local.getInt(GlassAppearance.STRENGTH, GlassAppearance.DEFAULT_STRENGTH))
                .putInt(GlassAppearance.TINT, local.getInt(GlassAppearance.TINT, GlassAppearance.DEFAULT_TINT))
                .commit()
    }
}

internal object ApplicationGlassTargets {
    fun includes(app: ApplicationInfo): Boolean = includes(app.packageName, app.flags, app.enabled)

    fun includes(packageName: String, flags: Int, enabled: Boolean): Boolean =
        enabled && flags and ApplicationInfo.FLAG_HAS_CODE != 0 &&
            packageName != "android" && packageName != BuildConfig.APPLICATION_ID && packageName !in GlassTargets.HOSTS

    fun isSystem(app: ApplicationInfo): Boolean =
        app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    fun hasUiActivity(name: String): Boolean = name != "android.app.AppDetailsActivity"

    fun packages(pm: PackageManager): List<String> {
        val flags = PackageManager.PackageInfoFlags.of(
            (PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS).toLong(),
        )
        return pm.getInstalledPackages(flags)
            .filter { info -> info.applicationInfo?.let(::includes) == true && info.activities.orEmpty().any { hasUiActivity(it.name) } }
            .map { it.packageName }
            .distinct()
            .sorted()
    }

    // The resource IDs come from these apps' own BZI9 APKs. IDs absent in another version are skipped.
    fun plates(packageName: String): Map<String, Int> = when (packageName) {
        "com.android.settings" -> mapOf(
            "aa_settings_cardview" to 24, "sec_dynamic_app_bar_layout" to 0, "search_plate" to 22,
        )
        "com.samsung.android.dialer" -> mapOf(
            "toolbar_container" to 0, "integrated_search_container" to 22, "bottom_menu_container" to 24,
        )
        "com.samsung.android.app.contacts" -> mapOf(
            "share_contacts_app_bar" to 0, "search_container" to 22, "bottom_menu_container" to 24,
        )
        "com.sec.android.gallery3d" -> mapOf(
            "bottom_bar_floating_container" to 24, "bottom_bar_layout_container" to 24,
            "toolbar_container" to 0, "location_search_bar_layout" to 22,
        )
        "com.sec.android.app.camera" -> mapOf("capture_ai_bottom_action" to 24)
        "com.sec.android.app.myfiles" -> mapOf("myfiles_search_plate" to 22, "dialog_bottom_menu" to 24)
        "com.google.android.documentsui", "com.android.documentsui" -> mapOf("search_plate" to 22)
        else -> emptyMap()
    }
}
