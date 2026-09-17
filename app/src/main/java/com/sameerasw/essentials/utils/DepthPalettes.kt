/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Utilities
 * File: DepthPalettes.kt
 * Description: Reads a depth map back to grey from the colour scale a depth tool exported it in.
 */

package com.sameerasw.essentials.utils

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import com.sameerasw.essentials.R
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The depth tools export their maps through a colour scale rather than as grey. A scale is a curve
 * of 256 colours, so a map is read back by finding the scale its colours lie on and then, for
 * every pixel, the nearest colour along it. The scales are matplotlib 3.10's tables of Spectral
 * (Marigold), turbo (Depth Pro), viridis, magma, inferno and plasma, 256 colours of three bytes
 * each in that order in the raw resource; grey is the scale of a map exported as it is.
 */
object DepthPalettes {
    private const val ENTRIES = 256
    private const val SAMPLE_GRID = 64

    /** How far, root mean square over the channels, a JPEG strays from the scale it was drawn with. */
    private const val FIT_TOLERANCE = 12f

    /** A colour cube guesses each pixel's entry; the entries this far either side of the guess are then compared exactly. */
    private const val CUBE = 64
    private const val REFINE = 8

    /** The map as grey, 0 at the start of its scale and 255 at the end; null when its colours follow no known scale. */
    fun toGrey(
        resources: Resources,
        map: Bitmap,
    ): Bitmap? {
        val (scale, misfit) = scales(resources).map { it to misfit(map, it) }.minBy { it.second }
        if (misfit > FIT_TOLERANCE) return null
        val cube = cube(scale)
        val pixels = IntArray(map.width * map.height)
        map.getPixels(pixels, 0, map.width, 0, 0, map.width, map.height)
        for (i in pixels.indices) {
            val guess = cube[cell(pixels[i])]
            val level = nearest(scale, pixels[i], (guess - REFINE).coerceAtLeast(0), (guess + REFINE + 1).coerceAtMost(ENTRIES))
            pixels[i] = Color.rgb(level, level, level)
        }
        return Bitmap.createBitmap(pixels, map.width, map.height, Bitmap.Config.ARGB_8888)
    }

    private fun scales(resources: Resources): List<IntArray> {
        val bytes = resources.openRawResource(R.raw.depth_palettes).use { it.readBytes() }
        val tables =
            List(bytes.size / (ENTRIES * 3)) { table ->
                IntArray(ENTRIES) { entry ->
                    val at = (table * ENTRIES + entry) * 3
                    Color.rgb(bytes[at].toInt() and 0xFF, bytes[at + 1].toInt() and 0xFF, bytes[at + 2].toInt() and 0xFF)
                }
            }
        return tables + IntArray(ENTRIES) { Color.rgb(it, it, it) }
    }

    /** Root mean square distance of a grid of the map's pixels to their nearest colour on [scale]. */
    private fun misfit(
        map: Bitmap,
        scale: IntArray,
    ): Float {
        val step = max(1, max(map.width, map.height) / SAMPLE_GRID)
        var sum = 0L
        var count = 0
        for (y in 0 until map.height step step) {
            for (x in 0 until map.width step step) {
                val pixel = map.getPixel(x, y)
                sum += distance(pixel, scale[nearest(scale, pixel, 0, ENTRIES)])
                count++
            }
        }
        return sqrt(sum.toFloat() / count / 3f)
    }

    /** For each cell of the colour cube, the entry of [scale] nearest the cell's centre. */
    private fun cube(scale: IntArray): IntArray {
        val span = 256 / CUBE
        val cells = IntArray(CUBE * CUBE * CUBE)
        for (r in 0 until CUBE) {
            for (g in 0 until CUBE) {
                for (b in 0 until CUBE) {
                    val centre = Color.rgb(r * span + span / 2, g * span + span / 2, b * span + span / 2)
                    cells[(r * CUBE + g) * CUBE + b] = nearest(scale, centre, 0, ENTRIES)
                }
            }
        }
        return cells
    }

    private fun cell(colour: Int): Int {
        val span = 256 / CUBE
        return (Color.red(colour) / span * CUBE + Color.green(colour) / span) * CUBE + Color.blue(colour) / span
    }

    private fun nearest(
        scale: IntArray,
        colour: Int,
        first: Int,
        end: Int,
    ): Int {
        var best = first
        var bestDistance = Int.MAX_VALUE
        for (entry in first until end) {
            val distance = distance(colour, scale[entry])
            if (distance < bestDistance) {
                bestDistance = distance
                best = entry
            }
        }
        return best
    }

    private fun distance(
        a: Int,
        b: Int,
    ): Int {
        val red = Color.red(a) - Color.red(b)
        val green = Color.green(a) - Color.green(b)
        val blue = Color.blue(a) - Color.blue(b)
        return red * red + green * green + blue * blue
    }
}
