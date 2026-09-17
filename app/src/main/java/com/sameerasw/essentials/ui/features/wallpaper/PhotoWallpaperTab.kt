/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Wallpaper
 * File: PhotoWallpaperTab.kt
 * Description: The Photo tab of the Wallpapers page: the Essentials wallpaper and what each screen shows.
 */

package com.sameerasw.essentials.ui.composables.wallpaper

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sameerasw.essentials.FeatureSettingsActivity
import com.sameerasw.essentials.R
import com.sameerasw.essentials.data.repository.SettingsRepository
import com.sameerasw.essentials.ui.components.sliders.ConfigSliderItem
import com.sameerasw.essentials.ui.core.cards.IconToggleItem
import com.sameerasw.essentials.ui.core.containers.RoundedCardContainer
import com.sameerasw.essentials.ui.core.pickers.SegmentedPicker
import com.sameerasw.essentials.ui.modifiers.highlight
import com.sameerasw.essentials.utils.HapticUtil
import com.sameerasw.essentials.utils.WallpaperImages
import com.sameerasw.essentials.viewmodels.MainViewModel

private const val BLUR_SLIDER_MAX = 25f
private const val AOD_IMAGE_CUSTOM = "custom"
private const val DEPTH_NONE = "none"
private const val DEPTH_MAP = "map"

