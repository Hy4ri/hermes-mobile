package com.m57.hermescontrol.data.local

import android.content.Context
import android.content.SharedPreferences
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionListResponse
import io.mockk.every
import io.mockk.mockk
import org.junit.After
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

        SessionListCacheStore.resetForTesting(mockPrefs)
    }

    @After
    fun tearDown() {
        SessionListCacheStore.resetForTesting(null)
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
    fun testCorruptedJsonReturnsNull() {
        prefStorage["sessions_page_corrupt"] = "{ not valid json }"
        assertNull(SessionListCacheStore.get("corrupt"))
    }

    @Test
    fun testScopePartitioningCoexistence() {
        val scopeA = DataScope("conn-a", "http://server-a", "profile-a")
        val scopeB = DataScope("conn-b", "http://server-b", "profile-b")

        val keyA = scopeA.persistentKey("recent")
        val keyB = scopeB.persistentKey("recent")

        val responseA = SessionListResponse(sessions = listOf(SessionInfo(id = "a-1", title = "Session A")), total = 1)
        val responseB = SessionListResponse(sessions = listOf(SessionInfo(id = "b-1", title = "Session B")), total = 1)

        SessionListCacheStore.put(keyA, responseA)
        SessionListCacheStore.put(keyB, responseB)

        val loadedA = SessionListCacheStore.get(keyA)
        val loadedB = SessionListCacheStore.get(keyB)

        assertNotNull(loadedA)
        assertNotNull(loadedB)
        assertEquals("Session A", loadedA?.sessions?.first()?.title)
        assertEquals("Session B", loadedB?.sessions?.first()?.title)

        // Removing keyA does not remove keyB
        SessionListCacheStore.remove(keyA)
        assertNull(SessionListCacheStore.get(keyA))
        assertNotNull(SessionListCacheStore.get(keyB))
    }

    @Test
    fun testClearRemovesAll() {
        val sample = SessionListResponse(sessions = emptyList(), total = 0)
        SessionListCacheStore.put("k1", sample)
        SessionListCacheStore.put("k2", sample)

        SessionListCacheStore.clear()
        assertNull(SessionListCacheStore.get("k1"))
        assertNull(SessionListCacheStore.get("k2"))
    }
}
