package dev.ryuya.galaxyglass

import android.app.KeyguardManager
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.ShapeDrawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.view.OneShotPreDrawListener
import dev.ryuya.galaxyglass.compat.DexSymbols
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Styles launcher-owned controls while leaving their native layout, actions and animation attached. */
@RequiresApi(33)
internal class GlassLauncherControls(
    context: Context,
    loader: ClassLoader,
    private val level: () -> Int,
    private val dark: () -> Boolean,
    private val material: GlassMaterial,
    private val onDrawn: () -> Unit,
    private val onSurface: (String, GlassSurfaceStage) -> Unit,
) {
    private enum class Role(val tag: String) {
        MENU("quick_options"), ARROW("quick_options_arrow"), MENU_TITLE("quick_options_title"),
        TABS("drawer_profile_tabs"), SEARCH("drawer_search"),
        MORE("drawer_more"), FINDER_CARD("finder_card"), FINDER_CHIP("finder_chip"),
        FINDER_INPUT("finder_search"), DRAWER_POPUP("drawer_overflow"), FINDER_POPUP("finder_overflow"),
        DRAWER_SORT("drawer_sort"), RECENTS_OVERFLOW("recents_overflow"), RECENTS_TASK_MENU("recents_task_menu"),
        MULTI_SELECT_MENU("multi_select_menu"), WIDGET_PICKER("widget_picker"), WIDGET_SEARCH("widget_search"),
        WIDGET_STACK_EDITOR("widget_stack_editor"), FOLDER_STYLE("folder_style"), TASKBAR_TIPS("taskbar_tips"),
        TASKBAR_OVERFLOW("taskbar_overflow"), HOME_CONTEXT_MENU("home_context_menu"),
        DEX_CONTEXT_MENU("dex_context_menu"), DEX_WELLBEING("dex_wellbeing"), TASKBAR("taskbar"),
        OVERLAY_SEARCH("overlay_apps_search"), HOME_FINDER("home_finder"), HOME_LAYOUT_TIP("home_layout_tip"),
        MULTI_SELECT_ACTION("multi_select_action"), WIDGET_STACK_ADD("widget_stack_add"), LARGE_FOLDER("large_folder"),
        PAGE_EDIT("home_page_edit"), PICKER_SELECTION("folder_picker_selection"), DRAG_GUIDE("drawer_drag_guide"),
    }

    private data class PlateAnchor(val viewId: Int, val role: Role, val parent: Boolean = false)

    private val views = WeakHashMap<View, Binding>()
    private val reported = mutableSetOf<Role>()
    private val main = Handler(Looper.getMainLooper())
    private val launcher = context.packageName
    private val resources = context.resources
    private val optionId = id("quickoption_container")
    private val optionTitleEditId = id("quickoption_title_edit_text")
    private val tabsId = id("workspace_tab_layout")
    private val searchId = id("app_search_wrapper")
    private val searchBackgroundId = id("backgroundContainer")
    private val drawerId = id("apps_screen")
    private val moreId = id("more_icon_button")
    private val moreContainerId = id("more_button_container")
    private val finderInputId = id("input_area")
    private val finderBackgroundId = id("search_background")
    private val sortPlateId = id("inner_blur_view")
    private val persistentPlates = mapOf(
        id("finderContainer") to Role.HOME_FINDER,
        id("task_bar_bg") to Role.TASKBAR,
        id("search_bar_background_active") to Role.OVERLAY_SEARCH,
        id("search_bar_background_deactive") to Role.OVERLAY_SEARCH,
    ).filterKeys { it != 0 }
    private val nativePlates = buildMap<Int, MutableList<PlateAnchor>> {
        fun add(layout: String, view: String?, role: Role, parent: Boolean = false) {
            val layoutId = resources.getIdentifier(layout, "layout", launcher)
            val viewId = view?.let(::id) ?: View.NO_ID
            if (layoutId != 0 && viewId != 0) {
                getOrPut(layoutId, ::mutableListOf).add(PlateAnchor(viewId, role, parent))
            }
        }
        add("quickoption_container", "quickoption_container", Role.MENU)
        add("task_menu_layout", "content", Role.RECENTS_TASK_MENU)
        add("page_edit_panel", "page_edit_panel", Role.PAGE_EDIT)
        add("drag_guide_top_panel", "drag_guide_top_container", Role.DRAG_GUIDE)
        add("drag_guide_bottom_panel", "drag_guide_bottom_container", Role.DRAG_GUIDE)
        add("selected_header_view", null, Role.PICKER_SELECTION)
        add("widget_list_view", "list_recycler_view", Role.WIDGET_PICKER)
        add("widget_list_view_tablet", "widget_container", Role.WIDGET_PICKER)
        add("widget_search_bar", "search_edit", Role.WIDGET_SEARCH, parent = true)
        add("stacked_widget_edit_container", "stacked_widget_edit_view_pager", Role.WIDGET_STACK_EDITOR)
        add("folder_setting_container", "folder_color_setting_container", Role.FOLDER_STYLE)
        add("taskbar_tips", "tips_container", Role.TASKBAR_TIPS)
        add("more_task_pot_main", "recycler_view_container", Role.TASKBAR_OVERFLOW)
        add("layout_taskbar_pot_view", "task_bar_bg", Role.TASKBAR)
        add("dex_digital_well_being_layout", "banner", Role.DEX_WELLBEING)
        add("home_layout_adjusted_tip_layout", "tip_container", Role.HOME_LAYOUT_TIP)
        add("indicator_search_layout", "finderContainer", Role.HOME_FINDER)
        add("overlay_apps_search_bar", "search_bar_background_active", Role.OVERLAY_SEARCH)
        add("overlay_apps_search_bar", "search_bar_background_deactive", Role.OVERLAY_SEARCH)
        add("overlay_apps_search_bar_land_icon", "overlay_apps_search_icon", Role.OVERLAY_SEARCH, parent = true)
        add("overlay_apps_sip_search_bar", "app_sip_search_edit_text", Role.OVERLAY_SEARCH, parent = true)
        add("folder_setting_title", "title", Role.FOLDER_STYLE)
        add("folder_setting_container", "preview_folder_layout", Role.FOLDER_STYLE)
        add("stacked_widget_edit_plus_page", "stacked_widget_add_button", Role.WIDGET_STACK_ADD)
        for (button in listOf("uninstall", "remove_shortcut", "create_folder", "cancel")) {
            add("multi_select_panel", "multi_select_${button}_button", Role.MULTI_SELECT_ACTION)
        }
    }
    private val menuRadiusId = resources.getIdentifier("quick_option_round_corner_radius", "dimen", launcher)
    private val largeFolderRadiusId = resources.getIdentifier("enforced_rounded_corner_max_radius", "dimen", launcher)
    private val nativeCrop = runCatching { NativeCrop() }.getOrNull()
    private var findLargeFolder: ((View) -> Unit)? = null
    private var tabIndicator: Class<out View>? = null
    private var reportedError = false
    private var reportedRefraction = false
    private var tips: GlassSamsungTips? = null

    init {
        install("quick_options") { hookQuickOptions(loader) }
        install("quick_options_folder_title") { hookQuickOptionFolderTitle(loader) }
        install("quick_options_arrow") { hookQuickOptionArrow(loader) }
        install("drawer_profile_indicator") { hookTabIndicator(loader) }
        install("launcher_plates") { hookLauncherPlates() }
        install("drawer_search_binding") { hookDrawerSearchBinding(loader) }
        install("finder_plates") { hookFinderPlates(loader) }
        install("overflow_menus") { hookOverflowMenus(loader) }
        install("recents_task_menu") { hookRecentsTaskMenu(loader) }
        install("large_folder") { hookLargeFolder(loader) }
        install(SAMSUNG_TOOLTIP) {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            tips = GlassSamsungTips(level, allowsSource = { keyguard?.isKeyguardLocked == false }) {
                onSurface(SAMSUNG_TOOLTIP, GlassSurfaceStage.DRAWN)
                onDrawn()
            }
        }
    }

    private fun id(name: String) = resources.getIdentifier(name, "id", launcher)

    private fun install(name: String, add: () -> Unit) {
        runCatching(add).onFailure { HookEntry.log("GLASS_HOME_CONTROL_UNAVAILABLE $name ${it.javaClass.simpleName}") }
    }

    private fun hookQuickOptions(loader: ClassLoader) {
        val popup = DexSymbols.findClass("com.honeyspace.ui.common.quickoption.QuickOptionPopup", false, loader)
            .asSubclass(ViewGroup::class.java)
        val container = popup.getDeclaredField("quickOptionContainer").apply { isAccessible = true }
        val title = popup.getDeclaredField("titleView").apply { isAccessible = true }
        // Both the in-place menu and the panel window finalise their outline here.
        val finish = popup.getDeclaredMethod("adjustCornerRadius")
        ModernHook.deoptimize(finish)
        ModernHook.aroundMethod(finish) { chain ->
            val result = chain.proceed()
            safely {
                val menu = container.get(chain.thisObject) as? ViewGroup ?: return@safely
                refresh(menu, Role.MENU)
                val header = title.get(chain.thisObject) as? View
                if (header?.parent === menu) refresh(header, Role.MENU_TITLE)
            }
            result
        }
    }

    private fun hookQuickOptionArrow(loader: ClassLoader) {
        val popup = DexSymbols.findClass("com.honeyspace.ui.common.quickoption.QuickOptionPopup", false, loader)
        val arrow = popup.getDeclaredField("arrow").apply { isAccessible = true }
        ModernHook.aroundMethod(popup.getDeclaredMethod("addArrow")) { chain ->
            val result = chain.proceed()
            safely { (arrow.get(chain.thisObject) as? View)?.let { watch(it, Role.ARROW) } }
            result
        }
    }

    private fun hookQuickOptionFolderTitle(loader: ClassLoader) {
        if (optionTitleEditId == 0) return
        val popup = DexSymbols.findClass("com.honeyspace.ui.common.quickoption.QuickOptionPopup", false, loader)
        val title = popup.getDeclaredField("titleView").apply { isAccessible = true }
        val anchor = popup.getMethod("getOriginalItemInfo")
        val isFolder = anchor.returnType.getMethod("isFolderItem")
        val setup = popup.getDeclaredMethod("setupTitleView", String::class.java,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        ModernHook.deoptimize(setup)
        ModernHook.aroundMethod(setup) { chain ->
            val result = chain.proceed()
            safely {
                if (level() <= 0 || chain.args[2] == true) return@safely
                val item = anchor.invoke(chain.thisObject) ?: return@safely
                if (isFolder.invoke(item) != true) return@safely
                val header = title.get(chain.thisObject) as? LinearLayout ?: return@safely
                val label = header.findViewById<TextView>(optionTitleEditId) ?: return@safely
                if (label.parent !== header) return@safely
                giveFolderTitleMoreRoom(header, label)
            }
            result
        }
    }

    private fun giveFolderTitleMoreRoom(header: LinearLayout, label: TextView) {
        val labelParams = label.layoutParams as? LinearLayout.LayoutParams ?: return
        val headerWidth = header.layoutParams?.width ?: return
        if (labelParams.width <= 0 || labelParams.weight != 0f || header.weightSum > 0f) return
        var available = headerWidth - header.paddingStart - header.paddingEnd -
            labelParams.marginStart - labelParams.marginEnd
        for (index in 0 until header.childCount) {
            val sibling = header.getChildAt(index)
            // Invisible edit controls also take space in the native horizontal layout.
            if (sibling === label || sibling.visibility == View.GONE) continue
            val params = sibling.layoutParams as? LinearLayout.LayoutParams ?: return
            if (params.width < 0 || params.weight != 0f) return
            available -= params.width + params.marginStart + params.marginEnd
        }
        val gap = (8 * header.resources.displayMetrics.density).roundToInt()
        if (available - gap > labelParams.width) {
            // Let the title and the rename field use the space left by the current buttons.
            labelParams.width = 0
            labelParams.weight = 1f
            labelParams.marginEnd += gap
            label.layoutParams = labelParams
        }
    }

    private fun hookRecentsTaskMenu(loader: ClassLoader) {
        val owner = DexSymbols.findClass("ig.m", false, loader).asSubclass(PopupWindow::class.java)
        val target = DexSymbols.findClass("ig.l", false, loader)
        val show = owner.getDeclaredMethod("b", View::class.java, target)
        val plateId = id("content")
        check(plateId != 0)
        ModernHook.aroundMethod(show) { chain ->
            val result = chain.proceed()
            safely {
                val menu = chain.thisObject as PopupWindow
                if (menu.isShowing) menu.contentView.findViewById<View>(plateId)?.let {
                    refresh(it, Role.RECENTS_TASK_MENU)
                }
            }
            result
        }
    }

    private fun hookLargeFolder(loader: ClassLoader) {
        val folder = DexSymbols.findClass(
            "com.honeyspace.ui.honeypots.folder.presentation.large.LargeFolderContainer", false, loader,
        ).asSubclass(View::class.java)
        val controller = folder.getMethod("getBackgroundController")
        val backgrounds = controller.returnType.getMethod("getAllowBackgroundView")
        val model = DexSymbols.findClass("vb.j0", false, loader)
        val observer = DexSymbols.findClass("com.honeyspace.common.interfaces.BlurObserverManager", false, loader)
        val style = DexSymbols.findClass("com.honeyspace.sdk.source.entity.SpannableStyle", false, loader)
        val setup = folder.getDeclaredMethod("u", model, observer, style, java.lang.Integer.TYPE)
        val find: (View) -> Unit = { view ->
            if (folder.isInstance(view)) safely {
                val owner = controller.invoke(view) ?: return@safely
                val layers = backgrounds.invoke(owner) as? List<*> ?: return@safely
                (layers.firstOrNull() as? View)?.let { watch(it, Role.LARGE_FOLDER) }
            }
        }
        ModernHook.aroundMethod(setup) { chain ->
            val result = chain.proceed()
            find(chain.thisObject as View)
            result
        }
        findLargeFolder = find
    }

    private fun hookLauncherPlates() {
        val sortLayout = resources.getIdentifier("sort_popup", "layout", launcher)
        val layouts = setOf("vertical_apps_tab_layout", "applist_tab_layout", "app_search_wrapper", "appscreen",
            "input_layout", "sort_popup")
            .map { resources.getIdentifier(it, "layout", launcher) }.filter { it != 0 }.toSet()
        check(layouts.isNotEmpty())
        val inflate = LayoutInflater::class.java.getMethod(
            "inflate", Int::class.javaPrimitiveType, ViewGroup::class.java, Boolean::class.javaPrimitiveType,
        )
        ModernHook.aroundMethod(inflate) { chain ->
            val layout = chain.args[0] as Int
            val plates = nativePlates[layout]
            if (plates == null && layout !in layouts) return@aroundMethod chain.proceed()
            val parent = chain.args[1] as? ViewGroup
            val firstChild = parent?.childCount ?: 0
            val result = chain.proceed()
            safely {
                fun inspect(view: View) {
                    when {
                        plates != null -> {
                            for (plate in plates) {
                                val anchor = if (plate.viewId == View.NO_ID) view else view.findViewById<View>(plate.viewId)
                                val target = if (plate.parent) anchor?.parent as? View else anchor
                                if (target != null) watch(target, plate.role)
                            }
                        }
                        layout == sortLayout && sortPlateId != 0 ->
                            view.findViewById<View>(sortPlateId)?.let { watch(it, Role.DRAWER_SORT) }
                        else -> findDrawerControl(view)
                    }
                }
                if (parent != null && result === parent) {
                    for (index in firstChild until parent.childCount) inspect(parent.getChildAt(index))
                } else if (result is View) inspect(result)
            }
            result
        }
    }

    private fun hookDrawerSearchBinding(loader: ClassLoader) {
        val controller = DexSymbols.findClass("s8.n", false, loader)
        val binding = controller.getDeclaredField("e").apply { isAccessible = true }
        val background = binding.type.getDeclaredField("g").apply { isAccessible = true }
        check(View::class.java.isAssignableFrom(background.type))
        val bindBackground = controller.getDeclaredMethod("a", Boolean::class.javaPrimitiveType)
        ModernHook.aroundMethod(bindBackground) { chain ->
            val result = chain.proceed()
            safely {
                val target = binding.get(chain.thisObject)?.let { background.get(it) as? View }
                if (target != null) refresh(target, Role.SEARCH)
            }
            result
        }
    }

    private fun hookFinderPlates(loader: ClassLoader) {
        // Finder's card binders fetch these themed drawables and assign them to a View.
        // Track the exact returned instances, so no other background is affected.
        val plateResources = buildMap {
            for ((name, role) in listOf("search_card_background" to Role.FINDER_CARD,
                "search_card_chip_background" to Role.FINDER_CHIP)) {
                for (suffix in listOf("", "_light", "_medium", "_dark")) {
                    val id = resources.getIdentifier(name + suffix, "drawable", launcher)
                    if (id != 0) put(id, role)
                }
            }
        }
        check(plateResources.isNotEmpty())
        val pending = WeakHashMap<Drawable, Role>()
        val main = Looper.getMainLooper()
        val load = Resources::class.java.getMethod("getDrawable", Int::class.javaPrimitiveType, Resources.Theme::class.java)
        val assign = View::class.java.getMethod("setBackground", Drawable::class.java)
        for (owner in listOf("j7.j0", "j7.m", "j7.q", "j7.w", "k7.a")) {
            runCatching {
                val type = DexSymbols.findClass(owner, false, loader)
                val method = if (owner == "k7.a") "z" else "r"
                type.declaredMethods.filter { it.name == method }.forEach(ModernHook::deoptimize)
            }
        }
        val capture = ModernHook.aroundMethod(load) { chain ->
            val result = chain.proceed()
            val role = plateResources[chain.args[0] as Int]
            if (role != null && main.isCurrentThread && result is Drawable) pending[result] = role
            result
        }
        try {
            ModernHook.aroundMethod(assign) { chain ->
                val result = chain.proceed()
                val stock = chain.args[0] as? Drawable
                if (stock != null && main.isCurrentThread) {
                    val view = chain.thisObject as View
                    val nativeInput = finderBackgroundId != 0 && view.id == finderBackgroundId &&
                        (stock is ColorDrawable || stock is GradientDrawable || stock is InsetDrawable)
                    val role = if (nativeInput) Role.FINDER_INPUT else pending[stock]
                    if (role != null) safely { refresh(view, role) }
                }
                result
            }
        } catch (error: Throwable) {
            capture.unhook()
            throw error
        }
    }

    private fun hookOverflowMenus(loader: ClassLoader) {
        val popup = DexSymbols.findClass("androidx.appcompat.widget.PopupMenu", false, loader)
        val show = popup.getMethod("show")
        val menuList = popup.getMethod("getMenuListView")
        val showing = ThreadLocal<Role>()
        val showHook = ModernHook.aroundMethod(show) { chain ->
            val result = chain.proceed()
            val role = showing.get()
            if (role != null) safely {
                val list = menuList.invoke(chain.thisObject) as? View ?: return@safely
                // PopupWindow draws its stock plate in a parent of the menu ListView.
                val plate = generateSequence(list.parent as? View) { it.parent as? View }
                    .firstOrNull { it.background != null }
                watch(plate ?: list, role)
            }
            result
        }
        var installed = false
        fun bind(role: Role, owner: String, name: String, vararg parameterTypes: Class<*>) {
            install(role.tag) {
                val method = DexSymbols.findClass(owner, false, loader).getDeclaredMethod(name, *parameterTypes)
                ModernHook.deoptimize(method)
                ModernHook.aroundMethod(method) { chain ->
                    val previous = showing.get()
                    showing.set(role)
                    try {
                        chain.proceed()
                    } finally {
                        if (previous == null) showing.remove() else showing.set(previous)
                    }
                }
                installed = true
            }
        }
        bind(Role.DRAWER_POPUP, "com.honeyspace.ui.honeypots.appscreen.presentation.AppscreenPot",
            "showPopupMenu", View::class.java)
        bind(Role.FINDER_POPUP, "g7.h0", "G", View::class.java)
        bind(Role.RECENTS_OVERFLOW, "com.honeyspace.ui.honeypots.recentscreen.presentation.RecentscreenPot",
            "showPopupMenu")
        bind(Role.MULTI_SELECT_MENU, "com.honeyspace.ui.common.MultiSelectContextPopupMenu",
            "createAndShowContextPopupMenu", View::class.java, MotionEvent::class.java,
            java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
        install("context_menus") {
            val callback = DexSymbols.findClass("kotlin.jvm.functions.Function1", false, loader)
            bind(Role.HOME_CONTEXT_MENU, "kc.b", "b", View::class.java, callback)
            bind(Role.DEX_CONTEXT_MENU, "s9.a", "b", View::class.java, java.lang.Integer.TYPE, Point::class.java, callback)
        }
        if (!installed) showHook.unhook()
    }

    private fun hookTabIndicator(loader: ClassLoader) {
        check(tabsId != 0)
        val type = DexSymbols.findClass("com.google.android.material.tabs.SeslTabRoundRectIndicator", false, loader)
            .asSubclass(View::class.java)
        val color = type.getDeclaredMethod("setSelectedIndicatorColor", Int::class.javaPrimitiveType)
        ModernHook.aroundMethod(color) { chain ->
            val result = chain.proceed()
            safely {
                val view = chain.thisObject as View
                findProfileIndicator(view)
                views[view]?.refresh()
            }
            result
        }
        tabIndicator = type
    }

    private fun findProfileIndicator(view: View) {
        var parent = view.parent as? View
        while (parent != null) {
            if (parent.id == tabsId) {
                watch(view, Role.TABS)
                return
            }
            parent = parent.parent as? View
        }
    }

    /** Also recognises controls which were inflated before the module became ready. */
    fun find(view: View) {
        if (tabIndicator?.isInstance(view) == true) findProfileIndicator(view)
        findLargeFolder?.invoke(view)
        persistentPlates[view.id]?.let { watch(view, it) }
        findDrawerControl(view)
    }

    private fun findDrawerControl(view: View) {
        if (view.id == View.NO_ID || view.id == 0) return
        when (view.id) {
            optionId -> watch(view, Role.MENU)
            tabsId -> if (tabIndicator == null) watch(view, Role.TABS)
            searchId -> if (searchBackgroundId != 0) {
                view.findViewById<View>(searchBackgroundId)?.let { watch(it, Role.SEARCH) }
            }
            finderInputId -> if (finderBackgroundId != 0) {
                view.findViewById<View>(finderBackgroundId)?.let { watch(it, Role.FINDER_INPUT) }
            }
            drawerId -> if (moreId != 0 && moreContainerId != 0 && searchBackgroundId != 0) {
                view.findViewById<View>(moreId)?.findViewById<View>(moreContainerId)
                    ?.findViewById<View>(searchBackgroundId)?.let { watch(it, Role.MORE) }
            }
        }
    }

    fun refresh() {
        tips?.refresh()
        views.values.toList().forEach(Binding::refresh)
    }

    private fun refresh(view: View, role: Role) {
        watch(view, role)
        views[view]?.refresh()
    }

    private fun watch(view: View, role: Role) {
        if (!main.looper.isCurrentThread) {
            main.post { safely { watch(view, role) } }
            return
        }
        if (views.containsKey(view)) return
        val binding = Binding(view, role)
        views[view] = binding
        onSurface(role.tag, GlassSurfaceStage.FOUND)
        view.addOnAttachStateChangeListener(binding)
        if (view.isAttachedToWindow) binding.onViewAttachedToWindow(view)
    }

    private fun visibleStrength(view: View, role: Role): Int {
        if (!view.isAttachedToWindow || !view.isShown) return 0
        val display = view.display ?: return 0
        if (display.displayId != 0 && role !in secondaryDisplayRoles) return 0
        val params = view.rootView.layoutParams as? WindowManager.LayoutParams ?: return 0
        if (params.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) return 0
        return level()
    }

    private fun didDraw(role: Role) {
        if (reported.add(role)) {
            HookEntry.log("GLASS_HOME_CONTROL_DRAWN ${role.tag}")
            onSurface(role.tag, GlassSurfaceStage.DRAWN)
            onDrawn()
        }
    }

    private fun safely(action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            if (!reportedError) {
                reportedError = true
                HookEntry.log("GLASS_HOME_CONTROL_FAILED ${error.javaClass.simpleName}")
            }
        }
    }

    private inner class Binding(view: View, private val role: Role) :
        View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
        private val view = WeakReference(view)
        private val effect = when (role) {
            Role.MENU, Role.ARROW, Role.MENU_TITLE -> null
            else -> GlassSystemSurfaceEffect(material, false) { didDraw(role) }
        }
        private var menu: MenuPlate? = null
        private var attenuated: Drawable? = null
        private var originalAlpha = 255
        private var appliedAlpha: Int? = null

        override fun onViewAttachedToWindow(view: View) {
            view.addOnLayoutChangeListener(this)
            OneShotPreDrawListener.add(view, ::refresh)
        }

        override fun onViewDetachedFromWindow(view: View) {
            view.removeOnLayoutChangeListener(this)
            effect?.restore(view)
            restoreMenu(view)
            restoreTint()
        }

        override fun onLayoutChange(view: View, left: Int, top: Int, right: Int, bottom: Int,
            oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) = refresh()

        fun refresh() = safely {
            val host = view.get() ?: return@safely
            val strength = visibleStrength(host, role)
            when (role) {
                Role.MENU -> if (strength <= 0) restoreMenu(host) else attachMenu(host)
                Role.ARROW, Role.MENU_TITLE -> if (strength <= 0) restoreTint() else styleTint(host, strength)
                Role.SEARCH, Role.FINDER_INPUT, Role.WIDGET_SEARCH, Role.OVERLAY_SEARCH ->
                    effect?.update(host, strength, 30, boundedPlate = true)
                Role.TASKBAR -> effect?.update(host, strength, 12, boundedPlate = true)
                Role.LARGE_FOLDER -> {
                    val resources = host.resources
                    val density = resources.displayMetrics.density
                    val corner = if (largeFolderRadiusId == 0) 24
                    else (resources.getDimension(largeFolderRadiusId) / density).roundToInt()
                    effect?.update(host, strength, corner, keepFill = true)
                }
                Role.MULTI_SELECT_ACTION -> effect?.update(host, strength, 18, keepFill = true)
                Role.FINDER_CHIP -> effect?.update(host, strength, 18, boundedPlate = true)
                Role.DRAWER_SORT -> effect?.update(host, strength, 24, keepFill = true)
                Role.MORE -> {
                    val maxSize = 88 * host.resources.displayMetrics.density
                    val buttonStrength = if (host.width <= maxSize && host.height <= maxSize) strength else 0
                    effect?.update(host, buttonStrength, 24, boundedPlate = true)
                }
                else -> effect?.update(host, strength, 24, boundedPlate = true)
            }
        }

        private fun attachMenu(host: View) {
            if (host.background === menu && menu != null) {
                menu?.invalidateSelf()
                return
            }
            val stock = host.background ?: return
            if (stock !is ColorDrawable && stock !is GradientDrawable && stock !is InsetDrawable) return
            val density = host.resources.displayMetrics.density
            val radius = if (menuRadiusId == 0) 24f * density else host.resources.getDimension(menuRadiusId)
            val plate = MenuPlate(host, stock, density, radius)
            menu?.release()
            menu = plate
            host.background = plate
        }

        private fun restoreMenu(host: View) {
            val plate = menu ?: return
            if (host.background === plate) host.background = plate.stock
            plate.release()
            menu = null
        }

        private fun styleTint(host: View, strength: Int) {
            val stock = host.background
            val supported = if (role == Role.ARROW) stock is ShapeDrawable else stock is ColorDrawable
            if (!supported || stock == null) {
                restoreTint()
                return
            }
            if (attenuated !== stock) {
                restoreTint()
                attenuated = stock
                originalAlpha = stock.alpha
            } else if (stock.alpha != appliedAlpha) originalAlpha = stock.alpha
            val suppression = if (role == Role.MENU_TITLE) 0.0084f else 0.0052f
            val alpha = (originalAlpha * (1f - strength * suppression)).roundToInt()
            if (stock.alpha != alpha) stock.alpha = alpha
            appliedAlpha = alpha
        }

        private fun restoreTint() {
            val stock = attenuated ?: return
            if (stock.alpha == appliedAlpha) stock.alpha = originalAlpha
            attenuated = null
            appliedAlpha = null
        }
    }

    companion object {
        private val diagnosticNames = Role.entries.mapTo(mutableSetOf()) { it.tag }
        private val secondaryDisplayRoles = setOf(
            Role.DEX_CONTEXT_MENU, Role.DEX_WELLBEING, Role.TASKBAR, Role.TASKBAR_TIPS,
            Role.TASKBAR_OVERFLOW, Role.OVERLAY_SEARCH,
        )

        fun isDiagnosticName(name: String): Boolean = name == SAMSUNG_TOOLTIP || name in diagnosticNames
    }

    private inner class MenuPlate(view: View, val stock: Drawable, density: Float, private val corner: Float) : Drawable() {
        private val host = WeakReference(view)
        private val radii = FloatArray(8)
        private val transform = Matrix()
        private var lens: GlassLens? = null
        private var source: BitmapShader? = null
        private var sampled: Bitmap? = null
        private val sourceBounds = Rect()
        private val screenDensity = density
        private var refractionUnavailable = false

        override fun draw(canvas: Canvas) {
            val view = host.get()
            val strength = if (view == null) 0 else visibleStrength(view, Role.MENU)
            stock.bounds = bounds
            if (view == null || strength <= 0 || bounds.isEmpty || !canvas.isHardwareAccelerated) {
                stock.draw(canvas)
                return
            }
            safely {
                val left = bounds.left.toFloat()
                val top = bounds.top.toFloat()
                val right = bounds.right.toFloat()
                val bottom = bounds.bottom.toFloat()
                val radius = corner.coerceIn(0f, minOf(bounds.width(), bounds.height()) / 2f)
                val night = dark()
                val refracted = if (refractionUnavailable) false else {
                    runCatching {
                        val glassSource = bitmapShader(view) ?: return@runCatching false
                        val glass = lens ?: GlassLens(screenDensity).also { lens = it }
                        glass.draw(canvas, glassSource, left, top, right, bottom, radius, strength, night,
                            opacity = strength * .009f, tintFactor = material.tintFactor)
                        if (!reportedRefraction) {
                            reportedRefraction = true
                            HookEntry.log("GLASS_HOME_CONTROL_REFRACTED quick_options")
                            onSurface(Role.MENU.tag, GlassSurfaceStage.REFRACTED)
                        }
                        true
                    }.getOrElse {
                        refractionUnavailable = true
                        release()
                        HookEntry.log("GLASS_HOME_CONTROL_REFRACTION_UNAVAILABLE quick_options ${it.javaClass.simpleName}")
                        false
                    }
                }
                val original = stock.alpha
                try {
                    // Keep enough native tint to read the menu over busy wallpaper.
                    val suppression = if (refracted) 0.007f else 0.0052f
                    stock.alpha = (original * (1f - strength * suppression)).roundToInt()
                    stock.draw(canvas)
                } finally {
                    stock.alpha = original
                }
                radii.fill(radius)
                material.drawSurface(canvas, left, top, right, bottom, radii, strength, night,
                    fill = !refracted, rim = true, profile = GlassMaterial.Profile.SYSTEM_SURFACE)
                didDraw(Role.MENU)
            }
        }

        private fun bitmapShader(view: View): BitmapShader? {
            val parent = view.parent as? ViewGroup
            val behind = parent?.getChildAt(0)?.takeUnless { it === view }
            val bitmap = behind?.let { nativeCrop?.bitmap(it) }
            if (bitmap == null || bitmap.isRecycled) {
                release()
                return null
            }
            if (sampled !== bitmap) {
                sampled = bitmap
                source = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                }
                sourceBounds.setEmpty()
            }
            if (sourceBounds != bounds) {
                sourceBounds.set(bounds)
                transform.setScale(bounds.width().toFloat() / bitmap.width, bounds.height().toFloat() / bitmap.height)
                transform.postTranslate(bounds.left.toFloat(), bounds.top.toFloat())
                source?.setLocalMatrix(transform)
            }
            return source
        }

        fun release() {
            if (sampled == null) return
            lens?.clearBackdrop()
            source = null
            sampled = null
        }

        override fun setAlpha(alpha: Int) = stock.setAlpha(alpha)
        override fun setColorFilter(filter: ColorFilter?) = stock.setColorFilter(filter)
        override fun getPadding(padding: Rect): Boolean = stock.getPadding(padding)
        override fun isStateful(): Boolean = stock.isStateful
        override fun onStateChange(state: IntArray): Boolean = stock.setState(state)
        override fun getOutline(outline: Outline) {
            if (!bounds.isEmpty) outline.setRoundRect(bounds, corner)
        }
        @Deprecated("Required by Drawable")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class NativeCrop {
        private val blurInfo = View::class.java.getDeclaredField("mBlurInfo").apply { isAccessible = true }
        private val mode = blurInfo.type.getMethod("getBlurMode")
        private val capturedBitmap = blurInfo.type.getMethod("getCapturedBitmap")

        fun bitmap(view: View): Bitmap? {
            val info = blurInfo.get(view) ?: return null
            if (mode.invoke(info) != 1) return null
            return (capturedBitmap.invoke(info) as? Bitmap)?.takeUnless(Bitmap::isRecycled)
        }
    }
}
