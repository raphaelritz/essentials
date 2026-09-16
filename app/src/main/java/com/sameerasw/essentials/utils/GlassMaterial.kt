/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Utilities
 * File: GlassMaterial.kt
 * Description: The wallpaper clock's glass: the wallpaper refracted through the digits by a runtime shader.
 */

package com.sameerasw.essentials.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Each glyph is a slab of glass lying on the wallpaper. Its top swells from the edge to a plateau:
 * the glyphs' coverage blurred over the rise, cubed so the slab is thin at its very edge and
 * hairlines stay hairlines. Light falling straight down refracts at that surface by Snell's law and
 * crosses the glass to the wallpaper, so each point shows the wallpaper a little inward of it,
 * compressed at the rim and magnified further in, the three colours bent by slightly different
 * amounts. The slab darkens where its surface is steep, lifts itself against a dark wallpaper and
 * dims against a bright one, and catches a light from the top left. Frost mixes in the wallpaper
 * softened and more of the part's colour, from clear glass to a translucent tint. The surface is
 * drawn at half size and read through a cubic spline, so its slope turns continuously; rise and
 * thickness are given for the large face and scale with a face's text size.
 */
class GlassMaterial(
    private val context: Context,
) {
    companion object {
        private const val MASK_SCALE = 0.5f
        private const val RISE_DP = 3f
        private const val THICKNESS_DP = 12f
        private const val REFRACTIVE_INDEX = 1.5f
        private const val BACKDROP_SCALE = 0.25f
        private const val BACKDROP_BLUR = 2f
        private const val DISPERSION = 0.05f
        private const val SHADE = 0.18f
        private const val SHINE = 1f
        private const val VIBRANCY = 0.2f
        private const val ADAPT = 0.25f
        private const val TINT_CLEAR = 0.1f
        private const val TINT_FROSTED = 0.8f
        private const val NOISE = 0.01f
        private const val AGSL = """
            uniform shader backdrop;
            uniform shader frost;
            uniform shader height;
            uniform float2 origin;
            uniform float texel;
            uniform float thickness;
            uniform float index;
            uniform float dispersion;
            uniform float frosting;
            uniform float shade;
            uniform float shine;
            uniform float vibrancy;
            uniform float adapt;
            uniform float noise;
            uniform float2 light;
            uniform shader tint;
            uniform float tinting;

            float at(float2 k) {
                return float(height.eval(origin + (k + 0.5) * texel).r);
            }

            float4 row(float2 k, float dy) {
                return float4(at(k + float2(-1.0, dy)), at(k + float2(0.0, dy)), at(k + float2(1.0, dy)), at(k + float2(2.0, dy)));
            }

            float4 spline(float f) {
                float g = 1.0 - f;
                return float4(g * g * g, 3.0 * f * f * f - 6.0 * f * f + 4.0, -3.0 * f * f * f + 3.0 * f * f + 3.0 * f + 1.0, f * f * f) / 6.0;
            }

            float4 splineSlope(float f) {
                float g = 1.0 - f;
                return float4(-3.0 * g * g, 9.0 * f * f - 12.0 * f, -9.0 * f * f + 6.0 * f + 3.0, 3.0 * f * f) / 6.0;
            }

            half4 main(float2 p) {
                float2 q = (p - origin) / texel - 0.5;
                float2 k = floor(q);
                float2 f = q - k;
                float4 r0 = row(k, -1.0);
                float4 r1 = row(k, 0.0);
                float4 r2 = row(k, 1.0);
                float4 r3 = row(k, 2.0);
                float4 wx = spline(f.x);
                float4 sx = splineSlope(f.x);
                float4 wy = spline(f.y);
                float4 sy = splineSlope(f.y);
                float4 rows = float4(dot(wx, r0), dot(wx, r1), dot(wx, r2), dot(wx, r3));
                float4 rowSlopes = float4(dot(sx, r0), dot(sx, r1), dot(sx, r2), dot(sx, r3));
                float h = dot(wy, rows);
                float2 grad = float2(dot(wy, rowSlopes), dot(sy, rows)) / texel;
                float swell = h * h * h;
                float2 surface = grad * (3.0 * h * h * thickness);
                float tanIn = length(surface);
                float2 inward = tanIn > 0.00001 ? surface / tanIn : float2(0.0);
                float cosIn = inversesqrt(1.0 + tanIn * tanIn);
                float sinIn = tanIn * cosIn;
                float sinOut = sinIn / index;
                float cosOut = sqrt(1.0 - sinOut * sinOut);
                float tanBend = (sinIn * cosOut - cosIn * sinOut) / (cosIn * cosOut + sinIn * sinOut);
                float2 shift = inward * (thickness * swell * tanBend);
                float t = clamp((h - 0.5) * 2.0, 0.0, 1.0);
                half3 clear = half3(
                    backdrop.eval(p + shift * (1.0 + dispersion)).r,
                    backdrop.eval(p + shift).g,
                    backdrop.eval(p + shift * (1.0 - dispersion)).b);
                half3 colour = mix(clear, frost.eval(p + shift).rgb, half(frosting));
                colour *= half(1.0 - shade * sinIn);
                half lum = dot(colour, half3(0.299, 0.587, 0.114));
                half sat = max(max(colour.r, colour.g), colour.b) - min(min(colour.r, colour.g), colour.b);
                colour = mix(half3(lum), colour, half(1.0 + vibrancy * (1.0 - float(sat))));
                half behind = dot(frost.eval(p).rgb, half3(0.299, 0.587, 0.114));
                colour += half3(half(adapt) * (half(0.5) - behind));
                colour = mix(colour, tint.eval(p).rgb, half(tinting));
                float lobe = pow(max(dot(-inward, light), 0.0), 4.5) + pow(max(dot(inward, light), 0.0), 4.5);
                float line = 1.0 - smoothstep(0.0, 0.3, abs(t - 0.3));
                float halo = pow(1.0 - t, 1.5);
                float spec = (line + 0.35 * halo) * lobe * shine * sinIn;
                colour = mix(colour, half3(1.0), half(clamp(spec, 0.0, 1.0)));
                float grain = fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453) - 0.5;
                colour += half3(half(grain * noise));
                return half4(colour, 1.0);
            }
        """
    }

    private val shader =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { RuntimeShader(AGSL) }.onFailure { ClockLog.add(context, "glass: shader rejected, ${it.message}") }.getOrNull()
        } else {
            null
        }
    private val density = context.resources.displayMetrics.density
    private var mask: Bitmap? = null
    private var height: Bitmap? = null
    private var heightField: HeightField? = null
    private val heightOrigin = PointF()
    private val maskCanvas = Canvas()
    private var sharp: Bitmap? = null
    private var frosted: Bitmap? = null
    private var sharpShader: BitmapShader? = null
    private var frostedShader: BitmapShader? = null
    private val backdropAt = RectF()
    private val screenToLocal = Matrix()

    /** The lock wallpaper as the engine draws it and where; the frosted copy is a quarter its size, blurred once. */
    fun setBackdrop(
        shown: Bitmap?,
        at: RectF?,
    ) {
        if (shown === sharp && at == backdropAt) return
        at?.let(backdropAt::set)
        if (shown !== sharp) {
            sharp = shown
            frosted?.recycle()
            frosted =
                shown?.let {
                    val small = Bitmap.createScaledBitmap(it, max(1, (it.width * BACKDROP_SCALE).toInt()), max(1, (it.height * BACKDROP_SCALE).toInt()), true)
                    WallpaperBlurUtil.blur(small, BACKDROP_BLUR)?.also { small.recycle() } ?: small
                }
        }
        sharpShader = sharp?.let(::backdropShader)
        frostedShader = frosted?.let(::backdropShader)
    }

    /**
     * Raises the slab's surface over the face about to be drawn: [painted] is the area the face
     * covers relative to its region, [bounds] the same area in the canvas's coordinates, [scale]
     * the scale the face is drawn at.
     */
    fun prepare(
        hosted: HostedClock,
        small: Boolean,
        painted: RectF,
        bounds: RectF,
        scale: Float,
    ) {
        val shader = shader ?: return
        val size = hosted.textSize(small) / hosted.textSize(small = false)
        val rise = RISE_DP * density * size
        val margin = 3 * rise
        val height = renderHeight(hosted, small, painted, margin, rise, scale)
        heightOrigin.set(bounds.left - margin, bounds.top - margin)
        shader.setInputShader(
            "height",
            BitmapShader(height, Shader.TileMode.DECAL, Shader.TileMode.DECAL).apply {
                filterMode = BitmapShader.FILTER_MODE_NEAREST
                setLocalMatrix(Matrix().apply { setScale(1f / MASK_SCALE, 1f / MASK_SCALE); postTranslate(heightOrigin.x, heightOrigin.y) })
            },
        )
        shader.setFloatUniform("origin", heightOrigin.x, heightOrigin.y)
        shader.setFloatUniform("texel", 1f / MASK_SCALE)
        shader.setFloatUniform("thickness", THICKNESS_DP * density * size)
        shader.setFloatUniform("index", REFRACTIVE_INDEX)
        shader.setFloatUniform("dispersion", DISPERSION)
        shader.setFloatUniform("shade", SHADE)
        shader.setFloatUniform("shine", SHINE)
        shader.setFloatUniform("vibrancy", VIBRANCY)
        shader.setFloatUniform("adapt", ADAPT)
        shader.setFloatUniform("light", -0.7071f, -0.7071f)
    }

    /**
     * The paint for one part: the wallpaper, seen through the glass from under [placement], with
     * [tint] mixed in as much as [frost] asks. Null where the device has no runtime shaders or no
     * backdrop has been handed in yet.
     */
    fun shader(
        frost: Float,
        tint: Shader,
        placement: Matrix,
    ): RuntimeShader? {
        val shader = shader ?: return null
        val sharpShader = sharpShader ?: return null
        val frostedShader = frostedShader ?: return null
        placement.invert(screenToLocal)
        lookThrough(sharp!!, sharpShader)
        lookThrough(frosted!!, frostedShader)
        shader.setInputShader("backdrop", sharpShader)
        shader.setInputShader("frost", frostedShader)
        shader.setFloatUniform("frosting", frost)
        shader.setFloatUniform("noise", NOISE * frost)
        shader.setInputShader("tint", tint)
        shader.setFloatUniform("tinting", TINT_CLEAR + (TINT_FROSTED - TINT_CLEAR) * frost)
        return shader
    }

    fun release() {
        mask?.recycle()
        mask = null
        height?.recycle()
        height = null
        heightField = null
        frosted?.recycle()
        frosted = null
        sharp = null
        sharpShader = null
        frostedShader = null
    }

    private fun backdropShader(bitmap: Bitmap): BitmapShader =
        BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) filterMode = BitmapShader.FILTER_MODE_LINEAR
        }

    /** Maps [bitmap] onto the screen as the engine draws it, then back into the face's own coordinates. */
    private fun lookThrough(
        bitmap: Bitmap,
        shader: BitmapShader,
    ) {
        shader.setLocalMatrix(
            Matrix().apply {
                setScale(backdropAt.width() / bitmap.width, backdropAt.height() / bitmap.height)
                postTranslate(backdropAt.left, backdropAt.top)
                postConcat(screenToLocal)
            },
        )
    }

    /** The face drawn at half size with room around it, then its coverage raised into the slab's surface over [rise] pixels. */
    private fun renderHeight(
        hosted: HostedClock,
        small: Boolean,
        painted: RectF,
        margin: Float,
        rise: Float,
        scale: Float,
    ): Bitmap {
        val width = max(1, ((painted.width() + 2 * margin) * MASK_SCALE).roundToInt())
        val height = max(1, ((painted.height() + 2 * margin) * MASK_SCALE).roundToInt())
        if (mask?.width != width || mask?.height != height) {
            mask?.recycle()
            this.height?.recycle()
            mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            this.height = Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_1010102)
            heightField = HeightField(width, height, rise * MASK_SCALE)
        }
        val mask = mask!!
        val surface = this.height!!
        mask.eraseColor(Color.TRANSPARENT)
        maskCanvas.setBitmap(mask)
        maskCanvas.save()
        maskCanvas.scale(MASK_SCALE, MASK_SCALE)
        maskCanvas.translate(margin - painted.left, margin - painted.top)
        hosted.draw(maskCanvas, small, scale)
        maskCanvas.restore()
        heightField!!.fill(mask, surface)
        return surface
    }
}
