package dev.otpbridge.vault
import org.junit.Assert.*
import org.junit.Test

class AccountSearchTest {
    private fun account(id: Int, label: String = "user$id@example.com", issuer: String = "Service") = AccountSummary("id$id", label, issuer, "SHA1", 6, 30)
    @Test fun searchIsAbsentThroughFourAndPresentAboveFour() {
        for (n in 0..4) assertFalse(AccountSearch.shouldShow((1..n).map { account(it) }))
        assertTrue(AccountSearch.shouldShow((1..5).map { account(it) }))
    }
    @Test fun matchesIssuerAndLabelCaseInsensitivelyAndPreservesReferences() {
        val list = listOf(account(1, "Alice@Example.com", "GitHub")) + (2..5).map { account(it) }
        assertEquals(listOf("id1"), AccountSearch.filter(list, " GITHUB alice ").map { it.id })
        assertTrue(AccountSearch.filter(list, "not-found").isEmpty())
        assertEquals(list, AccountSearch.filter(list, ""))
    }
    @Test fun aHiddenSearchCannotFilterASmallBoundAccountList() {
        val list = listOf(account(1))
        assertEquals(list, AccountSearch.filter(list, "unmatched old query"))
    }
}
