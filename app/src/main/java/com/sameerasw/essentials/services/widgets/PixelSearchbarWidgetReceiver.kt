/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Background Services & Receivers
 * File: PixelSearchbarWidgetReceiver.kt
 * Description: Background service component for PixelSearchbarWidgetReceiver.kt.
 */

package com.sameerasw.essentials.services.widgets

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.sameerasw.essentials.data.repository.SettingsRepository
import kotlinx.coroutines.launch

class PixelSearchbarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PixelSearchbarWidget()

    private fun ensureScraperRunning(context: Context) {
        try {
            val type = SettingsRepository(context).getPixelSearchbarType()
            if (type == "widget" || type == "music") {
                WidgetScraperService.start(context)
            }
        } catch (e: Exception) {
            Log.w("PixelSearchbarWidget", "Could not re-arm the scraper", e)
        }
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        super.onReceive(context, intent)

        // Every wake-up that matters comes through here — user unlock, app replace, launcher
        // rebind, the periodic update. Re-arm the scraper before anything renders.
        ensureScraperRunning(context)

        val action = intent.action
        if (action == Intent.ACTION_CONFIGURATION_CHANGED ||
            action == android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE
        ) {
            kotlinx.coroutines.MainScope().launch {
                try {
                    val glanceAppWidgetManager =
                        androidx.glance.appwidget.GlanceAppWidgetManager(context)
                    if (action == Intent.ACTION_CONFIGURATION_CHANGED) {
                        kotlinx.coroutines.delay(500)
                    }

                    val glanceIds =
                        glanceAppWidgetManager.getGlanceIds(PixelSearchbarWidget::class.java)
                    glanceIds.forEach { glanceId ->
                        glanceAppWidget.update(context, glanceId)
                    }
                } catch (e: Exception) {
                    android.util.Log.e(
                        "PixelSearchbarWidget",
                        "Error updating searchbar widget on broadcast",
                        e,
                    )
                }
            }
        }
    }
}
