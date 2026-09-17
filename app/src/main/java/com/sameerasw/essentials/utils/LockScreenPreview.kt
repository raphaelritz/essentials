/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Utilities
 * File: LockScreenPreview.kt
 * Description: Draws the lock screen as the wallpaper would, for the settings page.
 */

package com.sameerasw.essentials.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.sameerasw.essentials.data.repository.SettingsRepository

/**
 * The lock screen as the wallpaper draws it, for the settings page: the lock photo, one clock face
 * at rest and the subject in front of it. The clock is hosted here as the wallpaper hosts it, so
 * the preview shows the real glyphs under the materials in force, and it is drawn straight onto the
 * page's own hardware-accelerated canvas, the only kind the glass shader runs on.
 */
class LockScreenPreview(
    context: Context,
    private val repository: SettingsRepository,
) {
    companion object {
        /** The subject's coverage of the clock is read on this many points along each side of the clock's box. */
        private const val COVERAGE_GRID = 32
    }

    val width = context.resources.displayMetrics.widthPixels
    val height = context.resources.displayMetrics.heightPixels
    private val layer = LockClockLayer(context, repository, isShowing = { true }, invalidate = {})
    private val photo = WallpaperImages.decodeForSurface(WallpaperImages.lockFile(context), width, height)
    private val depth = WallpaperImages.decodeForSurface(WallpaperImages.lockDepthFile(context), width, height)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var blurred: Bitmap? = null
    private var blurredFor = -1f

    /** The subject cut from the photo as shown, at these settings; null without a photo or a depth map. */
    fun cut(
        level: Float,
        softness: Float,
        nearIsDark: Boolean,
    ): Bitmap? {
        val shown = shown() ?: return null
        val depth = depth ?: return null
        return WallpaperSubject.cut(shown, depth, level, softness, nearIsDark)
    }

    /** The lock screen at [scale] of its size: the photo, the [small] or large face, then [subject] in front. */
    fun draw(
        canvas: Canvas,
        small: Boolean,
        scale: Float,
        subject: Bitmap?,
    ) {
        canvas.save()
        canvas.scale(scale, scale)
        canvas.drawColor(Color.BLACK)
        val shown = shown()
        val at = shown?.let { WallpaperImages.cover(it, width, height) }
        if (shown != null && at != null) canvas.drawBitmap(shown, null, at, paint)
        layer.sync()
        layer.setBackdrop(shown, at)
        layer.drawStill(canvas, small)
        if (subject != null && at != null) canvas.drawBitmap(subject, null, at, paint)
        canvas.restore()
    }

    /** The share of the [small] or large face's box at which [subject] is opaque, read on a grid; null without a measured clock. */
    fun coverage(
        small: Boolean,
        subject: Bitmap?,
    ): Float? {
        if (subject == null) return null
        layer.sync()
        val clock = layer.stillBounds(small) ?: return null
        val at = WallpaperImages.cover(subject, width, height)
        var covered = 0
        for (row in 0 until COVERAGE_GRID) {
            for (column in 0 until COVERAGE_GRID) {
                val x = clock.left + clock.width() * (column + 0.5f) / COVERAGE_GRID
                val y = clock.top + clock.height() * (row + 0.5f) / COVERAGE_GRID
                val pixelX = ((x - at.left) / at.width() * subject.width).toInt().coerceIn(0, subject.width - 1)
                val pixelY = ((y - at.top) / at.height() * subject.height).toInt().coerceIn(0, subject.height - 1)
                if (Color.alpha(subject.getPixel(pixelX, pixelY)) >= 128) covered++
            }
        }
        return covered.toFloat() / (COVERAGE_GRID * COVERAGE_GRID)
    }

    fun release() {
        layer.release()
        blurred?.recycle()
        depth?.recycle()
        photo?.recycle()
    }

    /** The lock photo under the blur the wallpaper gives it, blurred again only when that setting moved. */
    private fun shown(): Bitmap? {
        val photo = photo ?: return null
        val blur = repository.getWallpaperLockBlur()
        if (blur != blurredFor) {
            blurred?.recycle()
            blurred = WallpaperBlurUtil.blur(photo, blur)
            blurredFor = blur
        }
        return blurred ?: photo
    }
}
