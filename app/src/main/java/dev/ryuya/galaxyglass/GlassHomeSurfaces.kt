package dev.ryuya.galaxyglass

import android.content.Context
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import dev.ryuya.galaxyglass.compat.DexSymbols
import java.util.concurrent.Executor

/** Adds light to native dock and popup folder backgrounds, below folder content. */
@RequiresApi(33)
internal class GlassHomeSurfaces(
    context: Context,
    loader: ClassLoader,
    main: Handler,
    worker: Executor,
    private val level: () -> Int,
    dark: () -> Boolean,
    private val material: GlassMaterial,
    private val onDrawn: () -> Unit,
    onSurface: (String, GlassSurfaceStage) -> Unit,
) {
    private val container = DexSymbols.findClass(
        "com.honeyspace.ui.honeypots.hotseat.presentation.HotseatContainer", false, loader,
    ).asSubclass(ViewGroup::class.java)
    private val dock = CompactGlassDock(context, loader, main, worker, level, dark, material, onDrawn)
    private val folderIcons = runCatching { GlassFolderIcons(loader, level, dark, material, onDrawn) }.onFailure {
        HookEntry.log("GLASS_FOLDER_ICONS_UNAVAILABLE ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()
    private val folderBackdrop = runCatching {
        GlassFolderBackdrop(loader, level, dark, material, onDrawn)
    }.onFailure {
        HookEntry.log("GLASS_FOLDER_LENS_UNAVAILABLE ${it.javaClass.simpleName}")
    }.getOrNull()
    private val controls = GlassLauncherControls(context, loader, level, dark, material, onDrawn, onSurface)

    init {
        val model = DexSymbols.findClass(
            "com.honeyspace.ui.honeypots.hotseat.viewmodel.HotseatViewModel", false, loader,
        )
        ModernHook.aroundMethod(container.getDeclaredMethod("setup", model)) { chain ->
            val result = chain.proceed()
            val view = chain.thisObject as ViewGroup
            safely { dock.watch(view) }
            result
        }
    }

    fun find(root: View) = safely { scan(root) }

    private fun scan(root: View) {
        if (isHomeWidgetBoundary(root)) return
        if (root.javaClass == container) dock.watch(root as ViewGroup)
        folderBackdrop?.find(root)
        controls.find(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) scan(root.getChildAt(index))
    }

    fun idleForHotReload() = folderIcons?.idleForHotReload() != false &&
        folderBackdrop?.idleForHotReload() != false

    fun stateForReload() = controls.stateForReload()

    fun reconnect(saved: Any?) = controls.reconnect(saved)

    fun close() {
        for (retire in listOf<() -> Unit>(dock::close, { folderBackdrop?.close() }, controls::close)) {
            runCatching(retire).onFailure { HookEntry.log("GLASS_HOME_RETIRE ${it.javaClass.simpleName}") }
        }
    }

    fun refresh() {
        dock.refresh()
        folderIcons?.refresh()
        folderBackdrop?.refresh()
        controls.refresh()
    }

    fun wallpaperChanged() = dock.wallpaperChanged()

    fun clearDockBackdrops() = dock.clearBackdrops()

    private fun safely(action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            HookEntry.log("GLASS_HOME " + error.javaClass.simpleName)
        }
    }

}

/** The workspace allocates a cell to these views, which can contain transparent areas. */
internal fun isHomeWidgetBoundary(view: View): Boolean = view is android.appwidget.AppWidgetHostView ||
    view.javaClass.name == "com.honeyspace.ui.common.widget.WidgetHostViewContainer" ||
    view.javaClass.name == "com.honeyspace.ui.honeypots.stackedwidget.presentation.StackedWidgetContainer"
