package dev.otpbridge.vault

object AccountSearch {
    const val SEARCH_THRESHOLD = 4
    fun shouldShow(accounts: List<AccountSummary>): Boolean = accounts.size > SEARCH_THRESHOLD
    /**
     * Word search over issuer, account and domain. A query that is a domain also matches accounts
     * linked to that domain or a parent domain. `force` searches even small lists.
     */
    fun filter(accounts: List<AccountSummary>, query: String, force: Boolean = false): List<AccountSummary> {
        if ((!force && !shouldShow(accounts)) || query.isBlank()) return accounts
        val host = AccountDomain.hostOf(query)
        val words = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        return accounts.filter { account ->
            (host.isNotEmpty() && AccountDomain.score(account, host) > 0) ||
                words.all { word -> "${account.issuer} ${account.label} ${account.domain}".contains(word, ignoreCase = true) }
        }
    }
}
