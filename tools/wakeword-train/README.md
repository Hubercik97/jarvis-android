# Lokalny model „Pobudka Jarvis”

Detektor wykorzystuje lokalne cechy mowy Google/openWakeWord (Apache 2.0) oraz
własny mały klasyfikator MLP. Model rozpoznaje całą frazę, bez słownika ASR,
klucza API i przesyłania nasłuchu do chmury. Gotowy angielski model `hey_jarvis`
nie jest używany. Model ani syntezatory nie imitują głosu aktora.

Przygotowanie środowiska (Python 3.12, CPU):

```sh
python -m venv .venv
.venv/bin/pip install -r tools/wakeword-train/requirements.txt
.venv/bin/python tools/wakeword-train/fetch.py /tmp/jarvis-wake-training
OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 .venv/bin/python \
  tools/wakeword-train/train.py /tmp/jarvis-wake-training /tmp/jarvis-wake-output
```

Skrypt pobierania sprawdza SHA256 każdego pliku z `sources.json`. Pobiera około
65 MB publicznych danych, bez płatnego API. Rozpakowane głosy Piper służą tylko
do generowania danych treningowych. Nie trafiają do instalatora. Karty trzech
głosów wskazują CC0 dla danych źródłowych i znajdują się obok tego dokumentu.
Piper jest narzędziem treningowym; nie stanowi zależności wykonawczej aplikacji.

Pierwszy trening wytwarza i buforuje syntetyczną mowę oraz cechy w katalogu
cache. Ziarno klasyfikatora, augmentacji i ONNX Runtime wynosi 3741. Różne wersje
silnika lub platformy mogą zmienić syntezę; manifest gotowych plików jednoznacznie
identyfikuje dołączony model. Ponowne uruchomienie z tym samym cache zachowuje
dane i umożliwia porównanie zmian klasyfikatora.

Trening obejmuje frazy „Pobudka Jarvis”, „Pobudka Dżarwis” i warianty pisowni,
różne tempa, głośność, szum i symulowane odbicia. Negatywy obejmują samo
„Pobudka”, samo „Jarvis”, „Pobudka Jarek”, „Pobudka Jarosław”, potwierdzenie
„Jestem, słucham cię” i zwykłe zdania. Zbiór rozwojowy używa tempa 0,93,
a ocena odłożonych przykładów używa temp 0,87 i 1,08 oraz 12 dodatkowych zdań negatywnych.
Te same trzy syntetyczne głosy występują też
w treningu, więc wynik nie mierzy generalizacji na głos właściciela.
Przykłady odłożone nie trafiają do dopasowania wag, ale wyniki służyły do
porównywania wariantów modelu i wyboru progu. To diagnostyka rozwojowa,
nie nietknięty benchmark jakości po wdrożeniu.

Wynik detekcji wymaga przekroczenia progu w dwóch kolejnych ramkach. Próg
domyślny wynosi 0,90. Wynik klasyfikatora jest sygnałem modelu, a nie
skalibrowanym prawdopodobieństwem poprawnej detekcji. Pełne wyniki znajdują się
w `desktop/resources/wake/evaluation.json`. Test porównuje również
prawdopodobieństwa sklearn i wyeksportowanego ONNX, aby wykryć rozjazd eksportu.

Dołączony wariant `0.1-beta` ma przy progu 0,90 wynik **104/108** wykryć
syntetycznej frazy i **1/1566** fałszywych aktywacji na zdaniach negatywnych.
Jedna fałszywa aktywacja dotyczy czystej syntetycznej frazy „Pobudka, Darwin”
głosem `mc_speech`. W podzbiorze temp 0,87/1,08 wynik to 68/72 oraz 0/1044.
Przy progu 0,85 pełny wynik to 106/108 oraz 2/1566. Są to liczby prób,
nie wskaźnik fałszywych aktywacji na godzinę. Dołączone małe pliki WAV są
wybranym zestawem regresji (tempo 0,93), a pełna ocena obejmuje także próby
nieudane. Nie wolno wyciągać wniosku o pełnej niezawodności z samych testów WAV.

**Ograniczenia:** dane syntetyczne i symulowane odbicia nie zastępują nagrań
z odległości 0,5 / 2 / 4 m. Nie mierzą reakcji na telewizor, akcenty nieznanych
osób ani fałszywych aktywacji na godzinę. Potrzebne są próby na fizycznym
mikrofonie Windows oraz pomiary na Celeronie N4000. Fraza odtworzona z filmu
może aktywować każdy taki detektor; model nie uwierzytelnia osoby mówiącej.
