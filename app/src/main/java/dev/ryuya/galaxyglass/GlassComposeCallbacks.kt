package dev.ryuya.galaxyglass

import dalvik.system.PathClassLoader
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference

/** Owns only weak references to callbacks still held by a stock Compose modifier. */
internal class GlassComposeCallbacks {
    private val callbacks = ArrayList<WeakReference<Array<Any>>>()

    @Synchronized
    fun create(
        descriptor: Array<Any>,
        function: Class<*>,
        drawContent: Method,
        invoke: Method,
        unit: Any?,
        draw: InvocationHandler,
    ): Any {
        val reference = AtomicReference<InvocationHandler?>()
        reference.set(guardDraw(drawContent, unit, draw))
        val state = arrayOf<Any>(descriptor, reference)
        callbacks.removeAll { it.get() == null }
        callbacks.add(WeakReference(state))
        val handler = constructor().newInstance(state, drawContent, invoke.name, unit) as InvocationHandler
        return Proxy.newProxyInstance(function.classLoader, arrayOf(function), handler)
    }

    @Synchronized
    fun stateForReload(): Array<Array<Any>> = callbacks.mapNotNull(WeakReference<Array<Any>>::get).toTypedArray()

    @Synchronized
    fun detach() {
        for (state in stateForReload()) delegate(state)?.set(null)
        callbacks.clear()
    }

    @Synchronized
    fun reconnect(saved: Any?, drawContent: Method, unit: Any?, make: (Array<*>) -> InvocationHandler?) {
        for (item in (saved as? Array<*>).orEmpty()) {
            @Suppress("UNCHECKED_CAST")
            val state = item as? Array<Any> ?: continue
            val descriptor = state.getOrNull(0) as? Array<*> ?: continue
            val callback = make(descriptor) ?: continue
            delegate(state)?.set(guardDraw(drawContent, unit, callback))
            callbacks.add(WeakReference(state))
        }
    }

    private fun guardDraw(drawContent: Method, unit: Any?, draw: InvocationHandler): InvocationHandler =
        InvocationHandler { proxy, method, args ->
            GlassHookDispatch.drawOrStock({
                drawContent.invoke(requireNotNull(args?.firstOrNull()))
                unit
            }) { draw.invoke(proxy, method, args) }
        }

    @Suppress("UNCHECKED_CAST")
    private fun delegate(state: Array<Any>): AtomicReference<InvocationHandler?>? =
        state.getOrNull(1) as? AtomicReference<InvocationHandler?>

    companion object {
        private var apkPath: String? = null
        private var residentConstructor: Constructor<*>? = null

        fun configure(source: String) { apkPath = source }

        @Synchronized
        private fun constructor(): Constructor<*> {
            residentConstructor?.let { return it }
            val loader = PathClassLoader(requireNotNull(apkPath), ClassLoader.getSystemClassLoader())
            val type = Class.forName("dev.ryuya.galaxyglass.resident.ComposeDrawCallback", false, loader)
            require(type.classLoader === loader)
            return type.getConstructor(Array<Any>::class.java, Method::class.java, String::class.java,
                Any::class.java).also { residentConstructor = it }
        }

    }
}
