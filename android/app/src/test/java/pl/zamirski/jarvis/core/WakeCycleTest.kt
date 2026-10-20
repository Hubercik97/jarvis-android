package pl.zamirski.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.zamirski.jarvis.core.WakeCycle.Effect
import pl.zamirski.jarvis.core.WakeCycle.Event
import pl.zamirski.jarvis.core.WakeCycle.State

class WakeCycleTest {

    private fun run(start: State, vararg events: Event): Pair<State, List<Effect>> {
        var s = start
        val all = mutableListOf<Effect>()
        for (e in events) {
            val r = WakeCycle.reduce(s, e)
            s = r.state
            all += r.effects
        }
        return s to all
    }

    @Test
    fun `full happy cycle returns to listening`() {
        val (s, fx) = run(
            State.Off,
            Event.Enable,
            Event.WakeDetected(0.9f),
            Event.CommandHeard("Która godzina?"),
            Event.ReplyReady("Jest 12:00."),
            Event.SpeakingDone,
        )
        assertEquals(State.Listening, s)
        assertEquals(
            listOf(
                Effect.StartDetector,
                Effect.StopDetector, Effect.StartRecognition,
                Effect.AskAi("Która godzina?"),
                Effect.Speak("Jest 12:00."),
                Effect.StartDetector,
            ),
            fx,
        )
    }

    @Test
    fun `detector is always stopped before recognition starts`() {
        val r = WakeCycle.reduce(State.Listening, Event.WakeDetected(0.7f))
        val stop = r.effects.indexOf(Effect.StopDetector)
        val start = r.effects.indexOf(Effect.StartRecognition)
        assertTrue(stop >= 0 && start > stop)
    }

    @Test
    fun `second wake during a command never starts a second recognition`() {
        for (busy in listOf(State.Command, State.Thinking, State.Speaking)) {
            val r = WakeCycle.reduce(busy, Event.WakeDetected(0.99f))
            assertEquals(busy, r.state)
            assertFalse(r.effects.contains(Effect.StartRecognition))
        }
    }

    @Test
    fun `detector and recognition are never active together across any event sequence`() {
        // Prosty model zasobu: liczymy aktywne nagrywania na podstawie efektów.
        val events = listOf(
            Event.Enable, Event.WakeDetected(0.9f), Event.WakeDetected(0.9f), Event.CommandFailed(true),
            Event.WakeDetected(0.9f), Event.CommandHeard("x"), Event.WakeDetected(0.9f), Event.ReplyFailed("błąd"),
            Event.SpeakingDone, Event.MicBusy, Event.WakeDetected(0.9f), Event.RetryTimer, Event.AppForeground,
            Event.WakeDetected(0.9f), Event.AppBackground, Event.DetectorError("x"), Event.RetryTimer,
            Event.WakeDetected(0.8f), Event.AppForeground, Event.AppBackground, Event.Disable,
        )
        var s: State = State.Off
        var detector = false
        var recognition = false
        for (e in events) {
            // Wynik lub błąd polecenia oznacza, że systemowe rozpoznawanie już się zakończyło.
            if (e is Event.CommandHeard || e is Event.CommandFailed) recognition = false
            val r = WakeCycle.reduce(s, e)
            for (fx in r.effects) {
                when (fx) {
                    Effect.StartDetector -> {
                        assertFalse("detektor przy aktywnym rozpoznawaniu (po $e)", recognition)
                        detector = true
                    }
                    Effect.StopDetector -> detector = false
                    Effect.StartRecognition -> {
                        assertFalse("rozpoznawanie przy aktywnym detektorze (po $e)", detector)
                        assertFalse("drugie rozpoznawanie (po $e)", recognition)
                        recognition = true
                    }
                    Effect.CancelRecognition -> recognition = false
                    else -> {}
                }
            }
            s = r.state
        }
        assertEquals(State.Off, s)
        assertFalse(detector)
    }

