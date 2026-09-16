/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Utilities
 * File: LockScreenClockSize.kt
 * Description: The lock screen clock's size, as the settings the keyguard derives it from.
 */

package com.sameerasw.essentials.utils

import android.content.Context

/**
 * The keyguard shows the clock on one line while notifications or media are on the lock screen,
 * and always when the two-line clock is off; a size is a setting of all three.
 */
object LockScreenClockSize {
    const val DYNAMIC = "dynamic"
    const val LARGE = "large"
    const val SMALL = "small"
    const val DOUBLE_LINE_CLOCK = "lockscreen_use_double_line_clock"
    const val SHOW_NOTIFICATIONS = "lock_screen_show_notifications"
    const val SHOW_MEDIA = "media_controls_lock_screen"

    fun apply(
        context: Context,
        size: String,
    ) {
        val belowClock = if (size == LARGE) 0 else 1
        SecureSettings.putInt(context, DOUBLE_LINE_CLOCK, if (size == SMALL) 0 else 1)
        SecureSettings.putInt(context, SHOW_NOTIFICATIONS, belowClock)
        SecureSettings.putInt(context, SHOW_MEDIA, belowClock)
    }
}
