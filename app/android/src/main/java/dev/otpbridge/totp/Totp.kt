package dev.otpbridge.totp

import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class TotpAccount(val label: String, val issuer: String, val secret: String, val algorithm: String = "SHA1", val digits: Int = 6, val period: Int = 30)

object Totp {
    /**
     * Lenient RFC 4648 Base32, matching mainstream authenticators: ignores spaces,
     * case and padding, and drops leftover trailing bits. Many services issue
     * secrets whose length is not a multiple of 8 characters or below 80 bits.
     */
    fun decodeBase32(input: String): ByteArray {
        val raw = input.trim().uppercase().replace(" ", "").trimEnd('=')
        require(raw.isNotEmpty() && raw.matches(Regex("[A-Z2-7]+"))) { "Secret must be valid Base32" }
        var bits = 0; var buffer = 0
        val result = ArrayList<Byte>(raw.length * 5 / 8)
        raw.forEach { ch ->
            buffer = (buffer shl 5) or "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(ch); bits += 5
            if (bits >= 8) { bits -= 8; result.add((buffer shr bits).toByte()); buffer = buffer and ((1 shl bits) - 1) }
        }
        require(result.size >= MIN_SECRET_BYTES) { "Secret is too short" }
        return result.toByteArray()
    }
    const val MIN_SECRET_BYTES = 5
    /** RFC 4226 recommends at least 128 bits and requires 80; shorter ones still work but are weak. */
    fun isWeak(secret: String): Boolean = decodeBase32(secret).let { val weak = it.size < 10; it.fill(0); weak }
    fun encodeBase32(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"; val out = StringBuilder(); var bits = 0; var value = 0
        bytes.forEach { b -> value = (value shl 8) or (b.toInt() and 255); bits += 8; while (bits >= 5) { bits -= 5; out.append(alphabet[(value shr bits) and 31]) } }
        if (bits > 0) out.append(alphabet[(value shl (5 - bits)) and 31])
        return out.toString()
    }

    /** Full validation for storage: a stored account must be nameable. */
    fun validate(account: TotpAccount): TotpAccount {
        require(account.label.isNotBlank() || account.issuer.isNotBlank()) { "Give the account a name" }
        return validateSetup(account)
    }
    /** Validates everything except the name, so nameless QR codes can reach the confirm screen. */
    fun validateSetup(account: TotpAccount): TotpAccount {
        require(account.label.length <= 200) { "Account name is too long (maximum 200 characters)" }
        require(account.issuer.length <= 200) { "Issuer is too long" }
        require(account.algorithm in setOf("SHA1", "SHA256", "SHA512")) { "Unsupported TOTP algorithm" }
        require(account.digits in setOf(6, 8)) { "Only six or eight digits are supported" }
        require(account.period in 15..120) { "Period must be between 15 and 120 seconds" }
        decodeBase32(account.secret).fill(0)
        return account
    }

    /**
     * Parses otpauth://totp. Strict mode (QR scan/manual) rejects unknown
     * parameters and issuer conflicts; lenient mode (bulk import from other
     * apps) ignores cosmetic extras such as image/color and prefers the issuer
     * parameter over the label prefix.
     */
    fun parse(uriText: String, lenient: Boolean = false): TotpAccount {
        require(uriText.length <= 4096) { "QR data is too large" }
        val uri = try { URI(uriText.trim()) } catch (_: java.net.URISyntaxException) { throw IllegalArgumentException("Link is malformed") }
        require(uri.scheme == "otpauth" && uri.host == "totp" && uri.fragment == null) { "Only otpauth://totp accounts are supported" }
        fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        val params = linkedMapOf<String, String>()
        uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.forEach {
            val parts = it.split('=', limit = 2); val key = decode(parts[0])
            require(key !in params) { "Duplicate TOTP parameter: $key" }
            params[key] = decode(parts.getOrElse(1) { "" })
        }
        if (!lenient) require(params.keys.all { it in setOf("secret", "issuer", "algorithm", "digits", "period") }) { "Unsupported TOTP parameter" }
        val label = decode(uri.rawPath.orEmpty().removePrefix("/"))
        val labelIssuer = label.substringBefore(':', "")
        val issuer = params["issuer"] ?: labelIssuer
        if (!lenient) require(labelIssuer.isEmpty() || issuer == labelIssuer) { "Issuer does not match the account label" }
        return validateSetup(TotpAccount(label.substringAfter(':'), issuer, params["secret"] ?: error("Missing TOTP secret"), params["algorithm"]?.uppercase() ?: "SHA1", params["digits"]?.toIntOrNull() ?: if ("digits" in params) error("Invalid digit count") else 6, params["period"]?.toIntOrNull() ?: if ("period" in params) error("Invalid period") else 30))
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
