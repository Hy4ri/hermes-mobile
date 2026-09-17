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
    fun isNewerVersion_newerCoreOutranksDevBuild() {
        // Local dev default "1.0-dev" must still see a real release as newer.
        assertTrue(isNewerVersion("1.21.0", "1.0-dev"))
        assertTrue(isNewerVersion("v1.21.0", "1.0-dev"))
    }

    @Test
    fun isNewerVersion_preReleaseSuffixesSortBelowStableBase() {
        // A versionName built from a release-candidate tag ("1.25.0-rc.1",
        // or the dot-form "1.25.rc.1") must see the stable release of the
        // same base as a NEWER update. Previously the "rc" segment parsed
        // as 0 with a stray trailing segment, so an installed rc compared
        // GREATER than the stable tag and the update prompt never fired.
        assertTrue(isNewerVersion("v1.25", "1.25.rc.1"))
        assertTrue(isNewerVersion("v1.25", "1.25.0-rc.1"))
        assertTrue(isNewerVersion("v1.25", "1.25-rc.9"))
        assertTrue(isNewerVersion("v1.26.0", "1.25.0-rc.2"))
        assertTrue(isNewerVersion("v1.28.1", "1.28.0-rc.3"))
        assertTrue(isNewerVersion("1.25.0-rc.3", "1.25.0-rc.2"))
        // Equal versions — including rc == rc — must stay silent.
        assertFalse(isNewerVersion("v1.25", "1.25"))
        assertFalse(isNewerVersion("v1.25.0-rc.2", "1.25.0-rc.2"))
        // A stable install must not be prompted by a same-base rc release.
        assertFalse(isNewerVersion("v1.25.0-rc.2", "1.25.0"))
        // Numeric growth still wins over any pre-release marker.
        assertTrue(isNewerVersion("v1.26", "1.25.0-rc.5"))
        // Local dev builds still see any real release as newer.
        assertTrue(isNewerVersion("v1.21.0", "1.0-dev"))
    }

    @Test
    fun isNewerVersion_unparseableNeverClaimsUpdate() {
        assertFalse(isNewerVersion("not-a-version", "1.21.0"))
        assertFalse(isNewerVersion("1.21.0", ""))
        assertFalse(isNewerVersion("", "1.21.0"))
    }

    @Test
    fun isNewerVersion_comparesCoreBeforePrerelease() {
        assertTrue(isNewerVersion("1.25.1-rc.1", "1.25"))
        assertFalse(isNewerVersion("1.25", "1.25.1-rc.1"))
        assertTrue(isNewerVersion("1.25.0.1", "1.25-rc.9"))
        assertFalse(isNewerVersion("1.24.9", "1.25.rc.1"))
        assertFalse(isNewerVersion("1.25", "1.25.0"))
        assertFalse(isNewerVersion("1.25.0-rc.1", "1.25.rc.1"))
        assertFalse(isNewerVersion("1.25.rc.1", "1.25.0-rc.1"))
    }

    @Test
    fun isNewerVersion_ordersPrereleaseIdentifiers() {
        val versions = listOf("1.25-alpha", "1.25-alpha.1", "1.25-beta", "1.25-rc.2", "1.25-rc.10", "1.25")
        versions.zipWithNext().forEach { (older, newer) ->
            assertTrue("$newer > $older", isNewerVersion(newer, older))
            assertFalse("$older < $newer", isNewerVersion(older, newer))
        }
        assertTrue(isNewerVersion("1.25-rc.99999999999999999999", "1.25-rc.10"))
        assertTrue(isNewerVersion("1.25-rc.a", "1.25-rc.10"))
        assertTrue(isNewerVersion("1.25", "1.25-dev"))
    }

    @Test
    fun isNewerVersion_ignoresBuildMetadata() {
        assertFalse(isNewerVersion("1.25+build.2", "1.25+build.1"))
        assertFalse(isNewerVersion("1.25", "1.25+build.1"))
        assertTrue(isNewerVersion(" v1.25+build.2 ", "1.25-rc.1+build.3"))
    }

    @Test
    fun isNewerVersion_rejectsMalformedVersionsOnEitherSide() {
        listOf("garbage", "1..25", "1.25-", "1.25+", "1.25_rc.1", "1.25!", "99999999999999999999").forEach {
            assertFalse(it, isNewerVersion(it, "1.25"))
            assertFalse(it, isNewerVersion("1.25", it))
        }
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
