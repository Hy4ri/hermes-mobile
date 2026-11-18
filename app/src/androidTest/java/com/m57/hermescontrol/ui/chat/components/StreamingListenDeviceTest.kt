package com.m57.hermescontrol.ui.chat.components

import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.fullbleed.FullBleedAgentMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/** Opt-in disposable-emulator checks: real production UI, HTTP/WebSocket, AudioTrack and ExoPlayer. */
@RunWith(AndroidJUnit4::class)
class StreamingListenDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val errors = AtomicInteger()
    private val streamCalls = AtomicInteger()
    private val restCalls = AtomicInteger()
    private val connected = CountDownLatch(1)
    private val firstAudio = CountDownLatch(1)
    private val firstGate = CountDownLatch(1)
    private val endGate = CountDownLatch(1)
    private val disconnected = CountDownLatch(1)
    private var server: MockWebServer? = null
    private var worker: Thread? = null
    private var fallback = false
    private lateinit var controller: SpeechController
    private val pcm =
        ByteArray(24_000 * 2 * 3).also { bytes ->
            val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            repeat(bytes.size / 2) { i -> samples.putShort((sin(2 * PI * 220 * i / 24_000) * 3000).toInt().toShort()) }
        }

    @Before
    fun disposableEmulatorOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("disposableSpeech") == "true")
        assertTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        AuthManager.init(context.applicationContext)
        runBlocking { withTimeout(15_000) { AuthManager.awaitInitialization() } }
    }

    @After
    fun cleanup() {
        if (::controller.isInitialized) compose.runOnIdle { controller.reset() }
        firstGate.countDown()
        endGate.countDown()
        worker?.join(2000)
        server?.shutdown()
    }

    private fun fixture() {
        val fixture = MockWebServer()
        fixture.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.requestUrl?.encodedPath) {
                        "/api/auth/ws-ticket" -> {
                            MockResponse()
                                .setHeader("Content-Type", "application/json")
                                .setBody("""{"ticket":"fixture-speech-ticket"}""")
                        }

                        "/api/audio/speak-stream" -> {
                            streamCalls.incrementAndGet()
                            assertEquals("fixture-speech-ticket", request.requestUrl?.queryParameter("ticket"))
                            MockResponse().withWebSocketUpgrade(
                                object : WebSocketListener() {
                                    override fun onOpen(
                                        webSocket: WebSocket,
                                        response: Response,
                                    ) {
                                        connected.countDown()
                                    }

                                    override fun onMessage(
                                        webSocket: WebSocket,
                                        text: String,
                                    ) {
                                        if (!text.contains("done")) return
                                        if (fallback) {
                                            webSocket.send("""{"type":"fallback"}""")
                                            return
                                        }
                                        worker =
                                            thread(name = "speech-fixture-1883") {
                                                if (!firstGate.await(15, TimeUnit.SECONDS)) return@thread
                                                if (!webSocket.send(
                                                        """{"type":"start","sample_rate":24000,"channels":1}""",
                                                    )
                                                ) {
                                                    return@thread
                                                }
                                                webSocket.send(pcm.copyOfRange(0, 48_000).toByteString())
                                                firstAudio.countDown()
                                                if (!endGate.await(15, TimeUnit.SECONDS)) return@thread
                                                webSocket.send(pcm.copyOfRange(48_000, pcm.size).toByteString())
                                                webSocket.send("""{"type":"end"}""")
                                                webSocket.close(1000, "complete")
                                            }
                                    }

                                    override fun onClosing(
                                        webSocket: WebSocket,
                                        code: Int,
                                        reason: String,
                                    ) {
                                        disconnected.countDown()
                                        webSocket.close(code, null)
                                    }

                                    override fun onFailure(
                                        webSocket: WebSocket,
                                        t: Throwable,
                                        response: Response?,
                                    ) {
                                        disconnected.countDown()
                                    }
                                },
                            )
                        }

                        "/api/audio/speak" -> {
                            restCalls.incrementAndGet()
                            val encoded = Base64.encodeToString(wav(), Base64.NO_WRAP)
                            MockResponse()
                                .setHeader("Content-Type", "application/json")
                                .setHeadersDelay(31, TimeUnit.SECONDS)
                                .setBody(
                                    """{"ok":true,"data_url":"data:audio/wav;base64,$encoded","mime_type":"audio/wav","provider":"device-fixture"}""",
                                )
                        }

                        else -> {
                            MockResponse().setResponseCode(404)
                        }
                    }
            }
        fixture.start()
        server = fixture
        connect(fixture.url("/").toString(), "fixture-session")
    }

    private fun connect(
        url: String,
        cookie: String,
    ) {
        AuthManager.setBaseUrl(url)
        AuthManager.setToken(null)
        AuthManager.setWsAuthParam("ticket")
        AuthManager.setSessionCookie(cookie)
        runBlocking {
            withTimeout(5000) {
                while (AuthManager.currentDataScope()?.baseUrl?.trimEnd('/') != url.trimEnd('/')) delay(20)
            }
        }
    }

    private fun render() {
        compose.setContent {
            val speech = rememberChatSpeech(onError = { errors.incrementAndGet() })
            val active by speech.speakingId.collectAsState()
            val preparing by speech.preparingId.collectAsState()
            SideEffect { controller = speech }
            HermesControlTheme {
                Surface {
                    FullBleedAgentMessage(
                        message = ChatMessage(id = "speech-device-1883", role = MessageRole.ASSISTANT, content = TEXT),
                        isSpeaking = active != null,
                        isPreparingSpeech = preparing != null,
                        onToggleSpeak = {
                            speech.toggle(SpeechRequest("device-1883", "speech-device-1883", TEXT))
                        },
                    )
                }
            }
        }
    }

    private fun click() = compose.onNodeWithTag("fullbleed_speak").performClick()

    private fun awaitPlaying(timeout: Long = 20_000) =
        compose
            .waitUntil(timeout) {
                (controller.speakingId.value != null && controller.preparingId.value == null) || errors.get() > 0
            }.also { assertEquals(0, errors.get()) }

    private fun awaitFinished(timeout: Long = 20_000) =
        compose
            .waitUntil(timeout) {
                controller.speakingId.value == null
            }.also { assertEquals(0, errors.get()) }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            context.filesDir.resolve("1883-$name.png").outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun streamPlaysBeforeEndDrainsAndReplaysWithoutNetwork() {
        fixture()
        render()
        val started = SystemClock.elapsedRealtime()
        click()
        assertTrue(connected.await(5, TimeUnit.SECONDS))
        assertTrue(controller.preparingId.value != null)
        screenshot("preparing")
        firstGate.countDown()
        assertTrue(firstAudio.await(5, TimeUnit.SECONDS))
        awaitPlaying()
        assertTrue(
            "AudioTrack must be active before end is sent",
            context.getSystemService(AudioManager::class.java).isMusicActive,
        )
        screenshot("playing")
        Log.i(TAG, "fixture first AudioTrack write after ${SystemClock.elapsedRealtime() - started} ms; end still held")
        assertTrue(controller.speakingId.value != null)
        endGate.countDown()
        awaitFinished()
        screenshot("complete")
        assertEquals(1, streamCalls.get())
        assertEquals(0, restCalls.get())
        click()
        awaitPlaying()
        awaitFinished()
        assertEquals("Replay must use the completed WAV cache", 1, streamCalls.get())
        assertEquals(0, restCalls.get())
    }

    @Test
    fun stopDuringPreparingClosesSocketWithoutFallbackOrRevival() {
        fixture()
        render()
        click()
        assertTrue(connected.await(5, TimeUnit.SECONDS))
        assertTrue(controller.preparingId.value != null)
        click()
        assertEquals(null, controller.preparingId.value)
        assertEquals(null, controller.speakingId.value)
        assertTrue(disconnected.await(5, TimeUnit.SECONDS))
        firstGate.countDown()
        endGate.countDown()
        SystemClock.sleep(400)
        assertEquals(null, controller.speakingId.value)
        assertEquals(0, restCalls.get())
        assertEquals(0, errors.get())
    }

    @Test
    fun stopAfterPcmReleasesAudioTrackAndDeletesPartialCache() {
        fixture()
        render()
        firstGate.countDown()
        click()
        awaitPlaying()
        click()
        compose.waitUntil(5000) { !context.getSystemService(AudioManager::class.java).isMusicActive }
        assertEquals(null, controller.speakingId.value)
        assertTrue(disconnected.await(5, TimeUnit.SECONDS))
        endGate.countDown()
        compose.waitUntil(5000) {
            context.cacheDir
                .resolve("speech_tts")
                .listFiles()
                .orEmpty()
                .none { it.isFile }
        }
        assertEquals(0, restCalls.get())
        assertEquals(0, errors.get())
    }

    @Test
    fun fallbackSurvives31SecondRestGenerationAndPlays() {
        fallback = true
        fixture()
        render()
        val started = SystemClock.elapsedRealtime()
        click()
        compose.waitUntil(5000) { restCalls.get() == 1 }
        assertTrue(controller.preparingId.value != null)
        screenshot("rest-generating")
        awaitPlaying(45_000)
        awaitFinished(45_000)
        assertTrue(SystemClock.elapsedRealtime() - started >= 31_000)
        assertEquals(1, streamCalls.get())
        assertEquals(1, restCalls.get())
        Log.i(TAG, "31-second REST fallback completed and drained")
    }

    @Test
    fun liveGatewayStreamsThroughProductionUiAndAudioTrack() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("liveGateway")
        assumeTrue("Optional live backend run", !url.isNullOrBlank())
        connect(requireNotNull(url), requireNotNull(args.getString("liveCookie")))
        render()
        val started = SystemClock.elapsedRealtime()
        click()
        compose.waitUntil(180_000) { controller.preparingId.value == null || errors.get() > 0 }
        assertEquals(0, errors.get())
        assertTrue("Must observe playback, not just a terminal state", controller.speakingId.value != null)
        val first = SystemClock.elapsedRealtime() - started
        screenshot("live-playing")
        Log.i(TAG, "LIVE first PCM/playback after $first ms")
        awaitFinished(180_000)
        Log.i(TAG, "LIVE completed and drained after ${SystemClock.elapsedRealtime() - started} ms")
        compose.waitUntil(5000) { !context.getSystemService(AudioManager::class.java).isMusicActive }
        assertFalse(context.getSystemService(AudioManager::class.java).isMusicActive)
    }

    private fun wav(): ByteArray =
        ByteBuffer
            .allocate(44 + pcm.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray())
                putInt(36 + pcm.size)
                put("WAVEfmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(24_000)
                putInt(48_000)
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(pcm.size)
                put(pcm)
            }.array()

    companion object {
        private const val TAG = "SpeechDevice1883"
        private const val TEXT =
            "This is the first sentence for the read aloud emulator test. " +
                "Playback should start before the remaining sentences finish generating. " +
                "This last sentence checks that the stream drains completely."
    }
}
