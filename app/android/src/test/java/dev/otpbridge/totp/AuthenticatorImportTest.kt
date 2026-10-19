package dev.otpbridge.totp

import org.junit.Assert.*
import org.junit.Test

class AuthenticatorImportTest {
    // Builds a Google Authenticator MigrationPayload protobuf for tests.
    private fun varint(v: Long): ByteArray { val out = java.io.ByteArrayOutputStream(); var x = v; while (true) { if (x and 0x7f.inv().toLong() == 0L) { out.write(x.toInt()); break }; out.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }; return out.toByteArray() }
    private fun field(n: Int, bytes: ByteArray) = varint(((n shl 3) or 2).toLong()) + varint(bytes.size.toLong()) + bytes
    private fun num(n: Int, v: Int) = varint((n shl 3).toLong()) + varint(v.toLong())
    private fun otp(secret: ByteArray, name: String, issuer: String, alg: Int = 1, digits: Int = 1, type: Int = 2) =
        field(1, field(1, secret) + field(2, name.toByteArray()) + field(3, issuer.toByteArray()) + num(4, alg) + num(5, digits) + num(6, type))
    private fun uri(payload: ByteArray) = "otpauth-migration://offline?data=" + java.net.URLEncoder.encode(java.util.Base64.getEncoder().encodeToString(payload), "UTF-8")
    private val secret = "12345678901234567890".toByteArray()

    @Test fun decodesGoogleTransferPayloadAndGeneratesCorrectCodes() {
        val r = AuthenticatorImport.parseMigration(uri(otp(secret, "GitHub:alice@example.com", "GitHub") + otp(secret, "bob", "Acme", alg = 2, digits = 2) + num(3, 1) + num(4, 0)))
        assertEquals(2, r.entries.size); assertTrue(r.skipped.isEmpty()); assertNull(r.batch)
        val a = r.entries[0].account
        assertEquals("alice@example.com", a.label); assertEquals("GitHub", a.issuer); assertEquals("SHA1", a.algorithm); assertEquals(6, a.digits)
        // RFC 6238 SHA1 vector at T=59 (8 digits 94287082 -> 6 digits 287082)
        assertEquals("287082", Totp.generate(a, 59))
        assertEquals("SHA256", r.entries[1].account.algorithm); assertEquals(8, r.entries[1].account.digits)
    }
    @Test fun reportsBatchAndSkipsHotpAndBadEntries() {
        val r = AuthenticatorImport.parseMigration(uri(otp(secret, "ok", "X") + otp(secret, "counter", "Y", type = 1) + otp(byteArrayOf(1, 2), "tiny", "Z") + num(3, 3) + num(4, 1) + num(5, 77)))
        assertEquals(1, r.entries.size); assertEquals(2, r.skipped.size)
        assertTrue(r.skipped[0].reason.contains("HOTP")); assertEquals(AuthenticatorImport.Batch(1, 3, 77), r.batch)
    }
    @Test fun rejectsCorruptedPayloadsWithoutCrashing() {
        listOf("otpauth-migration://offline?data=%%%", "otpauth-migration://offline", "otpauth-migration://offline?data=" + java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x0a, 0x7f, 1))).forEach { u ->
            try { AuthenticatorImport.parseMigration(u); fail("should reject $u") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun parsesTextExportWithCommonRealWorldQuirks() {
        val text = """
            # comment
            otpauth://totp/Service.com:1234567890?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHP&issuer=Service.com
            otpauth://totp/Other:me%40mail.com?secret=JBSWY3DPEHPK3PXP&issuer=Other%20Name&image=https%3A%2F%2Fx
            otpauth://totp/Short:x?secret=JBSWY3DPEHP&issuer=Short
            otpauth://hotp/Counter:x?secret=JBSWY3DPEHPK3PXP&counter=1
            not a link
        """.trimIndent()
        val r = AuthenticatorImport.parseText(text)
        assertEquals(3, r.entries.size)
        assertEquals("Service.com", r.entries[0].account.issuer); assertFalse(r.entries[0].weak)
        assertEquals("Other Name", r.entries[1].account.issuer) // issuer param wins over label prefix in lenient mode
        assertTrue(r.entries[2].weak)
        assertEquals(2, r.skipped.size)
    }
    @Test fun strictScanStillRejectsExtrasButLenientImportAccepts() {
        val u = "otpauth://totp/A:b?secret=JBSWY3DPEHPK3PXP&issuer=A&image=x"
        try { Totp.parse(u); fail() } catch (_: IllegalArgumentException) { }
        assertEquals("b", Totp.parse(u, lenient = true).label)
    }
    @Test fun lenientBase32MatchesOtherAuthenticators() {
        // 27 chars (not a valid strict length) decodes by dropping trailing bits; codes stay deterministic.
        val a = TotpAccount("t", "", "JBSWY3DPEHPK3PXPJBSWY3DPEHP")
        assertEquals(Totp.generate(a, 30), Totp.generate(a.copy(secret = "jbsw y3dp ehpk 3pxp jbsw y3dp ehp"), 30))
        try { Totp.decodeBase32("JBSW"); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun ordersScreenshotsByEmbeddedPartRegardlessOfPickOrder() {
        val part = { i: Int -> uri(otp(secret, "acct$i", "X") + num(3, 3) + num(4, i) + num(5, 9)) }
        val plain = "otpauth://totp/P:q?secret=JBSWY3DPEHPK3PXP"
        val ordered = AuthenticatorImport.orderByPart(listOf(part(2), plain, part(0), part(1)))
        assertEquals(listOf(part(0), part(1), part(2), plain), ordered)
    }
}
