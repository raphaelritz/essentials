/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Background Services & Receivers
 * File: UnifiedWallpaperService.kt
 * Description: Live wallpaper drawing the Essentials images for the home and lock screen, their blur and the lock screen clock.
 */

package com.sameerasw.essentials.services

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.utils.LockClockLayer
import com.sameerasw.essentials.utils.WallpaperBlurUtil
import com.sameerasw.essentials.utils.WallpaperImages

/**
 * One engine set for both screens. The lock look is the lock image, its blur and the hosted clock;
 * the home look is the home image and its blur; unlocking crossfades between them. The system has
 * no way to blur a static wallpaper and cuts hard between two wallpaper windows at unlock, which is
 * why one engine draws both screens from images kept on disk.
 */
class UnifiedWallpaperService : WallpaperService() {
    companion object {
        /** The stretch of the keyguard's unlock transition in which it fades its lock screen out. */
        private const val UNLOCK_ANIMATION_MS = 150L
        private const val BOTH_SCREENS = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        private val FILTER_PAINT = Paint(Paint.FILTER_BITMAP_FLAG)
    }

    override fun onCreateEngine(): Engine = WallpaperEngine()

    inner class WallpaperEngine : Engine() {
        private lateinit var repository: SettingsRepository
        private lateinit var clock: LockClockLayer
        private val keyguardManager by lazy { applicationContext.getSystemService(KEYGUARD_SERVICE) as KeyguardManager }

        private var lockSource: Bitmap? = null
        private var homeSource: Bitmap? = null
        private var lockBlurred: Bitmap? = null
        private var homeBlurred: Bitmap? = null
        private var lockBlurredFor = -1f
        private var homeBlurredFor = -1f
        private var surfaceWidth = 0
        private var surfaceHeight = 0

        /** 0 is the lock look, 1 the home look. */
        private var unlockProgress = 0f
        private var unlockAnimator: ValueAnimator? = null
        private val crossfadePaint = Paint(FILTER_PAINT)

        private val fastestRefreshRate = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).supportedModes.maxOf { it.refreshRate }
        private var votedRefreshRate = 0f

