// Głos Jarvisa w chmurze — Google Cloud Text-to-Speech, głosy „Chirp 3: HD” (naturalne, polskie).
// WYŁĄCZONE, dopóki nie ma sekretu GOOGLE_TTS_API_KEY (płatna usługa z darmowym limitem — wymaga zgody właściciela).
// Klucz tylko na serwerze. Tekst do syntezy nie jest logowany ani zapisywany.

export const GOOGLE_TTS_URL = "https://texttospeech.googleapis.com/v1/text:synthesize";
export const MAX_TTS_CHARS = 1500;

/** Męskie głosy Chirp 3 HD (lista z dokumentacji Google; polskie: pl-PL-Chirp3-HD-<Imię>). */
export const MALE_VOICES = [
  "Charon", "Orus", "Fenrir", "Iapetus", "Algenib", "Alnilam", "Achird", "Algieba",
  "Enceladus", "Puck", "Rasalgethi", "Sadachbia", "Sadaltager", "Schedar", "Umbriel", "Zubenelgenubi",
];
export const DEFAULT_VOICE = "Charon";

export function ttsEnabled(env) {
  return typeof env.GOOGLE_TTS_API_KEY === "string" && env.GOOGLE_TTS_API_KEY.trim().length > 0;
}

/** Tekst do syntezy: bez znaczników źródeł [n] i adresów, maks. MAX_TTS_CHARS. */
export function speakableText(text) {
  return String(text ?? "")
    .replace(/\s?\[\d+(?:[,\s–-]+\d+)*\]/g, "")
    .replace(/https?:\/\/\S+/g, "")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, MAX_TTS_CHARS);
}

/** POST /tts {text, voice?, rate?} → {audio: <base64 MP3>, voice, chars}. Autoryzację sprawdza wywołujący. */
export async function handleTts(request, env, json) {
  if (!ttsEnabled(env)) return json({ error: "Głos w chmurze jest wyłączony (brak klucza Google TTS na serwerze)", enabled: false }, 503);
  let body;
  try {
    body = await request.json();
  } catch {
    return json({ error: "Nieprawidłowy JSON" }, 400);
  }
  const text = speakableText(body && body.text);
  if (!text) return json({ error: "Brak tekstu do przeczytania" }, 400);
  const voice = MALE_VOICES.includes(body?.voice) ? body.voice : DEFAULT_VOICE;
  const rate = Number(body?.rate);
  const speakingRate = Number.isFinite(rate) ? Math.min(1.3, Math.max(0.75, rate)) : 1.0;

  let res;
  try {
    res = await fetch(`${GOOGLE_TTS_URL}?key=${encodeURIComponent(env.GOOGLE_TTS_API_KEY.trim())}`, {
      method: "POST",
      signal: AbortSignal.timeout(20_000),
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        input: { text },
        voice: { languageCode: "pl-PL", name: `pl-PL-Chirp3-HD-${voice}` },
        audioConfig: { audioEncoding: "MP3", speakingRate },
      }),
    });
  } catch {
    return json({ error: "Nie udało się połączyć z usługą głosu" }, 502);
  }
  if (!res.ok) {
    const hint = res.status === 403 ? " (sprawdź klucz lub włączenie Text-to-Speech API i rozliczeń w Google Cloud)" : res.status === 429 ? " (limit)" : "";
    return json({ error: `Usługa głosu zwróciła błąd ${res.status}${hint}` }, 502);
  }
  let data;
  try {
    data = await res.json();
  } catch {
    return json({ error: "Nieprawidłowa odpowiedź usługi głosu" }, 502);
  }
  if (typeof data?.audioContent !== "string" || !data.audioContent) return json({ error: "Pusta odpowiedź usługi głosu" }, 502);
  return json({ audio: data.audioContent, format: "mp3", voice, chars: text.length });
}
