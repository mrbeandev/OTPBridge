package dev.otpbridge.vault

/** Owns only an in-memory authorized data-key copy; never persisted. */
class ForegroundSession {
    private var key: ByteArray? = null
    val unlocked get() = key != null
    fun unlock(authorized: ByteArray) { lock(); key = authorized.copyOf() }
    fun lock() { key?.fill(0); key = null }
    fun <T> use(action: (ByteArray) -> T): T {
        val copy = key?.copyOf() ?: error("Unlock this session first")
        return try { action(copy) } finally { copy.fill(0) }
    }
}