    @Test
    fun `empty or failed command speaks a short message and resumes listening`() {
        val heardNothing = WakeCycle.reduce(State.Command, Event.CommandHeard("  "))
        assertEquals(State.Listening, heardNothing.state)
        assertEquals(listOf(Effect.Speak(WakeCycle.NOT_HEARD), Effect.StartDetector), heardNothing.effects)

        val silence = WakeCycle.reduce(State.Command, Event.CommandFailed(silence = true))
        assertEquals(listOf(Effect.StartDetector), silence.effects)
    }

    @Test
    fun `ai failure is spoken, not swallowed`() {
        val r = WakeCycle.reduce(State.Thinking, Event.ReplyFailed("Brak internetu."))
        assertEquals(State.Speaking, r.state)
        assertEquals(listOf(Effect.Speak("Brak internetu.")), r.effects)
    }

    @Test
    fun `detector errors back off and finally switch off with a notification`() {
        var r = WakeCycle.reduce(State.Listening, Event.DetectorError("audio"))
        assertEquals(State.Failed(1, "audio"), r.state)
        assertTrue(r.effects.contains(Effect.ScheduleRetry(2_000)))
        var s = r.state
        for (attempt in 2..WakeCycle.MAX_ATTEMPTS) {
            r = WakeCycle.reduce(s, Event.DetectorError("audio"))
            assertEquals(State.Failed(attempt, "audio"), r.state)
            s = r.state
        }
        r = WakeCycle.reduce(s, Event.DetectorError("audio"))
        assertEquals(State.Off, r.state)
        assertTrue(r.effects.any { it is Effect.Notify })
        assertTrue(r.effects.contains(Effect.StopService))
    }

    @Test
    fun `retry delays grow and are capped`() {
        assertEquals(2_000L, WakeCycle.retryDelay(1))
        assertEquals(4_000L, WakeCycle.retryDelay(2))
        assertEquals(8_000L, WakeCycle.retryDelay(3))
        assertEquals(60_000L, WakeCycle.retryDelay(10))
    }

    @Test
    fun `mic busy pauses and retries, app in foreground pauses until background`() {
        val busy = WakeCycle.reduce(State.Listening, Event.MicBusy)
        assertEquals(State.Paused(WakeCycle.PauseReason.MIC_BUSY), busy.state)
        assertEquals(State.Listening, WakeCycle.reduce(busy.state, Event.RetryTimer).state)

        val fg = WakeCycle.reduce(State.Listening, Event.AppForeground)
        assertEquals(State.Paused(WakeCycle.PauseReason.APP_IN_FOREGROUND), fg.state)
        assertEquals(fg.state, WakeCycle.reduce(fg.state, Event.RetryTimer).state)
        val back = WakeCycle.reduce(fg.state, Event.AppBackground)
        assertEquals(State.Listening, back.state)
        assertEquals(listOf(Effect.StartDetector), back.effects)
    }

    @Test
    fun `disable from any state stops everything`() {
        val states = listOf(
            State.Off, State.Listening, State.Command, State.Thinking, State.Speaking,
            State.Paused(WakeCycle.PauseReason.MIC_BUSY), State.Failed(2, "x"),
        )
        for (s in states) {
            val r = WakeCycle.reduce(s, Event.Disable)
            assertEquals(State.Off, r.state)
            assertTrue(r.effects.containsAll(listOf(Effect.StopDetector, Effect.CancelRecognition, Effect.StopService)))
        }
    }

    @Test
    fun `notification always tells when the microphone is active`() {
        assertTrue(WakeCycle.notificationText(State.Listening).contains("Mikrofon aktywny"))
        assertTrue(WakeCycle.notificationText(State.Command).contains("Mikrofon aktywny"))
        assertTrue(WakeCycle.micInUse(State.Listening))
        assertFalse(WakeCycle.micInUse(State.Paused(WakeCycle.PauseReason.APP_IN_FOREGROUND)))
        assertFalse(WakeCycle.micInUse(State.Off))
    }
}
