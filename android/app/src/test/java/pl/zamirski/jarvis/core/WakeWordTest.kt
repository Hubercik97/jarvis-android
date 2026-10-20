package pl.zamirski.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.zamirski.jarvis.core.WakeCycle.Effect
import pl.zamirski.jarvis.core.WakeCycle.Event
import pl.zamirski.jarvis.core.WakeCycle.State

class WakeWordTest {

    @Test
    fun `wake word alone means listen for a command`() {
        listOf("Jarvis", "jarvis.", "Dżarwis", "Hej Jarvis", "hej, dżarwis", "Halo Jarvis!").forEach {
            assertEquals("dla: $it", "", WakeWord.match(it))
        }
    }

    @Test
    fun `command spoken right after the wake word keeps polish characters`() {
        assertEquals("przełącz mnie na kalendarz", WakeWord.match("Jarvis, przełącz mnie na kalendarz"))
        assertEquals("która godzina?", WakeWord.match("Dżarwis która godzina?"))
        assertEquals("włącz latarkę", WakeWord.match("hej Jarvis włącz latarkę"))
        assertEquals("otwórz Spotify", WakeWord.match("no dobra Jarvis otwórz Spotify"))
    }

    @Test
    fun `speech recognition spellings and split words still wake jarvis`() {
        assertEquals("", WakeWord.match("Dżarvis"))
        assertEquals("", WakeWord.match("Dżerwis"))
        assertEquals("", WakeWord.match("Jarwis!"))
        assertEquals("otwórz Spotify", WakeWord.match("Jar wis otwórz Spotify"))
        assertEquals("która godzina", WakeWord.match("Dżar wis, która godzina"))
        assertEquals("włącz latarkę", WakeWord.match("Jarvisie włącz latarkę"))
        assertEquals("przełącz na kalendarz", WakeWord.match("Jarvis,przełącz na kalendarz".replace(",", ", ")))
    }

    @Test
    fun `ordinary speech does not wake jarvis`() {
        listOf(
            "Jarosław wraca jutro", "Na jarmarku było tłoczno", "Darwin napisał książkę",
            "zadzwoń do mamy", "", "jarvisowy", "Garaż jest otwarty", "W czerwcu jedziemy do Garwolina",
            "Karol jest w pracy", "jar z miodem", "zadzwoń do Jarka", "jaś wie", "Darwina",
        ).forEach { assertNull("nie powinno wybudzić: $it", WakeWord.match(it)) }
    }

    @Test
    fun `speech errors are classified for continuous listening`() {
        assertEquals(WakeWord.ErrorKind.RESTART, WakeWord.classify(7))
        assertEquals(WakeWord.ErrorKind.RESTART, WakeWord.classify(6))
        assertEquals(WakeWord.ErrorKind.BUSY, WakeWord.classify(8))
        assertEquals(WakeWord.ErrorKind.LANGUAGE, WakeWord.classify(13))
        assertEquals(WakeWord.ErrorKind.FATAL, WakeWord.classify(9))
        assertEquals(WakeWord.ErrorKind.RETRY, WakeWord.classify(2))
        assertEquals(WakeWord.ErrorKind.BUSY, WakeWord.classify(3))
    }

    @Test
    fun `restart guard stops a tight loop and recovers after the window`() {
        val g = RestartGuard(windowMs = 20_000, maxInWindow = 3)
        assertTrue(g.allow(0))
        assertTrue(g.allow(100))
        assertTrue(g.allow(200))
        assertFalse(g.allow(300))
        assertTrue(g.allow(25_000))
    }

    @Test
    fun `wake with command goes straight to the ai without a second recognition`() {
        val r = WakeCycle.reduce(State.Listening, Event.WakeWithCommand("przełącz na kalendarz"))
        assertEquals(State.Thinking, r.state)
        assertEquals(listOf(Effect.StopDetector, Effect.AskAi("przełącz na kalendarz")), r.effects)
        val alone = WakeCycle.reduce(State.Listening, Event.WakeWithCommand(""))
        assertEquals(State.Command, alone.state)
        assertTrue(alone.effects.contains(Effect.StartRecognition))
        // W trakcie obsługi polecenia kolejne „Jarvis, …” jest ignorowane.
        assertEquals(State.Speaking, WakeCycle.reduce(State.Speaking, Event.WakeWithCommand("x")).state)
    }
}
