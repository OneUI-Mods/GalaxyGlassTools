package dev.ryuya.galaxyglass

import android.content.res.Resources

/** Native view anchors verified in the One UI 9 BZI9 SystemUI APK. */
internal object GlassSystemCatalog {
    data class Target(
        val id: Int,
        val cornerDp: Int = 0,
        val keepFill: Boolean = false,
        val powerActionIcon: Boolean = false,
        val shadeBackdrop: Boolean = false,
    )
    enum class NotificationControls { PLATE, CONTENT }
    data class Surface(
        val name: String,
        val targets: List<Target>,
        val dialog: Boolean,
        val lockScreen: Boolean,
        val notificationControls: NotificationControls?,
    )

    /** A null name targets the inflated root. CREATE_NONE never adds a background to a bare view. */
    private data class Anchor(
        val name: String?,
        val cornerDp: Int = CREATE_NONE,
        val keepFill: Boolean = false,
        val powerActionIcon: Boolean = false,
        val shadeBackdrop: Boolean = false,
    )
    private data class Layout(
        val name: String,
        val anchors: List<Anchor>,
        val dialog: Boolean = false,
        val lockScreen: Boolean = false,
        val notificationControls: NotificationControls? = null,
    )

    private const val CREATE_NONE = 0
    private const val ROOT = 0
    private const val SYSTEM_UI = "com.android.systemui"
    private const val ANDROID = "android"

    private fun root(name: String, radius: Int = CREATE_NONE) = Layout(name, listOf(Anchor(null, radius)))
    private fun plate(name: String, vararg views: String) = Layout(name, views.map { Anchor(it) })
    private fun roundedPlate(name: String, radius: Int, vararg views: String) =
        Layout(name, views.map { Anchor(it, radius) })
    private fun dialog(name: String, vararg views: String) = Layout(name, views.map { Anchor(it) }, dialog = true)
    private fun shadePlate(name: String?, radius: Int) = Anchor(name, radius, shadeBackdrop = true)
    private fun chip(name: String) = Anchor(name, 24, keepFill = true)

    private val mediaOutput = Anchor("sec_media_output_text", 18)
    private val editAreas = listOf(shadePlate("qs_active_page_parent", 28), shadePlate("qs_available_area", 28))

