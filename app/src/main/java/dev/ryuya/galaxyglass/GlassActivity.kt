package dev.ryuya.galaxyglass

import android.content.Intent
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class GlassActivity : AppCompatActivity() {
    private var statePrefs: SharedPreferences? = null
    private var stateListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var scopeStatus: TextView? = null
    private var scopeGeneration = 0

    override fun onResume() {
        super.onResume()
        updateSystemAppScope(request = false)
    }

    override fun onDestroy() {
        stateListener?.let { statePrefs?.unregisterOnSharedPreferenceChangeListener(it) }
        super.onDestroy()
    }

    override fun onCreate(state: Bundle?) {
        AppUiTheme.apply(this)
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_glass)
        GlassSettings.migrate(this)
        bindReloadButtons()
        val root = requireViewById<View>(R.id.glass_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
        requireViewById<View>(R.id.glass_licenses).setOnClickListener {
            startActivity(Intent(this, LicensesActivity::class.java))
        }
        val prefs = getSharedPreferences(ConfigProvider.PREFS, Context.MODE_PRIVATE)
        val master = requireViewById<MaterialSwitch>(R.id.glass_enabled)
        val systemApps = requireViewById<MaterialSwitch>(R.id.glass_system_apps)
        systemApps.isChecked = prefs.getBoolean(SystemAppGlassConfig.SWITCH, true)
        scopeStatus = requireViewById(R.id.glass_system_app_scope_status)
        requireViewById<View>(R.id.glass_system_app_scope).setOnClickListener {
            updateSystemAppScope(request = true)
        }
        systemApps.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean(SystemAppGlassConfig.SWITCH, enabled).apply()
            FrameworkServiceConnection.publishSystemAppConfig(this)
        }
        val capture = requireViewById<MaterialSwitch>(R.id.glass_capture)
        master.isChecked = prefs.getBoolean("enabled", true)
        capture.isChecked = prefs.getBoolean(GlassTargets.CAPTURE_SETTING, false)
        val preview = requireViewById<GlassPreview>(R.id.glass_preview)
        val quick = requireViewById<MaterialSwitch>(R.id.glass_quick)
        val notifications = requireViewById<MaterialSwitch>(R.id.glass_notifications)
        val volume = requireViewById<MaterialSwitch>(R.id.glass_volume)
        val systemSurfaces = requireViewById<MaterialSwitch>(R.id.glass_system_surfaces)
        val lockScreen = requireViewById<MaterialSwitch>(R.id.glass_lock_screen)
        val home = requireViewById<MaterialSwitch>(R.id.glass_home)
        val slider = requireViewById<Slider>(R.id.glass_strength)
        val value = requireViewById<TextView>(R.id.glass_value)
        val tintSlider = requireViewById<Slider>(R.id.glass_tint)
        val tintValue = requireViewById<TextView>(R.id.glass_tint_value)
        val supported = GlassTargets.supportsCaptureGlass()
        requireViewById<TextView>(R.id.glass_requirements).setText(
            when {
                !supported -> R.string.glass_preview_only
                GlassSettings.pending(this) -> R.string.glass_migration_pending
                else -> R.string.glass_requirements
            },
        )
        quick.isChecked = prefs.getBoolean(GlassAppearance.QUICK, false)
        notifications.isChecked = prefs.getBoolean(GlassAppearance.NOTIFICATIONS, false)
        volume.isChecked = prefs.getBoolean(GlassAppearance.VOLUME, false)
        systemSurfaces.isChecked = prefs.getBoolean(GlassAppearance.SYSTEM_SURFACES, false)
        lockScreen.isChecked = GlassAppearance.lockScreenEnabled(prefs)
        home.isChecked = prefs.getBoolean(GlassAppearance.HOME, false)
        slider.value = prefs.getInt(GlassAppearance.STRENGTH, GlassAppearance.DEFAULT_STRENGTH).coerceIn(0, 100).toFloat()
        tintSlider.value = GlassAppearance.tintPercent(
            prefs.getInt(GlassAppearance.TINT, GlassAppearance.DEFAULT_TINT),
        ).toFloat()
        val status = requireViewById<TextView>(R.id.glass_live_status)
        for (control in listOf(quick, notifications, volume, systemSurfaces, lockScreen, home)) {
            control.isEnabled = GlassTargets.supportsSystemGlass() || !supported
        }
        statePrefs = getSharedPreferences("host_status", Context.MODE_PRIVATE)
        if (supported) requireViewById<View>(R.id.glass_surface_diagnostics).apply {
            visibility = View.VISIBLE
            setOnClickListener { showSurfaceDiagnostics() }
        }
        fun displayStatus() {
            if (!supported) return
            val currentBoot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
            val states = requireNotNull(statePrefs)
            fun state(key: String, enabled: Boolean): Int {
                if (!enabled || slider.value <= 0f) return R.string.glass_state_off
                if (!master.isChecked) return R.string.glass_state_master_off
                if (states.getInt("$key.version", 0) != BuildConfig.VERSION_CODE ||
                    states.getInt("$key.boot", -2) != currentBoot) return R.string.glass_state_reload
                return when (states.getString("$key.state", "")) {
                    "DRAWN" -> R.string.glass_state_drawn
                    "UNSUPPORTED", "CRASH_GUARD" -> R.string.glass_state_error
                    else -> R.string.glass_state_pending
                }
            }

            val rows = listOf(
                R.string.glass_quick to state("com.android.systemui.glass_quick", quick.isChecked),
                R.string.glass_notifications to state("com.android.systemui.glass_notifications", notifications.isChecked),
                R.string.glass_volume to state("com.android.systemui.glass_volume", volume.isChecked),
                R.string.glass_system_surfaces to state("com.android.systemui.glass_system_surfaces", systemSurfaces.isChecked),
                R.string.glass_lock_screen to state("com.android.systemui.glass_lock_screen", lockScreen.isChecked),
                R.string.glass_home to state("com.sec.android.app.launcher.glass_home", home.isChecked),
                R.string.glass_capture to state("${GlassTargets.CAPTURE}.${GlassTargets.CAPTURE_SETTING}", capture.isChecked),
            )
            status.visibility = View.VISIBLE
            status.text = rows.joinToString("\n") { (name, result) -> getString(R.string.glass_status_line, getString(name), getString(result)) }
        }
        if (supported) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> displayStatus() }
            stateListener = listener
            statePrefs?.registerOnSharedPreferenceChangeListener(listener)
        }
        fun display() {
            val strength = slider.value.toInt()
            val tint = GlassAppearance.tintPercent(tintSlider.value.toInt())
            preview.appearance = GlassAppearance(
                quick = quick.isChecked, notifications = notifications.isChecked, home = home.isChecked,
                strength = strength, tint = tint, volume = volume.isChecked,
                volumeTheme = prefs.getInt("volume", 0),
                systemSurfaces = systemSurfaces.isChecked,
                lockScreen = lockScreen.isChecked,
            )
            value.text = getString(R.string.qs_percent, strength)
            tintValue.text = getString(R.string.qs_percent, tint)
            displayStatus()
        }
        fun changed() {
            if (supported) contentResolver.notifyChange(ConfigProvider.URI, null)
            FrameworkServiceConnection.publishSystemAppConfig(this)
        }
        for ((control, key) in listOf(quick to GlassAppearance.QUICK,
                   notifications to GlassAppearance.NOTIFICATIONS, volume to GlassAppearance.VOLUME,
                   systemSurfaces to GlassAppearance.SYSTEM_SURFACES, lockScreen to GlassAppearance.LOCK_SCREEN,
                   home to GlassAppearance.HOME, master to "enabled", capture to GlassTargets.CAPTURE_SETTING)) {
            control.setOnCheckedChangeListener { _, enabled ->
                if (supported || control === master) prefs.edit().putBoolean(key, enabled).apply()
                display()
                changed()
            }
        }
        fun bindSlider(control: Slider, key: String) {
            val notifyHosts = Runnable { changed() }
            control.addOnChangeListener { _, position, fromUser ->
                if (fromUser) {
                    prefs.edit().putInt(key, position.toInt()).apply()
                    control.removeCallbacks(notifyHosts)
                    control.postDelayed(notifyHosts, 80L)
                }
                display()
            }
        }
        bindSlider(slider, GlassAppearance.STRENGTH)
        bindSlider(tintSlider, GlassAppearance.TINT)
        display()
    }

    private fun updateSystemAppScope(request: Boolean) {
        val status = scopeStatus ?: return
        val generation = ++scopeGeneration
        FrameworkServiceConnection.systemAppScope(this, request) { result ->
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
                }
                status.setText(R.string.glass_reload_working)
            }
        }
    }

    private fun showSurfaceDiagnostics() {
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        val snapshot = requireNotNull(statePrefs).all
        val rows = snapshot.keys.asSequence()
            .filter { it.endsWith(".state") }
            .mapNotNull { key ->
                val base = key.removeSuffix(".state")
                val prefix = when {
                    base.startsWith(GlassSurfaceStage.SYSTEM_STATUS_PREFIX) -> GlassSurfaceStage.SYSTEM_STATUS_PREFIX
                    base.startsWith(GlassSurfaceStage.HOME_STATUS_PREFIX) -> GlassSurfaceStage.HOME_STATUS_PREFIX
                    else -> return@mapNotNull null
                }
                if (snapshot["$base.boot"] != boot || snapshot["$base.version"] != BuildConfig.VERSION_CODE) return@mapNotNull null
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
}
