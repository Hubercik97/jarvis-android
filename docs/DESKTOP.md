# JARVIS DESKTOP + CLOUD — wersja testowa 0.3

## Audyt i kierunek

Główny klient: Windows 10/11 x64, Electron + React + TypeScript. Model, trwała pamięć
oraz zadania działają w Cloudflare, bez lokalnego modelu AI. Android pozostaje opcjonalnym
klientem; jego kod nie został usunięty ani przebudowany w tym przyroście.

Baza zmian: `feature/jarvis-cloud-assistant` → `feature/jarvis-openai-foundation` →
`feature/jarvis-desktop-cloud`. Zastano działającą PWA, Kotlin/Compose, backend Workers,
OpenAI/Anthropic, D1 rozmów i zadań, KV faktów oraz 72 testy backendu. Stary wykonawca
zadania działał tylko przez czas żądania HTTP. Odczyt publicznego `/health` istniejącego testowego Workera potwierdził dostępność starszego backendu Anthropic, KV i wyszukiwania; bez tokenu nie testowano płatnej rozmowy. Nowy kod OpenAI/Desktop nie został tam wdrożony.

Panel WWW w katalogu głównym pozostaje
alternatywą bez instalacji; sterowanie systemem jest dostępne wyłącznie w Electronie.

## Co zbudowano

- Osobne okno Electron, React/TypeScript, ciemny interfejs, kula AI reagująca na poziom
  mikrofonu i stan mowy, tekst rozmowy, lokalny zegar, pogoda na żądanie.
- Rozmowa przez istniejące API OpenAI, historia D1 z możliwością ponownego otwarcia
  i usunięcia, panel faktów KV, panel zadań z wynikami, terminem i anulowaniem.
- Pamięć projektów D1: repozytorium, podsumowanie postępu, aktualizacja i usuwanie.
- JARVIS DEVELOPER: odczyt metadanych repozytorium przez oficjalne GitHub API.
  Nie jest to jeszcze agent Codex wykonujący kod, testy i PR na polecenie modelu.
- Lokalny agent w procesie głównym Electron: otwieranie Chrome, Dokumentów i skonfigurowanego
  repozytorium GitHub. Przykłady: „Jarvis, otwórz Chrome”, „otwórz folder Dokumenty”,
  „otwórz mój projekt na GitHubie”. Każda akcja wymaga sprawdzenia tokenu w chmurze i natywnego potwierdzenia.
- Minimalizacja/zamknięcie do zasobnika; zakończenie procesu przez menu zasobnika.
  Uruchamianie z Windowsem jest opcją domyślnie wyłączoną.
- Nagranie mikrofonu do 30 sekund, transkrypcja polska przez `/stt` i OpenAI.
  Rozpoznany tekst wymaga naciśnięcia Wyślij. Ukrycie okna odrzuca rozpoczęte nagranie.
- Głos systemowy z wyborem polskiego głosu; opcjonalny Google TTS z męskim Charonem.
  Przycisk Stop oraz dotknięcie kuli przerywają wypowiedź. Dostępność i jakość lokalnego
  męskiego głosu zależy od pakietów zainstalowanych w Windows.
- Cloudflare Queues: żądanie uruchomienia zwraca 202, a konsument zapisuje wynik w D1
  niezależnie od klienta. Cron obsługuje terminy zadań oraz odzyskanie utraconej wysyłki.
- Windows NSIS EXE: konfiguracja instalatora per użytkownik, workflow kompilujący
  na `windows-latest`, test Electron, instalacja/deinstalacja na runnerze Windows, test zainstalowanej aplikacji, suma SHA256 i artefakt do pobrania. Workflow nie
  publikuje wydania, nie wdraża chmury i nie podpisuje kodu certyfikatem.

## Instalacja i połączenie użytkownika

1. Pobierz artefakt `Jarvis-Desktop-Windows-x64-test` z workflowu **Jarvis Desktop — Windows test build**.
   Zweryfikuj SHA256 względem pliku obok instalatora.
2. Uruchom `Jarvis-Desktop-0.3.0-Setup-x64.exe`, wybierz katalog i zakończ instalację.
   Build testowy jest niepodpisany. Windows może wyświetlić SmartScreen; nie jest to
   potwierdzenie bezpieczeństwa programu ani zalecenie wyłączania zabezpieczeń.
3. W Ustawieniach wpisz publiczny adres HTTPS własnego Workera (bez ścieżki) oraz APP_TOKEN.
   To token aplikacji, **nie klucz OpenAI**. Klucze dostawców pozostają w Cloudflare.
