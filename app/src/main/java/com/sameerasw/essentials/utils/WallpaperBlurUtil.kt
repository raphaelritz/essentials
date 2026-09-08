/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Utilities
 * File: WallpaperBlurUtil.kt
 * Description: Produces a blurred copy of a wallpaper bitmap.
 */

package com.sameerasw.essentials.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.util.Log

/**
 * Blurs wallpaper bitmaps off-screen.
 *
 * The AOD overlay can lean on [RenderEffect] directly because it blurs a live View. A wallpaper
 * engine draws into a Surface canvas instead, which has no render-effect hook, so the blur has to
 * be baked into a bitmap that the engine then draws. The source image is never modified — callers
 * keep the pristine original and re-derive whenever the radius changes.
 */
object WallpaperBlurUtil {
    private const val TAG = "WallpaperBlur"

    /**
     * The AOD overlay multiplies its slider value by this before handing it to RenderEffect
     * (AodWallpaperOverlayHandler.applyBlurEffect). Matching it keeps one slider meaning one
     * visual result on both surfaces.
     */
    const val BLUR_RADIUS_SCALE = 4f

    /** RenderEffect rejects very large radii; keep well inside what it accepts. */
    private const val MAX_RADIUS_PX = 250f

    /**
     * Returns a blurred copy of [source], or [source] itself when no blur is requested.
     *
     * @param source [Bitmap] The pristine wallpaper.
     * @param radius [Float] Slider value, in the same units the AOD blur slider uses.
     * @return A new blurred bitmap, or the original when the radius is zero or blurring fails.
     */
    fun blur(
        source: Bitmap,
        radius: Float,
    ): Bitmap {
        if (radius <= 0f) return source
        val radiusPx = (radius * BLUR_RADIUS_SCALE).coerceAtMost(MAX_RADIUS_PX)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            renderEffectBlur(source, radiusPx)?.let { return it }
        }
        return downscaleBlur(source, radiusPx)
    }

    /**
     * Blurs via RenderEffect on an off-screen RenderNode, which is the same implementation the AOD
     * overlay gets, so the two surfaces match.
     */
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
            node.setRenderEffect(
                RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP),
            )

            val canvas = node.beginRecording()
            // Software bitmaps cannot be sampled by a hardware canvas on every device.
            val drawable =
                if (source.config == Bitmap.Config.HARDWARE) {
                    source
                } else {
                    source.copy(Bitmap.Config.ARGB_8888, false) ?: source
                }
            canvas.drawBitmap(drawable, 0f, 0f, null)
            node.endRecording()

            hardwareRenderer
                .createRenderRequest()
                .setWaitForPresent(true)
                .syncAndDraw()

            val image = reader.acquireNextImage() ?: return null
            image.use {
                val buffer = it.hardwareBuffer ?: return null
                buffer.use { hb ->
                    val wrapped =
                        Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB))
                            ?: return null
                    // Copy off the hardware buffer: the engine draws this repeatedly and the
                    // buffer is recycled with the ImageReader.
                    wrapped.copy(Bitmap.Config.ARGB_8888, false)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "RenderEffect blur unavailable, falling back", t)
            null
        } finally {
            runCatching { renderer?.destroy() }
            runCatching { imageReader?.close() }
        }
    }

    /**
     * Fallback for devices without RenderEffect: repeatedly downscale and upscale with bilinear
     * filtering, which approximates a gaussian well enough at wallpaper scale.
     */
    private fun downscaleBlur(
        source: Bitmap,
        radiusPx: Float,
    ): Bitmap {
        return try {
            val factor = (radiusPx / 4f).coerceIn(2f, 24f)
            val smallWidth = (source.width / factor).toInt().coerceAtLeast(1)
            val smallHeight = (source.height / factor).toInt().coerceAtLeast(1)

            var working = Bitmap.createScaledBitmap(source, smallWidth, smallHeight, true)
            repeat(2) {
                val next = Bitmap.createScaledBitmap(working, smallWidth, smallHeight, true)
                if (next != working) working.recycle()
                working = next
            }

            val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            Canvas(output).drawBitmap(
                working,
                null,
                android.graphics.Rect(0, 0, source.width, source.height),
                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
            )
            working.recycle()
            output
        } catch (t: Throwable) {
            Log.w(TAG, "Fallback blur failed", t)
            source
        }
    }
}
