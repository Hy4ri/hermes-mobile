package com.m57.hermescontrol.ui.chat

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpeechInputHelperTest {
    @Before
    fun setUp() {
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setAction(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers { self as Intent }
    }

    @After
    fun tearDown() {
        unmockkConstructor(Intent::class)
    }

    @Test
    fun isSpeechInputAvailable_returnsTrue_whenPackageManagerFindsActivities() {
        val context = mockk<Context>()
        val packageManager = mockk<PackageManager>()
        val resolveInfo = mockk<ResolveInfo>()

        every { context.packageManager } returns packageManager
        every {
            packageManager.queryIntentActivities(any<Intent>(), any<Int>())
        } returns listOf(resolveInfo)

        val result =
            SpeechInputHelper.isSpeechInputAvailable(
                context = context,
                isRecognizerServiceAvailable = { false },
            )
        assertTrue(result)
    }

    @Test
    fun isSpeechInputAvailable_returnsTrue_whenRecognitionServiceAvailableEvenIfNoActivities() {
        val context = mockk<Context>()
        val packageManager = mockk<PackageManager>()

        every { context.packageManager } returns packageManager
        every {
            packageManager.queryIntentActivities(any<Intent>(), any<Int>())
        } returns emptyList()

        val result =
            SpeechInputHelper.isSpeechInputAvailable(
                context = context,
                isRecognizerServiceAvailable = { true },
            )
        assertTrue(result)
    }

    @Test
    fun isSpeechInputAvailable_returnsFalse_whenNoActivitiesAndNoRecognitionService() {
        val context = mockk<Context>()
        val packageManager = mockk<PackageManager>()

        every { context.packageManager } returns packageManager
        every {
            packageManager.queryIntentActivities(any<Intent>(), any<Int>())
        } returns emptyList()

        val result =
            SpeechInputHelper.isSpeechInputAvailable(
                context = context,
                isRecognizerServiceAvailable = { false },
            )
        assertFalse(result)
    }

    @Test
    fun createSpeechIntent_constructsIntentWithActionAndPrompt() {
        val prompt = "Listening..."
        val intent = SpeechInputHelper.createSpeechIntent(prompt)
        assertNotNull(intent)
    }
}
