/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Utilities
 * File: WallpaperImages.kt
 * Description: The images the Essentials wallpaper draws, and what the system shows where.
 */

package com.sameerasw.essentials.utils

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import com.sameerasw.essentials.services.UnifiedWallpaperService
import java.io.File

/**
 * The lock and home images live in app storage, cropped to the screen once, so every surface shows
 * the same framing and a blur change never compounds. The system's own wallpaper can be copied in,
 * which is how the phone keeps its look when Essentials takes over drawing it.
 */
object WallpaperImages {
    private const val LOCK_FILE = "wallpaper_lock.jpg"
    private const val HOME_FILE = "wallpaper_home.jpg"
    private const val AOD_CUSTOM_FILE = "custom_aod_wallpaper.png"
    private const val LOCK_DEPTH_FILE = "wallpaper_lock_depth.png"
    private const val JPEG_QUALITY = 95

    /** Which screens the Essentials wallpaper is set on. */
    enum class Coverage { NONE, HOME_ONLY, LOCK_ONLY, BOTH }

    fun lockFile(context: Context): File = File(context.filesDir, LOCK_FILE)

    fun homeFile(context: Context): File = File(context.filesDir, HOME_FILE)

    fun aodCustomFile(context: Context): File = File(context.filesDir, AOD_CUSTOM_FILE)

    /** The lock photo's depth map, cropped as the photo was, so the two line up whatever resolution the map came in. */
    fun lockDepthFile(context: Context): File = File(context.filesDir, LOCK_DEPTH_FILE)

    fun coverage(context: Context): Coverage {
        val manager = WallpaperManager.getInstance(context)
        val home = isEssentials(manager, WallpaperManager.FLAG_SYSTEM)
        val lock = isEssentials(manager, resolve(manager, WallpaperManager.FLAG_LOCK))
        return when {
            home && lock -> Coverage.BOTH
            home -> Coverage.HOME_ONLY
            lock -> Coverage.LOCK_ONLY
            else -> Coverage.NONE
        }
    }

    fun drawsLock(context: Context): Boolean = coverage(context).let { it == Coverage.LOCK_ONLY || it == Coverage.BOTH }

    /** Whether the system shows a live wallpaper there, which has no image to copy. */
    fun isLive(
        context: Context,
        which: Int,
    ): Boolean {
        val manager = WallpaperManager.getInstance(context)
        return info(manager, resolve(manager, which)) != null
    }

    /**
     * A screen-sized copy of the image the system shows there, or null for a live wallpaper or
     * without the storage permission the system wants for reading it.
     */
    fun captureSystem(
        context: Context,
        which: Int,
    ): Bitmap? {
        if (!PermissionUtils.hasManageExternalStoragePermission(context)) return null
        val manager = WallpaperManager.getInstance(context)
        val resolved = resolve(manager, which)
        if (info(manager, resolved) != null) return null
        val drawable = manager.getDrawable(resolved) ?: return null
        val bitmap =
            (drawable as? BitmapDrawable)?.bitmap
                ?: Bitmap.createBitmap(drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                    drawable.setBounds(0, 0, it.width, it.height)
                    drawable.draw(Canvas(it))
                }
        return cropToScreen(context, bitmap)
    }

    /** Decodes a picked image down to at most twice the display's longest edge, which keeps a 100MP photo from taking the process down. */
    fun decode(
        context: Context,
        uri: Uri,
    ): Bitmap? {
        val metrics = context.resources.displayMetrics
        val target = maxOf(metrics.widthPixels, metrics.heightPixels) * 2
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, target, target)
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    /**
     * Decodes a stored image down to roughly the surface size, in the surface's own colour space:
     * a wide-gamut photo drawn onto the sRGB surface would otherwise be converted pixel by pixel on
     * every frame.
     */
    fun decodeForSurface(
        file: File,
        width: Int,
        height: Int,
    ): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, width, height)
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            },
        )
    }

    /** Centre-crops and scales to exactly the display size, so every surface shows the same framing. */
    fun cropToScreen(
        context: Context,
        source: Bitmap,
    ): Bitmap {
        val metrics = context.resources.displayMetrics
        val targetWidth = metrics.widthPixels
        val targetHeight = metrics.heightPixels
        if (source.width == targetWidth && source.height == targetHeight) return source
        val scale = maxOf(targetWidth.toFloat() / source.width, targetHeight.toFloat() / source.height)
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(targetWidth)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(targetHeight)
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val cropped = Bitmap.createBitmap(scaled, (scaledWidth - targetWidth) / 2, (scaledHeight - targetHeight) / 2, targetWidth, targetHeight)
        if (scaled !== cropped && scaled !== source) scaled.recycle()
        return cropped
    }

    /** Where [bitmap] lands when it covers a [width] by [height] surface: centred, with no horizontal travel. */
    fun cover(
        bitmap: Bitmap,
        width: Int,
        height: Int,
    ): RectF {
        val scale = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val scaledWidth = bitmap.width * scale
        val scaledHeight = bitmap.height * scale
        val left = -(scaledWidth - width) / 2f
        val top = -(scaledHeight - height) / 2f
        return RectF(left, top, left + scaledWidth, top + scaledHeight)
    }

    fun save(
        file: File,
        bitmap: Bitmap,
    ) {
        val format = if (file.extension == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        file.outputStream().use { bitmap.compress(format, JPEG_QUALITY, it) }
    }

    private fun sampleSize(
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        var sample = 1
        while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2
        return sample
    }

    /** A lock screen without a wallpaper of its own shows the home screen's. */
    private fun resolve(
        manager: WallpaperManager,
        which: Int,
    ): Int = if (which == WallpaperManager.FLAG_LOCK && manager.getWallpaperId(WallpaperManager.FLAG_LOCK) < 0) WallpaperManager.FLAG_SYSTEM else which

    private fun isEssentials(
        manager: WallpaperManager,
        which: Int,
    ): Boolean = info(manager, which)?.serviceName == UnifiedWallpaperService::class.java.name

    private fun info(
        manager: WallpaperManager,
        which: Int,
    ) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) manager.getWallpaperInfo(which) else manager.wallpaperInfo
}
