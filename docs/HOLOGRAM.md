# Holographic HUD 0.4 — 23.10.2026

## Zakres i audyt

Zmiany rozwijają `feature/jarvis-desktop-cloud` na gałęzi `feature/jarvis-holographic-hud`.
Dotychczasowy ekran Desktop miał kulę CSS i animację mowy sterowaną jedynie flagą.
PWA miała pierścienie CSS, a Android rysował metaliczny rdzeń Canvas.
Nie zmieniono kontraktu API, autoryzacji, modelu, przechowywania pamięci ani integracji.
Jedyna zmiana po stronie pakowania Workera kopiuje nowe publiczne zasoby HUD.

- Desktop: nowe centralne pole HUD i boczne panele, wszystkie dotychczasowe zakładki,
  sterowanie komputerem, historia, zadania, pamięć, ustawienia i zasobnik zachowane.
- PWA: ten sam silnik 3D oraz panele, dotychczasowy SpeechRecognition, polecenia tekstowe,
  synteza, instalacja i offline pozostają dostępne. Pogoda używa istniejącego `/weather`
  po uwierzytelnieniu; brak danych nie jest zastępowany domyślną temperaturą.
- Android: natywny Compose Canvas z projekcją 3D, bez WebView lub dużego silnika 3D.
  Istniejące rzeczywiste stany, pogoda i dane połączenia nadal pochodzą z ViewModel.

## Scena 3D

Three.js/WebGL2: geometria punktów na sferze, shader rozmiaru, głębi, światła i deformacji,
obrót przestrzenny, pierścienie i segmenty HUD, skala techniczna, świetlna podstawa.
Nie ma statycznego obrazu, filmu, zewnętrznego fontu ani tekstur z internetu.
Punkty mają deterministyczne współrzędne 3D; kolejne poziomy jakości obejmują całą kulę.

ECO: 640 cząsteczek / DPR do 1 / 24 kl./s. BALANCED: 1400 / DPR do 1,25.
HIGH: 2400 / DPR do 1,5. Normalny limit to 40 kl./s. Po utrzymującym się spadku
rzeczywistej liczby klatek scena obniża jakość. Wstępny wybór uwzględnia mały ekran,
pamięć (jeśli przeglądarka ją ujawnia), liczbę wątków, tryb oszczędny i reduced-motion.
Ukrycie okna lub przewinięcie sceny poza ekran zatrzymuje rendering. Demontaż zwalnia
geometrie, materiały, kontekst i obserwatory. Utrata WebGL daje ruchomą projekcję 3D
Canvas, z jawną etykietą trybu zgodności. Reduced-motion ogranicza animacje.

Android: 220 punktów i 20 kl./s na low-RAM, inaczej 480 i 30 kl./s, perspektywa
oraz sortowanie według głębi. Animacja zatrzymuje się poza aktywnym cyklem życia ekranu.

## Rzeczywista synchronizacja audio

Desktop Google TTS zachowuje ten sam głos i endpoint. Odtwarzanie przechodzi przez:
`HTMLMediaElement → MediaElementAudioSource → AnalyserNode → destination`.
Mikrofon używa osobnego `MediaStreamAudioSource → AnalyserNode`, bez odsłuchu zwrotnego.
RMS, peak, 24 pasma FFT i 48 punktów przebiegu pochodzą z próbek audio. Te same dane
zasilają panel sygnału i shader. Cicha próbka nie generuje sztucznego sygnału mowy.
Stop i ukrycie okna czyszczą sygnał i kończą analizę. Gdy analiza TTS nie jest dostępna,
odtwarzanie jest kontynuowane zwykłą ścieżką; panel pokazuje brak analizy.

