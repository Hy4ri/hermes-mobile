package com.m57.hermescontrol.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import androidx.annotation.RequiresApi
import com.m57.hermescontrol.MainActivity

/**
 * Handles a single assistant invocation. There is no voice UI in v1 — every
 * trigger (long-press Home / corner swipe) simply opens the app in the
 * foreground, then closes the session.
 *
 * [onShow] fires for every invocation; [onHandleAssist] additionally receives
 * the assist data. Both route to the same guarded launch so the app opens
 * exactly once per session. The deprecated 3-arg [onHandleAssist] is kept so
 * the launch works on every supported API level (the framework's
 * [AssistState] default forwards to it below API 29).
 */
class AssistantSession(context: Context) : VoiceInteractionSession(context) {
    private var launchHandled = false

    override fun onShow(
        args: Bundle?,
        showFlags: Int,
    ) {
        super.onShow(args, showFlags)
        launchHermesAndFinish()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onHandleAssist(
        data: Bundle?,
        structure: AssistStructure?,
        content: AssistContent?,
    ) {
        launchHermesAndFinish()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onHandleAssist(state: AssistState) {
        launchHermesAndFinish()
    }

    private fun launchHermesAndFinish() {
        if (launchHandled) return
        launchHandled = true

        val intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)

        finish()
    }
}
