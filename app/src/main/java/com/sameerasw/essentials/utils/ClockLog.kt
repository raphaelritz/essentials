/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Utilities
 * File: ClockLog.kt
 * Description: Timestamped file log of the lock screen clock's positions and events, for the developer options.
 */

package com.sameerasw.essentials.utils

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ClockLog {
    private const val FILE = "lock_clock_log.txt"
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun add(
        context: Context,
        line: String,
    ) = File(context.filesDir, FILE).appendText("${time.format(Date())} $line\n")

    fun read(context: Context): String = File(context.filesDir, FILE).takeIf { it.exists() }?.readText() ?: ""

    fun clear(context: Context) = File(context.filesDir, FILE).delete()
}
