# Jarvis Desktop 0.5.1 — nasłuch, mikrofon i sterowanie

Poprawki zachowują holograficzny HUD, dotychczasowy głos, backend i uprawnienia. Nasłuch pozostaje lokalny. Nie zmieniono modelu polskiej frazy na angielski ani nie uruchomiono ciągłego STT w chmurze.

## Przyczyna komunikatu o przeciążeniu

Wersja 0.5.0 wysyłała z AudioWorklet ramkę 1280 próbek co 80 ms bez potwierdzenia jej przetworzenia. Krótkie zatrzymanie renderera lub IPC mogło dostarczyć kilka ramek naraz i przekroczyć małą kolejkę. Pięć takich zdarzeń mogło wyłączyć detektor nawet wtedy, gdy sam model liczył szybko. Komunikat „Komputer nie nadąża” nie rozróżniał tych sytuacji.

Wersja 0.5.1:

- Czeka na potwierdzenie inferencji przed wysłaniem następnej ramki. Bufor w AudioWorklet mieści najwyżej 1,6 s, a awaryjna kolejka usługi ma najwyżej 12 ramek. Opóźnienia nie tworzą nieograniczonej kolejki IPC.
- Po rzeczywistej utracie fragmentu audio zeruje kontekst frazy, zamiast łączyć nieciągłe słowa. Same spiętrzenia IPC nie powodują komunikatu o zbyt wolnym modelu.
- Wykrywa trwałe przeciążenie na podstawie zmierzonego czasu obliczeń i długu wobec budżetu 80 ms; zatrzymany silnik nadal ma watchdog 3 s. Błąd podpowiada rozmowę bez hasła.
- Po co najmniej 2,56 s niemal całkowitej ciszy (RMS poniżej 0,0005, około −66 dBFS) zachowuje cichy kontekst i pomija ciężkie inferencje. Każdy głośniejszy sygnał natychmiast wznawia pełne obliczenia. To prosty próg energii bez dodatkowego modelu VAD.

Silnik: ONNX Runtime Node 1.24.3, CPU, po jednym wątku obliczeń na sesję, ekstraktor Google/openWakeWord i mały polski klasyfikator MLP. Modele mają łącznie około 3 MB. Rozpoznawanie właściwego polecenia nadal wykorzystuje istniejący, krótki zapis w pamięci i `/stt` po świadomej aktywacji.

## Pomiary i ich granice

Powtarzalne polecenie w katalogu `desktop`:

```sh
npm run bench:wake
```

Porównanie 100 ramek w czasie rzeczywistym (8 sekund ciszy), Linux, Node 24.19.0, AMD EPYC 9V74:

| Pomiar | Pełne obliczenia | Oszczędzanie w ciszy |
|---|---:|---:|
| Wywołania sieci neuronowych | 100 | 32 |
| Średni czas obsługi ramki | 2,85 ms | 0,92 ms |
| p95 czasu obsługi ramki | 4,71 ms | 3,20 ms |
| CPU wątku po rozgrzaniu, procent jednego rdzenia | 3,62% | 0,44% |
| Sterta JS wątku na końcu | 8,54 MiB | 7,25 MiB |
| RSS całego procesu testowego na końcu | 83,13 MiB | 83,68 MiB |

To pomiar ciszy na procesorze chmurowym, nie wynik z Celerona N4000. RSS obejmuje cały proces testowy, a nie wyłącznie model. Nie jest to RAM całej aplikacji Electron. Sterta JS nie obejmuje wszystkich natywnych buforów ONNX. W hałasie i podczas mowy pełny model pracuje częściej, więc oszczędność będzie inna. Krótkie, kolejne uruchomienia nie dowodzą zmniejszenia RAM.

## Panel diagnostyczny

Ustawienia → „Diagnostyka · mikrofon, nasłuch i chmura” pokazuje:

- CPU komputera, liczbę procesorów logicznych i RAM urządzenia;
- średni/p95 czas obsługi ramki, CPU wątku, stertę JS, RSS procesu głównego i sumę zestawów roboczych procesów Desktop;
- liczbę ramek, pominięć ciszy, odrzuconych ramek, kolejkę i opóźnienie przechwytywania;
- ostatnie zdarzenia techniczne, bez treści audio, poleceń, tokenów i adresu API.

