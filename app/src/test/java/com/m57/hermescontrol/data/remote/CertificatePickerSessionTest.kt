package com.m57.hermescontrol.data.remote

import com.m57.hermescontrol.ui.authlogin.CertificatePromptController
import com.m57.hermescontrol.ui.settings.CertificateBindingDraft
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificatePickerSessionTest {
    private val controller = CertificatePromptController()
    private val url = "https://example.test:8443/".toHttpUrl()
    private var now = 0L
    private val pending = mutableListOf<Pair<Long, () -> Unit>>()
    private var finishes = 0

    private fun picker(): CertificatePickerSession {
        val attempt = requireNotNull(controller.beginSelection())
        return CertificatePickerSession(
            schedule = { delay, action ->
                val task = now + delay to action
                pending += task
                val cancel: () -> Unit = { pending.remove(task) }
                cancel
            },
            result = { alias, valid ->
                finishes++
                controller.selected(attempt, alias, valid)
            },
        ).also { it.launched() }
    }

    private fun open() {
        controller.offer(controller.reset(), url)
    }

    private fun advance(millis: Long) {
        val end = now + millis
        while (true) {
            val task = pending.filter { it.first <= end }.minByOrNull { it.first } ?: break
            pending.remove(task)
            now = task.first
            task.second()
        }
        now = end
    }

    @Test
    fun `launch with no callback or lifecycle transition permits manual retry`() {
        open()
        picker()
        assertTrue(controller.state.value.choosing)
        advance(5_000)
        assertFalse(controller.state.value.choosing)
        assertTrue(controller.state.value.selectionFailed)
        assertNotNull(controller.beginSelection())
        assertEquals(1, finishes)
    }

    @Test
    fun `return without callback preserves previous candidate and enables save`() =
        runBlocking {
            open()
            controller.selected(requireNotNull(controller.beginSelection()), "previous", true)
            val session = picker()
            session.paused()
            advance(60_000) // No timeout while the user is still choosing in the external activity.
            assertTrue(controller.state.value.choosing)
            session.resumed()
            advance(1_000)
            assertFalse(controller.state.value.choosing)
            assertFalse(controller.state.value.selectionFailed)
            assertEquals("previous", controller.state.value.alias)
            var saved = false
            controller.save(this, { _, _ -> }, { _, alias ->
                assertEquals("previous", alias)
                saved = true
            })
            // The existing save test covers completion; this verifies recovery doesn't block the action.
            assertTrue(controller.state.value.saving)
            controller.reset()
            assertFalse(saved)
        }

    @Test
    fun `late callback after no callback recovery cannot replace next choice`() {
        open()
        val abandoned = picker()
        abandoned.paused()
        abandoned.resumed()
        advance(1_000)
        val next = picker()
        assertFalse(abandoned.callbackReceived())
        abandoned.finish("late", true)
        assertTrue(controller.state.value.choosing)
        assertNull(controller.state.value.alias)
        assertTrue(next.callbackReceived())
        next.finish("new", true)
        assertEquals("new", controller.state.value.alias)
        assertEquals(2, finishes)
    }

    @Test
    fun `callback before return grace finishes once and does not time out`() {
        open()
        val session = picker()
        session.paused()
        session.resumed()
        assertTrue(session.callbackReceived())
        advance(1_000)
        assertTrue(controller.state.value.choosing)
        session.finish("candidate", true)
        session.finish(null, false)
        advance(60_000)
        assertEquals("candidate", controller.state.value.alias)
        assertEquals(1, finishes)
    }

    @Test
    fun `cancel callback and host destruction release selection and ignore late results`() {
        open()
        val cancelled = picker()
        assertTrue(cancelled.callbackReceived())
        cancelled.finish(null, true)
        assertFalse(controller.state.value.choosing)
        assertFalse(controller.state.value.selectionFailed)
        val destroyed = picker()
        destroyed.finish(null, true)
        controller.reset() // Login screen leaves composition.
        destroyed.finish("late", true)
        advance(60_000)
        assertNull(controller.state.value.origin)
        assertEquals(2, finishes)
    }

    @Test
    fun `key access check with no completion cannot keep choosing indefinitely`() {
        open()
        val session = picker()
        session.paused()
        assertTrue(session.callbackReceived())
        session.resumed()
        advance(15_000)
        assertFalse(controller.state.value.choosing)
        assertTrue(controller.state.value.selectionFailed)
        session.finish("late", true)
        assertNull(controller.state.value.alias)
    }

    @Test
    fun `already queued recovery cannot discard a callback received in the meantime`() {
        open()
        val session = picker()
        val staleRecovery = pending.single().second
        session.paused()
        session.resumed()
        assertTrue(session.callbackReceived())
        staleRecovery()
        assertTrue(controller.state.value.choosing)
        session.finish("candidate", true)
        assertEquals("candidate", controller.state.value.alias)
        assertEquals(1, finishes)
    }

    @Test
    fun `leaving login and opening another origin rejects the pending picker result`() {
        open()
        val session = picker()
        session.paused()
        controller.offer(controller.reset(), "https://other.test:9443/".toHttpUrl())
        assertTrue(session.callbackReceived())
        session.finish("old-origin", true)
        assertNull(controller.state.value.alias)
        assertEquals(
            "other.test",
            controller.state.value.origin
                ?.host,
        )
        assertFalse(controller.state.value.choosing)
    }

    @Test
    fun `manual Connections editor keeps its draft after return without callback`() {
        val editor = CertificateEditSession()
        val initial = CertificateBindingDraft("example.test", "8443", "previous")
        var draft = initial
        var choosing = true
        val token = editor.beginSelection()
        val session =
            CertificatePickerSession(
                schedule = { delay, action ->
                    val task = now + delay to action
                    pending += task
                    val cancel: () -> Unit = { pending.remove(task) }
                    cancel
                },
                result = { alias, valid ->
                    if (editor.accept(token)) {
                        choosing = false
                        if (valid && alias != null) draft = draft.copy(alias = alias, certificateReselected = true)
                    }
                },
            )
        session.launched()
        session.paused()
        session.resumed()
        advance(1_000)
        assertFalse(choosing)
        assertEquals(initial, draft)
        assertTrue(draft.canSave(null, emptyMap()))
        assertFalse(session.callbackReceived())
        session.finish("late", true)
        assertEquals(initial, draft)
    }
}
