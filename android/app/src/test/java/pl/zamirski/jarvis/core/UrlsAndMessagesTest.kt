package pl.zamirski.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlsAndMessagesTest {

    @Test
    fun `https url is normalized`() {
        assertEquals("https://jarvis.example.workers.dev", Urls.normalizeBackend("  https://jarvis.example.workers.dev/ "))
    }

    @Test
    fun `insecure or empty urls are rejected`() {
        assertNull(Urls.normalizeBackend(null))
        assertNull(Urls.normalizeBackend(""))
        assertNull(Urls.normalizeBackend("http://jarvis.example.com"))
        assertNull(Urls.normalizeBackend("https://"))
        assertNull(Urls.normalizeBackend("https://bad host.com"))
    }

    @Test
    fun `every failure has a polish message`() {
        FailureReason.values().forEach { assertTrue(Messages.forFailure(it).isNotBlank()) }
        assertTrue(Messages.forFailure(FailureReason.SERVER, "HTTP 500").contains("HTTP 500"))
    }

    @Test
    fun `speech errors are mapped`() {
        (1..13).forEach { assertFalse(Messages.forSpeechError(it).startsWith("Nieznany")) }
        assertTrue(Messages.forSpeechError(99).contains("99"))
        assertTrue(Messages.forSpeechError(9).contains("mikrofon"))
        assertTrue(Messages.isSilence(6))
        assertTrue(Messages.isSilence(7))
        assertFalse(Messages.isSilence(9))
    }
}
