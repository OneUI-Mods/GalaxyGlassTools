package dev.ryuya.galaxyglass

import android.graphics.Canvas
import android.view.View
import java.lang.reflect.Method
import java.lang.reflect.Modifier as JvmModifier
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/** Applies glass to identified Material 2 and 3 chrome and floating panels. */
internal class ApplicationComposeChrome(
    loader: ClassLoader,
    installed: MutableSet<Method>,
    private val strength: (View) -> Int,
    private val paint: (View, Canvas, Float, Float, Surface, Int) -> Unit,
) {
    enum class Surface(val radius: Int, val panel: Boolean = false) {
        BAR(0), SEARCH(24), MENU(12, true), SHEET(24, true), DRAWER(20, true),
    }

    private val nodes = WeakHashMap<Any, Unit>()
    private val invalidate: Method

    init {
        fun type(name: String) = Class.forName(name, false, loader)
        val modifier = type("androidx.compose.ui.Modifier")
        val composer = type("androidx.compose.runtime.Composer")
        val empty = requireNotNull(modifier.getField("Companion").get(null))
        val then = modifier.getMethod("then", modifier)
        val material2Sheet = "androidx.compose.material.ModalBottomSheetKt"
        val legacyEntries = buildMap {
            fun add(owner: String, name: String, surface: Surface) {
                val methods = runCatching { type(owner).declaredMethods }.getOrNull() ?: return
                for (method in methods) {
                    if (method.name.substringBefore('-') == name && composer in method.parameterTypes) {
                        put(method, surface)
                    }
                }
            }
            add(material2Sheet, "ModalBottomSheetLayout", Surface.SHEET)
            // Both Drawer functions subcompose their Surface inside BoxWithConstraints.
            for (name in listOf("ModalDrawer", "BottomDrawer")) {
                add("androidx.compose.material.DrawerKt\$$name\$1", "invoke", Surface.DRAWER)
            }
        }
        val composingLegacy = ThreadLocal<Method>()
        val renderers = listOf(
            "material3.AppBarKt" to setOf("SingleRowTopAppBar", "TwoRowsTopAppBar", "BottomAppBar"),
            "material3.NavigationBarKt" to setOf("NavigationBar", "ShortNavigationBar"),
            "material3.NavigationRailKt" to setOf("NavigationRail"),
            "material3.SearchBarKt" to setOf("SearchBar", "DockedSearchBar"),
            "material3.TabRowKt" to setOf("PrimaryTabRow", "SecondaryTabRow", "TabRow", "ScrollableTabRow"),
            "material3.ModalBottomSheetKt" to setOf("ModalBottomSheet"),
            "material3.ModalBottomSheet_androidKt" to setOf("ModalBottomSheet"),
            "material3.BottomSheetScaffoldKt" to setOf("StandardBottomSheet"),
            "material3.NavigationDrawerKt" to setOf("ModalDrawerSheet", "DismissibleDrawerSheet", "PermanentDrawerSheet"),
            "material3.MenuKt" to setOf("DropdownMenuContent", "DropdownMenuPopupContent"),
            "material.AppBarKt" to setOf("TopAppBar", "BottomAppBar"),
            "material.BottomNavigationKt" to setOf("BottomNavigation"),
            "material.NavigationRailKt" to setOf("NavigationRail"),
            "material.TabRowKt" to setOf("TabRow", "ScrollableTabRow"),
            "material.MenuKt" to setOf("DropdownMenuContent"),
            "material.SurfaceKt" to if (legacyEntries.isEmpty()) emptySet() else setOf("Surface"),
        ).flatMap { (owner, names) ->
            if (names.isEmpty()) return@flatMap emptyList()
            val declarations = runCatching { type("androidx.compose.$owner").declaredMethods }.getOrNull()
                ?: return@flatMap emptyList()
            declarations.mapNotNull { method ->
                val name = method.name.substringBefore('-').substringBefore('$')
                if (method in installed || !JvmModifier.isStatic(method.modifiers) || name !in names) return@mapNotNull null
                val types = method.parameterTypes
                val legacyPanel = name == "Surface"
                if (legacyPanel && (types.getOrNull(0) != modifier ||
                    types.getOrNull(1)?.name != "androidx.compose.ui.graphics.Shape")) return@mapNotNull null
                val index = types.indexOf(modifier)
                val composerIndex = types.indexOf(composer)
                if (index !in 0..29 || composerIndex <= index) return@mapNotNull null
                val masks = (composerIndex + 9) / 10
                val tail = types.drop(composerIndex + 1)
                if (tail.size !in masks..(masks + 1) || tail.any { it != Int::class.javaPrimitiveType }) {
                    return@mapNotNull null
                }
                val surface = when {
                    legacyPanel || name.contains("BottomSheet") -> Surface.SHEET
                    name.endsWith("DrawerSheet") -> Surface.DRAWER
                    name.startsWith("DropdownMenu") -> Surface.MENU
                    name.contains("SearchBar") -> Surface.SEARCH
                    else -> Surface.BAR
                }
                Renderer(method, index, composerIndex + 1 + index / 10, types.lastIndex.takeIf { tail.size > masks },
                    surface, legacyPanel)
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

        val overlays = mutableMapOf<Surface, Any>()
        fun overlayFor(surface: Surface): Any = overlays.getOrPut(surface) {
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
                                paint(view, target, width, height, surface, level)
                            }
                        }
                        unit
                    }
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "Application glass chrome"
                    else -> null
                }
            }
            requireNotNull(draw.invoke(null, empty, callback))
        }
        val composing = ThreadLocal<MutableSet<Surface>>()
        for (renderer in renderers) {
            runCatching {
                val overlay = overlayFor(renderer.surface)
                if (renderer.legacyPanel) legacyEntries.values.forEach { overlayFor(it) }
                ModernHook.deoptimize(renderer.method)
                ModernHook.aroundMethod(renderer.method) { chain ->
                    var surface = renderer.surface
                    if (renderer.legacyPanel) {
                        val source = composingLegacy.get() ?: return@aroundMethod chain.proceed()
                        // Style the native panel call. Surfaces composed by application content
                        // within the same layout retain their own appearance.
                        val caller = Throwable().stackTrace.firstOrNull {
                            val owner = it.className
                            owner != renderer.method.declaringClass.name &&
                                !owner.startsWith("dev.ryuya.galaxyglass.") && !owner.startsWith("io.github.libxposed.") &&
                                !owner.startsWith("org.lsposed.") && !owner.startsWith("java.lang.reflect.")
                        }
                        if (caller == null || caller.className != source.declaringClass.name ||
                            caller.methodName != source.name) {
                            return@aroundMethod chain.proceed()
                        }
                        surface = legacyEntries.getValue(source)
                    }
                    val active = composing.get()
                    if (active?.contains(surface) == true) return@aroundMethod chain.proceed()
                    val args = chain.args.toTypedArray()
                    val default = renderer.defaultIndex
                    val defaultBit = 1 shl renderer.modifierIndex
                    val stock = if (default != null && (args[default] as Int) and defaultBit != 0) empty
                        else args[renderer.modifierIndex]
                    if (!modifier.isInstance(stock)) return@aroundMethod chain.proceed()
                    if (default != null) args[default] = (args[default] as Int) and defaultBit.inv()
                    val changeMask = 0b111 shl (1 + renderer.modifierIndex % 10 * 3)
                    args[renderer.changedIndex] = (args[renderer.changedIndex] as Int) and changeMask.inv()
                    args[renderer.modifierIndex] = then.invoke(stock, if (renderer.legacyPanel) overlays.getValue(surface) else overlay)
                    val surfaces = active ?: mutableSetOf<Surface>().also(composing::set)
                    surfaces.add(surface)
                    try {
                        chain.proceed(args)
                    } finally {
                        surfaces.remove(surface)
                        if (surfaces.isEmpty()) composing.remove()
                    }
                }
            }.onSuccess { installed.add(renderer.method) }
                .onFailure { HookEntry.log("GLASS_APP_COMPOSE_METHOD_UNAVAILABLE ${renderer.method.name}") }
        }
        if (renderers.any { it.legacyPanel && it.method in installed }) {
            for (method in legacyEntries.keys.filterNot(installed::contains)) runCatching {
                ModernHook.aroundMethod(method) { chain ->
                    val previous = composingLegacy.get()
                    composingLegacy.set(method)
                    try {
                        chain.proceed()
                    } finally {
                        if (previous == null) composingLegacy.remove() else composingLegacy.set(previous)
                    }
                }
            }.onSuccess { installed.add(method) }
                .onFailure { HookEntry.log("GLASS_APP_COMPOSE_METHOD_UNAVAILABLE ${method.name}") }
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
        val method: Method, val modifierIndex: Int, val changedIndex: Int, val defaultIndex: Int?, val surface: Surface,
        val legacyPanel: Boolean,
    )
}
