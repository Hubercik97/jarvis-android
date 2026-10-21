# Jarvis Cloud — architektura, koszty, limity

Stan: 21.10.2026, gałąź `feature/jarvis-cloud-assistant`. Wdrażane jest wyłącznie środowisko **testowe**
(`jarvis-backend-test`). Produkcja (`jarvis-backend`) nie jest wdrażana bez zgody właściciela.

## Architektura

```
Telefon (Kotlin/Compose)                 Cloudflare Workers (24/7, bez komputera)         Anthropic API
├─ mowa → tekst (Google / na telefonie) ─▶ POST /chat  ── token aplikacji ──▶ Claude (claude-haiku-5-5)
├─ narzędzia lokalne (rejestr, walidacja)◀─ "actions" (propozycje AI)
├─ głos Jarvisa (TextToSpeech pl-PL)      ├─ /memory  ── KV „jarvis-backend-test-memory”
└─ historia rozmów (na telefonie)         └─ /health
```

- Klucz modelu i token aplikacji: tylko sekrety GitHub → Cloudflare. W APK jest jedynie adres backendu.
- Rozpoznawanie mowy (telefon/Google) jest oddzielone od głosu Jarvisa (syntezator telefonu) i od AI (chmura).
- Na telefonie nie działa żaden ciężki model AI.

## Co działa w chmurze

| Element | Stan |
|---|---|
| Backend testowy `https://jarvis-backend-test.lunvra-golf.workers.dev` | ✅ wdrożony, test dymny przechodzi |
| Token aplikacji (fail-closed), limit 3 MB, brak logów | ✅ |
| Narzędzia telefonu (aplikacje, zegar, przypomnienia, kalendarz, latarka, ustawienia, pamięć) | ✅ backend; telefon — do testu |
| Pamięć faktów (KV) z podglądem i usuwaniem w aplikacji | ⚠️ kod gotowy, KV tworzone przy wdrożeniu |
| Ponawianie: backend → Anthropic (1× przy 429/5xx/529), telefon → backend (1× przy braku połączenia) | ✅ |
| Limit zapytań: 40/min na instancję Workera | ✅ (ochrona pomocnicza, nie globalna) |

## Pamięć

- Jarvis zapisuje fakt **tylko na wyraźną prośbę** („Jarvis, zapamiętaj, że…”) — narzędzie `remember_fact`.
- Maks. 50 faktów po 200 znaków, najnowsze pierwsze; przy przepełnieniu znika najstarszy (aplikacja o tym mówi).
- Podgląd i usuwanie: Ustawienia → **Pamięć Jarvisa** (pojedynczo i „Usuń wszystko”).
- API: `GET /memory`, `POST /memory {text}`, `DELETE /memory/<id>`, `DELETE /memory` — wszystkie wymagają tokenu.
- Fakty trafiają do AI przy każdej rozmowie, oznaczone jako dane (nie polecenia).
- Historia rozmów zostaje na telefonie (nie jest wysyłana do chmury poza bieżącą rozmową).

## Koszty i limity (darmowe plany)

**Cloudflare Workers Free** (źródło: dokumentacja Cloudflare, sprawdzone 21.10.2026):
- 100 000 zapytań dziennie (reset o północy UTC), 10 ms CPU na zapytanie — czekanie na API modelu i KV **nie** wlicza się do CPU.
- KV Free: 100 000 odczytów/dzień, 1 000 zapisów/dzień, 1 GB. Jarvis: 1 odczyt na wiadomość, zapis tylko przy „zapamiętaj”/usuwaniu.
- Przy jednym użytkowniku limity są praktycznie nieosiągalne → **0 zł**.

**Anthropic API** (jedyny płatny element, płatność za użycie):
- `claude-haiku-5-5`: od 0,10 USD / mln tokenów wejściowych i 0,50 USD / mln wyjściowych (cennik w konsoli).
- Szacunek (nie pomiar): jedno polecenie z narzędziem ≈ 2–4 tys. tokenów wejściowych (instrukcje + opisy narzędzi + historia)
  i ok. 100–200 wyjściowych → rząd **0,0005–0,001 USD**; 1 000 poleceń ≈ 0,5–1 USD. Rzeczywiste zużycie: konsola → Usage.
- **Zalecane:** ustaw miesięczny limit wydatków w konsoli Anthropic (Settings → Limits). To twarda granica kosztów
  także przy wycieku tokenu — limiter w Workerze jest tylko pomocniczy.

**Niewłączone płatne opcje** (wymagają zgody i przedstawienia kosztów przed użyciem):
- Wyszukiwanie w internecie przez Claude: 10 USD / 1 000 wyszukiwań + tokeny.
- Chmurowa synteza mowy (bardziej naturalny męski głos): płatna u dostawcy TTS; dziś używany jest darmowy syntezator telefonu
  z wyborem głosu, tempa i tonu.

## Słowo „Jarvis” i zablokowany ekran

Zob. [WAKE_WORD.md](WAKE_WORD.md). W skrócie: nasłuch w tle działa jako usługa pierwszoplanowa z powiadomieniem,
słowo jest wykrywane w tekście z rozpoznawania mowy. Działanie przy zablokowanym/wygaszonym ekranie **nie jest potwierdzone**
— wymaga testu na S21. Przy zablokowanym ekranie Jarvis nic nie odblokowuje; działania wrażliwe w tle są wyłączone.
Docelowo: własny detektor słowa (openWakeWord) wytrenowany na nagraniach właściciela — mniejsze zużycie baterii, bez dźwięków.

## Przygotowanie do biznesu (projekt — nic nie jest wykonywane)

Ten sam mechanizm narzędzi, ale wykonywanych **po stronie backendu** (klucze sklepu tylko w sekretach Cloudflare):

| Obszar | Narzędzia (planowane) | Ryzyko | Zasada |
|---|---|---|---|
| Shopify — odczyt | `shop_list_products`, `shop_get_orders`, `shop_stats` | odczyt | Shopify Admin API, token z zakresem tylko do odczytu |
| Produkty — analiza | `product_research` (wyszukiwanie w sieci) | koszt | Płatne wyszukiwanie — włączane osobno po zgodzie |
| Opisy i marketing | `draft_product_description`, `draft_post` | szkic | Wynik to **szkic** zapisany do zatwierdzenia, nigdy publikacja |
| Zmiany w sklepie | `shop_update_product`, `shop_create_discount` | CONFIRM | Kolejka zatwierdzeń: Jarvis przygotowuje zmianę → Ty zatwierdzasz w aplikacji → backend wykonuje |
| Zakupy, publikacje, usuwanie | — | zablokowane | Nie będą dostępne dla AI bez osobnej decyzji właściciela |

Wymagania przed startem: osobny token Shopify (najpierw `read_products`, `read_orders`), sekrety tylko w Cloudflare,
dziennik wykonanych działań, testy z atrapą API Shopify.

## Do skonfigurowania przez właściciela

1. Konsola Anthropic → **Limits**: miesięczny limit wydatków (np. 10 USD).
2. Po wdrożeniu testowym: zainstalować najnowsze APK i sprawdzić Ustawienia → Pamięć Jarvisa.
3. Decyzja o produkcji: wdrożenie `jarvis-backend` (osobny Worker, te same sekrety) — tylko po zgodzie.
