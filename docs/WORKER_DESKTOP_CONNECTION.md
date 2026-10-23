# Jarvis Desktop 0.5.2 TEST — zgodność Workera i lokalny detektor

## Wynik audytu adresu właściciela

Adres w aplikacji: `https://jarvis-android.lunvra-golf.workers.dev` — bez `/chat`, bez końcowych ścieżek ani parametrów.

23.10.2026 runner GitHub sprawdził wyłącznie metodą GET: `/health`, `/conversations`, `/memory`, `/tasks`, `/projects`, `/stt`, `/tts`, `/chat`. Wszystkie zwróciły **HTTP 404**, bez obiektu JSON API Jarvisa. To nie jest potwierdzenie błędnego tokenu: pod podanym adresem nie udostępniono oczekiwanych ścieżek. Możliwe przyczyny to niewłaściwy Worker/adres, brak wdrożonego backendu albo brak kierowania żądań do kodu backendu.

Źródło wyniku: workflow Desktop `40003373454`, zadanie `cloud-audit`. Zielony status tego zadania oznacza wykonanie audytu, nie udane połączenie. Raport nie zawiera treści odpowiedzi, danych prywatnych ani nagłówków. Lokalny proxy blokuje tę domenę (403 tunelu CONNECT), dlatego wynik pochodzi z runnera. Audyt nie wywołuje modeli, STT, TTS ani operacji zapisu. Sprawdzenie metadanych Cloudflare odczytuje wyłącznie obecność znanych nazw sekretów i wiązań; nie odczytuje ich wartości.

## Kontrakt i autoryzacja

Desktop i Android używają tego samego nagłówka **`x-jarvis-token`**, którego wartość musi odpowiadać sekretowi **`APP_TOKEN` tego konkretnego Workera**. `JARVIS_APP_TOKEN` to nazwa sekretu w GitHub Actions: nie jest automatycznie sekretem Cloudflare. Klucz OpenAI pozostaje po stronie serwera i nie jest tokenem aplikacji.

Minimalne `GET /health` to JSON `{ "auth": "ok", "configured": true }`. Bez poprawnego tokenu `auth` wynosi `invalid`; przy braku serwerowego sekretu wynosi `missing`. Bieżący backend dodatkowo zwraca `api_version: 1` oraz rzeczywiste możliwości wynikające z obecności wiązań. Starszy poprawny kontrakt Androida nadal jest akceptowany. Pole `configured` oznacza obecność konfiguracji klucza, nie udane płatne wywołanie dostawcy AI.

| Funkcja | Endpoint i format |
|---|---|
| Rozmowa Android / Windows | `POST /chat {messages:[{role,content}]}` → `{reply}` |
| Historia D1 | `GET/POST /conversations`, `GET/POST /conversations/:id/messages` |
| Pamięć faktów KV | `GET/POST/DELETE /memory`, `DELETE /memory/:id` |
| Zadania / projekty | `/tasks`, `/projects` i istniejące podścieżki |
| Polski STT | `POST /stt {audio,mime:"audio/webm"}` → `{text}` |
| Istniejący głos | `POST /tts {text}` → `{audio,format,...}` |
| Pogoda / GitHub | `GET /weather?latitude=...&longitude=...`, `/developer/project?repo=...` |

Zapis rozmowy Desktop wymaga wiązania **DB** i migracji D1; pamięć faktów wymaga **MEMORY**. Bez DB nie wolno oznaczać całej aplikacji jako gotowej do rozmów z historią. Sam brak Queue nie blokuje rozmowy; trwałe zadania w tle nadal wymagają dotychczasowej konfiguracji Queue i DB.

## Przygotowanie naprawy po stronie Workera — bez wdrożenia

Kod repozytorium już zawiera kompatybilne endpointy. Nie należy zastępować go prostą odpowiedzią `{ok:true}` ani fikcyjnym serwerem czatu. Właściwy punkt wejścia to **`backend/entry.js`**, importujący backend, a nie główny plik strony WWW. `assets.run_worker_first` musi obejmować wszystkie ścieżki API, aby istniejący plik SPA nie przejmował `/health`.

