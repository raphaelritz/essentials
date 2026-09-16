/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Utilities
 * File: LockClockLayer.kt
 * Description: The lock screen clock as the wallpaper draws it: hosted from its plugin, placed and animated like the keyguard's.
 */

package com.sameerasw.essentials.utils

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.animation.doOnEnd
import com.sameerasw.essentials.data.repository.SettingsRepository
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Hosts the keyguard's own clock plugin and draws it where the keyguard shows the real clock, with
 * the keyguard's swap between its large and small face and its hand-off with the always-on display.
 * Everything runs on the wallpaper engine's thread; [invalidate] asks the engine for a frame.
 */
class LockClockLayer(
    private val context: Context,
    private val repository: SettingsRepository,
    private val isShowing: () -> Boolean,
    private val invalidate: () -> Unit,
) {
    companion object {
        /** Clocks verified to disappear behind a transparent seed colour and to host faithfully. */
        private val SUPPORTED_CLOCKS = setOf("DEFAULT", "DIGITAL_CLOCK_FLEX", "DIGITAL_CLOCK_CALLIGRAPHY")

        /** Clocks the always-on display shows as an outline; the others thin their strokes instead. */
        private val OUTLINE_CLOCKS = setOf("DIGITAL_CLOCK_CALLIGRAPHY")

        /**
         * The keyguard's clock swap: one emphasized tween that also carries the small face along
         * with the smartspace cards. The keyguard cuts between its faces on that curve within a
         * tenth of a second; here they cross-fade on plain time across the swap instead.
         */
        const val SWAP_MS = 300L
        private const val SWAP_FADE_OUT_UNTIL = 0.6f
        private const val SWAP_FADE_IN_FROM = 0.2f
        private val SWAP_INTERPOLATOR =
            PathInterpolator(
                Path().apply {
                    cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
                    cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
                },
            )

        /** A swap joined with less than this left still plays this long, so the new face never pops in. */
        private const val SWAP_LEAST_MS = 250L

        /** The keyguard's exit at unlock: the clock rises and fades over the first stretch of the transition. */
        private const val UNLOCK_EXIT_MS = 150L
        private const val UNLOCK_RISE_DP = 48f
        val UNLOCK_EXIT_INTERPOLATOR = PathInterpolator(0.1f, 0.1f, 0f, 1f)

        /**
         * The keyguard's slide between the always-on display and the lock screen. The always-on
         * face is white, since the hidden real clock has no colour for it to take, so the face
         * wakes up white and takes its material while the plugin's morph turns the outline into
         * the fill.
         */
        private const val AOD_TRANSITION_MS = 500L
        private const val PLUGIN_MORPH_MS = 300L

        private val AOD_TRANSITION_INTERPOLATOR = PathInterpolator(0.4f, 0f, 0.2f, 1f)

        /**
         * The framework sends the wake command as waking starts; the keyguard's own transition
         * begins this much later, seen in its tree as the moment its clock's weight starts to change.
         */
        private const val KEYGUARD_WAKE_LAG_MS = 30L

        /** The keyguard's own transition to the always-on display is this far along when the sleep command arrives. */
        private const val KEYGUARD_SLEEP_LEAD_MS = 40L

        /** The clock plugin's own doze animation outlasts that slide. */
        private const val DOZE_ANIMATION_MS = 750L

        /** The keyguard dims the wallpaper with black at alpha 0.20; a clock drawn under it needs the inverse. */
        private const val SCRIM_COMPENSATION = 1.25f
        private const val DEFAULT_COLOR_ID = "DEFAULT"

        /**
         * The keyguard scales each face to nine tenths about the centre of its unscaled box;
         * accessibility reports the scaled box's top-left with the unscaled size. Read from
         * SystemUI's burn-in modifier.
         */
        private const val FACE_SCALE = 0.9f

        /**
         * SystemUI's burn-in protection on the always-on display: a vertical offset that is a
         * triangle wave of the wall-clock minute with SystemUI's own dimension as amplitude, and
         * for the large face a scale on another such wave in place of [FACE_SCALE]. The offset is
         * held above a floor only the keyguard knows, so the offset it applied is read from its
         * tree while it dozes; the wave stands in until the first doze.
         */
        private const val BURN_IN_OFFSET_DIMEN = "burn_in_prevention_offset_y"
        private const val BURN_IN_OFFSET_PERIOD_MINUTES = 271f
        private const val BURN_IN_SCALE_PERIOD_MINUTES = 181f
        private const val BURN_IN_MIN_SCALE = 0.75f
        private const val BURN_IN_SCALE_RANGE = 0.2f

        fun supports(clockId: String): Boolean = clockId in SUPPORTED_CLOCKS

        fun currentClockId(context: Context): String = HostedClock.currentClockId(context) ?: "DEFAULT"

        fun outlines(clockId: String): Boolean = clockId in OUTLINE_CLOCKS

        /** The time into the swap at which its eased timeline reaches [eased]. */
        fun swapElapsed(eased: Float): Long = elapsed(SWAP_INTERPOLATOR, eased, SWAP_MS)

        /** The time into a transition of [duration] at which [interpolator] reaches [eased]. */
        private fun elapsed(
            interpolator: TimeInterpolator,
            eased: Float,
            duration: Long,
        ): Long {
            var low = 0f
            var high = 1f
            repeat(16) {
                val mid = (low + high) / 2f
                if (interpolator.getInterpolation(mid) < eased) low = mid else high = mid
            }
            return (high * duration).toLong()
        }
    }

    private var hosted: HostedClock? = null
    private var hostedFor: String? = null
    private val surface = Point()

    /** Which face the keyguard shows and where, as measured through accessibility. */
    private var small = repository.getLockClockSmall()
    private var rect = Rect()

    /** The face fading out after the keyguard swapped clocks, for as long as [swapAnimator] runs. */
    private var previousSmall = false
    private var previousRect = Rect()
    private var swapAnimator: ValueAnimator? = null

    /** How far up the small face travels while it leaves, and from where it arrives; measured with the rest positions. */
    private var slide = 0

    /** How far above its rest the keyguard had the small face when the wallpaper joined the swap; the slide is played from there. */
    private var swapFrom = 0f

    private val burnInAmplitude = HostedClock.systemUiDimension(context, BURN_IN_OFFSET_DIMEN)?.roundToInt() ?: 0

    /** Where the always-on display has the face relative to its rest position, for the doze animation playing. */
    private var aodOffsetY = 0
    private var aodScale = 1f
    private var dozeAnimator: ValueAnimator? = null
    private var fallingAsleep = false

    /** A wake that interrupts the sleep joins it where it stands: this far along, this much faded. */
    private var wakeFrom = 0f
    private var fadeFrom = 1f
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    /**
     * The plugin renders in white, a mask the face's material is painted through: the colour the
     * keyguard would give the real clock, or the wallpaper clock's own dark-mode colour, brightened
     * against the keyguard's scrim and under white for as long as the always-on display's white
     * outline is turning into it.
     */
    private val layerPaint = Paint()
    private val fillPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }
    private val whitePaint =
        Paint().apply {
            color = Color.WHITE
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        }
    private val density = context.resources.displayMetrics.density
    private val glass = GlassMaterial(context)

    /** The slide and scale the layer draws a face through; the glass looks through them at the wallpaper where it lies on the screen. */
    private val placement = Matrix()
    private var hours = look(minutes = false)
    private var minutes = look(minutes = true)
    private var split = repository.getLockClockSplit()
    private var splitSmall = repository.getLockClockSplitSmall()

    /** The always-on display shows some clocks as an outline, which the wallpaper clock can keep on the lock screen. */
    private var outline = false

    /** The side-by-side comparison wants the clock in a colour no clock theme produces. */
    private var compare = repository.getLockClockCompare()

    val animating: Boolean
        get() = dozeAnimator != null || swapAnimator?.isRunning == true

    fun resize(
        width: Int,
        height: Int,
    ) {
        surface.set(width, height)
        place()
    }

    /** Hosts the keyguard's current clock unless it already is, and brings the time up to date. */
    fun sync() {
        val clockId = currentClockId(context)
        if (clockId != hostedFor) host(clockId)
        restyle()
        hosted?.tick()
    }

    /** Locale, zone and format reach the plugin at creation only, so a change means hosting again. */
    fun onTimeSettingsChanged() = drop()

    fun onPreferenceChanged(key: String) {
        when (key) {
            SettingsRepository.KEY_LOCK_CLOCK_COMPARE -> {
                compare = repository.getLockClockCompare()
                invalidate()
            }
            SettingsRepository.KEY_LOCK_CLOCK_SMALL,
            SettingsRepository.KEY_LOCK_CLOCK_RECT_LARGE,
            SettingsRepository.KEY_LOCK_CLOCK_RECT_SMALL,
            -> {
                place()
                invalidate()
            }
            SettingsRepository.KEY_LOCK_SCREEN_CLOCK_SEED_COLOR,
            SettingsRepository.KEY_LOCK_SCREEN_CLOCK_SELECTED_COLOR_ID,
            SettingsRepository.KEY_LOCK_CLOCK_DARK_VARIANT,
            SettingsRepository.KEY_LOCK_CLOCK_SPLIT,
            SettingsRepository.KEY_LOCK_CLOCK_SPLIT_SMALL,
            -> {
                restyle()
                invalidate()
            }
            SettingsRepository.KEY_LOCK_CLOCK_OUTLINE -> {
                outline = repository.getLockClockOutline() && outlines(hostedFor ?: return)
                hosted?.doze(if (outline) 1f else 0f)
                invalidate()
            }
            SettingsRepository.KEY_LOCK_SCREEN_CLOCK_WEIGHT,
            SettingsRepository.KEY_LOCK_SCREEN_CLOCK_WIDTH,
            SettingsRepository.KEY_LOCK_SCREEN_CLOCK_ROUNDNESS,
            -> drop()
            else ->
                if (SettingsRepository.isLockClockLookKey(key)) {
                    restyle()
                    invalidate()
                }
        }
    }

    /**
     * Plays the keyguard's hand-off from the always-on display: the plugin's own doze animation for
     * colour and stroke, and the keyguard's slide from where the always-on display had the face to
     * the lock position. The keyguard's slide began when the wake command arrived at [startedAt].
     * A wake that interrupts the sleep takes over from where the sleep stands, as the keyguard
     * reverses its own transition from there.
     */
    fun wake(
        locked: Boolean,
        startedAt: Long,
    ) {
        val displayState = displayManager.getDisplay(Display.DEFAULT_DISPLAY).state
        ClockLog.add(context, "wake: display $displayState, locked $locked, hosted ${hosted != null}, animator scale ${Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)}")
        val hosted = hosted ?: return
        val lag = (startedAt + KEYGUARD_WAKE_LAG_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        val interrupted = dozeAnimator?.takeIf { fallingAsleep }?.let { dozeProgress() }?.takeIf { it < 1f }
        stopDozeAnimation()
        if (!locked) {
            handler.postDelayed({ doze(0f) }, lag)
            return
        }
        fallingAsleep = false
        if (interrupted == null) placeAod()
        val joinAt = interrupted?.let { elapsed(AOD_TRANSITION_INTERPOLATOR, 1f - AOD_TRANSITION_INTERPOLATOR.getInterpolation(it), AOD_TRANSITION_MS) } ?: 0L
        wakeFrom = joinAt.toFloat() / AOD_TRANSITION_MS
        fadeFrom = 1f - (interrupted ?: 0f)
        startDozeAnimation(lag, joinAt)
    }

    /** The reverse: the face slides towards the always-on position and fades out under the real one turning white. */
    fun sleep(
        locked: Boolean,
        visible: Boolean,
    ) {
        val hosted = hosted ?: return
        stopDozeAnimation()
        if (!locked || !visible) {
            doze(1f)
            return
        }
        fallingAsleep = true
        placeAod()
        startDozeAnimation(0L, KEYGUARD_SLEEP_LEAD_MS)
    }

    /**
     * The animator times the transition, so the system's animator scale slows it like the
     * keyguard's; the keyguard's own lag before it starts is no animation and is waited out as is.
     */
    private fun startDozeAnimation(
        lag: Long,
        from: Long = 0L,
    ) {
        var frames = 0
        var last = -1f
        val animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = DOZE_ANIMATION_MS
                addUpdateListener {
                    val amount = dozeProgress().let { if (fallingAsleep) it else 1f - it }
                    frames++
                    last = amount
                    doze(amount)
                    invalidate()
                }
                doOnEnd {
                    ClockLog.add(context, "doze: ${if (fallingAsleep) "sleep" else "wake"} fed $frames amounts, last $last, ${if (dozeAnimator == null) "cancelled" else "ended"}")
                    stopDozeAnimation()
                    invalidate()
                }
            }
        dozeAnimator = animator
        handler.postDelayed({
            animator.start()
            animator.currentPlayTime = from
        }, lag)
        invalidate()
    }

    /** The doze amount the keyguard hands the real clock; an outline-only clock stays at the always-on end. */
    private fun doze(amount: Float) {
        if (!outline) hosted?.doze(amount)
    }

    private fun stopDozeAnimation() {
        handler.removeCallbacksAndMessages(null)
        dozeAnimator?.let {
            dozeAnimator = null
            it.cancel()
        }
    }

    /** The keyguard's transition to or from the always-on display: linear over its length, 0 until it starts. */
    private fun dozeProgress(): Float = ((dozeAnimator?.currentPlayTime ?: 0L).toFloat() / AOD_TRANSITION_MS).coerceIn(0f, 1f)

    /** The plugin's own morph between outline and fill, which starts with the transition and is linear. */
    private fun morphProgress(): Float = ((dozeAnimator?.currentPlayTime ?: 0L).toFloat() / PLUGIN_MORPH_MS).coerceIn(0f, 1f)

    /** The scale is relative to [FACE_SCALE]; the top read on the always-on display and the offset meet at the centre of the unscaled box, about which the keyguard scales. */
    private fun placeAod() {
        val minutes = System.currentTimeMillis() / 60_000f
        val burnInY = zigzag(minutes, burnInAmplitude.toFloat(), BURN_IN_OFFSET_PERIOD_MINUTES).toInt() * 2 - burnInAmplitude
        aodScale = if (small) 1f else (BURN_IN_MIN_SCALE + zigzag(minutes, BURN_IN_SCALE_RANGE, BURN_IN_SCALE_PERIOD_MINUTES)) / FACE_SCALE
        val seen = repository.getLockClockAodTop(small)
        aodOffsetY = if (seen == null) max(0, burnInY) else seen - rect.top - (rect.height() * (1f - aodScale) * FACE_SCALE / 2f).roundToInt()
        ClockLog.add(context, "aod: ${if (small) "small" else "large"} rest ${rect.toShortString()}, burn-in $burnInY, seen ${seen ?: "nothing"}, offset $aodOffsetY, scale $aodScale")
    }

    private fun zigzag(
        x: Float,
        amplitude: Float,
        period: Float,
    ): Float {
        val phase = x % period / (period / 2)
        return amplitude * if (phase <= 1f) phase else 2f - phase
    }

    /** [exit] is how far the keyguard's unlock exit has run, on its own curve; 1 is gone. */
    fun draw(
        canvas: Canvas,
        exit: Float,
    ) {
        val hosted = hosted ?: return
        if (exit >= 1f) return
        val alpha = 1f - exit
        canvas.save()
        canvas.translate(0f, -UNLOCK_RISE_DP * density * exit)
        placement.setTranslate(0f, -UNLOCK_RISE_DP * density * exit)
        if (dozeAnimator != null) {
            drawDozeTransition(canvas, hosted, alpha, dozeProgress())
        } else {
            tint(whiteness = 0f)
            val swap = swapAnimator?.takeIf { it.isRunning }
            if (swap == null) {
                drawFace(canvas, hosted, small, rect, alpha)
            } else {
                val time = swap.animatedFraction
                val eased = SWAP_INTERPOLATOR.getInterpolation(time)
                drawFace(canvas, hosted, previousSmall, previousRect, alpha * (1f - (time / SWAP_FADE_OUT_UNTIL).coerceIn(0f, 1f)), if (previousSmall) -(swapFrom + (slide - swapFrom) * eased) else 0f)
                drawFace(canvas, hosted, small, rect, alpha * ((time - SWAP_FADE_IN_FROM) / (1f - SWAP_FADE_IN_FROM)).coerceIn(0f, 1f), if (small) -swapFrom * (1f - eased) else 0f)
            }
        }
        canvas.restore()
    }

    /** The lock wallpaper as the engine draws it and where, for the glass to show through the digits. */
    fun setBackdrop(
        shown: Bitmap?,
        at: RectF?,
    ) = glass.setBackdrop(shown, at)

    fun release() {
        stopDozeAnimation()
        swapAnimator?.cancel()
        drop()
        glass.release()
    }

    private fun host(clockId: String) {
        hostedFor = clockId
        hosted =
            if (supports(clockId)) {
                try {
                    HostedClock.load(context, clockId, Color.WHITE, axes())
                } catch (t: Throwable) {
                    ClockLog.add(context, "host: $clockId failed, ${generateSequence(t) { it.cause }.last()}")
                    null
                }
            } else {
                null
            }
        val hosted = hosted ?: return
        outline = repository.getLockClockOutline() && outlines(clockId)
        if (outline) hosted.doze(1f)
        place()
    }

    private fun drop() {
        hosted = null
        hostedFor = null
    }

    /** One part's paint: its material, the two colours the theme in force gives it, whether it runs from one into the other, and the glass's frost. */
    private class Look(
        val material: String,
        val gradient: Boolean,
        val direction: String,
        val first: Int,
        val second: Int,
        val frost: Float,
    ) {
        val glass: Boolean
            get() = material == SettingsRepository.LOCK_CLOCK_MATERIAL_GLASS
    }

    private fun restyle() {
        hours = look(minutes = false)
        minutes = look(minutes = true)
        split = repository.getLockClockSplit()
        splitSmall = repository.getLockClockSplitSmall()
    }

    /** What the keyguard gives the real clock, or the wallpaper clock's own picks for the minutes and for dark mode. */
    private fun look(minutes: Boolean): Look {
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val own = dark && repository.getLockClockDarkVariant()
        val first =
            when {
                minutes && own -> slotColour(SettingsRepository.LOCK_CLOCK_SLOT_MINUTES_DARK, dark)
                minutes -> slotColour(SettingsRepository.LOCK_CLOCK_SLOT_MINUTES, dark)
                own -> slotColour(SettingsRepository.LOCK_CLOCK_SLOT_DARK, dark)
                else -> chosen(repository.getLockScreenClockSelectedColorId(), repository.getLockScreenClockSeedColor(), dark)
            }
        val second =
            when {
                minutes && own -> slotColour(SettingsRepository.LOCK_CLOCK_SLOT_MINUTES_SECOND_DARK, dark)
                minutes -> slotColour(SettingsRepository.LOCK_CLOCK_SLOT_MINUTES_SECOND, dark)
                own -> slotColour(SettingsRepository.LOCK_CLOCK_SLOT_SECOND_DARK, dark)
                else -> slotColour(SettingsRepository.LOCK_CLOCK_SLOT_SECOND, dark)
            }
        val part = SettingsRepository.lockClockPart(minutes, own)
        return Look(repository.getLockClockMaterial(part), repository.getLockClockGradient(part), repository.getLockClockGradientDirection(part), first, second, repository.getLockClockGlassFrost(part))
    }

    private fun slotColour(
        slot: String,
        dark: Boolean,
    ): Int = chosen(repository.getLockClockColorId(slot), repository.getLockClockSeedColor(slot), dark)

    /** A chosen seed, or the theme's accent for the default. */
    private fun chosen(
        colorId: String,
        seed: Int,
        dark: Boolean,
    ): Int = if (colorId != DEFAULT_COLOR_ID) seed else context.getColor(if (dark) android.R.color.system_accent1_100 else android.R.color.system_accent2_600)

    private fun tint(whiteness: Float) {
        whitePaint.alpha = (whiteness * 255).toInt()
    }

    /** Paints [look] over [area] of the layer, through the mask drawn there. */
    private fun fill(
        canvas: Canvas,
        area: RectF,
        look: Look,
    ) {
        fillPaint.color = if (compare) Color.BLUE else compensated(look.first)
        fillPaint.shader =
            when {
                compare -> null
                look.glass -> glass.shader(look.frost, colours(area, look), placement)
                look.gradient -> colours(area, look)
                else -> null
            }
        canvas.drawRect(area, fillPaint)
    }

    /** The part's colour across [area]: its first, running into its second when it is a gradient. */
    private fun colours(
        area: RectF,
        look: Look,
    ): LinearGradient {
        val endX = if (look.direction == SettingsRepository.LOCK_CLOCK_GRADIENT_DOWN) area.left else area.right
        val endY = if (look.direction == SettingsRepository.LOCK_CLOCK_GRADIENT_RIGHT) area.top else area.bottom
        return LinearGradient(area.left, area.top, endX, endY, compensated(look.first), compensated(if (look.gradient) look.second else look.first), Shader.TileMode.CLAMP)
    }

    private fun compensated(colour: Int): Int =
        Color.rgb(
            (Color.red(colour) * SCRIM_COMPENSATION).toInt().coerceAtMost(255),
            (Color.green(colour) * SCRIM_COMPENSATION).toInt().coerceAtMost(255),
            (Color.blue(colour) * SCRIM_COMPENSATION).toInt().coerceAtMost(255),
        )

    private fun axes(): Map<String, Float> =
        HostedClock.currentAxes(context)
            ?: mapOf(
                "wght" to repository.getLockScreenClockWeight().toFloat(),
                "wdth" to repository.getLockScreenClockWidth().toFloat(),
                "ROND" to repository.getLockScreenClockRoundness().toFloat(),
            )

    /**
     * Lays the hosted clock out where the keyguard puts the face it now shows; nothing is drawn
     * before that is measured. After a swap the old face fades out where it was and the new one
     * fades in where it is, the keyguard's whole swap fitted into what is left of it.
     */
    private fun place() {
        val hosted = hosted
        val nowSmall = repository.getLockClockSmall()
        val nowRect = repository.getLockClockRect(nowSmall) ?: Rect()
        slide = repository.getLockClockSwapSlide()
        val elapsed = repository.getLockClockSwapElapsed()
        if (hosted != null && nowSmall != small && isShowing()) {
            previousSmall = small
            previousRect = Rect(rect)
            val caughtAt = SWAP_INTERPOLATOR.getInterpolation(elapsed.toFloat() / SWAP_MS)
            swapFrom = slide * if (nowSmall) 1f - caughtAt else caughtAt
            swapAnimator?.cancel()
            swapAnimator =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = max(SWAP_MS - elapsed, SWAP_LEAST_MS)
                    interpolator = LinearInterpolator()
                    addUpdateListener { invalidate() }
                    doOnEnd { invalidate() }
                    start()
                }
        }
        small = nowSmall
        rect = nowRect
        hosted?.fit(small, rect)
        ClockLog.add(context, "place: ${if (small) "small" else "large"} at ${rect.toShortString()}${if (rect.isEmpty) " (unmeasured)" else ""}, painted ${hosted?.painted(small, FACE_SCALE)?.toShortString()}")
    }

    /**
     * The face slides by the always-on offset and, scaled about the centre of its unscaled box,
     * takes the always-on size on the way. On the wake the keyguard runs the scale through the
     * curve mirrored, so the offset is gone early and the size lingers.
     */
    private fun drawDozeTransition(
        canvas: Canvas,
        hosted: HostedClock,
        alpha: Float,
        linear: Float,
    ) {
        val fade =
            when {
                fallingAsleep -> 1f - linear
                fadeFrom < 1f -> fadeFrom + (1f - fadeFrom) * (linear - wakeFrom) / (1f - wakeFrom)
                else -> 1f
            }
        val progress = AOD_TRANSITION_INTERPOLATOR.getInterpolation(linear)
        val travel = if (fallingAsleep) progress else 1f - progress
        val sizeTravel = if (fallingAsleep) progress else AOD_TRANSITION_INTERPOLATOR.getInterpolation(1f - linear)
        val scale = 1f + (aodScale - 1f) * sizeTravel
        val morph = morphProgress()
        tint(whiteness = if (fallingAsleep) morph else 1f - morph)
        val pivotX = rect.left + rect.width() * FACE_SCALE / 2f
        val pivotY = rect.top + rect.height() * FACE_SCALE / 2f
        canvas.save()
        canvas.translate(0f, aodOffsetY * travel)
        canvas.scale(scale, scale, pivotX, pivotY)
        placement.preTranslate(0f, aodOffsetY * travel)
        placement.preScale(scale, scale, pivotX, pivotY)
        drawFace(canvas, hosted, small, rect, alpha * fade)
        canvas.restore()
    }

    private fun drawFace(
        canvas: Canvas,
        hosted: HostedClock,
        small: Boolean,
        rect: Rect,
        alpha: Float,
        offsetY: Float = 0f,
    ) {
        if (alpha <= 0f || rect.isEmpty) return
        val painted = hosted.painted(small, FACE_SCALE)
        val bounds = RectF(painted).apply { offset(rect.left.toFloat(), rect.top + offsetY) }
        val divide = if (split && (!small || splitSmall)) hosted.split(small) else null
        val parts =
            when {
                divide == null -> listOf(bounds to hours)
                small -> {
                    val x = bounds.left + divide * FACE_SCALE
                    listOf(RectF(bounds.left, bounds.top, x, bounds.bottom) to hours, RectF(x, bounds.top, bounds.right, bounds.bottom) to minutes)
                }
                else -> {
                    val y = bounds.top + divide * FACE_SCALE
                    listOf(RectF(bounds.left, bounds.top, bounds.right, y) to hours, RectF(bounds.left, y, bounds.right, bounds.bottom) to minutes)
                }
            }
        if (parts.any { it.second.glass }) glass.prepare(hosted, small, painted, bounds, FACE_SCALE)
        layerPaint.alpha = (alpha * 255).toInt()
        canvas.saveLayer(bounds, layerPaint)
        canvas.save()
        canvas.translate(rect.left.toFloat(), rect.top + offsetY)
        hosted.draw(canvas, small, FACE_SCALE)
        canvas.restore()
        for ((area, look) in parts) fill(canvas, area, look)
        if (whitePaint.alpha > 0) canvas.drawRect(bounds, whitePaint)
        canvas.restore()
    }
}
