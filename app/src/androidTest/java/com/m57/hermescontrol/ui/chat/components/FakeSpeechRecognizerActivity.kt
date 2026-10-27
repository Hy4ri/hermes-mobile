package com.m57.hermescontrol.ui.chat.components

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent

/** Answers RECOGNIZE_SPEECH at once with a fixed transcript. */
class FakeSpeechRecognizerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Runs in the test APK's own process, which has no Kotlin stdlib: plain Java collections only.
        val results = java.util.ArrayList<String>()
        results.add(TRANSCRIPT)
        setResult(RESULT_OK, Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results))
        finish()
    }

    companion object {
        const val TRANSCRIPT = "A fresh copy of production"
    }
}
