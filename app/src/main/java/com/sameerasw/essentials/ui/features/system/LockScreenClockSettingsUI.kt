/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: UI Feature - System
 * File: LockScreenClockSettingsUI.kt
 * Description: UI component and settings composable for System feature domain.
 */

package com.sameerasw.essentials.ui.features.system

import android.content.Intent
import android.os.Build
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sameerasw.essentials.R
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.ui.activities.WallpaperActivity
import com.sameerasw.essentials.ui.components.sliders.ConfigSliderItem
import com.sameerasw.essentials.ui.core.cards.IconToggleItem
import com.sameerasw.essentials.ui.core.containers.RoundedCardContainer
import com.sameerasw.essentials.ui.core.pickers.SegmentedPicker
import com.sameerasw.essentials.ui.modifiers.highlight
import com.sameerasw.essentials.ui.core.sheets.PermissionsBottomSheet
import com.sameerasw.essentials.utils.HapticUtil
import com.sameerasw.essentials.utils.LockClockLayer
import com.sameerasw.essentials.utils.LockScreenClockSize
import com.sameerasw.essentials.utils.LockScreenPreview
import com.sameerasw.essentials.utils.PermissionUIHelper
import com.sameerasw.essentials.utils.WallpaperImages
import com.sameerasw.essentials.viewmodels.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** A slider drag settles for this long before the preview renders again. */
private const val PREVIEW_SETTLE_MS = 40L
private const val PREVIEW_WIDTH_FRACTION = 0.6f

