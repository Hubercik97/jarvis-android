package pl.zamirski.jarvis.core

import java.text.Normalizer

/**
 * Logika trybu rozmowy (bez patrzenia na ekran): po odpowiedzi głosowej Jarvis
 * sam zaczyna słuchać, dopóki użytkownik nie zakończy rozmowy.
 */
object ConversationFlow {

    /** Słowa grzecznościowe i wołacze ignorowane przy rozpoznawaniu końca rozmowy. */
    private val FILLERS = setOf(
        "dobra", "dobrze", "ok", "okej", "okay", "no", "jarvis", "jarvisie", "dzarwis",
        "dziekuje", "dzieki", "ci", "bardzo", "wielkie", "super", "swietnie",
    )

    /** Polecenia kończące rozmowę (po normalizacji: małe litery, bez polskich znaków i interpunkcji). */
    private val END_PHRASES = setOf(
        "to wszystko", "to juz wszystko", "na razie to wszystko", "wystarczy", "na razie wystarczy",
        "koniec", "koniec rozmowy", "zakoncz", "zakoncz rozmowe", "konczymy", "stop", "cisza",
        "do widzenia", "do uslyszenia", "na razie", "pa", "nic wiecej", "nic wiecej nie trzeba",
    )

    /** Same podziękowania („dzięki”, „dziękuję bardzo”) też kończą rozmowę. */
    private val THANKS = setOf("dziekuje", "dzieki", "dziekuje bardzo", "dzieki wielkie", "dziekuje ci")

    const val END_REPLY = "W porządku, kończę rozmowę. Naciśnij mikrofon, gdy będziesz mnie potrzebować."

    fun normalize(text: String): String {
        val noL = text.replace('ł', 'l').replace('Ł', 'L')
        val ascii = Normalizer.normalize(noL, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return ascii.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Czy wypowiedź oznacza „kończymy rozmowę”. Dłuższe pytania nigdy nie są tak traktowane. */
    fun isEndPhrase(text: String): Boolean {
        val n = normalize(text)
        if (n.isEmpty()) return false
        if (n in END_PHRASES || n in THANKS) return true
        val words = n.split(' ')
        if (words.size > 7) return false
        // Zdejmujemy wypełniacze z początku i końca: „dobra jarvis to wszystko dzięki”.
        var start = 0
        var end = words.size
        while (start < end && isFiller(words, start)) start++
        while (end > start && isFiller(words, end - 1)) end--
        val core = words.subList(start, end).joinToString(" ")
        if (core.isEmpty()) return words.any { it == "dziekuje" || it == "dzieki" }
        return core in END_PHRASES
    }

    private fun isFiller(words: List<String>, i: Int): Boolean = words[i] in FILLERS

    /**
     * Czy po zakończonej odpowiedzi Jarvis ma znowu zacząć słuchać.
     * Tylko gdy tryb rozmowy trwa, odpowiedź została wypowiedziana na głos,
     * a aplikacja jest na ekranie (Android nie pozwala na mikrofon w tle bez usługi pierwszoplanowej).
     */
    fun shouldListenAgain(sessionActive: Boolean, replySpoken: Boolean, appInForeground: Boolean): Boolean =
        sessionActive && replySpoken && appInForeground
}
