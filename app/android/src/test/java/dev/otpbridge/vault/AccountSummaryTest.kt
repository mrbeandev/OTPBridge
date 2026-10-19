package dev.otpbridge.vault

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class AccountSummaryTest {
    @Test fun displayCatalogContainsNoSecretOrCode() {
        val item = AccountSummary("opaque-reference", "test@example.com", "Example", "SHA1", 6, 30)
        val encoded = AccountSummaryCodec.encode(listOf(item))
        assertEquals(listOf(item), AccountSummaryCodec.decode(encoded))
        val keys = JSONArray(encoded).getJSONObject(0).keys().asSequence().toSet()
        assertEquals(setOf("id", "label", "issuer", "algorithm", "digits", "period"), keys)
        assertFalse(keys.contains("secret")); assertFalse(keys.contains("otp")); assertFalse(keys.contains("key"))
    }
    @Test fun deletingMetadataKeepsOtherAccountsAndAllowsEmptyCatalog() {
        val a = AccountSummary("a", "first", "Example", "SHA1", 6, 30)
        val b = AccountSummary("b", "second", "Example", "SHA256", 8, 30)
        assertEquals(listOf(b), AccountSummaryCodec.decode(AccountSummaryCodec.encode(listOf(a,b).filterNot { it.id == "a" })))
        assertTrue(AccountSummaryCodec.decode(AccountSummaryCodec.encode(emptyList())).isEmpty())
    }
}
