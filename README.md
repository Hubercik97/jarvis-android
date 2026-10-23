# Jarvis Desktop + Cloud

Nowy interfejs: [Holographic HUD 3D 0.4 — renderer, audio, testy i ograniczenia](docs/HOLOGRAM.md).

**Główny kierunek: Windows + chmura.** Zobacz [instalację, funkcje, testy i ograniczenia wersji Desktop](docs/DESKTOP.md). Android pozostaje opcjonalnym klientem.

Poprzedni przyrost chmurowy: [audyt, OpenAI, D1, zadania, panel i ograniczenia](docs/CLOUD_AI.md).
Poniżej zachowano opis pierwotnej PWA; stan gałęzi Android i chmury opisuje powyższy dokument.

Prototyp PWA (etap 1). Działa w Google Chrome na Androidzie i można go dodać do ekranu głównego jak aplikację.

**Adres po publikacji:** https://hubercik97.github.io/jarvis-android/

## Co działa w etapie 1

| Funkcja | Stan |
|---|---|
| Rozpoznawanie polskiej mowy (pl-PL) | ✅ Web Speech API w Chrome |
| Odpowiedzi głosowe | ✅ syntezator mowy telefonu (polski głos systemu) |
| Lokalne polecenia | ✅ godzina, data, „co potrafisz”, „status”, „powtórz”, „powiedz …”, „stop” |
| Obsługa błędów mikrofonu | ✅ brak zgody, brak mikrofonu, mikrofon zajęty, brak internetu, brak HTTPS |
| Instalacja na ekranie głównym | ✅ manifest + service worker, działa też offline (interfejs) |
| Czuwanie na hasło „Jarvis” | ⚠️ eksperymentalne, tylko przy otwartej aplikacji |
| Połączenie z modelem AI | ❌ jeszcze nie — Jarvis mówi to wprost i nie udaje odpowiedzi |
| Pamięć rozmów | ❌ etap późniejszy (teraz rozmowa znika po zamknięciu) |

## Pliki

```
index.html             interfejs
styles.css             wygląd (futurystyczny HUD)
app.js                 mowa, głos, błędy, czuwanie, instalacja
manifest.webmanifest   konfiguracja PWA (nazwa, ikony, tryb pełnoekranowy)
sw.js                  service worker (offline, zawsze świeża wersja online)
icons/                 ikony 192/512, maskable, apple-touch
.nojekyll              GitHub Pages serwuje pliki bez przetwarzania Jekyll
```

Wszystkie ścieżki są względne, więc aplikacja działa pod adresem `/jarvis-android/` na GitHub Pages.

## Publikacja na GitHub Pages (jednorazowo)

1. Na GitHubie otwórz repozytorium **jarvis-android** → **Settings** → **Pages**.
2. **Source:** `Deploy from a branch`.
3. **Branch:** `main`, folder `/ (root)` → **Save**.
4. Po 1–2 minutach strona będzie pod adresem https://hubercik97.github.io/jarvis-android/
   (postęp widać w zakładce **Actions** → „pages build and deployment”).

Każdy kolejny push do `main` publikuje nową wersję automatycznie.

## Test na telefonie z Androidem

1. Otwórz **Google Chrome** (nie Samsung Internet ani Firefox — nie mają rozpoznawania mowy).
2. Wejdź na https://hubercik97.github.io/jarvis-android/
3. Dotknij dużego koła z mikrofonem → **Zezwól** na mikrofon.
4. Powiedz: „Która godzina?”. Jarvis powinien pokazać tekst i odpowiedzieć głosem.
5. Wypróbuj też: „Jaki dziś dzień?”, „Co potrafisz?”, „Status”, „Powiedz witaj w domu”.
6. Zapytaj o coś innego (np. „Jaka jest pogoda?”) — Jarvis uczciwie powie, że nie ma jeszcze połączenia z AI.
7. **Dodanie do ekranu głównego:** przycisk **Zainstaluj** u góry albo menu Chrome (⋮) → **Dodaj do ekranu głównego** / **Zainstaluj aplikację**.
8. Uruchom Jarvisa z ikony — otworzy się bez paska adresu.

### Gdy coś nie działa

- **Brak głosu po polsku** (znaczek „Głos: brak polskiego”): Ustawienia Androida → System → Języki → **Zamiana tekstu na mowę** → silnik Google → zainstaluj język **polski**. Upewnij się, że głośność multimediów nie jest wyciszona.
- **Mikrofon zablokowany:** ikona obok adresu w Chrome → Uprawnienia → Mikrofon → Zezwól. Dodatkowo: Ustawienia → Aplikacje → Chrome → Uprawnienia → Mikrofon.
- **„Brak połączenia z usługą mowy”:** rozpoznawanie mowy w Chrome na Androidzie zwykle korzysta z serwerów Google — potrzebny jest internet.
- **Stara wersja po aktualizacji:** zamknij i otwórz aplikację ponownie (online zawsze pobierana jest najnowsza wersja).

## Czuwanie na hasło „Jarvis” — co jest możliwe

- **Działa:** gdy aplikacja jest otwarta na ekranie, przełącznik „Czuwanie” uruchamia ciągłe rozpoznawanie mowy. Powiedz „Jarvis, która godzina?” albo samo „Jarvis”, a potem polecenie. Ekran nie gaśnie (Wake Lock).
- **Ograniczenia:** Chrome co kilka sekund restartuje nasłuch (na części telefonów słychać wtedy krótki dźwięk systemowy), zużywa to baterię i dane, a dźwięk trafia do usługi mowy Google.
- **Nie działa i nie da się tego zrobić w PWA:** nasłuch w tle, przy zablokowanym ekranie albo w innej aplikacji. Przeglądarka na to nie pozwala.
- **Prawdziwe wybudzanie w tle** wymaga natywnej aplikacji na Androida (usługa pierwszoplanowa z lokalnym detektorem słowa, np. Porcupine lub openWakeWord). To kandydat na późniejszy etap.

## Bezpieczeństwo

- W kodzie frontendu nie ma i nie będzie kluczy API.
- Model AI zostanie podłączony przez własny backend (np. funkcja serverless), który trzyma klucz po stronie serwera. W `app.js` jest na to miejsce: stała `AI_ENDPOINT` (obecnie `null`).

## Plan kolejnych etapów

1. ~~Prototyp głosowy PWA~~ (ten etap)
2. Backend + połączenie z modelem AI (klucz tylko na serwerze)
3. Pamięć rozmów
4. Narzędzia biznesowe (kalendarz, notatki, zadania itp.)
5. Opcjonalnie: natywna aplikacja Android dla wybudzania w tle
