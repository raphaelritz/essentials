/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Utilities
 * File: WallpaperSubject.kt
 * Description: Cuts the subject out of the lock photo along its depth map, to draw in front of the clock.
 */

package com.sameerasw.essentials.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect

/**
 * The subject is what the depth map puts nearer than a chosen level: the photo keeps those pixels
 * and loses the rest, with a ramp of the chosen softness between. The map is stretched over the
 * photo, so a map of a lower resolution cuts the same, only more coarsely.
 */
object WallpaperSubject {
    /** Narrower than one grey step of the map, so a hard edge still has a slope to clamp. */
    private const val LEAST_RAMP = 1f / 255f

    fun cut(
        photo: Bitmap,
        depth: Bitmap,
        level: Float,
        softness: Float,
        nearIsDark: Boolean,
    ): Bitmap {
        val subject = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(subject)
        canvas.drawBitmap(photo, 0f, 0f, null)
        canvas.drawBitmap(depth, null, Rect(0, 0, photo.width, photo.height), maskPaint(level, softness, nearIsDark))
        return subject
    }

    /**
     * Keeps the photo where the map is brighter than the level, or darker for a map that paints
     * near things dark: the map's grey becomes an alpha that is 0 at one end of the ramp and 1 at
     * the other, the matrix working in the pixels' own 0..255.
     */
    private fun maskPaint(
        level: Float,
        softness: Float,
        nearIsDark: Boolean,
    ): Paint {
        val ramp = softness.coerceAtLeast(LEAST_RAMP)
        val gain = (if (nearIsDark) -1f else 1f) / ramp
        val zero = if (nearIsDark) level + ramp / 2f else level - ramp / 2f
        val matrix =
            ColorMatrix(
                floatArrayOf(
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    gain, 0f, 0f, 0f, -zero * gain * 255f,
                ),
            )
        return Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
    }
}
