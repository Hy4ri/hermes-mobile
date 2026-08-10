package com.m57.hermescontrol.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic of the in-app updater (issue #867): version comparison and
 * GitHub release JSON parsing. No Android/network dependencies.
 */
class AppUpdateCheckerTest {
    // ── Version comparison ──────────────────────────────────────────────

    @Test
    fun isNewerVersion_stripsLeadingV() {
        assertTrue(
            "release tags carry a leading v; versionName does not",
            isNewerVersion("v1.22.0", "1.21.0"),
        )
        assertFalse(
            "same version must not count as newer",
            isNewerVersion("v1.21.0", "1.21.0"),
        )
    }

    @Test
    fun isNewerVersion_comparesNumericSegments() {
        assertTrue(isNewerVersion("1.21.0", "1.2"))
        assertTrue(isNewerVersion("2.0.0", "1.99.99"))
        assertFalse(isNewerVersion("1.2", "1.21.0"))
        assertFalse(isNewerVersion("1.21.0", "1.21.0"))
    }

    @Test
    fun isNewerVersion_nonNumericSuffixCountsAsZero() {
        // Local dev default "1.0-dev" must still see a real release as newer.
        assertTrue(isNewerVersion("1.21.0", "1.0-dev"))
        assertTrue(isNewerVersion("v1.21.0", "1.0-dev"))
    }

    @Test
    fun isNewerVersion_unparseableNeverClaimsUpdate() {
        assertFalse(isNewerVersion("not-a-version", "1.21.0"))
        assertFalse(isNewerVersion("1.21.0", ""))
        assertFalse(isNewerVersion("", "1.21.0"))
    }

    @Test
    fun normalizedVersion_stripsLeadingV() {
        assertEquals("1.21.0", normalizedVersion("v1.21.0"))
        assertEquals("1.21.0", normalizedVersion("1.21.0"))
    }

    // ── Release JSON parsing ────────────────────────────────────────────

    @Test
    fun parseUpdateInfo_picksApkAsset() {
        val json =
            """
            {
              "tag_name": "v1.22.0",
              "assets": [
                {
                  "name": "version.txt",
                  "size": 12,
                  "browser_download_url": "https://github.com/Hy4ri/hermes-mobile/releases/download/v1.22.0/version.txt"
                },
                {
                  "name": "hermes-mobile-v1.22.0.apk",
                  "size": 12345678,
                  "browser_download_url": "https://github.com/Hy4ri/hermes-mobile/releases/download/v1.22.0/hermes-mobile-v1.22.0.apk"
                }
              ]
            }
            """.trimIndent()

        val info = parseUpdateInfo(json)
        assertNotNull(info)
        assertEquals("v1.22.0", info!!.tagName)
        val apk = info.apkAsset
        assertNotNull("the .apk asset must be selected", apk)
        assertEquals("hermes-mobile-v1.22.0.apk", apk!!.name)
        assertEquals(12345678L, apk.size)
        assertTrue(apk.browserDownloadUrl.endsWith(".apk"))
    }

    @Test
    fun parseUpdateInfo_noApkAsset_yieldsNullApk() {
        val json =
            """
            {
              "tag_name": "v1.22.0",
              "assets": [
                {
                  "name": "version.txt",
                  "size": 12,
                  "browser_download_url": "https://github.com/Hy4ri/hermes-mobile/releases/download/v1.22.0/version.txt"
                }
              ]
            }
            """.trimIndent()

        val info = parseUpdateInfo(json)
        assertNotNull(info)
        assertNull(info!!.apkAsset)
    }

    @Test
    fun parseUpdateInfo_malformedJson_yieldsNull() {
        assertNull(parseUpdateInfo("not json at all"))
        assertNull(parseUpdateInfo(""))
    }
}
