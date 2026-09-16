/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Utilities
 * File: LockScreenClockLocator.kt
 * Description: Finds the keyguard's clock faces in its accessibility tree, the one view of SystemUI open to another app.
 */

package com.sameerasw.essentials.utils

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.Calendar

/**
 * The keyguard's tree is small, a few dozen nodes on the lock screen, and it is walked from the
 * root of the window that reported a change. The faces carry no resource ids SystemUI's own
 * lookups can resolve, so they are known by what they announce: the current time, and nothing
 * else.
 */
object LockScreenClockLocator {
    /**
     * A node whose whole announcement is a time, as opposed to a notification mentioning one. The
     * small clock announces "12 04", the large one "12:04".
     */
    private val TIME_PATTERN = Regex("""^\s*(\d{1,2})[\s:.]+(\d{2})(\s*[AaPp]\.?[Mm]\.?)?\s*$""")
    private const val CLOCK_SEARCH_FRACTION = 0.6f

    /** The small clock is one text line high; the large one spans a good part of the screen. */
    private const val SMALL_CLOCK_HEIGHT_FRACTION = 0.2f

    /** The small clock sits at the very top; a time chip further down is something else. */
    private const val SMALL_CLOCK_TOP_FRACTION = 0.2f

    /** Both faces span a good part of the width; the shade's and the status bar's clocks do not. */
    private const val MIN_CLOCK_WIDTH_FRACTION = 0.3f
    private const val MAX_DEPTH = 8
    private const val MAX_NODES = 800

    /**
     * SystemUI prefetches descendants in the same round trip only while its main thread is idle,
     * unless told not to stop; during a swap a walk would otherwise cost a round trip per node.
     */
    private const val PREFETCH = AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID or AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE

    class Faces(
        val small: Rect?,
        val large: Rect?,
        val walked: Int,
    ) {
        val any: Boolean
            get() = small != null || large != null

        override fun toString(): String = "small ${small?.toShortString() ?: "off"}, large ${large?.toShortString() ?: "off"}"
    }

    /** The faces in the window that reported a change; both are present only while the keyguard crossfades them. */
    fun faces(
        service: AccessibilityService,
        windowId: Int,
    ): Faces = service.windows.firstOrNull { it.id == windowId }?.let(::root)?.let { faces(service, it) } ?: Faces(null, null, 0)

    /** For the measurement, with no report to name the window: the first window that holds a face. */
    fun faces(service: AccessibilityService): Faces = service.windows.mapNotNull(::root).map { faces(service, it) }.firstOrNull { it.any } ?: Faces(null, null, 0)

    private fun root(window: AccessibilityWindowInfo): AccessibilityNodeInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) window.getRoot(PREFETCH) else window.root

    private fun child(
        node: AccessibilityNodeInfo,
        index: Int,
    ): AccessibilityNodeInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) node.getChild(index, PREFETCH) else node.getChild(index)

    private fun faces(
        service: AccessibilityService,
        root: AccessibilityNodeInfo,
    ): Faces {
        val metrics = service.resources.displayMetrics
        val now = Calendar.getInstance()
        val walked = mutableListOf<AccessibilityNodeInfo>()
        collect(root, 0, (metrics.heightPixels * CLOCK_SEARCH_FRACTION).toInt(), walked)
        val clocks =
            walked
                .filter { it.isVisibleToUser && showsCurrentTime(it, now) }
                .map(::bounds)
                .filter { it.width() > metrics.widthPixels * MIN_CLOCK_WIDTH_FRACTION }
        return Faces(
            small = clocks.firstOrNull { it.height() < metrics.heightPixels * SMALL_CLOCK_HEIGHT_FRACTION && it.top < metrics.heightPixels * SMALL_CLOCK_TOP_FRACTION },
            large = clocks.firstOrNull { it.height() >= metrics.heightPixels * SMALL_CLOCK_HEIGHT_FRACTION && it.top < metrics.heightPixels * CLOCK_SEARCH_FRACTION },
            walked = walked.size,
        )
    }

    /** Subtrees that start below where a clock can be are not entered. */
    private fun collect(
        node: AccessibilityNodeInfo?,
        depth: Int,
        maxTop: Int,
        out: MutableList<AccessibilityNodeInfo>,
    ) {
        if (node == null || depth > MAX_DEPTH || out.size >= MAX_NODES) return
        if (bounds(node).top > maxTop) return
        out += node
        for (i in 0 until node.childCount) collect(runCatching { child(node, i) }.getOrNull(), depth + 1, maxTop, out)
    }

    /**
     * An alarm chip or a calendar entry announces a time too; only the clock announces the current
     * one. The minute before still counts, for a tree read across a tick.
     */
    private fun showsCurrentTime(
        node: AccessibilityNodeInfo,
        now: Calendar,
    ): Boolean {
        val match = announcement(node)?.let { TIME_PATTERN.matchEntire(it) } ?: return false
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        val hour24 = now.get(Calendar.HOUR_OF_DAY)
        val hour12 = now.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        val currentMinute = now.get(Calendar.MINUTE)
        return (hour == hour24 || hour == hour12) && (minute == currentMinute || minute == (currentMinute + 59) % 60)
    }

    private fun announcement(node: AccessibilityNodeInfo): String? =
        node.text?.toString()?.takeIf { it.isNotBlank() } ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }

    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }
}
