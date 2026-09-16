/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Utilities
 * File: HostedClock.kt
 * Description: Google's own lock screen clock, loaded from its plugin APK and drawn by the wallpaper.
 */

package com.sameerasw.essentials.utils

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.icu.util.Calendar
import android.icu.util.TimeZone
import android.provider.Settings
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import dalvik.system.PathClassLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.Executor

/**
 * The Pixel clocks are plugin APKs that only SystemUI may load, but nothing stops another process
 * from loading them the same way: a class loader over SystemUI's APK as host, the plugin APK as
 * child; the default clock is SystemUI's own provider and comes from the host alone. The clock
 * then renders into any canvas, which is how the wallpaper gets the real glyphs instead of a
 * look-alike. Both faces are kept, since the keyguard swaps the large clock for the small one
 * whenever a notification is showing.
 */
class HostedClock private constructor(
    private val large: Face,
    private val small: Face,
) {
    private class Face(
        val view: View,
        val events: Any,
        val animations: Any?,
        val textSize: Float,
        /** SystemUI's own faces sit in a frame of the keyguard's that wraps them, so their width is their content's; a plugin's face is laid out at the region. */
        val wrapped: Boolean,
    ) {
        val region = Rect()
        val measured = Point()

        /** Where hours and minutes part, along the face's axis, in view pixels; found on demand and forgotten on layout. */
        var split: Float? = null
        val doze = animations?.javaClass?.methods?.first { it.name == "doze" && it.parameterCount == 1 }
    }

    companion object {
        /** The one-line face is drawn this much smaller to find the gap between its hours and minutes. */
        private const val GAP_SCAN_DOWNSCALE = 4
        private const val PLUGIN_ACTION = "com.android.systemui.action.PLUGIN_CLOCK_PROVIDER"
        private const val SYSTEM_UI = "com.android.systemui"
        private const val CLOCK_FACE_SETTING = "lock_screen_custom_clock_face"
        private const val DEFAULT_CLOCK = "DEFAULT"
        private const val LARGE_CLOCK_TEXT_SIZE = "large_clock_text_size"
        private const val SMALL_CLOCK_TEXT_SIZE = "small_clock_text_size"
        private const val API_PACKAGE = "com.android.systemui.plugins.keyguard.ui.clocks"
        private const val LOGCAT_BUFFER = "com.android.systemui.log.core.LogcatOnlyMessageBuffer"
        private const val DEFAULT_PROVIDER = "com.android.systemui.shared.clocks.DefaultClockProvider"
        private const val TIME_KEEPER = "com.android.systemui.customization.clocks.TimeKeeperImpl"
        private const val FUNCTION = "kotlin.jvm.functions.Function0"

        /**
         * The only packages a plugin is meant to share with its host. SystemUI filters its own
         * class loader down to these when parenting a plugin, so the plugin's bundled Kotlin and
         * AndroidX win over SystemUI's R8-stripped copies; a plain parent-first chain fails on that.
         */
        private val HOST_PREFIXES = listOf("com.android.systemui.plugin", "com.android.systemui.log", "com.android.systemui.common")

        /** The clock the keyguard is set to, read from the setting SystemUI itself reads; its own default when the setting names none. */
        fun currentClockId(context: Context): String =
            runCatching { JSONObject(Settings.Secure.getString(context.contentResolver, CLOCK_FACE_SETTING)).getString("clockId").ifEmpty { DEFAULT_CLOCK } }
                .getOrDefault(DEFAULT_CLOCK)

        /** The font axes the keyguard applies to the real clock, straight from the same setting. */
        fun currentAxes(context: Context): Map<String, Float>? =
            runCatching {
                val json = Settings.Secure.getString(context.contentResolver, CLOCK_FACE_SETTING) ?: return null
                val array = JSONObject(json).optJSONArray("axes") ?: return null
                (0 until array.length()).associate { i ->
                    val axis = array.getJSONObject(i)
                    axis.getString("key") to axis.getDouble("value").toFloat()
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() }

        /**
         * The plugin API's own enum for twelve or twenty-four hours; its formatters default to twelve
         * until told otherwise, and a boolean does not reach them.
         */
        private fun timeFormatKind(
            host: ClassLoader,
            is24Hour: Boolean,
        ): Any? =
            runCatching {
                host.loadClass("$API_PACKAGE.TimeFormatKind").getField(if (is24Hour) "FULL_DAY" else "HALF_DAY").get(null)
            }.getOrNull()

        /**
         * Loads the provider of [clockId], SystemUI's own or a plugin's, and creates both of its
         * faces. Null when nothing provides it or when SystemUI lacks the text sizes the keyguard
         * lays the faces out with.
         */
        fun load(
            context: Context,
            clockId: String,
            seedColor: Int?,
            axes: Map<String, Float>,
        ): HostedClock? {
            val pm = context.packageManager
            val host = PathClassLoader(systemUiPaths(context).joinToString(File.pathSeparator), ClassLoader.getSystemClassLoader())
            val systemUi = context.createPackageContext(SYSTEM_UI, 0)
            val builtIn = builtInProvider(host, systemUi)
            if (provides(builtIn, clockId)) return create(systemUi, builtIn, host, clockId, seedColor, axes, wrapped = true)
            val services =
                pm
                    .queryIntentServices(Intent(PLUGIN_ACTION), PackageManager.MATCH_ALL)
                    .sortedByDescending { clockId.lowercase().contains(it.serviceInfo.packageName.substringAfterLast('.')) }
            for (resolved in services) {
                val info = resolved.serviceInfo
                val loader = PluginClassLoader(pm.getApplicationInfo(info.packageName, 0).sourceDir, host)
                val provider = loader.loadClass(info.name).getDeclaredConstructor().newInstance()
                invoke(provider, "onCreate", context, PluginContext(context.createPackageContext(info.packageName, 0), loader))
                if (!provides(provider, clockId)) {
                    runCatching { invoke(provider, "onDestroy") }
                    continue
                }
                return create(context, provider, host, clockId, seedColor, axes, wrapped = false)
            }
            return null
        }

        private fun provides(
            provider: Any,
            clockId: String,
        ): Boolean = (invoke(provider, "getClocks") as List<*>).any { invoke(it!!, "getClockId") == clockId }

        /**
         * SystemUI's own provider is injected rather than constructed: it wants SystemUI's resources
         * and a factory for its time keeper, and its faces read SystemUI's dimensions through the
         * context they are created with, hence [systemUi] here and for the clock's creation.
         */
        private fun builtInProvider(
            host: ClassLoader,
            systemUi: Context,
        ): Any {
            val provider = allocate(host.loadClass(DEFAULT_PROVIDER))
            provider.javaClass.getField("resources").set(provider, systemUi.resources)
            provider.javaClass.getField("timeKeeperFactory").set(provider, Proxy.newProxyInstance(host, arrayOf(host.loadClass(FUNCTION))) { _, _, _ -> timeKeeper(host) })
            return provider
        }

        private fun timeKeeper(host: ClassLoader): Any =
            allocate(host.loadClass(TIME_KEEPER)).also {
                it.javaClass.getField("calendar").set(it, Calendar.getInstance())
                it.javaClass.getField("callbacks").set(it, ArrayList<Any>())
            }

        /**
         * R8 removed the empty constructors of SystemUI's injected classes and moved their field
         * initialisers into the callers, so such a class is only ever allocated, never constructed;
         * its fields are filled afterwards, as SystemUI's own code does.
         */
        private fun allocate(cls: Class<*>): Any {
            val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, cls)!!
        }

        private fun create(
            context: Context,
            provider: Any,
            host: ClassLoader,
            clockId: String,
            seedColor: Int?,
            axes: Map<String, Float>,
            wrapped: Boolean,
        ): HostedClock? {
            val largeTextSize = systemUiDimension(context, LARGE_CLOCK_TEXT_SIZE) ?: return null
            val smallTextSize = systemUiDimension(context, SMALL_CLOCK_TEXT_SIZE) ?: return null
            // SystemUI initialises a provider before asking it for clocks; that is where it builds
            // the asset loader createClock relies on.
            callLargest(provider, "initialize") { type ->
                when {
                    type.isAssignableFrom(Executor::class.java) -> Executor { it.run() }
                    type.simpleName == "ClockMessageBuffers" -> messageBuffers(type, host)
                    type.isInterface -> noOpProxy(type)
                    else -> null
                }
            }
            val settings = clockSettings(host, clockId, seedColor, axes)
            val clock =
                callLargest(provider, "createClock") { type ->
                    when {
                        type.isAssignableFrom(Context::class.java) -> context
                        type.simpleName == "ClockSettings" -> settings
                        else -> newWithDefaults(type)
                    }
                }!!
            val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            invoke(clock, "initialize", dark, 0f, 0f)
            val timeFormat = timeFormatKind(host, DateFormat.is24HourFormat(context))
            runCatching { invoke(clock, "getEvents") }.getOrNull()?.let { events ->
                runCatching { invoke(events, "onLocaleChanged", Locale.getDefault()) }
                runCatching { invoke(events, "onTimeZoneChanged", TimeZone.getDefault()) }
                timeFormat?.let { runCatching { invoke(events, "onTimeFormatChanged", it) } }
            }
            return HostedClock(
                face(invoke(clock, "getLargeClock")!!, largeTextSize, timeFormat, wrapped),
                face(invoke(clock, "getSmallClock")!!, smallTextSize, timeFormat, wrapped),
            )
        }

        /**
         * The keyguard hands a face its font size and time format once, at setup: the plugin
         * answers each with its style applied afresh and unanimated, which would cut short a
         * doze morph under way.
         */
        private fun face(
            controller: Any,
            textSize: Float,
            timeFormat: Any?,
            wrapped: Boolean,
        ): Face {
            val view =
                runCatching { invoke(controller, "getView") as View }
                    .getOrElse { (invoke(invoke(controller, "getLayout")!!, "getViews") as List<*>).first() as View }
            val events = invoke(controller, "getEvents")!!
            invoke(events, "onFontSettingChanged", textSize)
            timeFormat?.let { runCatching { invoke(events, "onTimeFormatChanged", it) } }
            return Face(view, events, runCatching { invoke(controller, "getAnimations") }.getOrNull(), textSize, wrapped)
        }

        /**
         * SystemUI's own parser of the clock setting is the one constructor R8 is certain to have
         * kept, since the clock registry calls it; the JSON is the same shape Essentials writes.
         */
        private fun clockSettings(
            host: ClassLoader,
            clockId: String,
            seedColor: Int?,
            axes: Map<String, Float>,
        ): Any {
            val cls = host.loadClass("$API_PACKAGE.ClockSettings")
            val json = JSONObject().put("clockId", clockId)
            seedColor?.let { json.put("seedColor", it) }
            json.put("axes", JSONArray().also { array -> for ((key, value) in axes) array.put(JSONObject().put("key", key).put("value", value.toInt())) })
            runCatching { cls.getField("Companion").get(null) }.getOrNull()?.let { companion ->
                companion.javaClass.methods
                    .firstOrNull { it.name == "deserialize" && it.parameterCount == 1 }
                    ?.invoke(companion, json.toString())
                    ?.let { return it }
            }
            val synthetic = cls.constructors.firstOrNull { it.parameterCount == 5 && it.parameterTypes.last().simpleName == "DefaultConstructorMarker" }
            if (synthetic != null) return synthetic.newInstance(clockId, seedColor, axisStyle(synthetic.parameterTypes[2], axes), 0, null)
            val ctor =
                cls.constructors.first {
                    it.parameterCount == 3 && it.parameterTypes[0] == String::class.java && it.parameterTypes[1] == Int::class.javaObjectType
                }
            return ctor.newInstance(clockId, seedColor, axisStyle(ctor.parameterTypes[2], axes))
        }

        /** The axis style has had a map, a JSON array and a default-argument constructor across builds. */
        private fun axisStyle(
            cls: Class<*>,
            axes: Map<String, Float>,
        ): Any {
            cls.constructors.firstOrNull { it.parameterCount == 1 && it.parameterTypes[0] == Map::class.java }?.let { return it.newInstance(axes) }
            cls.constructors.firstOrNull { it.parameterCount == 1 && it.parameterTypes[0] == JSONArray::class.java }?.let {
                val array = JSONArray()
                for ((key, value) in axes) array.put(JSONObject().put("key", key).put("value", value.toDouble()))
                return it.newInstance(array)
            }
            cls.constructors
                .firstOrNull { it.parameterTypes.lastOrNull()?.simpleName == "DefaultConstructorMarker" && it.parameterTypes[0] == Map::class.java }
                ?.let { return it.newInstance(axes, 0, null) }
            return newWithDefaults(cls)
        }

        fun systemUiDimension(
            context: Context,
            name: String,
        ): Float? =
            runCatching {
                val resources = context.createPackageContext(SYSTEM_UI, 0).resources
                val id = resources.getIdentifier(name, "dimen", SYSTEM_UI)
                if (id == 0) null else resources.getDimension(id)
            }.getOrNull()

        private fun systemUiPaths(context: Context): List<String> {
            val info = context.packageManager.getApplicationInfo(SYSTEM_UI, PackageManager.GET_SHARED_LIBRARY_FILES)
            return listOf(info.sourceDir) + info.splitSourceDirs.orEmpty() + info.sharedLibraryFiles.orEmpty()
        }

        private fun messageBuffers(
            type: Class<*>,
            host: ClassLoader,
        ): Any {
            val ctor = type.constructors.first { it.parameterTypes.all { p -> p.simpleName == "MessageBuffer" } }
            val buffer = messageBuffer(ctor.parameterTypes.first(), host)
            return ctor.newInstance(*Array(ctor.parameterCount) { buffer })
        }

        /** SystemUI's logcat-backed buffer where this build ships it, otherwise a proxy that remembers and forgets. */
        private fun messageBuffer(
            type: Class<*>,
            host: ClassLoader,
        ): Any =
            runCatching {
                val ctor = host.loadClass(LOGCAT_BUFFER).constructors.first()
                ctor.newInstance(*ctor.parameterTypes.map { p -> if (p.isEnum) p.enumConstants.first() else "Essentials" }.toTypedArray())
            }.getOrElse { recordingProxy(type) }

        /**
         * Stands in for value-holder interfaces such as LogMessage: setters remember, getters return
         * what was remembered, and anything returning another interface gets a proxy of its own.
         */
        private fun recordingProxy(type: Class<*>): Any {
            val values = HashMap<String, Any?>()
            return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                val property = method.name.removePrefix("set").removePrefix("get").removePrefix("is")
                when {
                    method.name.startsWith("set") && args?.size == 1 -> values.put(property, args[0]).let { null }
                    method.returnType.isInterface -> recordingProxy(method.returnType)
                    else -> values[property] ?: zeroFor(method.returnType)
                }
            }
        }

        private fun noOpProxy(type: Class<*>): Any =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> zeroFor(method.returnType) }

        /**
         * Builds a Kotlin data class through the synthetic constructor its default arguments
         * generate, so every field takes its declared default whatever the fields are.
         */
        private fun newWithDefaults(cls: Class<*>): Any {
            val synthetic =
                cls.constructors.firstOrNull { it.parameterTypes.lastOrNull()?.simpleName == "DefaultConstructorMarker" }
                    ?: return cls.constructors.first().let { ctor -> ctor.newInstance(*ctor.parameterTypes.map { zeroFor(it) }.toTypedArray()) }
            val types = synthetic.parameterTypes
            val args = arrayOfNulls<Any>(types.size)
            for (i in 0 until types.size - 2) args[i] = zeroFor(types[i])
            args[types.size - 2] = -1
            return synthetic.newInstance(*args)
        }

        private fun zeroFor(type: Class<*>): Any? =
            when (type) {
                Boolean::class.javaPrimitiveType -> false
                Float::class.javaPrimitiveType -> 0f
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                else -> null
            }

        /** Invokes the overload of [name] with the most parameters, each argument synthesised by [argument]. */
        private fun callLargest(
            target: Any,
            name: String,
            argument: (Class<*>) -> Any?,
        ): Any? {
            val method =
                target.javaClass.methods.filter { it.name == name }.maxByOrNull { it.parameterCount }
                    ?: throw NoSuchMethodException("$name on ${target.javaClass.name}")
            return method.invoke(target, *method.parameterTypes.map(argument).toTypedArray())
        }

        private fun invoke(
            target: Any,
            name: String,
            vararg args: Any?,
        ): Any? {
            val method =
                target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                    ?: throw NoSuchMethodException("$name/${args.size} on ${target.javaClass.name}")
            return method.invoke(target, *args)
        }
    }

    private class PluginClassLoader(
        apk: String,
        private val host: ClassLoader,
    ) : PathClassLoader(apk, ClassLoader.getSystemClassLoader()) {
        override fun loadClass(
            name: String,
            resolve: Boolean,
        ): Class<*> {
            if (HOST_PREFIXES.any { name.startsWith(it) }) return host.loadClass(name)
            findLoadedClass(name)?.let { return it }
            return try {
                findClass(name)
            } catch (_: ClassNotFoundException) {
                try {
                    parent.loadClass(name)
                } catch (_: ClassNotFoundException) {
                    host.loadClass(name)
                }
            }
        }
    }

    /**
     * Mirrors what SystemUI hands a plugin: the plugin's own resources, with class lookups routed
     * through the chained loader so inflated layouts can find their view classes.
     */
    private class PluginContext(
        base: Context,
        private val loader: ClassLoader,
    ) : ContextWrapper(base) {
        private var inflater: LayoutInflater? = null

        override fun getClassLoader(): ClassLoader = loader

        override fun getSystemService(name: String): Any? {
            if (name != LAYOUT_INFLATER_SERVICE) return super.getSystemService(name)
            return inflater ?: LayoutInflater.from(baseContext).cloneInContext(this).also { inflater = it }
        }
    }

    private fun face(small: Boolean): Face = if (small) this.small else large

    /** The text size the keyguard lays the face out with, in pixels. */
    fun textSize(small: Boolean): Float = face(small).textSize

    /**
     * Sizes one face the way the keyguard's host does. A plugin's face is measured against a frame
     * the size of the region, by its own layout params. SystemUI's own faces sit in a frame that
     * wraps them, as wide as the screen: the small face's line would otherwise break to fit a
     * region read with narrower digits.
     */
    fun fit(
        small: Boolean,
        region: Rect,
    ) {
        val face = face(small)
        runCatching { invoke(face.events, "onTimeTick") }
        face.region.set(region)
        measure(face)
    }

    private fun measure(face: Face) {
        val params = face.view.layoutParams
        val width = if (face.wrapped) atMost(face.view.resources.displayMetrics.widthPixels) else exactly(face.region.width())
        face.view.measure(
            ViewGroup.getChildMeasureSpec(width, 0, params?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT),
            ViewGroup.getChildMeasureSpec(exactly(face.region.height()), 0, params?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        face.view.layout(0, 0, face.view.measuredWidth, face.view.measuredHeight)
        face.measured.set(face.view.measuredWidth, face.view.measuredHeight)
        face.split = null
    }

    /**
     * Where the hours end and the minutes begin, in view pixels from the face's top-left: on the
     * large face a y between its two lines, on the one-line face an x at the widest gap between
     * its glyphs, found by drawing the face small and looking for empty columns.
     */
    fun split(small: Boolean): Float? {
        val face = face(small)
        face.split?.let { return it }
        val found = if (small) gap(face) else lineBoundary(face)
        face.split = found
        return found
    }

    private fun lineBoundary(face: Face): Float? {
        val group = face.view as? ViewGroup ?: return null
        if (group.childCount != 2) return null
        val (upper, lower) = listOf(group.getChildAt(0), group.getChildAt(1)).sortedBy { it.top }
        return (upper.bottom + lower.top) / 2f
    }

    private fun gap(face: Face): Float? {
        val width = face.measured.x / GAP_SCAN_DOWNSCALE
        val height = face.measured.y / GAP_SCAN_DOWNSCALE
        if (width <= 0 || height <= 0) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        face.view.draw(Canvas(bitmap).apply { scale(1f / GAP_SCAN_DOWNSCALE, 1f / GAP_SCAN_DOWNSCALE) })
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        val inked = BooleanArray(width) { x -> (0 until height).any { y -> Color.alpha(pixels[y * width + x]) != 0 } }
        val first = inked.indexOfFirst { it }
        val last = inked.indexOfLast { it }
        var widestStart = -1
        var widest = 0
        var x = first
        while (x in 0 until last) {
            if (inked[x]) {
                x++
                continue
            }
            var end = x
            while (end < last && !inked[end]) end++
            if (end - x > widest) {
                widest = end - x
                widestStart = x
            }
            x = end
        }
        return if (widestStart < 0) null else (widestStart + widest / 2f) * GAP_SCAN_DOWNSCALE
    }

    private fun exactly(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), View.MeasureSpec.EXACTLY)

    private fun atMost(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.AT_MOST)

    /** The keyguard lays the clock out again on every tick, and so does this. */
    fun tick() {
        for (face in listOf(large, small)) {
            runCatching { invoke(face.events, "onTimeTick") }
            if (!face.region.isEmpty) measure(face)
        }
    }

    /**
     * The keyguard's doze amount, 1 on the always-on display, handed to the plugin every frame as
     * the keyguard hands it. The plugin animates colour, weight and digits from the frame the
     * amount turns around, and steps without animating when it goes from one end straight to the
     * other.
     */
    fun doze(fraction: Float) {
        for (face in listOf(large, small)) face.doze?.invoke(face.animations, fraction)
    }

    /**
     * Draws one face at [scale] about the top-left of its region, which sits at the canvas origin.
     * The face is drawn where it put itself inside the box it was laid out in, as its host would
     * draw it: the flex clock's digits change width while their weight morphs between the
     * always-on and the lock looks, and the face keeps them centred by moving its own frame.
     */
    fun draw(
        canvas: Canvas,
        small: Boolean,
        scale: Float,
    ) {
        val face = face(small)
        canvas.save()
        canvas.translate(offsetX(face, scale), offsetY(face, scale))
        canvas.scale(scale, scale)
        canvas.translate(face.view.left.toFloat(), face.view.top.toFloat())
        face.view.draw(canvas)
        canvas.restore()
    }

    /** The area [draw] covers, relative to the top-left of the region. */
    fun painted(
        small: Boolean,
        scale: Float,
    ): RectF {
        val face = face(small)
        val left = offsetX(face, scale)
        val top = offsetY(face, scale)
        return RectF(left + face.view.left * scale, top + face.view.top * scale, left + face.view.right * scale, top + face.view.bottom * scale)
    }

    /**
     * The keyguard keeps the scaled large face centred where the measured one was, and the small
     * face's text starting where the measured one's did, so a region read with other digits still
     * puts today's digits where the keyguard has them.
     */
    private fun offsetX(
        face: Face,
        scale: Float,
    ): Float = if (face === small) 0f else (face.region.width() - face.measured.x) * scale / 2f

    private fun offsetY(
        face: Face,
        scale: Float,
    ): Float = (face.region.height() - face.measured.y) * scale / 2f
}
