"""
Ocena modelu openWakeWord "hey_jarvis" dla polskiej wymowy.

Generuje nagrania syntezatorem Piper (polskie i angielskie głosy), dodaje ciszę i szum,
puszcza je strumieniowo (ramki 1280 próbek = 80 ms, 16 kHz) przez openWakeWord
i zapisuje maksymalny wynik detektora dla każdej frazy.

To jest pomiar na mowie syntetycznej — NIE zastępuje testu na prawdziwym głosie właściciela.
"""
import json
import os
import subprocess
import sys
import wave

import numpy as np
from scipy.signal import resample_poly

import openwakeword
from openwakeword.model import Model

VOICES_DIR = sys.argv[1]
OUT_DIR = sys.argv[2]
os.makedirs(OUT_DIR, exist_ok=True)

VOICES = {
    "pl_gosia": "pl_PL-gosia-medium",
    "pl_darkman": "pl_PL-darkman-medium",
    "pl_mc_speech": "pl_PL-mc_speech-medium",
    "en_lessac": "en_US-lessac-medium",
    "en_ryan": "en_US-ryan-medium",
}

# (tekst, oczekiwanie): True = powinno wybudzić, False = nie powinno
PL_PHRASES = [
    ("Jarvis.", True),
    ("Dżarwis.", True),
    ("Dżarwis, która godzina?", True),
    ("Hej Jarvis.", True),
    ("Hej, Dżarwis.", True),
    ("Hej Dżarwis, włącz muzykę.", True),
    ("Dzień dobry, jak się masz?", False),
    ("Jaka jest dzisiaj pogoda w Krakowie?", False),
    ("Jarosław wraca jutro do domu.", False),
    ("Na jarmarku było dużo ludzi.", False),
    ("Garaż jest otwarty, a samochód stoi na podjeździe.", False),
    ("Zadzwoń do mamy i powiedz, że będę później.", False),
]
EN_PHRASES = [
    ("Hey Jarvis.", True),
    ("Jarvis.", True),
    ("Hey Jarvis, what time is it?", True),
    ("Good morning, how are you today?", False),
]
LENGTH_SCALES = [0.9, 1.0, 1.15]


def synth(model_path, text, length_scale, out_wav):
    subprocess.run(
        ["piper", "--model", model_path, "--output_file", out_wav, "--length_scale", str(length_scale)],
        input=text.encode("utf-8"),
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    with wave.open(out_wav, "rb") as w:
        sr = w.getframerate()
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32)
    if sr != 16000:
        g = np.gcd(sr, 16000)
        pcm = resample_poly(pcm, 16000 // g, sr // g)
    return pcm


def with_padding_and_noise(pcm, snr_db, rng):
    pad = np.zeros(16000, dtype=np.float32)
    x = np.concatenate([pad, pcm, pad])
    if snr_db is not None:
        sig_power = np.mean(pcm ** 2) + 1e-9
        noise_power = sig_power / (10 ** (snr_db / 10))
        x = x + rng.normal(0, np.sqrt(noise_power), size=x.shape)
    return np.clip(x, -32768, 32767).astype(np.int16)


def max_score(model, audio):
    model.reset()
    best = 0.0
    for i in range(0, len(audio) - 1280 + 1, 1280):
        pred = model.predict(audio[i:i + 1280])
        best = max(best, float(pred["hey_jarvis"]))
    return best


def main():
    openwakeword.utils.download_models(model_names=["hey_jarvis"])
    model = Model(wakeword_models=["hey_jarvis"], inference_framework="onnx")
    rng = np.random.default_rng(42)
    rows = []
    for vkey, vname in VOICES.items():
        model_path = os.path.join(VOICES_DIR, vname + ".onnx")
        phrases = PL_PHRASES if vkey.startswith("pl_") else EN_PHRASES
        for text, expected in phrases:
            for ls in LENGTH_SCALES:
                pcm = synth(model_path, text, ls, os.path.join(OUT_DIR, "tmp.wav"))
                for snr in (None, 10):
                    audio = with_padding_and_noise(pcm, snr, rng)
                    s = max_score(model, audio)
                    rows.append({
                        "voice": vkey, "text": text, "expected": expected,
                        "length_scale": ls, "snr_db": snr, "max_score": round(s, 4),
                    })
                    print(f"{vkey:13s} ls={ls:<4} snr={str(snr):4s} exp={int(expected)} score={s:.3f}  {text}")

    with open(os.path.join(OUT_DIR, "results.json"), "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=1)

    # Podsumowanie: dla każdej frazy — średni i minimalny wynik oraz odsetek wykryć przy progach 0.3 / 0.5
    summary = {}
    for r in rows:
        key = ("pl" if r["voice"].startswith("pl_") else "en", r["text"], r["expected"])
        summary.setdefault(key, []).append(r["max_score"])
    lines = ["| Język | Fraza | Powinno wybudzić | Średni wynik | Min | Wykrycia ≥0.3 | Wykrycia ≥0.5 |", "|---|---|---|---|---|---|---|"]
    for (lang, text, exp), scores in summary.items():
        a = np.array(scores)
        lines.append(
            f"| {lang} | {text} | {'tak' if exp else 'nie'} | {a.mean():.2f} | {a.min():.2f} | "
            f"{(a >= 0.3).sum()}/{len(a)} | {(a >= 0.5).sum()}/{len(a)} |"
        )
    table = "\n".join(lines)
    with open(os.path.join(OUT_DIR, "summary.md"), "w", encoding="utf-8") as f:
        f.write(table + "\n")
    print(table)

    # Adnotacje (czytelne przez API GitHuba) — zwięźle, po jednej na grupę (limit adnotacji na krok).
    groups = {}
    for (lang, text, exp), scores in summary.items():
        a = np.array(scores)
        groups.setdefault((lang, exp), []).append(
            f"{text} śr={a.mean():.2f} min={a.min():.2f} max={a.max():.2f} ≥0.3={(a >= 0.3).sum()}/{len(a)} ≥0.5={(a >= 0.5).sum()}/{len(a)}"
        )
    for (lang, exp), items in groups.items():
        print(f"::notice title=WW {lang} {'POZYTYWNE' if exp else 'NEGATYWNE'}::" + " || ".join(items))

if __name__ == "__main__":
    main()
