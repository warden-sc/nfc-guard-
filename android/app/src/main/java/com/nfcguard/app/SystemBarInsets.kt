package com.nfcguard.app

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * targetSdk 35+는 화면이 상태바/내비게이션바 아래까지 그려진다(edge-to-edge 강제, 36부터는 해제 불가).
 * 콘텐츠가 시스템 바에 가리지 않도록 view의 원래 padding에 시스템 바 높이를 더한다.
 */
fun View.applySystemBarInsets() {
    val start = paddingStart
    val top = paddingTop
    val end = paddingEnd
    val bottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.updatePadding(left = start + bars.left, top = top + bars.top, right = end + bars.right, bottom = bottom + bars.bottom)
        insets
    }
}
