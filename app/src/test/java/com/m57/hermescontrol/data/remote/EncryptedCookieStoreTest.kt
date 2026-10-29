package com.m57.hermescontrol.data.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.m57.hermescontrol.profilelock.FakeSharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class EncryptedCookieStoreTest {
    private val encrypted = spyk(FakeSharedPreferences())
    private val legacy = FakeSharedPreferences()
    private val context = mockk<Context>()
    private lateinit var editor: SharedPreferences.Editor

    @Before
    fun setUp() {
        legacy.values[EncryptedCookieStore.LEGACY_SESSION_COOKIE_KEY] = "legacy-session"
        editor = spyk(encrypted.edit())
        every { encrypted.edit() } returns editor
        every { context.applicationContext } returns context
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any<Throwable>()) } returns 0
        mockkStatic(MasterKeys::class)
        every { MasterKeys.getOrCreate(any()) } returns "test-key"
        mockkStatic(EncryptedSharedPreferences::class)
        every {
            EncryptedSharedPreferences.create(
                any<String>(),
                any<String>(),
                any<Context>(),
                any<EncryptedSharedPreferences.PrefKeyEncryptionScheme>(),
                any<EncryptedSharedPreferences.PrefValueEncryptionScheme>(),
            )
        } returns encrypted
    }

    @After
    fun tearDown() = unmockkAll()

    private fun store() = EncryptedCookieStore(context, CompletableDeferred(legacy))

    @Test
    fun migrationCommitsEncryptedCopyBeforeRemovingLegacy() =
        runTest {
            var committed = false
            every { editor.commit() } answers {
                assertTrue(legacy.contains(EncryptedCookieStore.LEGACY_SESSION_COOKIE_KEY))
                val result = callOriginal()
                committed = result
                result
            }

            val cookies = store().load("server-a")

            assertTrue(committed)
            assertEquals("legacy-session", cookies.single().value)
            assertFalse(legacy.contains(EncryptedCookieStore.LEGACY_SESSION_COOKIE_KEY))
            assertTrue(encrypted.getString("cookies::server-a", null)!!.contains("legacy-session"))
            verify(exactly = 1) { editor.commit() }
            verify(exactly = 0) { editor.apply() }
        }

    @Test
    fun failedEncryptedCommitKeepsLegacySource() =
        runTest {
            every { editor.commit() } returns false

            val cookies = store().load("server-a")

            assertEquals("legacy-session", cookies.single().value)
            verify(exactly = 1) { editor.commit() }
            assertEquals("legacy-session", legacy.getString(EncryptedCookieStore.LEGACY_SESSION_COOKIE_KEY, null))

            every { editor.commit() } answers { callOriginal() }
            assertEquals("legacy-session", store().load("server-a").single().value)
            assertFalse(legacy.contains(EncryptedCookieStore.LEGACY_SESSION_COOKIE_KEY))
            verify(exactly = 2) { editor.commit() }
        }

    @Test
    fun throwingMigrationCommitStillReturnsLegacyCookie() =
        runTest {
            every { editor.commit() } throws IOException("disk unavailable")

            assertEquals("legacy-session", store().load("server-a").single().value)
            assertTrue(legacy.contains(EncryptedCookieStore.LEGACY_SESSION_COOKIE_KEY))
        }

    @Test
    fun failedEncryptedReadStillReturnsLegacyCookie() =
        runTest {
            every { encrypted.getString("cookies::server-a", null) } throws
                IllegalStateException("keystore unavailable")

            assertEquals("legacy-session", store().load("server-a").single().value)
            assertTrue(legacy.contains(EncryptedCookieStore.LEGACY_SESSION_COOKIE_KEY))
        }

    @Test
    fun migrationIsIdempotentAcrossStoreInstances() =
        runTest {
            val first = store().load("server-a")
            val restarted = store().load("server-a")

            assertEquals(first, restarted)
            assertEquals("legacy-session", restarted.single().value)
            assertTrue(store().load("server-b").isEmpty())
            verify(exactly = 1) { editor.commit() }
        }
}
