/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Utilities
 * File: SecureSettings.kt
 * Description: Writes secure settings through whichever grant the app has.
 */

package com.sameerasw.essentials.utils

import android.content.Context
import android.provider.Settings

object SecureSettings {
    fun canWrite(context: Context): Boolean = PermissionUtils.canWriteSecureSettings(context) || ShizukuUtils.hasPermission() || RootUtils.isRootPermissionGranted()

    /** Writes through the app's own grant, else Shizuku, else root; false when none of them can. */
    fun putInt(
        context: Context,
        key: String,
        value: Int,
    ): Boolean {
        if (PermissionUtils.canWriteSecureSettings(context) && runCatching { Settings.Secure.putInt(context.contentResolver, key, value) }.getOrDefault(false)) return true
        val command = "settings put secure $key $value"
        return when {
            ShizukuUtils.hasPermission() -> {
                ShizukuUtils.runCommand(command)
                true
            }
            RootUtils.isRootPermissionGranted() -> {
                RootUtils.runCommand(command)
                true
            }
            else -> false
        }
    }
}
