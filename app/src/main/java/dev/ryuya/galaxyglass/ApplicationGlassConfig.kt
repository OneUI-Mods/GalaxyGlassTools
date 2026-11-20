package dev.ryuya.galaxyglass

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** Only appearance preferences are shared with scoped applications. */
internal data class ApplicationGlassConfig(val strength: Int, val tint: Int) {
    companion object {
        const val SYSTEM_SWITCH = "glass_system_apps"
        private const val LEGACY_INSTALLED_SWITCH = "glass_installed_apps"
        // Retain the group used by running 0.3.0 system app processes.
        const val REMOTE = "system_app_appearance"

        fun read(prefs: SharedPreferences): ApplicationGlassConfig {
            val strength = if (prefs.getBoolean(SYSTEM_SWITCH, false)) {
                prefs.getInt(GlassAppearance.STRENGTH, GlassAppearance.DEFAULT_STRENGTH).coerceIn(0, 100)
            } else 0
            return ApplicationGlassConfig(strength, GlassAppearance.tintPercent(
                prefs.getInt(GlassAppearance.TINT, GlassAppearance.DEFAULT_TINT),
            ))
        }

        fun publish(local: SharedPreferences, remote: SharedPreferences): Boolean =
            remote.edit()
                .putBoolean(SYSTEM_SWITCH, local.getBoolean("enabled", true) && local.getBoolean(SYSTEM_SWITCH, true))
                // Disable the old renderer even in third-party processes that still have 0.4.0 loaded.
                .putBoolean(LEGACY_INSTALLED_SWITCH, false)
                .putInt(GlassAppearance.STRENGTH, local.getInt(GlassAppearance.STRENGTH, GlassAppearance.DEFAULT_STRENGTH))
                .putInt(GlassAppearance.TINT, local.getInt(GlassAppearance.TINT, GlassAppearance.DEFAULT_TINT))
                .commit()
    }
}

internal object ApplicationGlassTargets {
    private const val SAMSUNG_KEYBOARD = "com.samsung.android.honeyboard"

    fun includes(app: ApplicationInfo): Boolean = includes(app.packageName, app.flags, app.enabled)

    fun includes(packageName: String, flags: Int, enabled: Boolean): Boolean =
        enabled && flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 &&
            flags and ApplicationInfo.FLAG_HAS_CODE != 0 &&
            packageName != "android" && packageName != BuildConfig.APPLICATION_ID && packageName !in GlassTargets.HOSTS

    fun hasUiActivity(name: String): Boolean = name != "android.app.AppDetailsActivity"

    fun packages(pm: PackageManager): List<String> {
        val flags = PackageManager.PackageInfoFlags.of(
            (PackageManager.MATCH_SYSTEM_ONLY or PackageManager.GET_ACTIVITIES or
                PackageManager.MATCH_DISABLED_COMPONENTS).toLong(),
        )
        return pm.getInstalledPackages(flags)
            .filter { info ->
                info.applicationInfo?.let(::includes) == true &&
                    (hasInputMethodSurfaces(info.packageName) || info.activities.orEmpty().any { hasUiActivity(it.name) })
            }
            .map { it.packageName }
            .distinct()
            .sorted()
    }

    fun hasInputMethodSurfaces(packageName: String): Boolean = inputMethodPlates(packageName).isNotEmpty()

    fun inputMethodPlates(packageName: String): Map<String, Int> =
        if (packageName == SAMSUNG_KEYBOARD) SAMSUNG_INPUT_PLATES else emptyMap()

    private val SAMSUNG_INPUT_PLATES = mapOf(
        "keyboard_background" to 18,
        "candidate_view" to 12,
        "clipboardPanel" to 20,
        "toolbar_background_layout" to 16,
        "toolbar_root_window" to 20,
    )

    val sheetPlates = mapOf("design_bottom_sheet" to 24)

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
        "com.android.intentresolver", "com.google.android.intentresolver" -> mapOf(
            "chooser_dialog_content" to 24,
            "sem_chooser_select_apps_fragment_root_view" to 24,
            "sem_more_apps_grid_view" to 22,
        )
        "com.android.chrome" -> mapOf(
            "toolbar" to 0,
            "location_bar_background_view" to 22,
            "hub_toolbar" to 0,
        )
        else -> emptyMap()
    }

    fun layoutPlates(packageName: String): Map<String, Int> = when (packageName) {
        "com.android.intentresolver", "com.google.android.intentresolver" -> mapOf("resolver_profile_tab_button" to 20)
        else -> emptyMap()
    }

    fun usesOverlay(packageName: String, className: String): Boolean =
        packageName == "com.android.chrome" && className in CHROME_CANVAS_TOOLBARS

    private val CHROME_CANVAS_TOOLBARS = setOf(
        "org.chromium.chrome.browser.toolbar.top.ToolbarPhone",
        "org.chromium.chrome.browser.toolbar.top.ToolbarTablet",
        "org.chromium.chrome.browser.hub.HubToolbarView",
    )
}