@Composable
fun PhotoWallpaperTab(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    highlightSetting: String? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val coverage by viewModel.wallpaperCoverage
    val active = coverage == WallpaperImages.Coverage.BOTH
    val drawsLock = coverage == WallpaperImages.Coverage.BOTH || coverage == WallpaperImages.Coverage.LOCK_ONLY
    val drawsHome = coverage == WallpaperImages.Coverage.BOTH || coverage == WallpaperImages.Coverage.HOME_ONLY
    val lockImage by viewModel.wallpaperLockImage
    val homeImage by viewModel.wallpaperHomeImage
    val lockKeepable by viewModel.wallpaperLockKeepable
    val homeKeepable by viewModel.wallpaperHomeKeepable
    val hasCustomAod by viewModel.hasAodWallpaperCustomImage
    val hasDepthMap by viewModel.wallpaperDepthMap
    var setupAwaitingPhoto by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // The system preview runs in another app; what it left behind is only known on the way back.
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshWallpaperState(context) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val lockPhotoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                viewModel.setWallpaperLockImage(context, it) {
                    if (setupAwaitingPhoto) {
                        setupAwaitingPhoto = false
                        viewModel.openEssentialsWallpaperPicker(context)
                    }
                }
            }
        }
    val homePhotoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { viewModel.setWallpaperHomeImage(context, SettingsRepository.WALLPAPER_IMAGE_PHOTO, it) }
        }
    val aodPhotoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { viewModel.setCustomAodWallpaper(context, it) }
        }
    val depthMapPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { viewModel.setWallpaperLockDepth(context, it) }
        }

    Column(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RoundedCardContainer {
            IconToggleItem(
                iconRes = R.drawable.rounded_wallpaper_24,
                title = stringResource(R.string.essentials_wallpaper_title),
                modifier = Modifier.highlight(highlightSetting == "wallpaper_essentials"),
                description =
                    stringResource(
                        when (coverage) {
                            WallpaperImages.Coverage.BOTH -> R.string.essentials_wallpaper_active
                            WallpaperImages.Coverage.HOME_ONLY -> R.string.essentials_wallpaper_home_only
                            WallpaperImages.Coverage.LOCK_ONLY -> R.string.essentials_wallpaper_lock_only
                            WallpaperImages.Coverage.NONE -> R.string.essentials_wallpaper_not_set
                        },
                    ),
                isChecked = active,
                onCheckedChange = { checked ->
                    HapticUtil.performVirtualKeyHaptic(view)
                    if (checked) {
                        viewModel.prepareEssentialsWallpaper(context) { hasLockImage ->
                            if (hasLockImage) {
                                viewModel.openEssentialsWallpaperPicker(context)
                            } else {
                                setupAwaitingPhoto = true
                                lockPhotoPicker.launch("image/*")
                            }
                        }
                    } else {
                        viewModel.disableEssentialsWallpaper(context)
                    }
                },
            )
            if (coverage == WallpaperImages.Coverage.HOME_ONLY || coverage == WallpaperImages.Coverage.LOCK_ONLY) {
                IconToggleItem(
                    iconRes = R.drawable.rounded_open_in_new_24,
                    title = stringResource(R.string.essentials_wallpaper_fix),
                    description = stringResource(R.string.essentials_wallpaper_fix_desc),
                    showToggle = false,
                    onClick = {
                        HapticUtil.performVirtualKeyHaptic(view)
                        viewModel.openEssentialsWallpaperPicker(context)
                    },
                )
            }
        }

        SectionTitle(R.string.wallpaper_section_lock)
        RoundedCardContainer {
            ImageChoice(
                selected = lockImage,
                modifier = Modifier.highlight(highlightSetting == "wallpaper_lock_image"),
                options = listOf(SettingsRepository.WALLPAPER_IMAGE_SYSTEM to R.string.wallpaper_image_current, SettingsRepository.WALLPAPER_IMAGE_PHOTO to R.string.wallpaper_image_photo),
                onSelect = { kind ->
                    when {
                        kind == SettingsRepository.WALLPAPER_IMAGE_PHOTO -> lockPhotoPicker.launch("image/*")
                        !lockKeepable -> toast(context, if (drawsLock) R.string.wallpaper_image_already_essentials else R.string.wallpaper_image_live)
                        else -> viewModel.setWallpaperLockImage(context, null)
                    }
                },
            )
            ImageChoice(
                selected = if (hasDepthMap) DEPTH_MAP else DEPTH_NONE,
                title = R.string.wallpaper_depth,
                options = listOf(DEPTH_NONE to R.string.wallpaper_depth_none, DEPTH_MAP to R.string.wallpaper_depth_map),
                onSelect = { kind -> if (kind == DEPTH_MAP) depthMapPicker.launch("image/*") else viewModel.removeWallpaperLockDepth(context) },
            )
            ConfigSliderItem(
                title = stringResource(R.string.wallpaper_blur),
                value = viewModel.wallpaperLockBlur.floatValue,
                onValueChange = { viewModel.setWallpaperLockBlur(it) },
                modifier = Modifier.highlight(highlightSetting == "wallpaper_lock_blur"),
                valueRange = 0f..BLUR_SLIDER_MAX,
                iconRes = R.drawable.rounded_blur_on_24,
            )
        }
        Text(
            text = stringResource(R.string.wallpaper_depth_intro),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionTitle(R.string.wallpaper_section_home)
        RoundedCardContainer {
            ImageChoice(
                selected = homeImage,
                modifier = Modifier.highlight(highlightSetting == "wallpaper_home_image"),
                options =
                    listOf(
                        SettingsRepository.WALLPAPER_IMAGE_LOCK to R.string.wallpaper_image_same_as_lock,
                        SettingsRepository.WALLPAPER_IMAGE_SYSTEM to R.string.wallpaper_image_current,
                        SettingsRepository.WALLPAPER_IMAGE_PHOTO to R.string.wallpaper_image_photo,
                    ),
                onSelect = { kind ->
                    when {
                        kind == SettingsRepository.WALLPAPER_IMAGE_PHOTO -> homePhotoPicker.launch("image/*")
                        kind == SettingsRepository.WALLPAPER_IMAGE_SYSTEM && !homeKeepable ->
                            toast(context, if (drawsHome) R.string.wallpaper_image_already_essentials else R.string.wallpaper_image_live)
                        else -> viewModel.setWallpaperHomeImage(context, kind)
                    }
                },
            )
            ConfigSliderItem(
                title = stringResource(R.string.wallpaper_blur),
                value = viewModel.wallpaperHomeBlur.floatValue,
                onValueChange = { viewModel.setWallpaperHomeBlur(it) },
                modifier = Modifier.highlight(highlightSetting == "wallpaper_home_blur"),
                valueRange = 0f..BLUR_SLIDER_MAX,
                iconRes = R.drawable.rounded_blur_on_24,
            )
        }

        SectionTitle(R.string.wallpaper_section_aod)
        RoundedCardContainer {
            ImageChoice(
                selected = if (hasCustomAod) AOD_IMAGE_CUSTOM else SettingsRepository.WALLPAPER_IMAGE_LOCK,
                modifier = Modifier.highlight(highlightSetting == "wallpaper_aod_image"),
                options = listOf(SettingsRepository.WALLPAPER_IMAGE_LOCK to R.string.wallpaper_image_same_as_lock, AOD_IMAGE_CUSTOM to R.string.wallpaper_image_custom),
                onSelect = { kind ->
                    if (kind == AOD_IMAGE_CUSTOM) aodPhotoPicker.launch("image/*") else viewModel.removeCustomAodWallpaper(context)
                },
            )
            IconToggleItem(
                iconRes = R.drawable.rounded_mobile_text_2_24,
                title = stringResource(R.string.link_aod_settings),
                description = stringResource(R.string.link_aod_settings_desc),
                showToggle = false,
                onClick = {
                    HapticUtil.performVirtualKeyHaptic(view)
                    openFeature(context, "Always on Display")
                },
            )
        }

        RoundedCardContainer {
            IconToggleItem(
                iconRes = R.drawable.rounded_nest_clock_farsight_analog_24,
                title = stringResource(R.string.link_lock_screen_clock),
                description = stringResource(R.string.link_lock_screen_clock_desc),
                showToggle = false,
                onClick = {
                    HapticUtil.performVirtualKeyHaptic(view)
                    openFeature(context, "Lock screen clock")
                },
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SectionTitle(title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ImageChoice(
    selected: String,
    options: List<Pair<String, Int>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    title: Int = R.string.wallpaper_image,
) {
    val labelled = options.map { it.first to stringResource(it.second) }
    SegmentedPicker(
        items = labelled,
        selectedItem = labelled.firstOrNull { it.first == selected } ?: labelled.first(),
        onItemSelected = { onSelect(it.first) },
        labelProvider = { it.second },
        title = stringResource(title),
        modifier = modifier.fillMaxWidth(),
    )
}

private fun toast(
    context: Context,
    message: Int,
) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

private fun openFeature(
    context: Context,
    featureId: String,
) = context.startActivity(Intent(context, FeatureSettingsActivity::class.java).putExtra("feature", featureId))
