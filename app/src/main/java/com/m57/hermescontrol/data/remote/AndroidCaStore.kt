package com.m57.hermescontrol.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@Serializable
internal enum class CaSource { SYSTEM, USER }

/** Alias + fingerprint: replacing a certificate under the same alias must never broaden a binding. */
@Serializable
internal data class CaReference(
    val alias: String,
    val source: CaSource,
    val sha256: String,
    val subject: String,
) {
    fun encode(): String = Json.encodeToString(this)

    companion object {
        fun decode(value: String): CaReference = Json.decodeFromString(value)
    }
}

internal data class AndroidCaCertificate(
    val reference: CaReference,
    val certificate: X509Certificate,
)

/** Read-only access to active Android roots. No import, removal or Android security-setting mutation. */
internal class AndroidCaStore(
    private val load: () -> KeyStore = { KeyStore.getInstance("AndroidCAStore").apply { load(null) } },
) {
    fun list(): List<AndroidCaCertificate> {
        val store = load()
        return store
            .aliases()
            .toList()
            .mapNotNull { alias ->
                val source = source(alias) ?: return@mapNotNull null
                val certificate = store.getCertificate(alias) as? X509Certificate ?: return@mapNotNull null
                if (certificate.basicConstraints < 0) return@mapNotNull null
                AndroidCaCertificate(
                    CaReference(alias, source, fingerprint(certificate), certificate.subjectX500Principal.name),
                    certificate,
                )
            }.sortedWith(compareBy({ it.reference.source }, { it.reference.subject }, { it.reference.alias }))
    }

    fun resolve(reference: CaReference): X509Certificate {
        val certificate = load().getCertificate(reference.alias) as? X509Certificate
        if (source(reference.alias) != reference.source || certificate == null ||
            certificate.basicConstraints < 0 || fingerprint(certificate) != reference.sha256
        ) {
            throw CertificateException("Selected Android CA is unavailable")
        }
        certificate.checkValidity()
        return certificate
    }

    /** Intersect the active source policy with this exact installed anchor before path building.
     * A USER reference stays USER even when an identical SYSTEM certificate exists.
     */
    fun resolveAllowed(
        reference: CaReference,
        includeUser: Boolean,
    ): X509Certificate {
        if (reference.source == CaSource.USER && !includeUser) {
            throw CertificateException("Selected user CA is excluded by the active trust policy")
        }
        return resolve(reference)
    }

    fun trust(
        reference: CaReference,
        includeUser: Boolean,
    ): X509TrustManager {
        val certificate = resolveAllowed(reference, includeUser)
        val store =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry("selected-ca", certificate)
            }
        return TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply {
                init(store)
            }.trustManagers
            .filterIsInstance<X509TrustManager>()
            .single()
    }

    private fun source(alias: String): CaSource? =
        when {
            alias.startsWith("system:") -> CaSource.SYSTEM
            alias.startsWith("user:") -> CaSource.USER
            else -> null
        }

    private fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02X".format(it) }
}
