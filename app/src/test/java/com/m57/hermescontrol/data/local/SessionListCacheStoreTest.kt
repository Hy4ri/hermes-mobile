package com.m57.hermescontrol.data.local

import android.content.Context
import android.content.SharedPreferences
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionListResponse
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SessionListCacheStoreTest {
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var mockContext: Context
    private val prefStorage = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        prefStorage.clear()
        mockPrefs = mockk(relaxed = true)
        mockEditor = mockk(relaxed = true)
        mockContext = mockk(relaxed = true)

        every { mockContext.applicationContext } returns mockContext
        every { mockContext.getSharedPreferences(any(), any()) } returns mockPrefs
        every { mockPrefs.edit() } returns mockEditor

        every { mockEditor.putString(any(), any()) } answers {
            prefStorage[firstArg()] = secondArg()
            mockEditor
        }
        every { mockPrefs.getString(any(), any()) } answers {
            prefStorage[firstArg()] ?: secondArg()
        }
        every { mockEditor.remove(any()) } answers {
            prefStorage.remove(firstArg())
            mockEditor
        }
        every { mockEditor.clear() } answers {
            prefStorage.clear()
            mockEditor
        }
        every { mockEditor.apply() } returns Unit

        SessionListCacheStore.init(mockContext)
    }

    @Test
    fun testPutAndGet() {
        val sample =
            SessionListResponse(
                sessions =
                    listOf(
                        SessionInfo(
                            id = "s-123",
                            title = "Test Session",
                        ),
                    ),
                total = 1,
            )

        SessionListCacheStore.put("active", sample)
        val loaded = SessionListCacheStore.get("active")

        assertNotNull(loaded)
        assertEquals(1, loaded?.total)
        assertEquals("s-123", loaded?.sessions?.firstOrNull()?.id)
        assertEquals("Test Session", loaded?.sessions?.firstOrNull()?.title)
    }

    @Test
    fun testCacheMiss() {
        assertNull(SessionListCacheStore.get("nonexistent"))
    }

    @Test
    fun testRemove() {
        val sample = SessionListResponse(sessions = emptyList(), total = 0)
        SessionListCacheStore.put("k1", sample)
        assertNotNull(SessionListCacheStore.get("k1"))

        SessionListCacheStore.remove("k1")
        assertNull(SessionListCacheStore.get("k1"))
    }

    @Test
    fun testClear() {
        val sample = SessionListResponse(sessions = emptyList(), total = 0)
        SessionListCacheStore.put("k1", sample)
        SessionListCacheStore.put("k2", sample)

        SessionListCacheStore.clear()
        assertNull(SessionListCacheStore.get("k1"))
        assertNull(SessionListCacheStore.get("k2"))
    }
}