Brak pomiaru CPU na nieobsługiwanej platformie jest oznaczony wprost. Czasy ramki obejmują tanią ścieżkę ciszy; do oceny trudnej mowy odczytaj je także podczas wypowiadania fraz. Dane są w pamięci procesu; nie powstaje plik nagrania ani automatyczna wysyłka raportu.

„Testuj mikrofon lokalnie” zatrzymuje wake-word, sprawdza urządzenie przez 5 sekund, pokazuje prawdziwy RMS/szczyt i częstotliwości próbkowania, po czym zwalnia mikrofon. Nie wywołuje API i nie zapisuje dźwięku. Można przerwać test natychmiast. Odebranie zgody lub brak wybranego urządzenia daje osobny komunikat.

„Testuj połączenie API” sprawdza zapisany endpoint i token, rozróżnia błędny token, brak modelu, problem sieci/certyfikatu i nieprawidłowy serwer. W instalacji wymagane pozostaje HTTPS, walidacja certyfikatu i zakaz przekierowań. HTTP localhost jest wyjątkiem wyłącznie w niepakowanej wersji deweloperskiej. Klucze/tokeny nie trafiają do diagnostyki ani komunikatów IPC.

## Rozmowa bez hasła

1. Zapisz poprawny adres chmury i token, następnie sprawdź API.
2. W diagnostyce wybierz „Rozmowa głosowa bez hasła”. Detektor zostanie zatrzymany, a czat uruchomi zwykły mikrofon.
3. Powiedz polecenie, zakończ nagranie przyciskiem mikrofonu, sprawdź transkrypcję i wybierz „Wyślij”.
4. STT/AI i opcjonalny chmurowy TTS korzystają z dotychczasowych usług i mogą naliczać ich zwykłe koszty. Testy automatyczne korzystają wyłącznie z lokalnych odpowiedników API.

## Sterowanie komputerem

Osobny przełącznik „Włącz sterowanie lokalne” zapisuje zgodę od razu i nie wymaga wypełnienia formularza API. Domyślnie pozostaje wyłączony. Próba użycia skrótu przy wyłączonej zgodzie otwiera Ustawienia i wyjaśnia, jak ją włączyć, zamiast wyświetlać techniczny prefiks błędu Electron.

Włączenie przełącznika nie wykonuje operacji. Każde otwarcie Chrome, Dokumentów lub projektu nadal wymaga uwierzytelnienia w chmurze i osobnego zatwierdzenia. IPC ma tę samą zamkniętą listę operacji i weryfikuje nadawcę. Zgoda jest sprawdzana ponownie po odpowiedzi chmury i po oknie zatwierdzania — cofnięcie jej blokuje oczekującą akcję. Formularz konfiguracji chmury nie może sam nadać tej zgody.

## Próba na Windows 10 / N4000 / 4 GB

Nie mamy logów ani dostępu do fizycznego laptopa właściciela. Po zainstalowaniu wersji testowej:

1. Użyj trybu oszczędnego grafiki i wykonaj lokalny test wybranego mikrofonu.
2. Włącz nasłuch, zmierz 30 s ciszy oraz mowę przy otwartym i zminimalizowanym oknie; odczytaj raport oraz Menedżer zadań.
3. Sprawdź obie frazy z różnych odległości i podczas typowego obciążenia. Porównaj czas ramki z budżetem 80 ms i sprawdź odrzucenia; zgłoś raport bez sekretów, jeśli problem pozostaje.
4. Sprawdź rozmowę bez hasła, odłączenie mikrofonu, blokadę/wznowienie i natychmiastowe zatrzymanie.

Jeżeli pełne obliczenia na tym laptopie nadal stale przekraczają budżet, lżejszym następnym krokiem jest własny mały model KWS int8 z cechami FFT/MFCC i lokalnym buforem przed mową. Wymaga treningu i testów polskiej frazy oraz porównania na CPU bez AVX. Gotowy angielski `hey_jarvis` i mały Vosk miały w repozytorium słabe wyniki polskiego hasła. Do czasu takiej walidacji rozmowa przyciskiem pozostaje działającą alternatywą, z pełnym STT w chmurze.
