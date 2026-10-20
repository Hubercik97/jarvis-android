# Jarvis — wybudzanie słowem „Jarvis”, słuchawki Bluetooth, działanie w tle

Stan na 20.10.2026. Dokument rozdziela to, co **zmierzono**, od tego, co **zaprojektowano** i co **wymaga testu na Galaxy S21**.

## 1. Wymagania

- Lokalny detektor słowa, **bez klucza API** i bez wysyłania ciągłego dźwięku do chmury.
- Słowo „Jarvis” w **polskiej** wymowie („Jarvis”, „Dżarwis”).
- Po wykryciu: istniejące rozpoznawanie mowy `SpeechRecognizer` (pl-PL), jedna sesja naraz.
- Usługa pierwszoplanowa z widocznym powiadomieniem; bez omijania blokady ekranu i zabezpieczeń systemu.

## 2. Pomiar detektorów (CI, workflow „Jarvis — ocena wake word”)

Metoda: nagrania syntezatorem Piper — 3 polskie głosy (gosia, darkman, mc_speech) i 2 angielskie, po 3 tempa mowy,
czyste i z szumem (SNR 10 dB) → 18 prób na frazę PL, 12 na frazę EN. Detekcja strumieniowa (ramki 80 ms).
**To mowa syntetyczna — wynik orientacyjny, nie zastępuje testu na głosie właściciela.**

Kod: `tools/wakeword-eval/eval.py`, `tools/wakeword-eval/eval_vosk.py`.

### openWakeWord, model `hey_jarvis` (próg 0.5)

| Fraza | Język | Wykrycia ≥0.5 | Wykrycia ≥0.3 |
|---|---|---|---|
| „Jarvis.” | PL | **0/18** | 0/18 |
| „Dżarwis.” | PL | **0/18** | 0–2/18 |
| „Dżarwis, która godzina?” | PL | **0/18** | 0–2/18 |
| „Hej Jarvis.” | PL | 0–2/18 | 4–5/18 |
| „Hej, Dżarwis.” | PL | 8–9/18 | 11–14/18 |
| „Hej Dżarwis, włącz muzykę.” | PL | 8–10/18 | 12–13/18 |
| „Hey Jarvis.” | EN | 12/12 | 12/12 |
| „Jarvis.” | EN | 8/12 | 10/12 |
| 6 zdań polskich bez słowa „Jarvis” | PL | 0/108 (fałszywe) | 2/108 |

(Zakresy — dwa uruchomienia; model dodaje losowy szum przy starcie bufora.)

Wniosek: model jest trenowany na angielskim „hey jarvis” (potwierdza to jego dokumentacja). **Samo „Jarvis” po polsku praktycznie nie działa.**
„Hej Dżarwis” działa w około połowie prób — za słabo jako główne rozwiązanie.

Licencja: kod openWakeWord — Apache 2.0; gotowe modele — CC BY-NC-SA 4.0 (tylko użytek niekomercyjny).

### Vosk, polski model `vosk-model-small-pl-0.22` (gramatyka ograniczona do wariantów „Jarvis”)

| Fraza | Wykrycia |
|---|---|
| wszystkie 6 fraz z „Jarvis/Dżarwis” | **0/18** każda |
| 6 zdań kontrolnych | 0/18 fałszywych |

Wniosek: słowa „jarvis” i jego polskich zapisów nie ma w słowniku małego modelu — bez własnego słownika i grafu Vosk ich nie rozpozna. Bez gramatyki model słyszy „jar”, „żar”, „darwin”, „charlie”.

### Odrzucone

- **Porcupine (Picovoice)** — wymaga klucza AccessKey w aplikacji (sprzeczne z zasadą „żadnych kluczy w APK”).
- **Systemowy detektor słowa Androida** (`HotwordDetectionService`) — dostępny tylko dla domyślnego asystenta systemowego, nie dla zwykłej aplikacji.

## 3. Alternatywy do decyzji właściciela

1. **Własny model openWakeWord dla polskiego „Dżarwis”** (rekomendowane). Ten sam lekki mechanizm (melspektrogram + embedding Google na Apache 2.0) z własnym klasyfikatorem wytrenowanym na polskich próbkach syntetycznych (Piper) z szumem i pogłosem, a do weryfikacji — 20–50 nagrań głosu właściciela. Trening w chmurze (GitHub Actions lub Google Colab z telefonu). Ryzyko: jakość zależy od danych; potrzebna weryfikacja na prawdziwym głosie.
2. **„Hej Dżarwis” z obecnym modelem jako tryb eksperymentalny** — szybko, ale fraza inna niż wymagana i ok. 50% skuteczności w pomiarze.
3. **Bez ciągłego nasłuchu**: przycisk słuchawek Bluetooth (sesja multimediów) albo boczny klawisz Samsunga („Klawisz boczny → Naciśnij dwukrotnie → Otwórz aplikację → Jarvis”) + opcja „słuchaj od razu po otwarciu”. Zero zużycia baterii na nasłuch, ale wymaga gestu.

