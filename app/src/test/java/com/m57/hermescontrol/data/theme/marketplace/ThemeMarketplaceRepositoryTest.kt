package com.m57.hermescontrol.data.theme.marketplace

import com.m57.hermescontrol.data.remote.NetworkResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MockWebServer tests for [ThemeMarketplaceRepository] (t_5316ccb7).
 *
 * Exercises the gallery request shape, catalog parsing, icon-pack
 * filtering, VSIX resolve, error mapping, and the in-memory cache —
 * all without touching the live Marketplace.
 */
class ThemeMarketplaceRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: ThemeMarketplaceRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository =
            ThemeMarketplaceRepository(
                galleryBaseUrl = server.url("/").toString().trimEnd('/'),
                cacheTtlMs = 60_000L,
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun search_parsesEntriesAndFiltersIconPacks() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(SEARCH_RESPONSE),
            )

            val result = repository.search("dracula", 20)
            assertTrue(result is NetworkResult.Success)
            val entries = (result as NetworkResult.Success).data

            // 3 extensions in the fixture; the file-icon pack is filtered out.
            assertEquals(2, entries.size)
            assertEquals("dracula-theme.theme-dracula", entries[0].extensionId)
            assertEquals("Dracula Official", entries[0].displayName)
            assertEquals("Dracula Theme", entries[0].publisher)
            assertTrue(entries[0].installs > 0)
            assertEquals("github-theme.github-vscode-theme", entries[1].extensionId)

            // Request shape: POST + gallery headers + Themes-category criteria.
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertTrue(recorded.getHeader("Accept")!!.contains("api-version=3.0-preview.1"))
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("\"filterType\":8"))
            assertTrue(body.contains("Microsoft.VisualStudio.Code"))
            assertTrue(body.contains("\"filterType\":5"))
            assertTrue(body.contains("Themes"))
            assertTrue(body.contains("\"filterType\":10"))
            assertTrue(body.contains("dracula"))
        }

    @Test
    fun search_emptyQueryOmitsSearchTextCriterion() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody(EMPTY_RESPONSE))

            val result = repository.search("", 10)
            assertTrue(result is NetworkResult.Success)

            val body = server.takeRequest().body.readUtf8()
            assertTrue(!body.contains("\"filterType\":10"))
        }

    @Test
    fun search_cachesIdenticalQueries() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody(EMPTY_RESPONSE))

            assertTrue(repository.search("nord", 10) is NetworkResult.Success)
            assertTrue(repository.search("nord", 10) is NetworkResult.Success)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun search_serverErrorMapsToFailure() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

            val result = repository.search("nord", 10)
            assertTrue(result is NetworkResult.Failure)
        }

    @Test
    fun fetchDownloadUrl_resolvesVsixAsset() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody(RESOLVE_RESPONSE))

            val result = repository.fetchDownloadUrl("dracula-theme.theme-dracula")
            assertTrue(result is NetworkResult.Success)
            assertEquals(
                "https://example.cdn/vsix/dracula.vsix",
                (result as NetworkResult.Success).data,
            )

            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"filterType\":7"))
            assertTrue(body.contains("dracula-theme.theme-dracula"))
        }

    @Test
    fun resolveAssets_returnsDownloadAndPreview() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody(RESOLVE_RESPONSE))

            val result = repository.resolveAssets("dracula-theme.theme-dracula")
            assertTrue(result is NetworkResult.Success)
            val assets = (result as NetworkResult.Success).data
            assertEquals("https://example.cdn/vsix/dracula.vsix", assets.downloadUrl)
            assertEquals("https://example.cdn/icon.png", assets.previewUrl)
        }

    @Test
    fun resolveAssets_isCached() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody(RESOLVE_RESPONSE))

            assertTrue(repository.resolveAssets("dracula-theme.theme-dracula") is NetworkResult.Success)
            assertTrue(repository.resolveAssets("dracula-theme.theme-dracula") is NetworkResult.Success)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun resolveAssets_missingVsixMapsToFailure() =
        runBlocking {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody("""{"results":[]}"""),
            )

            val result = repository.resolveAssets("publisher.theme")
            assertTrue(result is NetworkResult.Failure)
        }

    @Test
    fun fetchDownloadUrl_rejectsBadIdWithoutNetwork() =
        runBlocking {
            val result = repository.fetchDownloadUrl("not-an-id")
            assertTrue(result is NetworkResult.Failure)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun search_paginatesByPageNumber() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody(EMPTY_RESPONSE))

            repository.search("nord", 10, page = 2)
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"pageNumber\":2"))
        }

    @Test
    fun search_cachesDistinctPagesSeparately() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody(PAGE_ONE))
            server.enqueue(MockResponse().setResponseCode(200).setBody(PAGE_TWO))

            repository.search("nord", 10, page = 1)
            repository.search("nord", 10, page = 2)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun looksLikeIconTheme_matchesTagsAndText() {
        val iconByTag =
            GalleryExtension(
                extensionName = "x",
                displayName = "Pretty Colors",
                tags = listOf("Icon-Theme"),
            )
        val iconByText =
            GalleryExtension(
                extensionName = "y",
                displayName = "My File Icons Pack",
            )
        val colorTheme =
            GalleryExtension(
                extensionName = "z",
                displayName = "Dracula Official",
                shortDescription = "Dark theme for many editors",
            )
        assertTrue(ThemeMarketplaceRepository.looksLikeIconTheme(iconByTag))
        assertTrue(ThemeMarketplaceRepository.looksLikeIconTheme(iconByText))
        assertTrue(!ThemeMarketplaceRepository.looksLikeIconTheme(colorTheme))
    }

    companion object {
        private const val SEARCH_RESPONSE: String = """
            {
              "results": [
                {
                  "extensions": [
                    {
                      "extensionName": "theme-dracula",
                      "displayName": "Dracula Official",
                      "shortDescription": "Official Dracula Theme",
                      "publisher": {"publisherName": "dracula-theme", "displayName": "Dracula Theme"},
                      "statistics": [{"statisticName": "install", "value": 8123456.0}],
                      "tags": ["color-theme", "dark"]
                    },
                    {
                      "extensionName": "file-icons",
                      "displayName": "Super File Icons Pack",
                      "shortDescription": "File icons for VS Code",
                      "publisher": {"publisherName": "icon-maker", "displayName": "Icon Maker"},
                      "statistics": [{"statisticName": "install", "value": 100.0}],
                      "tags": ["icon-theme"]
                    },
                    {
                      "extensionName": "github-vscode-theme",
                      "displayName": "GitHub Theme",
                      "shortDescription": "GitHub color theme",
                      "publisher": {"publisherName": "github-theme", "displayName": "GitHub"},
                      "statistics": [{"statisticName": "install", "value": 5000.0}],
                      "tags": ["color-theme"]
                    }
                  ]
                }
              ]
            }
            """

        private const val EMPTY_RESPONSE: String = """{"results": [{"extensions": []}]}"""

        private const val PAGE_ONE: String = """
            {"results": [{"extensions": [{"extensionName": "a", "displayName": "A"}]}]}
            """

        private const val PAGE_TWO: String = """
            {"results": [{"extensions": [{"extensionName": "b", "displayName": "B"}]}]}
            """

        private const val RESOLVE_RESPONSE: String = """
            {
              "results": [
                {
                  "extensions": [
                    {
                      "extensionName": "theme-dracula",
                      "displayName": "Dracula Official",
                      "publisher": {"publisherName": "dracula-theme", "displayName": "Dracula Theme"},
                      "versions": [
                        {
                          "files": [
                            {
                              "assetType": "Microsoft.VisualStudio.Services.Icons.Default",
                              "source": "https://example.cdn/icon.png"
                            },
                            {
                              "assetType": "Microsoft.VisualStudio.Services.VSIXPackage",
                              "source": "https://example.cdn/vsix/dracula.vsix"
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """
    }
}
