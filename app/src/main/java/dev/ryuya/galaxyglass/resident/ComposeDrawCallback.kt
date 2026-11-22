package dev.ryuya.galaxyglass.resident

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference

/**
 * Lives outside the Vector module generation. A native Compose Modifier can retain this
 * handler between updates. The only reference to module code is the replaceable delegate.
 */
class ComposeDrawCallback(
    private val state: Array<Any>,
    private val drawContent: Method,
    private val invocation: String,
    private val unit: Any?,
) : InvocationHandler {
    @Suppress("UNCHECKED_CAST")
    private val delegate = state[1] as AtomicReference<InvocationHandler?>

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? =
        when (method.name) {
            invocation -> {
                val active = delegate.get()
                if (active != null) active.invoke(proxy, method, args)
                else {
                    drawContent.invoke(requireNotNull(args?.firstOrNull()))
                    unit
                }
            }
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "Galaxy Glass Compose draw callback"
            else -> null
        }
}
