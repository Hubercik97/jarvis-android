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
if (!health.configured) fail("Test backendu", "Serwer nie ma klucza ANTHROPIC_API_KEY.");
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
