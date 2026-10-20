// Jarvis backend — Cloudflare Worker.
// Pośredniczy między aplikacją Android a modelem AI. Klucz API jest tylko tutaj (sekret ANTHROPIC_API_KEY).
//
// Zmienne środowiskowe:
//   ANTHROPIC_API_KEY  (sekret, wymagany)  — klucz API modelu Claude
//   APP_TOKEN          (sekret, wymagany)  — wspólny token z aplikacją. Bez niego /chat odrzuca KAŻDE żądanie (503),
//                                            żeby nikt obcy nie zużywał Twojego klucza.
//   MODEL              (opcjonalny)        — domyślnie claude-haiku-5-5 (szybki, tani — dobry do rozmowy głosowej);
//                                            nazwa sprawdzona z listą modeli API 20.10.2026.
//
// Worker niczego nie loguje — ani treści rozmów, ani nagłówków, ani kluczy.

const ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
export const DEFAULT_MODEL = "claude-haiku-5-5";
export const MAX_MESSAGES = 20;
export const MAX_CHARS = 12000;
export const MAX_IMAGE_B64 = 2_000_000;
/** Największe poprawne żądanie z aplikacji to ok. 2,2 MB (zdjęcie + historia); powyżej — 413. */
export const MAX_BODY_BYTES = 3_000_000;
export const MAX_ACTIONS = 5;
const MAX_TOOL_TEXT = 2000;
const TOOL_ID_RE = /^[A-Za-z0-9_-]{1,64}$/;
const IMAGE_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);

export const SYSTEM_PROMPT = [
  "Jesteś Jarvis — osobisty asystent AI Huberta.",
  "Zawsze odpowiadasz po polsku, naturalnie i zwięźle (zwykle 1–4 zdania), bo odpowiedzi są czytane na głos.",
  "Nie używaj Markdown, list wypunktowanych, tabel ani emoji.",
  "Gdy użytkownik dołączy zdjęcie z kamery, opisz je i odpowiedz na pytanie. Nie masz pamięci między rozmowami, internetu w czasie rzeczywistym ani dostępu do komputera użytkownika — nie udawaj, że je masz.",
  "Jeśli czegoś nie wiesz, powiedz to wprost.",
].join(" ");

/** Dodatek do instrukcji, gdy aplikacja udostępnia narzędzia działań na telefonie. */
export const TOOLS_PROMPT = [
  "Masz narzędzia do wykonywania działań na telefonie użytkownika.",
  "Gdy użytkownik prosi o działanie, które obsługuje narzędzie, użyj go zamiast opisywać, jak to zrobić.",
  "Nie twierdź, że coś zostało zrobione, dopóki nie dostaniesz wyniku narzędzia.",
  "Po wyniku powiedz jednym krótkim zdaniem, co zrobiłeś albo co się nie udało i dlaczego.",
].join(" ");

/**
 * Narzędzia LOKALNE: backend tylko je proponuje, wykonuje je aplikacja na telefonie
 * (po własnej walidacji i — gdy trzeba — po potwierdzeniu użytkownika).
 * Model może użyć wyłącznie narzędzi z tej listy, które zgłosi aplikacja.
 */
export const TOOL_DEFS = {
  open_app: {
    description:
      "Otwiera aplikację lub grę zainstalowaną na telefonie użytkownika. Użyj, gdy użytkownik prosi o otwarcie, " +
      "uruchomienie lub włączenie aplikacji albo gry. Telefon sam dopasuje nazwę do zainstalowanych aplikacji.",
    input_schema: {
      type: "object",
      properties: {
        name: {
          type: "string",
          description:
            "Nazwa aplikacji lub gry w mianowniku, bez słów „aplikacja”/„gra”, np. „Spotify”, „Mapy Google” " +
            "(a nie „Mapach Google”).",
          maxLength: 80,
        },
      },
      required: ["name"],
      additionalProperties: false,
    },
  },
};

/** Lista narzędzi zgłoszonych przez aplikację, ograniczona do znanych backendowi. */
export function parseClientTools(input) {
  if (!Array.isArray(input)) return [];
  return [...new Set(input.filter((t) => typeof t === "string" && Object.hasOwn(TOOL_DEFS, t)))];
}

function isPlainObject(v) {
  return v !== null && typeof v === "object" && !Array.isArray(v);
}

/**
 * Wyniki narzędzi odesłane przez aplikację: {actions:[{id,tool,args}], results:[{id,ok,output}]}.
 * Zwraca null (brak), {error} (błędne dane) albo {calls, results}.
 */
