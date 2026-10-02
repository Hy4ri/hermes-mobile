package com.m57.hermescontrol.data.theme.import

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Spec M2 / acceptance A5: the import path is data-only.
 *
 * A `.vsix` is a zip of files, and a theme extension can ship arbitrary
 * JavaScript alongside its colors. This builds a fixture package that contains
 * exactly that decoy and asserts the parser reads the colors and never treats
 * the executable content as anything but bytes to ignore. Served from
 * MockWebServer, so the whole proof is offline and deterministic.
 */
class VsixThemeParserDataOnlyTest {
    private lateinit var server: MockWebServer

    private val decoyJs =
        """
        // If the importer ever evaluated extension code, this marker would run
        // and the test process would die. It must only ever be read as bytes.
        throw new Error("DECOY_EXECUTED");
        """.trimIndent()

    private val packageJson =
        """
        {
          // JSONC comments and trailing commas are common in real packages.
          "name": "fixture-theme",
          "contributes": {
            "themes": [
              { "label": "Fixture Dark", "uiTheme": "vs-dark", "path": "./themes/dark.json", },
            ],
          },
        }
        """.trimIndent()

    private val themeJson =
        """
        {
          "name": "Fixture Dark",
          "type": "dark",
          "colors": {
            "editor.background": "#101010",
            "editor.foreground": "#f0f0f0",
            "activityBar.background": "#181818",
          },
        }
        """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `colors are imported and the decoy script is ignored`() =
        runTest {
            server.enqueue(MockResponse().setBody(Buffer().write(vsixFixture())))
            val url = server.url("/fixture.vsix").toString()

            val variants = VsixThemeParser(client = OkHttpClient()).parseVsix(url).getOrThrow()

            assertEquals(1, variants.size)
            val variant = variants.single()
            assertEquals("Fixture Dark", variant.label)
            // `type` is the package.json `uiTheme` verbatim (parseThemeFile only
            // falls back to the theme file's own "type" when that is blank).
            assertEquals("vs-dark", variant.type)
            assertEquals("#101010", variant.colors["editor.background"])
            assertEquals("#f0f0f0", variant.colors["editor.foreground"])

            // Nothing executable leaked into the parsed model, and the decoy content
            // is nowhere in the result.
            val serialized = variants.toString()
            assertFalse(serialized.contains("DECOY_EXECUTED"))
            assertFalse(serialized.contains("extension/dist/activate.js"))
            assertTrue(variant.colors.keys.none { it.endsWith(".js") })
        }

    @Test
    fun `package without contributed themes falls back to scanning json only`() =
        runTest {
            server.enqueue(
                MockResponse().setBody(Buffer().write(vsixFixture(packageJson = """{"name":"no-themes"}"""))),
            )
            val url = server.url("/fixture.vsix").toString()

            val variants = VsixThemeParser(client = OkHttpClient()).parseVsix(url).getOrThrow()

            // The fallback scans JSON files for colors; the decoy .js is not JSON and
            // must not be picked up.
            assertTrue(variants.isNotEmpty())
            assertTrue(variants.all { it.colors.isNotEmpty() })
            assertFalse(variants.any { it.name.endsWith(".js") })
        }

    @Test
    fun `package with no colors imports nothing usable`() =
        runTest {
            val emptyPackage =
                """
                {"name":"empty","contributes":{"themes":[
                  {"label":"X","uiTheme":"vs-dark","path":"./themes/dark.json"}
                ]}}
                """.trimIndent()
            server.enqueue(
                MockResponse()
                    .setBody(
                        Buffer()
                            .write(
                                vsixFixture(
                                    packageJson = emptyPackage,
                                    themeJson = """{"name":"X","type":"dark","colors":{}}""",
                                ),
                            ),
                    ),
            )
            val url = server.url("/fixture.vsix").toString()

            val result = VsixThemeParser(client = OkHttpClient()).parseVsix(url)

            // The parser resolves the contributed file but finds no colors, so
            // nothing usable comes back. The apply pipeline rejects this on the
            // `variants.all { it.colors.isEmpty() }` guard and reports
            // "Theme package has no colors to import" rather than silently
            // applying an empty theme — and never carries the decoy script.
            assertTrue(result.isSuccess)
            val variants = result.getOrThrow()
            assertTrue(variants.all { it.colors.isEmpty() })
            assertFalse(variants.any { it.colors.keys.any { key -> key.endsWith(".js") } })
        }

    @Test
    fun `package with nested theme path like dot-slash theme is extracted correctly`() =
        runTest {
            val nestedPackage =
                """
                {
                  "name": "dracula-like",
                  "contributes": {
                    "themes": [
                      { "label": "Dracula", "uiTheme": "vs-dark", "path": "./theme/dracula.json" }
                    ]
                  }
                }
                """.trimIndent()
            val themeJson =
                """
                {
                  "name": "Dracula",
                  "type": "dark",
                  "colors": {
                    "editor.background": "#282A36",
                    "editor.foreground": "#F8F8F2"
                  }
                }
                """.trimIndent()
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                zip.write("extension/package.json", nestedPackage)
                zip.write("extension/theme/dracula.json", themeJson)
            }
            server.enqueue(MockResponse().setBody(Buffer().write(out.toByteArray())))
            val url = server.url("/dracula.vsix").toString()

            val variants = VsixThemeParser(client = OkHttpClient()).parseVsix(url).getOrThrow()
            assertEquals(1, variants.size)
            assertEquals("Dracula", variants.first().label)
            assertEquals("#282A36", variants.first().colors["editor.background"])
        }

    private fun vsixFixture(
        packageJson: String = this.packageJson,
        themeJson: String = this.themeJson,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.write("extension/package.json", packageJson)
            zip.write("extension/themes/dark.json", themeJson)
            // Decoys: a real activation script and a JSON file that is not a theme.
            zip.write("extension/dist/activate.js", decoyJs)
            zip.write("extension/dist/loader.js", decoyJs)
            zip.write("extension/package.nls.json", """{"decoy":"DECOY_EXECUTED"}""")
        }
        return out.toByteArray()
    }

    private fun ZipOutputStream.write(
        name: String,
        content: String,
    ) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray())
        closeEntry()
    }
}
