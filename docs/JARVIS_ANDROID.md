# Jarvis — aplikacja Android (APK)

Natywna aplikacja Android (Kotlin + Jetpack Compose) + backend w chmurze (Cloudflare Worker). Wszystko budowane w GitHub Actions — bez komputera.

## Co jest w środku

| Folder | Zawartość |
|---|---|
| `android/` | Aplikacja Android (Kotlin, minSdk 26, targetSdk 34) |
| `backend/` | Cloudflare Worker pośredniczący do modelu AI (klucz API tylko tutaj) |
| `.github/workflows/android-apk.yml` | Testy + budowanie APK + wydanie z linkiem do pobrania |
| `.github/workflows/backend-deploy.yml` | Ręczne wdrożenie backendu na Cloudflare |

Wersja PWA z etapu 1 (pliki w katalogu głównym: `index.html`, `app.js`, …) zostaje nietknięta — aplikacja Android jest w osobnym folderze.

## Dlaczego natywnie, a nie PWA w WebView

W WebView na Androidzie nie działa Web Speech API (rozpoznawanie mowy). Natywna aplikacja używa systemowego
`SpeechRecognizer` (pl-PL) i `TextToSpeech` (pl-PL) — bezpośrednio mikrofonu i głośnika telefonu.

## Funkcje (stan faktyczny)

Ekran główny (wzorowany na projekcie wizualnym): nagłówek JARVIS z ustawieniami, data i godzina,
karta pogody, animowany rdzeń AI (metaliczna kula, obracające się pierścienie, pomarańczowe akcenty,
świetlna platforma), powitanie zależne od pory dnia, przyciski Głos / Kamera / Pliki / Internet,
dolny pasek z dużym mikrofonem.

| Funkcja | Stan |
|---|---|
| Mikrofon → polska mowa → AI → odpowiedź głosem | ✅ (wymaga skonfigurowanego backendu) |
| Pisanie poleceń tekstem | ✅ |
| Otwieranie aplikacji i gier („otwórz Spotify”, „uruchom Candy Crush”) | ⚠️ zaimplementowane, **do sprawdzenia na S21** — dopasowanie nazwy na telefonie, lista aplikacji nie trafia do chmury |
| Alarm, minutnik, przypomnienie, kalendarz, latarka, ekrany ustawień poleceniem | ⚠️ zaimplementowane, **do sprawdzenia na S21** |
| Historia rozmów | ✅ zapisana na telefonie (maks. 50 rozmów, bez zdjęć); bieżąca rozmowa wraca po ponownym uruchomieniu |
| Tryb rozmowy (bez dotykania ekranu) | ✅ po odpowiedzi Jarvis sam znowu słucha; koniec: „to wszystko” / „dziękuję”. Tylko przy otwartej aplikacji — ekran nie gaśnie w trakcie |
| Kamera → zdjęcie → opis przez AI | ✅ (aparat systemowy, zdjęcie usuwane po wysłaniu) |
| Pliki (tekst, JSON, obrazy) → analiza AI | ✅ |
| Internet → wyszukiwanie w przeglądarce | ✅ (otwiera wyszukiwarkę; AI samo nie przegląda sieci) |
| Pogoda | ✅ prawdziwe dane Open-Meteo (miasto z Ustawień albo przybliżona lokalizacja); bez danych → „Brak danych” |
| Kalendarz | ✅ odczyt wydarzeń z kalendarza telefonu |
| Przypomnienia | ✅ powiadomienia (mogą przyjść z kilkuminutowym opóźnieniem) |
| Notatki, Zadania | ✅ zapisywane lokalnie, z dyktowaniem |
| Inteligentny dom | ❌ niepodłączone — ekran mówi to wprost, bez fałszywych przełączników |
| Komputer / panel Windows | ❌ planowane — architektura: `sync/SyncRepository` + backend |
| Pamięć AI między rozmowami, okulary | ❌ planowane |
| Słuchawki Bluetooth (mikrofon na czas słuchania, głos Jarvisa w słuchawkach) | ⚠️ zaimplementowane, **niesprawdzone na S21** — zob. [WAKE_WORD.md](WAKE_WORD.md) |
| Słowo „Jarvis” w tle (inne aplikacje) | ⚠️ zaimplementowane przez rozpoznawanie mowy, **do sprawdzenia na S21** — zob. [WAKE_WORD.md](WAKE_WORD.md) §7 |

Aplikacja nie zapisuje nagrań głosu.

## Konfiguracja (z telefonu)

### 1. Backend AI (jednorazowo)
Pełna instrukcja krok po kroku z telefonu: **[docs/BACKEND_SETUP.md](BACKEND_SETUP.md)**
(klucz API, konto Cloudflare, 4 sekrety GitHub, wdrożenie testowe `jarvis-backend-test`, konfiguracja aplikacji).

### Podgląd ekranów
Każdy build uruchamia też zadanie **Podgląd ekranów** (Paparazzi, bez emulatora). Zrzuty PNG są w artefakcie
`podglad-ekranow-N`. Lokalnie: `gradle -Ppreview :app:recordPaparazziDebug` w folderze `android/`.
Zadanie jest niezależne — jego błąd nie blokuje testów ani APK.

