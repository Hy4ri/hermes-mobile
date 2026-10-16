package com.m57.hermescontrol.applock

import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** Hide a native window without detaching its composition or resetting non-saveable form state. */
internal class PrivateWindowVisibility {
    private var root: View? = null
    private var originalVisibility = View.VISIBLE
    private var originalAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    private var originalFlags: Int? = null
    var blocked = false
    val hasWindow: Boolean get() = root != null

    fun dismiss(action: (() -> Unit)?) {
        if (!blocked) action?.invoke()
    }

    fun attach(view: View) {
        root = view
        update()
    }

    fun detach(view: View) {
        if (root === view) {
            root = null
            originalFlags = null
        }
    }

    fun update() {
        val view = root ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams
        if (blocked) {
            if (originalFlags == null) {
                originalVisibility = view.visibility
                originalAccessibility = view.importantForAccessibility
                originalFlags = (params?.flags ?: 0) and PRIVACY_FLAGS
            }
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            view.visibility = View.GONE
            if (params != null) updateFlags(view, params, params.flags or PRIVACY_FLAGS)
        } else {
            val saved = originalFlags ?: return
            if (params != null) updateFlags(view, params, (params.flags and PRIVACY_FLAGS.inv()) or saved)
            view.importantForAccessibility = originalAccessibility
            view.visibility = originalVisibility
            originalFlags = null
        }
    }

    private fun updateFlags(
        view: View,
        params: WindowManager.LayoutParams,
        flags: Int,
    ) {
        if (params.flags != flags) {
            params.flags = flags
            if (view.isAttachedToWindow) {
                view.context.getSystemService(WindowManager::class.java).updateViewLayout(view, params)
            }
        }
    }

    private companion object {
        const val PRIVACY_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_SECURE
    }
}

@Composable
internal fun PrivateWindow(content: @Composable (PrivateWindowVisibility) -> Unit) {
    val visibility = remember { PrivateWindowVisibility() }
    visibility.blocked = LocalPrivateWindowsBlocked.current
    // Defer new windows entirely. Existing windows stay composed, but their decor is hidden.
    if (!visibility.blocked || visibility.hasWindow) content(visibility)
    SideEffect { visibility.update() }
}

@Composable
internal fun CapturePrivateWindow(visibility: PrivateWindowVisibility) {
    val root = LocalView.current.rootView
    DisposableEffect(root) {
        visibility.attach(root)
        onDispose { visibility.detach(root) }
    }
}
