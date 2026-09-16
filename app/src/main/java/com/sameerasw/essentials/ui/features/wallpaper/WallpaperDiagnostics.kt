/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Wallpaper
 * File: WallpaperDiagnostics.kt
 * Description: Developer tools for the Essentials wallpaper clock.
 */

package com.sameerasw.essentials.ui.composables.wallpaper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sameerasw.essentials.R
import com.sameerasw.essentials.ui.core.cards.IconToggleItem
import com.sameerasw.essentials.ui.core.containers.RoundedCardContainer
import com.sameerasw.essentials.utils.ClockLog
import com.sameerasw.essentials.viewmodels.MainViewModel

@Composable
fun WallpaperDiagnostics(viewModel: MainViewModel) {
    val context = LocalContext.current
    val compare by viewModel.lockClockCompare

    Text(
        text = "Wallpaper clock",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    RoundedCardContainer {
        IconToggleItem(
            iconRes = R.drawable.rounded_visibility_off_24,
            title = "Show the system clock too",
            description = "Keeps the system clock visible and draws ours in blue, so the two can be compared frame by frame. Slow both down with the system's Animator duration scale",
            isChecked = compare,
            onCheckedChange = { viewModel.setLockClockCompare(it, context) },
        )
        IconToggleItem(
            iconRes = R.drawable.rounded_content_copy_24,
            title = "Copy the clock log",
            description = "Positions, measurements, SystemUI events and face reads, with timestamps",
            showToggle = false,
            onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Clock log", ClockLog.read(context)))
            },
        )
        IconToggleItem(
            iconRes = R.drawable.rounded_refresh_24,
            title = "Clear the clock log",
            showToggle = false,
            onClick = { ClockLog.clear(context) },
        )
    }
}
