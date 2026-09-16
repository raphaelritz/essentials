/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Background Services & Receivers
 * File: LockClockHandler.kt
 * Description: Follows which clock face the keyguard shows and where, for the clock the wallpaper draws.
 */

package com.sameerasw.essentials.services.handlers

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.utils.ClockLog
import com.sameerasw.essentials.utils.LockClockLayer
import com.sameerasw.essentials.utils.LockScreenClockLocator
import kotlinx.coroutines.android.asCoroutineDispatcher

/**
 * The keyguard's view tree is the only source of truth for the face it shows and where. SystemUI
 * reports every change to that tree while the keyguard is up, and each report ends in a read of
 * the faces: on the lock screen it tells a swap, on the always-on display where the face sits.
 * Where each face rests is measured once by [LockClockMeasurement] and kept until the layout changes.
 */
class LockClockHandler(
    private val service: AccessibilityService,
) {
    companion object {
        /** SystemUI sends a report for every change in its tree; the faces are read at most this often. */
        private const val CHECK_INTERVAL_MS = 50L

        /** The keyguard keeps a swap's outgoing face in its tree a little past the swap's end, before it is torn down. */
        private const val OUTGOING_LINGER_MS = 300L
    }

    private val repository = SettingsRepository(service)

    /** Reads wait on SystemUI; on the main thread they would stall the wallpaper's frames. */
    private val worker = Handler(HandlerThread("lock-clock").apply { start() }.looper)
    private val keyguardManager = service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private val powerManager = service.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val display = service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    private val measurement = LockClockMeasurement(service, repository, worker.asCoroutineDispatcher(), { locked })

    /** The delay the service declares for events, kept from before the keyguard is listened to; then events must not wait. */
    private var idleEventDelay = 0L

    @Volatile private var screenOn = true

    @Volatile private var checkPending = false

    @Volatile private var checkAt = 0L

    @Volatile private var changedWindowId = 0

    /**
     * When the keyguard's current swap ends. Until then its tree is being rebuilt and a read can
     * miss a face that is there, so the swap is decided on the read that caught it and reads that
     * disagree are let pass.
     */
    private var swapEndsAt = 0L
    private var lastReport = ""

    private val enabled: Boolean
        get() = repository.getLockClockInWallpaper()

    private val locked: Boolean
        get() = keyguardManager.isKeyguardLocked

    /** SystemUI's tree changed under the keyguard, in the window that holds the faces; only a subtree change can be a swap. */
    fun onSystemUiChanged(
        changeTypes: Int,
        windowId: Int,
    ) {
        if (!locked) {
            subscribe(false)
            return
        }
        if (changeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE == 0) return
        changedWindowId = windowId
        requestCheck()
    }

    fun onScreenOn() {
        screenOn = true
        if (enabled && locked) subscribe(true)
        measurement.onScreenOn()
    }

    /**
     * The always-on display swaps faces too, so the reports stay on for as long as the keyguard is
     * up, from the first frame of the doze: the reads date the doze for the always-on position.
     */
    fun onScreenOff() {
        screenOn = false
        if (enabled && locked) subscribe(true)
        measurement.onScreenOff()
    }

    fun destroy() {
        measurement.destroy()
        worker.looper.quitSafely()
    }

    /** Measures where the keyguard puts each face; see [LockClockMeasurement]. */
    fun measure() = measurement.start(screenOn)

    private fun requestCheck() {
        if (checkPending) return
        checkPending = true
        worker.postDelayed(check, (checkAt + CHECK_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }

    /**
     * During the keyguard's crossfade both faces are in the tree, the one not shown yet arriving;
     * afterwards only the shown face is. The small face's slide tells how far into the swap the
     * keyguard is. Only the swap to the small face is joined late, since a lingering large face
     * would sit under the notification that called for it. Both faces read right after a joined
     * swap are its outgoing face not yet torn down, not a swap back.
     */
    private val check =
        Runnable {
            checkPending = false
            checkAt = SystemClock.uptimeMillis()
            val windowId = changedWindowId
            val faces = LockScreenClockLocator.faces(service, windowId)
            val readAt = SystemClock.uptimeMillis()
            val shown = repository.getLockClockSmall()
            val both = faces.small != null && faces.large != null
            val small =
                when {
                    both -> !shown
                    faces.small != null -> true
                    faces.large != null -> false
                    else -> return@Runnable
                }
            val midSwap = faces.small != null && (both || small != shown)
            val elapsed = if (midSwap) swapElapsed(faces.small!!, entering = small) else 0L
            val report =
                "faces: $faces in window $windowId, shown ${name(shown)}, ${faces.walked} nodes in ${readAt - checkAt} ms" +
                    if (midSwap) ", swap $elapsed ms in" else ""
            if (report != lastReport) log(report)
            lastReport = report
            if ((faces.small == null || faces.large == null) && dozing()) noteAodTop(small, (faces.small ?: faces.large)!!.top)
            if (readAt < swapEndsAt + (if (both) OUTGOING_LINGER_MS else 0L)) return@Runnable
            if (midSwap) swapEndsAt = readAt + ((LockClockLayer.SWAP_MS - elapsed) * animatorScale()).toLong()
            setFace(small, if (small) elapsed else 0L)
        }

    /**
     * The keyguard keeps its tree while the display is dark too, and passes through a dozing
     * display state on its way there, but a dark display shows no face to read.
     */
    private fun dozing(): Boolean = !powerManager.isInteractive && display.state != Display.STATE_OFF

    /**
     * Where the always-on display holds the face. The keyguard's burn-in protection puts it on a
     * wave of the wall clock, no lower than a floor only the keyguard knows, so the position is
     * taken from its tree at every dozing read; the last read before a wake is where the face was,
     * at rest or mid-slide.
     */
    private fun noteAodTop(
        small: Boolean,
        top: Int,
    ) {
        if (repository.getLockClockAodTop(small) == top) return
        repository.setLockClockAodTop(small, top)
        log("aod: ${name(small)} seen at $top")
    }

    /** The developer option that slows every animation, the keyguard's swap included. */
    private fun animatorScale(): Float = Settings.Global.getFloat(service.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

    /** Where the keyguard's slide has the small face, as a point on the swap's timeline. */
    private fun swapElapsed(
        seen: Rect,
        entering: Boolean,
    ): Long {
        val rest = repository.getLockClockRect(true) ?: return 0L
        val slide = repository.getLockClockSwapSlide()
        if (slide <= 0) return 0L
        val away = (rest.top - (seen.bottom - rest.height())).toFloat() / slide
        return LockClockLayer.swapElapsed((if (entering) 1f - away else away).coerceIn(0f, 1f))
    }

    /** Content-change reports cost a delivery each, so SystemUI's are only asked for, and undelayed, while the keyguard is up. */
    private fun subscribe(on: Boolean) {
        val info = service.serviceInfo ?: return
        val types = if (on) info.eventTypes or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED else info.eventTypes and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED.inv()
        if (types == info.eventTypes) return
        if (on) idleEventDelay = info.notificationTimeout
        info.eventTypes = types
        info.notificationTimeout = if (on) 0L else idleEventDelay
        service.serviceInfo = info
        log("subscribe ${if (on) "on" else "off"}: types now ${Integer.toHexString(service.serviceInfo?.eventTypes ?: -1)}, event delay ${info.notificationTimeout} ms")
    }

    private fun setFace(
        small: Boolean,
        elapsed: Long,
    ) {
        if (repository.getLockClockSmall() == small) return
        repository.setLockClockSwapElapsed(elapsed)
        repository.setLockClockSmall(small)
    }

    private fun name(small: Boolean): String = if (small) "small" else "large"

    private fun log(line: String) = ClockLog.add(service, line)
}
