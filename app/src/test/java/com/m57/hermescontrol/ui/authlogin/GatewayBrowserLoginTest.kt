package com.m57.hermescontrol.ui.authlogin

import android.content.Context
import android.util.Log
import com.m57.hermescontrol.data.remote.CleartextPolicy
import com.m57.hermescontrol.data.remote.CookieManager
import com.m57.hermescontrol.data.remote.LoginKeepAliveService
import com.m57.hermescontrol.data.remote.LoopbackCallbackServer
import com.m57.hermescontrol.data.remote.NativeCallback
import com.m57.hermescontrol.data.remote.ServerEndpoint
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayBrowserLoginTest {
    private val context = mockk<Context>()
    private val listener = mockk<LoopbackCallbackServer>()
    private val client = OkHttpClient()
    private val endpoint = ServerEndpoint.parse("https://gateway.example/", CleartextPolicy.DENY)
    private val openBrowser = mockk<(String) -> Unit>()

    @Before
    fun setUp() {
        mockkObject(LoginKeepAliveService.Companion, LoopbackCallbackServer.Companion, CookieManager)
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { LoopbackCallbackServer.open() } returns listener
        every { listener.redirectUri } returns "http://127.0.0.1:5000/callback"
        every { listener.close() } just Runs
        every { LoginKeepAliveService.start(context) } just Runs
        every { LoginKeepAliveService.stop(context) } just Runs
        every { openBrowser(any()) } just Runs
    }

    @After
    fun tearDown() {
        unmockkObject(LoginKeepAliveService.Companion, LoopbackCallbackServer.Companion, CookieManager)
        unmockkStatic(Log::class)
    }

    @Test
    fun `service startup rejection returns an error and closes the listener without opening browser`() =
        runTest {
            for (failure in listOf(
                SecurityException("permission denied"),
                IllegalStateException("start not allowed"),
            )) {
                every { LoginKeepAliveService.start(context) } throws failure

                val result = runNativeBrowserLogin(context, client, endpoint, "oidc", openBrowser)

                assertEquals(BrowserLoginResult.ConnectionFailed(failure.message.orEmpty()), result)
            }
            verify(exactly = 0) { openBrowser(any()) }
            coVerify(exactly = 0) { listener.awaitCallback(any(), any()) }
            verify(exactly = 2) { LoginKeepAliveService.stop(context) }
            verify(exactly = 2) { listener.close() }
        }

    @Test
    fun `successful login stops service and closes listener after token redemption`() =
        runTest {
            MockWebServer().use { gateway ->
                gateway.enqueue(MockResponse().setBody("""{"access_token":"access","provider":"oidc"}"""))
                gateway.enqueue(MockResponse().setBody("""{"ticket":"ws-ticket"}"""))
                val localEndpoint =
                    ServerEndpoint.parse(gateway.url("/").toString(), CleartextPolicy.ALLOW_WITH_WARNING)
                coEvery { listener.awaitCallback(any(), BROWSER_LOGIN_TIMEOUT_MS) } returns NativeCallback.Code("code")
                every { CookieManager.installNativeSession(any(), localEndpoint) } just Runs

                val result = runNativeBrowserLogin(context, client, localEndpoint, "oidc", openBrowser)

                assertEquals(BrowserLoginResult.Success("ws-ticket"), result)
                assertEquals("/auth/native/token", gateway.takeRequest().path)
                assertEquals("/api/auth/ws-ticket", gateway.takeRequest().path)
                verify(exactly = 1) { CookieManager.installNativeSession(any(), localEndpoint) }
                verifyCleanup()
            }
        }

    @Test
    fun `browser launch failure returns NoBrowser and cleans up`() =
        runTest {
            every { openBrowser(any()) } throws IllegalStateException("no browser")

            assertEquals(
                BrowserLoginResult.NoBrowser,
                runNativeBrowserLogin(context, client, endpoint, "oidc", openBrowser),
            )

            coVerify(exactly = 0) { listener.awaitCallback(any(), any()) }
            verifyCleanup()
        }

    @Test
    fun `browser launch cancellation propagates and cleans up`() =
        runTest {
            val cancellation = CancellationException("cancelled while opening browser")
            every { openBrowser(any()) } throws cancellation

            try {
                runNativeBrowserLogin(context, client, endpoint, "oidc", openBrowser)
                throw AssertionError("Expected cancellation")
            } catch (actual: CancellationException) {
                assertSame(cancellation, actual)
            }

            verifyCleanup()
        }

    @Test
    fun `cancelling callback wait stops service and closes listener`() =
        runTest {
            coEvery { listener.awaitCallback(any(), any()) } coAnswers { awaitCancellation() }
            val job = launch { runNativeBrowserLogin(context, client, endpoint, "oidc", openBrowser) }
            runCurrent()
            coVerify(exactly = 1) { listener.awaitCallback(any(), any()) }

            job.cancel()
            job.join()

            assertTrue(job.isCancelled)
            verifyCleanup()
        }

    @Test
    fun `closed listener returns cancellation and cleans up`() =
        runTest {
            coEvery { listener.awaitCallback(any(), any()) } returns NativeCallback.Closed

            assertEquals(
                BrowserLoginResult.Cancelled,
                runNativeBrowserLogin(context, client, endpoint, "oidc", openBrowser),
            )

            verifyCleanup()
        }

    @Test
    fun `cleanup failure does not replace browser cancellation`() =
        runTest {
            val cancellation = CancellationException("cancelled")
            every { openBrowser(any()) } throws cancellation
            every { LoginKeepAliveService.stop(context) } throws SecurityException("stop not allowed")

            try {
                runNativeBrowserLogin(context, client, endpoint, "oidc", openBrowser)
                throw AssertionError("Expected cancellation")
            } catch (actual: CancellationException) {
                assertSame(cancellation, actual)
            }

            verifyCleanup()
        }

    private fun verifyCleanup() {
        verify(exactly = 1) { LoginKeepAliveService.stop(context) }
        verify(exactly = 1) { listener.close() }
        verifyOrder {
            LoginKeepAliveService.start(context)
            openBrowser(any())
            LoginKeepAliveService.stop(context)
            listener.close()
        }
    }
}
