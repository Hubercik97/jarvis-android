package pl.zamirski.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.zamirski.jarvis.core.AppMatcher.Match

class AppMatcherTest {

    // Typowe etykiety aplikacji na Galaxy S21 (przykładowa lista do testów).
    private val apps = listOf(
        AppEntry("Spotify", "com.spotify.music"),
        AppEntry("YouTube", "com.google.android.youtube"),
        AppEntry("YouTube Music", "com.google.android.apps.youtube.music"),
        AppEntry("Mapy", "com.google.android.apps.maps"),
        AppEntry("WhatsApp", "com.whatsapp"),
        AppEntry("Messenger", "com.facebook.orca"),
        AppEntry("Aparat", "com.sec.android.app.camera"),
        AppEntry("Ustawienia", "com.android.settings"),
        AppEntry("Galaxy Store", "com.sec.android.app.samsungapps"),
        AppEntry("Candy Crush Saga", "com.king.candycrushsaga"),
        AppEntry("Kalkulator", "com.sec.android.app.popupcalculator"),
        AppEntry("Zegar", "com.sec.android.app.clockpackage"),
        AppEntry("mObywatel", "pl.nask.mobywatel"),
    )

    private fun found(query: String): String? = (AppMatcher.match(query, apps) as? Match.Found)?.app?.packageName

    @Test
    fun `exact names and polish diacritics`() {
        assertEquals("com.spotify.music", found("Spotify"))
        assertEquals("com.spotify.music", found("spotify"))
        assertEquals("com.android.settings", found("ustawienia"))
        assertEquals("pl.nask.mobywatel", found("mObywatel"))
        assertEquals("com.sec.android.app.clockpackage", found("Zegar"))
    }

    @Test
    fun `filler words and spacing from speech are ignored`() {
        assertEquals("com.spotify.music", found("aplikację Spotify"))
        assertEquals("com.king.candycrushsaga", found("grę Candy Crush"))
        assertEquals("com.google.android.youtube", found("You Tube"))
        assertEquals("com.whatsapp", found("whats app"))
    }

    @Test
    fun `partial names pick the app that contains all words`() {
        assertEquals("com.king.candycrushsaga", found("Candy Crush"))
        assertEquals("com.sec.android.app.samsungapps", found("galaxy store"))
        assertEquals("com.google.android.apps.maps", found("Mapy Google")) // „Mapy” ⊂ zapytanie
    }

    @Test
    fun `small speech recognition typos still match`() {
        assertEquals("com.facebook.orca", found("Mesenger"))
        assertEquals("com.sec.android.app.popupcalculator", found("kalkulater"))
    }

    @Test
    fun `exact app wins over a longer one with the same prefix`() {
        assertEquals("com.google.android.youtube", found("YouTube"))
        assertEquals("com.google.android.apps.youtube.music", found("YouTube Music"))
    }

    @Test
    fun `unknown apps are not opened at random`() {
        val m = AppMatcher.match("Fortnite", apps)
        assertTrue(m is Match.NotFound)
        assertTrue(AppMatcher.match("", apps) is Match.NotFound)
        assertTrue(AppMatcher.match("aplikację", apps) is Match.NotFound)
        assertTrue(AppMatcher.match("Spotify", emptyList()) is Match.NotFound)
    }

    @Test
    fun `equally good matches are reported as ambiguous`() {
        val twins = listOf(AppEntry("Notatki", "a.notes"), AppEntry("Notatki", "b.notes"))
        val m = AppMatcher.match("notatki", twins)
        assertTrue(m is Match.Ambiguous)
        assertEquals(2, (m as Match.Ambiguous).candidates.size)
    }

    @Test
    fun `result text for ai never lists all installed apps`() {
        val text = AppMatcher.resultText("Fortnite", AppMatcher.match("Fortnite", apps))
        assertTrue(text.startsWith("Nie znaleziono aplikacji „Fortnite”"))
        assertTrue(apps.count { text.contains(it.label) } <= 3)
        assertEquals("Otwarto aplikację: Spotify", AppMatcher.resultText("spotify", AppMatcher.match("spotify", apps)))
    }
}
