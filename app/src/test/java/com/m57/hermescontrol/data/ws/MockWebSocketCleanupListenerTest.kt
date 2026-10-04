package com.m57.hermescontrol.data.ws

import io.mockk.every
import io.mockk.mockk
import io.mockk.verifySequence
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class MockWebSocketCleanupListenerTest {
    private val socket = mockk<WebSocket>()
    private val delegate = mockk<WebSocketListener>(relaxed = true)
    private val listener: WebSocketListener
        get() = requireNotNull(MockResponse().withClosingWebSocketUpgrade(delegate).webSocketListener)

    @Test
    fun acknowledgesCloseAfterDelegatedCallback() {
        every { socket.close(1000, "complete") } returns true

        listener.onClosing(socket, 1000, "complete")

        verifySequence {
            delegate.onClosing(socket, 1000, "complete")
            socket.close(1000, "complete")
        }
    }

    @Test
    fun acknowledgesCloseEvenWhenDelegatedCallbackThrows() {
        val failure = IllegalStateException("callback failure")
        every { delegate.onClosing(socket, 1000, "complete") } throws failure
        every { socket.close(1000, "complete") } returns true

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                listener.onClosing(socket, 1000, "complete")
            }

        assertSame(failure, thrown)
        verifySequence {
            delegate.onClosing(socket, 1000, "complete")
            socket.close(1000, "complete")
        }
    }

    @Test
    fun acceptsAlreadyInitiatedCustomCloseWithoutOverridingIt() {
        every { socket.close(1001, "custom close") } returns true
        every { socket.close(1000, "complete") } returns false
        every { delegate.onClosing(socket, 1000, "complete") } answers {
            socket.close(1001, "custom close")
            Unit
        }

        listener.onClosing(socket, 1000, "complete")

        verifySequence {
            delegate.onClosing(socket, 1000, "complete")
            socket.close(1001, "custom close")
            socket.close(1000, "complete")
        }
    }

    @Test
    fun forwardsOpenWithOriginalSocketAndResponse() {
        val response = mockk<Response>()

        listener.onOpen(socket, response)

        verifySequence { delegate.onOpen(socket, response) }
    }

    @Test
    fun forwardsBothTextAndBinaryMessages() {
        val bytes = "binary message".encodeUtf8()
        val wrapped = listener

        wrapped.onMessage(socket, "text message")
        wrapped.onMessage(socket, bytes)

        verifySequence {
            delegate.onMessage(socket, "text message")
            delegate.onMessage(socket, bytes)
        }
    }

    @Test
    fun forwardsClosedWithOriginalCodeAndReason() {
        listener.onClosed(socket, 1001, "server close")

        verifySequence { delegate.onClosed(socket, 1001, "server close") }
    }

    @Test
    fun forwardsFailureWithOriginalThrowableAndNullableResponse() {
        val failure = IllegalStateException("transport failure")
        val response = mockk<Response>()
        val wrapped = listener

        wrapped.onFailure(socket, failure, response)
        wrapped.onFailure(socket, failure, null)

        verifySequence {
            delegate.onFailure(socket, failure, response)
            delegate.onFailure(socket, failure, null)
        }
    }
}
