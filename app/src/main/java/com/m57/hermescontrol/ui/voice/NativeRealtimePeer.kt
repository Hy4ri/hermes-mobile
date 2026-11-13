package com.m57.hermescontrol.ui.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sqrt

/** Single-use, foreground audio transport. The caller owns authorization and SDP signaling. */
class NativeRealtimePeer(
    context: Context,
    private val onEvent: (String) -> Unit,
    private val onState: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val audioManager = applicationContext.getSystemService(AudioManager::class.java)
    private val closed = AtomicBoolean(false)
    private val microphoneMuted = AtomicBoolean(false)

    @Volatile
    private var peerThread: Thread? = null

    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "hermes-webrtc").also { peerThread = it }
        }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var started = false
    private var factory: PeerConnectionFactory? = null
    private var audioDevice: JavaAudioDeviceModule? = null
    private var audioSource: AudioSource? = null
    private var microphone: AudioTrack? = null
    private var peer: PeerConnection? = null
    private var events: DataChannel? = null
    private var focusRequest: AudioFocusRequest? = null
    private var previousAudioMode: Int? = null
    private var nextLevelAt = 0L

    /** Runs [negotiate] with the local SDP; it must return the authenticated signaling answer. */
    suspend fun connect(negotiate: suspend (String) -> String) {
        check(!closed.get()) { "Native audio connection is closed" }
        val connection =
            scope.async {
                check(!started) { "Native audio connection has already started" }
                started = true
                try {
                    onState("connecting")
                    checkOpen()
                    acquireAudioFocus()
                    initializeFactory(applicationContext)
                    createAudioPeer()
                    val connectionPeer = checkNotNull(peer)
                    val offer = createOffer(connectionPeer)
                    setDescription(connectionPeer, offer, local = true)
                    checkOpen()
                    val answer = negotiate(offer.description)
                    checkOpen()
                    require(answer.isNotBlank()) { "Audio signaling returned an empty answer" }
                    setDescription(
                        connectionPeer,
                        SessionDescription(SessionDescription.Type.ANSWER, answer),
                        local = false,
                    )
                } catch (error: Exception) {
                    if (error !is CancellationException) fail("Native audio connection failed")
                    close()
                    throw error
                }
            }
        try {
            connection.await()
        } catch (error: CancellationException) {
            close()
            throw error
        }
    }

    /** Returns the actual channel send result, not merely whether the event was queued locally. */
    fun send(event: String): Boolean {
        if (closed.get()) return false
        if (Thread.currentThread() === peerThread) return sendOnPeerThread(event)
        return try {
            executor.submit<Boolean> { sendOnPeerThread(event) }.get()
        } catch (_: RejectedExecutionException) {
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: ExecutionException) {
            dispatch { fail("Realtime data channel send failed") }
            false
        }
    }

    fun mute(muted: Boolean) {
        microphoneMuted.set(muted)
        dispatch {
            microphone?.setEnabled(!muted)
            if (muted) onLevel(0f)
        }
    }

    /** Stops provider speech only; this never cancels a Hermes task or coroutine. */
    fun interrupt() {
        dispatch {
            sendOnPeerThread("{\"type\":\"response.cancel\"}")
            sendOnPeerThread("{\"type\":\"output_audio_buffer.clear\"}")
        }
    }

    /** Cancels in-flight negotiation and releases native resources on their owning executor. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        executor.execute {
            try {
                releaseResources()
            } finally {
                dispatcher.close()
            }
        }
    }

    private fun createAudioPeer() {
        val module =
            JavaAudioDeviceModule
                .builder(applicationContext)
                .setAudioFormat(AudioFormat.ENCODING_PCM_16BIT)
                .setEnableVolumeLogger(false)
                .setSamplesReadyCallback(::measureLevel)
                .setAudioRecordErrorCallback(
                    object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                        override fun onWebRtcAudioRecordInitError(errorMessage: String) = captureFailed()

                        override fun onWebRtcAudioRecordStartError(
                            errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode,
                            errorMessage: String,
                        ) = captureFailed()

                        override fun onWebRtcAudioRecordError(errorMessage: String) = captureFailed()
                    },
                ).setAudioTrackErrorCallback(
                    object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                        override fun onWebRtcAudioTrackInitError(errorMessage: String) = playoutFailed()

                        override fun onWebRtcAudioTrackStartError(
                            errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode,
                            errorMessage: String,
                        ) = playoutFailed()

                        override fun onWebRtcAudioTrackError(errorMessage: String) = playoutFailed()
                    },
                ).createAudioDeviceModule()
        audioDevice = module
        val peerFactory =
            PeerConnectionFactory
                .builder()
                .setAudioDeviceModule(module)
                .createPeerConnectionFactory()
        factory = peerFactory
        val source = peerFactory.createAudioSource(MediaConstraints())
        audioSource = source
        val track = peerFactory.createAudioTrack("hermes-microphone", source)
        microphone = track
        track.setEnabled(!microphoneMuted.get())
        val configuration =
            PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
        val connectionPeer =
            checkNotNull(peerFactory.createPeerConnection(configuration, peerObserver)) {
                "Native audio peer could not be created"
            }
        peer = connectionPeer
        // addTrack creates a send/receive audio transceiver. Received audio is played by the ADM;
        // unlike video, Android remote audio needs neither a renderer nor an application sink.
        checkNotNull(connectionPeer.addTrack(track, listOf("hermes-audio")))
        val channel = checkNotNull(connectionPeer.createDataChannel("oai-events", DataChannel.Init()))
        events = channel
        channel.registerObserver(channelObserver)
    }

    private suspend fun createOffer(connectionPeer: PeerConnection): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            connectionPeer.createOffer(
                object : DescriptionObserver() {
                    override fun onCreateSuccess(description: SessionDescription) {
                        if (continuation.isActive) continuation.resume(description)
                    }

                    override fun onCreateFailure(error: String) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException("Could not create audio offer"))
                        }
                    }
                },
                MediaConstraints(),
            )
        }

    private suspend fun setDescription(
        connectionPeer: PeerConnection,
        description: SessionDescription,
        local: Boolean,
    ): Unit =
        suspendCancellableCoroutine { continuation ->
            val observer =
                object : DescriptionObserver() {
                    override fun onSetSuccess() {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onSetFailure(error: String) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException("Could not set audio description"))
                        }
                    }
                }
            if (local) {
                connectionPeer.setLocalDescription(observer, description)
            } else {
                connectionPeer.setRemoteDescription(observer, description)
            }
        }

    private fun sendOnPeerThread(event: String): Boolean {
        if (closed.get()) return false
        val channel = events ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(event.toByteArray(Charsets.UTF_8)), false))
    }

    private val channelObserver =
        object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                dispatch {
                    when (events?.state()) {
                        DataChannel.State.OPEN -> onState("connected")
                        DataChannel.State.CLOSING, DataChannel.State.CLOSED -> fail("Realtime data channel closed")
                        else -> Unit
                    }
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (closed.get() || buffer.binary) return
                // WebRTC frees the buffer as soon as this callback returns.
                val event = Charsets.UTF_8.decode(buffer.data).toString()
                dispatch { onEvent(event) }
            }
        }

    private val peerObserver =
        object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                when (newState) {
                    PeerConnection.IceConnectionState.FAILED -> dispatch { fail("Audio ICE connection failed") }
                    PeerConnection.IceConnectionState.DISCONNECTED -> dispatch { fail("Audio ICE disconnected") }
                    PeerConnection.IceConnectionState.CLOSED -> dispatch { fail("Audio ICE connection closed") }
                    else -> Unit
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                when (newState) {
                    PeerConnection.PeerConnectionState.FAILED -> dispatch { fail("Audio connection failed") }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> dispatch { fail("Audio disconnected") }
                    PeerConnection.PeerConnectionState.CLOSED -> dispatch { fail("Audio connection closed") }
                    else -> Unit
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit

            override fun onIceCandidate(candidate: IceCandidate) = Unit

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

            override fun onAddStream(stream: MediaStream) = Unit

            override fun onRemoveStream(stream: MediaStream) = Unit

            override fun onDataChannel(channel: DataChannel) = Unit

            override fun onRenegotiationNeeded() = Unit
        }

    private fun measureLevel(samples: JavaAudioDeviceModule.AudioSamples) {
        if (closed.get() || microphoneMuted.get()) return
        val now = SystemClock.elapsedRealtime()
        if (now < nextLevelAt) return
        nextLevelAt = now + LEVEL_INTERVAL_MS
        val data = samples.data
        val count = data.size / 2
        if (count == 0) return
        var energy = 0.0
        var index = 0
        while (index + 1 < data.size) {
            val sample = ((data[index].toInt() and 0xff) or (data[index + 1].toInt() shl 8)).toShort()
            val normalized = sample / 32768.0
            energy += normalized * normalized
            index += 2
        }
        val level = sqrt(energy / count).toFloat().coerceIn(0f, 1f)
        dispatch { onLevel(if (microphoneMuted.get()) 0f else level) }
    }

    private fun acquireAudioFocus() {
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                ).setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                    ) {
                        dispatch { fail("Audio focus lost") }
                    }
                }.build()
        check(audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "Audio focus was not granted"
        }
        focusRequest = request
        previousAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    private fun releaseResources() {
        try {
            events?.let { channel ->
                channel.unregisterObserver()
                channel.close()
                channel.dispose()
            }
            events = null
        } finally {
            try {
                peer?.dispose()
                peer = null
            } finally {
                try {
                    microphone?.dispose()
                    microphone = null
                    audioSource?.dispose()
                    audioSource = null
                    factory?.dispose()
                    factory = null
                } finally {
                    try {
                        audioDevice?.release()
                        audioDevice = null
                    } finally {
                        focusRequest?.let(audioManager::abandonAudioFocusRequest)
                        focusRequest = null
                        previousAudioMode?.let { mode ->
                            if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) audioManager.mode = mode
                        }
                        previousAudioMode = null
                    }
                }
            }
        }
    }

    private fun checkOpen() = check(!closed.get()) { "Native audio connection is closed" }

    private fun captureFailed() = dispatch { fail("Microphone capture failed") }

    private fun playoutFailed() = dispatch { fail("Audio playback failed") }

    private fun fail(message: String) {
        if (closed.get()) return
        try {
            onState("error:$message")
        } finally {
            close()
        }
    }

    private fun dispatch(block: () -> Unit) {
        if (closed.get()) return
        try {
            executor.execute { if (!closed.get()) block() }
        } catch (_: RejectedExecutionException) {
            // A racing close has already stopped this single-use transport.
        }
    }

    private open class DescriptionObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit

        override fun onSetSuccess() = Unit

        override fun onCreateFailure(error: String) = Unit

        override fun onSetFailure(error: String) = Unit
    }

    private companion object {
        const val LEVEL_INTERVAL_MS = 100L
        var initialized = false

        @Synchronized
        fun initializeFactory(context: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions(),
            )
            initialized = true
        }
    }
}
