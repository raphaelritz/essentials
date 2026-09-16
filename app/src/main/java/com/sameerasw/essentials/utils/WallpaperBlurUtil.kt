/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Utilities
 * File: WallpaperBlurUtil.kt
 * Description: Blurs wallpaper bitmaps off-screen for the live wallpaper engine.
 */

package com.sameerasw.essentials.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.util.Log

/**
 * A wallpaper engine draws into a Surface canvas, which has no render-effect hook, so the blur is
 * baked into a bitmap the engine then draws. The source is never modified.
 */
object WallpaperBlurUtil {
    private const val TAG = "WallpaperBlur"

    /** The AOD overlay hands its slider value times this to RenderEffect; one slider value means one look on every surface. */
    private const val BLUR_RADIUS_SCALE = 4f

    /** A blurred copy of [source], or null when no blur is asked for or none can be produced. */
    fun blur(
        source: Bitmap,
        radius: Float,
    ): Bitmap? {
        if (radius <= 0f) return null
        val radiusPx = radius * BLUR_RADIUS_SCALE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            renderEffectBlur(source, radiusPx)?.let { return it }
        }
        return downscaleBlur(source, radiusPx)
    }

    /** RenderEffect on an off-screen RenderNode, the same implementation the AOD overlay gets, so the two surfaces match. */
    private fun renderEffectBlur(
        source: Bitmap,
        radiusPx: Float,
    ): Bitmap? {
        var imageReader: ImageReader? = null
        var renderer: HardwareRenderer? = null
        return try {
            val width = source.width
            val height = source.height
            val reader =
                ImageReader.newInstance(
                    width,
                    height,
                    PixelFormat.RGBA_8888,
                    1,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
                )
            imageReader = reader

            val node = RenderNode("wallpaper-blur")
            val hardwareRenderer = HardwareRenderer()
            renderer = hardwareRenderer
            hardwareRenderer.setSurface(reader.surface)
            hardwareRenderer.setContentRoot(node)

            node.setPosition(0, 0, width, height)
            node.setRenderEffect(RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP))

            // Software bitmaps cannot be sampled by a hardware canvas on every device.
            val uploadable = source.copy(Bitmap.Config.ARGB_8888, false)
            node.beginRecording().drawBitmap(uploadable, 0f, 0f, null)
            node.endRecording()
            hardwareRenderer
                .createRenderRequest()
                .setWaitForPresent(true)
                .syncAndDraw()
            uploadable.recycle()

            val image = reader.acquireNextImage() ?: return null
            image.use {
                val buffer = it.hardwareBuffer ?: return null
                buffer.use { hb ->
                    // Copied off the hardware buffer: the engine draws this repeatedly and the buffer
                    // goes with the ImageReader.
                    Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB))?.copy(Bitmap.Config.ARGB_8888, false)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "RenderEffect blur unavailable, falling back", e)
            null
        } finally {
            renderer?.destroy()
            imageReader?.close()
        }
    }

    /** Without RenderEffect: a bilinear downscale and upscale, which approximates a gaussian well enough at wallpaper scale. */
    private fun downscaleBlur(
        source: Bitmap,
        radiusPx: Float,
    ): Bitmap {
        val factor = (radiusPx / 4f).coerceIn(2f, 24f)
        val small = Bitmap.createScaledBitmap(source, (source.width / factor).toInt().coerceAtLeast(1), (source.height / factor).toInt().coerceAtLeast(1), true)
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(small, null, Rect(0, 0, source.width, source.height), Paint(Paint.FILTER_BITMAP_FLAG))
        small.recycle()
        return output
    }
}
