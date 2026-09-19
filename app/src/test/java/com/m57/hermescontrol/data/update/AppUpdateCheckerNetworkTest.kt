package com.m57.hermescontrol.data.update

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

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
    ) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
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
            enqueue(RELEASES_BODY)

            val info = checker().fetchLatestRelease(includeReleaseCandidates = true)

            assertEquals("v1.25.0-rc.3", info?.tagName)
            assertEquals("/repos/Hy4ri/hermes-mobile/releases?per_page=20", server.takeRequest().path)
        }

    @Test
    fun fetchLatestRelease_releaseCandidatesStillIgnoreAlphaAndBeta() =
        runTest {
            enqueue(ALPHA_BETA_BODY)

            assertNull(checker().fetchLatestRelease(includeReleaseCandidates = true))
        }

    @Test
    fun fetchLatestRelease_nonSuccessYieldsNull() =
        runTest {
            // One queued response per request: stable path, then RC path.
            enqueue("", code = 404)
            enqueue("", code = 404)

            assertNull(checker().fetchLatestRelease())
            assertNull(checker().fetchLatestRelease(includeReleaseCandidates = true))
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
