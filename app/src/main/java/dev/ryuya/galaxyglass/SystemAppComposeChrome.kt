package dev.ryuya.galaxyglass

import android.graphics.Canvas
import android.view.View
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JvmModifier
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/** Hooks complete Material bars. Item composables, app content and gestures stay with their owner. */
internal class SystemAppComposeChrome(
    loader: ClassLoader,
    private val strength: (View) -> Int,
    private val paint: (View, Canvas, Float, Float, Int, Int) -> Unit,
) {
    private val nodes = WeakHashMap<Any, Unit>()
    private val invalidate: Method

    init {
        fun type(name: String) = Class.forName(name, false, loader)
        val modifier = type("androidx.compose.ui.Modifier")
        val composer = type("androidx.compose.runtime.Composer")
        val empty = requireNotNull(modifier.getField("Companion").get(null))
        val then = modifier.getMethod("then", modifier)
        val renderers = listOf(
            "AppBarKt" to setOf("SingleRowTopAppBar", "TwoRowsTopAppBar", "BottomAppBar"),
            "NavigationBarKt" to setOf("NavigationBar", "ShortNavigationBar"),
            "NavigationRailKt" to setOf("NavigationRail"),
            "SearchBarKt" to setOf("SearchBar", "DockedSearchBar"),
        ).flatMap { (owner, names) ->
            val declarations = runCatching { type("androidx.compose.material3.$owner").declaredMethods }.getOrNull()
                ?: return@flatMap emptyList()
            declarations.mapNotNull { method ->
                val name = method.name.substringBefore('-')
                if (!JvmModifier.isStatic(method.modifiers) || name !in names) return@mapNotNull null
                val types = method.parameterTypes
                val index = types.indexOf(modifier)
                val composerIndex = types.indexOf(composer)
                if (index !in 0..29 || composerIndex <= index) return@mapNotNull null
                val masks = (composerIndex + 9) / 10
                val tail = types.drop(composerIndex + 1)
                if (tail.size !in masks..(masks + 1) || tail.any { it != Int::class.javaPrimitiveType }) {
                    return@mapNotNull null
                }
                Renderer(method, index, composerIndex + 1 + index / 10,
                    types.lastIndex.takeIf { tail.size > masks }, if (name.contains("SearchBar")) 24 else 0)
            }
        }
        require(renderers.isNotEmpty())

        val draw = type("androidx.compose.ui.draw.DrawModifierKt").declaredMethods.single {
            it.name == "drawWithContent" && it.parameterCount == 2 && it.parameterTypes[0] == modifier
        }
        val function = draw.parameterTypes[1]
        val invoke = function.methods.single { JvmModifier.isAbstract(it.modifiers) && it.parameterCount == 1 }
        val scope = type("androidx.compose.ui.node.LayoutNodeDrawScope")
        val node = scope.getDeclaredField("drawNode").apply { isAccessible = true }
        val owner = type("androidx.compose.ui.node.DelegatableNodeKt").declaredMethods.single {
            it.name == "requireOwner" && it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(node.type)
        }
        val drawContent = scope.getMethod("drawContent")
        val size = scope.getMethod("getSize-NH-jbRc")
        val context = scope.getMethod("getDrawContext")
        val canvas = context.returnType.getMethod("getCanvas")
        val nativeCanvas = type("androidx.compose.ui.graphics.AndroidCanvas_androidKt")
            .getMethod("getNativeCanvas", canvas.returnType)
        invalidate = type("androidx.compose.ui.node.DrawModifierNodeKt").getMethod("invalidateDraw", node.type)
        val unit = runCatching { type("kotlin.Unit").getField("INSTANCE").get(null) }.getOrNull()

        val overlays = mutableMapOf<Int, Any>()
        for (renderer in renderers) {
            runCatching {
                val overlay = overlays.getOrPut(renderer.radius) {
                    val callback = Proxy.newProxyInstance(function.classLoader, arrayOf(function)) { proxy, called, args ->
                        when (called.name) {
                            invoke.name -> {
                                val native = requireNotNull(args?.firstOrNull())
                                drawContent.invoke(native)
                                runCatching {
                                    val current = node.get(native) ?: return@runCatching
                                    nodes[current] = Unit
                                    val view = owner.invoke(null, current) as? View ?: return@runCatching
                                    val level = strength(view)
                                    if (level <= 0) return@runCatching
                                    val target = nativeCanvas.invoke(null, canvas.invoke(context.invoke(native))) as Canvas
                                    if (!target.isHardwareAccelerated) return@runCatching
                                    val packed = size.invoke(native) as Long
                                    val width = Float.fromBits((packed ushr 32).toInt())
                                    val height = Float.fromBits(packed.toInt())
                                    if (width.isFinite() && height.isFinite() && width > 0 && height > 0) {
                                        paint(view, target, width, height, renderer.radius, level)
                                    }
                                }
                                unit
                            }
                            "equals" -> proxy === args?.firstOrNull()
                            "hashCode" -> System.identityHashCode(proxy)
                            "toString" -> "System app glass chrome"
                            else -> null
                        }
                    }
                    requireNotNull(draw.invoke(null, empty, callback))
                }
                ModernHook.deoptimize(renderer.method)
                ModernHook.aroundMethod(renderer.method) { chain ->
                    val args = chain.args.toTypedArray()
                    val default = renderer.defaultIndex
                    val defaultBit = 1 shl renderer.modifierIndex
                    val stock = if (default != null && (args[default] as Int) and defaultBit != 0) empty
                        else args[renderer.modifierIndex]
                    if (!modifier.isInstance(stock)) return@aroundMethod chain.proceed()
                    if (default != null) args[default] = (args[default] as Int) and defaultBit.inv()
                    val changeMask = 0b111 shl (1 + renderer.modifierIndex % 10 * 3)
                    args[renderer.changedIndex] = (args[renderer.changedIndex] as Int) and changeMask.inv()
                    args[renderer.modifierIndex] = then.invoke(stock, overlay)
                    chain.proceed(args)
                }
            }.onFailure { HookEntry.log("GLASS_SYSTEM_APP_COMPOSE_METHOD_UNAVAILABLE ${renderer.method.name}") }
        }
    }

    fun refresh() {
        val iterator = nodes.keys.iterator()
        while (iterator.hasNext()) {
            try {
                invalidate.invoke(null, iterator.next())
            } catch (_: ReflectiveOperationException) {
                iterator.remove()
            }
        }
    }

    private data class Renderer(
        val method: Method, val modifierIndex: Int, val changedIndex: Int, val defaultIndex: Int?, val radius: Int,
    )
}
