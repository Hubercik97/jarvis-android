package pl.zamirski.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationFlowTest {

    @Test
    fun `end phrases are recognized in natural polish`() {
        listOf(
            "To wszystko",
            "to wszystko.",
            "Dobra, to wszystko, dzięki!",
            "Jarvis, koniec rozmowy",
            "Dziękuję",
            "Dzięki wielkie",
            "OK, wystarczy",
            "Zakończ rozmowę",
            "Do usłyszenia, Jarvis",
            "stop",
            "No dobra, na razie to wszystko",
        ).forEach { assertTrue("powinno zakończyć: $it", ConversationFlow.isEndPhrase(it)) }
    }

    @Test
    fun `normal questions do not end the conversation`() {
        listOf(
            "Jaka jest pogoda?",
            "Dziękuję, a jaka jest jutro pogoda?",
            "Stop, powiedz mi jeszcze która godzina",
            "Koniec świata nastąpi kiedy?",
            "To wszystko co wiesz o Krakowie?",
            "ok super",
            "",
            "   ",
            "Dobra, to wszystko jest bardzo ciekawe ale opowiedz mi więcej o tym projekcie",
        ).forEach { assertFalse("nie powinno zakończyć: $it", ConversationFlow.isEndPhrase(it)) }
    }

    @Test
    fun `normalization removes polish diacritics and punctuation`() {
        assertEquals("zazolc gesla jazn", ConversationFlow.normalize("Zażółć, gęślą jaźń!"))
        assertEquals("lodz", ConversationFlow.normalize("ŁÓDŹ"))
    }

    @Test
    fun `listening resumes only when session active, reply spoken and app visible`() {
        assertTrue(ConversationFlow.shouldListenAgain(sessionActive = true, replySpoken = true, appInForeground = true))
        assertFalse(ConversationFlow.shouldListenAgain(sessionActive = false, replySpoken = true, appInForeground = true))
        assertFalse(ConversationFlow.shouldListenAgain(sessionActive = true, replySpoken = false, appInForeground = true))
        assertFalse(ConversationFlow.shouldListenAgain(sessionActive = true, replySpoken = true, appInForeground = false))
    }
}