export function parsePending(input, allowedTools) {
  if (input === undefined || input === null) return null;
  if (!isPlainObject(input) || !Array.isArray(input.actions) || !Array.isArray(input.results)) {
    return { error: "Nieprawidłowe wyniki narzędzi" };
  }
  const { actions, results } = input;
  if (actions.length === 0 || actions.length > MAX_ACTIONS || results.length !== actions.length) {
    return { error: "Nieprawidłowa liczba wyników narzędzi" };
  }
  const calls = [];
  for (const a of actions) {
    if (!isPlainObject(a) || typeof a.id !== "string" || !TOOL_ID_RE.test(a.id)) return { error: "Nieprawidłowe id narzędzia" };
    if (!allowedTools.includes(a.tool)) return { error: "Nieznane narzędzie" };
    if (!isPlainObject(a.args) || JSON.stringify(a.args).length > MAX_TOOL_TEXT) return { error: "Nieprawidłowe parametry narzędzia" };
    calls.push({ id: a.id, tool: a.tool, args: a.args });
  }
  const out = [];
  for (const c of calls) {
    const r = results.find((x) => isPlainObject(x) && x.id === c.id);
    if (!r || typeof r.ok !== "boolean") return { error: "Brak wyniku narzędzia" };
    const output = typeof r.output === "string" ? r.output.slice(0, MAX_TOOL_TEXT) : "";
    out.push({ id: c.id, ok: r.ok, output });
  }
  return { calls, results: out };
}

/** Dokleja do rozmowy wywołania narzędzi (asystent) i ich wyniki (użytkownik) w formacie API modelu. */
export function withToolResults(modelMessages, pending) {
  if (!pending) return modelMessages;
  return [
    ...modelMessages,
    {
      role: "assistant",
      content: pending.calls.map((c) => ({ type: "tool_use", id: c.id, name: c.tool, input: c.args })),
    },
    {
      role: "user",
      content: pending.results.map((r) => ({
        type: "tool_result",
        tool_use_id: r.id,
        content: r.output || (r.ok ? "Wykonano." : "Nie udało się."),
        is_error: !r.ok,
      })),
    },
  ];
}

/** Wywołania narzędzi z odpowiedzi modelu — tylko dozwolone, z parametrami w postaci obiektu. */
export function extractActions(data, allowedTools) {
  if (!data || !Array.isArray(data.content)) return [];
  return data.content
    .filter((b) => b && b.type === "tool_use" && allowedTools.includes(b.name) && isPlainObject(b.input) && TOOL_ID_RE.test(String(b.id)))
    .slice(0, MAX_ACTIONS)
    .map((b) => ({ id: b.id, tool: b.name, args: b.input }));
}

// Bez nagłówków CORS: aplikacja Android nie jest przeglądarką, a strony WWW nie powinny wołać tego API.
const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
};

function json(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}

/**
 * Czyści sekret wklejony z telefonu: usuwa białe i niewidoczne znaki z początku i końca
 * (spacje, nowe linie, twardą spację, znaki zerowej szerokości, BOM).
 */
export function cleanSecret(value) {
  return String(value ?? "").replace(/^[\s\u00a0\u200b-\u200d\u2060\ufeff]+|[\s\u00a0\u200b-\u200d\u2060\ufeff]+$/g, "");
}

/** Klucz nadaje się do nagłówka HTTP tylko z widocznymi znakami ASCII. */
export function isHeaderSafe(value) {
  return /^[\x21-\x7e]+$/.test(value);
}

/** Porównanie w stałym czasie (nie zdradza, ile znaków tokenu się zgadza). */
export function safeEqual(a, b) {
  const enc = new TextEncoder();
  const x = enc.encode(String(a));
  const y = enc.encode(String(b));
  let diff = x.length ^ y.length;
  const n = Math.max(x.length, y.length);
  for (let i = 0; i < n; i++) diff |= (x[i] ?? 0) ^ (y[i] ?? 0);
  return diff === 0;
}

/**
 * "ok"      — token z aplikacji zgodny,
 * "invalid" — brak tokenu w żądaniu albo błędny,
 * "missing" — serwer nie ma ustawionego APP_TOKEN (wtedy /chat zawsze odmawia).
 */
export function authState(request, env) {
  const expected = cleanSecret(env.APP_TOKEN);
  if (!expected) return "missing";
  const given = cleanSecret(request.headers.get("x-jarvis-token"));
  if (!given) return "invalid";
  return safeEqual(given, expected) ? "ok" : "invalid";
}

/**
 * Waliduje i porządkuje historię: tylko role user/assistant, przycięta długość,
 * sklejone kolejne wiadomości tej samej roli, start i koniec na wiadomości użytkownika.
 */
export function normalizeMessages(input) {
  if (!Array.isArray(input)) return null;
  const cleaned = [];
  for (const m of input.slice(-MAX_MESSAGES)) {
    if (!m || (m.role !== "user" && m.role !== "assistant")) continue;
    if (typeof m.content !== "string") continue;
    const content = m.content.trim().slice(0, MAX_CHARS);
    if (!content) continue;
    const image = validImage(m);
    const last = cleaned[cleaned.length - 1];
    if (last && last.role === m.role) {
      last.content = `${last.content}\n${content}`;
      if (image) last.image = image;
    } else {
      cleaned.push(image ? { role: m.role, content, image } : { role: m.role, content });
    }
  }
  while (cleaned.length && cleaned[0].role !== "user") cleaned.shift();
  while (cleaned.length && cleaned[cleaned.length - 1].role !== "user") cleaned.pop();
  return cleaned.length ? cleaned : null;
}

