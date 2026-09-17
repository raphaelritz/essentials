/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Background Services & Receivers
 * File: LockClockMeasurement.kt
 * Description: Measures where the keyguard puts each clock face, by forcing each face in turn on a covered lock screen.
 */

package com.sameerasw.essentials.services.handlers

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.sameerasw.essentials.R
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.utils.ClockLog
import com.sameerasw.essentials.utils.HostedClock
import com.sameerasw.essentials.utils.LockClockLayer
import com.sameerasw.essentials.utils.LockScreenClockLocator
import com.sameerasw.essentials.utils.LockScreenClockSize
import com.sameerasw.essentials.utils.SecureSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Where the keyguard puts each face at rest is a constant of the device's layout. It is measured
 * by forcing the small face and then the large one through the settings that decide between them,
 * on a lock screen covered so that nothing on it is touched or read mid-swipe. The phone locks and
 * wakes itself for it; if it cannot wake itself, the next wake finishes the measurement.
 */
class LockClockMeasurement(
    private val service: AccessibilityService,
    private val repository: SettingsRepository,
    reader: CoroutineDispatcher,
    private val locked: () -> Boolean,
) {
    companion object {
        private const val RELAYOUT_POLL_MS = 50L
        private const val RELAYOUT_TIMEOUT_MS = 2000L

        /** Waking within about half a second of the lock catches the keyguard mid-layout, with the clock too high. */
        private const val WAKE_AFTER_LOCK_MS = 2000L
        private const val WAKE_LOCK_MS = 5000L
        private const val ATTEMPTS = 3

        /** The keyguard swaps faces in 300 ms; the small face on its way out is followed for longer, in case the keyguard is slow to start. */
        private const val SLIDE_SAMPLE_MS = 16L
        private const val SLIDE_TRACK_MS = 600L
        private const val COVER_ALPHA = 235

        /** A measurement takes about eight seconds; the cover never outlives one by much, whatever happens to it. */
        private const val COVER_TIMEOUT_MS = 20_000L
        private const val COVER_PADDING_DP = 32f
        private const val COVER_TEXT_SP = 18f

        /** The transition from the always-on display takes 500 ms; the measurement reads once the keyguard is at rest. */
        private const val SETTLE_MS = 1000L

        /** What the keyguard's layout of [clockId] depends on; a change means measuring again. */
        fun layoutKey(
            context: Context,
            clockId: String = HostedClock.currentClockId(context),
        ): String {
            val metrics = context.resources.displayMetrics
            val axes = if (LockClockLayer.followsAxes(clockId)) HostedClock.currentAxes(context)?.let { "/${it["wght"]}/${it["wdth"]}" }.orEmpty() else ""
            return "${metrics.widthPixels}x${metrics.heightPixels}@${metrics.densityDpi}/${context.resources.configuration.fontScale}/${Build.FINGERPRINT}$axes"
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val powerManager = service.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val scope = CoroutineScope(reader + Job())

    @Volatile private var pending = false

    @Volatile private var attempts = 0
    private var wakeAfterLock = false
    private var cover: View? = null
    private val hideCoverRunnable = Runnable { hideCover() }

    init {
        restoreSettingsIfLeft()
    }

    fun start(screenOn: Boolean) {
        if (!SecureSettings.canWrite(service)) return
        pending = true
        attempts = 0
        showCover()
        if (screenOn && locked()) {
            scope.launch { run() }
            return
        }
        wakeAfterLock = true
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
    }

    /** The lock screen is up again; a measurement still wanted goes on once the keyguard is at rest. */
    fun onScreenOn() {
        if (pending && locked()) {
            scope.launch {
                delay(SETTLE_MS)
                run()
            }
        }
    }

    fun onScreenOff() {
        if (!wakeAfterLock) return
        wakeAfterLock = false
        handler.postDelayed(::wakeUp, WAKE_AFTER_LOCK_MS)
    }

    fun destroy() {
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        hideCover()
    }

    private fun wakeUp() {
        @Suppress("DEPRECATION")
        powerManager
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "essentials:lockclock")
            .acquire(WAKE_LOCK_MS)
    }

    private suspend fun run() {
        pending = false
        log("measure: start, attempt ${attempts + 1}")
        if (attempts++ >= ATTEMPTS) {
            handler.post(::hideCover)
            return
        }
        handler.post(::showCover)
        // The two-line setting cannot be read by apps, only written; the app's own size is its value.
        val doubleLine = if (repository.getLockScreenClockSize() == LockScreenClockSize.SMALL) 0 else 1
        val showNotifications = Settings.Secure.getInt(service.contentResolver, LockScreenClockSize.SHOW_NOTIFICATIONS, 1)
        val showMedia = runCatching { Settings.Secure.getInt(service.contentResolver, LockScreenClockSize.SHOW_MEDIA, 1) }.getOrDefault(1)
        repository.setLockClockSettingsToRestore("$doubleLine,$showNotifications,$showMedia")
        if (!write(LockScreenClockSize.DOUBLE_LINE_CLOCK, 0)) return
        val small = awaitFace(small = true)
        if (!write(LockScreenClockSize.DOUBLE_LINE_CLOCK, 1) || !write(LockScreenClockSize.SHOW_NOTIFICATIONS, 0) || !write(LockScreenClockSize.SHOW_MEDIA, 0)) return
        val slide = trackSlide(small)
        val large = awaitFace(small = false)
        restoreSettingsIfLeft()
        store(small, large, slide)
    }

    /** A failed write stops the measurement and puts the user's settings back. */
    private fun write(
        key: String,
        value: Int,
    ): Boolean {
        if (SecureSettings.putInt(service, key, value)) {
            log("measure: wrote $key=$value")
            return true
        }
        log("measure: could not write $key")
        restoreSettingsIfLeft()
        handler.post(::hideCover)
        return false
    }

    /** The settings the measurement forces, put back if the process died in the middle of it. */
    private fun restoreSettingsIfLeft() {
        val values = repository.getLockClockSettingsToRestore()?.split(',')?.mapNotNull { it.toIntOrNull() } ?: return
        repository.clearLockClockSettingsToRestore()
        if (values.size != 3) return
        SecureSettings.putInt(service, LockScreenClockSize.DOUBLE_LINE_CLOCK, values[0])
        SecureSettings.putInt(service, LockScreenClockSize.SHOW_NOTIFICATIONS, values[1])
        SecureSettings.putInt(service, LockScreenClockSize.SHOW_MEDIA, values[2])
    }

    /** Polls until the keyguard shows the asked-for face alone, in the same place twice, or gives up. */
    private suspend fun awaitFace(small: Boolean): Rect? {
        val deadline = SystemClock.uptimeMillis() + RELAYOUT_TIMEOUT_MS
        var previous: Rect? = null
        while (true) {
            val faces = LockScreenClockLocator.faces(service)
            val rect = if (small) faces.small?.takeIf { faces.large == null } else faces.large?.takeIf { faces.small == null }
            if (rect != previous) log("measure: waiting for ${name(small)}, see $faces")
            if ((rect != null && rect == previous) || SystemClock.uptimeMillis() > deadline) return rect
            previous = rect
            delay(RELAYOUT_POLL_MS)
        }
    }

    /**
     * The keyguard moves its smartspace cards when it swaps faces and slides the small face along
     * with them, out of the screen's top when the large face arrives. The face is followed at
     * frame rate until the swap is over; the farthest it got is the slide, zero where the keyguard
     * has no cards.
     */
    private suspend fun trackSlide(small: Rect?): Int {
        if (small == null) return 0
        val deadline = SystemClock.uptimeMillis() + SLIDE_TRACK_MS
        var farthest = 0
        while (SystemClock.uptimeMillis() <= deadline) {
            LockScreenClockLocator.faces(service).small?.let { seen -> farthest = max(farthest, small.top - (seen.bottom - small.height())) }
            delay(SLIDE_SAMPLE_MS)
        }
        return farthest
    }

    private fun store(
        small: Rect?,
        large: Rect?,
        slide: Int,
    ) {
        if (small == null || large == null) {
            log("measure: failed, small ${small?.toShortString() ?: "off"}, large ${large?.toShortString() ?: "off"}")
            pending = true
            return
        }
        repository.setLockClockRect(true, small)
        repository.setLockClockRect(false, large)
        repository.setLockClockSwapSlide(slide)
        repository.setLockClockLayoutKey(layoutKey(service))
        log("measure: stored small ${small.toShortString()}, large ${large.toShortString()}, slide $slide")
        handler.post(::hideCover)
    }

    private fun showCover() {
        handler.removeCallbacks(hideCoverRunnable)
        handler.postDelayed(hideCoverRunnable, COVER_TIMEOUT_MS)
        if (cover != null) return
        val padding = (COVER_PADDING_DP * service.resources.displayMetrics.density).toInt()
        val view =
            LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.argb(COVER_ALPHA, 0, 0, 0))
                addView(ProgressBar(service))
                addView(
                    TextView(service).apply {
                        setText(R.string.lock_clock_measuring)
                        setTextColor(Color.WHITE)
                        textSize = COVER_TEXT_SP
                        gravity = Gravity.CENTER
                        setPadding(padding, padding, padding, padding)
                    },
                )
            }
        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        service.getSystemService(WindowManager::class.java).addView(view, params)
        cover = view
    }

    private fun hideCover() {
        handler.removeCallbacks(hideCoverRunnable)
        val view = cover ?: return
        service.getSystemService(WindowManager::class.java).removeView(view)
        cover = null
    }

    private fun name(small: Boolean): String = if (small) "small" else "large"

    private fun log(line: String) = ClockLog.add(service, line)
}
