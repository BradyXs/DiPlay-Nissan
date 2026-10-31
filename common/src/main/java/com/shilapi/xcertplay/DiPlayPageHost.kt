package com.shilapi.xcertplay

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/** Keeps the window's content view stable while a destination is rebuilt. */
internal class DiPlayPageHost(context: Context) : FrameLayout(context) {
    private var currentPage: View? = null

    fun show(page: View) {
        require(page.parent == null)
        val previous = currentPage
        // Attach the replacement before removing the old tree. The window never has an empty root.
        addView(page, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        previous?.let(::removeView)
        currentPage = page
    }
}
