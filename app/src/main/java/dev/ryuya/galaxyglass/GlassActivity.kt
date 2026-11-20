package dev.ryuya.galaxyglass

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.tabs.TabLayout

class GlassActivity : AppCompatActivity() {
    private enum class Page(@StringRes val title: Int, @IdRes val view: Int) {
        APPEARANCE(R.string.glass_settings_tab_appearance, R.id.glass_appearance_page),
        TARGETS(R.string.glass_settings_tab_targets, R.id.glass_targets_page),
        MANAGEMENT(R.string.glass_settings_tab_management, R.id.glass_management_page),
    }

    private enum class TargetOption(
        val host: String, val key: String, @IdRes val control: Int, @IdRes val status: Int,
    ) {
        QUICK(GlassTargets.SYSTEM_UI, GlassAppearance.QUICK, R.id.glass_quick, R.id.glass_quick_state),
        NOTIFICATIONS(GlassTargets.SYSTEM_UI, GlassAppearance.NOTIFICATIONS,
            R.id.glass_notifications, R.id.glass_notifications_state),
        LOCK_SCREEN(GlassTargets.SYSTEM_UI, GlassAppearance.LOCK_SCREEN, R.id.glass_lock_screen, R.id.glass_lock_screen_state),
        VOLUME(GlassTargets.SYSTEM_UI, GlassAppearance.VOLUME, R.id.glass_volume, R.id.glass_volume_state),
        MENUS(GlassTargets.SYSTEM_UI, GlassAppearance.SYSTEM_SURFACES,
            R.id.glass_system_surfaces, R.id.glass_system_surfaces_state),
        HOME(GlassTargets.LAUNCHER, GlassAppearance.HOME, R.id.glass_home, R.id.glass_home_state),
        CAPTURE(GlassTargets.CAPTURE, GlassTargets.CAPTURE_SETTING, R.id.glass_capture, R.id.glass_capture_state);

        val statusKey: String get() = "$host.$key"
    }

    private lateinit var statePrefs: SharedPreferences
    private var stateListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var refreshTargetStates: (() -> Unit)? = null
    private var scopeStatus: TextView? = null
    private var scopeGeneration = 0
    private var page = Page.APPEARANCE

    override fun onResume() {
        super.onResume()
        refreshTargetStates?.invoke()
        if (page == Page.TARGETS) updateSystemAppScope(request = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, page.ordinal)
    }

    override fun onDestroy() {
        stateListener?.let(statePrefs::unregisterOnSharedPreferenceChangeListener)
        super.onDestroy()
    }

    override fun onCreate(state: Bundle?) {
        AppUiTheme.apply(this)
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            val lightSurface = !resources.configuration.isNightModeActive
            isAppearanceLightStatusBars = lightSurface
            isAppearanceLightNavigationBars = lightSurface
        }
        setContentView(R.layout.activity_glass)
        val root = requireViewById<View>(R.id.glass_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)