**Ograniczenie API:** `speechSynthesis` w Windows/przeglądarce nie udostępnia PCM.
Nie przechwytujemy całego dźwięku komputera i nie zastępujemy głosu. Dla tego silnika
SPEAKING jest rzeczywistym stanem odtwarzania, ale kula zachowuje ruch spoczynkowy,
a panel jawnie pokazuje „Głos systemowy • brak dostępu do PCM”. Nie udajemy synchronizacji.
PWA używa tego samego systemowego TTS; dodatkowy pomiar mikrofonu jest best effort
po rozpoczęciu rozpoznawania. Błąd analizatora nie blokuje rozpoznawania.

Android Google TTS: Visualizer analizuje wyłącznie sesję własnego MediaPlayer,
ze skalowaniem AS_PLAYED. Nie używa sesji 0 ani globalnego audio telefonu.
Przy braku uprawnienia/obsługi wizualizatora odtwarzanie pozostaje bez zmian, poziom
jest zerowany i dostępność jest pokazywana w UI. Systemowy Android TTS pozostaje bez
przechwytywania PCM. Nie dodano nowych uprawnień; RECORD_AUDIO i MODIFY_AUDIO_SETTINGS
były już zadeklarowane.

## Panele i stany

IDLE, LISTENING, THINKING, SPEAKING, ERROR wynikają z rzeczywistych zdarzeń UI/audio/API.
THINKING przyspiesza pierścienie, bez udawania procentowego postępu modelu.
Zegar korzysta z czasu urządzenia. Historia pokazuje faktyczne wiadomości i zapisane
czasy; brak czasu w starym wpisie jest oznaczany kreską. Pogoda wymaga odpowiedzi API.
Zadania pochodzą z `/tasks`; przed pobraniem stan jest nieznany, nie „0 zadań”.
FPS, liczba punktów i tryb renderera są mierzone/wybrane przez silnik. Nie ma fikcyjnych
CPU/RAM/temperatury procesora, map globalnych operacji ani zmyślonych wykonanych działań.

## Budowanie i testy

```sh
cd desktop
npm ci
npm run build  # Desktop i samodzielny pakiet PWA w ../hologram
npm test
```

`hologram/viewer.js`, CSS i licencja Three.js są generowane i zapisane w repozytorium,
aby statyczna PWA działała także bez Node na hostingu. Po zmianie źródeł silnika uruchom
`npm run build:hologram`. CI sprawdza zgodność wygenerowanego pakietu.

Test przeglądarki, z głównego katalogu i lokalnym serwerem:

```sh
python3 -m http.server 8000 --bind 127.0.0.1
# w drugiej sesji; Python Playwright + Chromium/Chrome
python3 tools/hologram-smoke.py
```

Sprawdza rzeczywisty WebGL, zmianę obrazu w czasie, działające polecenie tekstowe,
RMS tonu Web Audio i ciszę, deformację powiązaną z tym sygnałem, utratę GPU/fallback,
zwolnienie zasobów oraz układ telefonu bez poziomego przewijania. To nie jest pomiar
na fizycznym telefonie ani gwarancja FPS Celerona. Software WebGL w kontenerze
osiągał około 16–24 kl./s i obniżał jakość; wynik zależy od maszyny i GPU.

`desktop/tests/electron-smoke.cjs` testuje faktyczny odtwarzacz TTS z lokalnym plikiem
WAV-atrapą odpowiedzi serwera, reakcję kuli i wyzerowanie po Stop. Test nie używa
płatnego TTS ani zmyślonego sygnału w kodzie aplikacji. Zachowuje też testy sandboxa,
historii, autoryzacji sterowania, zasobnika i cyklu życia tokenu.

Osobne workflowy sprawdzają WebGL/PCM/PWA, backend, instalator Windows i natywny Android.
Zrzuty zapisują się w desktop/release i artefaktach CI. Jakość prawdziwego głosu,
kompatybilność mikrofonu podczas Web Speech na telefonie oraz płynność na Windows 10/
Celeronie i fizycznym Androidzie wymagają testu urządzeń. Nie wdrożono produkcji,
nie zmieniono sekretów i nie uruchomiono płatnych usług; limit właściciela pozostaje 50 zł/mies.
