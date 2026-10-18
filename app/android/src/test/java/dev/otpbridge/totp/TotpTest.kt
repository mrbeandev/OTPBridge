package dev.otpbridge.totp

import org.junit.Assert.*
import org.junit.Test

class TotpTest {
    private fun base32(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"; val out = StringBuilder(); var bits = 0; var value = 0
        bytes.forEach { b -> value = (value shl 8) or (b.toInt() and 255); bits += 8; while (bits >= 5) { bits -= 5; out.append(alphabet[(value shr bits) and 31]) } }
        if (bits > 0) out.append(alphabet[(value shl (5 - bits)) and 31])
        return out.toString()
    }
    @Test fun rfc6238AllAlgorithms() {
        val times = listOf(59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L)
        val expected = mapOf("SHA1" to listOf("94287082", "07081804", "14050471", "89005924", "69279037", "65353130"), "SHA256" to listOf("46119246", "68084774", "67062674", "91819424", "90698825", "77737706"), "SHA512" to listOf("90693936", "25091201", "99943326", "93441116", "38618901", "47863826"))
        val seed = "1234567890123456789012345678901234567890123456789012345678901234"
        expected.forEach { (algorithm, values) -> val length = when (algorithm) { "SHA1" -> 20; "SHA256" -> 32; else -> 64 }; val account = TotpAccount("test", "RFC", base32(seed.take(length).toByteArray()), algorithm, 8); times.forEachIndexed { index, time -> assertEquals(values[index], Totp.generate(account, time)) } }
    }
    @Test fun parsesEnrollment() {
        val account = Totp.parse("otpauth://totp/Example:alice%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example&algorithm=SHA256&digits=8&period=60")
        assertEquals("alice@example.com", account.label); assertEquals("Example", account.issuer); assertEquals(60, account.period); assertEquals(8, account.digits)
    }
    @Test fun rejectsMalformedAndUnsupportedUris() {
        listOf("otpauth://hotp/test?secret=JBSWY3DPEHPK3PXP", "otpauth://totp/test?secret=INVALID!", "otpauth://totp/test?secret=JBSWY3DPEHPK3PXP&digits=7", "otpauth://totp/test?secret=JBSWY3DPEHPK3PXP&period=0", "otpauth://totp/test?secret=JBSWY3DPEHPK3PXP&secret=JBSWY3DPEHPK3PXP", "otpauth://totp/A:test?secret=JBSWY3DPEHPK3PXP&issuer=B").forEach { uri -> try { Totp.parse(uri); fail("Should reject $uri") } catch (_: IllegalArgumentException) { } }
    }
    @Test fun sameCounterSameCode() { val a = TotpAccount("test", "", "JBSWY3DPEHPK3PXP"); assertEquals(Totp.generate(a, 60), Totp.generate(a, 89)); assertNotEquals(Totp.generate(a, 89), Totp.generate(a, 90)) }
}
