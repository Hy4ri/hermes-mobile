package com.m57.hermescontrol.data.ws

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okio.ByteString

/**
 * Keep the test listener's behavior, but acknowledge peer close frames so MockWebServer's
 * WebSocket task can finish. WebSocketListener's default onClosing is a no-op.
 */
internal fun MockResponse.withClosingWebSocketUpgrade(delegate: WebSocketListener): MockResponse =
    withWebSocketUpgrade(
        object : WebSocketListener() {
            override fun onOpen(
                webSocket: WebSocket,
                response: Response,
            ) = delegate.onOpen(webSocket, response)

            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) = delegate.onMessage(webSocket, text)

            override fun onMessage(
                webSocket: WebSocket,
                bytes: ByteString,
            ) = delegate.onMessage(webSocket, bytes)

            override fun onClosing(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                try {
                    delegate.onClosing(webSocket, code, reason)
                } finally {
                    // The delegate may already have initiated a close; a false return is valid.
                    webSocket.close(code, reason)
                }
            }

            override fun onClosed(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) = delegate.onClosed(webSocket, code, reason)

            override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: Response?,
            ) = delegate.onFailure(webSocket, t, response)
        },
    )
