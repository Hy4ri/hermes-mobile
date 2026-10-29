package com.m57.hermescontrol.data.update

import com.m57.hermescontrol.data.remote.TlsTestContext
import com.m57.hermescontrol.data.remote.TlsTestIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Endpoint contract for the opt-in release-candidate channel: the stable path
 * must keep using `releases/latest` (GitHub excludes pre-releases there), and
 * only the opt-in path may scan the releases list.
 */
class AppUpdateCheckerNetworkTest {
    private val identity = TlsTestIdentity("server")
    private val serverTls = TlsTestContext(identity)
    private val clientTls = TlsTestContext(trusted = listOf(identity.certificate))
    private val secureServers = mutableListOf<MockWebServer>()
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        secureServers.forEach { it.shutdown() }
    }

    private fun checker(
        nowMs: () -> Long = { System.currentTimeMillis() },
        metadataTtlMs: Long = 60_000L,
    ): AppUpdateChecker =
        AppUpdateChecker(
            client = OkHttpClient(),
            apiBaseUrl = server.url("/repos/Hy4ri/hermes-mobile").toString(),
            nowMs = nowMs,
            metadataTtlMs = metadataTtlMs,
        )

    private fun httpsServer(): MockWebServer =
        MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            start()
            secureServers.add(this)
        }

    private fun httpsChecker(): AppUpdateChecker =
        AppUpdateChecker(
            client =
                OkHttpClient
                    .Builder()
                    .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build(),
        )

    private fun enqueue(
        body: String,
        code: Int = 200,
        headers: Map<String, String> = emptyMap(),
    ) {
        val response = MockResponse().setResponseCode(code).setBody(body)
        headers.forEach(response::addHeader)
        server.enqueue(response)
    }

    @Test
    fun fetchLatestRelease_defaultUsesStableLatestEndpoint() =
        runTest {
            enqueue(STABLE_BODY)

            val info = checker().fetchLatestRelease()

            assertEquals("v1.24.2", info?.tagName)
            assertEquals("/repos/Hy4ri/hermes-mobile/releases/latest", server.takeRequest().path)
        }

    @Test
    fun fetchLatestRelease_releaseCandidatesScanTheReleasesList() =
        runTest {
            enqueue(STABLE_BODY)
            enqueue(RELEASES_BODY)

            val info = checker().fetchLatestRelease(includeReleaseCandidates = true)

            assertEquals("v1.25.0-rc.3", info?.tagName)
            assertEquals("/repos/Hy4ri/hermes-mobile/releases/latest", server.takeRequest().path)
            assertEquals("/repos/Hy4ri/hermes-mobile/releases?per_page=100&page=1", server.takeRequest().path)
        }

    @Test
    fun fetchLatestRelease_stableFallsBackToReleasesWhenLatestIsNotUsable() =
        runTest {
            enqueue("{}")
            enqueue(RELEASES_BODY)

            val info = checker().fetchLatestRelease()

            assertEquals("v1.24.2", info?.tagName)
            assertEquals("/repos/Hy4ri/hermes-mobile/releases/latest", server.takeRequest().path)
            assertEquals("/repos/Hy4ri/hermes-mobile/releases?per_page=100&page=1", server.takeRequest().path)
        }

    @Test
    fun fetchLatestRelease_rcScanFollowsGithubPagination() =
        runTest {
            enqueue(STABLE_BODY)
            val nextPage = server.url("/repos/Hy4ri/hermes-mobile/releases?per_page=100&page=2")
            enqueue(RELEASES_BODY, headers = mapOf("Link" to "<$nextPage>; rel=\"next\""))
            enqueue(
                """[{"tag_name":"v1.26.0-rc.1","prerelease":true,"assets":[{"name":"release.apk","size":5,"browser_download_url":"https://example.com/new.apk"}]}]""",
            )

            val info = checker().fetchLatestRelease(includeReleaseCandidates = true)

            assertEquals("v1.26.0-rc.1", info?.tagName)
            assertEquals("/repos/Hy4ri/hermes-mobile/releases/latest", server.takeRequest().path)
            assertEquals("/repos/Hy4ri/hermes-mobile/releases?per_page=100&page=1", server.takeRequest().path)
            assertEquals("/repos/Hy4ri/hermes-mobile/releases?per_page=100&page=2", server.takeRequest().path)
        }

    @Test
    fun fetchLatestRelease_rcScanAcceptsRepositoriesPaginationFormat() =
        runTest {
            enqueue(STABLE_BODY)
            val nextPage = server.url("/repositories/1270568813/releases?per_page=100&page=2")
            enqueue(RELEASES_BODY, headers = mapOf("Link" to "<$nextPage>; rel=\"next\""))
            enqueue(
                """[{"tag_name":"v2.0.0-rc.1","prerelease":true,"assets":[{"name":"release.apk","size":5,"browser_download_url":"https://example.com/new.apk"}]}]""",
            )

            val info = checker().fetchLatestRelease(includeReleaseCandidates = true)

            assertEquals("v2.0.0-rc.1", info?.tagName)
            assertEquals("/repos/Hy4ri/hermes-mobile/releases/latest", server.takeRequest().path)
            assertEquals("/repos/Hy4ri/hermes-mobile/releases?per_page=100&page=1", server.takeRequest().path)
            assertEquals("/repositories/1270568813/releases?per_page=100&page=2", server.takeRequest().path)
        }

    @Test
    fun fetchLatestRelease_releaseCandidatesStillIgnoreAlphaAndBetaButKeepStable() =
        runTest {
            enqueue(STABLE_BODY)
            enqueue(ALPHA_BETA_BODY)

            assertEquals("v1.24.2", checker().fetchLatestRelease(includeReleaseCandidates = true)?.tagName)
        }

    @Test
    fun fetchLatestRelease_nonSuccessYieldsNull() =
        runTest {
            // Each channel checks the stable endpoint and falls back to the list on 404.
            enqueue("", code = 404)
            enqueue("", code = 404)
            enqueue("", code = 404)
            enqueue("", code = 404)

            var now = 1_000L
            val testChecker = checker(nowMs = { now })
            assertNull(testChecker.fetchLatestRelease())
            // Advance clock beyond metadata TTL so candidate check doesn't coalesce or skip re-checking
            now += 60_001L
            assertNull(testChecker.fetchLatestRelease(includeReleaseCandidates = true))
        }

    @Test
    fun downloadApk_cancellingCoroutineCancelsNetworkAndDeletesPartialFile() =
        runTest {
            val secure = httpsServer()
            secure.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val destination = File.createTempFile("hermes-update", ".apk")
            destination.writeText("partial")
            val download =
                launch(Dispatchers.IO) {
                    httpsChecker().downloadApk(secure.url("/slow.apk").toString(), destination) {}
                }

            assertEquals("/slow.apk", secure.takeRequest(5, TimeUnit.SECONDS)?.path)
            download.cancelAndJoin()

            assertFalse(destination.exists())
        }

    @Test
    fun downloadApk_invalidUrlReturnsFailureAndDeletesDestination() =
        runTest {
            val destination = File.createTempFile("hermes-update", ".apk")
            destination.writeText("partial")

            val downloaded = checker().downloadApk("file:///tmp/release.apk", destination) {}

            assertFalse(downloaded)
            assertFalse(destination.exists())
        }

    @Test
    fun downloadApk_rejectsHttpBeforeSendingRequest() =
        runTest {
            enqueue("insecure APK")
            val destination = File.createTempFile("hermes-http-update", ".apk")
            destination.writeText("partial")
            try {
                val result = checker().downloadApk(server.url("/release.apk").toString(), destination) {}

                assertFalse(result)
                assertFalse(destination.exists())
                assertEquals(0, server.requestCount)
            } finally {
                destination.delete()
            }
        }

    @Test
    fun downloadApk_acceptsHttpsCaseInsensitively() =
        runTest {
            val secure = httpsServer()
            secure.enqueue(MockResponse().setBody("secure APK"))
            val destination = File.createTempFile("hermes-https-update", ".apk")
            try {
                val url = secure.url("/release.apk").toString().replaceFirst("https:", "HTTPS:")
                val result = httpsChecker().downloadApk(url, destination) {}

                assertTrue(result)
                assertEquals("secure APK", destination.readText())
                assertEquals("/release.apk", secure.takeRequest(5, TimeUnit.SECONDS)?.path)
            } finally {
                destination.delete()
            }
        }

    @Test
    fun downloadApk_rejectsHttpsDowngradeBeforeSendingHttpRequest() =
        runTest {
            val secure = httpsServer()
            secure.enqueue(
                MockResponse().setResponseCode(302).addHeader("Location", server.url("/insecure.apk")),
            )
            enqueue("insecure APK")
            val destination = File.createTempFile("hermes-downgrade-update", ".apk")
            destination.writeText("partial")
            try {
                val result = httpsChecker().downloadApk(secure.url("/release.apk").toString(), destination) {}

                assertFalse(result)
                assertFalse(destination.exists())
                assertEquals("/release.apk", secure.takeRequest(5, TimeUnit.SECONDS)?.path)
                assertEquals(0, server.requestCount)
            } finally {
                destination.delete()
            }
        }

    @Test
    fun downloadApk_followsHttpsRedirectToAnotherServer() =
        runTest {
            val first = httpsServer()
            val second = httpsServer()
            first.enqueue(
                MockResponse().setResponseCode(302).addHeader("Location", second.url("/signed.apk")),
            )
            second.enqueue(MockResponse().setBody("secure redirected APK"))
            val destination = File.createTempFile("hermes-redirect-update", ".apk")
            try {
                val result = httpsChecker().downloadApk(first.url("/release.apk").toString(), destination) {}

                assertTrue(result)
                assertEquals("secure redirected APK", destination.readText())
                assertEquals("/release.apk", first.takeRequest(5, TimeUnit.SECONDS)?.path)
                assertEquals("/signed.apk", second.takeRequest(5, TimeUnit.SECONDS)?.path)
            } finally {
                destination.delete()
            }
        }

    private companion object {
        const val STABLE_BODY = """
{
  "tag_name": "v1.24.2",
  "prerelease": false,
  "draft": false,
  "assets": [
    {
      "name": "hermes-mobile-v1.24.2.apk",
      "size": 12345,
      "browser_download_url": "https://example.com/stable.apk"
    }
  ]
}
"""

        const val RELEASES_BODY = """
[
  {
    "tag_name": "v1.25.0-rc.3",
    "prerelease": true,
    "draft": false,
    "assets": [
      {
        "name": "hermes-mobile-v1.25.0-rc.3.apk",
        "size": 999,
        "browser_download_url": "https://example.com/rc.apk"
      }
    ]
  },
  {
    "tag_name": "v1.24.2",
    "prerelease": false,
    "draft": false,
    "assets": [
      {
        "name": "hermes-mobile-v1.24.2.apk",
        "size": 12345,
        "browser_download_url": "https://example.com/stable.apk"
      }
    ]
  }
]
"""

        const val ALPHA_BETA_BODY = """
[
  {
    "tag_name": "v1.30.0-alpha.1",
    "prerelease": true,
    "assets": [
      {
        "name": "hermes-mobile-v1.30.0-alpha.1.apk",
        "size": 1,
        "browser_download_url": "https://example.com/alpha.apk"
      }
    ]
  },
  {
    "tag_name": "v1.29.0-beta.4",
    "prerelease": true,
    "assets": [
      {
        "name": "hermes-mobile-v1.29.0-beta.4.apk",
        "size": 1,
        "browser_download_url": "https://example.com/beta.apk"
      }
    ]
  }
]
"""
    }
}
