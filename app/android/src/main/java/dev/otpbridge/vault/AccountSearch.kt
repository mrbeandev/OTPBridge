package dev.otpbridge.vault

object AccountSearch {
    const val SEARCH_THRESHOLD = 4
    fun shouldShow(accounts: List<AccountSummary>): Boolean = accounts.size > SEARCH_THRESHOLD
    fun filter(accounts: List<AccountSummary>, query: String): List<AccountSummary> {
        if (!shouldShow(accounts) || query.isBlank()) return accounts
        val words = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        return accounts.filter { account -> words.all { word -> "${account.issuer} ${account.label}".contains(word, ignoreCase = true) } }
    }
}
