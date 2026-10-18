package dev.otpbridge.protocol

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StrictJsonTest {
    @Test fun rejectsCoerciveIntegerFields() {
        for (value in listOf("123", 1.5, JSONObject.NULL, -1L, 9007199254740992L)) {
            assertThrows(IllegalArgumentException::class.java) { StrictJson.fields(JSONObject().put("createdAt", value), setOf("createdAt")) }
        }
        StrictJson.fields(JSONObject().put("createdAt", 123L), setOf("createdAt"))
    }
    @Test fun rejectsUnknownFieldsAndNullOptionalReference() {
        assertThrows(IllegalArgumentException::class.java) { StrictJson.fields(JSONObject("{\"type\":\"fill.request\",\"extra\":\"x\"}"), setOf("type")) }
        assertThrows(IllegalArgumentException::class.java) { StrictJson.fields(JSONObject().put("type", "fill.request").put("accountRef", JSONObject.NULL), setOf("type"), setOf("accountRef")) }
    }
    @Test fun validatesUnicodeAndChannelScopedReviewedIdentity() {
        assertFalse(StrictJson.validUnicode("\uD800")); assertFalse(StrictJson.validUnicode("\uDC00"))
        assertTrue(StrictJson.validUnicode("Phone \uD83D\uDCF1 / தமிழ்"))
        val a = PhoneBridge.Pending("browserA", JSONObject().put("requestId", "same-id").put("origin", "https://a.example"))
        val b = PhoneBridge.Pending("browserB", JSONObject().put("requestId", "same-id").put("origin", "https://b.example"))
        assertNotEquals(a.key, b.key)
    }
}
