# Jarvis — aplikacja Android (APK)

Natywna aplikacja Android (Kotlin) + backend w chmurze (Cloudflare Worker). Wszystko budowane w GitHub Actions — bez komputera.

## Co jest w środku

| Folder | Zawartość |
|---|---|
| `android/` | Aplikacja Android (Kotlin, minSdk 26, targetSdk 34) |
| `backend/` | Cloudflare Worker pośredniczący do modelu AI (klucz API tylko tutaj) |
| `.github/workflows/android-apk.yml` | Testy + budowanie APK + wydanie z linkiem do pobrania |
| `.github/workflows/backend-deploy.yml` | Ręczne wdrożenie backendu na Cloudflare |

Istniejąca wersja PWA (jeśli jest w repozytorium) zostaje nietknięta — aplikacja Android jest w osobnym folderze.

## Dlaczego natywnie, a nie PWA w WebView

W WebView na Androidzie nie działa Web Speech API (rozpoznawanie mowy). Natywna aplikacja używa systemowego
`SpeechRecognizer` (pl-PL) i `TextToSpeech` (pl-PL) — bezpośrednio mikrofonu i głośnika telefonu.

## Funkcje (stan faktyczny)

Zaimplementowane w kodzie:
- przycisk mikrofonu → rozpoznanie polskiej mowy → odpowiedź AI → odczyt głosem po polsku,
- wpisywanie poleceń tekstem,
- obsługa: brak zgody na mikrofon (w tym trwała odmowa → przejście do ustawień), brak internetu, brak usługi rozpoznawania mowy, brak polskiego głosu TTS, błędy serwera, timeout,
- ekran **Status**: internet, mikrofon, rozpoznawanie mowy, głos polski, adres backendu, test połączenia z AI,
- rejestr modułów (`features/`) — pamięć, kamera, okulary, sterowanie komputerem, słowo wybudzające oznaczone jako **planowane**.

Czego NIE ma: nasłuchiwania w tle / przy wygaszonym ekranie, słowa „Jarvis”, pamięci między sesjami.
Aplikacja nie zapisuje nagrań głosu.

## Konfiguracja (z telefonu)

### 1. Backend AI (jednorazowo)
1. Załóż darmowe konto Cloudflare, utwórz API Token (szablon „Edit Cloudflare Workers”), skopiuj Account ID.
2. Klucz API modelu: console.anthropic.com → API Keys.
3. W GitHub: repozytorium → Settings → Secrets and variables → Actions → **New repository secret**:
   - `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`
   - `ANTHROPIC_API_KEY`
   - `JARVIS_APP_TOKEN` — dowolny długi losowy ciąg (to samo wpiszesz w aplikacji)
4. Actions → **Jarvis — wdrożenie backendu** → Run workflow. W logu kroku „Wdróż Worker” będzie adres `https://jarvis-backend.<konto>.workers.dev`.
5. (Opcjonalnie) Settings → Variables → `JARVIS_BACKEND_URL` = ten adres — kolejne APK będą go miały domyślnie.

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
- `JARVIS_APP_TOKEN` chroni backend przed obcymi (bez niego każdy znający adres mógłby zużywać Twój klucz).
- Do serwera wysyłany jest tylko tekst bieżącej rozmowy (max 20 ostatnich wiadomości). Nic nie jest zapisywane na serwerze.
