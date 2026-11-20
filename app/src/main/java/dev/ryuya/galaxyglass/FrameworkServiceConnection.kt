package dev.ryuya.galaxyglass

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.ryuya.galaxyglass.compat.DexSymbols
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.atomic.AtomicBoolean

/** App-side API 102 handshake and settings for the application scope. */
object FrameworkServiceConnection {
    private const val TAG = "GalaxyGlassBinder"
    private val registered = AtomicBoolean()
    @Volatile private var currentService: XposedService? = null

    enum class ScopeState { COUNT, REQUESTED, APPROVED, DISCONNECTED, FAILED }
    data class ScopeResult(val state: ScopeState, val applied: Int = 0, val total: Int = 0)
    private val worker by lazy {
        BackgroundExecutors.serial("XposedServiceMetadata", daemon = true)
    }

    fun register(context: Context) {
        DexSymbols.configure(context)
        if (!registered.compareAndSet(false, true)) return
        val app = context.applicationContext
        val applicationId = app.packageName
        XposedServiceHelper.registerListener(
            object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    currentService = service
                    // The listener can run inside Vector's provider.call. Return immediately;
                    // remote metadata reads must not delay the daemon's delivery reply.
                    worker.execute {
                        try {
                            val api = service.apiVersion
                            val framework = service.frameworkName
                            val version = service.frameworkVersionCode
                            if (currentService === service && HostStartupGate.unlocked(app)) {
                                syncApplicationGlassConfig(app, service)
                            }
                            Log.i(
                                TAG,
                                "CONNECTED $applicationId api=$api framework=$framework version=$version",
                            )
                        } catch (error: RuntimeException) {
                            Log.w(TAG, "METADATA_UNAVAILABLE $applicationId", error)
                        }
                    }
                    if (!HostStartupGate.unlocked(app) && app is Application) {
                        Handler(Looper.getMainLooper()).post {
                            HostStartupGate.whenUnlocked(app) { publishApplicationGlassConfig(app) }
                        }
                    }
                }

                override fun onServiceDied(service: XposedService) {
                    if (currentService === service) currentService = null
                    Log.w(TAG, "DISCONNECTED $applicationId")
                }
            },
        )
    }

    fun publishApplicationGlassConfig(context: Context) {
        val app = context.applicationContext
        worker.execute {
            val service = currentService ?: return@execute
            if (!HostStartupGate.unlocked(app)) return@execute
            runCatching { syncApplicationGlassConfig(app, service) }
                .onFailure { Log.w(TAG, "APP_GLASS_CONFIG_UNAVAILABLE", it) }
        }
    }

    private fun syncApplicationGlassConfig(context: Context, service: XposedService) {
        val local = context.getSharedPreferences(ConfigProvider.PREFS, Context.MODE_PRIVATE)
        val remote = service.getRemotePreferences(ApplicationGlassConfig.REMOTE)
        check(ApplicationGlassConfig.publish(local, remote)) { "Application appearance settings were not saved" }
        Log.i(TAG, "APP_GLASS_CONFIG_SYNCED")
    }

    fun applicationScope(context: Context, request: Boolean, result: (ScopeResult) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val service = currentService
            if (service == null) {
                result(ScopeResult(ScopeState.DISCONNECTED))
                return@execute
            }
            runCatching {
                val packages = ApplicationGlassTargets.packages(app.packageManager)
                val scoped = service.scope.toSet()
                val missing = packages.filterNot(scoped::contains)
                val already = packages.size - missing.size
                if (!request || missing.isEmpty()) {
                    result(ScopeResult(ScopeState.COUNT, already, packages.size))
                    return@runCatching
                }
                syncApplicationGlassConfig(app, service)
                result(ScopeResult(ScopeState.REQUESTED, already, packages.size))
                service.requestScope(missing, object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(approved: MutableList<String>) {
                        worker.execute {
                            val active = runCatching { service.scope.toSet() }.getOrDefault(scoped + approved)
                            result(ScopeResult(ScopeState.APPROVED, packages.count(active::contains), packages.size))
                        }
                    }

                    override fun onScopeRequestFailed(message: String) {
                        Log.i(TAG, "APP_GLASS_SCOPE_NOT_APPROVED")
                        result(ScopeResult(ScopeState.FAILED, already, packages.size))
                    }
                })
            }.onFailure {
                Log.w(TAG, "APP_GLASS_SCOPE_UNAVAILABLE", it)
                result(ScopeResult(ScopeState.FAILED))
            }
        }
    }
}
