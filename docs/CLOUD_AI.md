# JARVIS CLOUD AI — audyt i pierwszy przyrost implementacji

## Audyt (22.10.2026)

- `main`, `ee76bfe`: działająca statyczna PWA, bez natywnego Androida ani API.
- `feature/jarvis-cloud-assistant`, `340bfa0`: Kotlin/Compose, Worker, 54 testy Node,
  narzędzia telefonu, pamięć faktów KV, Tavily, Google TTS, CI budowania APK.
- `feature/jarvis-visual-rebuild`, `d3d3749`: osobna linia przebudowy wyglądu; nie została scalona.
- Ten przyrost bazuje na **cloud-assistant**, na gałęzi `feature/jarvis-openai-foundation`.
  Zachowuje Androida i istniejące narzędzia. PR należy kierować do `feature/jarvis-cloud-assistant`,
  aby przegląd obejmował ten przyrost, a nie wszystkie wcześniejsze zmiany Androida.
- Deklaracja wcześniejszego wdrożenia w `CLOUD.md` jest historyczna. Bieżące `/health`
  testowego Workera zostało zablokowane przez proxy (403), więc nie potwierdzono stanu chmury.
- Nie uruchomiono produkcji, płatnego API, ani workflowu wdrożeniowego.
- Dotychczasowe tabele cen i nazwy modeli w `CLOUD.md` nie zostały potwierdzone;
  nie należy na nich opierać budżetu. Ten dokument opisuje bieżący przyrost.

## Faktycznie dodane

1. Adapter OpenAI Chat Completions, domyślnie `gpt-4.1-mini`, z możliwością zmiany `MODEL`.
   `AI_PROVIDER=openai|anthropic`. Nowa instalacja preferuje OpenAI. Instalacja mająca wyłącznie
   `ANTHROPIC_API_KEY` zachowuje Anthropic. Gdy istnieją oba klucze, wybierane jest OpenAI,
   chyba że ustawiono `AI_PROVIDER`. Brak automatycznego przełączania dostawcy po błędzie.
   Konwersja obrazów, narzędzi i wyników zachowuje kontrakt Androida. Timeout 25 s;
   OpenAI nie jest automatycznie ponawiane po niejednoznacznym błędzie sieciowym.
2. D1: rozmowy, wiadomości, zadania, metadane audytu, dzienny licznik wywołań.
   Transakcje zapisu, usuwanie wiadomości wraz z rozmową, limity wejścia.
3. `research` i `business_draft`: jawne uruchomienie, atomowe zajęcie zadania, zapis wyniku/błędu,
   anulowanie i odrzucenie spóźnionego wyniku. Badania używają istniejącej pętli Tavily, jeśli
   skonfigurowano jej klucz. Bez niego wynik nie jest badaniem aktualnego internetu.
4. Panel PWA na tym samym originie: token tylko w pamięci karty, rozmowa z AI,
   opcjonalny zapis nowych rozmów, odczyt/usuwanie historii i faktów, zadania i szkice biznesowe.
   Polecenia lokalne nadal działają bez chmury. API nie trafia do cache service workera.
5. Pakowanie publicznych zasobów PWA do Workera według jawnej listy. Źródła backendu,
   konfiguracja i sekrety nie są kopiowane do katalogu zasobów.

## Uruchomienie lokalne (Node 24)

```sh
cd /workspace/jarvis-android/backend
npm ci
npm test
cp -n wrangler.local.toml.example wrangler.local.toml
npm run build
npx wrangler d1 migrations apply DB --local --config wrangler.local.toml
npx wrangler dev --local --config wrangler.local.toml --port 8787
```

`.dev.vars` jest ignorowany przez Git. Ustaw w nim własny losowy `APP_TOKEN` (co najmniej
32 losowe bajty). Bez klucza modelu można sprawdzić pamięć, bazę i odmowę dostępu do AI.
`OPENAI_API_KEY` dodawaj wyłącznie do lokalnego pliku sekretów lub sekretów Workera,
po ustaleniu budżetu. Nigdy do PWA/APK ani do czatu z programistą. Nie commituj tego pliku.
Lokalny D1 i KV nie komunikują się ze zdalnymi bazami. `npm run check` pakuje Worker
w trybie dry-run; niczego nie wdraża.

