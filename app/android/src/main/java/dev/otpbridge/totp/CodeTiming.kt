package dev.otpbridge.totp

object CodeTiming {
    fun validFrom(now: Long, period: Int): Long = now / (period * 1000L) * (period * 1000L)
    fun validUntil(now: Long, period: Int): Long = validFrom(now, period) + period * 1000L
    fun secondsRemaining(now: Long, until: Long): Int = ((until - now).coerceAtLeast(0) + 999).div(1000).toInt()
}
