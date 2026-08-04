package com.m57.hermescontrol.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import kotlin.system.measureTimeMillis

class SlowFakeEncryptedCookieStore : CookieStore {
    val store = FakeEncryptedCookieStore()

    override suspend fun save(
        serverId: String,
        cookies: List<Cookie>,
    ) {
        store.save(serverId, cookies)
    }

    override suspend fun load(serverId: String): List<Cookie> {
        delay(10) // simulated I/O delay
        return store.load(serverId)
    }

    override suspend fun clear(serverId: String) {
        store.clear(serverId)
    }

    override suspend fun clearAll() {
        store.clearAll()
    }
}

class PersistentCookieJarBenchmark {
    @Test
    fun benchmarkRunBlocking() {
        val time =
            measureTimeMillis {
                runBlocking {
                    val store = SlowFakeEncryptedCookieStore()
                    val jar =
                        PersistentCookieJar(
                            store = store,
                            storeScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
                        )

                    // Simulate 100 concurrent requests hitting loadForRequest before it's loaded
                    val jobs =
                        List(100) {
                            async(Dispatchers.IO) {
                                jar.loadForRequest("http://dashboard.local/".toHttpUrl())
                            }
                        }
                    jobs.awaitAll()
                }
            }
        println("BENCHMARK_RESULT: $time ms")
    }
}
