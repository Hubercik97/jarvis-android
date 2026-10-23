"""Reproducible synthetic Polish wake-phrase experiment. No user recordings.

Usage: python train.py CACHE_DIR OUTPUT_DIR
Requires numpy/scipy/onnxruntime/piper-tts/scikit-learn/skl2onnx.
Piper CC0 voice archives and openWakeWord Apache-2.0 feature models are
listed in sources.json. Evaluation uses different synthesis rates/noise seeds.
Synthetic results are NOT physical distance tests or a production FAR estimate.
"""
import os
os.environ.setdefault('OMP_NUM_THREADS', '1')
os.environ.setdefault('OPENBLAS_NUM_THREADS', '1')
import json, sys, hashlib, wave
from pathlib import Path
import numpy as np
import onnxruntime as ort
from piper import PiperVoice, SynthesisConfig
from scipy.signal import resample_poly, fftconvolve
from sklearn.neural_network import MLPClassifier
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler
from skl2onnx import to_onnx

CACHE, OUT = map(Path, sys.argv[1:3]); OUT.mkdir(parents=True, exist_ok=True)
RNG = np.random.default_rng(3741)
ort.set_seed(3741)
OPTIONS = ort.SessionOptions(); OPTIONS.intra_op_num_threads = 1; OPTIONS.inter_op_num_threads = 1
MEL = ort.InferenceSession(str(CACHE/'features/melspectrogram.onnx'), OPTIONS)
EMB = ort.InferenceSession(str(CACHE/'features/embedding_model.onnx'), OPTIONS)
WINDOW = 24
POS = ['Pobudka Jarvis.', 'Pobudka, Dżarwis.', 'Pobudka Dżarvis!', 'Pobudka, Jarwis.']
NEG = [
 'Pobudka.', 'Jarvis.', 'Dżarwis.', 'Hej Jarvis.', 'Jestem, słucham cię.',
 'Pobudka Jarek.', 'Pobudka Jarosław.', 'Pobudka Karol.', 'Pobudka Jadziu.',
 'Budka Jarvisa.', 'Pobudka zaraz.', 'Pobudka jutro rano.', 'Pobudka Warszawa.',
 'Pobudka, już czas wstawać.', 'Pobudka serwis.', 'Pobudka dla wszystkich.',
 'Jarvis przygotuj raport.', 'Jarosław wraca jutro do domu.',
 'Na jarmarku było dużo ludzi.', 'Garaż jest otwarty, a samochód stoi na podjeździe.',
 'Zadzwoń do mamy i powiedz, że będę później.', 'Jaka jest pogoda w Krakowie?',
 'Dzień dobry jak się masz?', 'Otwórz Chrome.', 'Sprawdź stan mojego projektu.',
 'Zaraz wracam, poczekaj chwilę.', 'W filmie robot rozmawia z człowiekiem.',
 'To nie jest komenda, tylko zwykła rozmowa.', 'Pobudka, Darwin.',
 'Dzisiaj porozmawiamy o rozwoju firmy i nowych projektach.',
 'Przed nami długa droga, trzeba będzie wcześniej wstać.',
 'Nie mam dostępu do mikrofonu. Włącz go w ustawieniach Windows.',
 'Raport jest gotowy. Możesz go przeczytać w panelu zadań.',
 'Dobranoc. Jutro będzie słoneczna pogoda.',
]
NEG += [
 'Pobudka Jasiu.', 'Pobudka Kasiu.', 'Pobudka, zaraz wstaję.',
 'Jak działa ten serwis?', 'Budka z lodami stoi obok garażu.',
 'Jarek i Jadwiga wrócą wieczorem.', 'Posłuchaj radia i sprawdź wiadomości.',
 'Zamknij okno, bo robi się zimno.', 'Przygotuję dokumenty do pracy.',
 'Dżarwis słucha polecenia.', 'Pobudka, Jarosławie.',
 'Chciałem zapytać, czy jutro mamy spotkanie.',
]
UNSEEN_NEG = [
 'Pobudka Darek.', 'Pobudka, wracaj do pracy.', 'Pobudka Karwowski.',
 'Przygotuj proszę kartę pracy na jutro.', 'Niech ktoś zadzwoni do Jarka.',
 'Włącz radio i poczekaj na komunikat.', 'Kawa stoi na biurku, a obok są dokumenty.',
 'Wczoraj rozmawialiśmy o zmianach w projekcie.', 'Słucham cię, powiedz co mam zrobić.',
 'Jarvis otwórz folder Dokumenty.', 'Pan Jarosław zamknął drzwi do garażu.',
 'Spokojnie, wszystko jest gotowe do pracy.',
]