/** Shares of the clock's box under the subject below and above which the preview warns. */
private const val SUBJECT_CLEAR_OF_CLOCK = 0.02f
private const val SUBJECT_COVERING_CLOCK = 0.5f

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LockScreenClockSettingsUI(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    highlightSetting: String? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val currentClockId by viewModel.lockScreenClockId
    val isDark = isSystemInDarkTheme()

    val coverage by viewModel.wallpaperCoverage
    val clockEnabled by viewModel.lockClockInWallpaper
    val clockSupported by viewModel.lockClockSupported
    val clockMeasured by viewModel.lockClockMeasured
    val clockMeasuredHere by viewModel.lockClockMeasuredHere
    val accessibilityEnabled by viewModel.isAccessibilityEnabled
    val notificationsEnabled by viewModel.isNotificationListenerEnabled
    val clockInWallpaper = viewModel.lockClockInWallpaperActive
    var requestingPermissionsFor by remember { mutableStateOf<Pair<Int, List<String>>?>(null) }
    var styleToConfirm by remember { mutableStateOf<ClockOption?>(null) }
    var measureToConfirm by remember { mutableStateOf<MeasureChoice?>(null) }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    val switchClock = { id: String ->
        if (clockInWallpaper && !viewModel.lockClockMeasuredFor(id, context)) {
            measureToConfirm = MeasureChoice(apply = { viewModel.setLockScreenClockId(id, context) }, undo = {})
        } else {
            viewModel.setLockScreenClockId(id, context)
        }
    }
    val sliderReleased = { undo: () -> Unit ->
        dragFrom = null
        if (clockInWallpaper && !viewModel.lockClockMeasuredHere.value) measureToConfirm = MeasureChoice(apply = {}, undo = undo)
    }
    val missingPermissions =
        listOfNotNull(
            "ACCESSIBILITY".takeIf { !accessibilityEnabled },
            "NOTIFICATION_LISTENER".takeIf { !notificationsEnabled },
        )
    val wallpaperReady = coverage == WallpaperImages.Coverage.BOTH
    val measurable = wallpaperReady && missingPermissions.isEmpty() && clockSupported
    val stepsDone = measurable && clockMeasured

    requestingPermissionsFor?.let { (title, keys) ->
        PermissionsBottomSheet(
            onDismissRequest = {
                requestingPermissionsFor = null
                viewModel.check(context)
            },
            featureTitle = title,
            permissions = PermissionUIHelper.getPermissionItems(keys, context, viewModel),
        )
    }

    styleToConfirm?.let { option ->
        AlertDialog(
            onDismissRequest = { styleToConfirm = null },
            confirmButton = {
                TextButton(onClick = {
                    styleToConfirm = null
                    viewModel.setLockClockInWallpaper(false, context)
                    viewModel.setLockScreenClockId(option.id, context)
                }) {
                    Text(stringResource(R.string.lock_clock_turn_off_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { styleToConfirm = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            title = { Text(stringResource(R.string.lock_clock_turn_off_title)) },
            text = { Text(stringResource(R.string.lock_clock_turn_off_text)) },
        )
    }
    measureToConfirm?.let { choice ->
        AlertDialog(
            onDismissRequest = {
                choice.undo()
                measureToConfirm = null
            },
            confirmButton = {
                TextButton(onClick = {
                    measureToConfirm = null
                    choice.apply()
                    viewModel.measureLockClock(context)
                }) {
                    Text(stringResource(R.string.lock_clock_measure_action))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    choice.undo()
                    measureToConfirm = null
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            title = { Text(stringResource(R.string.lock_clock_remeasure_title)) },
            text = { Text(stringResource(R.string.lock_clock_remeasure_text)) },
        )
    }

    val inversionMatrix =
        remember {
            ColorMatrix(
                floatArrayOf(
                    -1f,
                    0f,
                    0f,
                    0f,
                    255f,
                    0f,
                    -1f,
                    0f,
                    0f,
                    255f,
                    0f,
                    0f,
                    -1f,
                    0f,
                    255f,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                ),
            )
        }

    val clockOptions =
        remember {
            listOf(
                ClockOption("DEFAULT", R.string.lock_screen_clock_default, R.drawable.clock_flex),
                ClockOption(
                    "ANALOG_CLOCK_BIGNUM",
                    R.string.lock_screen_clock_bignum,
                    R.drawable.clock_bignum,
                ),
                ClockOption(
                    "DIGITAL_CLOCK_CALLIGRAPHY",
                    R.string.lock_screen_clock_calligraphy,
                    R.drawable.clock_calligraphy,
                ),
                ClockOption(
                    "DIGITAL_CLOCK_GROWTH",
                    R.string.lock_screen_clock_growth,
                    R.drawable.clock_growth,
                ),
                ClockOption(
                    "DIGITAL_CLOCK_HANDWRITTEN",
                    R.string.lock_screen_clock_handwritten,
                    R.drawable.clock_handwritten,
                ),
                ClockOption(
                    "DIGITAL_CLOCK_INFLATE",
                    R.string.lock_screen_clock_inflate,
                    R.drawable.clock_inflate,
                ),
                ClockOption(
                    "DIGITAL_CLOCK_METRO",
                    R.string.lock_screen_clock_metro,
                    R.drawable.clock_metro,
                ),
                ClockOption(
                    "DIGITAL_CLOCK_NUMBEROVERLAP",
                    R.string.lock_screen_clock_numoverlap,
                    R.drawable.clock_overlap,
                ),
                ClockOption(
                    "DIGITAL_CLOCK_WEATHER",
                    R.string.lock_screen_clock_weather,
                    R.drawable.clock_weather,
                ),
            )
        }

    val colorOptions =
        remember {
            listOf(
                ClockColorOption("DEFAULT", Color.Transparent, 0, R.string.color_default),
                ClockColorOption("RED", Color(0xFFE57373), -23641, R.string.color_red),
                ClockColorOption("GREEN", Color(0xFF81C784), -14057967, R.string.color_green),
                ClockColorOption("BLUE", Color(0xFF64B5F6), -14575885, R.string.color_blue),
                ClockColorOption("YELLOW", Color(0xFFFFF176), -5317, R.string.color_yellow),
                ClockColorOption("ORANGE", Color(0xFFFFB74D), -18611, R.string.color_orange),
                ClockColorOption("PURPLE", Color(0xFFBA68C8), -4560702, R.string.color_purple),
                ClockColorOption("PINK", Color(0xFFF06292), -1023342, R.string.color_pink),
                ClockColorOption("TEAL", Color(0xFF4DB6AC), -11684180, R.string.color_teal),
            )
        }

    val isDefaultStyleSelected =
        currentClockId == "DEFAULT" || currentClockId == "DIGITAL_CLOCK_FLEX"

    val carouselState = rememberCarouselState { clockOptions.size }

    LaunchedEffect(carouselState) {
        var isFirst = true
        snapshotFlow { carouselState.currentItem }
            .collect {
                if (isFirst) {
                    isFirst = false
                } else {
                    HapticUtil.performSliderHaptic(view)
                }
            }
    }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.lock_screen_clock_select_label),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalMultiBrowseCarousel(
            state = carouselState,
            preferredItemWidth = 180.dp,
            itemSpacing = 2.dp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(200.dp),
        ) { index ->
            val option = clockOptions[index]
            val isSelected =
                if (option.id == "DEFAULT") isDefaultStyleSelected else currentClockId == option.id
            val supported = !clockInWallpaper || LockClockLayer.supports(option.id)

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(vertical = 4.dp)
                        .maskClip(MaterialTheme.shapes.large)
                        .background(if (isDark) Color.White else MaterialTheme.colorScheme.surfaceBright)
                        .pointerInput(option, supported) {
                            detectTapGestures {
                                HapticUtil.performUIHaptic(view)
                                if (!supported) {
                                    styleToConfirm = option
                                } else if (option.id == "DEFAULT") {
                                    if (!isDefaultStyleSelected) {
                                        switchClock("DEFAULT")
                                    }
                                } else {
                                    switchClock(option.id)
                                }
                            }
                        },
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(id = option.imageRes),
                    contentDescription = stringResource(option.nameRes),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    colorFilter = if (isDark) ColorFilter.colorMatrix(inversionMatrix) else null,
                )

                if (isSelected) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    )
                }
            }
        }

        // Color & Tone Section
        Text(
            text = stringResource(R.string.label_color),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val split = clockInWallpaper && viewModel.lockClockSplit.value
        val darkVariant = clockInWallpaper && viewModel.lockClockDarkVariant.value
        var editMinutes by remember { mutableStateOf(false) }
        var editDark by remember { mutableStateOf(false) }
        val minutes = split && editMinutes
        val dark = darkVariant && editDark

        if (split || darkVariant) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (split) {
                    val parts = listOf(false to stringResource(R.string.lock_clock_hours), true to stringResource(R.string.lock_clock_minutes))
                    SegmentedPicker(items = parts, selectedItem = parts.first { it.first == minutes }, onItemSelected = { editMinutes = it.first }, labelProvider = { it.second }, containerColor = Color.Transparent, contentPadding = PaddingValues(0.dp), modifier = Modifier.weight(1f))
                }
                if (darkVariant) {
                    val modes = listOf(false to stringResource(R.string.lock_clock_mode_light), true to stringResource(R.string.lock_clock_mode_dark))
                    SegmentedPicker(items = modes, selectedItem = modes.first { it.first == dark }, onItemSelected = { editDark = it.first }, labelProvider = { it.second }, containerColor = Color.Transparent, contentPadding = PaddingValues(0.dp), modifier = Modifier.weight(1f))
                }
            }
        }

        RoundedCardContainer {
            if (minutes || dark) {
                ColorSlot(viewModel, colorOptions, colourSlot(minutes, dark, second = false), null, R.string.label_color_tone)
            } else {
                ColorSwatches(colorOptions, viewModel.lockScreenClockSelectedColorId.value) {
                    viewModel.setLockScreenClockColor(it.id, it.seedColor, context)
                }
                ConfigSliderItem(
                    title = stringResource(R.string.label_color_tone),
                    value = viewModel.lockScreenClockColorTone.intValue.toFloat(),
                    onValueChange = { viewModel.setLockScreenClockColorTone(it.toInt(), context) },
                    valueRange = 0f..100f,
                    valueFormatter = { "${it.toInt()}%" },
                    iconRes = R.drawable.rounded_palette_24,
                    enabled = viewModel.lockScreenClockSelectedColorId.value != "DEFAULT",
                )
            }

            if (clockInWallpaper) PartControls(viewModel, colorOptions, minutes, dark, highlightSetting)
        }

        if (clockInWallpaper) {
            RoundedCardContainer {
                IconToggleItem(
                    iconRes = R.drawable.rounded_timer_24,
                    modifier = Modifier.highlight(highlightSetting == "lock_clock_split"),
                    title = stringResource(R.string.lock_clock_split_title),
                    description = stringResource(R.string.lock_clock_split_desc),
                    isChecked = viewModel.lockClockSplit.value,
                    onCheckedChange = { viewModel.setLockClockSplit(it) },
                )
                if (split) {
                    IconToggleItem(
                        iconRes = R.drawable.rounded_line_weight_24,
                        title = stringResource(R.string.lock_clock_split_small_title),
                        description = stringResource(R.string.lock_clock_split_small_desc),
                        isChecked = viewModel.lockClockSplitSmall.value,
                        onCheckedChange = { viewModel.setLockClockSplitSmall(it) },
                    )
                }
                IconToggleItem(
                    iconRes = R.drawable.rounded_dark_mode_24,
                    modifier = Modifier.highlight(highlightSetting == "lock_clock_dark_variant"),
                    title = stringResource(R.string.lock_clock_dark_variant_title),
                    description = stringResource(R.string.lock_clock_dark_variant_desc),
                    isChecked = darkVariant,
                    onCheckedChange = { viewModel.setLockClockDarkVariant(it) },
                )
                if (LockClockLayer.outlines(currentClockId ?: "")) {
                    IconToggleItem(
                        iconRes = R.drawable.rounded_line_weight_24,
                        title = stringResource(R.string.lock_clock_outline_title),
                        modifier = Modifier.highlight(highlightSetting == "lock_clock_outline"),
                        description = stringResource(R.string.lock_clock_outline_desc),
                        isChecked = viewModel.lockClockOutline.value,
                        onCheckedChange = { viewModel.setLockClockOutline(it) },
                    )
                }
            }
        }

        if (clockInWallpaper && viewModel.wallpaperDepthMap.value) {
            Text(
                text = stringResource(R.string.lock_clock_depth_section),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RoundedCardContainer {
                DepthPreview(viewModel)
                ConfigSliderItem(
                    title = stringResource(R.string.lock_clock_depth_level),
                    value = viewModel.wallpaperDepthLevel.floatValue,
                    onValueChange = { viewModel.setWallpaperDepthLevel(it) },
                    valueRange = 0f..1f,
                    increment = 0.01f,
                    valueFormatter = { "${(it * 100).toInt()} %" },
                    iconRes = R.drawable.rounded_blur_linear_24,
                )
                ConfigSliderItem(
                    title = stringResource(R.string.lock_clock_depth_softness),
                    value = viewModel.wallpaperDepthSoftness.floatValue,
                    onValueChange = { viewModel.setWallpaperDepthSoftness(it) },
                    valueRange = 0f..0.5f,
                    increment = 0.01f,
                    valueFormatter = { "${(it * 100).toInt()} %" },
                    iconRes = R.drawable.rounded_blur_on_24,
                )
                IconToggleItem(
                    iconRes = R.drawable.rounded_invert_colors_24,
                    title = stringResource(R.string.lock_clock_depth_flip_title),
                    description = stringResource(R.string.lock_clock_depth_flip_desc),
                    isChecked = viewModel.wallpaperDepthNearIsDark.value,
                    onCheckedChange = { viewModel.setWallpaperDepthNearIsDark(it) },
                )
            }
        }

        if (isDefaultStyleSelected) {
            Text(
                text = stringResource(R.string.label_style_font),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            RoundedCardContainer {
                SegmentedPicker(
                    items = listOf("DEFAULT", "DIGITAL_CLOCK_FLEX"),
                    selectedItem = currentClockId ?: "DEFAULT",
                    onItemSelected = switchClock,
                    labelProvider = { if (it == "DEFAULT") "Default" else "Flex" },
                )

                ConfigSliderItem(
                    title = stringResource(R.string.label_weight),
                    value = viewModel.lockScreenClockWeight.intValue.toFloat(),
                    onValueChange = {
                        if (dragFrom == null) dragFrom = viewModel.lockScreenClockWeight.intValue
                        viewModel.setLockScreenClockWeight(it.toInt(), context)
                    },
                    onValueChangeFinished = { dragFrom?.let { from -> sliderReleased { viewModel.setLockScreenClockWeight(from, context) } } },
                    valueRange = 100f..1000f,
                    increment = 10f,
                    valueFormatter = { it.toInt().toString() },
                    iconRes = R.drawable.rounded_line_weight_24,
                )

                ConfigSliderItem(
                    title = stringResource(R.string.label_width),
                    value = viewModel.lockScreenClockWidth.intValue.toFloat(),
                    onValueChange = {
                        if (dragFrom == null) dragFrom = viewModel.lockScreenClockWidth.intValue
                        viewModel.setLockScreenClockWidth(it.toInt(), context)
                    },
                    onValueChangeFinished = { dragFrom?.let { from -> sliderReleased { viewModel.setLockScreenClockWidth(from, context) } } },
                    valueRange = 25f..200f,
                    increment = 5f,
                    valueFormatter = { it.toInt().toString() },
                    iconRes = R.drawable.rounded_arrows_outward_24,
                )

                ConfigSliderItem(
                    title = stringResource(R.string.label_roundness),
                    value = viewModel.lockScreenClockRoundness.intValue.toFloat(),
                    onValueChange = { viewModel.setLockScreenClockRoundness(it.toInt(), context) },
                    valueRange = 0f..100f,
                    increment = 5f,
                    valueFormatter = { it.toInt().toString() },
                    iconRes = R.drawable.rounded_rounded_corner_24,
                )
            }
        }

        RoundedCardContainer {
            IconToggleItem(
                iconRes = R.drawable.rounded_visibility_off_24,
                title = stringResource(R.string.lock_screen_clock_hide_title),
                modifier = Modifier.highlight(highlightSetting == "lock_screen_clock_hide"),
                description = stringResource(if (clockInWallpaper) R.string.lock_screen_clock_hide_forced_desc else R.string.lock_screen_clock_hide_desc),
                isChecked = viewModel.lockScreenClockHidden.value || clockInWallpaper,
                enabled = !clockInWallpaper,
                onCheckedChange = { viewModel.setLockScreenClockHidden(it, context) },
            )
            IconToggleItem(
                iconRes = R.drawable.rounded_visibility_off_24,
                title = stringResource(R.string.lock_screen_weather_title),
                modifier = Modifier.highlight(highlightSetting == "lock_screen_weather_hide"),
                description = stringResource(R.string.lock_screen_weather_desc),
                isChecked = viewModel.lockScreenWeatherHidden.value,
                onCheckedChange = { viewModel.setLockScreenWeatherHidden(it, context) },
            )
        }

        Text(
            text = stringResource(R.string.lock_screen_clock_size),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        RoundedCardContainer {
            val size by viewModel.lockScreenClockSize
            val sizes =
                listOf(
                    LockScreenClockSize.DYNAMIC to stringResource(R.string.lock_screen_clock_size_dynamic),
                    LockScreenClockSize.LARGE to stringResource(R.string.lock_screen_clock_size_large),
                    LockScreenClockSize.SMALL to stringResource(R.string.lock_screen_clock_size_small),
                )
            SegmentedPicker(
                items = sizes,
                selectedItem = sizes.first { it.first == size },
                onItemSelected = { viewModel.setLockScreenClockSize(it.first, context) },
                labelProvider = { it.second },
                modifier = Modifier.highlight(highlightSetting == "lock_screen_clock_size"),
                description =
                    stringResource(
                        when (size) {
                            LockScreenClockSize.LARGE -> R.string.lock_screen_clock_size_large_desc
                            LockScreenClockSize.SMALL -> R.string.lock_screen_clock_size_small_desc
                            else -> R.string.lock_screen_clock_size_dynamic_desc
                        },
                    ),
            )
        }

        Text(
            text = stringResource(R.string.lock_clock_section),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        RoundedCardContainer {
            SetupStep(
                done = wallpaperReady,
                iconRes = R.drawable.rounded_wallpaper_24,
                title = stringResource(R.string.essentials_wallpaper_title),
                description =
                    stringResource(
                        when (coverage) {
                            WallpaperImages.Coverage.BOTH -> R.string.essentials_wallpaper_active
                            WallpaperImages.Coverage.HOME_ONLY -> R.string.essentials_wallpaper_home_only
                            WallpaperImages.Coverage.LOCK_ONLY -> R.string.essentials_wallpaper_lock_only
                            WallpaperImages.Coverage.NONE -> R.string.lock_clock_needs_wallpaper
                        },
                    ),
                onClick = {
                    HapticUtil.performVirtualKeyHaptic(view)
                    context.startActivity(Intent(context, WallpaperActivity::class.java).putExtra("tab", "photo"))
                },
            )
            SetupStep(
                done = missingPermissions.isEmpty(),
                iconRes = R.drawable.rounded_accessibility_new_24,
                title = stringResource(R.string.lock_clock_step_permissions),
                description = stringResource(if (missingPermissions.isEmpty()) R.string.lock_clock_step_granted else R.string.lock_clock_step_grant),
                onClick = {
                    HapticUtil.performVirtualKeyHaptic(view)
                    if (missingPermissions.isNotEmpty()) requestingPermissionsFor = Pair(R.string.lock_clock_section, missingPermissions)
                },
            )
            val supportedOption = clockOptions.first { LockClockLayer.supports(it.id) }
            SetupStep(
                done = clockSupported,
                iconRes = R.drawable.rounded_nest_clock_farsight_analog_24,
                title = stringResource(R.string.lock_clock_step_style, stringResource(supportedOption.nameRes)),
                description = stringResource(if (clockSupported) R.string.lock_clock_step_selected else R.string.lock_clock_step_select),
                onClick = {
                    HapticUtil.performVirtualKeyHaptic(view)
                    viewModel.setLockScreenClockId(supportedOption.id, context)
                },
            )
            SetupStep(
                done = clockMeasuredHere,
                modifier = Modifier.highlight(highlightSetting == "lock_clock_measure"),
                iconRes = R.drawable.rounded_refresh_24,
                title = stringResource(R.string.lock_clock_measure_title),
                description =
                    stringResource(
                        when {
                            !measurable -> R.string.lock_clock_steps_pending
                            !clockMeasured -> R.string.lock_clock_measure_desc
                            !clockMeasuredHere -> R.string.lock_clock_measure_stale
                            else -> R.string.lock_clock_measured
                        },
                    ),
                enabled = measurable,
                onClick = { viewModel.measureLockClock(context) },
            )
            IconToggleItem(
                iconRes = R.drawable.rounded_lock_clock_24,
                modifier = Modifier.highlight(highlightSetting == "lock_clock_in_wallpaper"),
                title = stringResource(R.string.lock_clock_in_wallpaper_title),
                description =
                    stringResource(
                        when {
                            !stepsDone -> R.string.lock_clock_steps_pending
                            clockEnabled -> R.string.lock_clock_on
                            else -> R.string.lock_clock_ready
                        },
                    ),
                isChecked = clockEnabled && stepsDone,
                enabled = stepsDone,
                onCheckedChange = {
                    HapticUtil.performVirtualKeyHaptic(view)
                    viewModel.setLockClockInWallpaper(it, context)
                },
            )
        }

        Text(
            text = stringResource(R.string.lock_clock_intro),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/**
 * One part of the wallpaper clock in one mode: its material and frost, whether its colour runs into
 * a second, that colour, and the run's direction.
 */
@Composable
private fun PartControls(
    viewModel: MainViewModel,
    colorOptions: List<ClockColorOption>,
    minutes: Boolean,
    dark: Boolean,
    highlightSetting: String?,
) {
    val part = SettingsRepository.lockClockPart(minutes, dark)
    val material = viewModel.lockClockMaterials[part] ?: SettingsRepository.LOCK_CLOCK_MATERIAL_SOLID
    val gradient = viewModel.lockClockGradients[part] ?: false
    val direction = viewModel.lockClockGradientDirections[part] ?: SettingsRepository.LOCK_CLOCK_GRADIENT_DOWN
    val materials =
        listOfNotNull(
            SettingsRepository.LOCK_CLOCK_MATERIAL_SOLID to stringResource(R.string.lock_clock_material_solid),
            (SettingsRepository.LOCK_CLOCK_MATERIAL_GLASS to stringResource(R.string.lock_clock_material_glass)).takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU },
        )
    if (materials.size > 1) {
        SegmentedPicker(
            items = materials,
            selectedItem = materials.firstOrNull { it.first == material } ?: materials.first(),
            onItemSelected = { viewModel.setLockClockMaterial(part, it.first) },
            labelProvider = { it.second },
            modifier = Modifier.highlight(highlightSetting == "lock_clock_material"),
            title = stringResource(R.string.lock_clock_material),
            description = stringResource(R.string.lock_clock_material_glass_desc).takeIf { material == SettingsRepository.LOCK_CLOCK_MATERIAL_GLASS },
        )
    }
    if (material == SettingsRepository.LOCK_CLOCK_MATERIAL_GLASS) {
        ConfigSliderItem(
            title = stringResource(R.string.lock_clock_glass_frost),
            value = viewModel.lockClockGlassFrosts[part] ?: 0.25f,
            onValueChange = { viewModel.setLockClockGlassFrost(part, it) },
            valueFormatter = { "${(it * 100).toInt()}%" },
            iconRes = R.drawable.rounded_blur_on_24,
            description = stringResource(R.string.lock_clock_glass_frost_desc),
        )
    }
    IconToggleItem(
        iconRes = R.drawable.rounded_invert_colors_24,
        modifier = Modifier.highlight(highlightSetting == "lock_clock_gradient"),
        title = stringResource(R.string.lock_clock_gradient_title),
        description = stringResource(R.string.lock_clock_gradient_desc),
        isChecked = gradient,
        onCheckedChange = { viewModel.setLockClockGradient(part, it) },
    )
    if (!gradient) return
    ColorSlot(viewModel, colorOptions, colourSlot(minutes, dark, second = true), R.string.lock_clock_second_color, R.string.lock_clock_second_tone)
    val directions =
        listOf(
            SettingsRepository.LOCK_CLOCK_GRADIENT_DOWN to stringResource(R.string.lock_clock_gradient_vertical),
            SettingsRepository.LOCK_CLOCK_GRADIENT_RIGHT to stringResource(R.string.lock_clock_gradient_horizontal),
            SettingsRepository.LOCK_CLOCK_GRADIENT_DIAGONAL to stringResource(R.string.lock_clock_gradient_diagonal),
        )
    SegmentedPicker(
        items = directions,
        selectedItem = directions.first { it.first == direction },
        onItemSelected = { viewModel.setLockClockGradientDirection(part, it.first) },
        labelProvider = { it.second },
        title = stringResource(R.string.lock_clock_gradient_direction),
    )
}

/** The slot of one of the wallpaper clock's own colours; the hours' light colour is the system seed and has none. */
private fun colourSlot(
    minutes: Boolean,
    dark: Boolean,
    second: Boolean,
): String =
    when {
        minutes && second && dark -> SettingsRepository.LOCK_CLOCK_SLOT_MINUTES_SECOND_DARK
        minutes && second -> SettingsRepository.LOCK_CLOCK_SLOT_MINUTES_SECOND
        minutes && dark -> SettingsRepository.LOCK_CLOCK_SLOT_MINUTES_DARK
        minutes -> SettingsRepository.LOCK_CLOCK_SLOT_MINUTES
        second && dark -> SettingsRepository.LOCK_CLOCK_SLOT_SECOND_DARK
        second -> SettingsRepository.LOCK_CLOCK_SLOT_SECOND
        else -> SettingsRepository.LOCK_CLOCK_SLOT_DARK
    }

/** One of the wallpaper clock's own colours: the swatches, captioned or not, and their tone. */
@Composable
private fun ColorSlot(
    viewModel: MainViewModel,
    colorOptions: List<ClockColorOption>,
    slot: String,
    title: Int?,
    toneTitle: Int,
) {
    val id = viewModel.lockClockColorIds[slot] ?: "DEFAULT"
    ColorSwatches(colorOptions, id, title?.let { stringResource(it) }) { viewModel.setLockClockColor(slot, it.id) }
    ConfigSliderItem(
        title = stringResource(toneTitle),
        value = (viewModel.lockClockColorTones[slot] ?: 75).toFloat(),
        onValueChange = { viewModel.setLockClockColorTone(slot, it.toInt()) },
        valueRange = 0f..100f,
        valueFormatter = { "${it.toInt()}%" },
        iconRes = R.drawable.rounded_palette_24,
        enabled = id != "DEFAULT",
    )
}

@Composable
private fun ColorSwatches(
    options: List<ClockColorOption>,
    selectedId: String,
    title: String? = null,
    onSelect: (ClockColorOption) -> Unit,
) {
    val view = LocalView.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceBright,
                    shape = RoundedCornerShape(MaterialTheme.shapes.extraSmall.bottomEnd),
                ).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        options.chunked(5).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                rowOptions.forEach { colorOption ->
                    ColorCircle(
                        colorOption = colorOption,
                        isSelected = selectedId == colorOption.id,
                        onClick = {
                            HapticUtil.performUIHaptic(view)
                            onSelect(colorOption)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupStep(
    done: Boolean,
    iconRes: Int,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = IconToggleItem(
    iconRes = if (done) R.drawable.rounded_check_circle_24 else iconRes,
    title = title,
    modifier = modifier,
    description = description,
    showToggle = false,
    enabled = enabled,
    onClick = onClick,
)

@Composable
fun ColorCircle(
    colorOption: ClockColorOption,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (colorOption.id == "DEFAULT") MaterialTheme.colorScheme.surfaceVariant else colorOption.color,
                ).border(
                    width = if (isSelected) 3.dp else 1.dp,
                    color =
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline.copy(
                                alpha = 0.2f,
                            )
                        },
                    shape = CircleShape,
                ).pointerInput(colorOption.id) {
                    detectTapGestures {
                        onClick()
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        if (colorOption.id == "DEFAULT") {
            Icon(
                painter = painterResource(id = R.drawable.rounded_palette_24),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (isSelected) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (colorOption.id == "DEFAULT") MaterialTheme.colorScheme.primary else Color.White),
            )
        }
    }
}

/** The lock screen with the subject cut at the current depth, one face at a time, and whether the subject meets that face. */
@Composable
private fun DepthPreview(viewModel: MainViewModel) {
    val context = LocalContext.current
    val preview = remember { LockScreenPreview(context, SettingsRepository(context)) }
    DisposableEffect(preview) { onDispose { preview.release() } }
    var small by remember { mutableStateOf(false) }
    var subject by remember { mutableStateOf<Bitmap?>(null) }
    var covered by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(preview) {
        snapshotFlow { Triple(viewModel.wallpaperDepthLevel.floatValue, viewModel.wallpaperDepthSoftness.floatValue, viewModel.wallpaperDepthNearIsDark.value) }
            .collectLatest { (level, softness, nearIsDark) ->
                delay(PREVIEW_SETTLE_MS)
                subject = preview.cut(level, softness, nearIsDark)
            }
    }
    LaunchedEffect(subject, small) { covered = preview.coverage(small, subject) }
    val large = stringResource(R.string.lock_screen_clock_size_large)
    val smallLabel = stringResource(R.string.lock_screen_clock_size_small)
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth(PREVIEW_WIDTH_FRACTION)
                    .align(Alignment.CenterHorizontally)
                    .aspectRatio(preview.width.toFloat() / preview.height)
                    .clip(RoundedCornerShape(24.dp)),
        ) {
            drawIntoCanvas { preview.draw(it.nativeCanvas, small, size.width / preview.width, subject) }
        }
        SegmentedPicker(
            items = listOf(false, true),
            selectedItem = small,
            onItemSelected = { small = it },
            labelProvider = { if (it) smallLabel else large },
        )
        covered?.let { covered ->
            val warning =
                when {
                    covered < SUBJECT_CLEAR_OF_CLOCK -> stringResource(R.string.lock_clock_depth_clear)
                    covered > SUBJECT_COVERING_CLOCK -> stringResource(R.string.lock_clock_depth_covering, (covered * 100).toInt())
                    else -> null
                }
            warning?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** A change that leaves the wallpaper clock unmeasured: applied together with a measurement, or undone. */
private class MeasureChoice(
    val apply: () -> Unit,
    val undo: () -> Unit,
)

data class ClockOption(
    val id: String,
    val nameRes: Int,
    val imageRes: Int,
)

data class ClockColorOption(
    val id: String,
    val color: Color,
    val seedColor: Int,
    val nameRes: Int,
)