1. W Cloudflare sprawdź dokładną nazwę `jarvis-android` i jego aktywny adres. Zachowaj bieżący kod/wersję i listę wszystkich wiązań.
2. Porównaj konfigurację z `backend/wrangler.owner.toml.example`. Przykład zachowuje zmienne ustawiane w panelu (`keep_vars`) i nie zawiera sekretów. Skopiuj go do ignorowanego `wrangler.owner.toml`, zastąp identyfikator istniejącej D1 i dopisz **wszystkie dotychczasowe wiązania**, w tym MEMORY/Queue, jeżeli istnieją. Nie twórz nowej płatnej usługi w ramach tej naprawy.
3. Nie zmieniaj działających `APP_TOKEN`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY` ani ustawień głosu. Gdy `APP_TOKEN` istnieje, użyj tej samej wartości w Desktop. Aplikacja wymaga 16–500 znaków ASCII bez spacji. Nie przesyłaj wartości w czacie, poleceniu z argumentem ani logu. Brakujący sekret trzeba ustawić dopiero po stwierdzeniu jego braku.
4. Przed wdrożeniem przeprowadź `npm test`, `npm run build` i `npx wrangler deploy --config wrangler.owner.toml --dry-run --outdir .wrangler/owner-review`. Dry-run nie uruchamia zdalnej usługi. Brak prawdziwego DB ID w przykładzie jest celowy.
5. Migracje zdalnej bazy i wdrożenie są osobnym etapem wymagającym zgody właściciela. Nie uruchamiano ich w tym zadaniu. Dotychczasowy workflow `backend-deploy.yml` wdraża innego Workera (`jarvis-backend-test`), więc nie naprawi automatycznie adresu `jarvis-android`.
6. Po zatwierdzonym wdrożeniu najpierw sprawdź `/health` i odmowę dostępu bez tokenu. Następnie sprawdź historię i zwykłą rozmowę. Płatny test modelu/głosu uruchamiaj świadomie, z budżetem do 50 zł/miesiąc; limit liczby wywołań nie jest limitem kwoty.

## Poprawki Desktop / wake-word

- Osobne błędy dla HTML zamiast API, HTTP 404, błędnego tokenu, braku APP_TOKEN, błędnego JSON, błędu HTTP i brakującej D1. Diagnostyka pokazuje kod HTTP, bez treści odpowiedzi.
- Backend zgłasza możliwości bez zmiany istniejących formatów Androida. Nieprawidłowe metody na zarezerwowanych trasach API zwracają JSON, nie stronę SPA; autoryzacja pozostaje wymagana.
- ONNX nadal działa lokalnie w osobnym wątku, po jednym wątku obliczeń na sesję. Trzy grafy rozgrzewają się na danych syntetycznych przed otwarciem mikrofonu. Nie zapisuje się ani nie wysyła dźwięku hasła.
- Start ma ograniczenie 45 s, pojedyncza odpowiedź silnika 8 s zamiast 3 s. Przed uznaniem awarii aplikacja daje zaległej odpowiedzi IPC dodatkową turę obsługi. Nie usuwa to zabezpieczenia przed zawieszeniem ani wykrywania trwałego przeciążenia według budżetu 80 ms.
- Raport zawiera `workerStage`, `lastFailure` i ograniczoną listę zdarzeń; kod błędu ma zamknięty format. Treść natywnego błędu, nagłówki i tokeny nie trafiają do raportu.
- Nie ma automatycznej pętli restartowania mikrofonu po utracie uprawnień. Wyłączenie nasłuchu i rozmowa przyciskiem pozostają dostępne. Błędy chmury i lokalnego modelu są niezależne.

Nie ma zdalnego dostępu do fizycznego laptopa właściciela ani jego logów. Nie potwierdzono przyczyny konkretnego zawieszenia ONNX na N4000. Po instalacji poprawki wykonaj lokalny test mikrofonu, próbę przyciskiem oraz próbę hasła w otwartym i zminimalizowanym oknie; jeżeli błąd pozostaje, raport bez sekretów wskaże etap awarii. Nie deklarujemy pomiarów CPU/RAM z fizycznego N4000 na podstawie szybszego runnera.