## API osobistej przestrzeni

Każda ścieżka danych wymaga `x-jarvis-token`. Wszystkie osoby znające ten token mają
**pełny dostęp do tej samej przestrzeni**. To uwierzytelnianie osobistego prototypu,
nie wieloużytkownikowy IAM/OAuth ani system ról. Przed udostępnieniem innym wymagane
są osobne tożsamości, autoryzacja per rekord i rotacja sesji. CORS pozostaje wyłączony;
PWA łączy się z własnym originem, Android używa HTTPS.

| Operacja | API |
|---|---|
| Rozmowa AI (kontrakt Androida) | `POST /chat {messages, tools?, pending?}` |
| Fakty KV: lista/dodanie/usunięcie | `GET/POST/DELETE /memory`, `DELETE /memory/:id` |
| Rozmowy D1: lista/utworzenie | `GET/POST /conversations`, POST `{title}` |
| Transkrypcja: odczyt/dopisanie | `GET/POST /conversations/:id/messages`, POST `{role,content}` |
| Usunięcie rozmowy i wiadomości | `DELETE /conversations/:id` |
| Zadania: lista/utworzenie/odczyt | `GET/POST /tasks`, `GET /tasks/:id`, POST `{title,kind}` |
| Jawne wykonanie / anulowanie | `POST /tasks/:id/run`, `POST /tasks/:id/cancel` |

Transkrypcje zapisane przez klienta są danymi, nie dowodem wykonania operacji przez serwer.
Nie są automatycznie używane jako instrukcje ani przywracane do rozmowy z AI.
Panel historii odczytuje je do wglądu. Lista zwraca 100 najnowszych rekordów;
API wiadomości zwraca 100 ostatnich w kolejności chronologicznej. Paginacja jest dalszym etapem.

## Bezpieczeństwo i ograniczenia

- Zadania serwerowe mają tylko narzędzia odczytu publicznego internetu. Zakupy, finanse,
  publikacja, poczta i zmiany sklepu **nie mają wykonawców**. Integracje Shopify/GarmiLabs
  wymagają dokumentacji API, OAuth, zakresów uprawnień i testowego konta.
- Nie ma ogólnego wykonawcy arbitralnych URL/API, terminala ani przeglądarki z uprawnieniami.
  Prompt injection jest ograniczane przez oznaczanie niezaufanych danych i wąską listę narzędzi;
  same instrukcje modelu nie zapewniają całkowitej ochrony. Dotychczasowe akcje telefonu
  pozostają propozycjami, wykonywanymi według walidacji i potwierdzeń klienta Android.
- Zadania działają w żądaniu HTTP, a nie w trwałym background workerze. Przerwanie procesu
  może pozostawić `running`; nie ma jeszcze lease/recovery. Cloudflare Queues/Workflows
  i idempotentne wykonawce to następny etap. Anulowanie blokuje kolejne iteracje i zapis
  wyniku; nie cofa już wysłanego płatnego zapytania ani nie gwarantuje przerwania go u dostawcy.
- `DAILY_MODEL_CALL_LIMIT` wymaga DB i poprawnej dodatniej liczby; błąd bazy blokuje wydatki.
  Licznik rezerwuje logiczne wywołania modelu przed wysłaniem, także w pętli badawczej.
  Stary adapter Anthropic może ponowić transport jeden raz. To limit wywołań,
  nie dokładny globalny limit USD. Limit 40/min na instancję nadal jest pomocniczy.
- D1 i KV polegają na zabezpieczeniach platformy, HTTPS i szyfrowaniu platformowym w spoczynku.
  Nie dodano szyfrowania end-to-end ani aplikacyjnego kluczem użytkownika. Fakty/historia
  wysłane do modelu są przetwarzane przez dostawcę AI. Zapis historii w PWA jest domyślnie wyłączony.
- Audyt zawiera typ zdarzenia, identyfikator zasobu i czas; bez tokenów i treści rozmów.
  To nie jest niezmienny dziennik compliance. Nie obejmuje jeszcze wszystkich starszych akcji KV/TTS.
