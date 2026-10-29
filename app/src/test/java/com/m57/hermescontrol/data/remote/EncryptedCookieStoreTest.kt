package com.m57.hermescontrol.data.remote

import android.content.Context
import android.content.SharedPreferences
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

            runCatching { store().load("server-a") }

            verify(exactly = 1) { editor.commit() }
            assertEquals("legacy-session", legacy.getString(EncryptedCookieStore.LEGACY_SESSION_COOKIE_KEY, null))
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
