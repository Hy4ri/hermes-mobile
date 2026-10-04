package com.m57.hermescontrol.data.remote

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/** One manager per TLS origin. Android KeyChain is accessed only on the TLS worker. */
internal class ClientCertificateKeyManager(
    private val choose: (Array<out String>?, Array<out Principal>?, Socket?) -> String?,
    private val privateKey: (String) -> PrivateKey?,
    private val certificateChain: (String) -> Array<X509Certificate>?,
) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = choose(keyType, issuers, socket)

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = choose(keyType, issuers, null)

    override fun getPrivateKey(alias: String?): PrivateKey? = alias?.let(privateKey)

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = alias?.let(certificateChain)

    override fun getClientAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String>? = null

    override fun getServerAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String>? = null

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = null
}