4. Włącz zapamiętywanie tokenu tylko na swoim koncie Windows: Electron safeStorage używa
   systemowego szyfrowania (DPAPI). Bez tej opcji token znika po zamknięciu procesu.
5. Wybierz głos, opcjonalnie włącz sterowanie lokalne i autostart. Przetestuj mikrofon,
   polski głos, pamięć rozmowy i otwarcie Dokumentów po potwierdzeniu.

To jedna osobista przestrzeń ze wspólnym tokenem. Każdy posiadacz tokenu ma dostęp do
wszystkich jej danych. Nie udostępniaj jej jako usługi wielu użytkownikom bez osobnych
kont, sesji i autoryzacji per rekord.

## Rozwój lokalny

Node 24:

```sh
cd desktop
npm ci
npm test
npm run build
npm start
```

Electron pobiera własny runtime z oficjalnych wydań z kontrolą sum. `npm run dev`
uruchamia jedynie podgląd React w przeglądarce — nie udaje dostępu do agenta Windows.
Podgląd nie ma tokenu ani bezpośredniego dostępu do API. Działająca alternatywa WWW
pozostaje w głównym katalogu repozytorium i jest hostowana przez Worker.

```sh
# Na Windows: natywny instalator NSIS, bez publikacji
cd desktop
npm run dist:win
# Test prawdziwego okna + IPC z lokalnym serwerem-atrapą AI
node tests/electron-smoke.cjs
```

Na Linux test okna wymaga serwera X (np. Xvfb). Wyłącznie harness Linux uruchamia
Electron z `--no-sandbox`, aby działać w ograniczonym kontenerze. Zbudowana aplikacja
nie dostaje tej flagi. Test Windows nie wyłącza sandboxa.

Cross-build NSIS z Linux wymaga działającego Wine dla etapu deinstalatora. Natywny
workflow Windows jest podstawową ścieżką budowania. Pierwszy natywny build przeszedł
łącznie z testem okna i pakowaniem instalatora; wynik najnowszego commitu sprawdzaj w Actions. Nie traktuj małego pośredniego
EXE z nieudanego cross-buildu jako gotowego instalatora.

## Backend lokalny i chmurowy

Zachowaj istniejące zasoby i przed migracją zdalną wykonaj kopię bazy.

```sh
cd backend
npm ci
npm test
npm run build
cp -n wrangler.local.toml.example wrangler.local.toml
npx wrangler d1 migrations apply DB --local --config wrangler.local.toml
npx wrangler dev --local --config wrangler.local.toml --port 8787
```

Lokalny `.dev.vars` (ignorowany w Git) zawiera APP_TOKEN. Do developmentu Electron
akceptuje również `http://127.0.0.1:8787`; pakiet produkcyjny wymaga HTTPS.
Nie dołączaj `.dev.vars`, tokenów ani kluczy do instalatora. Klucz modelu nie jest
potrzebny do testów z atrapami. Wywołania prawdziwych modeli/STT/TTS mogą kosztować.

Po **osobnej zgodzie na koszty i testowe wdrożenie**:

1. Skopiuj `backend/wrangler.cloud.toml.example` do ignorowanego pliku konfiguracyjnego.
   Wskaż własny Worker, D1 i KV; nie pozostawiaj zerowych identyfikatorów.
2. Utwórz kolejkę `jarvis-desktop-tasks-test` na właściwym koncie. Powiąż `TASK_QUEUE`
   jako producenta i konsumenta oraz ustaw cron. Sam plik przykładowy niczego nie tworzy.
3. Zastosuj migracje **0001, 0002 i 0003** do testowej D1 przez `--remote` po sprawdzeniu
   identyfikatora bazy. Starsze instalacje wymagają migracji 0002 przed nowym wykonawcą zadań.
4. Ustaw sekrety Workera: APP_TOKEN, OPENAI_API_KEY; opcjonalnie GOOGLE_TTS_API_KEY,
   TAVILY_API_KEY, GITHUB_READ_TOKEN (tylko niezbędny odczyt repozytorium). Wybierz
   AI_PROVIDER, MODEL, STT_MODEL oraz DAILY_MODEL_CALL_LIMIT, np. 100.
5. Ustaw limit i alerty kosztów u dostawców. Dzienny licznik obejmuje logiczne wywołania
   modelu, transkrypcji i chmurowego TTS, ale nie mierzy USD. Stary Anthropic może
   ponowić transport. Anulowanie nie cofa już poniesionego kosztu wywołania.
6. Wykonaj dry-run, a dopiero po zatwierdzeniu wdrożenie testowe. Nie używaj istniejącego
   workflowu HTTP-only jako dowodu, że kolejka i cron są skonfigurowane.

