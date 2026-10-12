package com.m57.hermescontrol.ui.settings

import com.m57.hermescontrol.data.remote.CertificateOrigin
import okhttp3.HttpUrl

/** Validation and unsaved-change detection for the manual certificate editor. */
internal data class CertificateBindingDraft(
    val host: String,
    val port: String,
    val alias: String?,
    val certificateReselected: Boolean = false,
) {
    val validPort: Boolean get() = port.toIntOrNull()?.let { it in 1..65535 } == true
    val validHost: Boolean get() = runCatching { CertificateOrigin.parse(host, "443") }.isSuccess
    val origin: CertificateOrigin? get() = runCatching { CertificateOrigin.parse(host, port) }.getOrNull()

    fun duplicate(
        previous: HttpUrl?,
        bindings: Map<String, String?>,
    ): Boolean = origin?.let { it.url != previous && it.storageKey in bindings } == true

    fun canSave(
        previous: HttpUrl?,
        bindings: Map<String, String?>,
    ): Boolean = origin != null && !alias.isNullOrBlank() && !duplicate(previous, bindings)

    fun changedFrom(initial: CertificateBindingDraft): Boolean = this != initial
}
