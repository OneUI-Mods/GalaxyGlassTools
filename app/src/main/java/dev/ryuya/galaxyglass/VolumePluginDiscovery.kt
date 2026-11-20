package dev.ryuya.galaxyglass

import android.content.ComponentName
import android.content.Context
import java.lang.ref.WeakReference

/** Captures the Sound Assistant class loader while SystemUI creates the plugin context. */
internal class VolumePluginDiscovery(loader: ClassLoader) {
    private var pluginContext = WeakReference<Context>(null)
    private var pluginLoader = WeakReference<ClassLoader>(null)
    private var observer: ((Context, ClassLoader) -> Unit)? = null

    init {
        val manager = "com.samsung.systemui.splugins.SPluginInstanceManager"
        val wrapper = Class.forName("$manager\$PluginContextWrapper", false, loader)
        val constructor = wrapper.getDeclaredConstructor(Context::class.java, ClassLoader::class.java)
        val handler = Class.forName("$manager\$PluginHandler", false, loader)
        ModernHook.deoptimize(handler.getDeclaredMethod("handleLoadPlugin", ComponentName::class.java))
        ModernHook.aroundMethod(constructor) { chain ->
            val result = chain.proceed()
            val context = chain.args[0] as? Context
            val plugin = chain.args[1] as? ClassLoader
            if (context?.packageName == "com.samsung.android.soundassistant" && plugin != null) {
                register(context, plugin)
            }
            result
        }
    }

    @Synchronized
    fun observe(listener: (Context, ClassLoader) -> Unit) {
        observer = listener
        val context = pluginContext.get()
        val loader = pluginLoader.get()
        if (context != null && loader != null) listener(context, loader)
    }

    @Synchronized
    private fun register(context: Context, loader: ClassLoader) {
        if (pluginLoader.get() === loader) return
        pluginContext = WeakReference(context)
        pluginLoader = WeakReference(loader)
        observer?.invoke(context, loader)
    }
}
