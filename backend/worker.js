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
const IMAGE_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);

export const SYSTEM_PROMPT = [
  "Jesteś Jarvis — osobisty asystent AI Huberta.",
  "Zawsze odpowiadasz po polsku, naturalnie i zwięźle (zwykle 1–4 zdania), bo odpowiedzi są czytane na głos.",
  "Nie używaj Markdown, list wypunktowanych, tabel ani emoji.",
  "Gdy użytkownik dołączy zdjęcie z kamery, opisz je i odpowiedz na pytanie. Nie masz pamięci między rozmowami, internetu w czasie rzeczywistym ani dostępu do komputera użytkownika — nie udawaj, że je masz.",
  "Jeśli czegoś nie wiesz, powiedz to wprost.",
].join(" ");

// Bez nagłówków CORS: aplikacja Android nie jest przeglądarką, a strony WWW nie powinny wołać tego API.
const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
};

function json(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
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
  if (!env.APP_TOKEN) return "missing";
  const given = request.headers.get("x-jarvis-token");
  if (!given) return "invalid";
  return safeEqual(given, env.APP_TOKEN) ? "ok" : "invalid";
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
  if (!env.ANTHROPIC_API_KEY) return json({ error: "Serwer nie ma skonfigurowanego klucza API modelu" }, 503);

  let body;
  try {
    body = await request.json();
  } catch {
    return json({ error: "Nieprawidłowy JSON" }, 400);
  }
  const messages = normalizeMessages(body && body.messages);
  if (!messages) return json({ error: "Brak wiadomości od użytkownika" }, 400);

  let upstream;
  try {
    upstream = await fetch(ANTHROPIC_URL, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-api-key": env.ANTHROPIC_API_KEY,
        "anthropic-version": "2023-06-01",
      },
      body: JSON.stringify({
        model: env.MODEL || DEFAULT_MODEL,
        max_tokens: 600,
        system: SYSTEM_PROMPT,
        messages: toModelMessages(messages),
      }),
    });
  } catch {
    return json({ error: "Nie udało się połączyć z modelem AI" }, 502);
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
  if (!reply) return json({ error: "Model AI zwrócił pustą odpowiedź" }, 502);
  return json({ reply });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204 });

    if (url.pathname === "/health" && request.method === "GET") {
      return json({
        ok: true,
        configured: Boolean(env.ANTHROPIC_API_KEY),
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
