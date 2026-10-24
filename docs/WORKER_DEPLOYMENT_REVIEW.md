# Naprawa połączenia z jarvis-android — przygotowanie wdrożenia

## Stan kodu i właściwy Worker

Wersja Desktop 0.5.2 poprawnie zgłasza brak `/health` pod adresem `https://jarvis-android.lunvra-golf.workers.dev`. W repozytorium endpoint już istnieje w `backend/worker.js`; punktem wejścia jest `backend/entry.js`. Nie należy dodawać osobnego Workera z fikcyjnym `{ok:true}`. Potrzebny jest pełny backend: autoryzacja, adapter AI, polski STT i historia.

Dotychczasowy `backend/wrangler.toml` domyślnie wskazuje nazwę `jarvis-backend`, a środowisko testowe `jarvis-backend-test`. Wdrożenie do tych nazw nie naprawia automatycznie adresu **jarvis-android**. Nowa paczka przygotowuje konfigurację dokładnie dla tej nazwy i jawnie włącza trasę workers.dev. Samo przygotowanie paczki nie zmienia routingu ani kodu w Cloudflare.

## Kontrakt Windows / Android

| Operacja | Żądanie | Odpowiedź |
|---|---|---|
| Test konfiguracji | `GET /health`, opcjonalnie `x-jarvis-token` | JSON z `auth`, `configured`, `provider`, `model`, `api_version`, `capabilities` |
| AI, ten sam kontrakt Androida | `POST /chat {messages:[{role,content}]}` | `{reply}` lub dotychczasowe narzędzia telefonu |
| Polski STT | `POST /stt {audio:"base64",mime:"audio/webm"}` | `{text}` |
| Głos | `POST /tts {text}` | `{audio,format,voice,...}` |
| Historia | `/conversations`, `/conversations/:id/messages` | Dane zapisywane w D1 jako `DB` |

Wszystkie operacje na danych wymagają `x-jarvis-token` równego **APP_TOKEN tego Workera**. To nie jest klucz OpenAI ani token logowania ChatGPT. Brak APP_TOKEN blokuje dostęp. `/health` może być odczytany bez tokenu, ale wtedy nie potwierdza autoryzacji. Nie dodano CORS `*`, obejścia autoryzacji ani alternatywnego wspólnego tokenu.

`OPENAI_API_KEY` jest potrzebny do STT; istniejąca obsługa Anthropic dla rozmowy pozostaje zachowana. Klucze są wyłącznie sekretami serwera. D1 i migracje są wymagane do rozmowy z historią Desktop. Fakty osobiste wymagają dotychczasowego KV `MEMORY`; kolejki pozostają osobną możliwością długich zadań. Nie tworzono nowych płatnych usług.

## Nowy pakiet do przeglądu

Workflow **Jarvis — Worker API audit and deployment review (NO DEPLOY)** uruchamia się ręcznie lub po zmianie jego definicji w repozytorium:

1. Uruchamia testy backendu i lokalnego, rzeczywistego runtime workerd.
2. Ponownie odczytuje publiczne endpointy i metadane Cloudflare wyłącznie przez GET. Nie wykonuje prawdziwego płatnego AI/STT/TTS.
3. Zachowuje znane dotychczasowe wiązania D1/KV, zmienne i sekrety. Nieobsługiwane wiązanie przerywa przygotowanie zamiast je usuwać.
4. Może użyć wyłącznie istniejącej D1 wskazanej przez `JARVIS_D1_DATABASE_ID` w zmiennych repozytorium. Weryfikuje ją przez API. Nie wybiera pierwszej przypadkowej bazy, nie zmienia istniejącego `DB` i nie tworzy bazy.
5. Buduje archiwum **Jarvis-Cloudflare-Worker-review** z `bundle/entry.js`, `public/`, migracjami, `wrangler.owner.json`, `review.json` i instrukcją. `.dev.vars`, `.env`, klucze oraz wartości zmiennych odczytanych z panelu nie są kopiowane.
6. Pakuje tę konkretną konfigurację przez Wrangler `--dry-run`. **Nie zawiera kroku wdrażania**, zapisu sekretów ani migracji zdalnej bazy.

`review.json` podaje braki i to, czy wskazano bazę oraz czy workers.dev jest aktywne. `configurationComplete` odnosi się do wykrytych wymagań; `readyToDeploy` pozostaje `false`, ponieważ zgoda właściciela i przegląd migracji nie zostały zastąpione automatem. Puste wiązania lub klucze nie są oznaczane jako działające. Brak wybranej D1 nie uniemożliwia zbudowania paczki do przeglądu, ale blokuje gotowość konfiguracji.

Paczka nie zastępuje zgody na produkcję. Przed zatwierdzonym wdrożeniem należy rozwiązać braki raportu, potwierdzić docelową istniejącą bazę, przejrzeć migracje i zachować możliwość powrotu do dotychczasowej wersji. Nie resetować ani nie usuwać danych. Dzienny limit to liczba wywołań; limit 50 zł miesięcznie wymaga dodatkowo kontroli rozliczeń u dostawcy.

## Test rzeczywistej integracji

```sh
cd backend
npm ci
npm test
npm run test:integration
cd ../desktop
npm test
npm run build
node tests/wake-worker-api-smoke.cjs
```

Linux wymaga działającego serwera graficznego do testu Electron. `--no-sandbox` jest stosowane wyłącznie w testach linuksowego kontenera; produkcyjny renderer zachowuje sandbox i izolację.

Test API uruchamia **skompilowane `entry.js` w workerd**, pliki statyczne oraz prawdziwe lokalne D1/KV. Sprawdza JSON `/health` przy działających zasobach WWW, odrzucenie błędnego APP_TOKEN przed wywołaniem dostawcy, `/stt`, zgodny z Androidem `/chat`, TTS, trwały zapis transkrypcji i licznik budżetu.

Nowy test Electron przechodzi cały cykl po syntetycznej polskiej frazie: lokalny ONNX → „Jestem, słucham cię” → mikrofon polecenia → polski STT → adapter OpenAI → dwie wiadomości w lokalnej D1 → odtworzenie rzeczywistego MP3 → powrót do nasłuchu przy zminimalizowanym oknie. Do chwili wykrycia hasła nie ma wywołań STT/AI/TTS. Cztery późniejsze wywołania rezerwują faktyczny licznik w D1.

Jedynie zewnętrzne płatne usługi są przechwytywane przez lokalną funkcję testową; nie zastępujemy tras API ani bazy odpowiedziami testowego serwera HTTP. Test sprawdza integrację, lecz nie dowodzi poprawności prywatnego klucza modelu ani mikrofonu fizycznego N4000. To sprawdzenie pozostaje do wykonania po zatwierdzonym skonfigurowaniu chmury.

Interfejs graficzny, kod Androida i istniejące prace Centrum Integracji pozostają bez zmian.
