package com.m57.hermescontrol.data.tunnel

import java.security.MessageDigest
import java.util.Base64

/** Result of comparing a presented host key with the stored trust entries. */
enum class HostKeyStatus {
    MATCH,
    CHANGED,
    UNKNOWN,
}

/**
 * An OpenSSH `known_hosts` file, storing trust entries:
 * `host keytype base64`. Non-default ports are stored using OpenSSH format `[host]:port`.
 *
 * Keys are kept per (host identity, key type) so a server that offers both an Ed25519
 * and an RSA host key does not look like a key change when it switches.
 */
class KnownHosts private constructor(
    private val entries: Map<String, String>,
) {
    val size: Int get() = entries.size

    /** [HostKeyStatus] for [host] presenting [keyBase64] of [keyType]. */
    fun check(
        host: String,
        keyType: String,
        keyBase64: String,
    ): HostKeyStatus {
        val stored = entries[entryKey(host, keyType)] ?: return HostKeyStatus.UNKNOWN
        return if (stored == keyBase64) HostKeyStatus.MATCH else HostKeyStatus.CHANGED
    }

    fun withAccepted(
        host: String,
        keyType: String,
        keyBase64: String,
    ): KnownHosts = KnownHosts(entries + (entryKey(host, keyType) to keyBase64))

    fun without(host: String): KnownHosts {
        val target = normalizeHost(host)
        return KnownHosts(entries.filterKeys { it.substringBefore(' ') != target })
    }

    fun serialize(): String =
        entries.entries
            .sortedBy { it.key }
            .joinToString(separator = "\n", postfix = "\n") { (key, value) ->
                val host = key.substringBefore(' ')
                val type = key.substringAfter(' ')
                "$host $type $value"
            }

    override fun toString(): String = "KnownHosts(${entries.size} entries)"

    companion object {
        private const val KEY_FIELD_SEPARATOR = ' '

        fun parse(text: String): KnownHosts {
            val parsed = mutableMapOf<String, String>()
            text.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith('#')) return@forEach
                val fields = line.split(KEY_FIELD_SEPARATOR).filter { it.isNotEmpty() }
                if (fields.size < 3) return@forEach
                val host = normalizeHost(fields[0])
                val type = fields[1]
                val key = fields[2]
                if (host.isEmpty() || type.isEmpty() || key.isEmpty()) return@forEach
                parsed[entryKey(host, type)] = key
            }
            return KnownHosts(parsed)
        }

        /**
         * Computes the trust identity string for a given host and port.
         * Default port 22 or non-positive ports yield the bare host name.
         * Non-default ports yield `[host]:port` (OpenSSH known_hosts convention).
         */
        fun identity(
            host: String,
            port: Int = 22,
        ): String {
            val bare = bareHost(host)
            if (bare.isEmpty()) return ""
            return if (port == 22 || port <= 0) bare else "[$bare]:$port"
        }

        /**
         * Normalizes a host string for known_hosts lookup/storage:
         * preserves non-default port if present (or parses `[host]:port`),
         * normalizes default port 22 or bare host to bare host.
         */
        fun normalizeHost(host: String): String {
            val trimmed = host.trim()
            if (trimmed.startsWith('[') && trimmed.contains(']')) {
                val inside = trimmed.substringAfter('[').substringBefore(']')
                val after = trimmed.substringAfter(']', "")
                if (after.startsWith(':')) {
                    val port = after.removePrefix(":").toIntOrNull()
                    if (port != null && port != 22 && port > 0) {
                        return "[$inside]:$port"
                    }
                }
                return inside
            }
            val lastColon = trimmed.lastIndexOf(':')
            val hasSingleColon = trimmed.indexOf(':') == lastColon
            if (hasSingleColon && lastColon > 0) {
                val name = trimmed.substring(0, lastColon)
                val port = trimmed.substring(lastColon + 1).toIntOrNull()
                if (port != null && port != 22 && port > 0) {
                    return "[$name]:$port"
                }
                return name
            }
            return trimmed
        }

        /** Extracts the bare host without brackets or port. */
        private fun bareHost(host: String): String {
            val trimmed = host.trim()
            if (trimmed.startsWith('[') && trimmed.contains(']')) {
                return trimmed.substringAfter('[').substringBefore(']')
            }
            val lastColon = trimmed.lastIndexOf(':')
            val hasSingleColon = trimmed.indexOf(':') == lastColon
            if (hasSingleColon && lastColon > 0) {
                return trimmed.substring(0, lastColon)
            }
            return trimmed
        }

        /**
         * Host key type is the first length-prefixed string of the SSH key blob:
         * `ssh-ed25519`, `ssh-rsa`, `ecdsa-sha2-nistp256`, ...
         */
        fun keyType(keyBlob: ByteArray): String {
            if (keyBlob.size < 4) return ""
            val length =
                ((keyBlob[0].toInt() and 0xFF) shl 24) or
                    ((keyBlob[1].toInt() and 0xFF) shl 16) or
                    ((keyBlob[2].toInt() and 0xFF) shl 8) or
                    (keyBlob[3].toInt() and 0xFF)
            if (length <= 0 || length > keyBlob.size - 4) return ""
            return String(keyBlob, 4, length, Charsets.UTF_8)
        }

        /** OpenSSH-style `SHA256:<base64, no padding>` fingerprint. */
        fun fingerprint(keyBlob: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(keyBlob)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }

        private fun entryKey(
            host: String,
            keyType: String,
        ): String = "${normalizeHost(host)}$KEY_FIELD_SEPARATOR$keyType"
    }
}
