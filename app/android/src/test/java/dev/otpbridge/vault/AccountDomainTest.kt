package dev.otpbridge.vault

import org.junit.Assert.*
import org.junit.Test

class AccountDomainTest {
    private fun acct(id: String, issuer: String = "", label: String = "user", domain: String = "") = AccountSummary(id, label, issuer, "SHA1", 6, 30, domain)

    @Test fun normalizesUrlsToBareHost() {
        assertEquals("login.example.com", AccountDomain.normalize("https://Login.Example.com:443/path?x=1#y"))
        assertEquals("example.com", AccountDomain.normalize("  example.com.  "))
        assertEquals("example.com", AccountDomain.normalize("*.example.com"))
        assertEquals("", AccountDomain.normalize("   "))
        assertEquals("accounts.google.com", AccountDomain.hostOf("https://accounts.google.com"))
        listOf("exa mple.com", "-bad.com", "a..b", "http://").forEach { bad ->
            try { AccountDomain.normalize(bad); fail("should reject $bad") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun exactDomainBeatsParentDomainAndNameText() {
        val exact = acct("1", domain = "accounts.example.com")
        val parent = acct("2", domain = "example.com")
        val text = acct("3", issuer = "Example")
        assertEquals(listOf(exact), AccountDomain.matches(listOf(text, parent, exact), "accounts.example.com"))
        assertEquals(listOf(parent), AccountDomain.matches(listOf(text, parent), "accounts.example.com"))
        assertEquals(listOf(text), AccountDomain.matches(listOf(text), "accounts.example.com"))
    }

    @Test fun lookalikeDomainsNeverMatchSavedDomain() {
        val bank = acct("1", issuer = "Bank", domain = "bank.com")
        assertTrue(AccountDomain.matches(listOf(bank), "evilbank.com").isEmpty())
        assertTrue(AccountDomain.matches(listOf(bank), "bank.com.evil.net").isEmpty())
        assertEquals(0, AccountDomain.score(bank, "notbank.com"))
    }

    @Test fun multipleAccountsOnSameDomainAreAllReturnedForUserChoice() {
        val a = acct("1", label = "work@x.com", domain = "github.com")
        val b = acct("2", label = "home@x.com", domain = "github.com")
        assertEquals(listOf(a, b), AccountDomain.matches(listOf(a, b, acct("3", domain = "gitlab.com")), "github.com"))
    }

    @Test fun textFallbackUsesWholeWordsOnly() {
        assertEquals(1, AccountDomain.score(acct("1", issuer = "GitHub"), "github.com"))
        assertEquals(0, AccountDomain.score(acct("2", issuer = "Gitty"), "git.io"))
        assertEquals(1, AccountDomain.score(acct("3", label = "me via service.com"), "service.com"))
    }

    @Test fun searchByPrefilledHostFindsLinkedAndNamedAccounts() {
        val list = listOf(acct("1", domain = "github.com"), acct("2", issuer = "GitHub"), acct("3", issuer = "Other"))
        assertEquals(listOf("1", "2"), AccountSearch.filter(list, "github.com", force = true).map { it.id })
        assertEquals(listOf("3"), AccountSearch.filter(list, "other", force = true).map { it.id })
        assertEquals(list, AccountSearch.filter(list, "", force = true))
    }

    @Test fun catalogRoundTripKeepsDomainAndReadsOldEntries() {
        val encoded = AccountSummaryCodec.encode(listOf(acct("1", domain = "github.com"), acct("2")))
        val decoded = AccountSummaryCodec.decode(encoded)
        assertEquals("github.com", decoded[0].domain); assertEquals("", decoded[1].domain)
        val legacy = """[{"id":"x","label":"l","issuer":"i","algorithm":"SHA1","digits":6,"period":30}]"""
        assertEquals("", AccountSummaryCodec.decode(legacy)[0].domain)
    }

    // Regression: an older account with a linked domain must not hide a newer, unlinked
    // account for the same site. Both must be visible and neither silently auto-selected.
    @Test fun secondAccountOnSameSiteIsNeverHiddenByOlderLinkedOne() {
        val old = acct("old", issuer = "Example", label = "first@x.com", domain = "example.com")
        val new = acct("new", issuer = "Example", label = "second@x.com")
        val other = acct("other", issuer = "Unrelated")
        val list = listOf(old, new, other)
        assertEquals(listOf(old), AccountDomain.matches(list, "example.com")) // ranking unchanged
        assertEquals(listOf(old, new), AccountDomain.related(list, "example.com"))
        assertNull(AccountDomain.autoSelect(list, "example.com"))
        assertEquals(setOf("old", "new"), AccountSearch.filter(list, "example.com", force = true).map { it.id }.toSet())
    }

    @Test fun singleRelatedAccountIsStillAutoSelected() {
        val only = acct("1", issuer = "GitHub", domain = "github.com")
        assertEquals(only, AccountDomain.autoSelect(listOf(only, acct("2", issuer = "Other")), "github.com"))
    }
}