- Fakty KV mają istniejące ograniczenia współbieżności (read/modify/write). D1 nie zastępuje
  jeszcze KV faktów. Dla wielu użytkowników trzeba zmienić model izolacji i zapisu.

## Weryfikacja i pozostałe etapy

Przeszły 72 testy Node. Testy Node obejmują adaptery, kontrakt Androida, rzeczywisty SQL na SQLite,
przechowywanie i usuwanie rozmów, atomowe limity, wykonanie/anulowanie zadań i brak
wycieku błędów dostawcy. Wywołania AI są atrapami: testy nie dowodzą jakości odpowiedzi
ani dostępności wybranego modelu dla konkretnego konta.

Przeszły także: pakowanie `--dry-run --env test`, lokalna migracja D1, `node local-smoke.mjs`
w rzeczywistym workerd (auth, D1, KV, anulowanie, brak klucza) oraz przeglądarkowy
`python3 tools/cloud-smoke.py` uruchomiony z katalogu repozytorium. Test przeglądarkowy
wymaga Python Playwright i Chromium; odpowiedzi AI są atrapami, pozostałe endpointy
D1/KV są lokalne i rzeczywiste. Sprawdza też renderowanie HTML jako tekstu, brak cache API
i shell offline. Helper nie jest wymagany do zwykłego uruchomienia aplikacji.

Nie zbudowano nowego APK: na maszynie brak Gradle 8.9 i Android SDK 34.
Kod Androida pozostaje bez zmian; istniejący workflow testuje go i buduje APK.
Głos: istnieje Android STT, lokalny TTS i opcjonalny Google TTS. ElevenLabs, streaming,
pełna przeglądarka automatyzacji, harmonogram monitoringu, powiadomienia, integracje
biznesowe i niezawodne wybudzanie przy wygaszonym ekranie pozostają niewdrożone.

## Koszty i wdrożenie

Bieżące testy lokalne: **0 nowych kosztów usług zewnętrznych**; nie wysłano płatnego żądania.
Szacuj AI według aktualnego cennika konta:
`koszt = tokeny_wejścia/1e6 × cena_wejścia + tokeny_wyjścia/1e6 × cena_wyjścia`.
Narzędzia, pamięć i historia zwiększają kontekst, a badanie może wywołać model wielokrotnie.
Dolicz osobno Tavily, TTS i infrastrukturę. Nie zakładaj, że darmowy plan oznacza brak
możliwości naliczenia kosztów. Potwierdź ceny/limity w panelach przed włączeniem.

Po zgodzie właściciela na środowisko testowe:
1. Utwórz/wybierz D1 i KV w Cloudflare, dodaj właściwe bindingi do `env.test` i zastosuj
   `0001_cloud.sql` z `--remote --env test` dopiero po sprawdzeniu identyfikatora bazy.
2. Ustaw sekrety Workera: `OPENAI_API_KEY`, `APP_TOKEN`; opcjonalnie `TAVILY_API_KEY`,
   `GOOGLE_TTS_API_KEY`. Ustaw `AI_PROVIDER=openai`, zatwierdzony `MODEL` i liczbowy
   `DAILY_MODEL_CALL_LIMIT`. Limit wydatków oraz alerty skonfiguruj także u dostawców.
3. Dla ręcznego workflowu GitHub: `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`,
   `OPENAI_API_KEY` (lub zachowany Anthropic), `JARVIS_APP_TOKEN` oraz zmienna
   `JARVIS_D1_DATABASE_ID`. Workflow wymaga przygotowanej migracji i nie tworzy D1 automatycznie.
4. Zweryfikuj `/health`, odmowę bez tokenu, rozmowę, usuwanie pamięci/historii, zadanie i anulowanie.
   Test `smoke.mjs` używa prawdziwego AI i może kosztować — nie uruchamiaj bez budżetu.
5. Dopiero po testach na telefonie podejmij oddzielną decyzję o produkcji.

R2 jest opcjonalne dla przyszłych załączników. Queues/Workflows dla trwałych zadań,
a odizolowany kontener dla pełnej przeglądarki — z limitem czasu i egress, bez sekretów
w kontekście modelu. Bez otrzymanej zgody żaden z tych zasobów nie został uruchomiony.
