package com.example.mone

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Adds the system-bar insets (status bar at top, nav bar at bottom) on top of the
 * view's existing XML padding, so content clears the bars without losing its own padding.
 * Unlike android:fitsSystemWindows, this preserves horizontal padding.
 */
fun View.applyBarInsets() {
    val l = paddingLeft; val t = paddingTop; val r = paddingRight; val b = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
        insets
    }
}
