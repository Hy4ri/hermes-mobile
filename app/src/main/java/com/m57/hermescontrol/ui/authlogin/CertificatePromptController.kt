package com.m57.hermescontrol.ui.authlogin

import android.util.Log
import androidx.annotation.StringRes
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.remote.CertificateOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl

internal data class CertificatePromptState(
    val origin: HttpUrl? = null,
    val alias: String? = null,
    val choosing: Boolean = false,
    val saving: Boolean = false,
    val selectionFailed: Boolean = false,
    @param:StringRes val error: Int? = null,
    val savedOrigin: HttpUrl? = null,
)

/** Owns only the optional certificate flow; it never retries or changes the login operation. */
internal class CertificatePromptController {
    private val mutableState = MutableStateFlow(CertificatePromptState())
    val state = mutableState.asStateFlow()
    private var revision = 0L
    private var saveJob: Job? = null

    @Synchronized
    fun reset(): Long {
        revision++
        saveJob?.cancel()
        saveJob = null
        mutableState.value = CertificatePromptState()
        return revision
    }

    @Synchronized
    fun offer(
        attempt: Long,
        url: HttpUrl,
    ) {
        if (attempt != revision || mutableState.value.origin != null) return
        val origin = CertificateOrigin.from(url) ?: return
        mutableState.value = CertificatePromptState(origin = origin.url)
    }

    @Synchronized
    fun beginSelection(): Long? {
        val current = mutableState.value
        if (current.origin == null || current.choosing || current.saving) return null
        mutableState.value = current.copy(choosing = true, selectionFailed = false, error = null)
        return ++revision
    }

    @Synchronized
    fun selected(
        attempt: Long,
        alias: String?,
        valid: Boolean,
    ) {
        if (attempt != revision) return
        revision++
        val current = mutableState.value
        mutableState.value =
            current.copy(
                alias = if (valid && alias != null) alias else current.alias,
                choosing = false,
                selectionFailed = !valid,
            )
    }

    @Synchronized
    fun save(
        scope: CoroutineScope,
        validate: suspend (HttpUrl, String) -> Unit,
        persist: (HttpUrl, String) -> Unit,
    ) {
        val current = mutableState.value
        val origin = current.origin ?: return
        val alias = current.alias ?: return
        if (current.choosing || current.saving) return
        val attempt = ++revision
        mutableState.value = current.copy(saving = true, selectionFailed = false, error = null)
        saveJob =
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        validate(origin, alias)
                        currentCoroutineContext().ensureActive()
                        synchronized(this@CertificatePromptController) {
                            if (revision == attempt) {
                                // Cancellation/reset and persistence have one ordering point; no save-after-dismiss race.
                                persist(origin, alias)
                                mutableState.value = CertificatePromptState(savedOrigin = origin)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // Exception messages/causes can contain URLs or credentials. Keep the
                    // exception type and original stack for diagnosis without logging them.
                    Log.w(
                        "CertificatePrompt",
                        "Certificate verification/save failed: ${failure.javaClass.name}\n" +
                            failure.stackTrace.joinToString("\n"),
                    )
                    synchronized(this@CertificatePromptController) {
                        if (revision == attempt) {
                            mutableState.value = current.copy(error = R.string.mtls_prompt_verification_details)
                        }
                    }
                }
            }
    }
}
