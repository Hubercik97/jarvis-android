"""
Ocena alternatywy: Vosk (offline, Apache 2.0) z polskim modelem vosk-model-small-pl-0.22
w trybie wykrywania słowa kluczowego (gramatyka ograniczona do wariantów „Jarvis”).

Te same nagrania syntetyczne (Piper) co w eval.py. Wynik orientacyjny — nie zastępuje
testu na prawdziwym głosie właściciela.
"""
import json
import os
import sys

import numpy as np
from vosk import KaldiRecognizer, Model, SetLogLevel

sys.path.insert(0, os.path.dirname(__file__))
from eval import EN_PHRASES, LENGTH_SCALES, PL_PHRASES, VOICES, synth, with_padding_and_noise  # noqa: E402

SetLogLevel(-1)
VOICES_DIR, MODEL_DIR, OUT_DIR = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(OUT_DIR, exist_ok=True)

CANDIDATES = ["jarvis", "dżarwis", "dżarvis", "jarwis", "dżarwiz", "dżerwis"]


def vocabulary():
    for rel in ("graph/words.txt", "words.txt"):
        p = os.path.join(MODEL_DIR, rel)
        if os.path.exists(p):
            with open(p, encoding="utf-8") as f:
                return {line.split()[0] for line in f if line.strip()}
    return None


def run(model, audio, grammar):
    rec = KaldiRecognizer(model, 16000, json.dumps(grammar, ensure_ascii=False)) if grammar else KaldiRecognizer(model, 16000)
    texts = []
    data = audio.tobytes()
    for i in range(0, len(data), 3200):
        if rec.AcceptWaveform(data[i:i + 3200]):
            texts.append(json.loads(rec.Result()).get("text", ""))
    texts.append(json.loads(rec.FinalResult()).get("text", ""))
    return " ".join(t for t in texts if t).strip()


def main():
    vocab = vocabulary()
    in_vocab = [w for w in CANDIDATES if vocab is None or w in vocab]
    print(f"::notice title=Vosk słownik::słownik={'brak pliku' if vocab is None else len(vocab)} "
          f"warianty w słowniku: {in_vocab or 'żaden'} / sprawdzane: {CANDIDATES}")
    grammar = in_vocab + ["[unk]"] if in_vocab else None

    model = Model(MODEL_DIR)
    rng = np.random.default_rng(42)
    stats = {}
    heard = {}
    for vkey, vname in VOICES.items():
        if not vkey.startswith("pl_"):
            continue
        model_path = os.path.join(VOICES_DIR, vname + ".onnx")
        for text, expected in PL_PHRASES:
            for ls in LENGTH_SCALES:
                pcm = synth(model_path, text, ls, os.path.join(OUT_DIR, "tmp.wav"))
                for snr in (None, 10):
                    audio = with_padding_and_noise(pcm, snr, rng)
                    free = run(model, audio, None)
                    heard.setdefault(text, set()).add(free.split(" ")[0] if free else "(cisza)")
                    hit = False
                    if grammar:
                        g = run(model, audio, grammar)
                        hit = any(w in g.split() for w in in_vocab)
                    stats.setdefault((text, expected), []).append(hit)
                    print(f"{vkey:13s} ls={ls} snr={snr} exp={int(expected)} hit={int(hit)} free='{free}'  {text}")

    pos, neg = [], []
    for (text, expected), hits in stats.items():
        item = f"{text} {sum(hits)}/{len(hits)}"
        (pos if expected else neg).append(item)
    print("::notice title=Vosk POZYTYWNE (wykrycia)::" + " || ".join(pos))
    print("::notice title=Vosk NEGATYWNE (fałszywe)::" + " || ".join(neg))
    print("::notice title=Vosk słyszy (pierwsze słowo, bez gramatyki)::" + " || ".join(
        f"{t} → {sorted(h)[:6]}" for t, h in heard.items() if any(k in t for k in ("Jarvis", "Dżarwis"))))
    with open(os.path.join(OUT_DIR, "vosk.json"), "w", encoding="utf-8") as f:
        json.dump({"in_vocab": in_vocab, "stats": {f"{k[0]}|{k[1]}": v for k, v in stats.items()},
                   "heard": {k: sorted(v) for k, v in heard.items()}}, f, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