Wymagane kierunki sieci Workera: api.openai.com, opcjonalnie api.tavily.com,
texttospeech.googleapis.com, api.github.com i api.open-meteo.com. To nie są sekrety.
Komputer kontaktuje się wyłącznie ze skonfigurowanym API HTTPS; token nie jest
przekazywany dalej do GitHub, pogody ani dostawców AI.

### Zachowanie kolejki

D1 zajmuje zadanie atomowo; duplikat wiadomości nie uruchamia modelu ponownie.
Wysyłka ma dwuminutową dzierżawę. Cron ponawia utraconą wysyłkę, nie płatne wykonanie.
Zadanie przerwane w `running` po 15 minutach jest oznaczane jako failed: użytkownik
świadomie tworzy nową próbę. Błąd dostawcy zapisuje wynik failed bez automatycznego
ponawiania płatnego zadania. Anulowanie blokuje dalsze iteracje i zapis spóźnionego wyniku.
Harmonogram jest jednokrotny, z dokładnością crona; nie ma jeszcze cyklicznych monitorów
ani powiadomień push. Gdy komputer jest wyłączony, cloud wykonuje tylko zadania,
które nie potrzebują lokalnego agenta. Nie otworzy aplikacji na wyłączonym komputerze.

## Bezpieczeństwo

Renderer: sandbox, contextIsolation, wyłączone nodeIntegration, CSP, brak nawigacji
na obce strony i nowych okien. Wąski preload sprawdza nadawcę IPC i dopuszczone ścieżki.
Token pozostaje w procesie głównym; ustawienia zwracane interfejsowi zawierają tylko
informację o jego obecności. HTTP nie śledzi przekierowań z tokenem.

Brak zdalnego listenera komputera, dowolnej powłoki, PowerShell, ścieżek plikowych
z modelu i arbitralnych adresów. Polecenia lokalne są rozpoznawane deterministycznie
z jawnej listy, a odpowiedź AI nigdy nie jest wykonywanym kodem. Sterowanie lokalne
jest domyślnie wyłączone i nawet po włączeniu wymaga natywnego potwierdzenia.
Dziennik actions.jsonl zapisuje typ wykonanej akcji i czas, bez tekstów rozmów i tokenów.

Wiadomości, publikacje, usuwanie plików, zakupy, zmiany systemowe i zdalne wdrożenia
nie mają wykonawców w tej wersji. Ich przyszłe dodanie wymaga szczegółowych zakresów,
przeglądu skutków i osobnego potwierdzenia. Integracje Shopify/GarmiLabs i Codex są
opisane jako przyszłe, a nie prezentowane jako aktywne.

## Walidacja, wydajność i ograniczenia

Lokalnie przeszły 83 testy backendu, 7 testów polityki i poleceń Desktop, TypeScript/build React,
test rzeczywistego okna Electron i IPC oraz test lokalnego workerd/D1/KV.
Lokalna Queue dostarczyła zadanie po odpowiedzi 202 i zapisała w D1 oczekiwany błąd
braku klucza — bez wywołania płatnego API. Testy konsumenta z atrapą AI sprawdzają
wynik completed, duplikaty, terminy, anulowanie i awarię producenta.

Testy AI/STT/pogody/GitHub/TTS używają atrap. Nie potwierdzają jakości polskiej mowy,
dostępności modelu dla konkretnego konta ani zdalnego wdrożenia. Nie przeprowadzono
ręcznego testu na Windows 10/11, Celeron N4000 ani mikrofonie właściciela.

W teście Electron/Linux z automatyzacją suma working sets procesów wyniosła ok. 550 MB.
Nie jest to gwarancja zużycia RAM na Windows. Nie ma lokalnego modelu, WebGL, filmów
ani zewnętrznych fontów. Domyślny tryb oszczędny ogranicza animacje; analizator mikrofonu
odświeża poziom maksymalnie ok. 15 razy/s i jest zamykany po nagraniu. Zadania są
odświeżane tylko przy otwartym panelu, nie co sekundę w tle. Test na docelowym
komputerze 4 GB RAM pozostaje warunkiem oceny płynności.

Nowe płatne usługi nie zostały uruchomione. Koszt użycia chmury zależy od tokenów
wejścia/wyjścia, minut transkrypcji, znaków TTS, odczytów/zapisów D1/KV i operacji
kolejki. Nie zakładaj gwarantowanego 0 zł ani starego cennika z historycznych dokumentów.
Przed live testem uzgodnij budżet i aktualne limity dostawców.
