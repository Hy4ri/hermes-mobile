package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.m57.hermescontrol.data.local.ComposerDraftKey
import com.m57.hermescontrol.data.local.ComposerDraftStore
import com.m57.hermescontrol.data.local.DataScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val DRAFT_SAVE_DELAY_MS = 350L

/** A binding belongs to one navigation generation, never to the ambient connection (#1605). */
internal class ComposerDraftBinding(
    private val store: ComposerDraftStore,
    initialKey: ComposerDraftKey?,
    private val onFailure: () -> Unit = {},
) {
    val input = TextFieldState()
    private var key = initialKey
    private var edited = false
    private var loaded = false
    private var observedText = ""

    fun observeText() {
        val text = input.text.toString()
        if (text != observedText) {
            edited = true
            observedText = text
        }
    }

    fun restore(text: String?) {
        if (loaded) return
        // Inspect live input too: prefill/typing can precede snapshotFlow's next emission.
        if (!edited && input.text.isEmpty() && !text.isNullOrEmpty()) {
            input.replaceComposerDraft(text)
        }
        observedText = input.text.toString()
        loaded = true
    }

    fun load(): Deferred<String?>? = key?.let(store::load)

    fun save(): Deferred<Unit>? {
        observeText()
        if (!loaded && !edited) return null
        return key?.let { track(store.save(it, input.text.toString())) }
    }

    /** Session creation gives the same composer a stored id; it is not a navigation. */
    fun bindStoredSession(sessionId: String?): Deferred<Unit>? {
        val previous = key ?: return null
        if (previous.sessionId != null || sessionId == null) return null
        save()
        val next = previous.copy(sessionId = sessionId)
        key = next
        return track(store.rekey(previous, next))
    }

    private fun track(operation: Deferred<Unit>): Deferred<Unit> {
        operation.invokeOnCompletion { failure ->
            if (failure != null && failure !is CancellationException) onFailure()
        }
        return operation
    }

    fun accepted(): Deferred<Unit>? {
        edited = true
        loaded = true
        input.replaceComposerDraft("")
        observedText = ""
        return key?.let { track(store.save(it, "")) }
    }
}

@Composable
internal fun rememberPersistentComposerDraft(
    scope: DataScope?,
    generation: Long,
    sessionId: String?,
    onFailure: () -> Unit,
): ComposerDraftBinding {
    val context = LocalContext.current
    val store = remember(context) { ComposerDraftStore.get(context) }
    val binding =
        remember(scope, generation) {
            ComposerDraftBinding(store, scope?.let { ComposerDraftKey(it, sessionId) }, onFailure)
        }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Enqueue a move during composition's successful apply, before a send can clear the new key.
    SideEffect { binding.bindStoredSession(sessionId) }
    LaunchedEffect(binding) {
        // Start observing before the disk read. A restore must not overwrite a user edit or prefill.
        val observer =
            launch(start = CoroutineStart.UNDISPATCHED) {
                snapshotFlow { binding.input.text.toString() }.collect {
                    binding.observeText()
                }
            }
        try {
            binding.restore(binding.load()?.await())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep live input usable, but do not report the failed read as a restored draft.
            onFailure()
        }
        try {
            snapshotFlow { binding.input.text.toString() }.collectLatest {
                // The separate observer tracks edits throughout loading and the debounce interval.
                binding.observeText()
                delay(DRAFT_SAVE_DELAY_MS)
                try {
                    binding.save()?.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The binding reports IO errors, including when this collector has been cancelled.
                }
            }
        } finally {
            observer.cancel()
        }
    }
    DisposableEffect(binding, lifecycle) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) binding.save()
            }
        lifecycle.addObserver(observer)
        onDispose {
            // The store owns IO jobs, so navigation cancellation cannot discard this captured text.
            binding.save()
            lifecycle.removeObserver(observer)
        }
    }
    return binding
}
