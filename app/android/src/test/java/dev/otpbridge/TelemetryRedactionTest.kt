package dev.otpbridge

import org.junit.Assert.*
import org.junit.Test

class TelemetryRedactionTest {
    private fun r(s: String) = Telemetry.redact(s)
    @Test fun removesOtpCodesAndSecrets() {
        assertFalse(r("approved code 482913 sent").contains("482913"))
        assertFalse(r("seed JBSWY3DPEHPK3PXPJBSWY3DP").contains("JBSWY3DPEHPK3PXP"))
        assertFalse(r("otpauth://totp/Acme:bob@example.com?secret=ABC").contains("secret=ABC"))
        assertFalse(r("token Kq3n8vS0b2xW1cY7dE4fG6hJ9kL5mN0pQ2rS4tU6vW8").contains("Kq3n8vS0b2"))
    }
    @Test fun removesPersonalIdentifiers() {
        assertEquals("[email]", r("bob@example.com"))
        assertFalse(r("from 202.164.130.49").contains("202.164"))
        assertFalse(r("at 11.2820224, 75.7727232").contains("11.282"))
        assertEquals("https://github.com/[path]", r("https://github.com/login?return_to=x"))
    }
    @Test fun keepsUsefulDiagnosticText() {
        assertEquals("Relay error 409", r("Relay error 409"))
        assertEquals("ms=1532", r("ms=1532"))
    }
}
