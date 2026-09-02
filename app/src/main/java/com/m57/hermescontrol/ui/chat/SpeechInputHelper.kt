package com.m57.hermescontrol.ui.chat

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

object SpeechInputHelper {
    /**
     * Checks if speech recognition is available on this device through any registered provider,
     * including standard Android [SpeechRecognizer] services or offline/third-party apps
     * that handle [RecognizerIntent.ACTION_RECOGNIZE_SPEECH] (e.g. FUTO Voice Input).
     */
    fun isSpeechInputAvailable(
        context: Context,
        isRecognizerServiceAvailable: (Context) -> Boolean = { ctx ->
            runCatching { SpeechRecognizer.isRecognitionAvailable(ctx) }.getOrDefault(false)
        },
    ): Boolean {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        val activities =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching {
                    context.packageManager.queryIntentActivities(
                        intent,
                        PackageManager.ResolveInfoFlags.of(0L),
                    )
                }.getOrDefault(emptyList())
            } else {
                @Suppress("DEPRECATION")
                runCatching {
                    context.packageManager.queryIntentActivities(intent, 0)
                }.getOrDefault(emptyList())
            }
        return activities.isNotEmpty() || isRecognizerServiceAvailable(context)
    }

    /**
     * Builds the standard speech recognition intent with language model and prompt.
     */
    fun createSpeechIntent(prompt: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                prompt,
            )
        }
}
