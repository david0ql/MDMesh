package com.dallycontrol.agent.support

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Android 15 draws every app edge to edge and no longer resizes a window for the keyboard: a screen has to keep its
 * own content clear of the status bar, the navigation bar and the on-screen keyboard. Without this the support
 * screen's reply box and its Enviar button sit under the keyboard.
 */
object SupportInsets {
    /** Pad [root] by the system bars and the keyboard, on top of its own [side] and [bottom] padding. */
    fun keepClear(root: View, side: Int, bottom: Int) {
        val top = root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            // The top padding already leaves room for a status bar: only a taller one adds to it.
            view.setPadding(side + bars.left, maxOf(top, bars.top + side / 2), side + bars.right, bottom + bars.bottom)
            insets
        }
    }
}
