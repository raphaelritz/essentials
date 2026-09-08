/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Background Services & Receivers
 * File: WidgetScraperService.kt
 * Description: Background service component for WidgetScraperService.kt.
 */

package com.sameerasw.essentials.services.widgets

import android.app.Service
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.SizeF
import android.view.View
import android.view.ViewTreeObserver
import android.widget.RemoteViews
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.services.NotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class WidgetScraperService : Service() {
    private inner class ScrapingHostView(
        context: Context,
    ) : AppWidgetHostView(context) {
        private val drawListener =
            ViewTreeObserver.OnDrawListener {
                notifyWidgetChanged()
            }

        private val layoutListener =
            ViewTreeObserver.OnGlobalLayoutListener {
                notifyWidgetChanged()
            }

        override fun updateAppWidget(remoteViews: RemoteViews?) {
            super.updateAppWidget(remoteViews)
            if (remoteViews != null) onRemoteViewsReceived(remoteViews)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            viewTreeObserver.addOnDrawListener(drawListener)
            viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            setOnHierarchyChangeListener(
                object : OnHierarchyChangeListener {
                    override fun onChildViewAdded(
                        parent: View?,
                        child: View?,
                    ) {
                        notifyWidgetChanged()
                    }

                    override fun onChildViewRemoved(
                        parent: View?,
                        child: View?,
                    ) {
                        notifyWidgetChanged()
                    }
                },
            )
        }

        override fun onDetachedFromWindow() {
            viewTreeObserver.removeOnDrawListener(drawListener)
            viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
            setOnHierarchyChangeListener(null)
            super.onDetachedFromWindow()
        }
    }

    private inner class ScrapingWidgetHost(
        context: Context,
        hostId: Int,
    ) : AppWidgetHost(context, hostId) {
        override fun onCreateView(
            context: Context,
            appWidgetId: Int,
            appWidget: AppWidgetProviderInfo?,
        ): AppWidgetHostView = ScrapingHostView(context)
    }

    companion object {
        const val HOST_ID = 1025

        private const val TAG = "WidgetScraper"

        private const val NOTIFICATION_CHANNEL_ID = "pixel_searchbar_scraper"

        private const val NOTIFICATION_ID = 8421

        /** Fallback searchbar height in dp, used until the Glance widget reports its real size. */
        private const val FALLBACK_HEIGHT_DP = 56

        /** Fallback horizontal inset in dp applied to the screen width for the same reason. */
        private const val FALLBACK_HORIZONTAL_INSET_DP = 32

        /** Floor for the advertised width, so we never advertise a degenerate size. */
        private const val MIN_ADVERTISED_WIDTH_DP = 64

        @Volatile
        var currentRemoteViews: RemoteViews? = null
            private set

        fun start(context: Context) {
            context.startService(Intent(context, WidgetScraperService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WidgetScraperService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settingsRepository: SettingsRepository
    private var appWidgetHost: ScrapingWidgetHost? = null
    private var hostView: AppWidgetHostView? = null
    private var isForeground = false
    private val handler = Handler(Looper.getMainLooper())

    /**
     * The most recent RemoteViews exactly as the provider sent it, kept so the correct variant can
     * be re-resolved when the searchbar size changes without waiting for the next provider update.
     */
    private var rawRemoteViews: RemoteViews? = null

    /**
     * The Glance widget records its measured size as it renders; that is the only place the real
     * searchbar dimensions are known. Picking the change up here avoids starting the service from
     * a background render pass.
     */
    private val hostSizeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SettingsRepository.KEY_PIXEL_SEARCHBAR_WIDGET_HOST_WIDTH ||
                key == SettingsRepository.KEY_PIXEL_SEARCHBAR_WIDGET_HOST_HEIGHT ||
                key == SettingsRepository.KEY_PIXEL_SEARCHBAR_WIDGET_WIDTH_OVERRIDE ||
                key == SettingsRepository.KEY_PIXEL_SEARCHBAR_WIDGET_HEIGHT_OVERRIDE
            ) {
                handler.post { onHostSizeChanged() }
            }
        }

    // Music playback tracking components
    private var mediaSessionManager: MediaSessionManager? = null
    private var currentController: MediaController? = null
    private var musicBroadcastReceiver: MusicBroadcastReceiver? = null

    private val mediaCallback =
        object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                updateMediaMetadata(currentController)
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                updateMediaMetadata(currentController)
            }
        }

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            updateActiveSession(controllers)
        }

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        settingsRepository.registerOnSharedPreferenceChangeListener(hostSizeListener)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val type = settingsRepository.getPixelSearchbarType()
        if (type == "widget" || type == "music") {
            // Without this the process is killed between updates, and the provider's push then only
            // reaches us after Android gets around to restarting the sticky service — which is why a
            // widget change took a minute or more to show up while the launcher's own copy was
            // instant. A hosted widget only receives updates while its host is alive.
            if (settingsRepository.getPixelSearchbarKeepAlive()) enterForeground() else exitForeground()
        }
        if (type == "widget") {
            bindAndListenWidget()
        } else if (type == "music") {
            listenToMusicSession()
        } else {
            exitForeground()
            stopSelf()
        }
        return START_STICKY
    }

    /**
     * Promotes the scraper to a foreground service so the widget host stays alive and provider
     * updates land immediately. Falls back silently when the platform refuses the promotion.
     */
    private fun enterForeground() {
        if (isForeground) return
        try {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager?.createNotificationChannel(
                android.app.NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(com.sameerasw.essentials.R.string.pixel_searchbar_keep_alive_channel),
                    android.app.NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                },
            )

            val tapIntent =
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, com.sameerasw.essentials.ui.activities.PixelSearchbarSettingsActivity::class.java),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                )

            val notification =
                androidx.core.app.NotificationCompat
                    .Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(com.sameerasw.essentials.R.drawable.rounded_search_24)
                    .setContentTitle(getString(com.sameerasw.essentials.R.string.pixel_searchbar_keep_alive_title))
                    .setContentText(getString(com.sameerasw.essentials.R.string.pixel_searchbar_keep_alive_text))
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setContentIntent(tapIntent)
                    .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (t: Throwable) {
            Log.w(TAG, "Could not run in the foreground; updates may lag", t)
        }
    }

    private fun exitForeground() {
        if (!isForeground) return
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        isForeground = false
    }

    private fun bindAndListenWidget() {
        // Clear any media components
        cleanupMediaListener()

        // Repeated starts would otherwise leak the previous host; keep the last scrape so the
        // searchbar does not blink back to the placeholder while rebinding.
        releaseWidgetHost()

        val widgetId = settingsRepository.getPixelSearchbarWidgetId()
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            stopSelf()
            return
        }

        val awm = AppWidgetManager.getInstance(this)
        val info =
            awm.getAppWidgetInfo(widgetId) ?: run {
                stopSelf()
                return
            }

        val host = ScrapingWidgetHost(this, HOST_ID)
        appWidgetHost = host

        // Create and register the host view before listening starts. startListening replays the
        // provider's cached views to views the host already knows about, so registering first
        // removes any chance of missing that first snapshot.
        val view = host.createView(this, widgetId, info)
        hostView = view
        host.startListening()
        applyHostSize(view, widgetId)
    }

    /**
     * Returns the size the scraped widget is actually drawn at, in dp.
     *
     * The Glance widget publishes its measured size as it renders. Until that has happened once,
     * fall back to a searchbar-shaped estimate rather than to zero.
     */
    private fun hostSizeDp(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        val screenWidthDp = (metrics.widthPixels / metrics.density).toInt()
        val fallbackWidth = (screenWidthDp - FALLBACK_HORIZONTAL_INSET_DP).coerceAtLeast(MIN_ADVERTISED_WIDTH_DP)

        // A manual override wins per axis, then the size the Glance widget measured, then a
        // searchbar-shaped estimate for the render that has not happened yet.
        val width =
            settingsRepository
                .getPixelSearchbarWidgetWidthOverride()
                .takeIf { it > 0 }
                ?: settingsRepository.getPixelSearchbarWidgetHostWidth().takeIf { it > 0 }
                ?: fallbackWidth
        val height =
            settingsRepository
                .getPixelSearchbarWidgetHeightOverride()
                .takeIf { it > 0 }
                ?: settingsRepository.getPixelSearchbarWidgetHostHeight().takeIf { it > 0 }
                ?: FALLBACK_HEIGHT_DP
        return width to height
    }

    /**
     * Tells the hosted widget how much room it has.
     *
     * The widget is bound by the picker and then hosted off-screen, so without this its options
     * bundle stays empty and every size-aware provider reads a width and height of zero. They then
     * send back their most collapsed layout — Firefox answers with its icon-only variant, and
     * providers that build sized RemoteViews emit their smallest one.
     *
     * A single exact size is advertised on purpose: a provider that keys its RemoteViews by the
     * host's advertised sizes then produces exactly one variant, which is what the nested-RemoteViews
     * replay in the Glance widget can actually render.
     */
    private fun applyHostSize(
        view: AppWidgetHostView,
        widgetId: Int,
    ) {
        val (widthDp, heightDp) = hostSizeDp()
        val options =
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
                putInt(
                    AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                    AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    putParcelableArrayList(
                        AppWidgetManager.OPTION_APPWIDGET_SIZES,
                        arrayListOf(SizeF(widthDp.toFloat(), heightDp.toFloat())),
                    )
                }
            }

        // Updating the options makes the provider push a fresh, correctly sized update.
        runCatching { AppWidgetManager.getInstance(this).updateAppWidgetOptions(widgetId, options) }

        // The host view is never attached to a window, so it has no size of its own to report.
        runCatching { view.updateAppWidgetSize(options, widthDp, heightDp, widthDp, heightDp) }

        val density = resources.displayMetrics.density
        val widthPx = (widthDp * density).toInt()
        val heightPx = (heightDp * density).toInt()
        runCatching {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, widthPx, heightPx)
        }
    }

    /**
     * Re-advertises the size after the Glance widget reports a new measurement, and re-resolves the
     * layout already in hand so the searchbar corrects itself without waiting for the provider.
     */
    private fun onHostSizeChanged() {
        if (settingsRepository.getPixelSearchbarType() != "widget") return
        val view = hostView ?: return
        applyHostSize(view, settingsRepository.getPixelSearchbarWidgetId())
        rawRemoteViews?.let {
            currentRemoteViews = resolveForHostSize(it)
            notifyWidgetChanged()
        }
    }

    /**
     * Picks the variant of [remoteViews] matching the space the searchbar actually has.
     *
     * A provider may answer with a RemoteViews carrying several layouts — a landscape/portrait pair,
     * or a map keyed by size. The Glance widget nests whatever it is handed, and a nested RemoteViews
     * is applied without size information, so the framework falls back to the first variant, which is
     * the smallest. Resolving here hands the launcher a single, correctly sized layout instead.
     *
     * There is no public API for this, so it degrades to the original RemoteViews when unavailable —
     * no worse than not trying.
     */
    private fun resolveForHostSize(remoteViews: RemoteViews): RemoteViews {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return remoteViews
        val (widthDp, heightDp) = hostSizeDp()
        val resolved =
            try {
                // getRemoteViewsToApply is a blocked non-SDK method, so plain reflection is both
                // refused by the platform and flagged by lint. Go through HiddenApiBypass, which
                // the app already initialises in EssentialsApp.onCreate and uses elsewhere.
                org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(
                    RemoteViews::class.java,
                    remoteViews,
                    "getRemoteViewsToApply",
                    this,
                    SizeF(widthDp.toFloat(), heightDp.toFloat()),
                ) as? RemoteViews
            } catch (t: Throwable) {
                Log.w(TAG, "RemoteViews variant resolution unavailable; replaying as sent", t)
                null
            } ?: return remoteViews

        if (resolved === remoteViews) return remoteViews

        // The returned variant is a child of a live hierarchy. Detach it with the copy constructor
        // so nesting it in the Glance tree does not re-point the original's caches.
        return runCatching { RemoteViews(resolved) }.getOrElse {
            Log.w(TAG, "could not detach resolved variant; using it directly", it)
            resolved
        }
    }

    private fun listenToMusicSession() {
        // Clear any widget hosting components
        cleanupWidgetListener()

        try {
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            mediaSessionManager = manager
            if (manager != null) {
                val componentName = ComponentName(this, NotificationListener::class.java)
                val initialSessions = manager.getActiveSessions(componentName)
                updateActiveSession(initialSessions)
                manager.addOnActiveSessionsChangedListener(activeSessionsListener, componentName)
            }
        } catch (_: Exception) {
        }

        // Dynamically register the MusicBroadcastReceiver
        try {
            if (musicBroadcastReceiver == null) {
                val receiver = MusicBroadcastReceiver()
                musicBroadcastReceiver = receiver
                val filter =
                    android.content.IntentFilter().apply {
                        addAction("com.android.music.metadatachanged")
                        addAction("com.android.music.playstatechanged")
                        addAction("com.android.music.playbackcomplete")
                        addAction("com.android.music.queuechanged")
                        addAction("com.spotify.music.metadatachanged")
                        addAction("com.spotify.music.playbackstatechanged")
                        addAction("com.htc.music.metadatachanged")
                        addAction("com.real.music.metadatachanged")
                        addAction("com.sonyericsson.music.metadatachanged")
                        addAction("com.sec.android.app.music.metadatachanged")
                        addAction("com.sec.android.app.music.playstatechanged")
                        addAction("com.miui.player.metadatachanged")
                    }
                androidx.core.content.ContextCompat.registerReceiver(
                    this,
                    receiver,
                    filter,
                    androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun updateActiveSession(controllers: List<MediaController>?) {
        val active =
            controllers
                ?.sortedWith(
                    compareByDescending<MediaController> {
                        val state = it.playbackState?.state
                        state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                    }.thenByDescending {
                        val state = it.playbackState?.state
                        state == PlaybackState.STATE_PAUSED
                    },
                )?.firstOrNull()

        if (active != currentController) {
            currentController?.unregisterCallback(mediaCallback)
            currentController = active
            active?.registerCallback(mediaCallback)
            updateMediaMetadata(active)
        }
    }

    private fun updateMediaMetadata(controller: MediaController?) {
        if (controller == null) return
        val metadata = controller.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val packageName = controller.packageName

        val artwork =
            metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        val filesDirFile = File(filesDir, "music_artwork.png")
        if (artwork != null) {
            try {
                FileOutputStream(filesDirFile).use { out ->
                    artwork.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (_: Exception) {
            }
        } else {
            if (filesDirFile.exists()) filesDirFile.delete()
        }

        settingsRepository.setPixelSearchbarMusicTitle(title)
        settingsRepository.setPixelSearchbarMusicArtist(artist)
        settingsRepository.setPixelSearchbarMusicPackage(packageName)
        notifyWidgetChanged()
    }

    private fun onRemoteViewsReceived(remoteViews: RemoteViews) {
        // Keep our own copy: the host view retains this instance, and nesting it in a Glance tree
        // mutates it in place (configureAsChild re-points its caches at the enclosing root).
        val own = runCatching { RemoteViews(remoteViews) }.getOrDefault(remoteViews)
        rawRemoteViews = own
        currentRemoteViews = resolveForHostSize(own)
        notifyWidgetChanged()
    }

    private var updatePending = false

    private fun notifyWidgetChanged() {
        if (updatePending) return
        updatePending = true

        handler.postDelayed({
            updatePending = false
            settingsRepository.incrementPixelSearchbarWidgetRevision()

            serviceScope.launch {
                runCatching {
                    val manager =
                        androidx.glance.appwidget.GlanceAppWidgetManager(this@WidgetScraperService)
                    val widget = PixelSearchbarWidget()
                    val glanceIds = manager.getGlanceIds(PixelSearchbarWidget::class.java)
                    for (glanceId in glanceIds) widget.update(this@WidgetScraperService, glanceId)
                }
            }
        }, 100L)
    }

    /** Tears the host down but keeps the last scrape, for rebinding without a visible gap. */
    private fun releaseWidgetHost() {
        appWidgetHost?.stopListening()
        appWidgetHost = null
        hostView = null
    }

    private fun cleanupWidgetListener() {
        releaseWidgetHost()
        rawRemoteViews = null
        currentRemoteViews = null
    }

    private fun cleanupMediaListener() {
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener)
        } catch (_: Exception) {
        }
        currentController?.unregisterCallback(mediaCallback)
        currentController = null
        mediaSessionManager = null

        // Dynamic unregistration
        try {
            musicBroadcastReceiver?.let {
                unregisterReceiver(it)
            }
            musicBroadcastReceiver = null
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        settingsRepository.unregisterOnSharedPreferenceChangeListener(hostSizeListener)
        handler.removeCallbacksAndMessages(null)
        cleanupWidgetListener()
        cleanupMediaListener()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