function validImage(m) {
  if (m.role !== "user" || !m.image || typeof m.image !== "object") return null;
  const { media_type, data } = m.image;
  if (!IMAGE_TYPES.has(media_type) || typeof data !== "string" || !data || data.length > MAX_IMAGE_B64) return null;
  if (!/^[A-Za-z0-9+/=]+$/.test(data)) return null;
  return { media_type, data };
}

/** Zamienia znormalizowane wiadomości na format API modelu (zdjęcie jako blok image). */
export function toModelMessages(messages) {
  return messages.map((m) =>
    m.image
      ? {
          role: m.role,
          content: [
            { type: "image", source: { type: "base64", media_type: m.image.media_type, data: m.image.data } },
            { type: "text", text: m.content },
          ],
        }
      : { role: m.role, content: m.content },
  );
}

export function extractText(data) {
  if (!data || !Array.isArray(data.content)) return "";
  return data.content
    .filter((b) => b && b.type === "text" && typeof b.text === "string")
    .map((b) => b.text)
    .join("")
    .trim();
}

async function handleChat(request, env) {
  const auth = authState(request, env);
  if (auth === "missing") return json({ error: "Serwer nie ma skonfigurowanego tokenu aplikacji" }, 503);
  if (auth !== "ok") return json({ error: "Nieprawidłowy token aplikacji" }, 401);
  const apiKey = cleanSecret(env.ANTHROPIC_API_KEY);
  if (!apiKey) return json({ error: "Serwer nie ma skonfigurowanego klucza API modelu" }, 503);
  if (!isHeaderSafe(apiKey)) {
    return json({ error: "Klucz API modelu zawiera niedozwolone znaki — wklej go ponownie w sekretach" }, 503);
  }

  const declared = Number(request.headers.get("content-length") || 0);
  if (declared > MAX_BODY_BYTES) return json({ error: "Za duże żądanie (maks. 3 MB)" }, 413);
  let raw;
  try {
    raw = await request.text();
  } catch {
    return json({ error: "Nie udało się odczytać żądania" }, 400);
  }
  if (new TextEncoder().encode(raw).length > MAX_BODY_BYTES) return json({ error: "Za duże żądanie (maks. 3 MB)" }, 413);
  let body;
  try {
    body = JSON.parse(raw);
  } catch {
    return json({ error: "Nieprawidłowy JSON" }, 400);
  }
  const messages = normalizeMessages(body && body.messages);
  if (!messages) return json({ error: "Brak wiadomości od użytkownika" }, 400);
  const tools = parseClientTools(body.tools);
  const pending = parsePending(body.pending, tools);
  if (pending && pending.error) return json({ error: pending.error }, 400);

  let upstream;
  try {
    upstream = await fetch(ANTHROPIC_URL, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-api-key": apiKey,
        "anthropic-version": "2023-06-01",
      },
      body: JSON.stringify({
        model: env.MODEL || DEFAULT_MODEL,
        max_tokens: 600,
        system: tools.length ? `${SYSTEM_PROMPT} ${TOOLS_PROMPT}` : SYSTEM_PROMPT,
        messages: withToolResults(toModelMessages(messages), pending),
        ...(tools.length
          ? { tools: tools.map((name) => ({ name, description: TOOL_DEFS[name].description, input_schema: TOOL_DEFS[name].input_schema })) }
          : {}),
      }),
    });
  } catch (e) {
    // Tylko typ błędu (np. TypeError) — bez treści, która mogłaby zawierać fragment klucza.
    const kind = e && typeof e.name === "string" ? e.name.replace(/[^A-Za-z]/g, "").slice(0, 40) : "";
    return json({ error: `Nie udało się połączyć z modelem AI${kind ? ` (${kind})` : ""}` }, 502);
  }

  if (!upstream.ok) {
    // Nie przekazujemy szczegółów odpowiedzi dostawcy do aplikacji — tylko kod.
    const hint = upstream.status === 401 ? " (sprawdź klucz API)" : upstream.status === 429 ? " (limit zapytań)" : "";
    return json({ error: `Model AI zwrócił błąd ${upstream.status}${hint}` }, 502);
  }

  let data;
  try {
    data = await upstream.json();
  } catch {
    return json({ error: "Nieprawidłowa odpowiedź modelu AI" }, 502);
  }
  const reply = extractText(data);
  const actions = extractActions(data, tools);
  if (!reply && !actions.length) return json({ error: "Model AI zwrócił pustą odpowiedź" }, 502);
  return json(actions.length ? { reply, actions } : { reply });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204 });

    if (url.pathname === "/health" && request.method === "GET") {
      return json({
        ok: true,
        configured: Boolean(cleanSecret(env.ANTHROPIC_API_KEY)),
        key_format_ok: isHeaderSafe(cleanSecret(env.ANTHROPIC_API_KEY)),
        model: env.MODEL || DEFAULT_MODEL,
        auth: authState(request, env),
      });
    }
    if (url.pathname === "/chat" && request.method === "POST") {
      return handleChat(request, env);
    }
    return json({ error: "Nie znaleziono" }, 404);
  },
};