    private val layouts = listOf(
        // SystemUI dialogs and Samsung tile details.
        dialog("internet_connectivity_dialog"),
        dialog("bluetooth_tile_dialog"),
        dialog("hearing_devices_tile_dialog"),
        dialog("audio_sharing_dialog"),
        dialog("connected_display_dialog", "cd_bottom_sheet"),
        dialog("connected_display_dialog_2", "cd_bottom_sheet"),
        dialog("media_output_dialog", "media_output_dialog"),
        dialog("privacy_dialog", "root"),
        dialog("sec_privacy_dialog", "root"),
        dialog("privacy_dialog_v2"),
        dialog("screen_record_options"),
        dialog("screen_share_dialog"),
        dialog("power_ui_dialog"),
        dialog("global_actions_power_dialog"),
        dialog("accessibility_deprecate_extra_dim_dialog"),
        dialog("controls_add_dialog"),
        dialog("letterbox_education_dialog_layout"),
        dialog("letterbox_restart_dialog_layout"),
        dialog("closure_state_alert_ui_dialog", "closure_state_alert_background"),
        dialog("sec_quick_settings_brightness_dialog"),
        dialog("font_scaling_dialog"),
        dialog("zen_mode_duration_dialog"),
        dialog("zen_mode_turn_on_dialog_container"),
        dialog("quick_settings_airplane_checkbox_alertdialog"),
        dialog("quick_settings_footer_dialog"),
        dialog("quick_settings_footer_dialog_parental_controls"),
        dialog("sec_quick_settings_footer_dialog"),
        dialog("sec_quick_settings_footer_dialog_parental_controls"),
        dialog("sec_fgs_manager_container"),
        dialog("wireless_charging_alignment_dialog"),
        plate("sec_qs_detail_container", "qs_detail_parent"),
        root("qs_panel_multi_sim_preffered_slot_popup_menu"),

        // System-owned overlays. Media and screenshot thumbnails have separate image views.
        plate("clipboard_overlay", "clipboard_preview", "actions_container_background", "minimized_preview"),
        Layout("clipboard_edit_text_activity", listOf(Anchor("done_button", 24))),
        plate("screenshot_shelf", "actions_container_background", "screenshot_message_container"),
        plate("accessibility_floating_menu_tooltip", "text"),
        plate("sec_accessibility_floating_menu_tooltip", "text"),
        plate("accessibility_floating_menu_item", "icon_view"),
        plate("searcle_tip_popup", "searcle_tip_bubble"),
        root("contextual_edu_dialog", 20),
        root("window_magnification_settings_view"),
        root("text_toast"),
        root("sem_text_toast"),
        root("global_actions_toast"),
        roundedPlate("compat_mode_hint", 22, "compat_mode_hint_text"),
        roundedPlate("chipbar", 24, "chipbar_inner"),
        roundedPlate("connected_display_chip", 20, "icons_rounded_container"),
        roundedPlate("media_ttt_chip_receiver", 26, "app_icon"),
        root("status_bar_user_chip_container"),
        Layout("samsung_ongoing_privacy_chip", listOf(Anchor("icons_container", keepFill = true))),
        Layout("sec_ongoing_privacy_chip", listOf(Anchor("icons_container", keepFill = true))),
        Layout("ongoing_activity_chip_content", listOf(chip("ongoing_activity_chip_background"))),
        Layout("sec_ongoing_activity_chip_item", listOf(chip("capsule_item_top_layout"))),
        Layout("samsung_ongoing_call_chip", listOf(chip("ongoing_call_chip_background"))),
        Layout("samsung_ongoing_cast_chip", listOf(chip("samsung_ongoing_cast_chip_background"))),
        Layout("oneui_90_sec_media_view", listOf(shadePlate("sec_qs_media_drag_and_drop", 29), mediaOutput)),
        Layout("sec_media_view", listOf(shadePlate("sec_qs_media_controls", 29), mediaOutput)),
        plate("oneui_90_sec_qs_media_panel_options", "sec_remove_text", "sec_cancel_text"),
        Layout("sec_qs_media_panel_options", listOf(Anchor("sec_remove_text", 16))),
        Layout("media_long_press_menu", listOf(Anchor("dismiss_text", 18))),
        Layout("media_carousel_settings_button", listOf(Anchor("background", 24))),
        root("media_popup_next_layout", 22),
        root("media_popup_prev_layout", 22),
        Layout("notification_guts", emptyList(), notificationControls = NotificationControls.PLATE),
        Layout("notification_2025_conversation_info", emptyList(), notificationControls = NotificationControls.CONTENT),
        Layout("notification_2025_partial_conversation_info", emptyList(), notificationControls = NotificationControls.CONTENT),
        dialog("notif_half_shelf", "half_shelf"),
        Layout("notification_2025_info", listOf(Anchor("alert", 18, keepFill = true),
            Anchor("silence", 18, keepFill = true))),
        root("notification_2025_smart_reply_button", 24),
        root("notification_2025_smart_action_button", 24),
        Layout("notification_snooze", listOf(shadePlate(null, 24))),
        Layout("sec_notification_snooze", listOf(Anchor("header", 22))),
        Layout("notification_2025_footer", listOf(Anchor("settings_button", 24), Anchor("dismiss_text", 24),
            Anchor("history_button", 24))),
        root("shelf_action_chip", 24),
        root("promoted_menu_item", 18),
        Layout("qs_customize_tile_edit_layout", editAreas),
        Layout("qs_customize_tile_edit_layout_land", editAreas),
        Layout("qs_pop_over_customize_tile_edit_layout", editAreas),
        Layout("qs_customize_top_summary_and_buttons", listOf(Anchor("left_button", 22), Anchor("right_button", 22))),
        Layout("qs_customize_top_summary_and_buttons_land", listOf(Anchor("left_button", 22), Anchor("right_button", 22))),
        Layout("qs_customize_main_button", listOf(Anchor(null, 22))),
        Layout("qs_setting_edit_actionbar", listOf(Anchor("action_arrow", 21))),
        Layout("qspanel_media_devices_bar_layout", listOf(shadePlate("device_touch_area", 24), shadePlate("media_touch_area", 24))),

        // Device Controls and Flex Mode can be surfaced without any captured backdrop.
        dialog("controls_dialog"),
        roundedPlate("controls_no_favorites", 24, "controls_no_favorites_group"),
        roundedPlate("controls_with_favorites", 24, "controls_scroll_view", "controls_panel"),
        root("controls_base_item", 18),
        Layout("sec_controls_base_item", listOf(Anchor(null, 22, keepFill = true))),
        roundedPlate("hearing_device_ambient_volume_layout", 24, "ambient_expand_icon"),
        roundedPlate("basic_panel_layout", 24, "brightness_volume_view", "gridView"),
        roundedPlate("flex_media_panel_layout", 24, "brightness_volume_view", "gridView"),
        roundedPlate("touchpad_media_panel_layout", 24, "brightness_volume_view", "gridView"),
        roundedPlate("edit_panel_layout", 24, "main_floating_area"),
        roundedPlate("flex_panel_collapse_button_layout", 22, "icon_area"),
        roundedPlate("flex_panel_expand_button_layout", 22, "icon_view"),
        roundedPlate("flex_panel_expand_button_layout_fold", 22, "icon_view"),
        roundedPlate("flex_floating_media_panel", 22, "action_resume", "action_pause",
            "action_seek_previous", "action_seek_next", "action_skip_previous", "action_skip_next"),
        roundedPlate("floating_touchpad_media_panel", 22, "action_resume", "action_pause",
            "action_skip_previous", "action_skip_next"),

        // Window-management controls. These never select a hosted app surface.
        root("bubble_manage_menu"),
        plate("bubble_bar_menu_view", "bubble_bar_manage_menu_bubble_section", "bubble_bar_manage_menu_actions_section"),
        root("bubble_overflow_view", 24),
        root("sec_bubble_overflow_view", 24),
        root("bubble_stack_user_education", 22),
        root("bubbles_manage_button_education", 22),
        Layout("bubble_bar_expanded_view", listOf(Anchor("bubble_bar_caption_view", 24, keepFill = true))),
        Layout("pip_menu", listOf(Anchor("actions_container", 18), Anchor("top_end_container", 20))),
        Layout("sem_pip_menu", listOf(Anchor("actions_container", 20), Anchor("top_end_container", 20))),
        root("screen_pinning_request", 24),
        plate("desktop_mode_window_decor_handle_menu", "app_info_pill", "windowing_pill", "more_actions_pill",
            "open_in_app_or_browser_pill"),
        plate("desktop_mode_window_decor_layout_menu", "container", "windowing_pill"),
        root("desktop_mode_window_decor_layout_menu_legacy"),
        plate("desktop_mode_window_decor_resize_controls", "layout_menu_snap_menu_layout"),
        root("desktop_windowing_education_tooltip_container", 16),
        root("mt_app_compat_simplified_combined_menu_layout", 20),
        root("drag_hint_to_fullscreen", 18),
        roundedPlate("freeform_container_layout", 22, "freeform_container_tray_view", "freeform_container_recycler_view"),
        root("preview_list_container", 24),
        plate("open_by_default_settings_dialog", "open_by_default_dialog_container"),
        root("mw_caption_popup_menu"),
        root("mw_dex_caption_popup_menu"),
        root("mw_handle_popup_menu_freeform"),
        root("mw_handle_popup_menu_fullscreen"),
        root("mw_handle_popup_menu_min_freeform"),
        root("mw_handle_popup_menu_split"),
        root("mw_desktop_mode_tile_popup_menu"),
        plate("user_switcher_fullscreen", "add"),

        // Samsung and AOSP power menus.
        plate("global_actions_grid_lite", "android:list"),
        root("sec_global_actions_screen_capture_popup"),
        Layout("sec_global_actions_keysettingsview", listOf(Anchor("sec_global_actions_key_settings", 28))),
        Layout("sec_global_actions_item", listOf(Anchor("sec_global_actions_icon", powerActionIcon = true))),

        // These shortcuts have no backdrop capture or authentication content.
        Layout("keyguard_sec_affordance_view_left", listOf(Anchor("start_button", 22)), lockScreen = true),
        Layout("keyguard_sec_affordance_view_right", listOf(Anchor("end_button", 22)), lockScreen = true),
        Layout("ongoing_nowbar_view", listOf(Anchor(null, 24)), lockScreen = true),
        Layout("charger_nowbar_view", listOf(Anchor(null, 24)), lockScreen = true),
        Layout("sec_ongoing_card_item_layout", listOf(Anchor("stack_pip_layout", 28)), lockScreen = true),
        Layout("keyguard_settings_popup_menu", listOf(Anchor(null, 22)), lockScreen = true),
    )

