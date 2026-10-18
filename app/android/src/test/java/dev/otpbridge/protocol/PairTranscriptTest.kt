package dev.otpbridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class PairTranscriptTest {
    @Test fun sharedGoldenSas() {
        assertEquals("15976721", PairTranscript.sas("AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM", "qy_KMomDIsII-y2rUEi9Q8NVxkMPWIiXy1dJYc-pgG8", "_Dszk2elIl1TqS04AyOv0DXXgXttG-R9lG9rCanL3AY", "Test laptop", "Test phone", 1800000300000))
    }
    @Test fun javascriptCanonicalEscapes() {
        assertEquals("[1,\"channel\",\"browser\",\"phone\",\"A/B\\n\\\"C\",\"📱\",123]", PairTranscript.canonical("channel", "browser", "phone", "A/B\n\"C", "📱", 123))
    }
}
