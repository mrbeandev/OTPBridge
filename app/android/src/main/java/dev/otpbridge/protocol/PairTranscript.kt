package dev.otpbridge.protocol

import java.security.MessageDigest

/** JSON.stringify-compatible transcript bytes (Android org.json may escape forward slashes). */
object PairTranscript {
    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c -> when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        } }
        append('"')
    }
    fun canonical(channel: String, browserPublicKey: String, phonePublicKey: String, browserName: String, phoneName: String, expiresAt: Long): String =
        "[1,${quote(channel)},${quote(browserPublicKey)},${quote(phonePublicKey)},${quote(browserName)},${quote(phoneName)},$expiresAt]"
    fun sas(channel: String, browserPublicKey: String, phonePublicKey: String, browserName: String, phoneName: String, expiresAt: Long): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical(channel, browserPublicKey, phonePublicKey, browserName, phoneName, expiresAt).toByteArray(Charsets.UTF_8))
        return (((hash[0].toInt() and 255) shl 16) or ((hash[1].toInt() and 255) shl 8) or (hash[2].toInt() and 255)).toString().padStart(8, '0')
    }
}
