/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Background Services & Receivers
 * File: UnifiedWallpaperService.kt
 * Description: Live wallpaper that draws the unified wallpaper with an adjustable blur.
 */

package com.sameerasw.essentials.services

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.utils.WallpaperBlurUtil
import java.io.File

/**
 * Draws the unified wallpaper on the home screen, blurring at draw time.
 *
 * The system offers no way to blur a static wallpaper — WallpaperManager has no blur concept at
 * all — so the only way to keep the blur adjustable is to be the thing that draws it. Because the
 * pristine image stays on disk and only the rendered copy is blurred, changing the blur is instant
 * and reversible, and the user's original is never overwritten.
 */
class UnifiedWallpaperService : WallpaperService() {
    companion object {
        private const val TAG = "UnifiedWallpaper"

        /** The pristine, never-blurred source the engine renders from. */
        const val SOURCE_FILE_NAME = "unified_wallpaper_source.jpg"

        /**
         * Returns the file the unified wallpaper source is stored in.
         *
         * @param context [android.content.Context] Target context.
         * @return The source [File].
         */
        fun sourceFile(context: android.content.Context): File = File(context.filesDir, SOURCE_FILE_NAME)
    }

    override fun onCreateEngine(): Engine = BlurredImageEngine()

    inner class BlurredImageEngine : Engine() {
        private lateinit var repository: SettingsRepository
        private val handler = Handler(Looper.getMainLooper())

        /** Decoded pristine source, scaled to the surface. */
        private var sourceBitmap: Bitmap? = null

        /** What is actually drawn: [sourceBitmap] with the current blur baked in. */
        private var renderedBitmap: Bitmap? = null

        private var renderedBlur = -1f
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var xOffset = 0.5f

        private val prefsListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                when (key) {
                    SettingsRepository.KEY_UNIFIED_WALLPAPER_SOURCE_ID,
                    SettingsRepository.KEY_UNIFIED_WALLPAPER_BLUR_HOME,
                    SettingsRepository.KEY_AOD_WALLPAPER_BLUR,
                    -> handler.post { reload(decodeAgain = key == SettingsRepository.KEY_UNIFIED_WALLPAPER_SOURCE_ID) }
                }
            }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            repository = SettingsRepository(applicationContext)
            repository.registerOnSharedPreferenceChangeListener(prefsListener)
            setOffsetNotificationsEnabled(true)
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            reload(decodeAgain = true)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) draw()
        }

        override fun onOffsetsChanged(
            xOffsetValue: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int,
        ) {
            super.onOffsetsChanged(xOffsetValue, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)
            // Only redraw when the parallax actually moved enough to be visible.
            if (kotlin.math.abs(xOffsetValue - xOffset) < 0.001f) return
            xOffset = xOffsetValue
            draw()
        }

        /**
         * The effective blur for the home screen: zero unless the user opted in, otherwise the same
         * magnitude the AOD overlay uses, so one slider drives both surfaces.
         */
        private fun effectiveBlur(): Float =
            if (repository.getUnifiedWallpaperBlurHome()) repository.getAodWallpaperBlur() else 0f

        private fun reload(decodeAgain: Boolean) {
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return
            if (decodeAgain) {
                sourceBitmap?.recycle()
                sourceBitmap = decodeScaledSource()
                renderedBlur = -1f
            }
            rerenderIfNeeded()
            draw()
        }

        /**
         * Decodes the source down to roughly the surface size. Wallpapers are large and the engine
         * holds two copies, so decoding at full resolution is the fastest way to be killed.
         */
        private fun decodeScaledSource(): Bitmap? {
            val file = sourceFile(applicationContext)
            if (!file.exists()) return null
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

                var sample = 1
                while (bounds.outWidth / (sample * 2) >= surfaceWidth &&
                    bounds.outHeight / (sample * 2) >= surfaceHeight
                ) {
                    sample *= 2
                }

                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    },
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Could not decode wallpaper source", t)
                null
            }
        }

        private fun rerenderIfNeeded() {
            val source = sourceBitmap ?: return
            val blur = effectiveBlur()
            if (blur == renderedBlur && renderedBitmap != null) return

            val previous = renderedBitmap
            renderedBitmap = WallpaperBlurUtil.blur(source, blur)
            renderedBlur = blur
            if (previous != null && previous != source && previous != renderedBitmap) previous.recycle()
        }

        private fun draw() {
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return
            rerenderIfNeeded()

            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas() ?: return
                canvas.drawColor(Color.BLACK)
                val bitmap = renderedBitmap
                if (bitmap != null && !bitmap.isRecycled) {
                    canvas.drawBitmap(bitmap, null, destinationFor(bitmap), FILTER_PAINT)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Draw failed", t)
            } finally {
                if (canvas != null) runCatching { holder.unlockCanvasAndPost(canvas) }
            }
        }

        /**
         * Centre-crops the bitmap over the surface, shifting horizontally with the launcher's
         * parallax offset so paging feels the same as a static wallpaper.
         */
        private fun destinationFor(bitmap: Bitmap): RectF {
            val scale =
                maxOf(
                    surfaceWidth.toFloat() / bitmap.width,
                    surfaceHeight.toFloat() / bitmap.height,
                )
            val scaledWidth = bitmap.width * scale
            val scaledHeight = bitmap.height * scale
            val overflowX = scaledWidth - surfaceWidth
            val left = -overflowX * xOffset
            val top = -(scaledHeight - surfaceHeight) / 2f
            return RectF(left, top, left + scaledWidth, top + scaledHeight)
        }

        override fun onDestroy() {
            repository.unregisterOnSharedPreferenceChangeListener(prefsListener)
            handler.removeCallbacksAndMessages(null)
            renderedBitmap?.let { if (it != sourceBitmap) it.recycle() }
            renderedBitmap = null
            sourceBitmap?.recycle()
            sourceBitmap = null
            super.onDestroy()
        }
    }
}

private val FILTER_PAINT = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
