package pl.zamirski.jarvis.core

/**
 * Wykrywanie słowa „Jarvis” w TEKŚCIE z rozpoznawania mowy (pl-PL).
 * Rozpoznawanie mowy zapisuje to imię różnie, więc akceptujemy typowe warianty polskiej wymowy.
 * Czysta logika — testowana jednostkowo.
 */
object WakeWord {

    /** Warianty po normalizacji (małe litery, bez polskich znaków). */
    private val VARIANTS = listOf(
        "jarvis", "jarvisie", "jarvisa", "jarwis", "jarwisie", "jarwisa",
        "dzarwis", "dzarvis", "dzarwisie", "dzarvisie", "dzarwisa", "dzarwiz", "dzerwis", "dzervis",
        "dzarwi", "jervis", "dzarvi", "darwis",
    )
    private val WAKE = Regex("(?:^| )(?:hej |hey |ej |halo )?(${VARIANTS.joinToString("|")})(?= |$)")

    /**
     * null — brak słowa „Jarvis”;
     * "" — samo „Jarvis” (trzeba wysłuchać polecenia);
     * tekst — polecenie wypowiedziane od razu po słowie („Jarvis, przełącz na kalendarz”).
     */
    fun match(transcript: String): String? {
        val norm = ConversationFlow.normalize(transcript)
        val m = WAKE.find(norm) ?: return null
        // Polecenie bierzemy z oryginalnego tekstu (z polskimi znakami): liczymy słowa do końca słowa „Jarvis”.
        val wordsBefore = norm.substring(0, m.range.last + 1).trim().split(' ').size
        val original = transcript.trim().split(Regex("\\s+"))
        return original.drop(wordsBefore).joinToString(" ").trim().trimStart(',', '.', ':', ';', '!', '?', '-', ' ')
    }

    /** Jak traktować kod błędu android.speech.SpeechRecognizer podczas ciągłego nasłuchu. */
    enum class ErrorKind {
        /** Cisza / brak dopasowania / koniec sesji — po prostu słuchamy dalej. */
        RESTART,

        /** Mikrofon lub usługa mowy zajęta (np. rozmowa telefoniczna) — przerwa i ponowienie. */
        BUSY,

        /** Brak polskiego języka w rozpoznawaniu na urządzeniu — przejście na usługę Google. */
        LANGUAGE,

        /** Problem z siecią / serwerem — ponawianie z rosnącą przerwą. */
        RETRY,

        /** Brak zgody na mikrofon — nasłuch nie ma sensu. */
        FATAL,
    }

    fun classify(code: Int): ErrorKind = when (code) {
        5, 6, 7 -> ErrorKind.RESTART // CLIENT, SPEECH_TIMEOUT, NO_MATCH
        3, 8, 10 -> ErrorKind.BUSY // AUDIO (mikrofon zajęty, np. nagrywanie głosówki), RECOGNIZER_BUSY, TOO_MANY_REQUESTS
        12, 13 -> ErrorKind.LANGUAGE // LANGUAGE_NOT_SUPPORTED, LANGUAGE_UNAVAILABLE
        9 -> ErrorKind.FATAL // INSUFFICIENT_PERMISSIONS
        else -> ErrorKind.RETRY // NETWORK_TIMEOUT, NETWORK, SERVER, SERVER_DISCONNECTED, inne
    }
}

/**
 * Ochrona przed pętlą: jeśli sesje rozpoznawania kończą się natychmiast raz za razem,
 * zamiast kręcić się w kółko (bateria) zgłaszamy błąd.
 */
class RestartGuard(private val windowMs: Long = 20_000, private val maxInWindow: Int = 30) {
    private val starts = ArrayDeque<Long>()

    /** Zwraca false, gdy w oknie czasu było za dużo startów. */
    fun allow(nowMs: Long): Boolean {
        while (starts.isNotEmpty() && nowMs - starts.first() > windowMs) starts.removeFirst()
        if (starts.size >= maxInWindow) return false
        starts.addLast(nowMs)
        return true
    }

    fun reset() = starts.clear()
}
