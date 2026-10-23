# Windows — lokalne wybudzanie „Pobudka Jarvis” (0.5 test)

Ta wersja dodaje osobny, lokalny detektor całej polskiej frazy „Pobudka Jarvis” / „Pobudka, Dżarwis”. Nie używa ciągłego rozpoznawania mowy przez chmurę. Funkcja jest domyślnie wyłączona. Jest przeznaczona do testów; wyniki na syntezie mowy nie potwierdzają jeszcze skuteczności na mikrofonie właściciela ani na Celeronie N4000.

## Wymagania Windows

Windows 10/11 x64, dostęp do mikrofonu oraz [Microsoft Visual C++ Redistributable x64](https://learn.microsoft.com/pl-pl/cpp/windows/latest-supported-vc-redist) dla Visual Studio 2015–2022 lub nowszy zgodny pakiet z tej oficjalnej strony. Natywny ONNX Runtime wymaga bibliotek `MSVCP140`, `MSVCP140_1`, `VCRUNTIME140` i `VCRUNTIME140_1`; pakiet npm nie zawiera tych bibliotek. Na wielu komputerach są już zainstalowane przez inne aplikacje. Jeśli lokalny silnik nie startuje na nowym Windows, sprawdź ten warunek przed ponowną instalacją Jarvisa.

Instalator testowy Jarvisa nie instaluje ani nie aktualizuje środowiska Visual C++ w systemie. W razie potrzeby pobierz oficjalny pakiet Microsoft i zatwierdź jego instalację samodzielnie. Test na runnerze GitHub, który ma już narzędzia Visual C++, nie potwierdza obecności tego pakietu na czystym laptopie.

## Włączenie i wyłączenie

1. Otwórz Jarvisa i przejdź do Ustawień → wybudzanie głosowe.
2. Włącz wybudzanie, wybierz mikrofon oraz czułość i zapisz ustawienia nasłuchu. Windows musi pozwalać aplikacjom klasycznym korzystać z mikrofonu.
3. Opcja „Uruchamiaj nasłuchiwanie wraz z Jarvisem” wznawia nasłuch przy następnym starcie aplikacji. Osobne istniejące ustawienie „Uruchamiaj razem z Windows” uruchamia aplikację po zalogowaniu do systemu.
4. Głosowe potwierdzenie aktywacji można wyłączyć. Przy włączonym potwierdzeniu Jarvis mówi „Jestem, słucham cię”, czeka na zakończenie odtwarzania i dopiero potem otwiera mikrofon polecenia.
5. Zatrzymaj nasłuch natychmiast przyciskiem w oknie lub pozycją „Natychmiast wyłącz nasłuch” w menu ikony zasobnika. Wyłączenie nie wymaga połączenia z internetem.

Niższa czułość wymaga wyższego wyniku modelu i może pomijać cichą mowę. Wyższa czułość zwiększa ryzyko przypadkowych aktywacji. Dobierz ją na własnym mikrofonie. Po odłączeniu urządzenia, odebraniu uprawnień lub błędzie modelu Jarvis pokazuje komunikat; wybierz działający mikrofon i świadomie włącz nasłuch ponownie.

Domyślne 50% czułości odpowiada progowi modelu 0,90, wymagającemu dwóch kolejnych ramek. Zakres suwaka odpowiada progom od 0,97 do 0,83. Nie jest to regulacja głośności mikrofonu.

## Przebieg rozmowy

- IDLE: kula powoli pulsuje, a widoczny status mikrofonu informuje o lokalnym nasłuchu.
- Wykrycie całej frazy: niebieski błysk kuli i wstrzymanie detektora hasła.
- Potwierdzenie: istniejący dostawca TTS i wybrany głos; mikrofon hasła oraz mikrofon polecenia pozostają wstrzymane na czas wypowiedzi.
- LISTENING: nagranie następnego polecenia, reakcja kuli na rzeczywisty sygnał mikrofonu.
- THINKING: transkrypcja i istniejąca obsługa AI, pamięci oraz uprawnień.
- SPEAKING: istniejący głos Jarvisa. Analiza rzeczywistego odtwarzania działa tam, gdzie dostawca udostępnia PCM. Systemowy `speechSynthesis` nie udostępnia PCM; jego ograniczenie pozostaje widoczne, bez udawanej amplitudy.
- Po zakończeniu odpowiedzi albo braku polecenia Jarvis wraca do lokalnego nasłuchu hasła, jeśli użytkownik go nie zatrzymał.

Samo wybudzenie nie daje nowych uprawnień. Otwieranie aplikacji i inne istniejące operacje lokalne zachowują dotychczasowe uwierzytelnienie i okna zatwierdzania. Detektor nie potwierdza tożsamości mówiącego. Odtworzenie dokładnej frazy z filmu lub głośnika może aktywować asystenta; system nie może wiarygodnie rozpoznać intencji źródła dźwięku.

## Prywatność i działanie w tle

Hasło analizuje lokalny wątek roboczy ONNX Runtime na CPU. Ramki mikrofonu są przetwarzane w pamięci; aplikacja nie zapisuje nagrań z nasłuchu. Detektor nie ma klienta sieciowego ani klucza API. Wszystkie potrzebne modele są dołączone do instalatora — po instalacji nie potrzeba połączenia do wykrywania hasła.

Osobne polecenie wypowiedziane po aktywacji korzysta z dotychczasowego `/stt` i systemu AI w chmurze. Ta krótka sesja może generować dotychczasowe koszty API. Sam lokalny detektor nie korzysta z płatnej usługi. Potwierdzenie „Jestem, słucham cię” używa jednak wybranego dostawcy głosu: systemowy TTS działa bez kosztu API, a skonfigurowany Google TTS może naliczyć koszt już przy potwierdzeniu. Potwierdzenie można wyłączyć. Zachowaj limit 50 zł miesięcznie również dla tych wywołań.

Nasłuch działa również po zminimalizowaniu lub ukryciu okna w zasobniku. Ikona zasobnika pokazuje stan i pozwala zatrzymać nasłuch. Zakończenie aplikacji zwalnia mikrofon i wątek modelu. Brak obchodzenia blokady ekranu, logowania ani ustawień prywatności Windows. Jeśli Windows zawiesi aplikację, odbierze mikrofon lub uśpi komputer, detektor nie może wtedy działać. Działanie przy zablokowanej sesji wymaga sprawdzenia na konkretnym systemie i urządzeniu audio.

## Silnik, licencje i zasoby

- ONNX Runtime Node 1.24.3: MIT, wykonywanie wyłącznie CPU, jeden wątek obliczeń na sesję modelu. Nie wymaga GPU, CUDA, Pythona ani konta u dostawcy detektora na komputerze użytkownika.
- Ekstraktor mel i Google speech embedding: architektura openWakeWord, Apache-2.0. Używamy własnego klasyfikatora frazy; gotowy angielski model `hey_jarvis` nie jest rozwiązaniem dla tej polskiej komendy. Wcześniejsze pomiary: [WAKE_WORD.md](WAKE_WORD.md).
- Dane syntetyczne do treningu: Piper darkman, mc_speech i gosia, karty modeli wskazują dane CC0. Nie jest kopiowany głos aktora. Źródła, odciski plików i pomiar własnego modelu znajdują się w `tools/wakeword-train/` i `desktop/resources/wake/`.

Przetwarzanie odbywa się w ramkach 80 ms przy 16 kHz. Bufory i kolejka są ograniczone, a obliczenia nie blokują głównego wątku okna. Nie deklarujemy zmierzonego zużycia CPU/RAM na Celeronie N4000: ten pomiar wymaga fizycznego laptopa. Tryb oszczędny grafiki ogranicza koszt kuli niezależnie od detektora.

## Budowanie i testy

Modele znajdują się w `desktop/resources/wake`. Electron Builder kopiuje je do katalogu `resources/wake` instalacji, poza archiwum ASAR. Biblioteki natywne ONNX są rozpakowane do `app.asar.unpacked`, ponieważ nie mogą być ładowane jako zwykłe pliki wewnątrz ASAR. Kod roboczy pozostaje w skompilowanym `dist-electron`.

Na Windows PowerShell:

```powershell
cd desktop
$env:ONNXRUNTIME_NODE_INSTALL = 'skip'
npm ci
npm test
npm run dist:win
```

Na Linux/macOS podczas przygotowania środowiska:

```sh
cd desktop
ONNXRUNTIME_NODE_INSTALL=skip npm ci
npm test
npm run build
```

`skip` pomija pobieranie opcjonalnych bibliotek CUDA/TensorRT; gotowy runtime CPU nadal pochodzi z przypiętego pakietu npm. Nie należy ustawiać `--ignore-scripts` globalnie — inne zależności, w tym Electron, potrzebują instalacji. Workflow Windows ustawia ten parametr i buduje testowy NSIS EXE bez publikowania produkcji. Istniejące testy instalują EXE, uruchamiają faktycznie zainstalowaną aplikację i ją odinstalowują.

Automatyczne testy logiki, rzeczywistego wnioskowania i obsługi okna mają inne znaczenie niż test akustyczny. Ostateczny raport uruchomienia CI i pomiar modelu należy czytać razem; sam zielony build nie dowodzi skuteczności przy każdym mikrofonie.

Model `0.1-beta`: przy domyślnym progu rozpoznał **104/108** dodatnich prób syntetycznych i wywołał **1/1566** fałszywych aktywacji na kontrolach. Pozostała pomyłka to syntetyczne „Pobudka, Darwin”. Świeższy podzbiór tempa/szumu miał 68/72 wykryć i 0/1044 fałszywych aktywacji. Te same trzy syntetyczne głosy występują w treningu, a wyniki służyły do porównania wariantów — to diagnostyka rozwoju, nie niezależny pomiar skuteczności użytkowej. Pełne wyniki i progi: `desktop/resources/wake/evaluation.json`.

Testy Electron używają rzeczywistego AudioWorklet, mikrofonu testowego odtwarzającego polski WAV i lokalnego modelu ONNX. Wyłącznie STT/AI/TTS zastępuje lokalny serwer testowy. Obejmują nasłuch przy ukrytym/zminimalizowanym oknie, pełny cykl do ponownego uzbrojenia, autostart bez tokenu chmury i zwolnienie mikrofonu po zatrzymaniu lub utracie urządzenia. Test zainstalowanej aplikacji sprawdza załadowanie dołączonego modelu i biblioteki natywnej poza ASAR.

## Próby wymagające rzeczywistego Windows

Do odbioru na docelowym laptopie wykonaj co najmniej:

| Próba | Metoda | Wynik do zapisania |
|---|---|---|
| Odległość | Po 20 wypowiedzi obu wariantów z 0,5 m, 1 m, 2 m i 3 m, cisza oraz szum tła | Wykrycia, pominięcia i opóźnienie dla każdej odległości |
| Przypadkowe aktywacje | Zwykła rozmowa, muzyka i materiał TV przez co najmniej godzinę, bez wypowiadania pełnej frazy | Liczba aktywacji / czas |
| Zasobnik | Ukryj okno, wybudź i wypowiedz polecenie; powtórz cykl | Odpowiedź i powrót do nasłuchu |
| Głos Jarvisa | Odtwarzaj potwierdzenie i długą odpowiedź | Brak ponownego wybudzenia od własnego TTS |
| Utrata mikrofonu | Odłącz mikrofon lub odbierz uprawnienie | Zatrzymanie i czytelny komunikat, bez wysyłania ciągłego audio |
| Blokada / wznowienie | Zablokuj sesję, uśpij i wznow urządzenie | Działanie zgodne z uprawnieniami Windows, bez obejść |
| Celeron / 4 GB | 10 minut nasłuchu w zasobniku, potem rozmowa | Średni CPU, pamięć procesu, opóźnienie i komfort pracy |

Nie wykonujemy automatycznych nagrań tych prób. Jeśli użytkownik chce zebrać próbki do poprawy modelu, wymaga to jego osobnej zgody na nagrywanie i przechowywanie.

Poprawki błędów i diagnostyka wersji 0.5.1: [WINDOWS_DIAGNOSTICS.md](WINDOWS_DIAGNOSTICS.md).
