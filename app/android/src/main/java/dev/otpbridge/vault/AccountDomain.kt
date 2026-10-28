package dev.otpbridge.vault

/** Domain normalization and request-to-account matching. */
object AccountDomain {
    /** "https://Login.Example.com:443/path" -> "login.example.com". Blank stays blank; invalid input is rejected. */
    fun normalize(input: String): String {
        var s = input.trim().lowercase()
        if (s.isEmpty()) return ""
        s = s.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').substringBefore(':').trimEnd('.')
        if (s.startsWith("*.")) s = s.removePrefix("*.")
        require(s.length in 1..253 && s.matches(Regex("[a-z0-9-]+(\\.[a-z0-9-]+)*")) && s.split('.').none { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') }) { "Enter a domain like example.com" }
        return s
    }

    /** Host of a request origin, e.g. "https://accounts.google.com" -> "accounts.google.com". */
    fun hostOf(origin: String): String = runCatching { normalize(origin) }.getOrDefault("")

    /**
     * Ranks accounts for a request host. 3 = exact domain match, 2 = request is a subdomain of the saved
     * domain (saved "google.com" matches "accounts.google.com"), 1 = host/registrable name appears in
     * issuer or label, 0 = no match. Saved domains are never matched by suffix text alone
     * ("evilexample.com" does not match "example.com").
     */
    fun score(account: AccountSummary, host: String): Int {
        if (host.isEmpty()) return 0
        val d = account.domain
        if (d.isNotEmpty()) return when { host == d -> 3; host.endsWith(".$d") -> 2; else -> 0 }
        val text = "${account.issuer} ${account.label}".lowercase()
        val base = host.split('.').let { parts -> if (parts.size >= 2) parts[parts.size - 2] else parts[0] }
        return if (text.contains(host) || (base.length >= 3 && Regex("\\b${Regex.escape(base)}\\b").containsMatchIn(text))) 1 else 0
    }

    /** Best-matching accounts, highest score first; empty if nothing matches. */
    fun matches(accounts: List<AccountSummary>, host: String): List<AccountSummary> {
        val scored = accounts.map { it to score(it, host) }.filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == best }.map { it.first }
    }

    /** Every account related to the host at all, best first. Used for the visible list. */
    fun related(accounts: List<AccountSummary>, host: String): List<AccountSummary> =
        accounts.map { it to score(it, host) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }

    /**
     * Account to preselect, or null to make the user choose. Only auto-selects when exactly one
     * account relates to the host at any strength, so a second login on the same site is never hidden
     * behind an older, better-linked one.
     */
    fun autoSelect(accounts: List<AccountSummary>, host: String): AccountSummary? =
        related(accounts, host).singleOrNull()
}
