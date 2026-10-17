package com.m57.hermescontrol.ui.chat.components

import com.m57.hermescontrol.data.local.ComposerDraftKey
import com.m57.hermescontrol.data.local.ComposerDraftStore
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatUiState
import com.m57.hermescontrol.ui.chat.MessageRole
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerDraftBindingTest {
    private val owner = DataScope("connection", "https://one.local", "main")
    private val key = ComposerDraftKey(owner, "session")
    private val store = mockk<ComposerDraftStore>()

    init {
        every { store.save(any(), any()) } answers { CompletableDeferred(Unit) }
        every { store.rekey(any(), any()) } answers { CompletableDeferred(Unit) }
    }

    private fun ComposerDraftBinding.type(text: String) {
        input.edit { replace(0, length, text) }
    }

    @Test
    fun `untouched composer never overwrites an unread saved draft`() {
        val binding = ComposerDraftBinding(store, key)
        assertNull(binding.save())
        verify(exactly = 0) { store.save(any(), any()) }
    }

    @Test
    fun `restores text only once`() {
        val binding = ComposerDraftBinding(store, key)
        binding.restore("saved")
        assertEquals("saved", binding.input.text.toString())
        binding.type("")
        binding.restore("late duplicate")
        assertEquals("", binding.input.text.toString())
    }

    @Test
    fun `observed typing followed by deletion fences a late restore`() {
        val binding = ComposerDraftBinding(store, key)
        binding.type("typed")
        binding.observeText()
        binding.type("")
        binding.observeText()
        binding.restore("old draft")
        assertEquals("", binding.input.text.toString())
    }

    @Test
    fun `live prefill blocks restore before the observer runs`() {
        val binding = ComposerDraftBinding(store, key)
        binding.type("shared text")
        binding.restore("old draft")
        assertEquals("shared text", binding.input.text.toString())
    }

    @Test
    fun `save captures exact text and owner`() =
        runTest {
            val binding = ComposerDraftBinding(store, key)
            val text = "  مرحبا 🚀\n\t" + "long ".repeat(10_000)
            binding.type(text)
            binding.save()!!.await()
            verify(exactly = 1) { store.save(key, text) }
        }

    @Test
    fun `new chat handoff saves before moving and later writes use stored id`() =
        runTest {
            val unbound = key.copy(sessionId = null)
            val binding = ComposerDraftBinding(store, unbound)
            binding.type("before")
            binding.bindStoredSession("session")!!.await()
            assertEquals("before", binding.input.text.toString())
            binding.type("after")
            binding.save()!!.await()
            verifyOrder {
                store.save(unbound, "before")
                store.rekey(unbound, key)
                store.save(key, "after")
            }
            assertNull(binding.bindStoredSession("different"))
        }

    @Test
    fun `new chat restore in flight survives stored identity handoff`() =
        runTest {
            val unbound = key.copy(sessionId = null)
            val disk = CompletableDeferred<String?>()
            every { store.load(unbound) } returns disk
            val binding = ComposerDraftBinding(store, unbound)
            val loading = binding.load()!!
            binding.bindStoredSession("session")!!.await()
            disk.complete("saved new chat")
            binding.restore(loading.await())
            binding.save()!!.await()
            verify { store.save(key, "saved new chat") }
        }

    @Test
    fun `different navigation bindings never overwrite each other`() =
        runTest {
            val other = key.copy(scope = owner.copy(activeProfileId = "work"))
            val a = ComposerDraftBinding(store, key)
            val b = ComposerDraftBinding(store, other)
            a.restore("draft A")
            b.restore("draft B")
            a.type("edited A")
            a.save()!!.await()
            b.save()!!.await()
            verify { store.save(key, "edited A") }
            verify { store.save(other, "draft B") }
        }

    @Test
    fun `accepted send clears the saved draft and fences late restore`() =
        runTest {
            val binding = ComposerDraftBinding(store, key)
            binding.type("sent text")
            binding.accepted()!!.await()
            binding.restore("old disk text")
            assertEquals("", binding.input.text.toString())
            verify(exactly = 1) { store.save(key, "") }
            binding.type("next prompt")
            binding.save()!!.await()
            verify { store.save(key, "next prompt") }
        }

    @Test
    fun `rejected text remains recoverable without an acceptance clear`() =
        runTest {
            val binding = ComposerDraftBinding(store, key)
            binding.type("rejected text")
            binding.save()!!.await()
            assertEquals("rejected text", binding.input.text.toString())
            verify { store.save(key, "rejected text") }
            verify(exactly = 0) { store.save(key, "") }
        }

    @Test
    fun `missing owner never starts storage operations`() {
        val binding = ComposerDraftBinding(store, null)
        binding.type("local text")
        assertNull(binding.load())
        assertNull(binding.save())
        assertNull(binding.bindStoredSession("session"))
        assertNull(binding.accepted())
        verify(exactly = 0) { store.save(any(), any()) }
        verify(exactly = 0) { store.load(any()) }
        verify(exactly = 0) { store.rekey(any(), any()) }
    }

    @Test
    fun `unprompted gateway ids keep the restart safe new chat slot`() {
        val empty = ChatUiState(currentSessionId = "ephemeral")
        assertNull(empty.composerDraftSessionId)
        val notice = empty.copy(messages = listOf(ChatMessage(role = MessageRole.SYSTEM, content = "Notice")))
        assertNull(notice.composerDraftSessionId)
        val prompted = empty.copy(messages = listOf(ChatMessage(role = MessageRole.USER, content = "Prompt")))
        assertEquals("ephemeral", prompted.composerDraftSessionId)
        val resumed = empty.copy(composerSessionId = "existing")
        assertEquals("existing", resumed.composerDraftSessionId)
    }

    @Test
    fun `flush and acceptance failures reach the error callback without awaiting`() =
        runTest {
            var failures = 0
            val failed = CompletableDeferred<Unit>().apply { completeExceptionally(IllegalStateException("IO failed")) }
            every { store.save(any(), any()) } returns failed
            val binding = ComposerDraftBinding(store, key) { failures++ }
            binding.type("unsaved")
            binding.save()
            assertEquals(1, failures)
            binding.accepted()
            assertEquals(2, failures)
        }
}
