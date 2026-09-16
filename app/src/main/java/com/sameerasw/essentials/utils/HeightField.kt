/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Utilities
 * File: HeightField.kt
 * Description: How high a slab of glass stands over each pixel of a mask.
 */

package com.sameerasw.essentials.utils

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The mask's coverage blurred, three box blurs standing in for a gaussian of [sigma] texels: the
 * surface rises smoothly from a glyph's edge to a plateau, and strokes narrower than the rise round
 * into beads. Written with ten bits into the red channel of an RGBA_1010102 bitmap, which the
 * glass reads as a height.
 */
class HeightField(
    private val width: Int,
    private val height: Int,
    sigma: Float,
) {
    private val radius = ((sqrt(4f * sigma * sigma + 1f) - 1f) / 2f).roundToInt().coerceAtLeast(1)
    private val pixels = IntArray(width * height)
    private val previous = IntArray(width * height)
    private val values = FloatArray(width * height)
    private val scratch = FloatArray(width * height)
    private val bytes = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
    private val packed = bytes.asIntBuffer()

    fun fill(
        mask: Bitmap,
        into: Bitmap,
    ) {
        mask.getPixels(pixels, 0, width, 0, 0, width, height)
        if (pixels.contentEquals(previous)) return
        pixels.copyInto(previous)
        for (i in pixels.indices) values[i] = (pixels[i] ushr 24) / 255f
        repeat(PASSES) {
            for (y in 0 until height) box(values, scratch, y * width, width, 1)
            for (x in 0 until width) box(scratch, values, x, height, width)
        }
        for (i in values.indices) {
            packed.put(i, (values[i] * 1023f + 0.5f).toInt().coerceIn(0, 1023) or (3 shl 30))
        }
        bytes.position(0)
        into.copyPixelsFromBuffer(bytes)
    }

    /** One box blur along a line of [length] cells starting at [start] and [stride] apart, zero beyond its ends. */
    private fun box(
        source: FloatArray,
        into: FloatArray,
        start: Int,
        length: Int,
        stride: Int,
    ) {
        val norm = 1f / (2 * radius + 1)
        var sum = 0f
        for (k in 0 until minOf(radius, length)) sum += source[start + k * stride]
        for (i in 0 until length) {
            val entering = i + radius
            if (entering < length) sum += source[start + entering * stride]
            into[start + i * stride] = sum * norm
            val leaving = i - radius
            if (leaving >= 0) sum -= source[start + leaving * stride]
        }
    }

    companion object {
        private const val PASSES = 3
    }
}
