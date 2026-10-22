// Test dymny wdrożonego backendu (uruchamiany w GitHub Actions po wdrożeniu).
// Wymaga zmiennych: BACKEND_URL, APP_TOKEN. Nigdy nie wypisuje tokenu ani klucza.
// Wynik trafia do adnotacji workflowu (::notice / ::error).

const url = (process.env.BACKEND_URL || "").replace(/\/+$/, "");
const token = (process.env.APP_TOKEN || "").trim();

function notice(title, msg) {
  console.log(`::notice title=${title}::${msg.replace(/\r?\n/g, " ")}`);
}
function fail(title, msg) {
  console.log(`::error title=${title}::${msg.replace(/\r?\n/g, " ")}`);
  process.exit(1);
}

if (!/^https:\/\/[a-z0-9.-]+$/i.test(url)) fail("Test backendu", "Brak poprawnego adresu backendu po wdrożeniu.");
if (!token) fail("Test backendu", "Brak sekretu JARVIS_APP_TOKEN.");

async function call(path, init, attempts = 6) {
  let last;
  for (let i = 0; i < attempts; i++) {
    try {
      const res = await fetch(url + path, init);
      if (res.status !== 404 && res.status < 500) return res;
      last = `HTTP ${res.status}`;
      if (res.status === 503 || res.status === 502) return res;
    } catch (e) {
      last = e.message;
    }
    await new Promise((r) => setTimeout(r, 5000)); // nowo wdrożony Worker potrzebuje chwili
  }
  fail("Test backendu", `Brak odpowiedzi z ${path}: ${last}`);
}

const headers = { "x-jarvis-token": token };

let health;
for (let i = 0; i < 6; i++) {
  const h = await call("/health", { headers });
  health = await h.json();
  if (health.auth !== "missing") break;
  await new Promise((r) => setTimeout(r, 5000)); // świeżo ustawiony sekret potrzebuje chwili
}
notice("Backend /health", `ok=${health.ok} klucz_modelu=${health.configured} format_klucza=${health.key_format_ok} model=${health.model} token=${health.auth}`);
if (!health.configured) fail("Test backendu", "Serwer nie ma skonfigurowanego klucza wybranego dostawcy AI.");
if (health.auth !== "ok") fail("Test backendu", `Token aplikacji nie działa (stan: ${health.auth}).`);

const c = await call("/chat", {
  method: "POST",
  headers: { ...headers, "content-type": "application/json" },
  body: JSON.stringify({
    messages: [{ role: "user", content: "To test połączenia. Odpowiedz jednym krótkim zdaniem po polsku, że działasz." }],
    client: { app: "jarvis-smoke-test" },
  }),
});
const body = await c.json();
if (!c.ok || !body.reply) fail("Test backendu", `/chat nie zwrócił odpowiedzi AI (HTTP ${c.status}): ${body.error || "brak treści"}`);
notice("Odpowiedź AI z backendu", body.reply.slice(0, 200));

// Odrzucenie żądania bez tokenu (zabezpieczenie).
const bad = await fetch(url + "/chat", {
  method: "POST",
  headers: { "content-type": "application/json" },
  body: JSON.stringify({ messages: [{ role: "user", content: "x" }] }),
});
if (bad.status !== 401) fail("Test backendu", `Żądanie bez tokenu powinno dać 401, dało ${bad.status}.`);
notice("Zabezpieczenie", "Żądanie bez tokenu odrzucone (401).");

// Narzędzia: czy prawdziwy model proponuje open_app i czy rozumie wynik (ostrzeżenie zamiast błędu — model bywa niedeterministyczny).
const ask = await fetch(url + "/chat", {
  method: "POST",
  headers: { ...headers, "content-type": "application/json" },
  body: JSON.stringify({ messages: [{ role: "user", content: "Otwórz Spotify" }], tools: ["open_app"] }),
});
const askBody = await ask.json();
const action = Array.isArray(askBody.actions) ? askBody.actions[0] : null;
if (!ask.ok || !action || action.tool !== "open_app") {
  console.log(`::warning title=Narzędzia::Model nie zaproponował open_app (HTTP ${ask.status}): ${askBody.error || askBody.reply || "brak"}`);
} else {
  notice("Narzędzia: propozycja AI", `${action.tool} ${JSON.stringify(action.args)}`);
  const done = await fetch(url + "/chat", {
    method: "POST",
    headers: { ...headers, "content-type": "application/json" },
    body: JSON.stringify({
      messages: [{ role: "user", content: "Otwórz Spotify" }],
      tools: ["open_app"],
      pending: { actions: [action], results: [{ id: action.id, ok: true, output: "Otwarto aplikację: Spotify" }] },
    }),
  });
  const doneBody = await done.json();
  if (!done.ok || !doneBody.reply) fail("Narzędzia", `Odpowiedź po wyniku narzędzia: HTTP ${done.status} ${doneBody.error || ""}`);
  notice("Narzędzia: odpowiedź po wykonaniu", doneBody.reply.slice(0, 200));
}

