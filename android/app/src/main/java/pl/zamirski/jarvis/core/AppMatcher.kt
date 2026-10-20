package pl.zamirski.jarvis.core

/** Aplikacja widoczna w menu telefonu (z ikoną). */
data class AppEntry(val label: String, val packageName: String)

/**
 * Dopasowanie nazwy wypowiedzianej przez użytkownika do zainstalowanych aplikacji.
 * Działa wyłącznie na telefonie — lista aplikacji nie jest wysyłana do chmury.
 */
object AppMatcher {

    sealed class Match {
        data class Found(val app: AppEntry) : Match()
        data class Ambiguous(val candidates: List<AppEntry>) : Match()
        data class NotFound(val suggestions: List<AppEntry>) : Match()
    }

    /** Słowa pomijane w nazwie: „otwórz aplikację Spotify”, „włącz grę Candy Crush”. */
    private val FILLER = setOf(
        "aplikacja", "aplikacje", "aplikacji", "apke", "apka", "apki",
        "gra", "gre", "gry", "grze", "program", "otworz", "uruchom", "wlacz", "odpal", "prosze", "moja", "moje", "mi",
    )

    const val MIN_SCORE = 60
    private const val SUGGEST_SCORE = 40

    fun normalize(name: String): String =
        ConversationFlow.normalize(name).split(' ').filter { it.isNotBlank() && it !in FILLER }.joinToString(" ")

    /** 0–100: jak bardzo nazwa aplikacji pasuje do zapytania. */
    fun score(query: String, label: String): Int {
        val q = normalize(query)
        val l = normalize(label)
        if (q.isEmpty() || l.isEmpty()) return 0
        if (q == l) return 100
        if (q.replace(" ", "") == l.replace(" ", "")) return 95 // „you tube” / „YouTube”
        val qt = q.split(' ')
        val lt = l.split(' ')
        if (lt.size > 1 && l.startsWith("$q ")) return 85 // „mapy” → „Mapy Google”
        if (lt.containsAll(qt)) return 75 // wszystkie słowa zapytania są w nazwie
        if (qt.containsAll(lt) && l.length >= 3) return 70 // „spotify music” → „Spotify”
        val sim = similarity(q, l)
        return when {
            sim >= 0.85 -> 68
            sim >= 0.7 -> 55
            sim >= 0.55 -> 42
            else -> 0
        }
    }

    fun match(query: String, apps: List<AppEntry>): Match {
        if (normalize(query).isEmpty()) return Match.NotFound(emptyList())
        val scored = apps.map { it to score(query, it.label) }.filter { it.second > 0 }.sortedByDescending { it.second }
        if (scored.isEmpty()) return Match.NotFound(emptyList())
        val best = scored.first().second
        if (best >= MIN_SCORE) {
            val top = scored.filter { it.second == best }.map { it.first }.distinctBy { it.packageName }
            return if (top.size == 1) Match.Found(top.first()) else Match.Ambiguous(top.take(3))
        }
        return Match.NotFound(scored.filter { it.second >= SUGGEST_SCORE }.map { it.first }.take(3))
    }

    /** Podobieństwo 0–1 na podstawie odległości edycyjnej (literówki w rozpoznanej mowie). */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val n = maxOf(a.length, b.length)
        if (n == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / n
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }

    /** Wynik dla AI — krótki i bez listy wszystkich aplikacji (tylko do 3 podobnych nazw). */
    fun resultText(query: String, match: Match): String = when (match) {
        is Match.Found -> "Otwarto aplikację: ${match.app.label}"
        is Match.Ambiguous -> "Pasuje kilka aplikacji: ${match.candidates.joinToString { it.label }}. Zapytaj, którą otworzyć."
        is Match.NotFound ->
            "Nie znaleziono aplikacji „$query” na telefonie." +
                if (match.suggestions.isNotEmpty()) " Podobne: ${match.suggestions.joinToString { it.label }}." else ""
    }
}
