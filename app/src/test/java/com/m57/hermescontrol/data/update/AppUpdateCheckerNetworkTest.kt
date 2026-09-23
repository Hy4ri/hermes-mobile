package com.m57.hermescontrol.data.update

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
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun checker(): AppUpdateChecker =
        AppUpdateChecker(
            client = OkHttpClient(),
            apiBaseUrl = server.url("/repos/Hy4ri/hermes-mobile").toString(),
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

            assertNull(checker().fetchLatestRelease())
            assertNull(checker().fetchLatestRelease(includeReleaseCandidates = true))
        }

    @Test
    fun downloadApk_cancellingCoroutineCancelsNetworkAndDeletesPartialFile() =
        runTest {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val destination = File.createTempFile("hermes-update", ".apk")
            destination.writeText("partial")
            val download =
                launch(Dispatchers.IO) {
                    checker().downloadApk(server.url("/slow.apk").toString(), destination) {}
                }

            assertEquals("/slow.apk", server.takeRequest(5, TimeUnit.SECONDS)?.path)
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