    const val EDITOR = "quick_panel_editor"
    const val LOCK_NOTIFICATIONS = "lockscreen_notification_card"
    const val NOTIFICATION_SHELF = "notification_shelf"
    const val BUBBLE_FLYOUT = "notification_bubble_flyout"

    val popupMenus = mapOf(
        "com.android.systemui.globalactions.GlobalActionsPopupMenu" to "power_overflow",
        "com.android.systemui.qs.customize.QSBlurPopUpMenu" to "quick_panel_edit_popup",
        "com.android.systemui.user.UserSwitcherPopupMenu" to "user_switcher_popup",
    )
    const val QS_TOOLTIP = "quick_panel_tooltip"

    fun isDiagnosticName(name: String): Boolean = name == EDITOR || name == LOCK_NOTIFICATIONS ||
        name == NOTIFICATION_SHELF || name == BUBBLE_FLYOUT || name == SAMSUNG_TOOLTIP ||
        name == QS_TOOLTIP || name in popupMenus.values || layouts.any { it.name == name }

    fun resolve(resources: Resources): Map<Int, Surface> = buildMap {
        for (layout in layouts) {
            val layoutId = resources.getIdentifier(layout.name, "layout", SYSTEM_UI)
            if (layoutId == 0) continue
            val targets = layout.anchors.mapNotNull { anchor ->
                if (anchor.name == null) Target(ROOT, anchor.cornerDp, anchor.keepFill, shadeBackdrop = anchor.shadeBackdrop)
                else {
                    val pack = if (anchor.name.startsWith("android:")) ANDROID else SYSTEM_UI
                    val id = resources.getIdentifier(anchor.name.substringAfter(':'), "id", pack)
                    id.takeIf { it != 0 }?.let {
                        Target(it, anchor.cornerDp, anchor.keepFill, anchor.powerActionIcon, anchor.shadeBackdrop)
                    }
                }
            }
            if (targets.isNotEmpty() || layout.dialog || layout.notificationControls != null) {
                put(layoutId, Surface(layout.name, targets, layout.dialog, layout.lockScreen, layout.notificationControls))
            }
        }
    }
}
