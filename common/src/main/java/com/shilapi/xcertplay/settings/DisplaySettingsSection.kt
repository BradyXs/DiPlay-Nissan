package com.shilapi.xcertplay.settings

import android.content.Context
import com.shilapi.xcertplay.host.R

object DisplaySettingsSection {

    fun createResolutionSlider(
        context: Context,
        initialPercent: Int,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onPercentChanged: (Int) -> Unit,
    ): SettingsWidgets.ResolutionSliderResult =
        SettingsWidgets.createResolutionSlider(context, initialPercent, theme, onPercentChanged)

    fun createRightHandDriveRow(
        context: Context,
        checked: Boolean,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onChanged: (Boolean) -> Unit,
    ): SettingsWidgets.SwitchRowResult = SettingsWidgets.createSwitchRow(
        context = context,
        label = context.getString(R.string.right_hand_drive),
        description = context.getString(R.string.place_carplay_s_controls_closer_to_the_driver),
        checked = checked,
        theme = theme,
        contentDescription = context.getString(R.string.right_hand_drive),
        onChanged = onChanged,
    )
}