def synth(voice, text, rate):
    chunks=list(voice.synthesize(text,SynthesisConfig(length_scale=rate,noise_scale=.45,noise_w_scale=.6)))
    x=np.concatenate([np.frombuffer(c.audio_int16_bytes,dtype=np.int16) for c in chunks]).astype(np.float32)
    sr=chunks[0].sample_rate; g=np.gcd(sr,16000)
    return resample_poly(x,16000//g,sr//g)

def augment(x, seed, noisy=True):
    r=np.random.default_rng(seed)
    x=x*r.uniform(.25,1.0)
    if noisy:
        impulse=np.zeros(3500,dtype=np.float32); impulse[0]=1
        for delay in [370,860,1700,2900]: impulse[delay]=r.uniform(.02,.13)
        x=fftconvolve(x,impulse)[:len(x)]
    # Enough pre-roll for the 24-embedding warm-up, including short utterances.
    pre=int(r.uniform(1.8,2.3)*16000)
    x=np.concatenate([np.zeros(pre),x,np.zeros(24000)])
    if noisy:x+=r.normal(0,r.uniform(20,250),len(x))
    return np.clip(x,-32768,32767).astype(np.float32),pre

def features(x):
    # Matches wake-worker.cts: previous 480 PCM samples + each 1280 samples.
    mel=np.ones((76,32),np.float32); prev=np.zeros(480,np.float32); rows=[]
    for i in range(0,len(x)-1279,1280):
        chunk=x[i:i+1280]; inp=np.concatenate([prev,chunk]); prev=chunk[-480:]
        m=MEL.run(None,{'input':inp[None,:]})[0].reshape(-1,32)/10+2
        mel=np.concatenate([mel,m])[-76:]
        rows.append(mel.copy())
    batch=np.array(rows,dtype=np.float32)[...,None]
    return EMB.run(None,{'input_1':batch})[0].reshape(len(rows),96)

def windows(f):
    return np.array([f[i-WINDOW:i].reshape(-1) for i in range(WINDOW,len(f)+1)],np.float32)

train_x=[];train_y=[];train_weight=[];tests=[];samples=[]
for voice_index,name in enumerate(['darkman','mc_speech','gosia']):
    model=CACHE/f'vits-piper-pl_PL-{name}-medium-int8'/f'pl_PL-{name}-medium.onnx'
    # PiperVoice.load uses unconstrained ORT threads. Supply a single-thread session.
    from piper.config import PiperConfig
    voice=PiperVoice(ort.InferenceSession(str(model),OPTIONS),PiperConfig.from_dict(json.load(open(str(model)+'.json'))))
    for expected,phrases in [(True,POS),(False,NEG+UNSEEN_NEG)]:
        for pi,text in enumerate(phrases):
            for ri,rate in enumerate([.80,1.0,1.20,.93,.87,1.08]):
                key=f'{name}-{int(expected)}-{pi}-{ri}'
                cached=CACHE/'speech'/f'{key}.npy';cached.parent.mkdir(exist_ok=True)
                raw=np.load(cached) if cached.exists() else synth(voice,text,rate)
                if not cached.exists():np.save(cached,raw)
                unseen=not expected and pi>=len(NEG)
                eval_only=ri>=3 or unseen
                if unseen and ri<3:continue
                for aug in range(3 if eval_only else (5 if expected else 3)):
                    seed=voice_index*100000+pi*1000+ri*100+aug+(500000 if expected else 0)
                    x,pre=augment(raw,seed,aug>0)
                    feature_file=CACHE/'features-cache-v2'/f'{key}-{aug}.npy'
                    feature_file.parent.mkdir(exist_ok=True)
                    f=np.load(feature_file) if feature_file.exists() else features(x)
                    if not feature_file.exists():np.save(feature_file,f)
                    w=windows(f)
                    if eval_only:
                        tests.append((name,text,expected,aug,w,unseen,'development' if ri==3 else 'test'))
                        if aug==0 and text in [POS[0],POS[1],NEG[0],NEG[4],NEG[5]]:
                            samples.append((key,x,expected,text,name))
                    elif expected:
                        end=(pre+len(raw))/1280
                        # Embeddings see the preceding 760 ms. Mark complete phrase
                        # windows after its end, never the incomplete first word.
                        ix=[j for j in range(len(w)) if end+.1<=j+WINDOW<=end+8]
                        train_x.extend(w[ix]);train_y.extend([1]*len(ix))
                        train_weight.extend([2.0]*len(ix))
                        # Early/empty windows must not wake on the first half of the phrase.
                        ix=[j for j in range(len(w)) if j+WINDOW<end-2]
                        train_x.extend(w[ix]);train_y.extend([0]*len(ix))
                        train_weight.extend([1.0]*len(ix))
                    else:
                        # Multiple positions in complete non-keyword speech.
                        train_x.extend(w);train_y.extend([0]*len(w))
                        weight=8.0 if text=='Pobudka, Darwin.' else 2.0 if text.startswith('Pobudka') else 1.0
                        train_weight.extend([weight]*len(w))
    print(f'{name}: training windows {len(train_y)}, test clips {len(tests)}',flush=True)

# Noise and silence guard examples, including high amplitude background noise.
for i in range(50):
    x=RNG.normal(0,RNG.uniform(0,2200),64000).astype(np.float32)
    w=windows(features(x));train_x.extend(w[::2]);train_y.extend([0]*len(w[::2]))
    train_weight.extend([1.0]*len(w[::2]))
x=np.array(train_x,np.float32);y=np.array(train_y)
print('Training',x.shape,'positive',int(y.sum()),flush=True)
from threadpoolctl import threadpool_limits
with threadpool_limits(limits=1):
    classifier=make_pipeline(StandardScaler(),MLPClassifier(hidden_layer_sizes=(64,32),alpha=1,early_stopping=True,validation_fraction=.12,max_iter=200,random_state=3741))
    # Retain the negative prior: this is a rare-event detector, so false accepts
    # matter more than maximal recall on synthetic phrases.
    classifier.fit(x,y,mlpclassifier__sample_weight=np.array(train_weight))
onnx=to_onnx(classifier,x[:1],options={id(classifier[-1]):{'zipmap':False}},target_opset=17)
onnx.doc_string='Jarvis custom Polish phrase classifier; 24x96 speech embeddings; Apache-2.0; synthetic training only, physical microphone calibration required.'
meta=onnx.metadata_props.add();meta.key='license';meta.value='Apache-2.0'
(OUT/'pobudka-jarvis.onnx').write_bytes(onnx.SerializeToString())
exported=ort.InferenceSession(str(OUT/'pobudka-jarvis.onnx'),OPTIONS)
parity_error=0.
rows=[]
for name,text,expected,aug,w,unseen,split in tests:
    p=classifier.predict_proba(w)[:,1]
    runtime=exported.run(None,{exported.get_inputs()[0].name:w})[1][:,1]
    parity_error=max(parity_error,float(np.max(np.abs(runtime-p))))
    if parity_error>.0001:raise RuntimeError(f'ONNX export mismatch: {parity_error}')
    # Two adjacent frames suppress isolated spikes; same rule as the runtime.
    score=float(np.max(np.minimum(p[1:],p[:-1]))) if len(p)>1 else 0.
    rows.append(dict(voice=name,text=text,expected=expected,noise=bool(aug),augmentation=aug,unseen_text=unseen,split=split,score=round(score,6)))
threshold=.90
report={'seed':3741,'window':WINDOW,'threshold':threshold,'train_windows':len(y),'positive_windows':int(y.sum()),
 'model_version':'0.1-beta','hidden_layers':[64,32],
 'selection_note':'Held-out clips were excluded from weight fitting but used to compare model variants and operating thresholds; this is development diagnostics, not an untouched final benchmark.',
 'onnx_probability_max_error':parity_error,'model_sha256':hashlib.sha256((OUT/'pobudka-jarvis.onnx').read_bytes()).hexdigest(),
 'method':'synthetic Piper; development rate 0.93, test rates 0.87/1.08; 12 unseen negative phrases; clean and two noise/reverb seeds; two consecutive frames; synthetic speakers also occur in training; not a physical distance or real-speech FAR test',
 'positive_hits':sum(r['score']>=threshold for r in rows if r['expected']),
 'positive_count':sum(r['expected'] for r in rows),
 'false_activations':sum(r['score']>=threshold for r in rows if not r['expected']),
 'negative_count':sum(not r['expected'] for r in rows),'rows':rows}
report['by_split']={}
for split in ['development','test']:
    selected=[r for r in rows if r['split']==split]
    report['by_split'][split]={
      'positive_hits':sum(r['score']>=threshold for r in selected if r['expected']),
      'positive_count':sum(r['expected'] for r in selected),
      'false_activations':sum(r['score']>=threshold for r in selected if not r['expected']),
      'negative_count':sum(not r['expected'] for r in selected),
    }
report['threshold_sweep']=[{'threshold':t,
 'positive_hits':sum(r['score']>=t for r in rows if r['expected']),
 'false_activations':sum(r['score']>=t for r in rows if not r['expected'])}
 for t in [.6,.75,.85,.9]]
(OUT/'evaluation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
sample_dir=OUT/'fixtures';sample_dir.mkdir(exist_ok=True)
fixture_manifest=[]
for key,audio,expected,text,voice in samples:
    with wave.open(str(sample_dir/f'{key}.wav'),'wb') as w:
        w.setparams((1,2,16000,0,'NONE','not compressed'));w.writeframes(audio.astype('<i2').tobytes())
    fixture_manifest.append({'file':f'{key}.wav','expected':expected,'text':text,'voice':voice})
(sample_dir/'manifest.json').write_text(json.dumps(fixture_manifest,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k!='rows'},indent=2),flush=True)
