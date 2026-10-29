package com.shilapi.xcertplay

import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build

internal fun compatibleRipple(colors: ColorStateList, content: Drawable?, mask: Drawable?): Drawable =
    if (Build.VERSION.SDK_INT >= 21) RippleDrawable(colors, content, mask)
    else StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(colors.defaultColor))
        addState(intArrayOf(android.R.attr.state_focused), ColorDrawable(colors.defaultColor))
        addState(intArrayOf(), content ?: ColorDrawable(android.graphics.Color.TRANSPARENT))
    }