        private val prefsListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                when (key) {
                    SettingsRepository.KEY_WALLPAPER_REVISION,
                    SettingsRepository.KEY_WALLPAPER_HOME_IMAGE,
                    -> reload()
                    SettingsRepository.KEY_WALLPAPER_LOCK_BLUR,
                    SettingsRepository.KEY_WALLPAPER_HOME_BLUR,
                    -> draw()
                    SettingsRepository.KEY_LOCK_CLOCK_IN_WALLPAPER,
                    -> {
                        if (!repository.getLockClockInWallpaper()) clock.release()
                        syncLook()
                        draw()
                    }
                    else -> key?.let(clock::onPreferenceChanged)
                }
            }

        private val timeReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    if (intent.action != Intent.ACTION_TIME_TICK) clock.onTimeSettingsChanged()
                    if (!servesLock || !isVisible) return
                    if (clockWanted) clock.sync()
                    draw()
                }
            }

        /** Before Android 14 a live wallpaper is one engine for both screens. */
        private val flags: Int
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) wallpaperFlags else BOTH_SCREENS

        private val locked: Boolean
            get() = keyguardManager.isKeyguardLocked

        /** Whether this engine is the one the keyguard shows, and so plays the lock look. */
        private val servesLock: Boolean
            get() = flags and WallpaperManager.FLAG_LOCK != 0

        /** The clock needs one engine on both screens: two wallpaper windows would cut at unlock. */
        private val clockWanted: Boolean
            get() = flags == BOTH_SCREENS && repository.getLockClockInWallpaper()

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            repository = SettingsRepository(applicationContext)
            clock = LockClockLayer(applicationContext, repository, isShowing = { isVisible && locked }, invalidate = ::draw)
            repository.registerOnSharedPreferenceChangeListener(prefsListener)
            ContextCompat.registerReceiver(
                applicationContext,
                timeReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_TIME_TICK)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
            // The images are stored cropped to the screen, so there is nothing to slide with the launcher.
            setOffsetNotificationsEnabled(false)
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            clock.resize(width, height)
            syncLook()
            reload()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            surfaceWidth = 0
            surfaceHeight = 0
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (!visible) {
                stopWatchingKeyguard()
                return
            }
            syncLook()
            draw()
        }

        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean,
        ): Bundle? {
            when (action) {
                "android.wallpaper.wakingup" -> {
                    val startedAt = SystemClock.uptimeMillis()
                    syncLook()
                    if (clockWanted) clock.wake(locked, startedAt)
                    draw()
                }
                "android.wallpaper.goingtosleep" -> {
                    stopWatchingKeyguard()
                    if (clockWanted) clock.sleep(locked, isVisible)
                }
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        /**
         * Puts the engine in the look the keyguard state calls for. A wake that skips the always-on
         * display raises no visibility change, which is why the waking command also lands here.
         */
        private fun syncLook() {
            if (!servesLock) return
            if (locked) {
                unlockAnimator?.cancel()
                unlockProgress = 0f
                if (clockWanted) clock.sync()
                watchKeyguard()
            } else if (unlockAnimator?.isRunning != true) {
                unlockProgress = 1f
            }
        }

        /**
         * The keyguard's going-away command and the locked-state listener both need a signature
         * permission, so while the lock screen is up the keyguard state is read once per frame; the
         * flip lands within a frame of the real clock starting its exit.
         */
        private fun watchKeyguard() {
            stopWatchingKeyguard()
            Choreographer.getInstance().postFrameCallback(keyguardFrame)
        }

        private fun stopWatchingKeyguard() = Choreographer.getInstance().removeFrameCallback(keyguardFrame)

        private val keyguardFrame =
            object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (locked) {
                        Choreographer.getInstance().postFrameCallback(this)
                        return
                    }
                    animateUnlock()
                }
            }

        private fun animateUnlock() {
            unlockAnimator?.cancel()
            unlockAnimator =
                ValueAnimator.ofFloat(unlockProgress, 1f).apply {
                    duration = UNLOCK_ANIMATION_MS
                    interpolator = LockClockLayer.UNLOCK_EXIT_INTERPOLATOR
                    addUpdateListener {
                        unlockProgress = it.animatedValue as Float
                        draw()
                    }
                    doOnEnd { draw() }
                    start()
                }
        }

        private fun reload() {
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return
            recycleImages()
            lockSource = WallpaperImages.decodeForSurface(WallpaperImages.lockFile(applicationContext), surfaceWidth, surfaceHeight)
            homeSource =
                if (repository.getWallpaperHomeImage() == SettingsRepository.WALLPAPER_IMAGE_LOCK) {
                    lockSource
                } else {
                    WallpaperImages.decodeForSurface(WallpaperImages.homeFile(applicationContext), surfaceWidth, surfaceHeight) ?: lockSource
                }
            draw()
        }

        private fun recycleImages() {
            lockBlurred?.recycle()
            homeBlurred?.recycle()
            lockBlurred = null
            homeBlurred = null
            lockBlurredFor = -1f
            homeBlurredFor = -1f
            if (homeSource !== lockSource) homeSource?.recycle()
            lockSource?.recycle()
            homeSource = null
            lockSource = null
        }

        private fun rerenderIfNeeded() {
            val lockBlur = repository.getWallpaperLockBlur()
            if (lockBlur != lockBlurredFor) {
                lockBlurred?.recycle()
                lockBlurred = lockSource?.let { WallpaperBlurUtil.blur(it, lockBlur) }
                lockBlurredFor = lockBlur
            }
            val homeBlur = repository.getWallpaperHomeBlur()
            if (homeBlur != homeBlurredFor) {
                homeBlurred?.recycle()
                homeBlurred = homeSource?.let { WallpaperBlurUtil.blur(it, homeBlur) }
                homeBlurredFor = homeBlur
            }
        }

        private fun draw() {
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return
            rerenderIfNeeded()
            voteRefreshRate()
            val canvas = surfaceHolder.lockHardwareCanvas() ?: return
            try {
                drawLooks(canvas)
            } finally {
                surfaceHolder.unlockCanvasAndPost(canvas)
            }
        }

        private fun drawLooks(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            val home = homeBlurred ?: homeSource
            if (!servesLock) {
                home?.let { canvas.drawBitmap(it, null, destinationFor(it), FILTER_PAINT) }
                return
            }
            val lock = lockBlurred ?: lockSource
            lock?.let { canvas.drawBitmap(it, null, destinationFor(it), FILTER_PAINT) }
            if (unlockProgress > 0f && home != null && home !== lock) {
                crossfadePaint.alpha = (unlockProgress * 255).toInt()
                canvas.drawBitmap(home, null, destinationFor(home), crossfadePaint)
            }
            if (clockWanted) {
                clock.draw(canvas, unlockProgress)
            }
        }

        /** The panel is asked for its fastest rate only while something of ours animates. */
        private fun voteRefreshRate() {
            val rate = if (unlockAnimator?.isRunning == true || clock.animating) fastestRefreshRate else 0f
            if (rate == votedRefreshRate || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            surfaceHolder.surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
            votedRefreshRate = rate
        }

        /** Centre-crops the bitmap over the surface, with no horizontal travel. */
        private fun destinationFor(bitmap: Bitmap): RectF {
            val scale = maxOf(surfaceWidth.toFloat() / bitmap.width, surfaceHeight.toFloat() / bitmap.height)
            val scaledWidth = bitmap.width * scale
            val scaledHeight = bitmap.height * scale
            val left = -(scaledWidth - surfaceWidth) / 2f
            val top = -(scaledHeight - surfaceHeight) / 2f
            return RectF(left, top, left + scaledWidth, top + scaledHeight)
        }

        override fun onDestroy() {
            unlockAnimator?.cancel()
            stopWatchingKeyguard()
            clock.release()
            applicationContext.unregisterReceiver(timeReceiver)
            repository.unregisterOnSharedPreferenceChangeListener(prefsListener)
            recycleImages()
            super.onDestroy()
        }
    }
}
