package com.directlink.client

import android.content.Context

/**
 * Supported touch interaction modes in Handesk:
 * - HANDESK: App default hybrid scroll-first workflow (1-finger scroll & double-tap drag, 2-finger keyboard & shortcuts)
 * - TOUCH: RustDesk-style direct tablet touch (tap to left click, long-press right click, 1-finger direct drag)
 * - MOUSE: RustDesk-style trackpad (relative mouse delta, tap at current cursor position, 2-finger right-click)
 */
enum class GestureMode(
    val id: String,
    val title: String,
    val subtitle: String
) {
    HANDESK(
        id = "handesk",
        title = "Handesk Mode",
        subtitle = "Default: 1-finger scroll & double-tap drag, 2-finger shortcuts, 3-finger zoom"
    ),
    TOUCH(
        id = "touch",
        title = "Touch Mode",
        subtitle = "RustDesk direct touch: Tap to click, long-press right-click, 1-finger drag"
    ),
    MOUSE(
        id = "mouse",
        title = "Mouse Mode",
        subtitle = "RustDesk trackpad: Relative mouse movement, tap at cursor, 2-finger right-click"
    );

    companion object {
        private const val PREFS_NAME = "HandeskPrefs"
        private const val KEY_MODE = "active_gesture_mode"

        fun getSavedMode(context: Context): GestureMode {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedId = prefs.getString(KEY_MODE, HANDESK.id) ?: HANDESK.id
            return entries.firstOrNull { it.id == savedId } ?: HANDESK
        }

        fun saveMode(context: Context, mode: GestureMode) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_MODE, mode.id).apply()
        }
    }
}
