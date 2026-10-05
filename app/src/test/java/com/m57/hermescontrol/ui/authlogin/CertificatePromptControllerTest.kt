package com.m57.hermescontrol.ui.authlogin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class CertificatePromptControllerTest {
    private val controller = CertificatePromptController()
    private val url = "https://user:password@example.test:8443/api/status?token=private".toHttpUrl()

    private fun openAndSelect() {
        controller.offer(controller.reset(), url)
        controller.selected(requireNotNull(controller.beginSelection()), "candidate", true)
    }

    @Test
    fun `fixed origin excludes credentials and path and stale probes cannot reopen dialog`() {
        val attempt = controller.reset()
        controller.offer(attempt, url)
        assertEquals(
            "https://example.test:8443/",
            controller.state.value.origin
                .toString(),
        )
        controller.reset()
        controller.offer(attempt, url)
        assertNull(controller.state.value.origin)
        controller.offer(controller.reset(), "http://example.test/".toHttpUrl())
        assertNull(controller.state.value.origin)
    }

    @Test
    fun `cancelled unavailable and stale picker results cannot choose an identity`() {
        controller.offer(controller.reset(), url)
        controller.selected(requireNotNull(controller.beginSelection()), null, true)
        assertNull(controller.state.value.alias)
        controller.selected(requireNotNull(controller.beginSelection()), "unavailable", false)
        assertNull(controller.state.value.alias)
        assertTrue(controller.state.value.selectionFailed)
        val stale = requireNotNull(controller.beginSelection())
        assertNull(controller.beginSelection())
        controller.reset()
        controller.selected(stale, "late", true)
        assertNull(controller.state.value.alias)
    }

    @Test
    fun `save requires explicit selection and failed validation preserves binding`() =
        runBlocking {
            val writes = AtomicInteger()
            controller.offer(controller.reset(), url)
            controller.save(this, { _, _ -> error("must not run") }, { _, _ -> writes.incrementAndGet() })
            assertFalse(controller.state.value.saving)
            controller.selected(requireNotNull(controller.beginSelection()), "candidate", true)
            controller.save(this, { _, _ -> throw IOException("TLS rejected") }, { _, _ -> writes.incrementAndGet() })
            withTimeout(5000) { controller.state.first { it.error != null } }
            assertEquals("TLS rejected", controller.state.value.error)
            assertEquals("candidate", controller.state.value.alias)
            assertEquals(0, writes.get())
        }

    @Test
    fun `duplicate save validates and persists once and displays inline success`() =
        runBlocking {
            openAndSelect()
            val validations = AtomicInteger()
            val writes = AtomicInteger()
            val release = CompletableDeferred<Unit>()
            val validate: suspend (okhttp3.HttpUrl, String) -> Unit = { _, _ ->
                validations.incrementAndGet()
                release.await()
            }
            val persist: (okhttp3.HttpUrl, String) -> Unit = { _, alias ->
                assertEquals("candidate", alias)
                writes.incrementAndGet()
            }
            controller.save(this, validate, persist)
            controller.save(this, validate, persist)
            release.complete(Unit)
            withTimeout(5000) { controller.state.first { it.savedOrigin != null } }
            assertEquals(1, validations.get())
            assertEquals(1, writes.get())
            assertNull(controller.state.value.origin)
            controller.reset()
            assertNull(controller.state.value.savedOrigin)
        }

    @Test
    fun `dismissal cancels pending validation and never saves or reopens`() =
        runBlocking {
            openAndSelect()
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val writes = AtomicInteger()
            controller.save(this, { _, _ ->
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }, { _, _ -> writes.incrementAndGet() })
            withTimeout(5000) { started.await() }
            controller.reset()
            withTimeout(5000) { stopped.await() }
            assertEquals(0, writes.get())
            assertEquals(CertificatePromptState(), controller.state.value)
        }

    @Test
    fun `persistence conflict leaves dialog open without success`() =
        runBlocking {
            openAndSelect()
            controller.save(this, { _, _ -> }, { _, _ -> error("Bindings changed") })
            withTimeout(5000) { controller.state.first { it.error != null } }
            assertNull(controller.state.value.savedOrigin)
            assertEquals("Bindings changed", controller.state.value.error)
        }
}