## 4. Projekt usługi w tle (do wdrożenia po wyborze detektora)

Logika stanów jest już zaimplementowana i przetestowana: `core/WakeCycle.kt` (12 testów w `WakeCycleTest`).

- **Usługa pierwszoplanowa** `WakeWordService`, `android:foregroundServiceType="microphone"`, uprawnienia `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` (+ istniejące `RECORD_AUDIO`, `POST_NOTIFICATIONS`).
- **Start tylko z otwartej aplikacji** (przełącznik w Ustawieniach). Android pozwala usłudze używać mikrofonu tylko, gdy została uruchomiona, kiedy aplikacja była na ekranie (uprawnienie „podczas używania”). Dlatego `START_NOT_STICKY` — po zabiciu przez system usługa **nie** wraca sama z tła (i tak dostałaby ciszę z mikrofonu); użytkownik dostaje powiadomienie.
- **Powiadomienie stałe**: „Mikrofon aktywny — czekam na słowo „Jarvis”” + przycisk „Wyłącz”. Android 12+ pokazuje dodatkowo zieloną kropkę mikrofonu.
- **Jedno nagrywanie naraz**: detektor (`AudioRecord`, 16 kHz) jest zatrzymywany przed startem `SpeechRecognizer`; kolejne wybudzenie w trakcie polecenia jest ignorowane (gwarantuje `WakeCycle`).
- **Aplikacja na ekranie** → nasłuch wstrzymany (aplikacja ma własny mikrofon); po zejściu w tło — wznowienie.
- **Mikrofon zajęty** (rozmowa telefoniczna, inna aplikacja) → pauza i ponowienie; błędy detektora → ponowienia 2 s, 4 s, 8 s… maks. 5 prób, potem wyłączenie z komunikatem.
- **Ekran zablokowany**: Jarvis tylko odpowiada na pytania. Niczego nie odblokowuje i nie omija blokady. Każde przyszłe działanie wrażliwe (wysłanie wiadomości, otwarcie aplikacji, dane prywatne) będzie wymagało odblokowania telefonu (`KeyguardManager.requestDismissKeyguard`) i potwierdzenia.
- **Historia**: rozmowy z tła zapisywane przez ten sam `HistoryStore` (jeden egzemplarz na proces).

### Ograniczenia Samsunga / baterii

- One UI może usypiać aplikacje działające w tle. Wymagane: Ustawienia → Aplikacje → Jarvis → Bateria → **Bez ograniczeń**, oraz Ustawienia → Bateria → Limity użycia w tle → upewnij się, że Jarvis nie jest na liście „Uśpione aplikacje”.
- Ciągły nasłuch zużywa baterię (ciągłe przetwarzanie dźwięku). Rzeczywiste zużycie — do zmierzenia na S21.
- **Nie twierdzimy**, że wybudzanie działa przy zablokowanym lub wygaszonym ekranie, dopóki nie zostanie to sprawdzone na S21.

## 5. Słuchawki Bluetooth (zaimplementowane)

- `voice/BluetoothAudioRouter.kt` + `core/BluetoothRoutePolicy.kt` (testy: `BluetoothRoutePolicyTest`).
- Na czas słuchania polecenia: Android 12+ → `AudioManager.setCommunicationDevice(słuchawki SCO/BLE)`; Android 8–11 → `startBluetoothSco()`. Po zakończeniu słuchania łącze jest zwalniane.
- Głos Jarvisa (`TextToSpeech`) ma atrybuty `USAGE_ASSISTANT` / `CONTENT_TYPE_SPEECH` → system kieruje go tam, gdzie multimedia, czyli do podłączonych słuchawek (A2DP).
- Ustawienie: Ustawienia → Głos → „Mikrofon słuchawek Bluetooth” (domyślnie włączone; bez słuchawek nic nie zmienia).
- Uprawnienie `MODIFY_AUDIO_SETTINGS` (zwykłe, przyznawane automatycznie). Nie jest potrzebne `BLUETOOTH_CONNECT` — nazwa słuchawek może się nie wyświetlać.
- **Ograniczenie**: rozpoznawaniem mowy zajmuje się usługa Google; to, czy pobiera dźwięk z mikrofonu słuchawek po przełączeniu, zależy od telefonu i usługi — **wymaga testu na S21**.

## 6. Prywatność mikrofonu

- Rozpoznawanie polecenia: dźwięk przetwarza systemowa usługa mowy Google (jak dotąd).
- Detektor słowa (po wdrożeniu): przetwarzanie wyłącznie na telefonie, nic nie jest wysyłane ani zapisywane.
- Aplikacja nie zapisuje nagrań. Do backendu trafia tylko rozpoznany tekst.
