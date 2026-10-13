package com.m57.hermescontrol.data.update

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AppUpdateCheckerCacheTest {
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

    private fun checker(
        nowMs: () -> Long = { 1_000L },
        metadataTtlMs: Long = 60_000L,
        baseUrl: String = server.url("/repos/Hy4ri/hermes-mobile").toString(),
    ): AppUpdateChecker =
        AppUpdateChecker(
            client = OkHttpClient(),
            apiBaseUrl = baseUrl,
            nowMs = nowMs,
            metadataTtlMs = metadataTtlMs,
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
    fun fetchLatestRelease_withinTtlDoesNotMakeHttpRequests() =
        runTest {
            enqueue(STABLE_BODY)

            var currentTime = 10_000L
            val checkerInstance = checker(nowMs = { currentTime })

            val first = checkerInstance.fetchLatestRelease()
            assertEquals("v1.24.2", first?.tagName)
            assertEquals(1, server.requestCount)

            // Second call within TTL (30s later < 60s TTL)
            currentTime += 30_000L
            val second = checkerInstance.fetchLatestRelease()
            assertEquals("v1.24.2", second?.tagName)
            // No extra HTTP request made!
            assertEquals(1, server.requestCount)
        }

    @Test
    fun fetchLatestRelease_concurrentRequestsCoalesceToSingleScan() =
        runTest {
            enqueue(STABLE_BODY)
            enqueue(RELEASES_BODY_PAGE_1)

            val checkerInstance = checker()

            val d1 = async { checkerInstance.fetchLatestRelease(includeReleaseCandidates = true) }
            val d2 = async { checkerInstance.fetchLatestRelease(includeReleaseCandidates = true) }
            val results = awaitAll(d1, d2)

            assertEquals("v1.25.0-rc.3", results[0]?.tagName)
            assertEquals("v1.25.0-rc.3", results[1]?.tagName)
            // 1 for stable latest + 1 for releases list = 2 requests total
            assertEquals(2, server.requestCount)
        }

    @Test
    fun fetchLatestRelease_expiredCacheSendsETagConditionalHeaderAndReusesOn304() =
        runTest {
            // First call (T=0): 200 OK with ETag
            enqueue(STABLE_BODY, headers = mapOf("ETag" to "\"tag-12345\""))

            var currentTime = 1_000L
            val checkerInstance = checker(nowMs = { currentTime }, metadataTtlMs = 60_000L)

            val first = checkerInstance.fetchLatestRelease()
            assertEquals("v1.24.2", first?.tagName)
            val req1 = server.takeRequest()
            assertNull(req1.getHeader("If-None-Match"))

            // Advance clock past TTL (T=65s)
            currentTime += 65_000L
            // Server responds 304 Not Modified
            enqueue("", code = 304)

            val second = checkerInstance.fetchLatestRelease()
            assertEquals("v1.24.2", second?.tagName)
            val req2 = server.takeRequest()
            assertEquals("\"tag-12345\"", req2.getHeader("If-None-Match"))
        }

    @Test
    fun fetchLatestRelease_expiredCacheSendsLastModifiedFallbackHeaderAndReusesOn304() =
        runTest {
            // First call: 200 OK with Last-Modified (no ETag)
            enqueue(STABLE_BODY, headers = mapOf("Last-Modified" to "Wed, 21 Oct 2025 07:28:00 GMT"))

            var currentTime = 1_000L
            val checkerInstance = checker(nowMs = { currentTime }, metadataTtlMs = 60_000L)

            val first = checkerInstance.fetchLatestRelease()
            assertEquals("v1.24.2", first?.tagName)
            val req1 = server.takeRequest()
            assertNull(req1.getHeader("If-Modified-Since"))

            // Advance clock past TTL
            currentTime += 65_000L
            enqueue("", code = 304)

            val second = checkerInstance.fetchLatestRelease()
            assertEquals("v1.24.2", second?.tagName)
            val req2 = server.takeRequest()
            assertEquals("Wed, 21 Oct 2025 07:28:00 GMT", req2.getHeader("If-Modified-Since"))
        }

    @Test
    fun fetchLatestRelease_revalidatesAllPaginationPagesWithConditionalHeadersAndReusesCachedNextLink() =
        runTest {
            enqueue(STABLE_BODY, headers = mapOf("ETag" to "\"stable-etag-1\""))
            val page2Url = server.url("/repos/Hy4ri/hermes-mobile/releases?per_page=100&page=2").toString()
            enqueue(
                RELEASES_BODY_PAGE_1,
                headers =
                    mapOf(
                        "ETag" to "\"page1-etag-1\"",
                        "Link" to "<$page2Url>; rel=\"next\"",
                    ),
            )
            enqueue(
                RELEASES_BODY_PAGE_2,
                headers = mapOf("ETag" to "\"page2-etag-1\""),
            )

            var currentTime = 1_000L
            val checkerInstance = checker(nowMs = { currentTime }, metadataTtlMs = 60_000L)

            val first = checkerInstance.fetchLatestRelease(includeReleaseCandidates = true)
            assertEquals("v1.26.0-rc.1", first?.tagName)
            assertEquals(3, server.requestCount)

            server.takeRequest() // latest
            server.takeRequest() // page 1
            server.takeRequest() // page 2

            // Expire cache
            currentTime += 70_000L
            // Revalidation responses: all 304 Not Modified without repeating Link header on page 1
            enqueue("", code = 304) // latest
            enqueue("", code = 304) // page 1 304 (Link absent in 304 retains cached next)
            enqueue("", code = 304) // page 2 304

            val second = checkerInstance.fetchLatestRelease(includeReleaseCandidates = true)
            assertEquals("v1.26.0-rc.1", second?.tagName)

            val revalLatest = server.takeRequest()
            assertEquals("\"stable-etag-1\"", revalLatest.getHeader("If-None-Match"))

            val revalPage1 = server.takeRequest()
            assertEquals("\"page1-etag-1\"", revalPage1.getHeader("If-None-Match"))
            assertEquals("/repos/Hy4ri/hermes-mobile/releases?per_page=100&page=1", revalPage1.path)

            val revalPage2 = server.takeRequest()
            assertEquals("\"page2-etag-1\"", revalPage2.getHeader("If-None-Match"))
            assertEquals("/repos/Hy4ri/hermes-mobile/releases?per_page=100&page=2", revalPage2.path)
        }

    @Test
    fun fetchLatestRelease_changed200OnRevalidationUpdatesPaginationAndData() =
        runTest {
            enqueue(STABLE_BODY, headers = mapOf("ETag" to "\"stable-1\""))
            enqueue(RELEASES_BODY_PAGE_1, headers = mapOf("ETag" to "\"page1-1\""))

            var currentTime = 1_000L
            val checkerInstance = checker(nowMs = { currentTime }, metadataTtlMs = 60_000L)

            val first = checkerInstance.fetchLatestRelease(includeReleaseCandidates = true)
            assertEquals("v1.25.0-rc.3", first?.tagName)
            server.takeRequest()
            server.takeRequest()

            // Expire cache
            currentTime += 65_000L

            // Revalidation: stable is 304, but page 1 changed (200 OK) with new release and a nextLink!
            enqueue("", code = 304)
            val page2Url = server.url("/repos/Hy4ri/hermes-mobile/releases?per_page=100&page=2").toString()
            enqueue(
                RELEASES_BODY_PAGE_1,
                code = 200,
                headers =
                    mapOf(
                        "ETag" to "\"page1-2\"",
                        "Link" to "<$page2Url>; rel=\"next\"",
                    ),
            )
            enqueue(
                RELEASES_BODY_PAGE_2,
                code = 200,
                headers = mapOf("ETag" to "\"page2-2\""),
            )

            val second = checkerInstance.fetchLatestRelease(includeReleaseCandidates = true)
            assertEquals("v1.26.0-rc.1", second?.tagName)

            server.takeRequest() // latest
            server.takeRequest() // page 1
            val reqPage2 = server.takeRequest() // page 2
            assertEquals("/repos/Hy4ri/hermes-mobile/releases?per_page=100&page=2", reqPage2.path)
        }

    @Test
    fun fetchLatestRelease_fullHistoryScanFindsHighestSemVerOnLaterPage() =
        runTest {
            enqueue(STABLE_BODY)
            // Page 1 has older releases
            val page2Url = server.url("/repos/Hy4ri/hermes-mobile/releases?per_page=100&page=2").toString()
            enqueue(
                RELEASES_BODY_PAGE_1,
                headers = mapOf("Link" to "<$page2Url>; rel=\"next\""),
            )
            // Page 2 has a much newer release that was published earlier/later
            enqueue(
                """[{"tag_name":"v1.99.0-rc.1","prerelease":true,"assets":[{"name":"hermes.apk","size":100,"browser_download_url":"https://example.com/rc99.apk"}]}]""",
            )

            val checkerInstance = checker()
            val result = checkerInstance.fetchLatestRelease(includeReleaseCandidates = true)
            assertEquals("v1.99.0-rc.1", result?.tagName)
        }

    @Test
    fun fetchLatestRelease_scansBeyondTenPagesWithoutArbitraryLimit() =
        runTest {
            enqueue(STABLE_BODY)

            val totalPages = 12
            for (p in 1..totalPages) {
                val nextHeader =
                    if (p < totalPages) {
                        val nextUrl =
                            server
                                .url(
                                    "/repos/Hy4ri/hermes-mobile/releases?per_page=100&page=${p + 1}",
                                ).toString()
                        mapOf("Link" to "<$nextUrl>; rel=\"next\"")
                    } else {
                        emptyMap()
                    }

                val tag = if (p == totalPages) "v2.50.0-rc.1" else "v1.0.$p-rc.1"
                val body =
                    """[{"tag_name":"$tag","prerelease":true,"assets":[{"name":"a.apk","size":1,"browser_download_url":"https://example.com/$tag.apk"}]}]"""
                enqueue(body, headers = nextHeader)
            }

            val checkerInstance = checker()
            val result = checkerInstance.fetchLatestRelease(includeReleaseCandidates = true)
            assertEquals("v2.50.0-rc.1", result?.tagName)
            // 1 stable + 12 pages = 13 requests
            assertEquals(13, server.requestCount)
        }

    @Test
    fun fetchLatestRelease_paginationCycleFailsOrTerminatesWithoutHanging() =
        runTest {
            enqueue(STABLE_BODY)
            val page1Url = server.url("/repos/Hy4ri/hermes-mobile/releases?per_page=100&page=1").toString()
            val page2Url = server.url("/repos/Hy4ri/hermes-mobile/releases?per_page=100&page=2").toString()

            // Page 1 points to Page 2
            enqueue(
                RELEASES_BODY_PAGE_1,
                headers = mapOf("Link" to "<$page2Url>; rel=\"next\""),
            )
            // Page 2 points back to Page 1 (cycle!)
            enqueue(
                RELEASES_BODY_PAGE_2,
                headers = mapOf("Link" to "<$page1Url>; rel=\"next\""),
            )

            val checkerInstance = checker()
            // Must not hang: the visited-URL guard surfaces the cycle as a failure.
            assertIoFailure("pagination cycle") { checkerInstance.fetchLatestRelease(includeReleaseCandidates = true) }
            assertEquals(3, server.requestCount)
        }

    @Test
    fun fetchLatestRelease_untrustedOriginNextLinkIgnoredOrRejected() =
        runTest {
            enqueue(STABLE_BODY)
            // Next link points to a completely different origin (evil.com)
            val untrustedUrl = "https://evil.com/repos/Hy4ri/hermes-mobile/releases?page=2"
            enqueue(
                RELEASES_BODY_PAGE_1,
                headers = mapOf("Link" to "<$untrustedUrl>; rel=\"next\""),
            )

            val checkerInstance = checker()
            // Rejected, never followed: no request reaches evil.com.
            assertIoFailure("untrusted pagination URL") {
                checkerInstance.fetchLatestRelease(includeReleaseCandidates = true)
            }
            assertEquals(2, server.requestCount)
        }

    @Test
    fun fetchLatestRelease_exactOriginSchemePortBoundariesEnforced() =
        runTest {
            enqueue(STABLE_BODY)
            // Same host but different port or scheme
            val otherPort = server.port + 1
            val differentPortUrl = "http://${server.hostName}:$otherPort/repos/Hy4ri/hermes-mobile/releases?page=2"
            enqueue(
                RELEASES_BODY_PAGE_1,
                headers = mapOf("Link" to "<$differentPortUrl>; rel=\"next\""),
            )

            val checkerInstance = checker()
            assertIoFailure("untrusted pagination URL") {
                checkerInstance.fetchLatestRelease(includeReleaseCandidates = true)
            }
            assertEquals(2, server.requestCount)
        }

    @Test
    fun fetchLatestRelease_numericRepoPathAllowedInNextLink() =
        runTest {
            enqueue(STABLE_BODY)
            val numericNextUrl = server.url("/repositories/987654321/releases?per_page=100&page=2").toString()
            enqueue(
                RELEASES_BODY_PAGE_1,
                headers = mapOf("Link" to "<$numericNextUrl>; rel=\"next\""),
            )
            enqueue(RELEASES_BODY_PAGE_2)

            val checkerInstance = checker()
            val result = checkerInstance.fetchLatestRelease(includeReleaseCandidates = true)
            assertEquals("v1.26.0-rc.1", result?.tagName)
            assertEquals(3, server.requestCount)
        }

    @Test
    fun fetchLatestRelease_channelsHaveIsolatedCaches() =
        runTest {
            enqueue(STABLE_BODY) // For stable channel check
            enqueue(STABLE_BODY) // For RC channel check
            enqueue(RELEASES_BODY_PAGE_1)

            val checkerInstance = checker()

            val stableResult = checkerInstance.fetchLatestRelease(includeReleaseCandidates = false)
            assertEquals("v1.24.2", stableResult?.tagName)

            val rcResult = checkerInstance.fetchLatestRelease(includeReleaseCandidates = true)
            assertEquals("v1.25.0-rc.3", rcResult?.tagName)
        }

    @Test
    fun fetchLatestRelease_304WithoutInitialCacheTreatedAsError() =
        runTest {
            // First call ever receives a 304 without any cached entry
            enqueue("", code = 304)
            enqueue("", code = 304)

            val checkerInstance = checker()
            assertIoFailure("304 without cached") { checkerInstance.fetchLatestRelease() }
        }

    @Test
    fun fetchLatestRelease_404ClearsOldCacheEntry() =
        runTest {
            enqueue(STABLE_BODY, headers = mapOf("ETag" to "\"etag-1\""))

            var currentTime = 1_000L
            val checkerInstance = checker(nowMs = { currentTime }, metadataTtlMs = 60_000L)

            val first = checkerInstance.fetchLatestRelease()
            assertEquals("v1.24.2", first?.tagName)

            // Expire cache
            currentTime += 65_000L
            // Revalidation returns 404 (clears cache)
            enqueue("", code = 404)
            enqueue("", code = 404) // Stable fallback list fetch if attempted

            val second = checkerInstance.fetchLatestRelease()
            assertNull(second)

            // Next call should NOT send ETag because cache was cleared by 404
            currentTime += 65_000L
            enqueue(STABLE_BODY)
            val third = checkerInstance.fetchLatestRelease()
            assertEquals("v1.24.2", third?.tagName)
            val req3 = server.takeRequest()
            assertNull(req3.getHeader("If-None-Match"))
        }

    @Test
    fun fetchLatestRelease_malformedBodyDoesNotCreateUsableCache() =
        runTest {
            enqueue("not json at all", code = 200)

            var currentTime = 1_000L
            val checkerInstance = checker(nowMs = { currentTime }, metadataTtlMs = 60_000L)

            // The stable fast path rejects the malformed body on its single request.
            assertIoFailure("malformed release metadata") { checkerInstance.fetchLatestRelease() }
            assertEquals(1, server.requestCount)

            // Even within TTL, malformed response must not be cached as valid
            enqueue(STABLE_BODY)
            val second = checkerInstance.fetchLatestRelease()
            assertEquals("v1.24.2", second?.tagName)
        }

    @Test
    fun fetchLatestRelease_networkErrorAfterTtlDoesNotReturnStaleCachedResult() =
        runTest {
            enqueue(STABLE_BODY, headers = mapOf("ETag" to "\"etag-1\""))

            var currentTime = 1_000L
            val checkerInstance = checker(nowMs = { currentTime }, metadataTtlMs = 60_000L)

            val first = checkerInstance.fetchLatestRelease()
            assertEquals("v1.24.2", first?.tagName)

            // Expire cache
            currentTime += 65_000L
            // Server responds 500 error on revalidation
            enqueue("", code = 500)
            enqueue("", code = 500)

            // Must surface the failure (as before #1567), never the stale cached result.
            assertIoFailure("(500)") { checkerInstance.fetchLatestRelease() }
        }

    private suspend fun assertIoFailure(
        messagePart: String,
        block: suspend () -> Any?,
    ) {
        try {
            val result = block()
            fail("Expected IOException containing '$messagePart' but got $result")
        } catch (e: java.io.IOException) {
            assertTrue("Unexpected message: ${e.message}", e.message.orEmpty().contains(messagePart))
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

        const val RELEASES_BODY_PAGE_1 = """
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

        const val RELEASES_BODY_PAGE_2 = """
[
  {
    "tag_name": "v1.26.0-rc.1",
    "prerelease": true,
    "draft": false,
    "assets": [
      {
        "name": "hermes-mobile-v1.26.0-rc.1.apk",
        "size": 1000,
        "browser_download_url": "https://example.com/rc26.apk"
      }
    ]
  }
]
"""
    }
}
