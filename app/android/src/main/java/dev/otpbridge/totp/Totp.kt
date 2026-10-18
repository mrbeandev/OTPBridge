package dev.otpbridge.totp

import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class TotpAccount(val label: String, val issuer: String, val secret: String, val algorithm: String = "SHA1", val digits: Int = 6, val period: Int = 30)

object Totp {
    fun decodeBase32(input: String): ByteArray {
        val text = input.trim().uppercase().replace(" ", "")
        require(text.isNotEmpty() && text.matches(Regex("[A-Z2-7]+={0,6}"))) { "Secret must be valid Base32" }
        val raw = text.trimEnd('=')
        if ('=' in text) require(text.length % 8 == 0 && text.length - raw.length == when (raw.length % 8) { 2 -> 6; 4 -> 4; 5 -> 3; 7 -> 1; else -> 0 }) { "Invalid Base32 padding" }
        require(raw.length % 8 in setOf(0, 2, 4, 5, 7)) { "Invalid Base32 length" }
        var bits = 0; var buffer = 0
        val result = ArrayList<Byte>()
        raw.forEach { ch ->
            val value = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(ch)
            buffer = (buffer shl 5) or value; bits += 5
            if (bits >= 8) { bits -= 8; result.add((buffer shr bits).toByte()); buffer = buffer and ((1 shl bits) - 1) }
        }
        require(buffer == 0) { "Invalid Base32 trailing bits" }
        require(result.size >= 10) { "Secret must contain at least 80 bits" }
        return result.toByteArray()
    }

    fun validate(account: TotpAccount): TotpAccount {
        require(account.label.isNotBlank() && account.label.length <= 200) { "Account label is required (maximum 200 characters)" }
        require(account.issuer.length <= 200) { "Issuer is too long" }
        require(account.algorithm in setOf("SHA1", "SHA256", "SHA512")) { "Unsupported TOTP algorithm" }
        require(account.digits in setOf(6, 8)) { "Only six or eight digits are supported" }
        require(account.period in 15..120) { "Period must be between 15 and 120 seconds" }
        decodeBase32(account.secret).fill(0)
        return account
    }

    fun parse(uriText: String): TotpAccount {
        require(uriText.length <= 4096) { "QR data is too large" }
        val uri = URI(uriText.trim())
        require(uri.scheme == "otpauth" && uri.host == "totp" && uri.fragment == null) { "Only otpauth://totp accounts are supported" }
        fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        val params = linkedMapOf<String, String>()
        uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.forEach {
            val parts = it.split('=', limit = 2); val key = decode(parts[0])
            require(key !in params) { "Duplicate TOTP parameter: $key" }
            params[key] = decode(parts.getOrElse(1) { "" })
        }
        require(params.keys.all { it in setOf("secret", "issuer", "algorithm", "digits", "period") }) { "Unsupported TOTP parameter" }
        val label = decode(uri.rawPath.orEmpty().removePrefix("/"))
        val labelIssuer = label.substringBefore(':', "")
        val issuer = params["issuer"] ?: labelIssuer
        require(labelIssuer.isEmpty() || issuer == labelIssuer) { "Issuer does not match the account label" }
        return validate(TotpAccount(label.substringAfter(':'), issuer, params["secret"] ?: error("Missing TOTP secret"), params["algorithm"]?.uppercase() ?: "SHA1", params["digits"]?.toIntOrNull() ?: if ("digits" in params) error("Invalid digit count") else 6, params["period"]?.toIntOrNull() ?: if ("period" in params) error("Invalid period") else 30))
    }

    fun generate(account: TotpAccount, unixSeconds: Long): String {
        validate(account); require(unixSeconds >= 0)
        val secret = decodeBase32(account.secret)
        try {
            val mac = Mac.getInstance("Hmac${account.algorithm}")
            mac.init(SecretKeySpec(secret, "Hmac${account.algorithm}"))
            val digest = mac.doFinal(ByteBuffer.allocate(8).putLong(unixSeconds / account.period).array())
            val offset = digest.last().toInt() and 15
            val number = ((digest[offset].toInt() and 127) shl 24) or ((digest[offset + 1].toInt() and 255) shl 16) or ((digest[offset + 2].toInt() and 255) shl 8) or (digest[offset + 3].toInt() and 255)
            val modulus = if (account.digits == 8) 100_000_000 else 1_000_000
            digest.fill(0)
            return (number % modulus).toString().padStart(account.digits, '0')
        } finally { secret.fill(0) }
    }
}