### 2. Budowanie APK
Każdy push zmieniający `android/` lub `backend/` uruchamia **Jarvis — budowanie APK**.
Ręcznie: Actions → Jarvis — budowanie APK → Run workflow (zaznaczone „Opublikuj APK…”).
Po sukcesie powstaje wydanie testowe w **Releases** z plikiem `jarvis-N.apk`.

## Instalacja na Samsung Galaxy S21
1. Na telefonie otwórz w Chrome: `github.com/<konto>/jarvis-android/releases` (zalogowany).
2. Najnowsze wydanie → Assets → dotknij `jarvis-N.apk` → Pobierz.
3. Po pobraniu dotknij pliku. Przy pierwszym razie Android zapyta o zgodę: **Ustawienia → zezwól na instalację z tego źródła (Chrome)** → wróć → **Zainstaluj**.
4. Jeśli pojawi się Google Play Protect („Nieznana aplikacja”) → **Więcej szczegółów → Zainstaluj mimo to** (aplikacja nie jest w Sklepie Play).
5. Otwórz Jarvis → **Status** → wpisz adres backendu i token → **Zapisz i sprawdź**. Powinno pokazać „✅ Połączenie z AI: działa”.
6. Naciśnij mikrofon → zezwól na mikrofon („Podczas używania aplikacji”) → mów.

Jeśli brak polskiego głosu: Ustawienia → Zarządzanie ogólne → Zamiana tekstu na mowę → Preferowany mechanizm (Google lub Samsung) → zainstaluj język polski.
Jeśli rozpoznawanie nie działa: upewnij się, że aplikacja Google jest włączona i ma pakiet mowy polskiej.

Alternatywa bez Releases: Actions → wybrany build → Artifacts → `jarvis-apk-N` (ZIP) → rozpakuj w „Moje pliki” → dotknij APK.

## Aktualizacje
APK jest podpisane kluczem debug przechowywanym w cache GitHub Actions — nowe buildy instalują się jako aktualizacja.
Jeśli cache wygaśnie (7 dni bez buildów), Android zgłosi konflikt podpisu: odinstaluj starą wersję i zainstaluj nową.

## Bezpieczeństwo
- Klucz API modelu jest wyłącznie w sekretach GitHub/Cloudflare, nigdy w APK.
- `JARVIS_APP_TOKEN` chroni backend przed obcymi. Bez ustawionego tokenu backend odrzuca wszystkie rozmowy (fail-closed).
- Do serwera wysyłany jest tylko tekst bieżącej rozmowy (max 20 ostatnich wiadomości). Nic nie jest zapisywane na serwerze.

## Narzędzia (działania na telefonie)

AI nie wykonuje niczego samo. Backend zgłasza modelowi tylko narzędzia, które aplikacja wymieni w polu `tools`,
model **proponuje** wywołanie (`actions`), a aplikacja:
1. sprawdza je w `core/ToolRegistry` (nazwa, parametry, typy, długości) — nieznane narzędzie = odrzucone,
2. dla działań wrażliwych (`ToolRisk.CONFIRM`: wiadomości, połączenia, usuwanie) pokazuje okno „Potwierdź działanie”,
3. wykonuje je (`tools/LocalToolExecutor`) i zapisuje w rozmowie „▶ … — wykonano / nie udało się”,
4. odsyła wynik (`pending`), a AI mówi jednym zdaniem, co się stało. Maks. 3 rundy na polecenie.

| Narzędzie | Ryzyko | Uprawnienia | Uwagi |
|---|---|---|---|
| `open_app` | bez potwierdzenia | brak (deklaracja `<queries>` dla aplikacji z ikoną) | Do AI wraca tylko wynik i najwyżej 3 podobne nazwy, nigdy lista aplikacji |
| `set_alarm`, `set_timer` | bez potwierdzenia | `SET_ALARM` (zwykłe) | Przekazywane do aplikacji Zegar (Samsung Clock) |
| `create_reminder` | bez potwierdzenia | powiadomienia | Istniejące przypomnienia Jarvisa; mogą przyjść z kilkuminutowym opóźnieniem |
| `add_calendar_event` | potwierdzasz w kalendarzu | brak | Otwiera systemowy ekran z wypełnionym wydarzeniem — zapisujesz sam |
| `get_calendar_events` | bez potwierdzenia | `READ_CALENDAR` (zgoda w ekranie Kalendarz) | Tytuły i godziny wydarzeń trafiają do AI tylko na Twoje polecenie |
| `flashlight` | bez potwierdzenia | brak | |
| `open_settings` | bez potwierdzenia | brak | Tylko otwiera ekran — Android nie pozwala aplikacjom przełączać Wi-Fi/Bluetooth |

Aplikacja wysyła z każdym poleceniem czas lokalny i strefę telefonu (`client.local_time`, `client.tz`), bo model nie zna aktualnej daty.
