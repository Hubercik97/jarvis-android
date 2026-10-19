package pl.zamirski.jarvis.core

object Urls {
    /** Zwraca adres bez końcowego "/" albo null, gdy adres jest pusty lub nie używa HTTPS. */
    fun normalizeBackend(raw: String?): String? {
        val url = raw?.trim()?.trimEnd('/') ?: return null
        if (url.isEmpty()) return null
        if (!url.startsWith("https://", ignoreCase = true)) return null
        if (url.length <= "https://".length) return null
        if (url.any { it.isWhitespace() }) return null
        return url
    }
}