        GlassSettings.migrate(this)
        val prefs = getSharedPreferences(ConfigProvider.PREFS, Context.MODE_PRIVATE)
        statePrefs = getSharedPreferences("host_status", Context.MODE_PRIVATE)
        val supported = GlassTargets.supportsCaptureGlass()
        bindAppearanceAndTargets(prefs, supported)
        bindManagement(supported)
        bindPages(Page.entries.getOrElse(state?.getInt(STATE_PAGE) ?: 0) { Page.APPEARANCE })
    }

    private fun bindPages(initialPage: Page) {
        val tabs = requireViewById<TabLayout>(R.id.glass_tabs)
        val pages = Page.entries.associateWith { requireViewById<View>(it.view) }
        for (item in Page.entries) {
            tabs.addTab(tabs.newTab().setText(item.title), false)
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                page = Page.entries[tab.position]
                for ((item, view) in pages) {
                    view.visibility = if (item == page) View.VISIBLE else View.GONE
                }
                if (page == Page.TARGETS) updateSystemAppScope(request = false)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        tabs.getTabAt(initialPage.ordinal)?.select()
    }

    private fun bindAppearanceAndTargets(prefs: SharedPreferences, supported: Boolean) {
        val master = requireViewById<MaterialSwitch>(R.id.glass_enabled).apply {
            isChecked = prefs.getBoolean("enabled", true)
        }
        val switches = TargetOption.entries.associateWith { target ->
            requireViewById<MaterialSwitch>(target.control).apply {
                isChecked = if (target == TargetOption.LOCK_SCREEN) GlassAppearance.lockScreenEnabled(prefs)
                    else prefs.getBoolean(target.key, false)
                isEnabled = target == TargetOption.CAPTURE || GlassTargets.supportsSystemGlass() || !supported
            }
        }
        val systemApps = requireViewById<MaterialSwitch>(R.id.glass_system_apps)
        systemApps.isChecked = prefs.getBoolean(ApplicationGlassConfig.SYSTEM_SWITCH, true)
        scopeStatus = requireViewById(R.id.glass_system_app_scope_status)
        requireViewById<View>(R.id.glass_system_app_scope).setOnClickListener {
            updateSystemAppScope(request = true)
        }
        requireViewById<TextView>(R.id.glass_preview_notice).apply {
            visibility = if (supported) View.GONE else View.VISIBLE
            setText(R.string.glass_settings_preview_only)
        }

        val preview = requireViewById<GlassPreview>(R.id.glass_preview)
        val strength = requireViewById<Slider>(R.id.glass_strength)
        val strengthLabel = requireViewById<TextView>(R.id.glass_value)
        val tint = requireViewById<Slider>(R.id.glass_tint)
        val tintLabel = requireViewById<TextView>(R.id.glass_tint_value)
        val savedStrength = prefs.getInt(GlassAppearance.STRENGTH, GlassAppearance.DEFAULT_STRENGTH).coerceIn(0, 100)
        if (savedStrength % 5 != 0) strength.stepSize = 1f
        strength.value = savedStrength.toFloat()
        tint.value = GlassAppearance.tintPercent(
            prefs.getInt(GlassAppearance.TINT, GlassAppearance.DEFAULT_TINT),
        ).toFloat()

        val targetNotice = requireViewById<TextView>(R.id.glass_targets_notice)
        val statuses = TargetOption.entries.associateWith { requireViewById<TextView>(it.status) }
        fun displayStatus() {
            targetNotice.visibility = if (!master.isChecked) View.VISIBLE else View.GONE
            targetNotice.setText(R.string.glass_settings_master_off_notice)
            if (!supported) return
            val currentBoot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
            for ((target, control) in switches) {
                val status = statuses.getValue(target)
                if (!master.isChecked || !control.isChecked || !control.isEnabled || strength.value <= 0f) {
                    status.visibility = View.GONE
                    continue
                }
                status.setText(targetState(target.statusKey, currentBoot))
                status.visibility = View.VISIBLE
            }
        }
        refreshTargetStates = ::displayStatus
        if (supported) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> displayStatus() }
            stateListener = listener
            statePrefs.registerOnSharedPreferenceChangeListener(listener)
        }

        fun updatePreview() {
            val showGlass = master.isChecked
            preview.appearance = GlassAppearance(
                quick = showGlass,
                notifications = showGlass,
                home = showGlass,
                strength = strength.value.toInt(),
                tint = tint.value.toInt(),
                volume = showGlass,
                volumeTheme = prefs.getInt("volume", 0),
                systemSurfaces = showGlass,
            )
            strengthLabel.text = getString(R.string.qs_percent, strength.value.toInt())
            tintLabel.text = getString(R.string.qs_percent, tint.value.toInt())
            displayStatus()
        }

        fun publishAppearance() {
            if (supported) contentResolver.notifyChange(ConfigProvider.URI, null)
            FrameworkServiceConnection.publishApplicationGlassConfig(this)
        }

        master.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean("enabled", enabled).apply()
            updatePreview()
            publishAppearance()
        }
        for ((target, switch) in switches) {
            switch.setOnCheckedChangeListener { _, enabled ->
                if (supported) prefs.edit().putBoolean(target.key, enabled).apply()
                displayStatus()
                publishAppearance()
            }
        }
        systemApps.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean(ApplicationGlassConfig.SYSTEM_SWITCH, enabled).apply()
            FrameworkServiceConnection.publishApplicationGlassConfig(this)
        }

        fun bindSlider(control: Slider, key: String) {
            val publish = Runnable { publishAppearance() }
            control.addOnChangeListener { _, position, fromUser ->
                if (fromUser) {
                    prefs.edit().putInt(key, position.toInt()).apply()
                    control.removeCallbacks(publish)
                    control.postDelayed(publish, 80L)
                }
                updatePreview()
            }
        }
        bindSlider(strength, GlassAppearance.STRENGTH)
        bindSlider(tint, GlassAppearance.TINT)
        updatePreview()
    }

    @StringRes
    private fun targetState(key: String, boot: Int): Int {
        if (!matchesCurrentHost(key, boot)) return R.string.glass_state_reload
        return when (statePrefs.getString("$key.state", "")) {
            "DRAWN" -> R.string.glass_state_drawn
            "UNSUPPORTED", "CRASH_GUARD" -> R.string.glass_state_error
            else -> R.string.glass_state_pending
        }
    }

    private fun matchesCurrentHost(key: String, boot: Int): Boolean {
        val version = statePrefs.getInt("$key.version", 0)
        return hostVersionMatches(key, version) && statePrefs.getInt("$key.boot", -2) == boot
    }

    private fun bindManagement(supported: Boolean) {
        requireViewById<TextView>(R.id.glass_requirements).setText(
            when {
                !supported -> R.string.glass_preview_only
                GlassSettings.pending(this) -> R.string.glass_migration_pending
                else -> R.string.glass_requirements
            },
        )
        if (supported) requireViewById<View>(R.id.glass_surface_diagnostics).apply {
            visibility = View.VISIBLE
            setOnClickListener { showSurfaceDiagnostics() }
        }
        requireViewById<View>(R.id.glass_licenses).setOnClickListener {
            startActivity(Intent(this, LicensesActivity::class.java))
        }
        bindReloadButtons()
    }

    private fun updateSystemAppScope(request: Boolean) {
        val status = scopeStatus ?: return
        val generation = ++scopeGeneration
        FrameworkServiceConnection.applicationScope(this, request) { result ->
            runOnUiThread {
                if (isDestroyed || result.state == FrameworkServiceConnection.ScopeState.COUNT &&
                    generation != scopeGeneration) return@runOnUiThread
                status.text = when (result.state) {
                    FrameworkServiceConnection.ScopeState.COUNT ->
                        getString(R.string.glass_system_app_scope_count, result.applied, result.total)
                    FrameworkServiceConnection.ScopeState.APPROVED ->
                        getString(R.string.glass_system_app_scope_approved, result.applied, result.total)
                    FrameworkServiceConnection.ScopeState.REQUESTED -> getString(R.string.glass_system_app_scope_pending)
                    FrameworkServiceConnection.ScopeState.DISCONNECTED -> getString(R.string.glass_system_app_scope_disconnected)
                    FrameworkServiceConnection.ScopeState.FAILED -> getString(R.string.glass_system_app_scope_failed)
                }
            }
        }
    }

    private fun bindReloadButtons() {
        val buttons = TargetReload.Target.entries.associateWith { requireViewById<MaterialButton>(it.button) }
        val available = buttons.keys.associateWith { target ->
            try {
                val app = packageManager.getApplicationInfo(target.packageName, PackageManager.ApplicationInfoFlags.of(0))
                app.enabled
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
        for ((target, button) in buttons) {
            button.isEnabled = available.getValue(target)
            button.setOnClickListener {
                val status = requireViewById<TextView>(R.id.glass_reload_status)
                if (TargetReload.start(target) { result ->
                        runOnUiThread {
                            if (isDestroyed) return@runOnUiThread
                            status.setText(result.message)
                            for ((item, action) in buttons) action.isEnabled = available.getValue(item)
                        }
                    }) {
                    buttons.values.forEach { it.isEnabled = false }
                    status.setText(R.string.glass_reload_working)
                }
            }
        }
    }

    private fun showSurfaceDiagnostics() {
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        val snapshot = statePrefs.all
        val rows = snapshot.keys.asSequence()
            .filter { it.endsWith(".state") }
            .mapNotNull { key ->
                val base = key.removeSuffix(".state")
                val prefix = when {
                    base.startsWith(GlassSurfaceStage.SYSTEM_STATUS_PREFIX) -> GlassSurfaceStage.SYSTEM_STATUS_PREFIX
                    base.startsWith(GlassSurfaceStage.HOME_STATUS_PREFIX) -> GlassSurfaceStage.HOME_STATUS_PREFIX
                    else -> return@mapNotNull null
                }
                if (snapshot["$base.boot"] != boot ||
                    !hostVersionMatches(base, snapshot["$base.version"] as? Int ?: 0)) return@mapNotNull null
                val stage = GlassSurfaceStage.entries.firstOrNull { it.name == snapshot[key] } ?: return@mapNotNull null
                val name = base.removePrefix(prefix)
                val label = when (name) {
                    GlassSystemCatalog.EDITOR -> getString(R.string.glass_surface_editor)
                    GlassSystemCatalog.LOCK_NOTIFICATIONS -> getString(R.string.glass_surface_lock_notifications)
                    "notification_guts" -> getString(R.string.glass_surface_notification_controls)
                    "notification_2025_conversation_info" -> getString(R.string.glass_surface_conversation)
                    "notification_2025_partial_conversation_info" -> getString(R.string.glass_surface_partial_conversation)
                    "ongoing_nowbar_view" -> getString(R.string.glass_surface_now_bar)
                    "charger_nowbar_view" -> getString(R.string.glass_surface_now_bar_charging)
                    else -> name.replace('_', ' ')
                }
                getString(R.string.glass_status_line, label, getString(stage.label))
            }.sorted().toList()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.glass_surface_diagnostics)
            .setPositiveButton(android.R.string.ok, null)
        if (rows.isEmpty()) dialog.setMessage(R.string.glass_surface_diagnostics_empty)
        else dialog.setItems(rows.toTypedArray(), null)
        dialog.show()
    }

    private companion object {
        const val STATE_PAGE = "settings_page"

        // SystemUI and Capture retain the same hooks and diagnostics since 0.4.3.
        fun hostVersionMatches(key: String, version: Int): Boolean {
            val unchanged = key.startsWith("${GlassTargets.SYSTEM_UI}.") || key.startsWith("${GlassTargets.CAPTURE}.")
            return version == BuildConfig.VERSION_CODE || (unchanged && version in 24..BuildConfig.VERSION_CODE)
        }
    }
}