// Etap D: czy model wybiera właściwe narzędzia i liczy czas z kontekstu telefonu (ostrzeżenia — model bywa niedeterministyczny).
const ALL_TOOLS = ["open_app", "set_alarm", "set_timer", "create_reminder", "add_calendar_event", "get_calendar_events", "flashlight", "open_settings"];
const client = { app: "jarvis-smoke-test", local_time: "wtorek, 20 października 2026, 20:59", tz: "Europe/Warsaw" };
for (const [prompt, expected] of [
  ["Ustaw minutnik na 10 minut", "set_timer"],
  ["Przypomnij mi jutro o ósmej, żeby zadzwonić do mechanika", "create_reminder"],
  ["Włącz latarkę", "flashlight"],
]) {
  const r = await fetch(url + "/chat", {
    method: "POST",
    headers: { ...headers, "content-type": "application/json" },
    body: JSON.stringify({ messages: [{ role: "user", content: prompt }], tools: ALL_TOOLS, client }),
  });
  const b = await r.json();
  const a = Array.isArray(b.actions) ? b.actions[0] : null;
  if (a && a.tool === expected) notice(`Etap D: ${expected}`, `„${prompt}” → ${JSON.stringify(a.args)}`);
  else console.log(`::warning title=Etap D: ${expected}::„${prompt}” → ${a ? a.tool + " " + JSON.stringify(a.args) : b.error || b.reply || "brak działania"}`);
}

// Pamięć: dodaj fakt testowy, odczytaj, usuń (nic nie zostaje w pamięci użytkownika).
if (health.memory) {
  const add = await (await fetch(url + "/memory", {
    method: "POST", headers: { ...headers, "content-type": "application/json" },
    body: JSON.stringify({ text: "Test dymny — do usunięcia" }),
  })).json();
  const list = await (await fetch(url + "/memory", { headers })).json();
  const found = Array.isArray(list.facts) && list.facts.some((f) => f.id === add.fact?.id);
  const del = await fetch(url + `/memory/${add.fact?.id}`, { method: "DELETE", headers });
  if (!add.ok || !found || del.status !== 200) fail("Pamięć", `dodanie=${add.ok} odczyt=${found} usunięcie=${del.status}`);
  notice("Pamięć", `KV działa (dodanie, odczyt, usunięcie OK; zapisanych faktów: ${list.facts.length - 1})`);
} else {
  console.log("::warning title=Pamięć::Backend działa bez pamięci (brak przestrzeni KV).");
}

// Wyszukiwanie w internecie. Gdy sekret TAVILY_API_KEY istnieje, czekamy na nową wersję Workera
// (sekrety ustawione po wdrożeniu rozchodzą się kilka–kilkadziesiąt sekund), potem PRAWDZIWE wyszukiwanie.
const expectSearch = process.env.EXPECT_SEARCH === "true";
if (expectSearch && !health.search) {
  for (let i = 0; i < 18 && !health.search; i++) {
    await new Promise((r) => setTimeout(r, 5000));
    health = await (await call("/health", { headers })).json();
  }
  if (!health.search) fail("Wyszukiwanie", "Sekret TAVILY_API_KEY jest w GitHub, ale Worker po 90 s nadal zgłasza search=false.");
}
if (health.search) {
  const questions = [
    "Sprawdź w internecie: jaki jest najnowszy średni kurs euro według NBP? Podaj źródło.",
    "Wyszukaj w internecie najnowsze wiadomości o Cloudflare Workers i podaj źródła.",
  ];
  let ok = false;
  for (const q of questions) {
    const r = await fetch(url + "/chat", {
      method: "POST", headers: { ...headers, "content-type": "application/json" },
      body: JSON.stringify({ messages: [{ role: "user", content: q }], tools: [], client }),
    });
    const b = await r.json();
    const src = Array.isArray(b.sources) ? b.sources : [];
    const info = `HTTP ${r.status} zapytań=${b.research?.searches ?? 0} stron=${b.research?.pages ?? 0} wywołań_modelu=${b.research?.model_calls ?? 0} źródeł=${src.length}`;
    if (r.ok && src.length > 0 && (b.research?.searches ?? 0) > 0) {
      const withSnippet = src.filter((s) => s.snippet).length;
      notice("Wyszukiwanie", `${info} (z opisem: ${withSnippet})`);
      notice("Wyszukiwanie — źródła", src.slice(0, 3).map((s) => `[${s.n}] ${String(s.title).slice(0, 60)} — ${new URL(s.url).hostname}${s.date ? " " + s.date : ""}`).join(" | "));
      notice("Wyszukiwanie — odpowiedź", String(b.reply).replace(/\s+/g, " ").slice(0, 220));
      ok = true;
      break;
    }
    console.log(`::warning title=Wyszukiwanie (próba)::${info}: ${b.error || String(b.reply || "").replace(/\s+/g, " ").slice(0, 200)}`);
  }
  if (!ok) fail("Wyszukiwanie", "Wyszukiwanie włączone, ale żadna z prób nie zwróciła źródeł z internetu.");
} else {
  console.log("::notice title=Wyszukiwanie::Wyłączone — brak sekretu TAVILY_API_KEY.");
}

// Głos w chmurze (tylko gdy jest klucz Google TTS). Krótki tekst — znikomy koszt w darmowym limicie.
if (health.tts) {
  const r = await fetch(url + "/tts", {
    method: "POST", headers: { ...headers, "content-type": "application/json" },
    body: JSON.stringify({ text: "Dzień dobry, tu Jarvis.", voice: "Charon", rate: 0.95 }),
  });
  const b = await r.json();
  if (!r.ok || !b.audio) fail("Głos w chmurze", `HTTP ${r.status}: ${b.error || "brak audio"}`);
  notice("Głos w chmurze", `głos=${b.voice} znaków=${b.chars} audio=${Math.round(b.audio.length * 0.75 / 1024)} KB`);
} else {
  console.log("::notice title=Głos w chmurze::Wyłączony — brak sekretu GOOGLE_TTS_API_KEY.");
}
