package dev.otpbridge.totp

import java.net.URI
import java.net.URLDecoder

/**
 * Bulk import from other authenticators. Supports:
 * - Google Authenticator "Transfer accounts" QR codes
 *   (otpauth-migration://offline?data=<base64 protobuf MigrationPayload>), possibly split across several QR codes;
 * - Plain text with one otpauth://totp URI per line (Aegis, 2FAS, Bitwarden, KeePassXC and similar exports).
 *
 * Nothing here persists or logs secrets; callers decide what to save.
 */
object AuthenticatorImport {
    data class Entry(val account: TotpAccount, val weak: Boolean)
    data class Skipped(val source: String, val reason: String)
    data class Batch(val index: Int, val size: Int, val batchId: Int)
    data class Result(val entries: List<Entry>, val skipped: List<Skipped>, val batch: Batch? = null)

    private const val MAX_TEXT = 512 * 1024
    private const val MAX_ENTRIES = 500

    /** Parses free text: otpauth://totp URIs and/or Google migration URIs, one per line. */
    fun parseText(text: String): Result {
        require(text.length <= MAX_TEXT) { "Import file is too large" }
        val entries = ArrayList<Entry>(); val skipped = ArrayList<Skipped>(); var batch: Batch? = null
        var lineNo = 0
        for (rawLine in text.lineSequence()) {
            lineNo++
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isEmpty() || line.startsWith("#")) continue
            val where = "Line $lineNo"
            when {
                line.startsWith("otpauth-migration://", ignoreCase = true) -> try {
                    val r = parseMigration(line); entries += r.entries; skipped += r.skipped.map { it.copy(source = "$where · ${it.source}") }; batch = r.batch ?: batch
                } catch (e: IllegalArgumentException) { skipped += Skipped(where, e.message ?: "Invalid transfer code") }
                line.startsWith("otpauth://", ignoreCase = true) -> try {
                    val a = Totp.parse(line, lenient = true); entries += Entry(a, Totp.isWeak(a.secret))
                } catch (e: Exception) { skipped += Skipped(where, friendly(e)) }
                else -> skipped += Skipped(where, "Not an otpauth link")
            }
            require(entries.size <= MAX_ENTRIES) { "Too many accounts in one import" }
        }
        return Result(entries, skipped, batch)
    }

    /** Decodes one Google Authenticator transfer QR code. */
    fun parseMigration(uriText: String): Result {
        require(uriText.length <= 64 * 1024) { "Transfer code is too large" }
        val uri = try { URI(uriText.trim()) } catch (_: java.net.URISyntaxException) { throw IllegalArgumentException("Transfer code is malformed") }
        require(uri.scheme.equals("otpauth-migration", true) && uri.host == "offline") { "Not a Google Authenticator transfer code" }
        val data = uri.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }.firstOrNull { it[0] == "data" }?.getOrNull(1)
            ?: throw IllegalArgumentException("Transfer code has no data")
        val bytes = try { java.util.Base64.getDecoder().decode(URLDecoder.decode(data, "UTF-8").replace(' ', '+')) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Transfer code data is corrupted")
        }
        try { return decodePayload(bytes) } finally { bytes.fill(0) }
    }

    private fun decodePayload(bytes: ByteArray): Result {
        val entries = ArrayList<Entry>(); val skipped = ArrayList<Skipped>()
        var batchSize = 1; var batchIndex = 0; var batchId = 0
        val r = Proto(bytes)
        while (r.more()) {
            val (field, wire) = r.tag()
            when {
                field == 1 && wire == 2 -> {
                    val sub = r.bytes()
                    try { decodeOtp(sub, entries, skipped) } finally { sub.fill(0) }
                }
                field == 3 && wire == 0 -> batchSize = r.varint().toInt()
                field == 4 && wire == 0 -> batchIndex = r.varint().toInt()
                field == 5 && wire == 0 -> batchId = r.varint().toInt()
                else -> r.skip(wire)
            }
            require(entries.size + skipped.size <= MAX_ENTRIES) { "Too many accounts in one transfer code" }
        }
        val batch = if (batchSize > 1) Batch(batchIndex, batchSize, batchId) else null
        return Result(entries, skipped, batch)
    }

    // MigrationPayload.OtpParameters: 1 secret, 2 name, 3 issuer, 4 algorithm, 5 digits, 6 type, 7 counter
    private fun decodeOtp(bytes: ByteArray, entries: MutableList<Entry>, skipped: MutableList<Skipped>) {
        var secret: ByteArray? = null; var name = ""; var issuer = ""; var algorithm = 1; var digits = 1; var type = 2
        val r = Proto(bytes)
        try {
            while (r.more()) {
                val (field, wire) = r.tag()
                when {
                    field == 1 && wire == 2 -> { secret?.fill(0); secret = r.bytes() }
                    field == 2 && wire == 2 -> name = String(r.bytes(), Charsets.UTF_8)
                    field == 3 && wire == 2 -> issuer = String(r.bytes(), Charsets.UTF_8)
                    field == 4 && wire == 0 -> algorithm = r.varint().toInt()
                    field == 5 && wire == 0 -> digits = r.varint().toInt()
                    field == 6 && wire == 0 -> type = r.varint().toInt()
                    else -> r.skip(wire)
                }
            }
            val label = name.substringAfter(':').trim().ifBlank { name.trim() }.ifBlank { issuer.ifBlank { "Imported account" } }
            val display = listOf(issuer, label).filter { it.isNotBlank() }.distinct().joinToString(" · ").take(80)
            if (type == 1) { skipped += Skipped(display, "Counter-based (HOTP) codes are not supported"); return }
            if (type != 2 && type != 0) { skipped += Skipped(display, "Unsupported code type"); return }
            val alg = when (algorithm) { 0, 1 -> "SHA1"; 2 -> "SHA256"; 3 -> "SHA512"; else -> { skipped += Skipped(display, "Unsupported algorithm (MD5)"); return } }
            val dig = when (digits) { 0, 1 -> 6; 2 -> 8; else -> { skipped += Skipped(display, "Unsupported digit count"); return } }
            val s = secret ?: run { skipped += Skipped(display, "Missing secret"); return }
            if (s.size < Totp.MIN_SECRET_BYTES) { skipped += Skipped(display, "Secret is too short"); return }
            val resolvedIssuer = issuer.ifBlank { name.substringBefore(':', "").trim() }
            val account = try { Totp.validate(TotpAccount(label.take(200), resolvedIssuer.take(200), Totp.encodeBase32(s), alg, dig, 30)) } catch (e: Exception) { skipped += Skipped(display, friendly(e)); return }
            entries += Entry(account, s.size < 10)
        } finally { secret?.fill(0) }
    }

    private fun friendly(e: Exception): String = when {
        e.message?.contains("Base32") == true -> "Secret is not valid Base32"
        e.message?.contains("too short") == true -> "Secret is too short"
        e is IllegalArgumentException || e is IllegalStateException -> e.message?.take(80) ?: "Invalid account"
        else -> "Invalid account"
    }

    /** Minimal bounded protobuf reader for the migration payload. */
    private class Proto(private val b: ByteArray) {
        private var i = 0
        fun more() = i < b.size
        fun varint(): Long {
            var shift = 0; var result = 0L
            while (true) {
                require(i < b.size && shift < 64) { "Transfer code data is corrupted" }
                val x = b[i++].toInt() and 0xff
                result = result or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return result
                shift += 7
            }
        }
        fun tag(): Pair<Int, Int> { val t = varint(); require(t > 0) { "Transfer code data is corrupted" }; return (t ushr 3).toInt() to (t and 7).toInt() }
        fun bytes(): ByteArray {
            val n = varint(); require(n in 0..(b.size - i).toLong()) { "Transfer code data is corrupted" }
            return b.copyOfRange(i, i + n.toInt()).also { i += n.toInt() }
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> { require(i + 8 <= b.size) { "Transfer code data is corrupted" }; i += 8 }
                2 -> bytes().fill(0)
                5 -> { require(i + 4 <= b.size) { "Transfer code data is corrupted" }; i += 4 }
                else -> throw IllegalArgumentException("Transfer code data is corrupted")
            }
        }
    }
}
