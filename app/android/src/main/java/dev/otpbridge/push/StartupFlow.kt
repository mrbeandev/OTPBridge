package dev.otpbridge.push

/** Pure per-visit policy: UI prompts and navigation must never compete. */
class StartupFlow {
    var visit = 0L
        private set
    var requestNavigationConsumed = false
        private set
    var permissionAttempted = false
        private set
    fun beginVisit(): Long { visit++; requestNavigationConsumed = false; permissionAttempted = false; return visit }
    fun invalidate() { visit++ }
    fun canContinue(sessionMode: Boolean, unlocked: Boolean, hasAccounts: Boolean): Boolean = !sessionMode || unlocked || !hasAccounts
    fun takePermission(sessionMode: Boolean, unlocked: Boolean, hasAccounts: Boolean, canAsk: Boolean): Boolean {
        if (permissionAttempted || !canAsk || !canContinue(sessionMode, unlocked, hasAccounts)) return false
        permissionAttempted = true
        return true
    }
    fun takeSingleRequest(sessionMode: Boolean, unlocked: Boolean, hasAccounts: Boolean, pendingCount: Int, idle: Boolean): Boolean {
        if (requestNavigationConsumed || !idle || !canContinue(sessionMode, unlocked, hasAccounts)) return false
        if (pendingCount > 1) { requestNavigationConsumed = true; return false }
        if (pendingCount != 1) return false
        requestNavigationConsumed = true
        return true
    }
}
