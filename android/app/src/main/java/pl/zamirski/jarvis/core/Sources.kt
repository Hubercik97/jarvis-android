package pl.zamirski.jarvis.core

/** Źródło z wyszukiwania w internecie, numerowane tak jak cytaty [n] w odpowiedzi. */
data class Source(val n: Int, val title: String, val url: String, val date: String?)

object Sources {

    const val HEADER = "Źródła:"
    private val CITATION = Regex("\\s?\\[\\d+(?:[,\\s–-]+\\d+)*]")
    /** Adres bez końcowej interpunkcji zdania (kropka po adresie zostaje w tekście). */
    private val URL = Regex("\\s?https?://\\S*[^\\s.,;:!?)]")
    private val LINE = Regex("^\\[(\\d+)] (.+?) — (https?://\\S+)$")

    /** Tekst do historii i ekranu: odpowiedź + lista źródeł (z adresami — przydatne też w pytaniach uzupełniających). */
    fun withSources(text: String, sources: List<Source>): String {
        if (sources.isEmpty()) return text
        val lines = sources.joinToString("\n") { s ->
            val title = s.title.replace("—", "-").trim().ifBlank { s.url }
            val date = s.date?.let { " ($it)" } ?: ""
            "[${s.n}] $title$date — ${s.url}"
        }
        return "$text\n\n$HEADER\n$lines"
    }

    /** Tekst do czytania na głos: bez listy źródeł, bez [n] i bez adresów. */
    fun speakable(text: String): String =
        text.substringBefore("\n\n$HEADER\n")
            .replace(CITATION, "")
            .replace(URL, "")
            .replace(Regex("[ \\t]+"), " ")
            .trim()

    /** Rozdziela zapis z historii na treść i źródła (do klikalnej listy na ekranie). */
    fun split(text: String): Pair<String, List<Source>> {
        val idx = text.indexOf("\n\n$HEADER\n")
        if (idx < 0) return text to emptyList()
        val body = text.substring(0, idx)
        val sources = text.substring(idx + HEADER.length + 3).lines().mapNotNull { line ->
            val m = LINE.find(line.trim()) ?: return@mapNotNull null
            val (n, titlePart, url) = m.destructured
            val date = Regex(" \\((\\d{4}-\\d{2}-\\d{2})\\)$").find(titlePart)?.groupValues?.get(1)
            val title = if (date != null) titlePart.removeSuffix(" ($date)") else titlePart
            Source(n.toInt(), title, url, date)
        }
        return body to sources
    }
}
