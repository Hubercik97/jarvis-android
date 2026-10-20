# Jarvis — konfiguracja backendu AI z telefonu

Cel: Jarvis na telefonie → backend testowy w Cloudflare (`jarvis-backend-test`) → model Claude → odpowiedź czytana polskim głosem.

Zasady bezpieczeństwa:
- Klucz API modelu i token aplikacji są **tylko** w sekretach GitHuba i Cloudflare — nigdy w aplikacji, w repozytorium ani w logach.
- Backend odrzuca każdą rozmowę, jeśli nie ma ustawionego tokenu aplikacji (zasada „fail-closed”).
- Wdrażane jest wyłącznie środowisko **testowe**. Produkcja nie jest wdrażana bez osobnej zgody.

Wszystkie kroki da się zrobić w Chrome na Galaxy S21. Jeśli strona GitHuba lub Cloudflare nie pokazuje jakiejś opcji, włącz w menu Chrome (⋮) **„Wersja na komputer”**.

---

## Krok 1 — klucz API modelu (Anthropic)

1. Otwórz **platform.claude.com** i zaloguj się (lub załóż konto).
2. Doładuj środki (Billing) — API jest płatne za użycie; model `claude-haiku-5-5` jest najtańszy.
3. Wejdź w **API Keys → Create Key**, nazwij go np. `jarvis-backend-test`.
4. Skopiuj klucz (widoczny tylko raz) — wkleisz go w kroku 4 jako `ANTHROPIC_API_KEY`.

## Krok 2 — konto Cloudflare

1. Otwórz **dash.cloudflare.com** i załóż darmowe konto.
2. Otwórz raz sekcję **Workers & Pages** (tworzy to Twoją subdomenę `*.workers.dev`).
3. Skopiuj **Account ID** (widoczny w Workers & Pages po prawej stronie albo na stronie konta) → `CLOUDFLARE_ACCOUNT_ID`.
4. Ikona profilu → **My Profile → API Tokens → Create Token** → szablon **„Edit Cloudflare Workers”** → wybierz swoje konto → **Continue to summary → Create Token**.
5. Skopiuj token (widoczny tylko raz) → `CLOUDFLARE_API_TOKEN`.

## Krok 3 — token aplikacji

Wygeneruj długi losowy ciąg (min. 32 znaki, **tylko litery i cyfry**), np. generatorem haseł w Menedżerze haseł Google lub Samsung Pass. Zapisz go w menedżerze haseł — wpiszesz go też w aplikacji. To `JARVIS_APP_TOKEN`.

## Krok 4 — sekrety w GitHubie

1. Otwórz: `github.com/Hubercik97/jarvis-android` → **Settings → Secrets and variables → Actions**.
2. Zakładka **Secrets → New repository secret**, dodaj 4 sekrety (nazwy dokładnie takie):

| Nazwa | Wartość |
|---|---|
| `ANTHROPIC_API_KEY` | klucz z kroku 1 |
| `CLOUDFLARE_ACCOUNT_ID` | Account ID z kroku 2 |
| `CLOUDFLARE_API_TOKEN` | token z kroku 2 |
| `JARVIS_APP_TOKEN` | token z kroku 3 |

## Krok 5 — wdrożenie testowe

Workflow **„Jarvis — wdrożenie backendu (test)”** jest na gałęzi `feature/jarvis-cloud-assistant`, a GitHub pokazuje przycisk „Run workflow” tylko dla workflowów z gałęzi `main`. Dlatego wdrożenie uruchamia Claude przez API GitHuba — po Twojej wiadomości, że sekrety są gotowe.

Workflow:
1. uruchamia testy backendu,
2. wdraża Worker `jarvis-backend-test`,
3. ustawia w nim sekrety (bez wypisywania wartości),
4. robi test dymny: `/health`, prawdziwe pytanie do AI przez `/chat`, sprawdza, że żądanie bez tokenu jest odrzucane (401),
5. wypisuje w adnotacjach adres backendu i odpowiedź AI.

## Krok 6 — aplikacja

1. Jarvis → **Ustawienia → Backend AI**.
2. Adres: adres z kroku 5 (`https://jarvis-backend-test.<twoja-subdomena>.workers.dev`).
3. Token aplikacji: ten sam co `JARVIS_APP_TOKEN`.
4. **Zapisz i sprawdź** → oczekiwany status: **„✅ Połączenie z AI: działa (claude-haiku-5-5)”**.

## Krok 7 — test końcowy

Naciśnij mikrofon i zapytaj np. „Jaka jest stolica Francji?”. Jarvis powinien odpowiedzieć tekstem i polskim głosem.
Jeśli brak głosu: Ustawienia telefonu → Zarządzanie ogólne → Zamiana tekstu na mowę → zainstaluj język polski.

---

## Komunikaty w aplikacji

| Komunikat | Znaczenie |
|---|---|
| Brak adresu backendu AI | Nie wpisano adresu w Ustawieniach |
| Wpisz token aplikacji w Ustawieniach | Backend działa, ale aplikacja nie ma tokenu |
| Błędny token aplikacji | Token w aplikacji różni się od `JARVIS_APP_TOKEN` |
| Serwer nie ma ustawionego tokenu aplikacji | W Workerze brakuje sekretu `APP_TOKEN` — wdrożenie niepełne |
| Serwer AI działa, ale nie ma klucza API modelu | W Workerze brakuje `ANTHROPIC_API_KEY` |
| Model AI zwrócił błąd 401 | Nieprawidłowy klucz API |
| Model AI zwrócił błąd 429 | Limit zapytań lub brak środków na koncie API |

## Opcjonalnie: adres wbudowany w APK

Repozytorium → Settings → Secrets and variables → Actions → **Variables** → `JARVIS_BACKEND_URL` = adres backendu (tylko `https://…`). Kolejne APK będą miały go domyślnie. Token aplikacji **nigdy** nie jest wbudowywany w APK — wpisujesz go ręcznie.
