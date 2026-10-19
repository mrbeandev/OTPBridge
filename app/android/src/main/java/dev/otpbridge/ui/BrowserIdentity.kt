package dev.otpbridge.ui

/** Human-readable labels only; the QR values are context, not device attestation. */
data class BrowserIdentity(val browser: String, val version: String?, val system: String) {
    val summary: String get() = listOfNotNull(browser + (version?.let { " $it" } ?: ""), system.takeIf { it != "Unknown system" }).joinToString(" · ")
    companion object {
        fun from(userAgent: String, platform: String): BrowserIdentity {
            val ua = userAgent.take(240)
            val patterns = listOf("Edge" to "(?:Edg|EdgA|EdgiOS)/([0-9]+)", "Opera" to "(?:OPR|Opera)/([0-9]+)", "Firefox" to "(?:Firefox|FxiOS)/([0-9]+)", "Chrome" to "(?:Chrome|CriOS)/([0-9]+)", "Safari" to "Version/([0-9]+)")
            val match = patterns.firstNotNullOfOrNull { (name, pattern) -> Regex(pattern).find(ua)?.let { name to it.groupValues[1] } }
            val system = when {
                ua.contains("Android", true) || platform.contains("Android", true) -> "Android"
                ua.contains("iPhone", true) || ua.contains("iPad", true) -> "iOS"
                ua.contains("Windows", true) || platform.contains("Win", true) -> "Windows"
                ua.contains("Macintosh", true) || platform.contains("Mac", true) -> "macOS"
                ua.contains("CrOS", true) -> "ChromeOS"
                ua.contains("Linux", true) || platform.contains("Linux", true) -> "Linux"
                else -> "Unknown system"
            }
            return BrowserIdentity(match?.first ?: "Browser", match?.second, system)
        }
    }
}
