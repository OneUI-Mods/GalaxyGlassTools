package dev.ryuya.galaxyglass

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.provider.Settings

class ConfigProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val app = checkNotNull(context)
        val uid = Binder.getCallingUid()
        val host = callingPackage
        val self = uid == Process.myUid()
        val knownHost = uid / 100000 == 0 && host in GlassTargets.HOSTS &&
            app.packageManager.getPackagesForUid(uid)?.contains(host) == true
        if (!self && !knownHost) throw SecurityException("Unsupported caller")
        return when (method) {
            "config" -> {
                val token = Binder.clearCallingIdentity()
                try {
                    GlassSettings.migrate(app)
                    GlassSettings.snapshot(app)
                } finally {
                    Binder.restoreCallingIdentity(token)
                }
            }
            "report_status" -> {
                require(knownHost && host == extras?.getString("host"))
                val data = requireNotNull(extras)
                val feature = requireNotNull(data.getString("feature"))
                require(feature.matches(Regex("glass_[a-z0-9_]{1,90}")))
                val state = data.getString("state", "ERROR")
                require(state in STATUS)
                val key = "$host.$feature"
                app.getSharedPreferences("host_status", Context.MODE_PRIVATE).edit()
                    .putString("$key.state", state)
                    .putInt("$key.boot", Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1))
                    .putInt("$key.version", data.getInt("version"))
                    .apply()
                Bundle.EMPTY
            }
            else -> throw UnsupportedOperationException(method)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        val URI: Uri = Uri.parse("content://dev.ryuya.galaxyglass.config/modes")
        const val PREFS = "modes"
        private val STATUS = setOf("OFF", "READY", "DRAWN", "CRASH_GUARD", "UNSUPPORTED",
            "FOUND", "NATIVE_BLUR", "REFRACTED")
    }
}
