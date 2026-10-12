package com.m57.hermescontrol.data.remote

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** The socket supplies the real destination host and port, including redirect destinations. */
internal class OriginCaTrustManager(
    private val global: X509TrustManager,
    private val bindings: CertificateBindings,
    private val origin: CertificateOrigin,
    private val selected: (String) -> X509TrustManager,
) : X509TrustManager by global {
    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
    ) {
        try {
            bindings.checkedBinding(origin) { present, reference ->
                if (!present) {
                    global.checkServerTrusted(chain, authType)
                } else {
                    try {
                        // #1518: selected() first intersects installed anchors with the active source policy.
                        // Two independently successful path validations would not establish this intersection.
                        selected(requireNotNull(reference)).checkServerTrusted(chain, authType)
                    } catch (e: Exception) {
                        throw CertificateException("Server chain does not satisfy selected CA", e)
                    }
                }
            }
        } catch (e: CertificateException) {
            throw e
        }
    }
}
