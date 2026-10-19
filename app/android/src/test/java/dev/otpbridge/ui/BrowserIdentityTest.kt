package dev.otpbridge.ui
import org.junit.Assert.*
import org.junit.Test

class BrowserIdentityTest {
    @Test fun chromeLinuxUsesFriendlyLabelInsteadOfRawAgent() {
        val b = BrowserIdentity.from("Mozilla/5.0 (X11; Linux x86_64) Chrome/155.0.0.0 Safari/537.36", "Linux x86_64")
        assertEquals("Chrome 155 · Linux", b.summary)
    }
    @Test fun edgeTakesPrecedenceOverEmbeddedChrome() {
        assertEquals("Edge", BrowserIdentity.from("Chrome/155.0 Safari/537.36 Edg/155.0", "Win32").browser)
    }
    @Test fun safariAndUnknownAreHandled() {
        assertEquals("Safari 18 · macOS", BrowserIdentity.from("Mozilla/5.0 (Macintosh) Version/18.1 Safari/605", "MacIntel").summary)
        assertEquals("Browser", BrowserIdentity.from("", "").browser)
    }
}
