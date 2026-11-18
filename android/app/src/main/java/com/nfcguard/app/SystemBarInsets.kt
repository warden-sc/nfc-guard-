package com.nfcguard.app

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Keep the existing content padding and reserve room for system bars and cutouts. */
internal fun View.applySystemBarInsets() {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val safeArea = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.setPadding(left + safeArea.left, top + safeArea.top, right + safeArea.right, bottom + safeArea.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
